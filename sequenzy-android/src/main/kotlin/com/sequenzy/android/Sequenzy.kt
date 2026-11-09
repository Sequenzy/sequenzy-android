package com.sequenzy.android

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.sequenzy.core.CommerceCart
import com.sequenzy.core.CommerceCartLine
import com.sequenzy.core.CommerceCollection
import com.sequenzy.core.CommerceProduct
import com.sequenzy.core.DeviceInfo
import com.sequenzy.core.GeofenceTransition
import com.sequenzy.core.IdentityTokenProvider
import com.sequenzy.core.InAppButton
import com.sequenzy.core.InAppEventType
import com.sequenzy.core.InAppMessage
import com.sequenzy.core.LogLevel
import com.sequenzy.core.NearbyGeofences
import com.sequenzy.core.ProfileUpdate
import com.sequenzy.core.PushEventType
import com.sequenzy.core.PushPayload
import com.sequenzy.core.PushPermissionStatus
import com.sequenzy.core.SequenzyConfiguration
import com.sequenzy.core.SequenzyCore
import com.sequenzy.core.SequenzyException
import com.sequenzy.core.SequenzyLogger
import com.sequenzy.core.SubscribeRequest
import com.sequenzy.core.SubscribeResult
import com.sequenzy.core.UserIdentityTokenProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/** Lets the app veto messages and take over URL handling. */
interface SequenzyInAppListener {
    /** Return false to keep a message from showing now; it is offered again next foreground. */
    fun shouldShow(message: InAppMessage): Boolean = true

    /** Return true when the app handled the URL itself. */
    fun handleUrl(url: Uri, message: InAppMessage?): Boolean = false
}

/**
 * The Sequenzy SDK for Android.
 *
 * ```kotlin
 * Sequenzy.configure(application, publicKey = "seq_pk_...", companyId = "...")
 * Sequenzy.identify(user.email, identityTokenFromYourBackend)
 * Sequenzy.track("workout_completed", mapOf("minutes" to 30))
 * ```
 */
