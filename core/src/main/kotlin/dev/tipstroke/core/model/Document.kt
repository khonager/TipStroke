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
