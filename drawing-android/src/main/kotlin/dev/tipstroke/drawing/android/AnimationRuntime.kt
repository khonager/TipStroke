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
import dev.tipstroke.core.model.MediaTimeline
import dev.tipstroke.core.model.VideoEdit
import dev.tipstroke.core.model.MediaClip
import android.graphics.Bitmap
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
    val thumbnailRevision: Int = 0,
    val loading: Boolean = false,
    val hasMedia: Boolean = false,
    val mediaPositionUs: Long = 0,
    val mediaDurationUs: Long = 0,
    val mediaDrawingActive: Boolean = true,
    val videoEdit: VideoEdit? = null,
    val photoAssetIds: Set<String> = emptySet(),
    val clipSourceDurationsUs: Map<String, Long> = emptyMap(),
    val canUndoVideoEdit: Boolean = false,
    val canRedoVideoEdit: Boolean = false,
    val paintedFrameIds: Set<FrameId> = emptySet(),
    val coveragePending: Boolean = false,
)

enum class AnimationRecordingKind { IMAGE_MOTION, LIVE_DRAWING }

/** Only the active cel is installed in LayerStack. Other cels keep their sparse stores. */
internal class AnimationRuntime(
    private val stack: LayerStack,
    existingAsBackground: Boolean,
    initialFrames: List<AnimationFrame> = listOf(AnimationFrame(FrameId.new())),
    initialFps: Int = 12,
    initialPlayback: PlaybackMode = PlaybackMode.LOOP,
    private val storage: CelStorage? = null,
    private val storageError: (Throwable) -> Unit = {},
) {
    private val editUndo = ArrayDeque<MediaSource>()
    private val editRedo = ArrayDeque<MediaSource>()
    fun replaceMedia(source: MediaSource, remember: Boolean = true) {
        if (remember) { media?.let { editUndo.addLast(it) }; while (editUndo.size > 50) editUndo.removeFirst(); editRedo.clear() }
        if (media == null) {
            var time = 0L
            frames.replaceAll { frame ->
                val duration = MediaTimeline.durationUs(frame, fps)
                frame.copy(sourceTimeUs = time, durationUs = duration).also { time += duration }
            }
        }
        mediaPreview?.clear()
        media = source
        mediaPreview = source.takeIf { it.kind != "video" }?.let(::MediaPreview)
        showMediaTime(mediaPositionUs)
    }
    fun undoVideoEdit(redo: Boolean) {
        val from = if (redo) editRedo else editUndo
        val to = if (redo) editUndo else editRedo
        if (from.isEmpty()) return
        media?.let(to::addLast)
        val restored = from.removeLast()
        // Keep immutable assets reachable across saves while history still references them.
        replaceMedia(if (restored.edit != null) restored.copy(assets = media!!.assets + restored.assets) else restored, false)
    }
    fun editingSource(): MediaSource? {
        val source = media ?: return null
        if (source.edit != null) return source
        val id = "original"
        val edit = VideoEdit(listOf(MediaClip(id, id, "Original ${source.kind}", 0, source.durationUs)))
        return MediaSource(source.file, "sequence", source.width, source.height, edit.durationUs, source.frameRate, source.canvasScale, edit = edit, assets = mapOf(id to source))
    }
    var media: MediaSource? = null
        private set
    var mediaPreview: MediaPreview? = null
        private set
    var videoColorPreview: Bitmap? = null
    var mediaPositionUs = 0L
        private set
    var mediaDrawingActive = true
        private set
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
        stack.mediaColorAt = { x, y ->
            val source = media
            val bitmap = videoColorPreview ?: mediaPreview?.takeIf { it.timeUs == mediaPositionUs }?.bitmap
            if (source == null || bitmap == null || x < 0 || y < 0 || x >= source.width * source.canvasScale || y >= source.height * source.canvasScale) 0
            else bitmap.getPixel((x * bitmap.width / (source.width * source.canvasScale)).toInt(), (y * bitmap.height / (source.height * source.canvasScale)).toInt())
        }
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

    fun resizeCanvas(width: Int, height: Int, offsetX: Int, offsetY: Int) {
        saveActiveImageTransforms()
        rasterCels.forEach { (layerId, cels) ->
            cels.replaceAll { _, store -> store.resized(width, height, offsetX, offsetY).also { store.discard() } }
            (stack.layers.firstOrNull { it.id == layerId } as? RasterLayerRuntime)?.let { layer ->
                layer.tiles = cels[currentFrame.id] ?: TileStore(width, height)
            }
        }
        imageCels.values.forEach { cels ->
            cels.replaceAll { _, transform -> transform.copy(
                centerX = transform.centerX + offsetX,
                centerY = transform.centerY + offsetY,
            ) }
        }
    }

    fun applyResolution(stores: Map<TileStore, TileStore>, factor: Float) {
        editUndo.clear(); editRedo.clear()
        rasterCels.values.forEach { cels -> cels.replaceAll { _, store -> stores.getValue(store) } }
        imageCels.values.forEach { cels -> cels.replaceAll { _, transform -> transform.reduced(factor) } }
        media = media?.let { it.copy(canvasScale = it.canvasScale * factor) }
    }

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

    fun mediaIndexAt(timeUs: Long) = MediaTimeline.frameAt(frames, timeUs, fps)

    /** Installs cels only at drawing boundaries, never for each decoded video frame. */
    fun showMediaTime(timeUs: Long): Boolean {
        val source = media ?: return false
        mediaPositionUs = timeUs.coerceIn(0, source.durationUs - 1)
        val index = mediaIndexAt(mediaPositionUs)
        val changed = index != (if (mediaDrawingActive) selectedIndex else -1)
        if (index >= 0) {
            if (changed) { saveActiveImageTransforms(); install(index) }
            mediaDrawingActive = true
        } else {
            if (mediaDrawingActive) saveActiveImageTransforms()
            mediaDrawingActive = false
            stack.layers.forEach { it.activeAtFrame = isBackground(it.id) }
        }
        return changed
    }

    fun ensureMediaDrawingAtCursor() {
        if (media == null || mediaDrawingActive) return
        val position = mediaPositionUs
        mediaPositionUs = (position * fps / 1_000_000L) * 1_000_000L / fps
        addMediaDrawing()
        showMediaTime(position)
    }

    fun addMediaDrawing(duplicate: Boolean = false) {
        val source = media ?: return
        val start = MediaTimeline.snap(mediaPositionUs, fps, source.durationUs)
        val existing = frames.indexOfFirst { it.sourceTimeUs == start }
        if (existing >= 0) { showMediaTime(start); return }
        require(frames.size < 10_000) { "This project already has 10,000 drawings" }
        val frame = AnimationFrame(FrameId.new(), sourceTimeUs = start, durationUs = 1_000_000L / fps)
        if (duplicate && mediaDrawingActive) stack.layers.forEach { layer ->
            if (!isBackground(layer.id)) when (layer) {
                is RasterLayerRuntime -> rasterCels.getOrPut(layer.id) { linkedMapOf() }[frame.id] = layer.tiles.duplicate()
                is ImageLayerRuntime -> imageCels.getOrPut(layer.id) { linkedMapOf() }[frame.id] = layer.transform
            }
        }
        val selected = currentFrame.id
        frames.add(frame); frames.sortBy { it.sourceTimeUs ?: 0L }
        selectedIndex = frames.indexOfFirst { it.id == selected }
        showMediaTime(start)
    }

    fun setMediaDurationTicks(ticks: Int) {
        if (media == null || !mediaDrawingActive) return
        frames[selectedIndex] = currentFrame.copy(durationUs = ticks.coerceIn(1, 600) * 1_000_000L / fps)
    }

    fun select(index: Int) {
        require(index in frames.indices)
        if (index == selectedIndex) return
        saveActiveImageTransforms()
        install(index)
    }

    fun addBlankFrame(): Int {
        frames.add(selectedIndex + 1, AnimationFrame(FrameId.new(), sourceTimeUs = currentFrame.sourceTimeUs))
        select(selectedIndex + 1)
        return selectedIndex
    }

    fun duplicateFrame(): Int {
        saveActiveImageTransforms()
        val duplicate = AnimationFrame(FrameId.new(), currentFrame.exposure, currentFrame.sourceTimeUs)
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

    fun thumbnail(frameId: FrameId, sizePx: Int, mediaBitmap: Bitmap? = null): Bitmap? {
        if (frames.none { it.id == frameId }) return null
        saveActiveImageTransforms()
        return stack.renderAnimationFrameThumbnail(this, frameId, sizePx, mediaBitmap)
    }

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
        val bitmaps = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Bitmap, Boolean>())
        active.forEach { bitmaps.addAll(it.residentBitmaps()) }
        val encoded = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<EncodedTile, Boolean>())
        return rasterCels.values.flatMap { it.values }.distinct().sumOf { store ->
            val pixels = store.residentBitmaps().filter { bitmaps.add(it) }.sumOf { it.allocationByteCount.toLong() }
            val compressed = store.encodedBacking?.values.orEmpty().filter { encoded.add(it) }.sumOf { it.memoryBytes }
            pixels + compressed + if (store in active) 0L else store.history.estimatedBytes
        }
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
        if (media != null && !mediaDrawingActive) return
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
            lerp(a.second.stretchX, b.second.stretchX),
            lerp(a.second.stretchY, b.second.stretchY),
            if (a.second.warp == null && b.second.warp == null) null else dev.tipstroke.core.model.PhotoWarp(
                (a.second.warp ?: dev.tipstroke.core.model.PhotoWarp.identity()).points.zip(
                    (b.second.warp ?: dev.tipstroke.core.model.PhotoWarp.identity()).points) { p,q ->
                    dev.tipstroke.core.geometry.Point(lerp(p.x,q.x),lerp(p.y,q.y)) }),
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

    private val coverageCache = java.util.WeakHashMap<TileStore, Pair<Long, Boolean>>()
    private var coverageScanning = false
    private fun coverageStores() = rasterCels.filterKeys { !isBackground(it) }.values.flatMap { it.values }.distinct()

    /** Scan one immutable cel lease at a time off the input thread; no full-canvas copy. */
    fun refreshCoverage(onReady: () -> Unit) {
        if (media == null || coverageScanning) return
        val store = coverageStores().firstOrNull { coverageCache[it]?.first != it.revision } ?: return
        val revision = store.revision
        val lease = store.duplicate()
        coverageScanning = true
        coverageExecutor.execute {
            val result = runCatching {
                fun painted(bitmap: Bitmap): Boolean {
                    val row = IntArray(bitmap.width)
                    for (y in 0 until bitmap.height) {
                        bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                        if (row.any { it ushr 24 != 0 }) return true
                    }
                    return false
                }
                if (lease.isResident) lease.residentBitmaps().any(::painted)
                else lease.encodedBacking.orEmpty().values.any { tile ->
                    val bitmap = tile.decode()
                    try { painted(bitmap) } finally { bitmap.recycle() }
                }
            }
            ProjectPersistence.mainHandler.post {
                lease.discard()
                coverageScanning = false
                // A corrupt cel stays unpainted and normal loading still reports its error.
                if (store.revision == revision) coverageCache[store] = revision to result.getOrDefault(false)
                onReady()
            }
        }
    }
    private fun paintedFrames(): Set<FrameId> = buildSet {
        rasterCels.filterKeys { !isBackground(it) }.values.forEach { cels ->
            cels.forEach { (id, store) -> if (coverageCache[store] == (store.revision to true)) add(id) }
        }
        imageCels.filterKeys { !isBackground(it) }.values.forEach { addAll(it.keys) }
    }

    fun state(playing: Boolean, recording: AnimationRecordingKind? = null, thumbnailRevision: Int = 0) = AnimationUiState(
        frames.toList(), selectedIndex, fps, playbackMode, playing,
        onionBefore, onionAfter, onionOpacity, backgroundLayerIds.toSet(), recording, thumbnailRevision,
        paintedFrameIds = if (media != null) paintedFrames() else emptySet(),
        coveragePending = media != null && coverageStores().any { coverageCache[it]?.first != it.revision },
        hasMedia = media != null, mediaPositionUs = mediaPositionUs, mediaDurationUs = media?.durationUs ?: 0, mediaDrawingActive = mediaDrawingActive,
        clipSourceDurationsUs = media?.assets.orEmpty().mapValues { it.value.durationUs },
        videoEdit = media?.edit, photoAssetIds = media?.assets.orEmpty().filterValues { it.kind == "image" }.keys, canUndoVideoEdit = editUndo.isNotEmpty(), canRedoVideoEdit = editRedo.isNotEmpty(),
    )

    private val loading = java.util.IdentityHashMap<TileStore, MutableList<(Result<Unit>) -> Unit>>()
    private val loadingPins = java.util.IdentityHashMap<TileStore, Int>()
    fun framesResident(indices: Collection<Int>): Boolean = indices.all { index ->
        val id = frames.getOrNull(index)?.id
        rasterCels.values.all { it[id]?.isResident != false }
    }
    fun prepareFrames(indices: Collection<Int>, complete: (Result<Unit>) -> Unit) {
        val ids = indices.mapNotNull { frames.getOrNull(it)?.id }.toSet()
        val targets = rasterCels.values.flatMap { cels -> cels.filterKeys { it in ids }.values }.distinct()
        val stores = targets.filter { !it.isResident }
        if (stores.isEmpty()) { complete(Result.success(Unit)); return }
        targets.forEach { loadingPins[it] = (loadingPins[it] ?: 0) + 1 }
        var remaining = stores.size
        var failure: Throwable? = null
        stores.forEach { store ->
            val callback: (Result<Unit>) -> Unit = { result ->
                result.exceptionOrNull()?.let { failure = it }
                remaining--
                if (remaining == 0) {
                    try { complete(failure?.let { Result.failure(it) } ?: Result.success(Unit)) }
                    finally { targets.forEach { target ->
                        val count = requireNotNull(loadingPins[target]) - 1
                        if (count == 0) loadingPins.remove(target) else loadingPins[target] = count
                    } }
                }
            }
            val existing = loading[store]
            if (existing != null) existing += callback
            else {
                loading[store] = mutableListOf(callback)
                val encoded = requireNotNull(store.encodedBacking)
                AnimationDecoding.executor.execute {
                    val decoded = runCatching { TileStore.decodeTiles(encoded) }
                    ProjectPersistence.mainHandler.post {
                        val result = decoded.map { store.installDecoded(encoded, it) }
                        loading.remove(store)?.forEach { it(result) }
                    }
                }
            }
        }
    }
    var storageEnabled = true
    var trimmingSuspended = false
    private var keepResident = emptySet<FrameId>()
    private var spilling = false
    private var storageFailed = false

    fun trimCleanFrames(keep: Set<Int>) {
        keepResident = keep.mapNotNull { frames.getOrNull(it)?.id }.toSet()
        trimInactive()
    }

    private fun coldStores(): List<TileStore> {
        val pinned = stack.layers.filterIsInstance<RasterLayerRuntime>().mapTo(mutableSetOf()) { it.tiles }
        rasterCels.values.forEach { cels -> cels.filterKeys { it in keepResident }.values.forEach { pinned += it } }
        return rasterCels.values.flatMap { it.values }.distinct().filter {
            it !in pinned && it !in loading && it !in loadingPins
        }
    }

    private fun trimInactive() {
        if (!storageEnabled || trimmingSuspended) return
        val cold = coldStores()
        cold.forEach { store ->
            if (store.encodedBacking != null) {
                store.unloadIfClean()
                store.history.clear()
            }
        }
        val disk = storage ?: return
        if (spilling || storageFailed) return
        val store = cold.firstOrNull { it.isResident && it.encodedBacking == null && (it.allocatedTileCount > 0 || it.history.estimatedBytes > 0) } ?: return
        val revision = store.revision
        val lease = store.duplicate()
        spilling = true
        AnimationStorage.executor.execute {
            val encoded = runCatching { lease.encodeTo(disk) }
            ProjectPersistence.mainHandler.post {
                lease.discard()
                spilling = false
                encoded.fold(onSuccess = { store.acceptBacking(revision, it) }, onFailure = {
                    storageFailed = true // Keep pixels and undo intact; do not repeatedly retry a full disk.
                    storageError(it)
                })
                trimInactive()
            }
        }
    }

    fun snapshot(): SavedAnimationSnapshot {
        saveActiveImageTransforms()
        return SavedAnimationSnapshot(
            frames.toList(), selectedIndex, fps, playbackMode,
            onionBefore, onionAfter, onionOpacity, backgroundLayerIds.toSet(),
            rasterCels.mapValues { (_, cels) -> cels.mapValues { (_, store) -> if (store.encodedBacking == null) store.snapshotTiles() else emptyMap() } },
            imageCels.mapValues { (_, cels) -> cels.toMap() },
            rasterCels.mapValues { (_, cels) -> cels.mapNotNull { (id, store) -> store.encodedBacking?.let { id to it } }.toMap() },
            media, mediaPositionUs,
        )
    }

    fun restore(saved: SavedAnimationSnapshot) {
        editUndo.clear(); editRedo.clear()
        mediaPreview?.clear()
        media = saved.media
        mediaPreview = media?.takeIf { it.kind != "video" }?.let(::MediaPreview)
        backgroundLayerIds.clear()
        backgroundLayerIds += saved.backgroundLayerIds
        rasterCels.clear()
        saved.rasterCels.forEach { (layerId, cels) ->
            rasterCels[layerId] = cels.mapValuesTo(linkedMapOf()) { (_, tiles) ->
                TileStore(stack.canvasWidth, stack.canvasHeight).apply { replaceTiles(tiles) }
            }
        }
        saved.encodedRasterCels.forEach { (layerId, cels) ->
            val stores = rasterCels.getOrPut(layerId) { linkedMapOf() }
            cels.forEach { (frameId, encoded) ->
                stores[frameId]?.discard()
                stores[frameId] = TileStore(stack.canvasWidth, stack.canvasHeight).apply { deferTiles(encoded) }
            }
        }
        imageCels.clear()
        saved.imageCels.forEach { (layerId, cels) -> imageCels[layerId] = cels.toMutableMap() }
        onionBefore = saved.onionBefore.coerceIn(0, 6)
        onionAfter = saved.onionAfter.coerceIn(0, 6)
        onionOpacity = saved.onionOpacity.coerceIn(0f, 1f)
        install(saved.selectedIndex.coerceIn(frames.indices))
        if (media != null) showMediaTime(saved.mediaPositionUs)
    }

    private fun install(index: Int) {
        if (media != null) rasterCels.values.forEach { cels ->
            cels.entries.removeAll { (_, store) -> store.allocatedTileCount == 0 && store.encodedBacking == null && store.history.estimatedBytes == 0L }
        }
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

internal object AnimationDecoding {
    val executor = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "TipStroke-animation-decode").apply { isDaemon = true }
    }
}

internal object AnimationStorage {
    val executor = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "TipStroke-animation-storage").apply { isDaemon = true }
    }
}

private val coverageExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "TipStroke-coverage").apply { isDaemon = true } }
