package com.sequenzy.android

import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Fetches the current FCM token when the app ships Firebase Messaging.
 *
 * Kept out of [SequenzyMessagingService]: touching that class (even its
 * companion) loads its `FirebaseMessagingService` superclass, which crashes
 * apps that do not include Firebase. Here the Firebase types are only reached
 * through [Impl], after the class is known to exist.
 */
internal object FirebaseTokens {
    fun fetchIfAvailable() {
        val available = runCatching { Class.forName("com.google.firebase.messaging.FirebaseMessaging") }.isSuccess
        if (!available) return
        runCatching { Impl.fetch() }
            .onFailure { Log.i("Sequenzy", "Firebase is not initialized; push tokens are not registered") }
    }

    private object Impl {
        fun fetch() {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                if (!token.isNullOrEmpty()) Sequenzy.setPushToken(token)
            }
        }
    }
}
