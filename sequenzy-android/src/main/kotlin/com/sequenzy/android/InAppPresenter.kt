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
    private var hostActivity: Activity? = null

    fun enqueue(messages: List<InAppMessage>) {
        messages.filter { it.kind == InAppMessage.Kind.MESSAGE }.forEach { message ->
            if (message.id in shownIds || current?.id == message.id || queue.any { it.id == message.id }) return@forEach
            queue.addLast(message)
        }
        showNextIfIdle()
    }

    fun dismissAll() {
        queue.clear()
        shownIds.clear()
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
        val views = MessageViews(activity, message) { outcome -> finish(activity, message, outcome) }
        when (message.resolvedFormat) {
            InAppMessage.Format.MODAL -> dialog = views.modalDialog().also { it.show() }
            InAppMessage.Format.FULLSCREEN -> dialog = views.fullscreenDialog().also { it.show() }
            InAppMessage.Format.BANNER -> banner = views.banner().also { bannerRoot?.addView(it) }
        }
        Sequenzy.reportFromPresenter(InAppEventType.DISPLAYED, message.id)
    }

    private fun finish(activity: Activity, message: InAppMessage, outcome: Outcome) {
        if (current?.id != message.id) return
        when (outcome) {
            is Outcome.Dismissed -> Sequenzy.reportFromPresenter(InAppEventType.DISMISSED, message.id)
            is Outcome.Tapped -> {
                Sequenzy.reportFromPresenter(InAppEventType.CLICKED, message.id)
                val url = outcome.button?.url ?: message.buttons.firstOrNull { it.action == InAppButton.Action.OPEN_URL }?.url
                url?.let { Sequenzy.open(activity, Uri.parse(it), message) }
            }
        }
        hide()
        showNextIfIdle()
    }

    private fun hide() {
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

        /** A link button, or the banner itself (null button). */
        data class Tapped(val button: InAppButton?) : Outcome()
    }
}
