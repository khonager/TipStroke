package dev.tipstroke.drawing.android

import android.graphics.*
import dev.tipstroke.core.drawing.*
import dev.tipstroke.core.geometry.*
import dev.tipstroke.core.geometry.Point
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import kotlin.math.roundToInt
import dev.tipstroke.core.model.*
import org.json.JSONObject
import java.io.*
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

internal data class ReplayPaint(
    val stroke: CompletedStroke, val overlay: Boolean = false,
    val capStart: Boolean = false, val capEnd: Boolean = false, val skipFirst: Boolean = false,
    val ink: Stroke? = null,
)

/** Encoded immutable actions, not video frames. Only constructed on the project executor. */
class ProcessRecording internal constructor(
    val width: Int, val height: Int, internal val actions: List<ByteArray>, val stoppedReason: String?,
) {
    val actionCount get() = actions.size
    val bytes get() = actions.sumOf { it.size.toLong() }
    companion object {
        internal const val MAX_BYTES = 32L * 1024 * 1024
        internal const val MAX_ACTIONS = 30_000
        internal fun read(file: File): ProcessRecording? {
            if (!file.exists()) return null
            require(file.length() <= MAX_BYTES + 200_000) { "Recording exceeds storage limit" }
            return DataInputStream(file.inputStream().buffered()).use { input ->
                require(input.readUTF() == "TipStroke-process-1") { "Unsupported process recording version" }
                val width = input.readInt(); val height = input.readInt()
                require(width in 16..8192 && height in 16..8192)
                val reason = input.readUTF().ifEmpty { null }
                val count = input.readInt(); require(count in 0..MAX_ACTIONS)
                var total = 0L
                val actions = List(count) {
                    val size = input.readInt(); require(size in 1..MAX_BYTES.toInt())
                    total += size; require(total <= MAX_BYTES)
                    ByteArray(size).also(input::readFully)
                }
                require(input.read() == -1)
                ProcessRecording(width, height, actions, reason)
            }
        }
    }
    internal fun write(file: File) = DataOutputStream(file.outputStream().buffered()).use { out ->
        out.writeUTF("TipStroke-process-1"); out.writeInt(width); out.writeInt(height)
        out.writeUTF(stoppedReason.orEmpty()); out.writeInt(actions.size)
        actions.forEach { out.writeInt(it.size); out.write(it) }
    }
}

/** UI-thread capture; compression runs serially alongside project persistence. */
internal class ProcessRecorder(val width: Int, val height: Int, saved: ProcessRecording? = null) {
    private val actions = saved?.actions?.toMutableList() ?: mutableListOf()
    private var bytes = actions.sumOf { it.size.toLong() }
    @Volatile var stoppedReason: String? = saved?.stoppedReason
        private set
    private val queued = AtomicLong()
    private var metadata: String? = null
    private val known = mutableSetOf<String>()
    private val stores = mutableSetOf<TileStore>()
    fun stop(reason: String) { if (stoppedReason == null) stoppedReason = reason }
    fun detach() { stores.forEach { it.recordingListener = null }; stores.clear() }
    fun snapshot() = ProcessRecording(width, height, actions.toList(), stoppedReason)

