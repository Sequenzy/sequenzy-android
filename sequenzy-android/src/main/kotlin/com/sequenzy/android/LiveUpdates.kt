package com.sequenzy.android

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.sequenzy.core.LiveUpdateActivity
import com.sequenzy.core.LiveUpdateAlert
import com.sequenzy.core.LiveUpdateDecision
import com.sequenzy.core.LiveUpdatePayload
import com.sequenzy.core.LiveUpdateToken
import com.sequenzy.core.SequenzyCore
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** Draws one activity's notification from its attributes and content state. */
fun interface LiveUpdateRenderer {
    /**
     * Content for [activity]'s notification, or null to show nothing. Runs
     * on a background thread, also while the app is in the background.
     */
    fun render(context: Context, activity: LiveUpdateActivity): LiveUpdateContent?
}

/**
 * What a Live Update notification shows. On Android 16 it is a promoted
 * Live Update: at the top of the shade and the lock screen, with a status
 * bar chip ([shortCriticalText] or the countdown). Before Android 16 it is
 * an ongoing notification with a plain progress bar, updated in place.
 */
data class LiveUpdateContent @JvmOverloads constructor(
    /** Required: Android only promotes notifications with a title. */
    val title: String,
    val text: String? = null,
    /** Status bar chip on Android 16, e.g. "5 min" or "Arrived"; shown in full up to 7 characters. */
    val shortCriticalText: String? = null,
    /** 0 to [progressMax]; null shows no progress bar. */
    val progress: Int? = null,
    val progressMax: Int = 100,
    val progressIndeterminate: Boolean = false,
    /** Android 16: colored stretches of the bar. Their lengths set the bar's maximum instead of [progressMax]. */
    val segments: List<Segment> = emptyList(),
    /** Android 16: milestones on the bar, as positions on it. */
    val points: List<Point> = emptyList(),
    /** Counts down to this time in the notification and the chip. */
    val countdownToMillis: Long? = null,
    /** Notification small icon; defaults to [Sequenzy.notificationIcon]. */
    val smallIcon: Int = 0,
    val largeIcon: Bitmap? = null,
    /** Accent color. Live Updates cannot have a colorized background. */
    val color: Int? = null,
    /** Link or deep link opened on tap; without one the app opens. */
    val url: String? = null,
) {
    data class Segment @JvmOverloads constructor(val length: Int, val color: Int? = null)
    data class Point @JvmOverloads constructor(val position: Int, val color: Int? = null)
}

/**
 * Android Live Updates, started, updated and ended from your backend with
 * the same Sequenzy calls as iOS Live Activities.
 *
 * ```kotlin
 * // Application.onCreate, right after Sequenzy.configure:
 * SequenzyLiveUpdates.observe("DeliveryAttributes") { context, activity ->
 *     LiveUpdateContent(
 *         title = "Order ${activity.attributes["orderNumber"]}",
 *         text = activity.contentState["status"] as? String,
 *         progress = (activity.contentState["progress"] as? Number)?.toInt(),
 *     )
 * }
 * ```
 */
object SequenzyLiveUpdates {
    const val CHANNEL_ID = "sequenzy_live_updates"
    internal const val ACTION_DISMISSED = "com.sequenzy.android.LIVE_UPDATE_DISMISSED"
    internal const val EXTRA_ACTIVITY_ID = "activityId"
    private const val NOTIFICATION_ID = 0x5E9
    // Notification.EXTRA_REQUEST_PROMOTED_ONGOING (API 36.1). Requested
    // through the extra so the SDK compiles against API 36.
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
    private const val RECEIVE_TIMEOUT_MS = 8_000L

    /** Name of the notification channel Live Updates use, shown in the app's notification settings. */
    @JvmStatic
    var channelName: String = "Live updates"

    private val renderers = ConcurrentHashMap<String, LiveUpdateRenderer>()

