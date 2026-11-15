package dev.tipstroke.core.model

/** Normalized translation relative to the canvas; scale 1 fits the entire source. */
data class ClipPose(val x: Float = 0f, val y: Float = 0f, val scale: Float = 1f, val rotation: Float = 0f, val opacity: Float = 1f) {
    init {
        require(x.isFinite() && y.isFinite() && x in -4f..4f && y in -4f..4f)
        require(scale.isFinite() && scale in .05f..20f)
        require(rotation.isFinite() && rotation in -3600f..3600f)
        require(opacity.isFinite() && opacity in 0f..1f)
    }
    fun interpolate(to: ClipPose, t: Float) = ClipPose(
        x + (to.x - x) * t, y + (to.y - y) * t, scale + (to.scale - scale) * t,
        rotation + (to.rotation - rotation) * t, opacity + (to.opacity - opacity) * t,
    )
}

enum class ClipEasing { LINEAR, SMOOTH, HOLD }
data class ClipKeyframe(val timeUs: Long, val pose: ClipPose = ClipPose(), val easing: ClipEasing = ClipEasing.LINEAR) {
    init { require(timeUs in -86_400_000_000L..86_400_000_000L) }
}

data class MediaClip(
    val id: String, val assetId: String, val name: String,
    val sourceInUs: Long, val durationUs: Long,
    val keyframes: List<ClipKeyframe> = emptyList(),
    /** Crossfade into this clip, overlapping the end of the previous clip. */
    val transitionUs: Long = 0,
) {
    init {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,100}")) && assetId.matches(Regex("[a-zA-Z0-9_-]{1,100}")))
        require(name.length <= 120 && sourceInUs >= 0 && durationUs in 1..86_400_000_000L)
        require(sourceInUs <= 86_400_000_000L - durationUs)
        require(transitionUs in 0..durationUs / 2)
        require(keyframes.size <= 1000)
        require(keyframes.zipWithNext().all { (a, b) -> a.timeUs < b.timeUs })
    }
    fun poseAt(timeUs: Long): ClipPose {
        if (keyframes.isEmpty()) return ClipPose()
        val next = keyframes.indexOfFirst { it.timeUs > timeUs }
        if (next == 0) return keyframes.first().pose
        if (next < 0) return keyframes.last().pose
        val a = keyframes[next - 1]; val b = keyframes[next]
        val raw = ((timeUs - a.timeUs).toDouble() / (b.timeUs - a.timeUs)).toFloat().coerceIn(0f, 1f)
        val t = when (a.easing) { ClipEasing.LINEAR -> raw; ClipEasing.SMOOTH -> raw * raw * (3 - 2 * raw); ClipEasing.HOLD -> 0f }
        return a.pose.interpolate(b.pose, t)
    }
    fun withKeyframe(key: ClipKeyframe) = copy(keyframes = (keyframes.filterNot { it.timeUs == key.timeUs } + key).sortedBy { it.timeUs })
    fun slice(fromUs: Long, toUs: Long, newId: String = id): MediaClip {
        require(fromUs >= 0 && toUs > fromUs && toUs <= durationUs)
        return trimSource(sourceInUs + fromUs, sourceInUs + toUs).copy(id = newId)
    }
    /** Source-relative handles permit restoring trimmed footage after save/reopen. */
    fun trimSource(fromUs: Long, toUs: Long): MediaClip {
        require(fromUs >= 0 && toUs > fromUs && toUs <= 86_400_000_000L)
        val shift = fromUs - sourceInUs
        // Surrounding keys preserve the exact curve, even outside the visible window.
        return copy(sourceInUs = fromUs, durationUs = toUs - fromUs,
            keyframes = keyframes.map { it.copy(timeUs = it.timeUs - shift) },
            transitionUs = if (shift == 0L) minOf(transitionUs, (toUs - fromUs) / 2) else 0)
    }
}

/** One ripple-edited track. Transition limits prevent three simultaneous decoder frames. */
data class VideoEdit(val clips: List<MediaClip>) {
    init {
        require(clips.isNotEmpty() && clips.size <= 1000 && clips.map { it.id }.distinct().size == clips.size)
        require(clips.sumOf { it.keyframes.size } <= 10_000) { "Timeline supports up to 10,000 keyframes" }
        require(clips.first().transitionUs == 0L)
        require(clips.zipWithNext().all { (a, b) -> b.transitionUs <= a.durationUs / 2 })
        require(clips.sumOf { it.durationUs - it.transitionUs } <= 86_400_000_000L)
    }
    val durationUs: Long = clips.sumOf { it.durationUs - it.transitionUs }
    private val starts = LongArray(clips.size).also { starts ->
        var end = 0L
        clips.forEachIndexed { index, clip ->
            starts[index] = end - clip.transitionUs
            end = starts[index] + clip.durationUs
        }
    }
    fun startUs(index: Int): Long = starts[index]
    fun indexAt(timeUs: Long): Int {
        var low = 0; var high = starts.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (starts[middle] <= timeUs) low = middle + 1 else high = middle
        }
        return (low - 1).coerceAtLeast(0)
    }
    fun normalized(next: List<MediaClip>) = VideoEdit(next.mapIndexed { index, clip ->
        clip.copy(transitionUs = if (index == 0) 0 else minOf(clip.transitionUs, clip.durationUs / 2, next[index - 1].durationUs / 2))
    })
    fun replace(index: Int, clip: MediaClip) = normalized(clips.toMutableList().apply { this[index] = clip })
    fun split(index: Int, localUs: Long, newId: String): VideoEdit {
        val clip = clips[index]
        require(localUs in 1 until clip.durationUs)
        return normalized(clips.take(index) + clip.slice(0, localUs) + clip.slice(localUs, clip.durationUs, newId) + clips.drop(index + 1))
    }
    fun move(index: Int, target: Int) = normalized(clips.toMutableList().apply { add(target, removeAt(index)) })
    fun delete(index: Int): VideoEdit { require(clips.size > 1); return normalized(clips.filterIndexed { i, _ -> i != index }) }
}
