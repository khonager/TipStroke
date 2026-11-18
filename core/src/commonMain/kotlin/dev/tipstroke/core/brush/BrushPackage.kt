package dev.tipstroke.core.brush

import dev.tipstroke.core.model.*

data class LibraryBrush(val preset: BrushPreset, val author: String = "", val description: String = "")

/** Portable, bounded, data-only brush packages; no executable content or external assets. */
object BrushPackage {
    const val MAX_BYTES = 512 * 1024

    fun encode(brush: LibraryBrush): String {
        val p = brush.preset
        return BrushJson().apply {
            put("format", "tipstroke-brush"); put("version", if (p.ink != null) 3 else 2)
            put("id", p.id.value); put("name", p.displayName)
            put("author", brush.author); put("description", brush.description)
            put("engine", p.engine.name); put("schemaVersion", p.schemaVersion)
            put("size", p.baseSizePx); put("opacity", p.opacity); put("hardness", p.hardness)
            put("spacing", p.spacing); put("stabilization", p.stabilization)
            put("pressureSizeStart", p.pressureToSize.start); put("pressureSizeEnd", p.pressureToSize.end)
            put("pressureSizeExponent", p.pressureToSize.exponent)
            put("pressureOpacityStart", p.pressureToOpacity.start); put("pressureOpacityEnd", p.pressureToOpacity.end)
            put("pressureOpacityExponent", p.pressureToOpacity.exponent)
            put("speedTaper", p.speedTaper); put("pointSize", p.pencilPointSize)
            put("tiltSensitivity", p.pencilTiltSensitivity); put("shadeSize", p.pencilShadeSize)
            put("shadeOpacity", p.pencilShadeOpacity); put("grain", p.pencilGrain)
            put("tiltMode", p.pencilTiltMode.name); put("shadeStart", p.pencilShadeStartRadians)
            put("shadeTransition", p.pencilShadeTransitionRadians)
            p.stamp?.let { put("stamp", encodeStamp(it)) }
            p.ink?.let { ink -> put("ink", BrushJson().apply {
                put("texture", ink.texture.name); put("aspectRatio", ink.aspectRatio)
                put("rounding", ink.rounding); put("rotationDegrees", ink.rotationDegrees)
                put("particles", ink.particles); put("accumulate", ink.accumulate)
                put("flow", ink.flow); put("tiltSize", ink.tiltSize)
            }) }
        }.toString()
    }

    private fun encodeTexture(t: BrushTexture) = BrushJson().put("width", t.width).put("height", t.height)
        .put("coverage", kotlin.io.encoding.Base64.encode(t.bytes()))

    private fun decodeTexture(o: BrushJson): BrushTexture {
        val width = o.getInt("width"); val height = o.getInt("height")
        require(o.getDouble("width") == width.toDouble() && o.getDouble("height") == height.toDouble()) { "Invalid source dimensions." }
        require(width in 1..256 && height in 1..256) { "Tip sources must be at most 256 × 256." }
        val data = o.getString("coverage")
        require(data.length <= 90_000) { "Tip source is too large." }
        return BrushTexture(width, height, kotlin.io.encoding.Base64.decode(data))
    }

    private fun encodeStamp(s: StampBrush) = BrushJson().apply {
        put("shape", s.shape.name); put("grain", s.grain.name); put("grainPlacement", s.grainPlacement.name)
        s.shapeTexture?.let { put("shapeTexture", encodeTexture(it)) }
        s.grainTexture?.let { put("grainTexture", encodeTexture(it)) }
        put("grainScale", s.grainScale); put("grainDepth", s.grainDepth); put("aspectRatio", s.aspectRatio)
        put("rotationDegrees", s.rotationDegrees); put("followDirection", s.followDirection)
        put("rotationJitter", s.rotationJitter); put("scatter", s.scatter); put("count", s.count)
        put("sizeJitter", s.sizeJitter); put("opacityJitter", s.opacityJitter); put("flow", s.flow)
        put("tiltSize", s.tiltSize); put("startTaperPx", s.startTaperPx); put("fadeOutPx", s.fadeOutPx)
        put("hueJitter", s.hueJitter); put("saturationJitter", s.saturationJitter)
    }