    /**
     * Renders activities of [activityType] (use the same name as your iOS
     * ActivityAttributes type, so one backend call reaches both) and reports
     * that this device can start them. Call once per type in
     * `Application.onCreate`, right after `Sequenzy.configure`: Android
     * starts the app in the background when a push starts an activity.
     */
    @JvmStatic
    @JvmOverloads
    fun observe(activityType: String, renderer: LiveUpdateRenderer = DefaultLiveUpdateRenderer) {
        val core = Sequenzy.liveUpdateCore() ?: return
        if (!LiveUpdateToken.isActivityType(activityType)) {
            android.util.Log.e("Sequenzy", "observe: \"$activityType\" is not a valid activity type name")
            return
        }
        renderers[activityType] = renderer
        Sequenzy.enqueueLiveUpdate { core.liveUpdates.observe(activityType) }
    }

    /** Starts an activity from the app (for example after the person orders), so your backend can update and end it by [activityId]. */
    @JvmStatic
    @JvmOverloads
    fun start(
        activityType: String,
        activityId: String,
        attributes: Map<String, Any?> = emptyMap(),
        contentState: Map<String, Any?> = emptyMap(),
    ) {
        val core = Sequenzy.liveUpdateCore() ?: return
        Sequenzy.enqueueLiveUpdate {
            apply(core, core.liveUpdates.start(activityType, activityId, attributes, contentState))
        }
    }

    /** Updates a running activity from the app. */
    @JvmStatic
    fun update(activityId: String, contentState: Map<String, Any?>) {
        val core = Sequenzy.liveUpdateCore() ?: return
        Sequenzy.enqueueLiveUpdate { apply(core, core.liveUpdates.update(activityId, contentState)) }
    }

    /**
     * Ends a running activity from the app with its final [contentState]
     * (null keeps the current one). It stays until [dismissAtMillis], or
     * up to four hours, as on iOS; a past time removes it now.
     */
    @JvmStatic
    @JvmOverloads
    fun end(activityId: String, contentState: Map<String, Any?>? = null, dismissAtMillis: Long? = null) {
        val core = Sequenzy.liveUpdateCore() ?: return
        Sequenzy.enqueueLiveUpdate { apply(core, core.liveUpdates.end(activityId, contentState, dismissAtMillis)) }
    }

