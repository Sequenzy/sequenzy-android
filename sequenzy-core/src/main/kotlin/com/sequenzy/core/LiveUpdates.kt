package com.sequenzy.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.SecureRandom

/**
 * A Live Update token reported to Sequenzy, the Android counterpart of an
 * ActivityKit token. Android issues none, so the SDK generates them:
 *
 * - [Kind.PUSH_TO_START]: one per observed activity type, so your backend
 *   can start activities of that type on this device.
 * - [Kind.UPDATE]: one per running activity, tagged with its activity id,
 *   so updates and ends reach it.
 *
 * Sequenzy sends their pushes to this installation's FCM token.
 */
data class LiveUpdateToken(
    val kind: Kind,
    val activityType: String,
    /** Set for update tokens; null for push-to-start tokens. */
    val activityId: String?,
    /** 64 lowercase hex characters. */
    val token: String,
) {
    enum class Kind(val wire: String) { PUSH_TO_START("push_to_start"), UPDATE("update") }

    val isValid: Boolean
        get() = TOKEN_PATTERN.matches(token) && isActivityType(activityType) &&
            (kind == Kind.PUSH_TO_START || activityId?.let(::isActivityId) == true)

    /** A newer token for the same slot replaces the stored one. */
    internal fun replaces(other: LiveUpdateToken): Boolean =
        token == other.token || (
            kind == other.kind && activityType == other.activityType &&
                (kind == Kind.PUSH_TO_START || activityId == other.activityId)
            )

    internal fun toJson(): JsonObject = buildJsonObject {
        put("kind", kind.wire)
        put("activityType", activityType)
        activityId?.let { put("activityId", it) }
        put("token", token)
    }

    companion object {
        private val TOKEN_PATTERN = Regex("^[0-9a-f]{64,200}$")
        private val TYPE_PATTERN = Regex("^[A-Za-z_][A-Za-z0-9_]{0,127}$")
        private val ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
        private val random = SecureRandom()

        /** The type name your backend starts, e.g. `DeliveryAttributes`; the same name as the iOS attributes type. */
        fun isActivityType(value: String) = TYPE_PATTERN.matches(value)

        /** Ids such as `order-1234` or `game:5521`. */
        fun isActivityId(value: String) = ID_PATTERN.matches(value)

        /** A new random token. */
        fun generate(): String {
            val bytes = ByteArray(32)
            random.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        internal fun fromJson(json: JsonObject): LiveUpdateToken? {
            val kind = Kind.entries.firstOrNull { it.wire == json.string("kind") } ?: return null
            val token = LiveUpdateToken(
                kind = kind,
                activityType = json.string("activityType") ?: return null,
                activityId = json.string("activityId").takeIf { kind == Kind.UPDATE },
                token = json.string("token") ?: return null,
            )
            return token.takeIf { it.isValid }
        }
    }
}

enum class LiveUpdateEvent(val wire: String) { START("start"), UPDATE("update"), END("end") }

/** The alert that came with a start, update or end; it lights the screen like a notification would. */
data class LiveUpdateAlert(val title: String?, val body: String?, val sound: String? = null)

/**
 * An FCM data message for a Live Update. Sequenzy sends one for every
 * start, update and end your backend requests, with the same content and
 * timestamp as the iOS push.
 */
data class LiveUpdatePayload(
    val event: LiveUpdateEvent,
    val activityType: String,
    val activityId: String,
    /** Unix seconds; a push not newer than the last one applied is ignored. */
    val timestamp: Long,
    val contentState: JsonObject,
    /** Start only. */
    val attributes: JsonObject?,
    val alert: LiveUpdateAlert?,
    /** Unix seconds after which the content is out of date. */
    val staleDate: Long?,
    /** End only: Unix seconds when the notification goes away. */
    val dismissalDate: Long?,
    val relevanceScore: Double?,
    /** Start only: the token Sequenzy registered for the new activity. */
    val updateToken: String?,
) {
    companion object {
        /** Reads FCM `RemoteMessage.data`. Returns null for anything that is not a Sequenzy Live Update. */
        fun fromData(data: Map<String, String>): LiveUpdatePayload? {
            if (data["sequenzyLiveUpdate"] != "1") return null
            fun value(key: String) = data[key]?.trim()?.takeIf { it.isNotEmpty() }
            val event = LiveUpdateEvent.entries.firstOrNull { it.wire == value("event") } ?: return null
            val activityType = value("activityType")?.takeIf(LiveUpdateToken::isActivityType) ?: return null
            val activityId = value("activityId")?.takeIf(LiveUpdateToken::isActivityId) ?: return null
            val timestamp = value("timestamp")?.toLongOrNull() ?: return null
            val contentState = value("contentState")?.let(::parseJsonObject) ?: return null
            val title = value("alertTitle")
            val body = value("alertBody")
            return LiveUpdatePayload(
                event = event,
                activityType = activityType,
                activityId = activityId,
                timestamp = timestamp,
                contentState = contentState,
                attributes = value("attributes")?.let(::parseJsonObject),
                alert = if (title != null || body != null) LiveUpdateAlert(title, body, value("alertSound")) else null,
                staleDate = value("staleDate")?.toLongOrNull(),
                dismissalDate = value("dismissalDate")?.toLongOrNull(),
                relevanceScore = value("relevanceScore")?.toDoubleOrNull(),
                updateToken = value("updateToken")?.lowercase()?.takeIf { Regex("^[0-9a-f]{64,200}$").matches(it) },
            )
        }
    }
}

/** One Live Update as your renderer draws it. */
data class LiveUpdateActivity(
    val activityType: String,
    val activityId: String,
    /** Fixed when it started: the start's attributes. */
    val attributes: Map<String, Any?>,
    /** The latest content state. */
    val contentState: Map<String, Any?>,
    /** When the content is out of date, if your backend set a stale date. */
    val staleDateMillis: Long?,
    /** True once the activity ended: show the final content. */
    val isEnded: Boolean,
    /** Grows with every change; content of an older revision is not drawn. */
    val revision: Long = 0,
)

/** What to do with a Live Update the SDK received or the app changed. */
sealed class LiveUpdateDecision {
    /** Post or update the notification. */
    data class Show(
        val activity: LiveUpdateActivity,
        val alert: LiveUpdateAlert?,
        /** Ended activities: when to remove the notification (null = leave it to the person). */
        val dismissAtMillis: Long?,
    ) : LiveUpdateDecision()

    /** Leave the notification as it is: [reason] says why (e.g. `stale`, `unknown_activity`, `dismissed`). */
    data class Ignore(val reason: String) : LiveUpdateDecision()
}

/** Ended Live Updates stay until dismissed, at most this long, as on iOS. */
const val LIVE_UPDATE_ENDED_LINGER_MS = 4L * 60 * 60 * 1000

/**
 * Android Live Updates: their tokens, the activities this device shows and
 * the rules for applying pushes. Owned by [SequenzyCore], whose lock it
 * shares, so identify and reset never interleave with it.
 */
class LiveUpdates internal constructor(
    private val core: SequenzyCore,
    private val store: SequenzyStore,
    private val now: () -> Long,
) {
    companion object {
        /** Stored tokens and activities; the newest are kept. */
        const val MAX_TOKENS = 32
        const val MAX_ACTIVITIES = 32
        internal const val KEY_TOKENS = "liveUpdateTokens"
        internal const val KEY_ACTIVITIES = "liveUpdateActivities"
        internal const val REGISTER_PATH = "/api/v1/push/live-activities"
        internal const val REMOVE_PATH = "/api/v1/push/live-activities/remove"
    }

    private val json = Json { ignoreUnknownKeys = true }

    private data class Record(
        val activityType: String,
        val activityId: String,
        val token: String,
        val attributes: JsonObject,
        val contentState: JsonObject,
        val timestamp: Long,
        /** "active", "ended" or "dismissed". */
        val state: String,
        val staleDate: Long?,
        val revision: Long = 0,
    ) {
        fun toActivity() = LiveUpdateActivity(
            activityType = activityType,
            activityId = activityId,
            attributes = attributes.toKotlinMap(),
            contentState = contentState.toKotlinMap(),
            staleDateMillis = staleDate?.let { it * 1000 },
            isEnded = state != "active",
            revision = revision,
        )

        fun toJson(): JsonObject = buildJsonObject {
            put("activityType", activityType)
            put("activityId", activityId)
            put("token", token)
            put("attributes", attributes)
            put("contentState", contentState)
            put("timestamp", timestamp)
            put("state", state)
            staleDate?.let { put("staleDate", it) }
            put("revision", revision)
        }
    }

    /**
     * Network work that [receive] leaves for [sync]: it runs on the FCM
     * thread, which must post the notification before anything slow.
     */
    private val pendingSends = mutableListOf<LiveUpdateToken>()
    private val pendingRemovals = mutableListOf<String>()

    /** Tokens reported so far; sent again after every device registration and unlinked on reset. */
    val tokens: List<LiveUpdateToken>
        get() = parseArray(store.getString(KEY_TOKENS)).mapNotNull(LiveUpdateToken::fromJson)

    /** Activities this device shows now. */
    suspend fun active(): List<LiveUpdateActivity> = core.locked {
        records().filter { it.state == "active" }.map(Record::toActivity)
    }

    /**
     * Reports this device can start activities of [activityType], with a
     * push-to-start token generated once per type. Returns false when the
     * type name is not valid.
     */
    suspend fun observe(activityType: String): Boolean = core.locked {
        if (!LiveUpdateToken.isActivityType(activityType)) {
            core.logLocked(LogLevel.ERROR, "observeLiveUpdates: \"$activityType\" is not a valid type name (letters, digits and underscores)")
            return@locked false
        }
        val existing = tokens.firstOrNull { it.kind == LiveUpdateToken.Kind.PUSH_TO_START && it.activityType == activityType }
        // A known token is linked again with every device registration, so
        // launches do not wait on a request (a push may be waiting to show).
        if (existing == null) {
            val token = LiveUpdateToken(LiveUpdateToken.Kind.PUSH_TO_START, activityType, null, LiveUpdateToken.generate())
            saveToken(token)
            send(token)
        }
        true
    }

    /**
     * Whether [activity] is still what this device should show: no newer
     * change, reset or dismissal happened since it was decided. Runs [post]
     * under the lock when it is, so a reset or end cannot slip in between.
     */
    suspend fun <T> ifCurrent(activity: LiveUpdateActivity, post: () -> T): T? = core.locked {
        val record = records().firstOrNull { it.activityId == activity.activityId } ?: return@locked null
        if (record.revision != activity.revision || record.state == "dismissed") return@locked null
        post()
    }

    /** Sends the token reports a received push left pending. */
    suspend fun sync() = core.locked {
        while (pendingRemovals.isNotEmpty()) reportRemoved(pendingRemovals.removeAt(0), unlinkSubscriber = false)
        while (pendingSends.isNotEmpty()) {
            val token = pendingSends.removeAt(0)
            // Skipped when it ended or was replaced meanwhile.
            if (tokens.any { it.token == token.token }) send(token)
        }
    }

    /**
     * Applies a received push. [canShow] is false when the app does not
     * render this activity type or may not post notifications; a start is
     * then refused, and Sequenzy stops counting it as running. Makes no
     * network request: call [sync] after posting the notification.
     */
    suspend fun receive(payload: LiveUpdatePayload, canShow: Boolean): LiveUpdateDecision = core.locked {
        val records = records()
        val record = records.firstOrNull { it.activityId == payload.activityId }
        if (record != null && payload.timestamp <= record.timestamp) return@locked LiveUpdateDecision.Ignore("stale")
        when (payload.event) {
            LiveUpdateEvent.START -> {
                if (!canShow) {
                    payload.updateToken?.let { pendingRemovals.add(it) }
                    return@locked LiveUpdateDecision.Ignore("not_shown")
                }
                val token = payload.updateToken ?: LiveUpdateToken.generate()
                startLocked(
                    Record(
                        activityType = payload.activityType,
                        activityId = payload.activityId,
                        token = token,
                        attributes = payload.attributes ?: JsonObject(emptyMap()),
                        contentState = payload.contentState,
                        timestamp = payload.timestamp,
                        state = "active",
                        staleDate = payload.staleDate,
                    ),
                    previous = record,
                    deferSend = true,
                )
                LiveUpdateDecision.Show(findActivity(payload.activityId)!!, payload.alert, null)
            }
            LiveUpdateEvent.UPDATE -> {
                if (record == null) return@locked LiveUpdateDecision.Ignore("unknown_activity")
                if (record.state != "active") return@locked LiveUpdateDecision.Ignore(record.state)
                if (!canShow) return@locked LiveUpdateDecision.Ignore("not_shown")
                val next = saveRecord(
                    record.copy(contentState = payload.contentState, timestamp = payload.timestamp, staleDate = payload.staleDate ?: record.staleDate),
                )
                LiveUpdateDecision.Show(next.toActivity(), payload.alert, null)
            }
            LiveUpdateEvent.END -> {
                if (record == null) {
                    // FCM may deliver an end before its start: remember it,
                    // so the late start is dropped as stale.
                    saveRecord(
                        Record(
                            activityType = payload.activityType,
                            activityId = payload.activityId,
                            token = "",
                            attributes = JsonObject(emptyMap()),
                            contentState = payload.contentState,
                            timestamp = payload.timestamp,
                            state = "ended",
                            staleDate = null,
                        ),
                    )
                    return@locked LiveUpdateDecision.Ignore("unknown_activity")
                }
                if (record.state != "active") return@locked LiveUpdateDecision.Ignore(record.state)
                // Sequenzy marked the token ended when FCM accepted the end.
                val next = saveRecord(record.copy(contentState = payload.contentState, timestamp = payload.timestamp, state = "ended"))
                forgetToken(record.token)
                LiveUpdateDecision.Show(
                    next.toActivity(),
                    payload.alert,
                    payload.dismissalDate?.let { it * 1000 } ?: (now() + LIVE_UPDATE_ENDED_LINGER_MS),
                )
            }
        }
    }

    /** Starts an activity from the app (for example when the person places an order) and reports it, so your backend can update and end it. */
    suspend fun start(
        activityType: String,
        activityId: String,
        attributes: Map<String, Any?>,
        contentState: Map<String, Any?>,
        staleDateMillis: Long? = null,
    ): LiveUpdateDecision = core.locked {
        if (!LiveUpdateToken.isActivityType(activityType) || !LiveUpdateToken.isActivityId(activityId)) {
            core.logLocked(LogLevel.ERROR, "Live Update start needs a type name and an activity id such as order-1234")
            return@locked LiveUpdateDecision.Ignore("invalid")
        }
        val previous = records().firstOrNull { it.activityId == activityId }
        startLocked(
            Record(
                activityType = activityType,
                activityId = activityId,
                token = LiveUpdateToken.generate(),
                attributes = toJsonObject(attributes),
                contentState = toJsonObject(contentState),
                // Pushes are ordered by the server's clock; app changes never
                // move that, so a fast device clock cannot hide updates.
                timestamp = previous?.timestamp ?: 0,
                state = "active",
                staleDate = staleDateMillis?.let { it / 1000 },
            ),
            previous = previous,
        )
        LiveUpdateDecision.Show(findActivity(activityId)!!, null, null)
    }

    /** Updates a running activity from the app. */
    suspend fun update(activityId: String, contentState: Map<String, Any?>, staleDateMillis: Long? = null): LiveUpdateDecision =
        core.locked {
            val record = records().firstOrNull { it.activityId == activityId && it.state == "active" }
                ?: return@locked LiveUpdateDecision.Ignore("unknown_activity")
            val next = saveRecord(
                record.copy(
                    contentState = toJsonObject(contentState),
                    staleDate = staleDateMillis?.let { it / 1000 } ?: record.staleDate,
                ),
            )
            LiveUpdateDecision.Show(next.toActivity(), null, null)
        }

    /**
     * Ends a running activity from the app and tells Sequenzy, so your
     * backend's later updates are not sent. [contentState] is the final
     * content (null keeps the current one).
     */
    suspend fun end(activityId: String, contentState: Map<String, Any?>? = null, dismissAtMillis: Long? = null): LiveUpdateDecision =
        core.locked {
            val record = records().firstOrNull { it.activityId == activityId && it.state == "active" }
                ?: return@locked LiveUpdateDecision.Ignore("unknown_activity")
            val next = saveRecord(
                record.copy(contentState = contentState?.let(::toJsonObject) ?: record.contentState, state = "ended"),
            )
            remove(record.token, unlinkSubscriber = false)
            LiveUpdateDecision.Show(next.toActivity(), null, dismissAtMillis ?: (now() + LIVE_UPDATE_ENDED_LINGER_MS))
        }

    /**
     * The person swiped the notification away. Android asks apps not to
     * post it again, so later pushes for it are ignored and Sequenzy stops
     * sending them.
     */
    suspend fun dismissed(activityId: String) = core.locked {
        val record = records().firstOrNull { it.activityId == activityId } ?: return@locked
        if (record.state == "dismissed") return@locked
        saveRecord(record.copy(state = "dismissed"))
        if (record.state == "active") remove(record.token, unlinkSubscriber = false)
    }

    // region Hooks SequenzyCore calls with its lock held

    /** After a device registration: links every token to the installation's current FCM token and contact. */
    internal suspend fun resendLocked() {
        for (token in tokens) send(token)
    }

    /**
     * Another person signed in without reset: the previous person's
     * activities stay theirs. Same as [resetLocked]; the device
     * registration that follows links the push-to-start tokens to the new
     * person.
     */
    internal suspend fun userChangedLocked(): List<String> = resetLocked()

    /**
     * Sign-out: unlinks every token, forgets the activities and returns the
     * ids still showing (running or ended) so their notifications can be
     * removed. Push-to-start tokens belong to the device and are registered
     * again, anonymously, with the next device registration.
     */
    internal suspend fun resetLocked(): List<String> {
        val running = records().filter { it.state != "dismissed" && it.token.isNotEmpty() }.map { it.activityId }
        pendingSends.clear()
        pendingRemovals.clear()
        val all = tokens
        saveTokens(all.filter { it.kind == LiveUpdateToken.Kind.PUSH_TO_START })
        store.putString(KEY_ACTIVITIES, null)
        for (token in all) reportRemoved(token.token, unlinkSubscriber = true)
        return running
    }

    // endregion

    private suspend fun startLocked(record: Record, previous: Record?, deferSend: Boolean = false) {
        // A restarted activity id gets a new token; the older one is retired
        // on the server when the new one registers.
        if (previous != null && previous.token != record.token) forgetToken(previous.token)
        saveRecord(record)
        val token = LiveUpdateToken(LiveUpdateToken.Kind.UPDATE, record.activityType, record.activityId, record.token)
        saveToken(token)
        if (deferSend) pendingSends.add(token) else send(token)
    }

    private fun findActivity(activityId: String) = records().firstOrNull { it.activityId == activityId }?.toActivity()

    private fun records(): List<Record> = parseArray(store.getString(KEY_ACTIVITIES)).mapNotNull { entry ->
        runCatching {
            Record(
                activityType = entry.string("activityType")!!,
                activityId = entry.string("activityId")!!,
                token = entry.string("token")!!,
                attributes = entry["attributes"]!!.jsonObject,
                contentState = entry["contentState"]!!.jsonObject,
                timestamp = entry["timestamp"]!!.jsonPrimitive.longOrNull!!,
                state = entry.string("state")!!,
                staleDate = (entry["staleDate"] as? JsonPrimitive)?.longOrNull,
                revision = (entry["revision"] as? JsonPrimitive)?.longOrNull ?: 0,
            )
        }.getOrNull()
    }

    /** Stores [record] with the next revision and returns what was stored. */
    private fun saveRecord(record: Record): Record {
        val all = records()
        val saved = record.copy(revision = (all.firstOrNull { it.activityId == record.activityId }?.revision ?: 0) + 1)
        val others = all.filter { it.activityId != record.activityId }
        // Running activities are kept before finished ones when trimming.
        val (running, finished) = (others + saved).partition { it.state == "active" }
        val kept = (finished.takeLast(maxOf(0, MAX_ACTIVITIES - running.size)) + running).takeLast(MAX_ACTIVITIES)
        store.putString(KEY_ACTIVITIES, JsonArray(kept.map(Record::toJson)).toString())
        return saved
    }

    private fun saveToken(token: LiveUpdateToken) {
        saveTokens(tokens.filter { !token.replaces(it) } + token)
    }

    private fun forgetToken(token: String) {
        saveTokens(tokens.filter { it.token != token })
    }

    private fun saveTokens(list: List<LiveUpdateToken>) {
        // Push-to-start tokens (one per observed type) are kept before the
        // oldest activities' update tokens.
        val (starts, updates) = list.partition { it.kind == LiveUpdateToken.Kind.PUSH_TO_START }
        val kept = (starts.takeLast(MAX_TOKENS) + updates.takeLast(maxOf(0, MAX_TOKENS - starts.size)))
        store.putString(KEY_TOKENS, if (kept.isEmpty()) null else buildJsonArray { kept.forEach { add(it.toJson()) } }.toString())
    }

    private suspend fun send(token: LiveUpdateToken) {
        // Sequenzy sends Live Updates to the FCM token; without one there is
        // nothing to link. The next device registration sends the tokens.
        val deviceToken = core.pushToken ?: return
        val body = buildJsonObject {
            put("companyId", core.configuration.companyId)
            put("publicKey", core.configuration.publicKey)
            put("platform", "android")
            put("kind", token.kind.wire)
            put("activityType", token.activityType)
            token.activityId?.let { put("activityId", it) }
            put("token", token.token)
            put("anonymousId", core.anonymousId)
            put("deviceToken", deviceToken)
            core.device.appVersion?.takeIf { it.isNotEmpty() }?.let { put("appVersion", it) }
            core.device.osVersion?.takeIf { it.isNotEmpty() }?.let { put("osVersion", it) }
        }
        val response = core.postWithIdentityLocked(REGISTER_PATH, body) ?: return
        if (response.status !in 200..299) {
            core.logLocked(LogLevel.ERROR, "Live Update registration failed: HTTP ${response.status}")
        }
    }

    /** Forgets [token] and tells Sequenzy to stop sending to it. */
    private suspend fun remove(token: String, unlinkSubscriber: Boolean) {
        forgetToken(token)
        reportRemoved(token, unlinkSubscriber)
    }

    private suspend fun reportRemoved(token: String, unlinkSubscriber: Boolean) {
        val body = buildJsonObject {
            put("companyId", core.configuration.companyId)
            put("publicKey", core.configuration.publicKey)
            put("token", token)
            if (unlinkSubscriber) put("unlinkSubscriber", true)
        }
        val response = core.postLocked(REMOVE_PATH, body) ?: return
        if (response.status !in 200..299) {
            core.logLocked(LogLevel.ERROR, "Could not report the Live Update ended (HTTP ${response.status})")
        }
    }

    private fun parseArray(raw: String?): List<JsonObject> =
        raw?.let { runCatching { (json.parseToJsonElement(it) as JsonArray).mapNotNull { e -> e as? JsonObject } }.getOrNull() }
            ?: emptyList()
}

private fun parseJsonObject(raw: String): JsonObject? =
    runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()

/** JSON as plain Kotlin values: String, Long, Double, Boolean, null, List and Map. */
fun JsonElement.toKotlin(): Any? = when (this) {
    is JsonNull -> null
    is JsonPrimitive -> if (isString) content else booleanOrNull ?: longOrNull ?: doubleOrNull ?: content
    is JsonArray -> map { it.toKotlin() }
    is JsonObject -> toKotlinMap()
}

fun JsonObject.toKotlinMap(): Map<String, Any?> = mapValues { (_, value) -> value.toKotlin() }
