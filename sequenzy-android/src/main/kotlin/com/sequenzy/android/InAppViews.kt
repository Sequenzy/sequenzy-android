package com.sequenzy.android

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.sequenzy.core.InAppButton
import com.sequenzy.core.InAppMessage
import com.sequenzy.core.InAppStyle
import com.sequenzy.core.parseHexColor
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.cos
import kotlin.math.PI

// Draws messages to mobile-sdks/DESIGN.md with the platform's views; every
// size below matches the other renderers.

/** Step response of a damped spring (DESIGN.md: Motion), as an interpolator. */
private class SpringInterpolator(private val ratio: Float, response: Float, private val duration: Float) : Interpolator {
    private val omega = 2 * PI.toFloat() / response

    override fun getInterpolation(input: Float): Float {
        val t = input * duration
        val damped = omega * sqrt(1 - ratio * ratio)
        return 1 - exp(-ratio * omega * t) * (cos(damped * t) + ratio / sqrt(1 - ratio * ratio) * sin(damped * t))
    }
}

private fun springAnimator(response: Float, update: (Float) -> Unit): ValueAnimator {
    val seconds = response * 1.6f
    return ValueAnimator.ofFloat(0f, 1f).apply {
        duration = (seconds * 1000).toLong()
        interpolator = SpringInterpolator(0.86f, response, seconds)
        addUpdateListener { update(it.animatedValue as Float) }
    }
}

private val EASE_IN = PathInterpolator(0.32f, 0f, 0.67f, 0f)

/** WCAG contrast ratio, 1 to 21. */
private fun contrast(a: Int, b: Int) = ColorUtils.calculateContrast(a or 0xFF000000.toInt(), b or 0xFF000000.toInt())

private class Palette(
    val text: Int,
    val muted: Int,
    val eyebrow: Int,
    val primaryBackground: Int,
    val primaryText: Int,
    val secondaryLink: Int,
    val secondaryDismiss: Int,
    val closeFill: Int,
    val closeStroke: Int,
) {
    companion object {
        fun surface(colors: Colors) = Palette(
            text = colors.text,
            muted = colors.muted,
            eyebrow = if (contrast(colors.button, colors.background) >= 3) colors.button else colors.muted,
            primaryBackground = colors.button,
            primaryText = colors.buttonText,
            secondaryLink = colors.text,
            secondaryDismiss = colors.muted,
            closeFill = ColorUtils.setAlphaComponent(colors.text, 15),
            closeStroke = ColorUtils.setAlphaComponent(colors.text, 153),
        )

        fun image(colors: Colors): Palette {
            val keepsAccent = contrast(colors.button, 0xFF1F1F1F.toInt()) >= 2
            return Palette(
                text = Color.WHITE,
                muted = 0xD1FFFFFF.toInt(),
                eyebrow = 0xE6FFFFFF.toInt(),
                primaryBackground = if (keepsAccent) colors.button else Color.WHITE,
                primaryText = if (keepsAccent) colors.buttonText else 0xFF111111.toInt(),
                secondaryLink = Color.WHITE,
                secondaryDismiss = 0xD1FFFFFF.toInt(),
                closeFill = 0x52000000,
                closeStroke = Color.WHITE,
            )
        }
    }
}

private class Colors(style: InAppStyle) {
    val background = parseHexColor(style.backgroundColor) ?: Color.WHITE
    val text = parseHexColor(style.textColor) ?: 0xFF111827.toInt()
    val muted = parseHexColor(style.mutedTextColor) ?: 0xFF4B5563.toInt()
    val button = parseHexColor(style.buttonColor) ?: 0xFF111827.toInt()
    val buttonText = parseHexColor(style.buttonTextColor) ?: Color.WHITE
}

