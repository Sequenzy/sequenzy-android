package com.sequenzy.android

import android.app.Activity
import android.app.Dialog
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import com.sequenzy.core.InAppButton
import com.sequenzy.core.InAppEventType
import com.sequenzy.core.InAppMessage

/** Shows in-app messages one at a time over the resumed activity. Main thread only. */
internal class InAppPresenter {
    private val queue = ArrayDeque<InAppMessage>()
    private val shownIds = mutableSetOf<String>()
    private var current: InAppMessage? = null
    private var dialog: Dialog? = null
    private var banner: View? = null
    private var htmlDialog: InAppHtmlDialog? = null
    private var hostActivity: Activity? = null

    /** The contact the queued and shown messages were fetched for. */
    private var owner: String? = null

    /** After identify: another contact's messages go before they can act for the new one. */
    fun signedIn(contact: String?) {
        if (contact == owner) return
        dismissAll()
        owner = contact
    }

    fun enqueue(messages: List<InAppMessage>, owner: String?) {
        signedIn(owner)
        // An html item without its document came from a caller that did not
        // ask for html; it can't be drawn natively.
        messages.filter {
            it.kind == InAppMessage.Kind.MESSAGE && (it.resolvedFormat != InAppMessage.Format.HTML || it.html != null)
        }.forEach { message ->
            if (message.id in shownIds || current?.id == message.id || queue.any { it.id == message.id }) return@forEach
            queue.addLast(message)
        }
        showNextIfIdle()
    }

    fun dismissAll() {
        queue.clear()
        shownIds.clear()
        owner = null
        hide()
    }

    fun onActivityResumed(activity: Activity) = showNextIfIdle(activity)

    fun onActivityPaused(activity: Activity) {
        // Rotation, a permission prompt or leaving the screen closes the
        // views; the message was not answered, so show it again on resume.
        if (hostActivity === activity) {
            current?.let { message ->
                shownIds.remove(message.id)
                queue.addFirst(message)
            }
            hide()
        }
    }

    private fun showNextIfIdle(activity: Activity? = Sequenzy.resumedActivity) {
        if (current != null || activity == null || activity.isFinishing) return
        while (queue.isNotEmpty()) {
            val message = queue.removeFirst()
            if (Sequenzy.inAppListener?.shouldShow(message) == false) continue
            show(activity, message)
            return
        }
    }

    private fun show(activity: Activity, message: InAppMessage) {
        val bannerRoot = if (message.resolvedFormat == InAppMessage.Format.BANNER) {
            // No content view yet: keep the message for the next resume
            // instead of holding the presenter with nothing on screen.
            activity.findViewById<ViewGroup>(android.R.id.content) ?: run {
                queue.addFirst(message)
                return
            }
        } else {
            null
        }
        current = message
        hostActivity = activity
        shownIds.add(message.id)
        if (message.resolvedFormat == InAppMessage.Format.HTML) {
            // Reported displayed once the document has loaded; one that fails
            // to load stays in the feed for a later launch.
            val html = InAppHtmlDialog(
                activity,
                message,
                onLoaded = { Sequenzy.reportFromPresenter(InAppEventType.DISPLAYED, message.id) },
                onFinish = { outcome -> finishHtml(activity, message, outcome) },
                isForSignedInContact = { Sequenzy.signedInContact == owner },
            )
            htmlDialog = html
            html.show()
            return
        }
        val views = MessageViews(activity, message) { outcome -> finish(activity, message, outcome) }
        when (message.resolvedFormat) {
            InAppMessage.Format.MODAL -> dialog = views.modalDialog().also { it.show() }
            InAppMessage.Format.FULLSCREEN -> dialog = views.fullscreenDialog().also { it.show() }
            InAppMessage.Format.BANNER -> banner = views.banner().also { bannerRoot?.addView(it) }
            InAppMessage.Format.HTML -> Unit
        }
        Sequenzy.reportFromPresenter(InAppEventType.DISPLAYED, message.id)
    }

    private fun finish(activity: Activity, message: InAppMessage, outcome: Outcome) {
        if (current?.id != message.id) return
        when (outcome) {
            is Outcome.Dismissed -> Sequenzy.reportFromPresenter(InAppEventType.DISMISSED, message.id)
            is Outcome.Tapped -> {
                // A banner tap (null button) acts like its primary button
                val target = outcome.button ?: message.buttons.firstOrNull { it.acts }
                if (target?.action == InAppButton.Action.REQUEST_PUSH_PERMISSION) {
                    Sequenzy.requestPermissionAndReport(message.id)
                } else {
                    Sequenzy.reportFromPresenter(InAppEventType.CLICKED, message.id)
                    val url = target?.url ?: message.buttons.firstOrNull { it.action == InAppButton.Action.OPEN_URL }?.url
                    url?.let { Sequenzy.open(activity, Uri.parse(it), message) }
                }
            }
        }
        hide()
        showNextIfIdle()
    }

    private fun finishHtml(activity: Activity, message: InAppMessage, outcome: InAppHtmlDialog.Outcome) {
        if (current?.id != message.id) return
        htmlDialog = null
        val forOwner = Sequenzy.signedInContact == owner
        when (outcome) {
            is InAppHtmlDialog.Outcome.Dismissed ->
                if (forOwner) Sequenzy.reportFromPresenter(InAppEventType.DISMISSED, message.id)
            // The click was reported with its button id when the document
            // asked to open the link.
            is InAppHtmlDialog.Outcome.Opened -> if (forOwner) Sequenzy.open(activity, outcome.url, message)
            is InAppHtmlDialog.Outcome.Failed -> Unit
        }
        hide()
        showNextIfIdle()
    }

    private fun hide() {
        htmlDialog?.dismiss()
        htmlDialog = null
        dialog?.setOnDismissListener(null)
        dialog?.takeIf { it.isShowing }?.dismiss()
        dialog = null
        banner?.let { (it.parent as? ViewGroup)?.removeView(it) }
        banner = null
        current = null
        hostActivity = null
    }

    sealed class Outcome {
        data object Dismissed : Outcome()

        /** A link or push permission button, or the banner itself (null button). */
        data class Tapped(val button: InAppButton?) : Outcome()
    }
}
