package dev.tipstroke.app

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class ArtworkDestination { PHOTOS, SHARE, FILE }

internal fun deliverArtwork(
    context: Context, destination: ArtworkDestination, name: String, mime: String,
    render: (Uri, (Result<Unit>) -> Unit) -> Unit, finished: (Result<Unit>) -> Unit,
) {
    val scope = CoroutineScope(Dispatchers.Main.immediate)
    val resolver = context.contentResolver
    scope.launch {
        val target = runCatching { withContext(Dispatchers.IO) {
            if (destination == ArtworkDestination.SHARE) {
                val root = File(context.cacheDir, "shared-artwork").apply { mkdirs() }
                val expiry = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
                root.listFiles()?.filter { it.lastModified() < expiry }?.forEach { it.deleteRecursively() }
                val folder = File(root, UUID.randomUUID().toString()).apply { check(mkdir()) }
                val file = File(folder, File(name).name.takeUnless { it.isBlank() || it == "." || it == ".." } ?: "TipStroke")
                FileProvider.getUriForFile(context, context.packageName + ".brush-files", file)
            } else {
                require(destination == ArtworkDestination.PHOTOS)
                val video = mime.startsWith("video/")
                val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, File(name).name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, if (video) "Movies/TipStroke" else "Pictures/TipStroke")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                checkNotNull(resolver.insert(collection, values)) { "Could not create the gallery item." }
            }
        } }.getOrElse { finished(Result.failure(it)); return@launch }
        fun complete(result: Result<Unit>) {
            scope.launch {
                val outcome = runCatching {
                    result.getOrThrow()
                    if (destination == ArtworkDestination.PHOTOS) withContext(Dispatchers.IO) {
                        check(resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
                    } else {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = mime
                            putExtra(Intent.EXTRA_STREAM, target)
                            clipData = ClipData.newRawUri(name, target)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, "Share artwork"))
                    }
                    Unit
                }
                if (outcome.isFailure) withContext(Dispatchers.IO) { runCatching { resolver.delete(target, null, null) } }
                finished(outcome)
            }
        }
        try { render(target, ::complete) } catch (failure: Exception) { complete(Result.failure(failure)) }
    }
}
