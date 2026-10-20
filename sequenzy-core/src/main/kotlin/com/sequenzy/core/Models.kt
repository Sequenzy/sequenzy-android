package com.sequenzy.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** An item in the contact's in-app feed: a message shown over the app, or a content card. */
data class InAppMessage(
    val id: String,
    val kind: Kind,
    val format: Format?,
    val position: Position?,
    val title: String,
    val body: String,
    val imageUrl: String?,
    val buttons: List<InAppButton>,
    val data: Map<String, String>,
    val style: InAppStyle,
    val pinned: Boolean,
    val createdAt: String,
    val expiresAt: String?,
    val displayedAt: String?,
    val readAt: String?,
    val dismissedAt: String?,
    /** Short line above the title; empty when there is none. */
    val eyebrow: String = "",
    /** Modals and full screens with an image. */
    val imagePlacement: ImagePlacement? = null,
) {
    enum class Kind(val wire: String) { MESSAGE("message"), CARD("card") }
    enum class Format(val wire: String) { MODAL("modal"), BANNER("banner"), FULLSCREEN("fullscreen") }

    /** Banners: top or bottom. Modals: bottom (a sheet) or center. */
    enum class Position(val wire: String) { TOP("top"), BOTTOM("bottom"), CENTER("center") }
    enum class ImagePlacement(val wire: String) { TOP("top"), BACKGROUND("background") }

    /** Messages default to a modal; banners and modals default to the bottom. */
    val resolvedFormat: Format get() = format ?: Format.MODAL
    val resolvedPosition: Position get() = position ?: Position.BOTTOM
    val isRead: Boolean get() = readAt != null

    companion object {
        fun fromJson(element: JsonElement): InAppMessage? {
            val obj = element as? JsonObject ?: return null
            val id = obj.string("id") ?: return null
            val kind = Kind.entries.firstOrNull { it.wire == obj.string("kind") } ?: return null
            return InAppMessage(
                id = id,
                kind = kind,
                format = Format.entries.firstOrNull { it.wire == obj.string("format") },
                position = Position.entries.firstOrNull { it.wire == obj.string("position") },
                title = obj.string("title") ?: "",
                body = obj.string("body") ?: "",
                imageUrl = obj.string("imageUrl"),
                buttons = (obj["buttons"] as? JsonArray)?.mapNotNull(InAppButton::fromJson) ?: emptyList(),
                data = (obj["data"] as? JsonObject)?.mapNotNull { (key, value) ->
                    (value as? JsonPrimitive)?.contentOrNull?.let { key to it }
                }?.toMap() ?: emptyMap(),
                style = InAppStyle.fromJson(obj["style"]),
                pinned = (obj["pinned"] as? JsonPrimitive)?.booleanOrNull ?: false,
                createdAt = obj.string("createdAt") ?: "",
                expiresAt = obj.string("expiresAt"),
                displayedAt = obj.string("displayedAt"),
                readAt = obj.string("readAt"),
                dismissedAt = obj.string("dismissedAt"),
                eyebrow = obj.string("eyebrow") ?: "",
                imagePlacement = ImagePlacement.entries.firstOrNull { it.wire == obj.string("imagePlacement") },
            )
        }

        /** Parses a `{ "messages": [...] }` feed response, skipping malformed items. */
        fun listFromFeed(element: JsonElement): List<InAppMessage> =
            (element.jsonObject["messages"]?.jsonArray ?: JsonArray(emptyList())).mapNotNull(::fromJson)
    }
}

data class InAppButton(val label: String, val action: Action, val url: String?) {
    enum class Action(val wire: String) { OPEN_URL("open_url"), DISMISS("dismiss") }

    companion object {
        fun fromJson(element: JsonElement): InAppButton? {
            val obj = element as? JsonObject ?: return null
            val label = obj.string("label") ?: return null
            val action = Action.entries.firstOrNull { it.wire == obj.string("action") } ?: Action.OPEN_URL
            return InAppButton(label, action, obj.string("url"))
        }
    }
}

/** Resolved colors (`#rrggbb`), radii in dp and the brand font stack. */
data class InAppStyle(
    val backgroundColor: String,
    val textColor: String,
    val mutedTextColor: String,
    val buttonColor: String,
    val buttonTextColor: String,
    val cornerRadius: Double,
    val buttonRadius: Double,
    val fontFamily: String,
) {
    /** Font families from the CSS stack without quotes or generic names. */
    val fontFamilies: List<String>
        get() = fontFamily.split(",")
            .map { it.trim().trim('"', '\'') }
            .filter { it.isNotEmpty() && it.lowercase() !in GENERIC_FAMILIES }

    companion object {
        private val GENERIC_FAMILIES = setOf("serif", "sans-serif", "monospace", "system-ui", "cursive")

        val DEFAULT = InAppStyle("#ffffff", "#111827", "#4b5563", "#111827", "#ffffff", 12.0, 8.0, "")

        fun fromJson(element: JsonElement?): InAppStyle {
            val obj = element as? JsonObject ?: return DEFAULT
            return InAppStyle(
                backgroundColor = obj.string("backgroundColor") ?: DEFAULT.backgroundColor,
                textColor = obj.string("textColor") ?: DEFAULT.textColor,
                mutedTextColor = obj.string("mutedTextColor") ?: DEFAULT.mutedTextColor,
                buttonColor = obj.string("buttonColor") ?: DEFAULT.buttonColor,
                buttonTextColor = obj.string("buttonTextColor") ?: DEFAULT.buttonTextColor,
                cornerRadius = (obj["cornerRadius"] as? JsonPrimitive)?.doubleOrNull ?: DEFAULT.cornerRadius,
                buttonRadius = (obj["buttonRadius"] as? JsonPrimitive)?.doubleOrNull ?: DEFAULT.buttonRadius,
                fontFamily = obj.string("fontFamily") ?: DEFAULT.fontFamily,
            )
        }
    }
}

enum class InAppEventType(val wire: String) {
    DISPLAYED("displayed"), CLICKED("clicked"), DISMISSED("dismissed"), READ("read")
}

/** Parses `#rrggbb` into an ARGB int with full alpha, or null. */
fun parseHexColor(hex: String): Int? {
    val value = hex.trim().removePrefix("#")
    if (value.length != 6) return null
    val rgb = value.toIntOrNull(16) ?: return null
    return (0xFF shl 24) or rgb
}

internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content
