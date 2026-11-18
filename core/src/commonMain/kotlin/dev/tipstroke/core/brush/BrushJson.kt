package dev.tipstroke.core.brush

import kotlinx.serialization.json.*

/** Strict typed access keeps the established brush schema without platform JSON dependencies. */
internal class BrushJson private constructor(private val values: MutableMap<String, JsonElement>) {
    constructor() : this(linkedMapOf())
    constructor(text: String) : this(Json.parseToJsonElement(text).jsonObject.toMutableMap())
    fun put(key: String, value: Any): BrushJson = apply {
        values[key] = when (value) {
            is BrushJson -> JsonObject(value.values)
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            else -> error("Unsupported brush field $key")
        }
    }
    private fun primitive(key: String) = values.getValue(key).jsonPrimitive
    fun getString(key: String): String = primitive(key).also { require(it.isString) }.content
    fun getDouble(key: String): Double = primitive(key).also { require(!it.isString) }.double
    fun getInt(key: String): Int = getDouble(key).let {
        require(it.isFinite() && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE && it == it.toInt().toDouble())
        it.toInt()
    }
    fun getBoolean(key: String): Boolean = primitive(key).also { require(!it.isString) }.boolean
    fun has(key: String) = values.containsKey(key)
    fun getJSONObject(key: String) = BrushJson(values.getValue(key).jsonObject.toMutableMap())
    fun optJSONObject(key: String): BrushJson? = values[key]?.let {
        if (it is JsonObject) BrushJson(it.toMutableMap()) else null
    }
    override fun toString(): String = JsonObject(values).toString()
}
