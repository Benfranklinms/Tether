package dev.benfranklin.tether.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Loads the shared cross-language vectors from protocol/vectors. */
object Vectors {
    private val dir: File = File(System.getProperty("tether.vectors") ?: "../../protocol/vectors")

    fun load(name: String): JsonElement = Json.parseToJsonElement(File(dir, name).readText())

    fun array(name: String): JsonArray = load(name).jsonArray
}

fun JsonElement.str(key: String): String = jsonObject[key]!!.jsonPrimitive.content
fun JsonElement.obj(key: String): JsonObject = jsonObject[key]!!.jsonObject
fun JsonElement.arr(key: String): JsonArray = jsonObject[key]!!.jsonArray
