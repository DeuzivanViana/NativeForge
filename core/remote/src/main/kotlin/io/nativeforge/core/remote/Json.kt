package io.nativeforge.core.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Small JSON helpers over kotlinx.serialization's tree model (no code generation needed). */
object Json {
    val parser = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    fun parseObject(text: String): JsonObject? = runCatching { parser.parseToJsonElement(text) as? JsonObject }.getOrNull()

    fun quote(value: String): String = JsonPrimitive(value).toString()

    /** Builds a JSON object from Kotlin values (String, Number, Boolean, null, lists, maps, JsonElement). */
    fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to element(v) })

    fun element(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Enum<*> -> JsonPrimitive(value.name)
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to element(v) })
        is Iterable<*> -> JsonArray(value.map(::element))
        is Array<*> -> JsonArray(value.map(::element))
        else -> JsonPrimitive(value.toString())
    }
}

fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

fun JsonElement.stringOrNull(): String? = runCatching { jsonPrimitive.contentOrNull }.getOrNull()