    /**
     * Whether Android shows this app's Live Updates promoted: Android 16 or
     * later, and the person has not turned Live Updates off for the app.
     * Earlier versions show them as ongoing notifications.
     */
    @JvmStatic
    fun canPostPromotedNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.canPostPromotedNotifications()
    }

    /**
     * Opens the system setting where the person turns Live Updates back on
     * (Android 16), the app's notification settings (Android 8 to 15), or
     * the app's details page before that.
     */
    @JvmStatic
    fun promotionSettingsIntent(context: Context): Intent {
        val intent = when {
            Build.VERSION.SDK_INT >= 36 ->
                Intent("android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS")
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            else ->
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        }
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Shows or updates the Live Update an FCM message carries. Returns false
     * for messages that are not Sequenzy Live Updates. Called for you by
     * [SequenzyMessagingService.handleMessage].
     */
    @JvmStatic
    fun handleMessage(context: Context, data: Map<String, String>): Boolean {
        val payload = LiveUpdatePayload.fromData(data) ?: return false
        val core = Sequenzy.liveUpdateCore() ?: return true
        val canShow = renderers.containsKey(payload.activityType) && NotificationDisplay.canPostNotifications(context)
        // onMessageReceived runs on a background thread that Android keeps
        // alive only until it returns, so the notification is posted here,
        // before the token reports, which wait for the network.
        runBlocking {
            withTimeoutOrNull(RECEIVE_TIMEOUT_MS) {
                apply(context.applicationContext, core, core.liveUpdates.receive(payload, canShow))
            }
        }
        Sequenzy.scope.launch { core.liveUpdates.sync() }
        return true
    }

    // region Internals

    internal fun dismissed(activityId: String, done: () -> Unit) {
        val core = Sequenzy.liveUpdateCore() ?: return done()
        Sequenzy.scope.launch {
            try {
                core.liveUpdates.dismissed(activityId)
            } finally {
                done()
            }
        }
    }

    internal fun cancel(context: Context, activityIds: List<String>) {
        val manager = NotificationManagerCompat.from(context)
        for (id in activityIds) manager.cancel(tag(id), NOTIFICATION_ID)
    }

    private suspend fun apply(core: SequenzyCore, decision: LiveUpdateDecision) {
        Sequenzy.application()?.let { apply(it, core, decision) }
    }

    private fun tag(activityId: String) = "sequenzy.live:$activityId"

    /**
     * Draws [decision] outside the lock (the renderer is app code), then
     * posts it under the lock only if it is still the activity's latest
     * state: a reset, end or newer push in between wins.
     */
    // Lint cannot see through canPostNotifications(); the permission is checked.
    @SuppressLint("MissingPermission")
    private suspend fun apply(context: Context, core: SequenzyCore, decision: LiveUpdateDecision) {
        if (decision !is LiveUpdateDecision.Show) return
        val activity = decision.activity
        val manager = NotificationManagerCompat.from(context)
        val dismissAt = decision.dismissAtMillis
        val remaining = dismissAt?.let { it - System.currentTimeMillis() }
        if (activity.isEnded && remaining != null && remaining <= 0) {
            core.liveUpdates.ifCurrent(activity) { manager.cancel(tag(activity.activityId), NOTIFICATION_ID) }
            return
        }
        if (!NotificationDisplay.canPostNotifications(context)) return
        val renderer = renderers[activity.activityType] ?: DefaultLiveUpdateRenderer
        val content = runCatching { renderer.render(context, activity) }
            .onFailure { android.util.Log.e("Sequenzy", "Live Update renderer failed: ${it.message}") }
            .getOrNull()
            ?.let { if (it.title.isBlank()) it.copy(title = appLabel(context)) else it }
            ?: return
        ensureChannel(context)
        val notification = if (Build.VERSION.SDK_INT >= 36) {
            buildPromoted(context, activity, content, decision.alert, remaining)
        } else {
            buildOngoing(context, activity, content, decision.alert, remaining)
        }
        core.liveUpdates.ifCurrent(activity) {
            try {
                manager.notify(tag(activity.activityId), NOTIFICATION_ID, notification)
            } catch (_: SecurityException) {
                // Permission revoked between the check and the post.
            }
        }
    }

    @RequiresApi(36)
    private fun buildPromoted(
        context: Context,
        activity: LiveUpdateActivity,
        content: LiveUpdateContent,
        alert: LiveUpdateAlert?,
        remainingMs: Long?,
    ): Notification {
        val ended = activity.isEnded
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(icon(content))
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setOngoing(!ended)
            .setOnlyAlertOnce(alert == null)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(contentIntent(context, activity, content))
            .setDeleteIntent(deleteIntent(context, activity))
        // Promotion needs an ongoing, non-colorized notification with a
        // title, standard, big text or progress style and no custom views.
        if (!ended) builder.addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
        content.shortCriticalText?.let(builder::setShortCriticalText)
        content.color?.let(builder::setColor)
        content.largeIcon?.let(builder::setLargeIcon)
        content.countdownToMillis?.let { at ->
            builder.setWhen(at).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        }
        if (hasProgress(content)) {
            val style = Notification.ProgressStyle()
                .setProgressSegments(
                    content.segments.ifEmpty { listOf(LiveUpdateContent.Segment(content.progressMax.coerceAtLeast(1))) }
                        .map { segment ->
                            Notification.ProgressStyle.Segment(segment.length.coerceAtLeast(1))
                                .also { s -> segment.color?.let(s::setColor) }
                        },
                )
                .setProgressPoints(
                    content.points.map { point ->
                        Notification.ProgressStyle.Point(point.position).also { p -> point.color?.let(p::setColor) }
                    },
                )
                .setProgressIndeterminate(content.progressIndeterminate)
            content.progress?.let { style.setProgress(it.coerceAtLeast(0)) }
            builder.setStyle(style)
        } else if (content.text != null) {
            builder.setStyle(Notification.BigTextStyle().bigText(content.text))
        }
        if (ended && remainingMs != null) builder.setTimeoutAfter(remainingMs)
        return builder.build()
    }

    private fun buildOngoing(
        context: Context,
        activity: LiveUpdateActivity,
        content: LiveUpdateContent,
        alert: LiveUpdateAlert?,
        remainingMs: Long?,
    ): Notification {
        val ended = activity.isEnded
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(icon(content))
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setOngoing(!ended)
            .setOnlyAlertOnce(alert == null)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentIntent(context, activity, content))
            .setDeleteIntent(deleteIntent(context, activity))
        content.color?.let(builder::setColor)
        content.largeIcon?.let(builder::setLargeIcon)
        // The chip text has no place before Android 16; it becomes the subtext.
        content.shortCriticalText?.let(builder::setSubText)
        content.countdownToMillis?.let { at ->
            builder.setWhen(at).setShowWhen(true).setUsesChronometer(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                builder.addExtras(Bundle().apply { putBoolean("android.chronometerCountDown", true) })
            }
        }
        if (hasProgress(content)) {
            val max = content.segments.sumOf { it.length }.takeIf { it > 0 } ?: content.progressMax
            builder.setProgress(max.coerceAtLeast(1), (content.progress ?: 0).coerceIn(0, max.coerceAtLeast(1)), content.progressIndeterminate)
        }
        if (content.text != null && !hasProgress(content)) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(content.text))
        }
        if (ended && remainingMs != null) builder.setTimeoutAfter(remainingMs)
        return builder.build()
    }

    private fun hasProgress(content: LiveUpdateContent) =
        content.progress != null || content.progressIndeterminate || content.segments.isNotEmpty()

    private fun icon(content: LiveUpdateContent): Int =
        content.smallIcon.takeIf { it != 0 } ?: Sequenzy.notificationIcon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info

    private fun contentIntent(context: Context, activity: LiveUpdateActivity, content: LiveUpdateContent): PendingIntent? {
        val intent = content.url?.let { url ->
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                // A deep link stays in this app when it handles it.
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    setPackage(context.packageName)
                    if (resolveActivity(context.packageManager) == null) setPackage(null)
                }
            }
        } ?: context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context,
            tag(activity.activityId).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun deleteIntent(context: Context, activity: LiveUpdateActivity): PendingIntent {
        val intent = Intent(context, SequenzyLiveUpdateReceiver::class.java)
            .setAction(ACTION_DISMISSED)
            .putExtra(EXTRA_ACTIVITY_ID, activity.activityId)
        return PendingIntent.getBroadcast(
            context,
            tag(activity.activityId).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        // Default importance: Live Updates cannot be promoted from a MIN channel.
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun appLabel(context: Context): String =
        runCatching { context.applicationInfo.loadLabel(context.packageManager).toString() }.getOrNull() ?: "Live update"

    // endregion
}

/**
 * Renders well-known content state keys, for apps that send them:
 * `title`, `body` (or `status`), `progress` (0-100), `shortText` for the
 * status bar chip and `url`. Falls back to the start's attributes.
 */
object DefaultLiveUpdateRenderer : LiveUpdateRenderer {
    override fun render(context: Context, activity: LiveUpdateActivity): LiveUpdateContent {
        fun text(key: String) = (activity.contentState[key] ?: activity.attributes[key])?.toString()?.takeIf { it.isNotBlank() }
        return LiveUpdateContent(
            // Blank: the SDK shows the app's name.
            title = text("title") ?: "",
            text = text("body") ?: text("status"),
            shortCriticalText = text("shortText"),
            progress = (activity.contentState["progress"] as? Number)?.toInt()?.coerceIn(0, 100),
            url = text("url"),
        )
    }
}

/** Reports a Live Update the person swiped away, so Sequenzy stops sending to it. */
class SequenzyLiveUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SequenzyLiveUpdates.ACTION_DISMISSED) return
        val activityId = intent.getStringExtra(SequenzyLiveUpdates.EXTRA_ACTIVITY_ID) ?: return
        // Keeps the process alive until the dismissal is reported.
        val pending = goAsync()
        SequenzyLiveUpdates.dismissed(activityId) { pending.finish() }
    }
}
