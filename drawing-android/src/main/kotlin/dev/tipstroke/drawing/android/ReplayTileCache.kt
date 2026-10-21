package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.tipstroke.core.geometry.TileCoordinate
import java.io.File
import java.nio.file.Files

/** Exact full-resolution replay tiles, with a bounded resident set and disposable disk backing.
 * Owned by one replay worker. Eviction never drops artwork or changes brush rendering.
 */
internal class ReplayTileCache(
    private val parent: File? = null,
    private val residentLimit: Int = 128,
) : AutoCloseable {
    private data class Key(val layer: String, val coordinate: TileCoordinate)
    private data class Entry(val fileName: String, var dirty: Boolean = true)
    private val entries = linkedMapOf<Key, Entry>()
    private val resident = LinkedHashMap<Key, Bitmap>(16, .75f, true)
    private var directory: File? = null
    private var nextId = 0L
    private var diskBytes = 0L
    init { require(residentLimit >= 2) }
    internal val residentCount get() = resident.size

    private fun directory(): File = directory ?: (if (parent == null) {
        Files.createTempDirectory("tipstroke-replay-").toFile()
    } else {
        Files.createTempDirectory(parent.toPath(), "tipstroke-replay-").toFile()
    }).also { directory = it }

    private fun makeRoom() {
        if (resident.size < residentLimit) return
        val oldest = resident.entries.first()
        val entry = entries.getValue(oldest.key)
        if (entry.dirty) {
            val file = File(directory(), entry.fileName)
            val oldSize = file.length()
            file.outputStream().buffered().use {
                check(oldest.value.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Could not cache replay tile" }
            }
            diskBytes += file.length() - oldSize
            check(diskBytes <= 512L * 1024 * 1024) { "Replay cache needs more than 512 MiB of temporary space" }
            entry.dirty = false
        }
        resident.remove(oldest.key)
        oldest.value.recycle()
    }

    private fun tile(key: Key, edit: Boolean): Bitmap {
        resident[key]?.let {
            if (edit) entries.getValue(key).dirty = true
            return it
        }
        makeRoom()
        val entry = entries[key]
        val bitmap = if (entry == null) {
            check(edit)
            entries[key] = Entry("${nextId++}.png")
            Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        } else {
            requireNotNull(BitmapFactory.decodeFile(File(directory(), entry.fileName).path,
                BitmapFactory.Options().apply { inMutable = true })) { "Could not read replay tile" }
        }
        resident[key] = bitmap
        if (edit) entries.getValue(key).dirty = true
        return bitmap
    }

    inner class Layer internal constructor(private val id: String) {
        fun edit(coordinate: TileCoordinate): Bitmap = tile(Key(id, coordinate), true)
        fun contains(coordinate: TileCoordinate): Boolean = Key(id, coordinate) in entries
        fun remove(coordinate: TileCoordinate) {
            val key = Key(id, coordinate)
            resident.remove(key)?.recycle()
            entries.remove(key)?.let { entry ->
                directory?.let { root ->
                    val file = File(root, entry.fileName)
                    diskBytes -= file.length()
                    file.delete()
                }
            }
        }
        fun forEach(block: (TileCoordinate, Bitmap) -> Unit) {
            entries.keys.filter { it.layer == id }.forEach { block(it.coordinate, tile(it, false)) }
        }
        fun clear() { entries.keys.filter { it.layer == id }.forEach { remove(it.coordinate) } }
    }
    fun layer(id: String) = Layer(id)
    override fun close() {
        resident.values.forEach(Bitmap::recycle)
        resident.clear(); entries.clear()
        directory?.deleteRecursively(); directory = null; diskBytes = 0
    }
}
