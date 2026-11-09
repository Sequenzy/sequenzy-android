package com.sequenzy.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

const val SEQUENZY_SDK_VERSION = "0.1.3"

data class SequenzyConfiguration(
    /** Publishable key (`seq_pk_...`) restricted to this app's package name. */
    val publicKey: String,
    val companyId: String,
    /** Package name or bundle ID, sent as `x-sequenzy-app-id`. */
    val appId: String,
    val apiBaseUrl: String = DEFAULT_API_BASE_URL,
    /** `android` or `ios`: the push platform tokens are registered for. */
    val platform: String = "android",
    val userAgent: String = "sequenzy-android/$SEQUENZY_SDK_VERSION",
) {
    companion object {
        const val DEFAULT_API_BASE_URL = "https://api.sequenzy.com"
    }
}

/** Optional device details sent with push registration. */
data class DeviceInfo(
    val appVersion: String? = null,
    val osVersion: String? = null,
    val deviceName: String? = null,
    val locale: String? = Locale.getDefault().toLanguageTag(),
    val timezone: String? = TimeZone.getDefault().id,
)

/** Asks the host app's backend for a fresh identity token for `email`. */
fun interface IdentityTokenProvider {
    suspend fun fetchIdentityToken(email: String): String
}

/**
 * Asks the host app's backend for a fresh identity token for a user
 * identified with `identifyUser`: mint it for [externalId], plus [email] when
 * it is set.
 */
fun interface UserIdentityTokenProvider {
    suspend fun fetchIdentityToken(externalId: String, email: String?): String
}

enum class LogLevel { NONE, ERROR, INFO, DEBUG }

fun interface SequenzyLogger {
    fun log(level: LogLevel, message: String)
}

sealed class SequenzyException(message: String) : Exception(message) {
    class NotIdentified : SequenzyException("Call identify or identifyUser with an identity token first")
    class IdentityRejected : SequenzyException("The identity token was rejected")
    class Http(val status: Int, message: String?) : SequenzyException("HTTP $status ${message ?: ""}".trim())
}

internal data class QueuedEvent(
    val name: String,
    val eventId: String,
    val properties: JsonObject,
    val occurredAt: String,
    /** `screen`, `commerce` or `geofence`; null for custom events. */
    val type: String? = null,
    /** `enter` or `exit`, for geofence events. */
    val transition: String? = null,
    val geofenceId: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        type?.let { put("type", it) }
        put("name", name)
        put("eventId", eventId)
        put("properties", properties)
        put("occurredAt", occurredAt)
        transition?.let { put("transition", it) }
        geofenceId?.let { put("geofenceId", it) }
    }
}

/** Result of [SequenzyCore.subscribe]. */
sealed class SubscribeResult {
    data object Success : SubscribeResult()
    data class Failure(val error: String, val retryable: Boolean) : SubscribeResult()
}

/**
 * The platform-independent SDK engine: identity, the event queue, push
 * registration and the in-app feed. The Android `Sequenzy` facade owns one.
 * All public methods are suspend functions safe to call concurrently; they
 * run one at a time.
 */