    fun sync(stack: LayerStack, animated: Boolean, restoring: Boolean = false) {
        if (animated || stack.layers.any { it !is RasterLayerRuntime } || stack.canvasWidth != width || stack.canvasHeight != height) {
            stop("Recording stopped: image layers, animation and canvas resizing are not supported yet.")
        }
        if (stoppedReason != null) { detach(); return }
        val json = JSONObject().put("background", stack.backgroundColor).put("opacity", stack.backgroundOpacity)
        val layers = org.json.JSONArray()
        stack.layers.filterIsInstance<RasterLayerRuntime>().forEach { layer ->
            layers.put(JSONObject().put("id", layer.id.value).put("visible", layer.visible).put("opacity", layer.opacity))
        }
        val state = json.put("layers", layers).toString()
        if (!restoring && metadata != state) enqueue(2048) { out -> out.writeByte(0); out.writeUTF(state) }
        metadata = state
        stack.layers.filterIsInstance<RasterLayerRuntime>().forEach { layer ->
            if (known.add(layer.id.value) && !restoring && layer.tiles.allocatedTileCount > 0) {
                val cost = layer.tiles.allocatedTileBytes
                if (cost > QUEUE_LIMIT) stop("Recording stopped: initial artwork is too large.")
                else {
                    val initial = layer.tiles.snapshotTiles()
                    capture(layer.id.value, null, initial, recycle = true)
                }
            }
            stores += layer.tiles
            layer.tiles.recordingListener = { commands, patches -> capture(layer.id.value, commands, patches) }
        }
    }
    private fun capture(id: String, capturedCommands: List<ReplayPaint>?, patches: Map<TileCoordinate, Bitmap?>, recycle: Boolean = false) {
        val commands = capturedCommands?.takeIf { ops -> ops.sumOf { it.stroke.samples.size.toLong() + (it.ink?.inputs?.size ?: 0) } <= 100_000 }
        val cost = commands?.sumOf { it.stroke.samples.size.toLong() * 64 + (it.ink?.inputs?.size ?: 0) * 48L + 512 }
            ?: patches.size.toLong() * 256 * 256 * 4
        enqueue(cost, { if (recycle) patches.values.forEach { it?.recycle() } }) { out ->
            out.writeByte(if (commands != null) 1 else 2); out.writeUTF(id)
            if (commands != null) {
                out.writeInt(commands.size)
                commands.forEach { ReplayCodec.writePaint(out, it) }
            } else {
                out.writeInt(patches.size)
                patches.forEach { (coordinate, bitmap) ->
                    out.writeInt(coordinate.x); out.writeInt(coordinate.y)
                    val data = bitmap?.let { ByteArrayOutputStream().also { stream -> check(it.compress(Bitmap.CompressFormat.PNG, 100, stream)) }.toByteArray() }
                    out.writeInt(data?.size ?: 0); if (data != null) out.write(data)
                }
            }
        }
    }
    private fun enqueue(cost: Long, cleanup: () -> Unit = {}, write: (DataOutputStream) -> Unit) {
        if (stoppedReason != null) { cleanup(); return }
        if (queued.addAndGet(cost) > QUEUE_LIMIT) {
            queued.addAndGet(-cost); cleanup(); stop("Recording stopped: recording queue reached its memory budget."); return
        }
        ProjectPersistence.executor.execute {
            try {
                val buffer = ByteArrayOutputStream()
                DataOutputStream(GZIPOutputStream(buffer)).use(write)
                val encoded = buffer.toByteArray()
                if (bytes + encoded.size > ProcessRecording.MAX_BYTES || actions.size >= ProcessRecording.MAX_ACTIONS) {
                    stop("Recording stopped: 32 MiB or 30,000-action limit reached.")
                    // Never append later actions after a missing action.
                    accepting = false
                } else if (accepting) { actions += encoded; bytes += encoded.size }
            } catch (_: Exception) { accepting = false; stop("Recording stopped: an action could not be saved.") }
            finally { queued.addAndGet(-cost); cleanup() }
        }
    }
    private var accepting = true // executor-confined
    companion object { private const val QUEUE_LIMIT = 16L * 1024 * 1024 }
}

