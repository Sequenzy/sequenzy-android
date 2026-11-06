package com.sequenzy.android

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.sequenzy.core.PushPermissionStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Reads and requests the notification permission (Android 13+ asks at runtime). */
internal object PushPermissions {
    private const val PREFERENCES = "com.sequenzy.sdk"
    private const val KEY_ASKED = "pushPermissionAsked"
    private const val REQUEST_TIMEOUT_MS = 5L * 60 * 1000

    @Volatile
    private var pending: CompletableDeferred<Unit>? = null

    /**
     * Android cannot tell "never asked" from "denied" without an activity, so
     * the SDK remembers whether it asked. Notifications off before Android 13,
     * or after the app asked, read as denied.
     */
    fun status(context: Context): PushPermissionStatus {
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) return PushPermissionStatus.GRANTED
        val canAsk = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        return if (canAsk && !asked(context)) PushPermissionStatus.NOT_DETERMINED else PushPermissionStatus.DENIED
    }

    /**
     * Shows the system prompt when Android still allows it. When the contact
     * had turned notifications off and Android no longer prompts (before
     * Android 13, or after repeated denials) it opens the app's notification
     * settings instead. Returns the permission afterwards.
     */
    suspend fun request(context: Context, activity: Activity?): PushPermissionStatus {
        val before = status(context)
        if (before == PushPermissionStatus.GRANTED) return before
        if (Build.VERSION.SDK_INT >= 33) {
            val result = CompletableDeferred<Unit>()
            pending?.complete(Unit)
            pending = result
            markAsked(context)
            withContext(Dispatchers.Main) {
                val host = activity ?: context
                val intent = Intent(host, SequenzyPermissionActivity::class.java)
                if (activity == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { host.startActivity(intent) }.onFailure {
                    Log.e("Sequenzy", "Could not ask for notification permission: ${it.message}")
                    result.complete(Unit)
                }
            }
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) { result.await() }
        }
        val after = status(context)
        if (before == PushPermissionStatus.DENIED && after == PushPermissionStatus.DENIED) {
            withContext(Dispatchers.Main) { openSettings(activity ?: context) }
        }
        return after
    }

    fun openSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= 26) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
        }
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            Log.e("Sequenzy", "Could not open notification settings: ${it.message}")
        }
    }

    internal fun onRequestFinished() {
        pending?.complete(Unit)
        pending = null
    }

    private fun asked(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false)

    private fun markAsked(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean(KEY_ASKED, true).apply()
    }
}

/**
 * Invisible activity that asks for POST_NOTIFICATIONS and finishes, so the
 * SDK can ask from an in-app message without the app forwarding results.
 */
class SequenzyPermissionActivity : Activity() {
    private companion object {
        const val REQUEST_CODE = 0x5e9
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < 33) {
            finishRequest()
            return
        }
        // After a configuration change the system dialog is still up and
        // delivers its result to this new instance.
        if (savedInstanceState == null) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE) finishRequest()
    }

    private fun finishRequest() {
        PushPermissions.onRequestFinished()
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
