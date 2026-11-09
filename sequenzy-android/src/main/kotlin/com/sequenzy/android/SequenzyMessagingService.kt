package com.sequenzy.android

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.sequenzy.core.PushEventType
import com.sequenzy.core.PushPayload

/**
 * Receives FCM tokens and Sequenzy pushes. Registered by the SDK's manifest;
 * apps with their own `FirebaseMessagingService` call [handleNewToken] and
 * [handleMessage] from it instead (only one service receives FCM events).
 */
open class SequenzyMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        handleNewToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        handleMessage(this, message)
    }

    companion object {
        @JvmStatic
        fun handleNewToken(token: String) {
            if (Sequenzy.isConfigured) Sequenzy.setPushToken(token)
        }

        /**
         * Shows a Sequenzy push that arrived while the app was in the
         * foreground and reports it delivered. While the app is in the
         * background Android shows the notification itself and the tap is
         * reported when the app opens. Live Updates are shown or updated
         * in both cases. Returns false for other senders.
         */
        @JvmStatic
        fun handleMessage(service: FirebaseMessagingService, message: RemoteMessage): Boolean {
            if (SequenzyLiveUpdates.handleMessage(service, message.data)) return true
            val payload = PushPayload.fromData(message.data) ?: return false
            Sequenzy.reportPush(PushEventType.DELIVERED, payload)
            NotificationDisplay.show(service, payload)
            return true
        }

    }
}
