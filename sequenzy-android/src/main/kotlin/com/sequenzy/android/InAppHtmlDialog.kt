package com.sequenzy.android

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import com.sequenzy.core.InAppEventType
import com.sequenzy.core.InAppHtmlBridgeMessage
import com.sequenzy.core.InAppMessage
import com.sequenzy.core.ProfileUpdate

/**
 * Shows a custom HTML in-app message full screen in a transparent WebView. The document comes from the server
 * with the Sequenzy JS bridge already injected; this dialog is the other end of it.
 *
 * Isolation:
 * - The document loads at [InAppHtmlBridgeMessage.BASE_URL], never the app's own origin, with file and content
 *   access off, no mixed content and no third-party cookies.
 * - Main-frame navigations away from the document are cancelled and go through the app's URL handler; popups
 *   never open a window.
 * - Android's WebView cookie jar is shared by the app's web views, so the document can read cookies set for the
 *   Sequenzy origin only. The bridge object is visible to frames the document embeds; every call still acts only
 *   on the identified contact.
 *
 * The dialog ignores touches until the document has loaded, so a slow document never blocks the app.
 */
internal class InAppHtmlDialog(
    private val activity: Activity,
    private val message: InAppMessage,
    private val onLoaded: () -> Unit,
    private val onFinish: (Outcome) -> Unit,
    /** False once a different contact is identified: the document's calls are dropped. */
    private val isForSignedInContact: () -> Boolean = { true },
) {
    sealed class Outcome {
        /** Closed by the document, the close button or Back. */
        data object Dismissed : Outcome()

        /** The document asked to open a link (already reported as a click). */
        data class Opened(val url: Uri) : Outcome()

        /** Never finished loading; not reported, so it shows again later. */
        data object Failed : Outcome()
    }

    private val main = Handler(Looper.getMainLooper())
    private var dialog: Dialog? = null
    private var webView: WebView? = null
    private var closeButton: View? = null
    private var loaded = false
    private var finished = false
    private var bridgeCalls = 0
    private val timeout = Runnable { if (!loaded) finish(Outcome.Failed) }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    fun show(): Dialog {
        val webView = WebView(activity)
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.contentDescription = message.title.ifEmpty { "Message" }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = Client()
        webView.addJavascriptInterface(Bridge(), BRIDGE_NAME)
        this.webView = webView

        val root = FrameLayout(activity)
        root.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (message.showCloseButton) {
            val density = activity.resources.displayMetrics.density
            val size = (36 * density).toInt()
            val close = TextView(activity).apply {
                text = "×"
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(140, 0, 0, 0))
                }
                contentDescription = "Close"
                isClickable = true
                isFocusable = true
                visibility = View.INVISIBLE
                setOnClickListener { finish(Outcome.Dismissed) }
            }
            root.addView(
                close,
                FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END).apply {
                    val margin = (16 * density).toInt()
                    setMargins(margin, margin, margin, margin)
                },
            )
            closeButton = close
        }
        webView.visibility = View.INVISIBLE

        val dialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            // Touches reach the app until the document has loaded.
            addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
        dialog.setOnCancelListener { finish(Outcome.Dismissed) }
        this.dialog = dialog
        dialog.show()

        main.postDelayed(timeout, LOAD_TIMEOUT_MS)
        webView.loadDataWithBaseURL(InAppHtmlBridgeMessage.BASE_URL, message.html ?: "", "text/html", "utf-8", null)
        return dialog
    }

    /** Closes without reporting; the presenter reports from [onFinish]. */
    fun dismiss() {
        finished = true
        teardown()
    }

    private fun didLoad() {
        if (loaded || finished) return
        loaded = true
        main.removeCallbacks(timeout)
        webView?.visibility = View.VISIBLE
        closeButton?.visibility = View.VISIBLE
        dialog?.window?.clearFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        )
        webView?.requestFocus()
        onLoaded()
    }

    private fun finish(outcome: Outcome) {
        if (finished) return
        finished = true
        teardown()
        onFinish(outcome)
    }

    private fun teardown() {
        main.removeCallbacks(timeout)
        dialog?.setOnCancelListener(null)
        dialog?.takeIf { it.isShowing }?.dismiss()
        dialog = null
        val view = webView ?: return
        webView = null
        view.removeJavascriptInterface(BRIDGE_NAME)
        view.stopLoading()
        view.webViewClient = WebViewClient()
        // Not from inside the WebView's own callback, which may be on the stack.
        main.post { view.destroy() }
    }

    private fun open(raw: String, buttonId: String?) {
        if (!InAppHtmlBridgeMessage.isAllowedUrl(raw)) {
            Log.w(TAG, "In-app message link blocked: $raw")
            return
        }
        if (!isForSignedInContact()) return finish(Outcome.Failed)
        Sequenzy.reportFromPresenter(InAppEventType.CLICKED, message.id, buttonId)
        finish(Outcome.Opened(Uri.parse(raw)))
    }

    private fun handle(call: InAppHtmlBridgeMessage) {
        if (finished) return
        bridgeCalls += 1
        if (bridgeCalls > InAppHtmlBridgeMessage.MAX_CALLS_PER_DISPLAY) return
        // Fetched for a contact who is no longer signed in: close, unreported.
        if (!isForSignedInContact()) return finish(Outcome.Failed)
        when (call) {
            is InAppHtmlBridgeMessage.Close -> finish(Outcome.Dismissed)
            is InAppHtmlBridgeMessage.OpenUrl -> open(call.url, call.buttonId)
            is InAppHtmlBridgeMessage.LogClick ->
                Sequenzy.reportFromPresenter(InAppEventType.CLICKED, message.id, call.buttonId)
            is InAppHtmlBridgeMessage.Track -> Sequenzy.track(call.name, call.properties)
            is InAppHtmlBridgeMessage.SetAttributes -> Sequenzy.setProfile(ProfileUpdate(attributes = call.attributes))
            is InAppHtmlBridgeMessage.SetName ->
                Sequenzy.setProfile(ProfileUpdate(firstName = call.firstName, lastName = call.lastName))
        }
    }

    /**
     * Called on WebView's JavaBridge thread; everything runs on the main thread. Not private: WebView reaches
     * `postMessage` by reflection (kept by consumer-rules.pro).
     */
    inner class Bridge {
        @JavascriptInterface
        fun postMessage(json: String) {
            val call = InAppHtmlBridgeMessage.parse(json) ?: return
            main.post { handle(call) }
        }
    }

    private inner class Client : WebViewClient() {
        @TargetApi(Build.VERSION_CODES.N)
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame || isOwnDocument(request.url.toString())) return false
            open(request.url.toString(), null)
            return true
        }

        @Deprecated("Called below API 24")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            if (isOwnDocument(url)) return false
            open(url, null)
            return true
        }

        /** The document's own URL or a #fragment of it. */
        private fun isOwnDocument(url: String): Boolean =
            url.substringBefore('#') == InAppHtmlBridgeMessage.BASE_URL || url.startsWith("about:")

        override fun onPageFinished(view: WebView, url: String?) {
            didLoad()
        }

        @TargetApi(Build.VERSION_CODES.M)
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame && !loaded) finish(Outcome.Failed)
        }

        @TargetApi(Build.VERSION_CODES.O)
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // The WebView is unusable now; leave without crashing the app.
            finish(if (loaded) Outcome.Dismissed else Outcome.Failed)
            return true
        }
    }

    private companion object {
        const val TAG = "Sequenzy"
        const val BRIDGE_NAME = "SequenzyAndroid"
        const val LOAD_TIMEOUT_MS = 10_000L
    }
}
