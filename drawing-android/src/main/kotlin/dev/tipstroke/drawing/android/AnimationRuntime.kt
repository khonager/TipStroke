package dev.tipstroke.drawing.android

import dev.tipstroke.core.drawing.CompletedStroke
import dev.tipstroke.core.drawing.StrokeSample
import dev.tipstroke.core.geometry.Point
import dev.tipstroke.core.model.AnimationFrame
import dev.tipstroke.core.model.AnimationTimeline
import dev.tipstroke.core.model.FrameId
import dev.tipstroke.core.model.ImageTransform
import dev.tipstroke.core.model.LayerId
import dev.tipstroke.core.model.PlaybackMode
import dev.tipstroke.core.model.RecordingTiming
import java.util.LinkedHashMap

data class AnimationUiState(
    val frames: List<AnimationFrame>,
    val selectedIndex: Int,
    val fps: Int,
    val playbackMode: PlaybackMode,
    val playing: Boolean,
    val onionBefore: Int,
    val onionAfter: Int,
    val onionOpacity: Float,
    val backgroundLayerIds: Set<LayerId>,
    val recording: AnimationRecordingKind? = null,
)

enum class AnimationRecordingKind { IMAGE_MOTION, LIVE_DRAWING }

/** Only the active cel is installed in LayerStack. Other cels keep their sparse stores. */
internal class AnimationRuntime(
    private val stack: LayerStack,
    existingAsBackground: Boolean,
    initialFrames: List<AnimationFrame> = listOf(AnimationFrame(FrameId.new())),
    initialFps: Int = 12,
    initialPlayback: PlaybackMode = PlaybackMode.LOOP,
) {
    val frames = initialFrames.toMutableList()
    var selectedIndex = 0
        private set
    var fps = initialFps.coerceIn(1, 60)
    var playbackMode = initialPlayback
    var onionBefore = 1
    var onionAfter = 1
    var onionOpacity = .3f
    val backgroundLayerIds = mutableSetOf<LayerId>()
    val rasterCels = LinkedHashMap<LayerId, MutableMap<FrameId, TileStore>>()
    val imageCels = LinkedHashMap<LayerId, MutableMap<FrameId, ImageTransform>>()

    init {
        stack.layers.forEach { layer ->
            if (existingAsBackground) backgroundLayerIds += layer.id
            else register(layer)
        }
    }

    val currentFrame: AnimationFrame get() = frames[selectedIndex]
    val timeline: AnimationTimeline get() = AnimationTimeline(frames.toList(), fps, playbackMode)

    fun register(layer: CanvasLayerRuntime) {
        if (layer.id in backgroundLayerIds || layer.id in rasterCels || layer.id in imageCels) return
        when (layer) {
            is RasterLayerRuntime -> rasterCels[layer.id] = linkedMapOf(currentFrame.id to layer.tiles)
            is ImageLayerRuntime -> imageCels[layer.id] = linkedMapOf(currentFrame.id to layer.transform)
        }
    }

    fun unregisterMissingLayers() {
        val ids = stack.layers.mapTo(mutableSetOf()) { it.id }
        backgroundLayerIds.retainAll(ids)
        val removed = rasterCels.filterKeys { it !in ids }.values.flatMap { it.values }
        rasterCels.keys.retainAll(ids)
        imageCels.keys.retainAll(ids)
        discardUnreferenced(removed)
    }

    fun isBackground(id: LayerId) = id in backgroundLayerIds

    fun setBackground(id: LayerId, background: Boolean) {
        val layer = stack.layers.firstOrNull { it.id == id } ?: return
        if (background == isBackground(id)) return
        saveActiveImageTransforms()
        if (background) {
            backgroundLayerIds += id
            layer.activeAtFrame = true
        } else {
            backgroundLayerIds -= id
            register(layer)
            when (layer) {
                is RasterLayerRuntime -> rasterCels.getOrPut(id) { linkedMapOf() }[currentFrame.id] = layer.tiles
                is ImageLayerRuntime -> imageCels.getOrPut(id) { linkedMapOf() }[currentFrame.id] = layer.transform
            }
        }
    }

    fun select(index: Int) {
        require(index in frames.indices)
        if (index == selectedIndex) return
        saveActiveImageTransforms()
        install(index)
    }

    fun addBlankFrame(): Int {
        frames.add(selectedIndex + 1, AnimationFrame(FrameId.new()))
        select(selectedIndex + 1)
        return selectedIndex
    }

    fun duplicateFrame(): Int {
        saveActiveImageTransforms()
        val duplicate = AnimationFrame(FrameId.new(), currentFrame.exposure)
        stack.layers.forEach { layer ->
            if (isBackground(layer.id)) return@forEach
            when (layer) {
                is RasterLayerRuntime -> rasterCels.getOrPut(layer.id) { linkedMapOf() }[duplicate.id] = layer.tiles.duplicate()
                is ImageLayerRuntime -> imageCels[layer.id]?.get(currentFrame.id)?.let { transform ->
                    imageCels.getOrPut(layer.id) { linkedMapOf() }[duplicate.id] = transform
                }
            }
        }
        frames.add(selectedIndex + 1, duplicate)
        select(selectedIndex + 1)
        return selectedIndex
    }

    fun deleteFrame() {
        if (frames.size == 1) return
        saveActiveImageTransforms()
        val removed = frames.removeAt(selectedIndex)
        val removedStores = rasterCels.values.mapNotNull { it.remove(removed.id) }
        imageCels.values.forEach { it.remove(removed.id) }
        install(selectedIndex.coerceAtMost(frames.lastIndex))
        discardUnreferenced(removedStores)
    }

    fun setExposure(value: Int) {
        frames[selectedIndex] = currentFrame.copy(exposure = value.coerceIn(1, 600))
    }

    fun moveFrame(index: Int, target: Int) {
        if (index !in frames.indices || target !in frames.indices || index == target) return
        val currentId = currentFrame.id
        frames.add(target, frames.removeAt(index))
        selectedIndex = frames.indexOfFirst { it.id == currentId }
    }

    fun celRaster(layerId: LayerId, frameId: FrameId): TileStore? = rasterCels[layerId]?.get(frameId)
    fun celImageTransform(layerId: LayerId, frameId: FrameId): ImageTransform? = imageCels[layerId]?.get(frameId)

    fun carryRecordedDrawing(layerId: LayerId, from: Int, to: Int) {
        if (from !in frames.indices || to !in frames.indices) return
        val cels = rasterCels[layerId] ?: return
        val previous = cels[frames[from].id] ?: return
        val copy = previous.duplicate()
        cels.put(frames[to].id, copy)?.discard()
        if (selectedIndex == to) {
            (stack.layers.firstOrNull { it.id == layerId } as? RasterLayerRuntime)?.tiles = copy
        }
    }

    fun inactiveTileBytes(): Long {
        val active = stack.layers.filterIsInstance<RasterLayerRuntime>().mapTo(mutableSetOf()) { it.tiles }
        val counted = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<TileStore, Boolean>())
        return rasterCels.values.flatMap { it.values }.filter { it !in active && counted.add(it) }
            .sumOf { it.allocatedTileBytes + it.history.estimatedBytes }
    }

    fun copySelectedRasterCelToNewLayer(move: Boolean): LayerId? {
        val source = stack.selectedRaster() ?: return null
        val sourceWasBackground = isBackground(source.id)
        val sourceStore = source.tiles
        val newId = stack.addRaster()
        val destination = stack.selectedRaster() ?: return null
        destination.tiles = if (move) sourceStore else sourceStore.duplicate()
        register(destination)
        rasterCels[destination.id]?.set(currentFrame.id, destination.tiles)
        if (move) {
            source.tiles = TileStore(stack.canvasWidth, stack.canvasHeight)
            if (!sourceWasBackground) rasterCels[source.id]?.set(currentFrame.id, source.tiles)
        }
        return newId
    }

    fun saveActiveImageTransforms() {
        val frameId = currentFrame.id
        stack.layers.filterIsInstance<ImageLayerRuntime>().forEach { layer ->
            if (!isBackground(layer.id) && layer.activeAtFrame) {
                imageCels.getOrPut(layer.id) { linkedMapOf() }[frameId] = layer.transform
            }
        }
    }

    fun makeImageCelAtCurrentFrame(id: LayerId) {
        val layer = stack.layers.firstOrNull { it.id == id } as? ImageLayerRuntime ?: return
        layer.activeAtFrame = true
        imageCels.getOrPut(id) { linkedMapOf() }[currentFrame.id] = layer.transform
    }

    fun recordImageMotion(layerId: LayerId, start: Int, end: Int, timing: RecordingTiming, samples: List<Pair<Long, ImageTransform>>) {
        if (samples.isEmpty() || start !in frames.indices || end !in frames.indices || start > end) return
        val cels = imageCels.getOrPut(layerId) { linkedMapOf() }
        val duration = (samples.last().first - samples.first().first).coerceAtLeast(1L)
        for (index in start..end) {
            val progress = (index - start).toFloat() / (end - start).coerceAtLeast(1)
            val wanted = if (timing == RecordingTiming.FIT_RANGE) (duration * progress).toLong()
                else ((index - start) * 1000L / fps)
            cels[frames[index].id] = interpolatedTransform(samples, samples.first().first + wanted)
        }
        install(start)
    }

    private fun interpolatedTransform(samples: List<Pair<Long, ImageTransform>>, time: Long): ImageTransform {
        if (time <= samples.first().first) return samples.first().second
        if (time >= samples.last().first) return samples.last().second
        val next = samples.indexOfFirst { it.first >= time }
        val a = samples[next - 1]
        val b = samples[next]
        val fraction = ((time - a.first).toFloat() / (b.first - a.first).coerceAtLeast(1L)).coerceIn(0f, 1f)
        fun lerp(x: Float, y: Float) = x + (y - x) * fraction
        return ImageTransform(
            lerp(a.second.centerX, b.second.centerX),
            lerp(a.second.centerY, b.second.centerY),
            lerp(a.second.scale, b.second.scale),
            lerp(a.second.rotationDegrees, b.second.rotationDegrees),
        )
    }

    fun recordStrokeReveal(layerId: LayerId, start: Int, end: Int, timing: RecordingTiming, stroke: CompletedStroke) {
        if (start !in frames.indices || end !in frames.indices || start > end) return
        val cels = rasterCels.getOrPut(layerId) { linkedMapOf() }
        val duration = stroke.samples.last().elapsedNanos.coerceAtLeast(1L)
        for (index in start..end) {
            val progress = (index - start + 1).toFloat() / (end - start + 1)
            val cutoff = if (timing == RecordingTiming.FIT_RANGE) (duration * progress).toLong()
                else ((index - start + 1) * 1_000_000_000L / fps).coerceAtMost(duration)
            val partial = if (index == end) stroke else stroke.copy(samples = prefixThrough(stroke.samples, cutoff))
            val store = TileStore(stack.canvasWidth, stack.canvasHeight)
            store.commit(partial)
            cels.put(frames[index].id, store)?.discard()
        }
        install(start)
    }

    /** Place each part of a live stroke in the frame that was playing when it was drawn. */
    fun recordLiveStroke(layerId: LayerId, start: Int, startedAt: Long, downAt: Long, playedThrough: Int, stroke: CompletedStroke) {
        if (stroke.samples.isEmpty() || start !in frames.indices) return
        val offsetNanos = (downAt - startedAt).coerceAtLeast(0L) * 1_000_000L
        val durationNanos = stroke.samples.last().elapsedNanos
        val first = start + (offsetNanos * fps / 1_000_000_000L).toInt()
        val last = start + ((offsetNanos + durationNanos) * fps / 1_000_000_000L).toInt()
        val cels = rasterCels.getOrPut(layerId) { linkedMapOf() }
        for (index in first..maxOf(last, playedThrough)) {
            val frameEnd = ((index - start + 1) * 1_000_000_000L / fps) - offsetNanos
            if (index in frames.indices) {
                val part = if (frameEnd >= durationNanos) stroke.samples else prefixThrough(stroke.samples, frameEnd)
                val store = cels.getOrPut(frames[index].id) { TileStore(stack.canvasWidth, stack.canvasHeight) }
                store.commit(stroke.copy(samples = part))
            }
        }
        install(selectedIndex)
    }

    private fun prefixThrough(samples: List<StrokeSample>, cutoff: Long): List<StrokeSample> {
        val prefix = samples.takeWhile { it.elapsedNanos <= cutoff }.toMutableList()
        if (prefix.isEmpty()) prefix += samples.first()
        val next = samples.firstOrNull { it.elapsedNanos > cutoff }
        if (next != null && prefix.last() !== next && prefix.last().elapsedNanos < cutoff) {
            val before = prefix.last()
            val t = ((cutoff - before.elapsedNanos).toFloat() /
                (next.elapsedNanos - before.elapsedNanos).coerceAtLeast(1L)).coerceIn(0f, 1f)
            prefix += before.copy(
                position = Point(
                    before.position.x + (next.position.x - before.position.x) * t,
                    before.position.y + (next.position.y - before.position.y) * t,
                ),
                elapsedNanos = cutoff,
            )
        }
        return prefix
    }

    fun state(playing: Boolean, recording: AnimationRecordingKind? = null) = AnimationUiState(
        frames.toList(), selectedIndex, fps, playbackMode, playing,
        onionBefore, onionAfter, onionOpacity, backgroundLayerIds.toSet(), recording,
    )

    fun snapshot(): SavedAnimationSnapshot {
        saveActiveImageTransforms()
        return SavedAnimationSnapshot(
            frames.toList(), selectedIndex, fps, playbackMode,
            onionBefore, onionAfter, onionOpacity, backgroundLayerIds.toSet(),
            rasterCels.mapValues { (_, cels) -> cels.mapValues { (_, store) -> store.snapshotTiles() } },
            imageCels.mapValues { (_, cels) -> cels.toMap() },
        )
    }

    fun restore(saved: SavedAnimationSnapshot) {
        backgroundLayerIds.clear()
        backgroundLayerIds += saved.backgroundLayerIds
        rasterCels.clear()
        saved.rasterCels.forEach { (layerId, cels) ->
            rasterCels[layerId] = cels.mapValuesTo(linkedMapOf()) { (_, tiles) ->
                TileStore(stack.canvasWidth, stack.canvasHeight).apply { replaceTiles(tiles) }
            }
        }
        imageCels.clear()
        saved.imageCels.forEach { (layerId, cels) -> imageCels[layerId] = cels.toMutableMap() }
        onionBefore = saved.onionBefore.coerceIn(0, 6)
        onionAfter = saved.onionAfter.coerceIn(0, 6)
        onionOpacity = saved.onionOpacity.coerceIn(0f, 1f)
        install(saved.selectedIndex.coerceIn(frames.indices))
    }

    private fun install(index: Int) {
        selectedIndex = index
        val id = currentFrame.id
        stack.layers.forEach { layer ->
            if (isBackground(layer.id)) {
                layer.activeAtFrame = true
            } else when (layer) {
                is RasterLayerRuntime -> {
                    layer.tiles = rasterCels.getOrPut(layer.id) { linkedMapOf() }
                        .getOrPut(id) { TileStore(stack.canvasWidth, stack.canvasHeight) }
                    layer.activeAtFrame = true
                }
                is ImageLayerRuntime -> {
                    val transform = imageCels[layer.id]?.get(id)
                    layer.activeAtFrame = transform != null
                    if (transform != null) layer.transform = transform
                }
            }
        }
    }

    private fun discardUnreferenced(candidates: Collection<TileStore>) {
        val referenced = stack.layers.filterIsInstance<RasterLayerRuntime>().mapTo(mutableSetOf()) { it.tiles }
        rasterCels.values.forEach { referenced += it.values }
        candidates.distinct().filter { it !in referenced }.forEach(TileStore::discard)
    }
}
