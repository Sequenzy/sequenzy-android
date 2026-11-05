package com.sequenzy.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * A call from a custom HTML in-app message to the app, sent by the Sequenzy JS bridge the server injects into
 * every document (`window.sequenzy`, plus Braze's `brazeBridge` / `appboyBridge`). Wire shape (protocol v1):
 * `{ "__sequenzyInApp": 1, "method": ..., "args": {...} }`.
 */
sealed class InAppHtmlBridgeMessage {
    /** Close the message (reported as dismissed). */
    data object Close : InAppHtmlBridgeMessage()

    /** Open a link through the app (reported as a click), then close. */
    data class OpenUrl(val url: String, val external: Boolean, val buttonId: String?) : InAppHtmlBridgeMessage()

    /** Report a click without closing (Braze `logClick`). */
    data class LogClick(val buttonId: String?) : InAppHtmlBridgeMessage()

    /** A custom event on the contact (`Sequenzy.track`). */
    data class Track(val name: String, val properties: Map<String, Any?>) : InAppHtmlBridgeMessage()

    /** Custom attributes on the contact (`Sequenzy.setProfile`). */
    data class SetAttributes(val attributes: Map<String, Any?>) : InAppHtmlBridgeMessage()

    /** The contact's name (`Sequenzy.setProfile`). */
    data class SetName(val firstName: String?, val lastName: String?) : InAppHtmlBridgeMessage()

    companion object {
        /** Messages larger than this are dropped, as the bridge itself does. */
        const val MAX_MESSAGE_BYTES = 32 * 1024

        /** Bridge calls honored per display; the rest are dropped. */
        const val MAX_CALLS_PER_DISPLAY = 50

        /**
         * Where custom HTML documents load: a fixed https origin Sequenzy owns, so the document has a real origin
         * (local storage works for survey scripts) that is never the app's own web content.
         */
        const val BASE_URL = "https://in-app.sequenzy.com/"

        private val json = Json { ignoreUnknownKeys = true }
        private val BUTTON_ID = Regex("^[A-Za-z0-9 _-]{1,255}$")
        private val SCHEME = Regex("^\\s*([A-Za-z][A-Za-z0-9+.-]*):")
        private val BLOCKED_SCHEMES = setOf("http", "javascript", "data", "vbscript", "file", "blob", "about", "filesystem")

        /** Parses one bridge message posted as JSON; null for anything malformed or unknown. */
        fun parse(raw: String): InAppHtmlBridgeMessage? {
            if (raw.toByteArray(Charsets.UTF_8).size > MAX_MESSAGE_BYTES) return null
            val element = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return null
            return parse(element)
        }

        fun parse(element: JsonElement): InAppHtmlBridgeMessage? {
            val obj = element as? JsonObject ?: return null
            val version = (obj["__sequenzyInApp"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
            if (version != 1.0) return null
            val method = (obj["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val args = obj["args"] as? JsonObject ?: JsonObject(emptyMap())
            fun string(key: String, max: Int = 255): String? =
                (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }?.take(max)
            fun buttonId(): String? = string("buttonId")?.takeIf(::isValidButtonId)
            return when (method) {
                "close" -> Close
                "openUrl" -> {
                    val url = string("url", 2048) ?: return null
                    OpenUrl(url, (args["external"] as? JsonPrimitive)?.booleanOrNull == true, buttonId())
                }
                "logClick" -> LogClick(buttonId())
                "track" -> {
                    val name = string("name") ?: return null
                    Track(name, (args["properties"] as? JsonObject)?.let(::toMap) ?: emptyMap())
                }
                "setAttributes" -> (args["attributes"] as? JsonObject)?.let { SetAttributes(toMap(it)) }
                "setName" -> {
                    val first = string("firstName")
                    val last = string("lastName")
                    if (first == null && last == null) null else SetName(first, last)
                }
                else -> null
            }
        }

        /** Braze's logClick id rule: 1-255 letters, numbers, spaces, dashes or underscores. */
        fun isValidButtonId(id: String): Boolean = BUTTON_ID.matches(id)

        /**
         * Links a message may open: https, or an app scheme (myapp://, mailto:, tel:). Never script, data, file or
         * plain-http URLs.
         */
        fun isAllowedUrl(url: String): Boolean {
            val scheme = SCHEME.find(url)?.groupValues?.get(1)?.lowercase() ?: return false
            return scheme == "https" || scheme !in BLOCKED_SCHEMES
        }

        private fun toMap(obj: JsonObject): Map<String, Any?> = obj.mapValues { (_, value) -> toValue(value) }

        private fun toValue(element: JsonElement): Any? = when (element) {
            is JsonNull -> null
            is JsonObject -> toMap(element)
            is JsonArray -> element.map(::toValue)
            is JsonPrimitive -> when {
                element.isString -> element.content
                element.booleanOrNull != null -> element.booleanOrNull
                element.longOrNull != null -> element.longOrNull
                else -> element.doubleOrNull ?: element.contentOrNull
            }
        }
    }
}
