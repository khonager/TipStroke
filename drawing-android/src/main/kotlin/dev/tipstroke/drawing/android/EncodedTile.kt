package dev.tipstroke.drawing.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.lang.ref.WeakReference
import java.nio.file.Files
import java.util.UUID

/** Immutable PNG shared by live cels and save/export snapshots. All I/O is worker-only. */
internal class EncodedTile private constructor(private val file: File?, private val bytes: ByteArray?, val byteCount: Long) {
    val memoryBytes: Long get() = bytes?.size?.toLong() ?: 0L

    fun readBytes(): ByteArray = bytes ?: requireNotNull(file).readBytes()

    fun decode(): Bitmap = requireNotNull(if (file != null) BitmapFactory.decodeFile(file.path)
        else BitmapFactory.decodeByteArray(requireNotNull(bytes), 0, bytes.size)) { "Cannot decode animation tile" }
        .also {
            if (it.width != 256 || it.height != 256) {
                it.recycle()
                error("Invalid animation tile size")
            }
        }

    fun writeTo(destination: File) {
        if (file == null) destination.writeBytes(requireNotNull(bytes))
        else try { Files.createLink(destination.toPath(), file.toPath()) }
        catch (_: java.io.IOException) { file.copyTo(destination) }
    }

    companion object {
        fun inMemory(bytes: ByteArray) = EncodedTile(null, bytes, bytes.size.toLong())
        internal fun onDisk(file: File) = EncodedTile(file, null, file.length())
    }
}

/** Private files (not OS-purgeable cache). Weak leases keep queued saves/exports alive too.
 * Old process sessions are removed on first use; obsolete revisions are swept on worker writes.
 */
internal class CelStorage(private val root: File) {
    private var initialized = false
    private var writes = 0
    private val leases = mutableMapOf<File, WeakReference<EncodedTile>>()

    @Synchronized private fun create(write: (File) -> Unit): EncodedTile {
        if (!initialized) {
            root.deleteRecursively()
            check(root.mkdirs()) { "Cannot create animation working storage" }
            initialized = true
        }
        if (writes++ % 128 == 0) leases.entries.removeAll { (file, reference) ->
            if (reference.get() == null) { file.delete(); true } else false
        }
        val file = File(root, "${UUID.randomUUID()}.png")
        try {
            write(file)
            return EncodedTile.onDisk(file).also { leases[file] = WeakReference(it) }
        } catch (error: Throwable) { file.delete(); throw error }
    }

    fun store(bitmap: Bitmap): EncodedTile = create { file ->
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Cannot store animation tile" } }
    }

    fun retain(file: File): EncodedTile = create { destination ->
        try { Files.createLink(destination.toPath(), file.toPath()) }
        catch (_: java.io.IOException) { file.copyTo(destination) }
    }

    companion object {
        private val stores = mutableMapOf<String, CelStorage>()
        @Synchronized fun forFilesDirectory(files: File): CelStorage {
            val root = File(files, "animation-working-tiles")
            return stores.getOrPut(root.absolutePath) { CelStorage(root) }
        }
    }
}
