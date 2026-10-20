package com.sequenzy.android

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import com.sequenzy.core.PushEventType

/**
 * Opened by a tap on a notification the SDK displayed. Reports the click,
 * opens the push URL (or the app's launcher activity) and finishes.
 */
class SequenzyNotificationOpenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // super.onCreate dispatches the SDK's activity callbacks, which report
        // the click and open the URL through Sequenzy.handleIntent.
        super.onCreate(savedInstanceState)
        val payload = intent?.extras?.toPushPayload()
        if (payload != null) {
            if (!intent.getBooleanExtra(Sequenzy.EXTRA_HANDLED, false)) {
                intent.putExtra(Sequenzy.EXTRA_HANDLED, true)
                Sequenzy.reportPush(PushEventType.CLICKED, payload)
                payload.url?.let { Sequenzy.open(this, Uri.parse(it), null) }
            }
            if (payload.url == null) {
                packageManager.getLaunchIntentForPackage(packageName)?.let(::startActivity)
            }
        }
        finish()
    }
}