object Sequenzy {
    private const val TAG = "Sequenzy"
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * identify, reset, track and push registration run one at a time in call
     * order: `track(x); reset()` must record x for the user who was signed
     * in, and `reset(); identify(b)` must leave b signed in.
     */
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED).also { channel ->
        scope.launch {
            for (command in channel) {
                try {
                    command()
                } catch (error: Exception) {
                    // Only the SDK's own shutdown ends the loop; a cancellation
                    // thrown by app code (e.g. the token provider) must not
                    // stop every later command.
                    if (error is kotlinx.coroutines.CancellationException && !isActive) throw error
                    Log.e(TAG, "Sequenzy command failed: ${error.message}")
                }
            }
        }
    }

    private fun enqueue(command: suspend () -> Unit) {
        commands.trySend(command)
    }

    @Volatile
    private var core: SequenzyCore? = null
    private var application: Application? = null
    private var currentActivity: WeakReference<Activity>? = null
    private var presenter: InAppPresenter? = null
    private var lastFeedFetchAt = 0L
    private var showInAppMessages = true
    private var pollJob: kotlinx.coroutines.Job? = null

    /** Notification small icon; defaults to the app icon. */
    @JvmStatic
    var notificationIcon: Int = 0

    @JvmStatic
    var inAppListener: SequenzyInAppListener? = null

    /** How often the feed is fetched while the app is in the foreground. */
    @JvmStatic
    var feedRefreshIntervalMs: Long = 60_000

    /**
     * Show custom HTML in-app messages (surveys, Braze-style custom HTML) in a sandboxed WebView. Set false
     * before `configure` to leave them in the feed for another app or SDK version.
     */
    @JvmStatic
    var showsHtmlMessages: Boolean = true

    private val displayedFormats: List<InAppMessage.Format>
        get() = if (showsHtmlMessages) {
            InAppMessage.Format.entries.toList()
        } else {
            InAppMessage.Format.entries.filter { it != InAppMessage.Format.HTML }
        }

    @JvmStatic
    val isConfigured: Boolean get() = core != null

    /**
     * Call once from `Application.onCreate`.
     *
     * @param appId defaults to the package name; add it to the key's allowed app IDs.
     * @param identityTokenProvider fetches a fresh identity token from your backend.
     * @param userIdentityTokenProvider the same for users identified with [identifyUser]:
     *   mint the token for the external ID (and the email, when it is passed).
     * @param showInAppMessages set false to present messages yourself from [fetchMessages].
     */
    @JvmStatic
    @JvmOverloads
    fun configure(
        application: Application,
        publicKey: String,
        companyId: String,
        appId: String = application.packageName,
        apiBaseUrl: String = SequenzyConfiguration.DEFAULT_API_BASE_URL,
        identityTokenProvider: IdentityTokenProvider? = null,
        showInAppMessages: Boolean = true,
        logLevel: LogLevel = LogLevel.ERROR,
        userIdentityTokenProvider: UserIdentityTokenProvider? = null,
    ) {
        if (core != null) return
        this.application = application
        this.showInAppMessages = showInAppMessages
        if (notificationIcon == 0) notificationIcon = application.applicationInfo.icon
        core = SequenzyCore(
            configuration = SequenzyConfiguration(publicKey, companyId, appId, apiBaseUrl),
            store = SharedPreferencesStore(application),
            device = deviceInfo(application),
            identityTokenProvider = identityTokenProvider,
            logLevel = logLevel,
            logger = SequenzyLogger { level, message ->
                if (level == LogLevel.ERROR) Log.e(TAG, message) else Log.i(TAG, message)
            },
            scope = scope,
            pushPermissionReader = { PushPermissions.status(application) },
            handlesPushPermission = true,
            userIdentityTokenProvider = userIdentityTokenProvider,
        )
        // Live Updates of a person who signed out (or was replaced by another
        // sign-in) must not stay on this device.
        core?.onLiveUpdatesRemoved = { ids -> SequenzyLiveUpdates.cancel(application, ids) }
        presenter = InAppPresenter()
        application.registerActivityLifecycleCallbacks(ActivityTracker)
        ProcessLifecycleOwner.get().lifecycle.addObserver(ForegroundObserver)
        FirebaseTokens.fetchIfAvailable()
    }

    // region Identity and events

    @JvmStatic
    @JvmOverloads
    fun identify(email: String, identityToken: String? = null) {
        val core = requireCore() ?: return
        enqueue {
            core.identify(email, identityToken)
            val signedIn = core.contactKey
            withContext(Dispatchers.Main) { presenter?.signedIn(signedIn) }
            SequenzyPlacements.identityChanged(signedIn)
            refreshMessages(force = true)
        }
    }

    /**
     * Identifies the user by your own user ID, for users without an email
     * (phone login, a hidden address). Pass [email] too when you have it; the
     * identity token must be minted for exactly these identifiers.
     */
    @JvmStatic
    @JvmOverloads
    fun identifyUser(externalId: String, email: String? = null, identityToken: String? = null) {
        val core = requireCore() ?: return
        enqueue {
            core.identifyUser(externalId, email, identityToken)
            val signedIn = core.contactKey
            withContext(Dispatchers.Main) { presenter?.signedIn(signedIn) }
            SequenzyPlacements.identityChanged(signedIn)
            refreshMessages(force = true)
        }
    }

    @JvmStatic
    fun reset() {
        val core = requireCore() ?: return
        enqueue {
            core.reset()
            // Views belong to the main thread, whichever thread called reset.
            mainHandler.post { presenter?.dismissAll() }
            SequenzyPlacements.identityChanged(null)
        }
    }

    @JvmStatic
    @JvmOverloads
    fun track(name: String, properties: Map<String, Any?> = emptyMap()) {
        val core = requireCore() ?: return
        enqueue { core.track(name, properties) }
    }

    /** Records a screen view as the built-in "Screen Viewed" event. */
    @JvmStatic
    @JvmOverloads
    fun screen(name: String, properties: Map<String, Any?> = emptyMap()) {
        val core = requireCore() ?: return
        enqueue { core.screen(name, properties) }
    }

    @JvmStatic
    fun viewedProduct(product: CommerceProduct) {
        val core = requireCore() ?: return
        enqueue { core.viewedProduct(product) }
    }

    @JvmStatic
    fun addedToCart(line: CommerceCartLine) {
        val core = requireCore() ?: return
        enqueue { core.addedToCart(line) }
    }

    @JvmStatic
    fun removedFromCart(line: CommerceCartLine) {
        val core = requireCore() ?: return
        enqueue { core.removedFromCart(line) }
    }

    @JvmStatic
    fun viewedCart(cart: CommerceCart) {
        val core = requireCore() ?: return
        enqueue { core.viewedCart(cart) }
    }

    @JvmStatic
    fun viewedCollection(collection: CommerceCollection) {
        val core = requireCore() ?: return
        enqueue { core.viewedCollection(collection) }
    }

    @JvmStatic
    fun searched(query: String) {
        val core = requireCore() ?: return
        enqueue { core.searched(query) }
    }

    /** Name and custom attributes of the signed-in contact. */
    @JvmStatic
    fun setProfile(update: ProfileUpdate) {
        val core = requireCore() ?: return
        enqueue { core.setProfile(update) }
    }

    /** Newsletter or waitlist signup through a published form. */
    suspend fun subscribe(request: SubscribeRequest): SubscribeResult =
        requireCore()?.subscribe(request) ?: SubscribeResult.Failure("Call Sequenzy.configure first", retryable = false)

    @JvmStatic
    fun setIdentityTokenProvider(provider: IdentityTokenProvider?) {
        requireCore()?.identityTokenProvider = provider
    }

    @JvmStatic
    fun setUserIdentityTokenProvider(provider: UserIdentityTokenProvider?) {
        requireCore()?.userIdentityTokenProvider = provider
    }

    // endregion

    // region Push

    /** Registers an FCM token. [SequenzyMessagingService] calls this for you. */
    @JvmStatic
    fun setPushToken(token: String) {
        val core = requireCore() ?: return
        enqueue { core.setPushToken(token) }
    }

    /**
     * Handles a notification tap that opened [activity]. Called automatically
     * for every activity the app starts; returns false for other senders.
     */
    @JvmStatic
    fun handleIntent(activity: Activity, intent: Intent?): Boolean {
        // Reopened from Recents: the original launch was handled already.
        if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return false
        val payload = intent.extras?.toPushPayload() ?: return false
        if (intent.getBooleanExtra(EXTRA_HANDLED, false)) return false
        intent.putExtra(EXTRA_HANDLED, true)
        core?.let { core -> scope.launch { core.reportPush(PushEventType.CLICKED, payload) } }
        payload.url?.let { open(activity, Uri.parse(it), null) }
        return true
    }

    /** The notification permission as Android reports it now. */
    @JvmStatic
    fun pushPermissionStatus(): PushPermissionStatus? = application?.let { PushPermissions.status(it) }

    /**
     * Asks for notification permission, as a push primer does: the system
     * prompt on Android 13+ when Android still shows it, otherwise the app's
     * notification settings. Returns the permission afterwards and registers
     * the device again so Sequenzy knows.
     */
    suspend fun requestPushPermission(): PushPermissionStatus? {
        val app = application ?: return null
        val status = PushPermissions.request(app, resumedActivity)
        core?.let { core -> enqueue { core.registerPushIfNeeded() } }
        return status
    }

    /** [requestPushPermission] for Java callers; [callback] runs on the main thread. */
    @JvmStatic
    fun requestPushPermission(callback: (PushPermissionStatus?) -> Unit) {
        scope.launch {
            val status = requestPushPermission()
            withContext(Dispatchers.Main) { callback(status) }
        }
    }

    // endregion

    // region In-app

    /** Open in-app messages, for apps that present them themselves. Custom HTML messages are left out. */
    @JvmStatic
    suspend fun fetchMessages(): List<InAppMessage> = fetchMessages(includeHtml = false)

    /** Open in-app messages, for apps that present them themselves; custom HTML ones too with [includeHtml]. */
    @JvmStatic
    suspend fun fetchMessages(includeHtml: Boolean): List<InAppMessage> =
        (requireCore() ?: throw SequenzyException.NotIdentified()).fetchFeed(
            InAppMessage.Kind.MESSAGE,
            null,
            if (includeHtml) InAppMessage.Format.entries.toList() else null,
        )

    /**
     * What each placement shows the identified contact; keys missing from the result collapse. [SequenzyPlacementView]
     * calls this for you.
     */
    @JvmStatic
    suspend fun fetchPlacements(placements: List<String>): Map<String, InAppMessage> =
        (requireCore() ?: throw SequenzyException.NotIdentified()).fetchPlacements(placements)

    /** Content cards for an inbox or feed screen, pinned cards first. */
    @JvmStatic
    suspend fun fetchCards(limit: Int? = null): List<InAppMessage> =
        (requireCore() ?: throw SequenzyException.NotIdentified()).fetchFeed(InAppMessage.Kind.CARD, limit)

    /** Reports an event for a message or card you render yourself. */
    @JvmStatic
    fun report(type: InAppEventType, message: InAppMessage) {
        val core = requireCore() ?: return
        scope.launch { core.report(type, message.id) }
    }

    /**
     * Reports the click and performs the button's action: opens its URL, or
     * asks for push permission and reports the result with the click.
     */
    @JvmStatic
    fun handleButtonClick(context: Context, button: InAppButton, message: InAppMessage) {
        if (button.action == InAppButton.Action.REQUEST_PUSH_PERMISSION) {
            requestPermissionAndReport(message.id)
            return
        }
        report(InAppEventType.CLICKED, message)
        if (button.action == InAppButton.Action.OPEN_URL) button.url?.let { open(context, Uri.parse(it), message) }
    }

    internal fun requestPermissionAndReport(messageId: String) {
        val core = requireCore() ?: return
        scope.launch {
            val status = requestPushPermission()
            core.report(InAppEventType.CLICKED, messageId, buttonId = null, pushPermission = status)
        }
    }

    /** The identified contact's email, or null (also for a user identified by external ID only). */
    @JvmStatic
    fun identifiedEmail(): String? = core?.email

    /**
     * One string per identified contact (see [SequenzyCore.contactKey]), or
     * null while anonymous. Geofencing runs only while it is set.
     */
    @JvmStatic
    fun identifiedContact(): String? = core?.contactKey

    /**
     * The nearest geofences around a position, rounded to about 1 km before it
     * is sent. `SequenzyGeofences` calls this; apps with their own location
     * stack can too.
     */
    @JvmStatic
    suspend fun fetchNearbyGeofences(latitude: Double, longitude: Double, limit: Int): NearbyGeofences =
        (requireCore() ?: throw SequenzyException.NotIdentified()).fetchNearbyGeofences(latitude, longitude, limit)

    /**
     * Reports a geofence enter or exit and returns once it is sent or queued,
     * for apps with their own geofencing; `SequenzyGeofences` calls it too.
     */
    @JvmStatic
    suspend fun trackGeofenceTransition(
        geofenceId: String,
        transition: GeofenceTransition,
        atMillis: Long? = null,
    ): Boolean = requireCore()?.trackGeofenceTransition(geofenceId, transition, atMillis) ?: false

    /**
     * [trackGeofenceTransition] for several geofences reported at once: all are
     * stored before anything is sent. Returns how many were queued.
     */
    @JvmStatic
    suspend fun trackGeofenceTransitions(
        geofenceIds: List<String>,
        transition: GeofenceTransition,
        atMillis: Long? = null,
    ): Int = requireCore()?.trackGeofenceTransitions(geofenceIds, transition, atMillis) ?: 0

    internal suspend fun refreshMessages(force: Boolean = false) {
        val core = core ?: return
        if (!showInAppMessages) return
        val now = System.currentTimeMillis()
        // 90%: a poll firing on the interval must not lose a cycle to a fetch
        // that ran a moment after it was scheduled.
        if (!force && now - lastFeedFetchAt < feedRefreshIntervalMs * 9 / 10) return
        lastFeedFetchAt = now
        val owner = core.contactKey
        val messages = runCatching {
            core.fetchFeed(InAppMessage.Kind.MESSAGE, null, displayedFormats)
        }.getOrNull() ?: return
        if (core.contactKey != owner) return
        withContext(Dispatchers.Main) { presenter?.enqueue(messages, owner) }
    }

    // endregion

    // region Internals

    internal const val EXTRA_HANDLED = "com.sequenzy.handled"

    /** The configured core, without the error [requireCore] logs. */
    internal val currentCore: SequenzyCore? get() = core

    /** The identified contact's key (see [SequenzyCore.contactKey]). */
    internal val signedInContact: String? get() = core?.contactKey

    internal fun reportFromPresenter(type: InAppEventType, messageId: String, buttonId: String? = null) {
        val core = core ?: return
        scope.launch { core.report(type, messageId, buttonId) }
    }

    internal fun reportPush(type: PushEventType, payload: PushPayload) {
        val core = core ?: return
        scope.launch { core.reportPush(type, payload) }
    }

    internal fun open(context: Context, url: Uri, message: InAppMessage?) {
        if (inAppListener?.handleUrl(url, message) == true) return
        val intent = Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // A deep link into this app should stay in the app's own task; other
        // schemes the app doesn't handle (mailto:, tel:) go to the system.
        application?.let { app ->
            if (url.scheme != "http" && url.scheme != "https") {
                intent.setPackage(app.packageName)
                if (intent.resolveActivity(app.packageManager) == null) intent.setPackage(null)
            }
        }
        runCatching { context.startActivity(intent) }.onFailure {
            Log.e(TAG, "No activity can open $url")
        }
    }

    internal val resumedActivity: Activity? get() = currentActivity?.get()

    internal fun liveUpdateCore(): SequenzyCore? = requireCore()

    internal fun application(): Application? = application

    /** Live Update changes run in order with identify and reset. */
    internal fun enqueueLiveUpdate(command: suspend () -> Unit) = enqueue(command)

    private fun requireCore(): SequenzyCore? {
        if (core == null) Log.e(TAG, "Call Sequenzy.configure(application, publicKey, companyId) first")
        return core
    }

    private fun deviceInfo(context: Context): DeviceInfo {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
        return DeviceInfo(
            appVersion = version,
            osVersion = Build.VERSION.RELEASE,
            deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        )
    }

    private object ForegroundObserver : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            val core = core ?: return
            enqueue {
                core.flush()
                core.registerPushIfNeeded()
                refreshMessages()
            }
            SequenzyPlacements.appForegrounded()
            // Poll while in the foreground so a message created by something
            // the user just did (a tracked event starting a sequence) shows up
            // without leaving the app.
            pollJob?.cancel()
            if (feedRefreshIntervalMs > 0) {
                pollJob = scope.launch {
                    while (true) {
                        delay(feedRefreshIntervalMs)
                        enqueue { refreshMessages() }
                    }
                }
            }
        }

        override fun onStop(owner: LifecycleOwner) {
            pollJob?.cancel()
            pollJob = null
        }
    }

    private object ActivityTracker : Application.ActivityLifecycleCallbacks {
        /** Intents seen at creation, handled or restored after process death. */
        private val seenIntents = java.util.WeakHashMap<Intent, Boolean>()

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            activity.intent?.let { seenIntents[it] = true }
            // A restored activity carries the original intent without the
            // in-memory "handled" marker; handling it again would reopen the link.
            if (savedInstanceState == null) handleIntent(activity, activity.intent)
        }

        override fun onActivityResumed(activity: Activity) {
            // A different intent than the one the activity was created with
            // came through onNewIntent + setIntent(): a tap while open.
            val intent = activity.intent
            if (intent != null && seenIntents.put(intent, true) == null) handleIntent(activity, intent)
            currentActivity = WeakReference(activity)
            presenter?.onActivityResumed(activity)
        }

        override fun onActivityPaused(activity: Activity) {
            presenter?.onActivityPaused(activity)
            if (currentActivity?.get() === activity) currentActivity = null
        }

        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    // endregion
}

private val PUSH_KEYS = listOf("title", "body", "url", "imageUrl", "pushSendId", "deviceId", "clickToken", "eventsUrl")

/**
 * Reads only Sequenzy's string extras. Every activity's intent passes through
 * here, and unparceling a foreign Parcelable the app does not know throws, so
 * any failure means "not a Sequenzy push".
 */
internal fun Bundle.toPushPayload(): PushPayload? = runCatching {
    PushPayload.fromData(PUSH_KEYS.mapNotNull { key -> getString(key)?.let { key to it } }.toMap())
}.getOrNull()
