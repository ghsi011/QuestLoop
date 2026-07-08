package com.questloop.core.ai.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Shape-tolerant JSON accessors shared by the reverse-engineered OpenAI codecs.
 * Backend shapes drift: any field we expect to be a string/object/array can show
 * up as something else, and kotlinx's `.jsonPrimitive`/`.jsonObject`/`.jsonArray`
 * THROW on a mismatch. These keep the parsers' documented "return null/empty on
 * garbage" contracts (the app layer only catches IOException around them).
 */
internal fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

internal fun JsonElement?.intValueOrNull(): Int? = (this as? JsonPrimitive)?.intOrNull

internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

internal fun JsonElement?.asArray(): JsonArray? = this as? JsonArray
