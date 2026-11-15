package dev.tipstroke.core.model

/** Immutable, platform-independent coverage image. White paints; black is transparent. */
class BrushTexture(val width: Int, val height: Int, pixels: ByteArray) {
    private val data = pixels.copyOf()
    private val hash = 31 * (31 * width + height) + data.contentHashCode()
    init { require(width in 1..256 && height in 1..256 && data.size == width * height) }
    fun coverage(x: Int, y: Int): Int = data[y * width + x].toInt() and 255
    fun bytes(): ByteArray = data.copyOf()
    fun rotated() = BrushTexture(height, width, ByteArray(data.size) { i ->
        val x = i % height; val y = i / height
        data[(height - 1 - x) * width + y]
    })
    fun inverted() = BrushTexture(width, height, ByteArray(data.size) { (255 - (data[it].toInt() and 255)).toByte() })
    override fun equals(other: Any?) = other is BrushTexture && width == other.width && height == other.height && data.contentEquals(other.data)
    override fun hashCode() = hash
}

enum class StampShape { ROUND, SQUARE, OVAL, BRISTLES, IMAGE }
enum class StampGrain { NONE, NOISE, PAPER, LINES, IMAGE }
enum class GrainPlacement { CANVAS, STAMP }

/** A new brush is a shape and grain, independent of the three legacy brush algorithms. */
data class StampBrush(
    val shape: StampShape = StampShape.ROUND,
    val shapeTexture: BrushTexture? = null,
    val grain: StampGrain = StampGrain.NONE,
    val grainTexture: BrushTexture? = null,
    val grainPlacement: GrainPlacement = GrainPlacement.CANVAS,
    val grainScale: Float = 64f,
    val grainDepth: Float = .6f,
    val aspectRatio: Float = 1f,
    val rotationDegrees: Float = 0f,
    val followDirection: Boolean = false,
    val rotationJitter: Float = 0f,
    val scatter: Float = 0f,
    val count: Int = 1,
    val sizeJitter: Float = 0f,
    val opacityJitter: Float = 0f,
    val flow: Float = .7f,
    val tiltSize: Float = 0f,
    val startTaperPx: Float = 0f,
    val fadeOutPx: Float = 0f,
    val hueJitter: Float = 0f,
    val saturationJitter: Float = 0f,
) {
    init {
        require(shape != StampShape.IMAGE || shapeTexture != null)
        require(grain != StampGrain.IMAGE || grainTexture != null)
        require(grainScale in 2f..512f && grainDepth in 0f..1f)
        require(aspectRatio in .1f..1f && rotationDegrees in -180f..180f && rotationJitter in 0f..180f)
        require(scatter in 0f..2f && count in 1..8 && sizeJitter in 0f..1f && opacityJitter in 0f..1f)
        require(flow in .01f..1f && tiltSize in 0f..3f && startTaperPx in 0f..500f && fadeOutPx in 0f..2000f)
        require(hueJitter in 0f..1f && saturationJitter in 0f..1f)
    }
}
