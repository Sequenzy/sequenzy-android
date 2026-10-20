package com.sequenzy.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.sequenzy.core.PushPayload
import java.net.HttpURLConnection
import java.net.URL

internal object NotificationDisplay {
    const val CHANNEL_ID = "sequenzy_default"

    // Lint cannot see through canPostNotifications(); the permission is checked.
    @SuppressLint("MissingPermission")
    fun show(context: Context, payload: PushPayload) {
        if (!canPostNotifications(context)) return
        ensureChannel(context)
        val intent = Intent(context, SequenzyNotificationOpenActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("pushSendId", payload.pushSendId)
            putExtra("deviceId", payload.deviceId)
            putExtra("clickToken", payload.clickToken)
            putExtra("eventsUrl", payload.eventsUrl)
            payload.url?.let { putExtra("url", it) }
        }
        val pending = PendingIntent.getActivity(
            context,
            payload.pushSendId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(Sequenzy.notificationIcon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info)
            .setContentTitle(payload.title)
            .setContentText(payload.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(payload.body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pending)
        // onMessageReceived runs on a background thread, so a blocking
        // download is allowed here; FCM gives the service about 10 seconds.
        payload.imageUrl?.let(::downloadBitmap)?.let { bitmap ->
            builder.setLargeIcon(bitmap)
            builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(bitmap).bigLargeIcon(null as Bitmap?))
        }
        try {
            NotificationManagerCompat.from(context).notify(payload.pushSendId.hashCode(), builder.build())
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post.
        }
    }

    /** Android 13+ needs the runtime permission; earlier versions only the app-level switch. */
    private fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Notifications", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    private fun downloadBitmap(url: String): Bitmap? = runCatching {
        if (!url.startsWith("https://")) return null
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        try {
            connection.inputStream.use(BitmapFactory::decodeStream)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}