    private fun decodeStamp(o: BrushJson): StampBrush {
        fun n(key: String) = o.getDouble(key).toFloat().also { require(it.isFinite()) { "Invalid $key." } }
        require(o.getDouble("count") == o.getInt("count").toDouble())
        return StampBrush(
            shape = StampShape.valueOf(o.getString("shape")),
            shapeTexture = o.optJSONObject("shapeTexture")?.let(::decodeTexture),
            grain = StampGrain.valueOf(o.getString("grain")),
            grainTexture = o.optJSONObject("grainTexture")?.let(::decodeTexture),
            grainPlacement = GrainPlacement.valueOf(o.getString("grainPlacement")),
            grainScale = n("grainScale"), grainDepth = n("grainDepth"), aspectRatio = n("aspectRatio"),
            rotationDegrees = n("rotationDegrees"), followDirection = o.getBoolean("followDirection"),
            rotationJitter = n("rotationJitter"), scatter = n("scatter"), count = o.getInt("count"),
            sizeJitter = n("sizeJitter"), opacityJitter = n("opacityJitter"), flow = n("flow"), tiltSize = n("tiltSize"),
            startTaperPx = n("startTaperPx"), fadeOutPx = n("fadeOutPx"), hueJitter = n("hueJitter"), saturationJitter = n("saturationJitter"),
        )
    }

    fun decode(text: String): LibraryBrush {
        require(text.encodeToByteArray().size <= MAX_BYTES) { "Tip file is too large." }
        val o = BrushJson(text)
        require(o.getString("format") == "tipstroke-brush" && o.getDouble("version") in listOf(1.0, 2.0, 3.0)) { "Unsupported tip file version." }
        require(o.getDouble("schemaVersion") in listOf(2.0, 3.0, BrushPreset.CURRENT_SCHEMA_VERSION.toDouble())) { "Unsupported tip schema." }
        fun string(key: String, max: Int, nonempty: Boolean = false): String = o.getString(key).also {
            require(it.length <= max && (!nonempty || it.isNotBlank())) { "Invalid $key." }
        }
        fun number(key: String, min: Float, max: Float): Float = o.getDouble(key).toFloat().also {
            require(it.isFinite() && it in min..max) { "Invalid $key." }
        }
        fun curve(prefix: String) = PressureCurve(number(prefix + "Start", .01f, 1f),
            number(prefix + "End", 1f, 1f), number(prefix + "Exponent", .25f, 2f))
        val ink = o.optJSONObject("ink")?.let { value ->
            require(o.getDouble("version") == 3.0 && o.getInt("schemaVersion") >= 4 && !o.has("stamp")) { "Invalid Ink tip version." }
            fun n(key: String) = value.getDouble(key).toFloat().also { require(it.isFinite()) { "Invalid $key." } }
            InkBrush(InkTexture.valueOf(value.getString("texture")), n("aspectRatio"), n("rounding"),
                n("rotationDegrees"), value.getBoolean("particles"), value.getBoolean("accumulate"), n("flow"), n("tiltSize"))
        }
        require(!o.has("ink") || ink != null) { "Invalid Ink tip." }
        require(o.getDouble("version") != 3.0 || ink != null) { "Missing Ink tip definition." }
        return LibraryBrush(BrushPreset(
            schemaVersion = o.getInt("schemaVersion"),
            id = BrushId(string("id", 100, true)), displayName = string("name", 60, true),
            engine = BrushEngine.valueOf(o.getString("engine")),
            baseSizePx = number("size", 2f, 65536f), opacity = number("opacity", .05f, 1f),
            hardness = number("hardness", 0f, 1f), spacing = number("spacing", .001f, 2f),
            stabilization = number("stabilization", 0f, 1f),
            pressureToSize = curve("pressureSize"), pressureToOpacity = curve("pressureOpacity"),
            speedTaper = number("speedTaper", 0f, 1f), pencilPointSize = number("pointSize", .5f, 2f),
            pencilTiltSensitivity = number("tiltSensitivity", 0f, 1f), pencilShadeSize = number("shadeSize", 0f, 4f),
            pencilShadeOpacity = number("shadeOpacity", .2f, 1f), pencilGrain = number("grain", 0f, 1f),
            pencilTiltMode = PencilTiltMode.valueOf(o.getString("tiltMode")),
            pencilShadeStartRadians = number("shadeStart", BrushPreset.MIN_PENCIL_SHADE_START_RADIANS, BrushPreset.MAX_PENCIL_SHADE_START_RADIANS),
            pencilShadeTransitionRadians = number("shadeTransition", BrushPreset.MIN_PENCIL_SHADE_TRANSITION_RADIANS, BrushPreset.MAX_PENCIL_SHADE_TRANSITION_RADIANS),
            ink = ink,
            stamp = if (o.has("stamp")) {
                require(o.getDouble("version") == 2.0 && o.getInt("schemaVersion") >= 3) { "Invalid custom tip version." }
                decodeStamp(o.getJSONObject("stamp"))
            } else null,
        ), string("author", 80), string("description", 1000))
    }
}

