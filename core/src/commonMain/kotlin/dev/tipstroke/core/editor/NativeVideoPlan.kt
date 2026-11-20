package dev.tipstroke.core.editor

import dev.tipstroke.core.model.*
import kotlinx.serialization.json.*

/** Apple uses the same clip timing, interpolation and transition limits as Android. */
class NativeVideoPlan private constructor(private val edit: VideoEdit) {
    val durationUs: Long get() = edit.durationUs
    fun start(index: Int): Long = edit.startUs(index)
    fun index(timeUs: Long): Int = edit.indexAt(timeUs)

    fun samples(timeUs: Long): List<NativeClipSample> {
        val time = timeUs.coerceIn(0, durationUs - 1)
        val current = edit.indexAt(time)
        val elapsed = time - edit.startUs(current)
        val clip = edit.clips[current]
        fun sample(index: Int, local: Long, weight: Float): NativeClipSample {
            val item = edit.clips[index]
            return NativeClipSample(item.assetId, item.sourceInUs + local, item.poseAt(local), weight)
        }
        if (current > 0 && elapsed < clip.transitionUs) {
            val weight = elapsed.toFloat() / clip.transitionUs
            return listOf(sample(current - 1, time - edit.startUs(current - 1), 1f - weight), sample(current, elapsed, weight))
        }
        return listOf(sample(current, elapsed, 1f))
    }

    companion object {
        @Throws(Exception::class)
        fun decode(json: String): NativeVideoPlan {
            require(json.length <= 4_000_000)
            val items = Json.parseToJsonElement(json).jsonArray.map { element ->
                val entry = element.jsonObject
                fun text(key: String) = entry.getValue(key).jsonPrimitive.content
                val keys = entry.getValue("keyframes").jsonArray.map {
                    val key = it.jsonObject
                    fun number(name: String) = key.getValue(name).jsonPrimitive.float
                    ClipKeyframe(key.getValue("timeUs").jsonPrimitive.long,
                        ClipPose(number("x"), number("y"), number("scale"), number("rotation"), number("opacity")),
                        ClipEasing.valueOf(key.getValue("easing").jsonPrimitive.content))
                }
                MediaClip(text("id"), text("assetID"), text("name"), text("sourceInUs").toLong(),
                    text("durationUs").toLong(), keys, text("transitionUs").toLong())
            }
            return NativeVideoPlan(VideoEdit(items))
        }
    }
}

data class NativeClipSample(val assetId: String, val sourceUs: Long, val pose: ClipPose, val weight: Float)