/** Binary samples plus explicit, complete brush settings. No references to mutable presets. */
internal object ReplayCodec {
    private val inkBrushes by lazy { ReplayInkBrushesV1() }
    fun writePaint(out: DataOutputStream, paint: ReplayPaint) {
        val s = paint.stroke.style; val b = s.brush
        out.writeInt(1) // renderer version; unsupported versions fail rather than substitute a new brush
        out.writeBoolean(paint.overlay); out.writeBoolean(paint.capStart); out.writeBoolean(paint.capEnd); out.writeBoolean(paint.skipFirst)
        out.writeInt(b.schemaVersion); out.writeUTF(b.id.value); out.writeUTF(b.displayName); out.writeUTF(b.engine.name)
        listOf(b.baseSizePx,b.opacity,b.hardness,b.spacing,b.stabilization).forEach(out::writeFloat)
        listOf(b.pressureToSize,b.pressureToOpacity).forEach { out.writeFloat(it.start); out.writeFloat(it.end); out.writeFloat(it.exponent) }
        listOf(b.speedTaper,b.pencilPointSize,b.pencilTiltSensitivity,b.pencilShadeSize,b.pencilShadeOpacity,b.pencilGrain).forEach(out::writeFloat)
        out.writeUTF(b.pencilTiltMode.name); out.writeFloat(b.pencilShadeStartRadians); out.writeFloat(b.pencilShadeTransitionRadians)
        out.writeFloat(s.sizePx); out.writeFloat(s.opacity)
        listOf(s.color.red,s.color.green,s.color.blue,s.color.alpha).forEach(out::writeFloat)
        out.writeUTF(s.blend.name); out.writeUTF(s.pixelTool?.name.orEmpty())
        val points = s.selection?.points.orEmpty(); out.writeInt(points.size)
        points.forEach { out.writeFloat(it.x); out.writeFloat(it.y) }
        out.writeInt(paint.stroke.samples.size)
        paint.stroke.samples.forEach {
            out.writeInt(it.pointerId); out.writeFloat(it.position.x); out.writeFloat(it.position.y)
            out.writeFloat(it.pressure); out.writeFloat(it.tiltRadians); out.writeFloat(it.orientationRadians)
            out.writeLong(it.elapsedNanos); out.writeInt(it.buttonState); out.writeUTF(it.kind.name); out.writeFloat(it.opacityPressure)
        }
        out.writeBoolean(paint.ink != null)
        paint.ink?.let { ink ->
            out.writeUTF("androidx-ink-1.1.0-alpha08")
            out.writeInt(ink.brush.colorIntArgb); out.writeFloat(ink.brush.size); out.writeFloat(ink.brush.epsilon)
            val inputs = ink.inputs
            out.writeInt(inputs.getNoiseSeed()); out.writeFloat(0f) // v1 families have no animated paint
            out.writeInt(inputs.size)
            for (i in 0 until inputs.size) {
                val input = inputs[i]
                out.writeInt(when(input.toolType) { InputToolType.STYLUS -> 1; InputToolType.MOUSE -> 2; InputToolType.TOUCH -> 3; else -> 0 })
                out.writeFloat(input.x); out.writeFloat(input.y); out.writeLong(input.elapsedTimeMillis)
                out.writeFloat(input.strokeUnitLengthCm); out.writeFloat(input.pressure)
                out.writeFloat(input.tiltRadians); out.writeFloat(input.orientationRadians)
            }
        }
    }
    @OptIn(androidx.ink.brush.ExperimentalInkCustomBrushApi::class)
    fun readPaint(input: DataInputStream): ReplayPaint = with(input) {
        require(readInt() == 1) { "Unsupported recording brush renderer" }
        val overlay=readBoolean(); val start=readBoolean(); val end=readBoolean(); val skip=readBoolean()
        val b = BrushPreset(readInt(), BrushId(readUTF()), readUTF(), BrushEngine.valueOf(readUTF()),
            readFloat(),readFloat(),readFloat(),readFloat(),readFloat(),
            PressureCurve(readFloat(),readFloat(),readFloat()), PressureCurve(readFloat(),readFloat(),readFloat()),
            readFloat(),readFloat(),readFloat(),readFloat(),readFloat(),readFloat(),
            PencilTiltMode.valueOf(readUTF()),readFloat(),readFloat())
        val size=readFloat(); val opacity=readFloat(); val color=RgbaColor(readFloat(),readFloat(),readFloat(),readFloat())
        val blend=BlendBehavior.valueOf(readUTF()); val pixel=readUTF().ifEmpty { null }?.let(PixelTool::valueOf)
        val points=List(count(100_000)) { Point(readFloat(),readFloat()) }
        val samples=List(count(250_000)) { StrokeSample(readInt(), Point(readFloat(),readFloat()),readFloat(),readFloat(),readFloat(),readLong(),readInt(),PointerKind.valueOf(readUTF()),readFloat()) }
        require(samples.isNotEmpty())
        val ink = if (readBoolean()) {
            require(readUTF() == "androidx-ink-1.1.0-alpha08") { "Unsupported native Ink replay version" }
            val argb=readInt(); val inkSize=readFloat(); val epsilon=readFloat()
            val inputs=MutableStrokeInputBatch()
            inputs.setNoiseSeed(readInt()); require(readFloat() == 0f) { "Animated brush recording is not supported" }
            repeat(count(250_000)) {
                val type=when(readInt()) { 1 -> InputToolType.STYLUS; 2 -> InputToolType.MOUSE; 3 -> InputToolType.TOUCH; else -> InputToolType.UNKNOWN }
                inputs.add(type,readFloat(),readFloat(),readLong(),readFloat(),readFloat(),readFloat(),readFloat())
            }
            val preset=if(replayPressureEnabledV1(b.pressureToOpacity)) b.copy(pressureToOpacity=PressureCurve(1f,1f,1f)) else b
            Stroke(Brush.createWithColorIntArgb(inkBrushes.familyFor(preset),argb,inkSize,epsilon),inputs)
        } else null
        ReplayPaint(CompletedStroke(samples, StrokeStyle(b,size,opacity,color,blend,points.takeIf { it.isNotEmpty() }?.let(::SelectionRegion),pixel)),overlay,start,end,skip,ink)
    }
    fun DataInputStream.count(max: Int): Int = readInt().also { require(it in 0..max) }
}

