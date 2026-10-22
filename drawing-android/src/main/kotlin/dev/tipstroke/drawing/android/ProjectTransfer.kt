package dev.tipstroke.drawing.android

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Portable packages contain committed projects, without decoding or flattening their tiles. */
object ProjectTransfer {
    val ioExecutor: java.util.concurrent.Executor get() = ProjectPersistence.executor
    const val MIME = "application/octet-stream"
    private val safeId = Regex("[A-Za-z0-9_-]+")

    fun exportProjects(library: DrawingLibrary, ids: List<String>, output: OutputStream) {
        require(ids.isNotEmpty()) { "Select at least one drawing" }
        val selected = ids.toSet()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("tipstroke.json"))
            zip.write(JSONObject().put("format", "TipStroke").put("version", 1)
                .put("drawings", JSONArray(ids.distinct())).toString().toByteArray())
            zip.closeEntry()
            val items = JSONArray()
            library.galleryItems().forEach { item ->
                when (item) {
                    is GalleryDrawing -> if (item.drawing.id in selected) items.put(JSONObject()
                        .put("kind", "drawing").put("id", item.drawing.id))
                    is GalleryStack -> {
                        val idsInStack = item.drawings.map { it.id }.filter { it in selected }
                        if (idsInStack.isNotEmpty()) items.put(JSONObject().put("kind", "stack")
                            .put("id", item.id).put("name", item.name).put("drawingIds", JSONArray(idsInStack)))
                    }
                }
            }
            zip.putNextEntry(ZipEntry("gallery.json"))
            zip.write(JSONObject().put("schemaVersion", 1).put("items", items).toString().toByteArray())
            zip.closeEntry()
            selected.forEach { id ->
                val directory = library.projectDirectory(id)
                require(File(directory, "manifest.json").isFile) { "Drawing is missing" }
                directory.walkTopDown().filter { it.isFile }.forEach { file ->
                    addFile(zip, "drawings/$id/${file.relativeTo(directory).invariantSeparatorsPath}", file)
                }
            }
        }
    }

    fun importProjects(library: DrawingLibrary, input: InputStream): Int {
        val staging = File(library.root, ".import-${UUID.randomUUID()}").apply { mkdirs() }
        val committed = mutableListOf<File>()
        try {
            var total = 0L
            var count = 0
            val seen = hashSetOf<String>()
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++count <= 100_000) { "Too many files in package" }
                    val name = entry.name
                    require(name.isNotEmpty() && !name.startsWith('/') && '\\' !in name &&
                        name.split('/').none { it == ".." || it == "." } && seen.add(name)) { "Invalid package path" }
                    val file = File(staging, name)
                    require(file.canonicalPath.startsWith(staging.canonicalPath + File.separator)) { "Invalid package path" }
                    if (entry.isDirectory) file.mkdirs() else {
                        file.parentFile?.mkdirs()
                        file.outputStream().use { out ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val size = zip.read(buffer)
                                if (size < 0) break
                                total += size
                                require(total <= 2L * 1024 * 1024 * 1024) { "Package exceeds the 2 GiB import limit" }
                                out.write(buffer, 0, size)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            val header = JSONObject(File(staging, "tipstroke.json").also { require(it.length() in 1..8_388_608) { "Invalid package header" } }.readText())
            require(header.getString("format") == "TipStroke" && header.getInt("version") == 1) { "Unsupported TipStroke package" }
            val entries = header.getJSONArray("drawings")
            require(entries.length() in 1..10_000) { "Invalid drawing count" }
            val mapping = linkedMapOf<String, String>()
            for (index in 0 until entries.length()) {
                val old = entries.getString(index)
                require(safeId.matches(old) && old !in mapping) { "Invalid drawing ID" }
                val directory = File(staging, "drawings/$old")
                validateProject(directory)
                val manifest = File(directory, "manifest.json")
                val json = JSONObject(manifest.readText())
                val id = library.newId()
                mapping[old] = id
                manifest.writeText(json.put("id", id).toString(2))
            }
            mapping.forEach { (old, id) ->
                val target = library.projectDirectory(id)
                check(File(staging, "drawings/$old").renameTo(target)) { "Could not install imported drawing" }
                committed += target
            }
            library.importGalleryLayout(File(staging, "gallery.json"), mapping)
            return mapping.size
        } catch (failure: Throwable) {
            committed.forEach { it.deleteRecursively() }
            throw failure
        } finally { staging.deleteRecursively() }
    }

    private fun validateProject(directory: File) {
        val manifest = File(directory, "manifest.json")
        require(manifest.length() in 1..8_388_608) { "Invalid drawing manifest" }
        val json = JSONObject(manifest.readText())
        require(json.getInt("schemaVersion") in 1..3) { "Unsupported drawing version" }
        require(json.getInt("widthPx") in 1..32768 && json.getInt("heightPx") in 1..32768) { "Invalid canvas dimensions" }
        json.getString("name")
        json.getLong("modifiedAtMillis")
        val layers = json.getJSONArray("layers")
        require(layers.length() in 1..1024) { "Invalid layer count" }
        val ids = hashSetOf<String>()
        for (i in 0 until layers.length()) {
            val layer = layers.getJSONObject(i)
            val id = layer.getString("id")
            require(safeId.matches(id) && ids.add(id)) { "Invalid layer ID" }
            layer.getString("name")
            require(layer.getString("kind") in listOf("raster", "image")) { "Unsupported layer type" }
            if (layer.getString("kind") == "image") {
                require(File(directory, "assets/$id.source").isFile) { "Missing image source" }
                layer.getInt("originalWidthPx"); layer.getInt("originalHeightPx")
                layer.getDouble("centerX"); layer.getDouble("centerY"); layer.getDouble("scale")
            }
        }
        directory.walkTopDown().filter { it.isFile && it.extension == "png" && it.name != "thumbnail.png" }.forEach { file ->
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.path, bounds)
            require(bounds.outWidth == 256 && bounds.outHeight == 256) { "Invalid raster tile dimensions" }
            val bitmap = android.graphics.BitmapFactory.decodeFile(file.path)
            requireNotNull(bitmap) { "Invalid raster tile" }
            bitmap.recycle()
        }
        json.optJSONObject("animation")?.let { animation ->
            val frames = animation.getJSONArray("frames")
            val frameIds = hashSetOf<String>()
            require(frames.length() in 1..10_000) { "Invalid frame count" }
            for (i in 0 until frames.length()) {
                val frame = frames.getJSONObject(i)
                require(safeId.matches(frame.getString("id")) && frameIds.add(frame.getString("id"))) { "Invalid frame ID" }
                require(frame.optInt("exposure", 1) in 1..600) { "Invalid frame exposure" }
            }
            require(animation.optInt("fps", 12) in 1..60) { "Invalid animation FPS" }
            dev.tipstroke.core.model.PlaybackMode.valueOf(animation.optString("playbackMode", "LOOP"))
        }
    }

    /** Render one project at a time; batch media are ordinary ZIP files. */
    fun exportMedia(context: Context, library: DrawingLibrary, ids: List<String>, uri: Uri, image: ExportFormat?, animation: AnimationExportFormat?) {
        require(ids.isNotEmpty())
        val resolver = context.contentResolver
        if (image == null) ids.forEach { id ->
            val manifest = JSONObject(File(library.projectDirectory(id), "manifest.json").readText())
            require(manifest.optJSONObject("animation") != null) {
                "${manifest.optString("name", "Drawing")} has no animation. Choose an image or TipStroke format instead."
            }
        }
        fun render(id: String, destination: Uri) {
            val loaded = ProjectPersistence.load(library.projectDirectory(id)).getOrThrow()
            val snapshot = DrawingSnapshot(loaded.widthPx, loaded.heightPx, loaded.selectedId, loaded.layers.map { layer ->
                when (layer) {
                    is LoadedRaster -> SavedRasterSnapshot(layer.id, layer.name, layer.visible, layer.opacity, layer.tiles, layer.colorUsage, layer.groupId)
                    is LoadedImage -> SavedImageSnapshot(layer.id, layer.name, layer.visible, layer.opacity, Uri.fromFile(layer.assetFile), layer.originalWidthPx, layer.originalHeightPx, layer.transform, layer.maskTiles, layer.groupId)
                }
            }, loaded.galleryRotationQuarterTurns, loaded.animation, loaded.backgroundColor, loaded.backgroundOpacity)
            try {
                if (image != null) ProjectPersistence.export(resolver, destination, snapshot, image, 90, 1f, false).getOrThrow()
                else ProjectPersistence.exportAnimation(resolver, destination, snapshot, requireNotNull(animation), 1f).getOrThrow()
            } finally { snapshot.recycle() }
        }
        if (ids.size == 1) { render(ids.single(), uri); return }
        val temporary = File.createTempFile("gallery-export", ".tmp", context.cacheDir)
        try {
            ZipOutputStream(requireNotNull(resolver.openOutputStream(uri, "w"))).use { zip ->
                ids.forEachIndexed { index, id ->
                    render(id, Uri.fromFile(temporary))
                    val name = JSONObject(File(library.projectDirectory(id), "manifest.json").readText()).getString("name")
                        .replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").take(80)
                    addFile(zip, "${index + 1}-$name.${image?.extension ?: animation!!.extension}", temporary)
                }
            }
        } finally { temporary.delete() }
    }

    private fun addFile(zip: ZipOutputStream, path: String, file: File) {
        zip.putNextEntry(ZipEntry(path))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }
}