class SequenzyCore(
    val configuration: SequenzyConfiguration,
    private val transport: SequenzyTransport = HttpUrlConnectionTransport(),
    private val store: SequenzyStore = InMemoryStore(),
    @Volatile var device: DeviceInfo = DeviceInfo(),
    @Volatile var identityTokenProvider: IdentityTokenProvider? = null,
    @Volatile var logLevel: LogLevel = LogLevel.ERROR,
    /**
     * Reads the OS notification permission, sent with every registration so
     * the server knows who can see a push. Set by the Android layer.
     */
    @Volatile var pushPermissionReader: (() -> PushPermissionStatus?)? = null,
    /** True when the app can ask for permission, so the feed includes push primers. */
    @Volatile var handlesPushPermission: Boolean = false,
    private val logger: SequenzyLogger = SequenzyLogger { level, message -> println("[Sequenzy] $level: $message") },
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    /** Fresh tokens for users identified with [identifyUser]. */
    @Volatile var userIdentityTokenProvider: UserIdentityTokenProvider? = null,
) {
    companion object {
        const val MAX_QUEUED_EVENTS = 100
        const val MAX_BATCH_SIZE = 20
        const val MAX_EVENT_AGE_MS = 24L * 60 * 60 * 1000
        const val PUSH_REFRESH_INTERVAL_MS = 24L * 60 * 60 * 1000
        const val MAX_BACKOFF_MS = 5L * 60 * 1000
        /** Server limit for one event's properties, serialized. */
        const val MAX_PROPERTIES_BYTES = 8 * 1024
        /** Placement keys one request can ask for. */
        const val MAX_PLACEMENTS_PER_REQUEST = 10
        const val PROVIDER_RETRY_MS = 60_000L
        const val PROVIDER_TIMEOUT_MS = 20_000L
        /** Server limit for an external ID (the contact's `externalId`). */
        const val MAX_EXTERNAL_ID_LENGTH = 255

        private const val KEY_ANONYMOUS_ID = "anonymousId"
        private const val KEY_EMAIL = "email"
        private const val KEY_EXTERNAL_ID = "externalId"
        private const val KEY_IDENTITY_TOKEN = "identityToken"
        private const val KEY_EVENTS = "events"
        private const val KEY_PUSH_TOKEN = "pushToken"
        private const val KEY_PUSH_REGISTRATION = "pushRegistration"
        private const val KEY_PROFILE = "profile"
        private const val KEY_GEOFENCE_TRANSITIONS = "geofenceTransitions"

        fun isRetryable(status: Int) = status == 0 || status == 408 || status == 425 || status == 429 || status >= 500
    }

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private var events: MutableList<QueuedEvent> = loadEvents()
    private var backoffMs = 0L
    private var nextAttemptAt: Long? = null
    private var retryJob: Job? = null
    private var identityBlocked = false
    /** After a failed provider call, don't ask again before this time. */
    private var providerRetryAt = 0L

    /** Android Live Updates: tokens, received pushes and app-started activities. */
    val liveUpdates: LiveUpdates = LiveUpdates(this, store) { now() }

    /**
     * Called with the ids of Live Updates that stopped belonging to this
     * device's user (reset, or another person signed in), so the Android
     * layer removes their notifications.
     */
    @Volatile var onLiveUpdatesRemoved: ((List<String>) -> Unit)? = null

    // region Identity

    /** Stable per installation until [reset]. */
    val anonymousId: String
        get() = store.getString(KEY_ANONYMOUS_ID)?.takeIf { it.isNotEmpty() }
            ?: UUID.randomUUID().toString().also { store.putString(KEY_ANONYMOUS_ID, it) }

    val email: String? get() = store.getString(KEY_EMAIL)

    /** The user ID passed to [identifyUser], or null. */
    val externalId: String? get() = store.getString(KEY_EXTERNAL_ID)

    /**
     * One string per signed-in contact: the external ID when the app
     * identified the user by it, otherwise the email; null while anonymous.
     * Compare it to notice that another user signed in. The space keeps an
     * external ID from ever equalling an email.
     */
    val contactKey: String? get() = contactKey(email, externalId)

    private fun contactKey(email: String?, externalId: String?): String? =
        externalId?.let { "external $it" } ?: email

    /** The identifiers every identified request carries. */
    private fun identityFields(): JsonObject = buildJsonObject {
        email?.let { put("email", it) }
        externalId?.let { put("externalId", it) }
    }

    val pushToken: String? get() = store.getString(KEY_PUSH_TOKEN)

    suspend fun queuedEventCount(): Int = mutex.withLock { events.size }

    /** Links this installation to the contact with this email. Pass the token your backend minted, or rely on the provider. */
    suspend fun identify(email: String, identityToken: String? = null) = mutex.withLock {
        val normalized = email.trim().lowercase()
        if (!normalized.contains("@") || normalized.contains(" ")) {
            log(LogLevel.ERROR, "identify was called with an invalid email address")
            return@withLock
        }
        identifyLocked(normalized, null, identityToken)
    }

    /**
     * Links this installation to the contact with your own user ID, for users
     * without an email (phone login, a hidden address). Pass [email] too when
     * you have it. The identity token must be minted for exactly these
     * identifiers; without one, [userIdentityTokenProvider] is asked.
     */
    suspend fun identifyUser(externalId: String, email: String? = null, identityToken: String? = null) = mutex.withLock {
        val id = externalId.trim()
        if (id.isEmpty() || id.length > MAX_EXTERNAL_ID_LENGTH) {
            log(LogLevel.ERROR, "identifyUser needs a user ID of 1 to $MAX_EXTERNAL_ID_LENGTH characters")
            return@withLock
        }
        val normalizedEmail = email?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (normalizedEmail != null && (!normalizedEmail.contains("@") || normalizedEmail.contains(" "))) {
            log(LogLevel.ERROR, "identifyUser was called with an invalid email address")
            return@withLock
        }
        identifyLocked(normalizedEmail, id, identityToken)
    }

    private suspend fun identifyLocked(nextEmail: String?, nextExternalId: String?, identityToken: String?) {
        val previous = contactKey
        val contactChanged = previous != contactKey(nextEmail, nextExternalId)
        val identifiersChanged = email != nextEmail || externalId != nextExternalId
        if (previous != null && contactChanged) {
            // Events and profile changes queued for the previous user must
            // not be sent as the new one.
            events.clear()
            persistEvents()
            store.putString(KEY_PROFILE, null)
            // So are the previous user's running Live Updates; the
            // device's push-to-start tokens move to the new contact.
            liveUpdatesRemoved(liveUpdates.userChangedLocked())
        }
        if (identifiersChanged) {
            // A token verifies only with the exact identifiers it was minted for.
            store.putString(KEY_EMAIL, nextEmail)
            store.putString(KEY_EXTERNAL_ID, nextExternalId)
            store.putString(KEY_IDENTITY_TOKEN, null)
        }
        // Another contact's recent geofence transitions must not suppress this one's.
        if (contactChanged) store.putString(KEY_GEOFENCE_TRANSITIONS, null)
        if (!identityToken.isNullOrEmpty()) store.putString(KEY_IDENTITY_TOKEN, identityToken)
        identityBlocked = false
        providerRetryAt = 0
        registerPushLocked(force = identifiersChanged)
        flushLocked()
    }

    /** Forgets the user: unregisters the push token and clears identity, queue and anonymous id. */
    suspend fun reset() = mutex.withLock {
        store.getString(KEY_PUSH_TOKEN)?.let { token ->
            val body = buildJsonObject {
                put("companyId", configuration.companyId)
                put("publicKey", configuration.publicKey)
                put("platform", configuration.platform)
                put("token", token)
            }
            runCatching { post("/api/v1/push/app-devices/remove", body) }
                .onSuccess { if (it.status !in 200..299) log(LogLevel.ERROR, "Could not unregister the push token (HTTP ${it.status})") }
                .onFailure { log(LogLevel.ERROR, "Could not unregister the push token: ${it.message}") }
        }
        liveUpdatesRemoved(liveUpdates.resetLocked())
        store.putString(KEY_EMAIL, null)
        store.putString(KEY_EXTERNAL_ID, null)
        store.putString(KEY_IDENTITY_TOKEN, null)
        store.putString(KEY_PUSH_REGISTRATION, null)
        store.putString(KEY_ANONYMOUS_ID, UUID.randomUUID().toString())
        store.putString(KEY_PROFILE, null)
        store.putString(KEY_GEOFENCE_TRANSITIONS, null)
        events.clear()
        persistEvents()
        retryJob?.cancel()
        retryJob = null
        backoffMs = 0
        nextAttemptAt = null
        identityBlocked = false
    }

    // endregion

    // region Events

    /** Queues a custom event; it is sent once the user is identified. */
    suspend fun track(name: String, properties: Map<String, Any?> = emptyMap()) =
        enqueue(null, name, properties)

    /**
     * Records that the user opened a screen, as the built-in "Screen Viewed"
     * event that segments and sequences can use.
     */
    suspend fun screen(name: String, properties: Map<String, Any?> = emptyMap()) =
        enqueue("screen", "screen", properties + ("screen" to name.trim()))

    /** Browse abandonment and product interest, like the web SDK. */
    suspend fun viewedProduct(product: CommerceProduct) =
        enqueue("commerce", "product_viewed", mapOf("product" to product.toMap()))

    suspend fun addedToCart(line: CommerceCartLine) =
        enqueue("commerce", "product_added_to_cart", mapOf("product" to line.toMap(), "cartLine" to line.toMap()))

    suspend fun removedFromCart(line: CommerceCartLine) =
        enqueue("commerce", "product_removed_from_cart", mapOf("product" to line.toMap(), "cartLine" to line.toMap()))

    /** The whole cart, for cart abandonment. */
    suspend fun viewedCart(cart: CommerceCart) = enqueue("commerce", "cart_viewed", mapOf("cart" to cart.toMap()))

    suspend fun viewedCollection(collection: CommerceCollection) =
        enqueue("commerce", "collection_viewed", mapOf("collection" to collection.toMap()))

    suspend fun searched(query: String) = enqueue("commerce", "search_submitted", mapOf("searchQuery" to query.trim()))

    private suspend fun enqueue(type: String?, name: String, properties: Map<String, Any?>) = mutex.withLock {
        val trimmed = name.trim()
        val screen = properties["screen"]
        if (trimmed.isEmpty() || (type == "screen" && (screen !is String || screen.isEmpty()))) {
            log(
                LogLevel.ERROR,
                if (type == "screen") "screen was called without a screen name" else "track was called without an event name",
            )
            return@withLock
        }
        val json = toJsonObject(properties)
        if (json.toString().toByteArray(Charsets.UTF_8).size > MAX_PROPERTIES_BYTES) {
            // The server would reject the whole batch with 413; drop just this one.
            log(LogLevel.ERROR, "Dropped \"$trimmed\": properties are larger than 8 KB")
            return@withLock
        }
        events.add(QueuedEvent(trimmed, UUID.randomUUID().toString(), json, formatIso8601(now()), type))
        while (events.size > MAX_QUEUED_EVENTS) events.removeAt(0)
        persistEvents()
        flushLocked()
    }

    /** Sends queued events now. */
    suspend fun flush() = mutex.withLock { flushLocked() }

    /**
     * Sets the signed-in contact's name and custom attributes. Changes are kept
     * on the device and sent once identified, like events; later calls merge
     * into a pending update.
     */
    suspend fun setProfile(update: ProfileUpdate) = mutex.withLock {
        val incoming = update.toJson()
        val attributes = incoming["attributes"] as? JsonObject
        if (attributes != null && attributes.toString().toByteArray(Charsets.UTF_8).size > MAX_PROPERTIES_BYTES) {
            log(LogLevel.ERROR, "setProfile attributes are larger than 8 KB")
            return@withLock
        }
        val previous = parseObject(store.getString(KEY_PROFILE)) ?: JsonObject(emptyMap())
        val previousAttributes = previous["attributes"] as? JsonObject
        val merged = buildJsonObject {
            previous.forEach { (key, value) -> put(key, value) }
            incoming.forEach { (key, value) -> put(key, value) }
            if (previousAttributes != null || attributes != null) {
                put(
                    "attributes",
                    JsonObject((previousAttributes ?: JsonObject(emptyMap())) + (attributes ?: JsonObject(emptyMap()))),
                )
            }
        }
        store.putString(KEY_PROFILE, merged.toString())
        flushLocked()
    }

    /**
     * Subscribes an email through a published form, for people who are not
     * signed in (newsletter or waitlist screens). Does not identify the device.
     */
    suspend fun subscribe(request: SubscribeRequest): SubscribeResult {
        val email = request.email.trim()
        if (!email.contains("@") || email.any { it.isWhitespace() }) {
            return SubscribeResult.Failure("Enter a valid email address", retryable = false)
        }
        val body = buildJsonObject {
            put("companyId", configuration.companyId)
            put("publicKey", configuration.publicKey)
            put("formId", request.formId)
            put("email", email)
            request.firstName?.let { put("firstName", it) }
            request.lastName?.let { put("lastName", it) }
            request.phone?.let { put("phone", it) }
            request.attributes?.let { put("attributes", toJsonObject(it)) }
        }
        return runCatching { post("/api/v1/app-subscribe", body) }.fold(
            onSuccess = { response ->
                if (response.status in 200..299) {
                    SubscribeResult.Success
                } else {
                    val parsed = parseObject(response.body)
                    SubscribeResult.Failure(
                        parsed?.string("message") ?: parsed?.string("error") ?: "HTTP ${response.status}",
                        retryable = isRetryable(response.status),
                    )
                }
            },
            onFailure = { SubscribeResult.Failure(it.message ?: "Network error", retryable = true) },
        )
    }

    private suspend fun flushLocked() {
        parseObject(store.getString(KEY_PROFILE))?.let { profile ->
            if (contactKey == null || identityBlocked) return
            val next = nextAttemptAt
            if (next != null && next > now()) return
            when (sendProfile(profile)) {
                BatchOutcome.RETRY_LATER -> {
                    scheduleRetry()
                    return
                }
                BatchOutcome.STOP -> return
                else -> {
                    backoffMs = 0
                    nextAttemptAt = null
                    store.putString(KEY_PROFILE, null)
                }
            }
        }
        dropStaleEvents()
        while (events.isNotEmpty()) {
            if (contactKey == null || identityBlocked) return
            val next = nextAttemptAt
            if (next != null && next > now()) return
            val batch = events.take(MAX_BATCH_SIZE)
            when (sendBatch(batch)) {
                BatchOutcome.DELIVERED -> {
                    backoffMs = 0
                    nextAttemptAt = null
                    remove(batch)
                }
                BatchOutcome.DROPPED -> remove(batch)
                BatchOutcome.RETRY_LATER -> {
                    scheduleRetry()
                    return
                }
                BatchOutcome.STOP -> return
            }
        }
    }

    private enum class BatchOutcome { DELIVERED, DROPPED, RETRY_LATER, STOP }

    private suspend fun sendBatch(batch: List<QueuedEvent>): BatchOutcome {
        val result = withIdentity { identity, token ->
            post(
                "/api/v1/app-events",
                buildJsonObject {
                    put("companyId", configuration.companyId)
                    put("publicKey", configuration.publicKey)
                    identity.forEach { (key, value) -> put(key, value) }
                    put("identityToken", token)
                    put(
                        "events",
                        buildJsonArray {
                            batch.forEach { event ->
                                add(event.toJson())
                            }
                        },
                    )
                },
            )
        }
        return when (result) {
            is IdentifiedResult.NotIdentified, is IdentifiedResult.Rejected -> BatchOutcome.STOP
            is IdentifiedResult.Failed -> {
                log(LogLevel.INFO, "Event delivery failed, will retry: ${result.error.message}")
                BatchOutcome.RETRY_LATER
            }
            is IdentifiedResult.Response -> {
                val response = result.response
                when {
                    response.status in 200..299 -> {
                        logRejected(response.body)
                        BatchOutcome.DELIVERED
                    }
                    isRetryable(response.status) -> BatchOutcome.RETRY_LATER
                    else -> {
                        log(LogLevel.ERROR, "Dropping ${batch.size} events: HTTP ${response.status} ${errorMessage(response.body) ?: ""}")
                        BatchOutcome.DROPPED
                    }
                }
            }
        }
    }

    private suspend fun sendProfile(profile: JsonObject): BatchOutcome {
        val result = withIdentity { identity, token ->
            post(
                "/api/v1/app-attributes",
                buildJsonObject {
                    put("companyId", configuration.companyId)
                    put("publicKey", configuration.publicKey)
                    identity.forEach { (key, value) -> put(key, value) }
                    put("identityToken", token)
                    profile.forEach { (key, value) -> put(key, value) }
                },
            )
        }
        return when (result) {
            is IdentifiedResult.NotIdentified, is IdentifiedResult.Rejected -> BatchOutcome.STOP
            is IdentifiedResult.Failed -> BatchOutcome.RETRY_LATER
            is IdentifiedResult.Response -> when {
                result.response.status in 200..299 -> BatchOutcome.DELIVERED
                isRetryable(result.response.status) -> BatchOutcome.RETRY_LATER
                else -> {
                    log(LogLevel.ERROR, "Profile update rejected: HTTP ${result.response.status} ${errorMessage(result.response.body) ?: ""}")
                    BatchOutcome.DROPPED
                }
            }
        }
    }

    private fun logRejected(body: String) {
        val rejected = parseObject(body)?.get("rejected") as? JsonArray ?: return
        rejected.forEach { entry ->
            val error = ((entry as? JsonObject)?.get("error") as? JsonPrimitive)?.content ?: "unknown error"
            log(LogLevel.ERROR, "Event rejected: $error")
        }
    }

    private fun remove(batch: List<QueuedEvent>) {
        val ids = batch.map { it.eventId }.toSet()
        events.removeAll { it.eventId in ids }
        persistEvents()
    }

    private fun dropStaleEvents() {
        val cutoff = now() - MAX_EVENT_AGE_MS
        val before = events.size
        events.removeAll { (parseIso8601(it.occurredAt) ?: Long.MIN_VALUE) < cutoff }
        if (events.size != before) {
            log(LogLevel.INFO, "Dropped ${before - events.size} events older than 24 hours")
            persistEvents()
        }
    }

    private fun scheduleRetry() {
        backoffMs = (if (backoffMs == 0L) 1_000L else backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        val delayMs = backoffMs
        nextAttemptAt = now() + delayMs
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(delayMs)
            mutex.withLock {
                nextAttemptAt = null
                flushLocked()
            }
        }
    }

    private fun loadEvents(): MutableList<QueuedEvent> {
        val raw = store.getString(KEY_EVENTS) ?: return mutableListOf()
        return runCatching {
            json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
                val obj = element.jsonObject
                QueuedEvent(
                    name = obj.string("name") ?: return@mapNotNull null,
                    eventId = obj.string("eventId") ?: return@mapNotNull null,
                    properties = obj["properties"] as? JsonObject ?: JsonObject(emptyMap()),
                    occurredAt = obj.string("occurredAt") ?: return@mapNotNull null,
                    type = obj.string("type"),
                    transition = obj.string("transition"),
                    geofenceId = obj.string("geofenceId"),
                )
            }.toMutableList()
        }.getOrElse { mutableListOf() }
    }

    private fun persistEvents() {
        val array = buildJsonArray {
            events.forEach { event ->
                add(event.toJson())
            }
        }
        store.putString(KEY_EVENTS, array.toString())
    }

    // endregion

    // region Push

    /** Stores the FCM or APNs token and registers it with Sequenzy. */
    suspend fun setPushToken(token: String) = mutex.withLock {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return@withLock
        val changed = store.getString(KEY_PUSH_TOKEN) != trimmed
        store.putString(KEY_PUSH_TOKEN, trimmed)
        registerPushLocked(force = changed)
    }

    /** Registers the token when it or the user changed, or the last registration is a day old. */
    suspend fun registerPushIfNeeded(force: Boolean = false) = mutex.withLock { registerPushLocked(force) }

    private suspend fun registerPushLocked(force: Boolean) {
        val token = store.getString(KEY_PUSH_TOKEN) ?: return
        val identity = contactKey ?: ""
        val permission = runCatching { pushPermissionReader?.invoke() }.getOrNull()
        if (!force) {
            val last = parseObject(store.getString(KEY_PUSH_REGISTRATION))
            val lastAt = (last?.get("registeredAt") as? JsonPrimitive)?.longOrNull
            if (last != null && last.string("token") == token && last.string("email") == identity &&
                (permission == null || last.string("permission") == permission.wire) &&
                lastAt != null && now() - lastAt < PUSH_REFRESH_INTERVAL_MS
            ) {
                return
            }
        }

        val base = buildJsonObject {
            put("companyId", configuration.companyId)
            put("publicKey", configuration.publicKey)
            put("platform", configuration.platform)
            put("token", token)
            put("anonymousId", anonymousId)
            permission?.let { put("permission", it.wire) }
            device.appVersion?.takeIf { it.isNotEmpty() }?.let { put("appVersion", it) }
            device.osVersion?.takeIf { it.isNotEmpty() }?.let { put("osVersion", it) }
            device.deviceName?.takeIf { it.isNotEmpty() }?.let { put("deviceName", it) }
            device.locale?.takeIf { it.isNotEmpty() }?.let { put("locale", it) }
            device.timezone?.takeIf { it.isNotEmpty() }?.let { put("timezone", it) }
        }

        val response: HttpResponse = if (contactKey != null) {
            when (
                val result = withIdentity { fields, identityToken ->
                    post("/api/v1/push/app-devices", JsonObject(base + fields + mapOf("identityToken" to JsonPrimitive(identityToken))))
                }
            ) {
                is IdentifiedResult.Response -> result.response
                // Keep the device reachable; it links on the next identify with a valid token.
                is IdentifiedResult.NotIdentified, is IdentifiedResult.Rejected ->
                    runCatching { post("/api/v1/push/app-devices", base) }.getOrNull() ?: return
                is IdentifiedResult.Failed -> {
                    log(LogLevel.INFO, "Push registration failed: ${result.error.message}")
                    return
                }
            }
        } else {
            runCatching { post("/api/v1/push/app-devices", base) }.getOrElse {
                log(LogLevel.INFO, "Push registration failed: ${it.message}")
                return
            }
        }

        if (response.status !in 200..299) {
            log(LogLevel.ERROR, "Push registration failed: HTTP ${response.status} ${errorMessage(response.body) ?: ""}")
            return
        }
        val parsed = parseObject(response.body)
        val identified = (parsed?.get("identified") as? JsonPrimitive)?.booleanOrNull ?: false
        store.putString(
            KEY_PUSH_REGISTRATION,
            buildJsonObject {
                put("token", token)
                put("email", if (identified) identity else "")
                put("registeredAt", now())
                permission?.let { put("permission", it.wire) }
            }.toString(),
        )
        if ((parsed?.get("pushConfigured") as? JsonPrimitive)?.booleanOrNull == false) {
            log(LogLevel.INFO, "Device registered, but ${configuration.platform} push credentials are not set up in Sequenzy yet")
        }
        // Live Update tokens are sent to this FCM token and contact: link them
        // again whenever either may have changed.
        if (configuration.platform == "android") liveUpdates.resendLocked()
    }

    /** Reports a push as delivered or clicked; the payload's click token authenticates it. */
    suspend fun reportPush(type: PushEventType, payload: PushPayload) {
        val body = buildJsonObject {
            put("pushSendId", payload.pushSendId)
            put("deviceId", payload.deviceId)
            put("token", payload.clickToken)
            put("type", type.wire)
        }
        runCatching {
            transport.send(
                HttpRequest(
                    url = payload.eventsUrl,
                    headers = mapOf("content-type" to "application/json", "user-agent" to configuration.userAgent),
                    body = body.toString(),
                ),
            )
        }.onSuccess {
            if (it.status !in 200..299) log(LogLevel.ERROR, "Push ${type.wire} report failed: HTTP ${it.status}")
        }.onFailure {
            log(LogLevel.ERROR, "Push ${type.wire} report failed: ${it.message}")
        }
    }

    // endregion

    // region In-app

    /** Open messages or cards for the identified contact, in server order. */
    suspend fun fetchFeed(kind: InAppMessage.Kind? = null, limit: Int? = null): List<InAppMessage> =
        fetchFeed(kind, limit, null)

    /**
     * Open messages or cards in the formats the caller renders. [formats] null means the native formats, so
     * `html` messages arrive only when listed.
     */
    suspend fun fetchFeed(
        kind: InAppMessage.Kind?,
        limit: Int?,
        formats: List<InAppMessage.Format>?,
    ): List<InAppMessage> = mutex.withLock {
        val result = withIdentity { identity, token ->
            post(
                "/api/v1/in-app/feed",
                buildJsonObject {
                    put("companyId", configuration.companyId)
                    put("publicKey", configuration.publicKey)
                    identity.forEach { (key, value) -> put(key, value) }
                    put("identityToken", token)
                    kind?.let { put("kind", it.wire) }
                    limit?.let { put("limit", it) }
                    formats?.let { list -> put("formats", JsonArray(list.map { JsonPrimitive(it.wire) })) }
                    // Push primers come only when this app can ask for permission
                    if (handlesPushPermission) {
                        put("actions", JsonArray(listOf(JsonPrimitive(InAppButton.Action.REQUEST_PUSH_PERMISSION.wire))))
                    }
                },
            )
        }
        when (result) {
            is IdentifiedResult.NotIdentified -> throw SequenzyException.NotIdentified()
            is IdentifiedResult.Rejected -> throw SequenzyException.IdentityRejected()
            is IdentifiedResult.Failed -> throw result.error
            is IdentifiedResult.Response -> {
                val response = result.response
                if (response.status !in 200..299) throw SequenzyException.Http(response.status, errorMessage(response.body))
                InAppMessage.listFromFeed(json.parseToJsonElement(response.body))
            }
        }
    }

    /**
     * What each placement shows the identified contact: its highest-priority open item. Keys missing from the result
     * have nothing to show, so their slots collapse. Up to [MAX_PLACEMENTS_PER_REQUEST] keys.
     */
    suspend fun fetchPlacements(placements: List<String>): Map<String, InAppMessage> = mutex.withLock {
        val keys = placements.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_PLACEMENTS_PER_REQUEST)
        if (keys.isEmpty()) return@withLock emptyMap()
        val result = withIdentity { identity, token ->
            post(
                "/api/v1/in-app/feed/placements",
                buildJsonObject {
                    put("companyId", configuration.companyId)
                    put("publicKey", configuration.publicKey)
                    identity.forEach { (key, value) -> put(key, value) }
                    put("identityToken", token)
                    put("placements", JsonArray(keys.map { JsonPrimitive(it) }))
                    if (handlesPushPermission) {
                        put("actions", JsonArray(listOf(JsonPrimitive(InAppButton.Action.REQUEST_PUSH_PERMISSION.wire))))
                    }
                },
            )
        }
        when (result) {
            is IdentifiedResult.NotIdentified -> throw SequenzyException.NotIdentified()
            is IdentifiedResult.Rejected -> throw SequenzyException.IdentityRejected()
            is IdentifiedResult.Failed -> throw result.error
            is IdentifiedResult.Response -> {
                val response = result.response
                if (response.status !in 200..299) throw SequenzyException.Http(response.status, errorMessage(response.body))
                InAppMessage.mapFromPlacements(json.parseToJsonElement(response.body), keys)
            }
        }
    }

    /** Reports `displayed`, `clicked`, `dismissed` or `read` for a feed item. */
    suspend fun report(type: InAppEventType, messageId: String): Boolean = report(type, messageId, null)

    /**
     * Like [report], with [buttonId] naming the control clicked in a custom HTML
     * message. A push primer's click carries the permission read after asking.
     */
    suspend fun report(
        type: InAppEventType,
        messageId: String,
        buttonId: String?,
        pushPermission: PushPermissionStatus? = null,
    ): Boolean = mutex.withLock {
        val result = withIdentity { identity, token ->
            post(
                "/api/v1/in-app/feed/events",
                buildJsonObject {
                    put("companyId", configuration.companyId)
                    put("publicKey", configuration.publicKey)
                    identity.forEach { (key, value) -> put(key, value) }
                    put("identityToken", token)
                    put("messageId", messageId)
                    put("type", type.wire)
                    if (buttonId != null && type == InAppEventType.CLICKED && InAppHtmlBridgeMessage.isValidButtonId(buttonId)) {
                        put("buttonId", buttonId)
                    }
                    pushPermission?.let { put("pushPermission", it.wire) }
                },
            )
        }
        val ok = result is IdentifiedResult.Response && result.response.status in 200..299
        if (!ok) log(LogLevel.ERROR, "Could not report in-app ${type.wire} for $messageId")
        ok
    }

    // endregion

    // region Geofences

    /** The nearest active geofences around a position, rounded to two decimals here. */
    suspend fun fetchNearbyGeofences(latitude: Double, longitude: Double, limit: Int): NearbyGeofences = mutex.withLock {
        val result = withIdentity { identity, token ->
            post(
                "/api/v1/geofences/nearby",
                buildJsonObject {
                    put("companyId", configuration.companyId)
                    put("publicKey", configuration.publicKey)
                    identity.forEach { (key, value) -> put(key, value) }
                    put("identityToken", token)
                    put("latitude", Geofencing.roundCoordinate(latitude))
                    put("longitude", Geofencing.roundCoordinate(longitude))
                    put("limit", limit.coerceIn(1, 99))
                },
            )
        }
        when (result) {
            is IdentifiedResult.NotIdentified -> throw SequenzyException.NotIdentified()
            is IdentifiedResult.Rejected -> throw SequenzyException.IdentityRejected()
            is IdentifiedResult.Failed -> throw result.error
            is IdentifiedResult.Response -> {
                val response = result.response
                if (response.status !in 200..299) throw SequenzyException.Http(response.status, errorMessage(response.body))
                NearbyGeofences.fromJson(json.parseToJsonElement(response.body))
            }
        }
    }

    /**
     * Queues an enter or exit the OS reported for the identified contact. The
     * refresh region, a repeat of the same transition within a minute and any
     * transition while nobody is identified are ignored. Returns whether it was queued.
     */
    suspend fun trackGeofenceTransition(
        geofenceId: String,
        transition: GeofenceTransition,
        atMillis: Long? = null,
    ): Boolean = trackGeofenceTransitions(listOf(geofenceId), transition, atMillis) == 1

    /**
     * [trackGeofenceTransition] for several geofences the OS reported at once:
     * all are queued and stored before anything is sent, so a slow network
     * cannot cost the later ones. Returns how many were queued.
     */
    suspend fun trackGeofenceTransitions(
        geofenceIds: List<String>,
        transition: GeofenceTransition,
        atMillis: Long? = null,
    ): Int = mutex.withLock {
        // Visits belong to a contact; never queue one for whoever signs in next.
        if (contactKey == null) return@withLock 0
        val at = atMillis ?: now()
        val recent = (parseObject(store.getString(KEY_GEOFENCE_TRANSITIONS)) ?: JsonObject(emptyMap()))
            .mapValues { (_, value) -> value.jsonPrimitive.content.toLongOrNull() ?: 0L }
            .toMutableMap()
        var queued = 0
        for (id in geofenceIds.map { it.trim() }.distinct()) {
            if (id.isEmpty() || id == GeofenceRegion.REFRESH_REGION_ID) continue
            val key = "$id:${transition.wire}"
            if (Geofencing.isDuplicate(recent[key], at)) {
                log(LogLevel.DEBUG, "Ignored repeated geofence ${transition.wire} for $id")
                continue
            }
            recent[key] = at
            events.add(
                QueuedEvent(
                    name = "geofence",
                    eventId = UUID.randomUUID().toString(),
                    properties = JsonObject(emptyMap()),
                    occurredAt = formatIso8601(at),
                    type = "geofence",
                    transition = transition.wire,
                    geofenceId = id,
                ),
            )
            queued++
        }
        if (queued == 0) return@withLock 0
        val cutoff = now() - MAX_EVENT_AGE_MS
        store.putString(
            KEY_GEOFENCE_TRANSITIONS,
            buildJsonObject { recent.filterValues { it > cutoff }.forEach { (k, v) -> put(k, v) } }.toString(),
        )
        while (events.size > MAX_QUEUED_EVENTS) events.removeAt(0)
        persistEvents()
        flushLocked()
        queued
    }

    // endregion

    // region Identity tokens

    private sealed class IdentifiedResult {
        data class Response(val response: HttpResponse) : IdentifiedResult()
        data object NotIdentified : IdentifiedResult()
        data object Rejected : IdentifiedResult()
        data class Failed(val error: Throwable) : IdentifiedResult()
    }

    /**
     * Runs [operation] with the stored identifiers and token, asking the
     * provider when none is stored, and once more when the server rejects it.
     */
    private suspend fun withIdentity(operation: suspend (identity: JsonObject, token: String) -> HttpResponse): IdentifiedResult {
        if (contactKey == null || identityBlocked) return IdentifiedResult.NotIdentified
        val identity = identityFields()
        var usedProvider = false
        var token = store.getString(KEY_IDENTITY_TOKEN)
        if (token == null) {
            token = tokenFromProvider()
            usedProvider = true
        }
        var current = token ?: run {
            log(LogLevel.INFO, "No identity token yet; pass one to identify or identifyUser, or set an identity token provider")
            return IdentifiedResult.NotIdentified
        }
        while (true) {
            val response = try {
                operation(identity, current)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                return IdentifiedResult.Failed(error)
            }
            if (!isIdentityRejection(response)) return IdentifiedResult.Response(response)
            store.putString(KEY_IDENTITY_TOKEN, null)
            if (!usedProvider) {
                usedProvider = true
                val fresh = tokenFromProvider()
                if (fresh != null) {
                    current = fresh
                    continue
                }
            }
            identityBlocked = true
            log(LogLevel.ERROR, "The identity token was rejected; call identify or identifyUser again with a fresh token")
            return IdentifiedResult.Rejected
        }
    }

    /**
     * A fresh token for the current identity: from [userIdentityTokenProvider]
     * for a user identified by external ID, otherwise from
     * [identityTokenProvider] with the email.
     */
    private suspend fun tokenFromProvider(): String? {
        val contact = contactKey
        val email = email
        val externalId = externalId
        val request: suspend () -> String = when {
            externalId != null -> {
                val provider = userIdentityTokenProvider ?: return null
                suspend { provider.fetchIdentityToken(externalId, email) }
            }
            email != null -> {
                val provider = identityTokenProvider ?: return null
                suspend { provider.fetchIdentityToken(email) }
            }
            else -> return null
        }
        if (now() < providerRetryAt) return null
        return try {
            // Runs while the SDK's lock is held: a provider that never returns
            // (or calls back into the SDK) must not stall every later call.
            val token = kotlinx.coroutines.withTimeout(PROVIDER_TIMEOUT_MS) { request() }
            token.takeIf { it.isNotEmpty() && contactKey == contact }
                ?.also { store.putString(KEY_IDENTITY_TOKEN, it) }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
            providerRetryAt = now() + PROVIDER_RETRY_MS
            log(LogLevel.ERROR, "The identity token provider failed: ${error.message}; retrying in a minute")
            null
        }
    }

    private fun isIdentityRejection(response: HttpResponse): Boolean {
        if (response.status != 401 && response.status != 403) return false
        return parseObject(response.body)?.string("code") == "invalid_web_tracking_identity"
    }

    // endregion

    // region Live Update plumbing (LiveUpdates.kt holds the rules)

    internal suspend fun <T> locked(block: suspend () -> T): T = mutex.withLock { block() }

    internal fun logLocked(level: LogLevel, message: String) = log(level, message)

    /** Posts anonymously; null when the request could not be sent. */
    internal suspend fun postLocked(path: String, body: JsonObject): HttpResponse? =
        runCatching { post(path, body) }.getOrNull()

    /**
     * Posts with the signed-in contact's identity, like device registration:
     * without a usable identity token it registers anonymously and links on
     * the next identify. Null when the request could not be sent.
     */
    internal suspend fun postWithIdentityLocked(path: String, body: JsonObject): HttpResponse? {
        if (contactKey == null) return postLocked(path, body)
        return when (
            val result = withIdentity { fields, identityToken ->
                post(path, JsonObject(body + fields + mapOf("identityToken" to JsonPrimitive(identityToken))))
            }
        ) {
            is IdentifiedResult.Response -> result.response
            is IdentifiedResult.NotIdentified, is IdentifiedResult.Rejected -> postLocked(path, body)
            is IdentifiedResult.Failed -> {
                log(LogLevel.INFO, "Live Update registration failed: ${result.error.message}")
                null
            }
        }
    }

    private fun liveUpdatesRemoved(activityIds: List<String>) {
        if (activityIds.isEmpty()) return
        runCatching { onLiveUpdatesRemoved?.invoke(activityIds) }
    }

    // endregion

    // region HTTP and helpers

    private suspend fun post(path: String, body: JsonObject): HttpResponse =
        transport.send(
            HttpRequest(
                url = configuration.apiBaseUrl.trimEnd('/') + path,
                headers = mapOf(
                    "content-type" to "application/json",
                    "x-sequenzy-key" to configuration.publicKey,
                    "x-sequenzy-app-id" to configuration.appId,
                    "user-agent" to configuration.userAgent,
                ),
                body = body.toString(),
            ),
        )

    private fun parseObject(raw: String?): JsonObject? =
        raw?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }

    private fun errorMessage(body: String): String? = parseObject(body)?.string("error")

    private fun log(level: LogLevel, message: String) {
        if (level == LogLevel.NONE || level.ordinal > logLevel.ordinal) return
        logger.log(level, message)
    }

    // endregion
}

/** Converts event properties to JSON. Unsupported values become strings. */
fun toJsonElement(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Int, is Long, is Short, is Byte -> JsonPrimitive((value as Number).toLong())
    // NaN and infinity are not JSON; the server would reject the batch.
    is Number -> value.toDouble().let { if (it.isFinite()) JsonPrimitive(it) else JsonNull }
    is Map<*, *> -> JsonObject(value.entries.associate { (key, item) -> key.toString() to toJsonElement(item) })
    is Iterable<*> -> JsonArray(value.map(::toJsonElement))
    is Array<*> -> JsonArray(value.map(::toJsonElement))
    else -> JsonPrimitive(value.toString())
}

internal fun toJsonObject(properties: Map<String, Any?>): JsonObject =
    JsonObject(properties.mapValues { (_, value) -> toJsonElement(value) })

private fun isoFormat(pattern: String) = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

internal fun formatIso8601(millis: Long): String = isoFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").format(Date(millis))

internal fun parseIso8601(value: String): Long? =
    runCatching { isoFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").parse(value)?.time }.getOrNull()
        ?: runCatching { isoFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").parse(value)?.time }.getOrNull()
