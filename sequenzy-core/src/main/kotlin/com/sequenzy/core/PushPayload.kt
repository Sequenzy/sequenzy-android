package com.sequenzy.core

/**
 * The fields Sequenzy adds to every push: inside `sequenzy` on APNs, flat
 * string data fields on FCM. Pushes from other senders do not parse.
 */
data class PushPayload(
    val title: String,
    val body: String,
    /** https link or app deep link to open on tap. */
    val url: String?,
    val imageUrl: String?,
    val pushSendId: String,
    val deviceId: String,
    /** Authenticates `delivered` / `clicked` reports for this device. */
    val clickToken: String,
    val eventsUrl: String,
) {
    companion object {
        /** Reads FCM `RemoteMessage.data`. Returns null for other senders. */
        fun fromData(data: Map<String, String>): PushPayload? {
            fun value(key: String) = data[key]?.trim()?.takeIf { it.isNotEmpty() }
            val pushSendId = value("pushSendId") ?: return null
            val deviceId = value("deviceId") ?: return null
            val clickToken = value("clickToken") ?: return null
            val eventsUrl = value("eventsUrl") ?: return null
            if (!eventsUrl.startsWith("https://") && !eventsUrl.startsWith("http://")) return null
            return PushPayload(
                title = value("title") ?: "",
                body = value("body") ?: "",
                url = value("url"),
                imageUrl = value("imageUrl"),
                pushSendId = pushSendId,
                deviceId = deviceId,
                clickToken = clickToken,
                eventsUrl = eventsUrl,
            )
        }
    }
}

enum class PushEventType(val wire: String) { DELIVERED("delivered"), CLICKED("clicked") }
