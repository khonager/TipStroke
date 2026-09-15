package dev.tipstroke.core.model

import java.time.Instant
import java.util.UUID

@JvmInline value class DocumentId(val value: String)
@JvmInline value class LayerId(val value: String)

data class CanvasSpec(
    val widthPx: Int,
    val heightPx: Int,
    val colorSpace: ColorSpace = ColorSpace.SRGB_PREMULTIPLIED_8,
) {
    init { require(widthPx > 0 && heightPx > 0) }
}

enum class ColorSpace { SRGB_PREMULTIPLIED_8 }

sealed interface Layer {
    val id: LayerId
    val name: String
    val visible: Boolean
    val opacity: Float
}

data class RasterLayer(
    override val id: LayerId,
    override val name: String,
    override val visible: Boolean = true,
    override val opacity: Float = 1f,
) : Layer

data class ImageTransform(
    val centerX: Float,
    val centerY: Float,
    val scale: Float = 1f,
    val rotationDegrees: Float = 0f,
) {
    init { require(scale > 0f) }

    fun changedBy(deltaX: Float, deltaY: Float, scaleFactor: Float = 1f, rotationDeltaDegrees: Float = 0f) = copy(
        centerX = centerX + deltaX,
        centerY = centerY + deltaY,
        scale = (scale * scaleFactor).coerceIn(.02f, 16f),
        rotationDegrees = rotationDegrees + rotationDeltaDegrees,
    )
}

/**
 * A non-destructive placed image. [sourceId] resolves to the original encoded asset in the
 * project package; transforms never rewrite that source.
 */
data class ImageLayer(
    override val id: LayerId,
    override val name: String,
    override val visible: Boolean = true,
    override val opacity: Float = 1f,
    val sourceId: String,
    val originalWidthPx: Int,
    val originalHeightPx: Int,
    val transform: ImageTransform,
) : Layer {
    init { require(originalWidthPx > 0 && originalHeightPx > 0) }
}

enum class LayerKind { RASTER, IMAGE }

data class LayerSummary(
    val id: LayerId,
    val name: String,
    val kind: LayerKind,
    val visible: Boolean,
    val opacity: Float,
    val originalWidthPx: Int? = null,
    val originalHeightPx: Int? = null,
    val imageScale: Float? = null,
)

data class Document(
    val id: DocumentId,
    val schemaVersion: Int,
    val canvas: CanvasSpec,
    val layers: List<Layer>,
    val createdAt: Instant,
    val modifiedAt: Instant,
) {
    init {
        require(schemaVersion > 0)
        require(layers.map { it.id }.distinct().size == layers.size)
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        fun testCanvas(now: Instant = Instant.now()) = Document(
            id = DocumentId(UUID.randomUUID().toString()),
            schemaVersion = CURRENT_SCHEMA_VERSION,
            canvas = CanvasSpec(2048, 2048),
            layers = listOf(RasterLayer(LayerId(UUID.randomUUID().toString()), "Paint")),
            createdAt = now,
            modifiedAt = now,
        )
    }
}