/** A 12 × 12 X from two round-capped strokes, on a circle. */
@SuppressLint("ViewConstructor")
private class CloseView(context: Context, private val palette: Palette, private val sizePx: Float, density: Float) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.closeFill }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.closeStroke
        strokeWidth = 2 * density
        strokeCap = Paint.Cap.ROUND
    }
    private val half = 6 * density

    init {
        contentDescription = "Close"
        isClickable = true
        isFocusable = true
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, sizePx / 2, fill)
        canvas.drawLine(cx - half, cy - half, cx + half, cy + half, stroke)
        canvas.drawLine(cx + half, cy - half, cx - half, cy + half, stroke)
    }
}

/**
 * Lets buttons inside take taps but claims vertical drags, so the sheet or
 * banner follows the finger. A drag the content can still scroll goes to the
 * content instead.
 */
@SuppressLint("ViewConstructor")
private class DragFrame(
    context: Context,
    private val onDrag: (Float) -> Unit,
    private val onRelease: (offset: Float, velocity: Float) -> Unit,
    private val scrollTarget: () -> View? = { null },
) : FrameLayout(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var startY = 0f
    private var lastY = 0f
    private var lastTime = 0L
    private var velocity = 0f
    private var dragging = false

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startY = event.rawY
                lastY = event.rawY
                lastTime = event.eventTime
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = event.rawY - startY
                // Moving the finger up scrolls content down, and the reverse.
                val contentScrolls = scrollTarget()?.canScrollVertically(if (dy < 0) 1 else -1) == true
                if (abs(dy) > slop && !contentScrolls) dragging = true
            }
        }
        return dragging
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_MOVE -> {
                val dt = (event.eventTime - lastTime).coerceAtLeast(1)
                velocity = (event.rawY - lastY) / dt * 1000
                lastY = event.rawY
                lastTime = event.eventTime
                onDrag(event.rawY - startY)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onRelease(event.rawY - startY, velocity)
        }
        return true
    }
}

/**
 * Covers its parent without counting toward the parent's wrap-content size:
 * it measures to nothing until the parent gives it an exact size.
 */
internal class CoverFrame(context: Context) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val exact = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY &&
            MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY
        if (exact) super.onMeasure(widthMeasureSpec, heightMeasureSpec) else setMeasuredDimension(0, 0)
    }
}

/** A frame that never grows past [maxHeightPx], so its scroll view scrolls. */
internal class MaxHeightFrame(context: Context) : FrameLayout(context) {
    var maxHeightPx = Int.MAX_VALUE

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (maxHeightPx == Int.MAX_VALUE) return super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val given = MeasureSpec.getSize(heightMeasureSpec)
        val unbounded = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED
        val limit = if (unbounded) maxHeightPx else min(maxHeightPx, given)
        // AT_MOST also caps the minimum height of an image layout.
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST))
    }
}