/** Sequential native replay. No video, frame archive, or modifications to the live artwork. */
class ProcessReplay internal constructor(private val recording: ProcessRecording) : AutoCloseable {
    private val inkRenderer by lazy { CanvasStrokeRenderer.create(ReplayInkBrushesV1().textureStore) }
    private val layers = linkedMapOf<String, MutableMap<TileCoordinate, Bitmap>>()
    private var metadata = JSONObject().put("layers", org.json.JSONArray()).put("background", Color.WHITE).put("opacity", 1)
    private var position = -1
    fun frame(index: Int, maxEdge: Int = 1280): Bitmap {
        require(index in recording.actions.indices)
        if (index < position) { close(); position = -1 }
        while (position < index) apply(recording.actions[++position])
        val scale = minOf(1f, maxEdge.toFloat()/recording.width, maxEdge.toFloat()/recording.height)
        val output = Bitmap.createBitmap((recording.width*scale).toInt().coerceAtLeast(1), (recording.height*scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas=Canvas(output); canvas.drawColor(Color.WHITE); canvas.scale(scale,scale)
        canvas.drawColor(metadata.getInt("background").let { (it and 0xffffff) or ((metadata.getDouble("opacity")*255).roundToInt() shl 24) })
        val order=metadata.getJSONArray("layers")
        for (i in 0 until order.length()) {
            val layer=order.getJSONObject(i); if (!layer.getBoolean("visible")) continue
            val paint=Paint().apply { alpha=(layer.getDouble("opacity")*255).roundToInt() }
            layers[layer.getString("id")]?.forEach { (xy,b) -> canvas.drawBitmap(b,xy.x*256f,xy.y*256f,paint) }
        }
        return output
    }
    private fun apply(bytes: ByteArray) = DataInputStream(GZIPInputStream(ByteArrayInputStream(bytes))).use { input ->
        when (input.readUnsignedByte()) {
            0 -> {
                metadata=JSONObject(input.readUTF())
                val order=metadata.getJSONArray("layers")
                val retained=(0 until order.length()).map { order.getJSONObject(it).getString("id") }.toSet()
                layers.keys.toList().filter { it !in retained }.forEach { layers.remove(it)?.values?.forEach { bitmap -> bitmap.recycle(); allocated-- } }
            }
            1 -> {
                val tiles=layers.getOrPut(input.readUTF()) { mutableMapOf() }
                val count=input.readInt(); require(count in 1..250_000)
                val overlay=mutableMapOf<TileCoordinate,Bitmap>()
                try {
                    repeat(count) {
                        val op=ReplayCodec.readPaint(input)
                        val target=if(op.overlay) overlay else tiles
                        TileGrid.intersecting(op.stroke.replayBoundsV1(),recording.width,recording.height).forEach { xy ->
                            val bitmap=target.getOrPut(xy) { allocate() }
                            val canvas=Canvas(bitmap); canvas.translate(-xy.x*256f,-xy.y*256f)
                            if (op.ink != null) {
                                val pressure = replayPressureEnabledV1(op.stroke.style.brush.pressureToOpacity)
                                val rendered = if(pressure) Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888) else bitmap
                                val nativeCanvas=Canvas(rendered)
                                nativeCanvas.translate(-xy.x*256f,-xy.y*256f)
                                op.stroke.style.selection?.let { nativeCanvas.clipPath(it.toAndroidPath()) }
                                inkRenderer.draw(nativeCanvas,op.ink,Matrix())
                                if(pressure) {
                                    ReplayPainterV1.applyPressureOpacityMask(rendered,op.stroke,xy.x*256,xy.y*256)
                                    Canvas(bitmap).drawBitmap(rendered,0f,0f,null); rendered.recycle()
                                }
                            } else if(op.overlay) ReplayPainterV1.drawPencilOverlay(canvas,op.stroke,op.capStart,op.capEnd,op.skipFirst)
                            else ReplayPainterV1.draw(canvas,op.stroke)
                        }
                    }
                    overlay.forEach { (xy,b) -> Canvas(tiles.getOrPut(xy) { allocate() }).drawBitmap(b,0f,0f,null) }
                } finally { overlay.values.forEach { it.recycle(); allocated-- } }
            }
            2 -> {
                val tiles=layers.getOrPut(input.readUTF()) { mutableMapOf() }
                val count=input.readInt(); require(count in 0..1024)
                repeat(count) {
                    val xy=TileCoordinate(input.readInt(),input.readInt()); val size=input.readInt(); require(size in 0..1_048_576)
                    tiles.remove(xy)?.let { it.recycle(); allocated-- }
                    if(size>0) {
                        val data=ByteArray(size).also(input::readFully)
                        val decoded=requireNotNull(BitmapFactory.decodeByteArray(data,0,size))
                        require(decoded.width==256 && decoded.height==256)
                        val bitmap=allocate(); Canvas(bitmap).drawBitmap(decoded,0f,0f,null); decoded.recycle(); tiles[xy]=bitmap
                    }
                }
            }
            else -> error("Unsupported process action")
        }
    }
    private var allocated=0
    private fun allocate(): Bitmap {
        check(allocated < 512) { "Replay exceeds the 128 MiB tile budget" }
        allocated++; return Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888)
    }
    override fun close() { layers.values.forEach { it.values.forEach(Bitmap::recycle) }; layers.clear(); allocated=0 }
}

/** Serial background preview with lifecycle-safe delivery and no queued playback frames. */
class ProcessPreview(recording: ProcessRecording) : AutoCloseable {
    private val replay = ProcessReplay(recording)
    @Volatile private var closed = false
    fun frame(index: Int, callback: (Result<Bitmap>) -> Unit) {
        ProjectPersistence.executor.execute {
            if (closed) return@execute
            val result = runCatching { replay.frame(index, 960) }
            ProjectPersistence.mainHandler.post {
                if (closed) result.getOrNull()?.recycle() else callback(result)
            }
        }
    }
    override fun close() { closed = true; ProjectPersistence.executor.execute { replay.close() } }
}

/** Bound extra per-gesture recording references; oversized gestures fall back to exact tiles. */
internal class ReplayCapture {
    private val paints = mutableListOf<ReplayPaint>()
    private var samples = 0
    private var overflow = false
    fun add(paint: ReplayPaint) {
        if (overflow) return
        samples += paint.stroke.samples.size
        if (samples > 100_000) { paints.clear(); overflow = true } else paints += paint
    }
    fun commands(): List<ReplayPaint>? = paints.takeIf { !overflow && it.isNotEmpty() }?.toList()
    fun clear() { paints.clear(); samples = 0; overflow = false }
}