/** Builds the views for one message. `onFinish` runs after the exit animation. */
internal class MessageViews(
    private val activity: Activity,
    private val message: InAppMessage,
    private val onFinish: (InAppPresenter.Outcome) -> Unit,
) {
    private val style: InAppStyle = message.style
    private val tones = Colors(style)
    private val density = activity.resources.displayMetrics.density
    private val fontScale = min(activity.resources.configuration.fontScale, 1.4f)
    private val reduceMotion = runCatching {
        Settings.Global.getFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)
    private var finished = false
    private var leave: ((() -> Unit) -> Unit)? = null

    /** Runs the exit animation once, then reports the outcome. */
    private fun close(outcome: InAppPresenter.Outcome) {
        if (finished) return
        finished = true
        val exit = leave
        if (exit == null) onFinish(outcome) else exit { onFinish(outcome) }
    }

    private fun onButton(button: InAppButton) = close(
        if (button.action == InAppButton.Action.DISMISS) InAppPresenter.Outcome.Dismissed else InAppPresenter.Outcome.Tapped(button),
    )

    // region Modal

    fun modalDialog(): Dialog {
        val screenWidth = activity.resources.displayMetrics.widthPixels / density
        val dialogLayout = message.position == InAppMessage.Position.CENTER || screenWidth >= 600
        val sheet = !dialogLayout
        val dialog = overlayDialog()
        val root = FrameLayout(activity)
        val backdrop = View(activity).apply {
            alpha = 0f
            setOnClickListener { close(InAppPresenter.Outcome.Dismissed) }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(backdrop, FrameLayout.LayoutParams(MATCH, MATCH))

        val panelWidth = if (dialogLayout) min(screenWidth - 40, 360f) else screenWidth
        val panel = MaxHeightFrame(activity).apply {
            background = GradientDrawable().apply {
                setColor(tones.background)
                val radius = dp(style.cornerRadius.toFloat()).toFloat()
                cornerRadii = if (sheet) floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f) else FloatArray(8) { radius }
            }
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            if (dialogLayout) elevation = dp(24).toFloat()
        }
        var bottomInset = 0
        var content: View? = null
        var scrollView: View? = null
        fun contentBottom() = if (sheet) max(dp(16) + bottomInset, dp(24)) else dp(24)

        // Builds the panel for one image placement. A failed image builds it
        // again without one: surface tones, no-image padding.
        lateinit var fill: (InAppMessage.ImagePlacement?) -> Unit
        fill = { placement ->
            panel.removeAllViews()
            panel.minimumHeight = 0
            val onImage = placement == InAppMessage.ImagePlacement.BACKGROUND
            val palette = if (onImage) Palette.image(tones) else Palette.surface(tones)
            val closePalette = if (placement != null) Palette.image(tones) else Palette.surface(tones)
            val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            if (onImage) {
                // The picture sizes to the panel, never the panel to the picture.
                val cover = CoverFrame(activity).apply {
                    addView(imageView { if (!finished) fill(null) }, FrameLayout.LayoutParams(MATCH, MATCH))
                    addView(View(activity).apply { background = scrim() }, FrameLayout.LayoutParams(MATCH, MATCH))
                }
                panel.addView(cover, FrameLayout.LayoutParams(MATCH, MATCH))
                panel.minimumHeight = if (sheet) dp(460) else dp(panelWidth * 1.25f)
            } else if (placement == InAppMessage.ImagePlacement.TOP) {
                column.addView(
                    imageView { if (!finished) fill(null) },
                    LinearLayout.LayoutParams(MATCH, dp(min(panelWidth * 9 / 16, 260f))),
                )
            }
            val text = textAndButtons(palette, center = dialogLayout)
            text.setPadding(dp(24), dp(if (placement == InAppMessage.ImagePlacement.TOP) 20 else 28), dp(24), contentBottom())
            column.addView(text)
            content = text
            // Long copy at large text sizes scrolls inside the panel; the
            // close button stays put above it.
            val scroll = ScrollView(activity).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(column, FrameLayout.LayoutParams(MATCH, WRAP))
            }
            panel.addView(scroll, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
            scrollView = scroll
            if (sheet) {
                panel.addView(
                    View(activity).apply {
                        background = GradientDrawable().apply {
                            cornerRadius = dp(2.5f).toFloat()
                            setColor(if (placement != null) 0xB3FFFFFF.toInt() else ColorUtils.setAlphaComponent(tones.text, 51))
                        }
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                    FrameLayout.LayoutParams(dp(36), dp(5), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(6) },
                )
            }
            panel.addView(
                closeView(closePalette, 30) { close(InAppPresenter.Outcome.Dismissed) },
                FrameLayout.LayoutParams(dp(46), dp(46), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(6), 0) },
            )
            backdrop.setBackgroundColor(if (onImage) 0x80000000.toInt() else 0x66000000)
        }
        fill(placementOf(message))

        val host: View
        if (sheet) {
            var offset = 0f
            val frame = DragFrame(
                activity,
                onDrag = { dy ->
                    offset = if (dy > 0) dy else dy / 3
                    panel.translationY = offset
                },
                onRelease = { dy, velocity ->
                    if (dy > panel.height * 0.3f || velocity > dp(1000)) {
                        close(InAppPresenter.Outcome.Dismissed)
                    } else {
                        val start = panel.translationY
                        springAnimator(0.42f) { panel.translationY = start * (1 - it) }.start()
                    }
                },
                scrollTarget = { scrollView },
            )
            frame.addView(panel, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
            root.addView(frame, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
            host = frame
        } else {
            root.addView(panel, FrameLayout.LayoutParams(dp(panelWidth), WRAP, Gravity.CENTER))
            host = panel
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            bottomInset = bars.bottom
            content?.let { it.setPadding(it.paddingLeft, it.paddingTop, it.paddingRight, contentBottom()) }
            val screenHeight = activity.resources.displayMetrics.heightPixels
            if (sheet) {
                host.setPadding(0, bars.top + dp(24), 0, 0)
                panel.maxHeightPx = screenHeight - bars.top - dp(24)
            } else {
                panel.maxHeightPx = screenHeight - bars.top - bars.bottom - dp(48)
            }
            panel.requestLayout()
            insets
        }

        dialog.setContentView(root)
        root.post {
            backdrop.animate().alpha(1f).setDuration(if (reduceMotion) 180 else 220).start()
            when {
                reduceMotion -> {
                    panel.alpha = 0f
                    panel.animate().alpha(1f).setDuration(180).start()
                }
                sheet -> {
                    val from = panel.height.toFloat()
                    panel.translationY = from
                    springAnimator(0.42f) { panel.translationY = from * (1 - it) }.start()
                }
                else -> {
                    panel.alpha = 0f
                    panel.scaleX = 0.94f
                    panel.scaleY = 0.94f
                    panel.animate().alpha(1f).setDuration(200).start()
                    springAnimator(0.32f) {
                        panel.scaleX = 0.94f + 0.06f * it
                        panel.scaleY = 0.94f + 0.06f * it
                    }.start()
                }
            }
        }
        leave = { done ->
            backdrop.animate().alpha(0f).setDuration(if (reduceMotion) 180 else 200).start()
            val animator = panel.animate().setInterpolator(EASE_IN)
            when {
                reduceMotion -> animator.alpha(0f).setDuration(180)
                sheet -> animator.translationY(panel.height.toFloat() + bottomInset).setDuration(220)
                else -> animator.alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(160)
            }
            animator.withEndAction(done).start()
        }
        return dialog
    }

    // endregion

    // region Full screen

    fun fullscreenDialog(): Dialog {
        val dialog = overlayDialog()
        val root = FrameLayout(activity).apply { setBackgroundColor(tones.background) }
        val screenHeight = activity.resources.displayMetrics.heightPixels
        var bars = Insets.NONE
        var applyInsets: () -> Unit = {}

        // Builds the screen for one image placement. A failed image builds it
        // again without one: surface tones, the title below the status bar.
        lateinit var fill: (InAppMessage.ImagePlacement?) -> Unit
        fill = { placement ->
            root.removeAllViews()
            val onImage = placement == InAppMessage.ImagePlacement.BACKGROUND
            val palette = if (onImage) Palette.image(tones) else Palette.surface(tones)
            if (onImage) {
                root.addView(imageView { if (!finished) fill(null) }, FrameLayout.LayoutParams(MATCH, MATCH))
                root.addView(View(activity).apply { background = scrim() }, FrameLayout.LayoutParams(MATCH, MATCH))
            }
            val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            if (placement == InAppMessage.ImagePlacement.TOP) {
                column.addView(
                    imageView { if (!finished) fill(null) },
                    LinearLayout.LayoutParams(MATCH, (screenHeight * 0.48f).toInt()),
                )
            }
            val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            if (placement != InAppMessage.ImagePlacement.TOP) body.addView(View(activity), LinearLayout.LayoutParams(MATCH, 0, 1f))
            body.addView(textBlock(palette, center = false, fullscreen = true))
            if (!onImage) body.addView(View(activity), LinearLayout.LayoutParams(MATCH, 0, if (placement == null) 1.4f else 1f))
            body.addView(buttonStack(palette))
            column.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))
            val scroll = ScrollView(activity).apply {
                isFillViewport = true
                addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
            }
            root.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
            val closeButton = closeView(if (placement != null) Palette.image(tones) else palette, 32) { close(InAppPresenter.Outcome.Dismissed) }
            root.addView(closeButton, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END))
            applyInsets = {
                body.setPadding(dp(24), if (placement == InAppMessage.ImagePlacement.TOP) dp(28) else bars.top + dp(56), dp(24), dp(16) + bars.bottom)
                (closeButton.layoutParams as FrameLayout.LayoutParams).setMargins(0, bars.top + dp(4), dp(8), 0)
                closeButton.requestLayout()
            }
            applyInsets()
        }
        fill(placementOf(message))

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            applyInsets()
            insets
        }
        dialog.setContentView(root)
        root.alpha = 0f
        root.translationY = if (reduceMotion) 0f else dp(24).toFloat()
        root.post {
            root.animate().alpha(1f).translationY(0f).setDuration(if (reduceMotion) 180 else 280)
                .setInterpolator(DecelerateInterpolator(1.5f)).start()
        }
        leave = { done -> root.animate().alpha(0f).setDuration(if (reduceMotion) 180 else 200).withEndAction(done).start() }
        return dialog
    }

    // endregion

    // region Banner

    fun banner(): View {
        val palette = Palette.surface(tones)
        val top = message.resolvedPosition == InAppMessage.Position.TOP
        val direction = if (top) -1f else 1f
        val link = splitButtons(message.buttons).first?.takeIf { it.action == InAppButton.Action.OPEN_URL }

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(12), dp(8), dp(12))
            background = GradientDrawable().apply {
                setColor(tones.background)
                cornerRadius = dp(min(style.cornerRadius.toFloat(), 24f)).toFloat()
                setStroke(max(1, dp(0.5f)), ColorUtils.setAlphaComponent(tones.text, 15))
            }
            elevation = dp(8).toFloat()
            contentDescription = "sequenzy.banner"
        }
        val tappable = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            if (link != null) setOnClickListener { close(InAppPresenter.Outcome.Tapped(null)) }
        }
        message.imageUrl?.let {
            val image = imageView { tappable.getChildAt(0)?.visibility = View.GONE }.apply {
                outlineProvider = roundedOutline(10f)
                clipToOutline = true
            }
            tappable.addView(image, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
        }
        val text = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(1), 0, 0)
        }
        val hasTitle = message.title.isNotEmpty()
        if (hasTitle) text.addView(label(message.title, 15f, 20f, Typeface.BOLD, palette.text))
        if (message.body.isNotEmpty()) {
            text.addView(
                (if (hasTitle) label(message.body, 14f, 19f, Typeface.NORMAL, palette.muted) else label(message.body, 15f, 20f, Typeface.NORMAL, palette.text))
                    .apply {
                        maxLines = if (hasTitle) 2 else 3
                        ellipsize = TextUtils.TruncateAt.END
                        if (hasTitle) setPadding(0, dp(2), 0, 0)
                    },
            )
        }
        link?.let {
            text.addView(label(it.label, 14f, 19f, Typeface.BOLD, palette.eyebrow).apply { setPadding(0, dp(6), 0, 0) })
        }
        tappable.addView(text, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(tappable, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(closeView(palette, 28) { close(InAppPresenter.Outcome.Dismissed) }, LinearLayout.LayoutParams(dp(28), dp(28)))

        lateinit var frame: DragFrame
        frame = DragFrame(
            activity,
            onDrag = { dy -> row.translationY = if (dy * direction > 0) dy else dy / 3 },
            onRelease = { dy, velocity ->
                if (dy * direction > row.height * 0.4f || velocity * direction > dp(800)) {
                    close(InAppPresenter.Outcome.Dismissed)
                } else {
                    val start = row.translationY
                    springAnimator(0.38f) { row.translationY = start * (1 - it) }.start()
                }
            },
        )
        val screenWidth = activity.resources.displayMetrics.widthPixels / density
        frame.addView(row, FrameLayout.LayoutParams(dp(min(screenWidth - 24, 480f)), WRAP))
        // Only as wide as the banner, so taps beside it reach the app.
        frame.layoutParams = FrameLayout.LayoutParams(WRAP, WRAP, (if (top) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL)
        frame.clipChildren = false
        frame.clipToPadding = false

        ViewCompat.setOnApplyWindowInsetsListener(frame) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (view.layoutParams as FrameLayout.LayoutParams).setMargins(
                dp(12), if (top) bars.top + dp(8) else 0, dp(12), if (top) 0 else bars.bottom + dp(12),
            )
            view.requestLayout()
            insets
        }
        frame.post {
            if (reduceMotion) {
                row.alpha = 0f
                row.animate().alpha(1f).setDuration(180).start()
            } else {
                val from = direction * (row.height + dp(40))
                row.translationY = from
                springAnimator(0.38f) { row.translationY = from * (1 - it) }.start()
            }
        }
        leave = { done ->
            val animator = row.animate().setInterpolator(EASE_IN).setDuration(180)
            if (reduceMotion) animator.alpha(0f) else animator.translationY(direction * (row.height + dp(40)))
            animator.withEndAction(done).start()
        }
        return frame
    }

    // endregion

    // region Parts

    private fun overlayDialog(): Dialog {
        @Suppress("DEPRECATION")
        val dialog = object : Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar) {
            @Deprecated("Deprecated in Java")
            override fun onBackPressed() = close(InAppPresenter.Outcome.Dismissed)
        }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            WindowCompat.setDecorFitsSystemWindows(this, false)
        }
        return dialog
    }

    private fun textAndButtons(palette: Palette, center: Boolean) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(textBlock(palette, center, fullscreen = false))
        addView(buttonStack(palette))
    }

    private fun textBlock(palette: Palette, center: Boolean, fullscreen: Boolean) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val gravityValue = if (center) Gravity.CENTER_HORIZONTAL else Gravity.START
        // Centered headings keep clear of the close button in the corner.
        val inset = if (center) dp(20) else 0
        message.eyebrow.trim().takeIf { it.isNotEmpty() }?.let {
            addView(label(it, 13f, 18f, Typeface.BOLD, palette.eyebrow).apply {
                gravity = gravityValue
                setPadding(inset, 0, inset, dp(6))
            })
        }
        if (message.title.isNotEmpty()) {
            addView(
                label(message.title, if (fullscreen) 30f else 22f, if (fullscreen) 36f else 28f, Typeface.BOLD, palette.text).apply {
                    gravity = gravityValue
                    letterSpacing = if (fullscreen) -0.02f else -0.018f
                    setPadding(inset, 0, inset, 0)
                    ViewCompat.setAccessibilityHeading(this, true)
                },
            )
        }
        if (message.body.isNotEmpty()) {
            addView(
                label(message.body, if (fullscreen) 17f else 16f, if (fullscreen) 25f else 23f, Typeface.NORMAL, palette.muted).apply {
                    gravity = gravityValue
                    if (message.title.isNotEmpty()) setPadding(0, dp(8), 0, 0)
                },
            )
        }
    }

    private fun buttonStack(palette: Palette) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val (primary, secondary) = splitButtons(message.buttons)
        if (primary == null) return@apply
        setPadding(0, dp(24), 0, 0)
        addView(
            label(primary.label, 17f, 22f, Typeface.BOLD, palette.primaryText).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(16), 0, dp(16), 0)
                background = GradientDrawable().apply {
                    setColor(palette.primaryBackground)
                    cornerRadius = dp(min(style.buttonRadius.toFloat(), 26f)).toFloat()
                }
                pressedDim(0.85f)
                setOnClickListener { onButton(primary) }
            },
            LinearLayout.LayoutParams(MATCH, dp(52)),
        )
        secondary?.let { button ->
            addView(
                label(button.label, 17f, 22f, Typeface.BOLD, if (button.action == InAppButton.Action.OPEN_URL) palette.secondaryLink else palette.secondaryDismiss).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    pressedDim(0.6f)
                    setOnClickListener { onButton(button) }
                },
                LinearLayout.LayoutParams(MATCH, dp(48)).apply { topMargin = dp(4) },
            )
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun View.pressedDim(opacity: Float) {
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.alpha = opacity
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.alpha = 1f
            }
            false
        }
    }

    private fun closeView(palette: Palette, sizeDp: Int, onClick: () -> Unit): View =
        CloseView(activity, palette, dp(sizeDp).toFloat(), density).apply { setOnClickListener { onClick() } }

    private fun label(text: String, sizeDp: Float, lineHeightDp: Float, typeface: Int, color: Int) = TextView(activity).apply {
        this.text = text
        setTextColor(color)
        // dp scaled by the system font size, capped at 1.4×.
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sizeDp * density * fontScale)
        setLineSpacing(0f, 1f)
        val lineHeight = (lineHeightDp * density * fontScale).toInt()
        val fontHeight = paint.getFontMetricsInt(null)
        setLineSpacing((lineHeight - fontHeight).toFloat(), 1f)
        includeFontPadding = false
        this.typeface = font(typeface)
    }

    private fun font(styleFlag: Int): Typeface {
        val family = style.fontFamilies.firstOrNull()
        return if (family != null) Typeface.create(family, styleFlag) else Typeface.defaultFromStyle(styleFlag)
    }

    private fun scrim() = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(0x00000000, 0x8C000000.toInt(), 0xD1000000.toInt()),
    ).apply { setGradientCenter(0.5f, 0.55f) }

    private fun roundedOutline(radiusDp: Float) = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, dp(radiusDp).toFloat())
        }
    }

    /** An image on a faint placeholder; `onError` runs if it fails to load. */
    private fun imageView(onError: () -> Unit): ImageView {
        val view = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(ColorUtils.setAlphaComponent(tones.text, 15))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val url = message.imageUrl?.takeIf { it.startsWith("https://") }
        if (url == null) {
            view.post(onError)
            return view
        }
        thread(isDaemon = true) {
            val bitmap: Bitmap? = runCatching {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                try {
                    connection.inputStream.use(BitmapFactory::decodeStream)
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
            activity.runOnUiThread { if (bitmap != null) view.setImageBitmap(bitmap) else onError() }
        }
        return view
    }

    private fun dp(value: Int) = dp(value.toFloat())

    private fun dp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, activity.resources.displayMetrics).toInt()

    // endregion

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}

/** The primary button (first link, or first button) and the other one. */
internal fun splitButtons(buttons: List<InAppButton>): Pair<InAppButton?, InAppButton?> {
    val index = buttons.indexOfFirst { it.action == InAppButton.Action.OPEN_URL }
    val primaryIndex = if (index == -1) 0 else index
    val primary = buttons.getOrNull(primaryIndex)
    val secondary = buttons.filterIndexed { i, _ -> i != primaryIndex }.firstOrNull()
    return primary to secondary
}

/** "background" only when there is an image to put behind the text. */
internal fun placementOf(message: InAppMessage): InAppMessage.ImagePlacement? {
    if (message.imageUrl == null) return null
    return if (message.imagePlacement == InAppMessage.ImagePlacement.BACKGROUND) {
        InAppMessage.ImagePlacement.BACKGROUND
    } else {
        InAppMessage.ImagePlacement.TOP
    }
}
