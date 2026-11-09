package com.sequenzy.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.sequenzy.core.InAppButton
import com.sequenzy.core.InAppEventType
import com.sequenzy.core.InAppMessage
import com.sequenzy.core.SequenzyCore
import com.sequenzy.core.parseHexColor
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Shared by every [SequenzyPlacementView] in the app: one cache per placement key, fetches batched across views
 * attached in the same main-thread turn, and each item reported displayed once per app session. Content belongs to
 * one contact: identifying someone else or resetting empties the cache, and answers for the previous contact are
 * dropped. Every method runs on the main thread.
 */
object SequenzyPlacements {
    private val main = Handler(Looper.getMainLooper())
    private val items = HashMap<String, InAppMessage>()
    private val loaded = HashSet<String>()
    private val observers = HashMap<String, MutableSet<(InAppMessage?) -> Unit>>()
    private val pending = LinkedHashSet<String>()
    // Item ids are per contact, so these never need clearing.
    private val displayed = HashSet<String>()

    // Dismissed this session: a fetch that raced the report must not bring the item back.
    private val dismissed = HashSet<String>()
    private var scheduled = false

    // Who the cached content belongs to; contactKnown is false until the first identify, reset or fetch.
    private var contact: String? = null
    private var contactKnown = false

    // Bumped when the contact changes so a late answer for the previous one is dropped.
    private var generation = 0

    // The newest request sent for each key (main thread only).
    private val latestRequest = HashMap<String, Int>()
    private var requests = 0
    private var lastFetchAt = 0L
    private const val FOREGROUND_REFRESH_MS = 60_000L

    /** What the placement shows now, from the last fetch; null when empty. */
    @JvmStatic
    fun item(placementId: String): InAppMessage? = items[placementId]

    /**
     * Calls [observer] with the placement's item now (if fetched) and on every change. Observing a new placement
     * fetches it. Returns a function that stops observing.
     */
    @JvmStatic
    fun observe(placementId: String, observer: (InAppMessage?) -> Unit): () -> Unit {
        observers.getOrPut(placementId) { LinkedHashSet() }.add(observer)
        if (placementId in loaded) observer(items[placementId]) else schedule(listOf(placementId))
        return {
            observers[placementId]?.let { set ->
                set.remove(observer)
                if (set.isEmpty()) observers.remove(placementId)
            }
        }
    }

    /** Fetch one placement again, or every observed one. */
    @JvmStatic
    @JvmOverloads
    fun refresh(placementId: String? = null) {
        schedule(placementId?.let { listOf(it) } ?: observers.keys.toList())
    }

    /** Reports the item shown; once per item per app session. */
    @JvmStatic
    fun logDisplayed(item: InAppMessage) {
        if (displayed.add(item.id)) Sequenzy.report(InAppEventType.DISPLAYED, item)
    }

    /** Reports a dismissal, empties the slot and asks for its next item. */
    @JvmStatic
    fun dismiss(item: InAppMessage) {
        dismissed.add(item.id)
        val key = item.placement ?: return Sequenzy.report(InAppEventType.DISMISSED, item)
        if (items[key]?.id == item.id) set(key, null)
        // Ask for the next item once the server has the dismissal: sooner, it still answers with the dismissed item.
        val core = Sequenzy.currentCore ?: return
        Sequenzy.scope.launch {
            runCatching { core.report(InAppEventType.DISMISSED, item.id) }
            main.post { schedule(listOf(key)) }
        }
    }

    /** After identify (the contact key: external ID or email) or reset (null). */
    internal fun identityChanged(email: String?) = main.post {
        if (contactKnown && contact == email) {
            refresh()
            return@post
        }
        contact = email
        contactKnown = true
        // Another contact's content must never show: forget every slot, cached ones too, so a view attached later
        // fetches.
        generation += 1
        pending.clear()
        val observed = observers.keys.toList()
        items.clear()
        loaded.clear()
        observed.forEach { set(it, null) }
        if (email != null) refresh()
    }

    internal fun appForegrounded() = main.post {
        if (System.currentTimeMillis() - lastFetchAt >= FOREGROUND_REFRESH_MS) refresh()
    }

    private fun set(key: String, item: InAppMessage?) {
        loaded.add(key)
        if (item == null) items.remove(key) else items[key] = item
        observers[key]?.toList()?.forEach { it(item) }
    }

    private fun schedule(keys: Collection<String>) {
        pending.addAll(keys)
        if (scheduled) return
        scheduled = true
        main.post {
            scheduled = false
            val batch = pending.toList()
            pending.clear()
            if (batch.isNotEmpty()) fetch(batch)
        }
    }

    private fun fetch(keys: List<String>) {
        val core = Sequenzy.currentCore ?: return
        val started = generation
        lastFetchAt = System.currentTimeMillis()
        // Whoever is signed in now owns what this fetch returns.
        if (!contactKnown) {
            contact = core.contactKey
            contactKnown = true
        }
        // The newest request for each key wins: an older answer arriving later (a refresh in flight during a
        // dismissal) must not overwrite it.
        val requestsByChunk = keys.chunked(SequenzyCore.MAX_PLACEMENTS_PER_REQUEST).map { chunk ->
            val request = ++requests
            chunk.forEach { latestRequest[it] = request }
            chunk to request
        }
        Sequenzy.scope.launch {
            for ((chunk, request) in requestsByChunk) {
                // A failed fetch keeps what was showing; empty slots stay collapsed.
                val found = runCatching { core.fetchPlacements(chunk) }.getOrNull() ?: continue
                main.post {
                    if (started == generation) {
                        chunk.filter { latestRequest[it] == request }
                            .forEach { key -> set(key, found[key]?.takeUnless { it.id in dismissed }) }
                    }
                }
            }
        }
    }
}

/**
 * A slot in your layout that shows Sequenzy content for one placement, drawn to mobile-sdks/DESIGN.md (the card
 * layout without unread dot, pin or close control). `GONE` while there is nothing to show, so the slot collapses.
 * Reports displayed once per item per session, clicks and dismissals.
 *
 * In XML set the key with `android:tag="home_hero"`, or call [placementId]. In Compose:
 * `AndroidView(factory = { SequenzyPlacementView(it, "home_hero") })`.
 */
class SequenzyPlacementView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    constructor(context: Context, placementId: String) : this(context) {
        this.placementId = placementId
    }

    /** The placement key defined in Sequenzy, e.g. `home_hero`. */
    var placementId: String = (tag as? String).orEmpty()
        set(value) {
            if (field == value) return
            field = value
            if (isAttachedToWindow) follow()
        }

    /** Called when the slot's content changes; null when it collapses. */
    var onChange: ((InAppMessage?) -> Unit)? = null

    var item: InAppMessage? = null
        private set

    private var stopObserving: (() -> Unit)? = null

    init {
        visibility = View.GONE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        follow()
    }

    override fun onDetachedFromWindow() {
        stopObserving?.invoke()
        stopObserving = null
        super.onDetachedFromWindow()
    }

    private fun follow() {
        stopObserving?.invoke()
        stopObserving = null
        if (placementId.isBlank()) {
            show(null)
            return
        }
        stopObserving = SequenzyPlacements.observe(placementId) { show(it) }
    }

    private fun show(next: InAppMessage?) {
        if (next == item) return
        item = next
        removeAllViews()
        visibility = if (next == null) View.GONE else View.VISIBLE
        if (next != null) {
            addView(build(next), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            if (isAttachedToWindow) SequenzyPlacements.logDisplayed(next)
        }
        onChange?.invoke(next)
    }

    private fun dp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

    /** Text and button colors: on the item's surface, or over a hero image (mobile-sdks/DESIGN.md "Colors"). */
    private class Colors(val text: Int, val muted: Int, val eyebrow: Int, val fill: Int, val onFill: Int)

    private fun colors(item: InAppMessage, overImage: Boolean): Colors {
        val style = item.style
        val opaque = 0xFF000000.toInt()
        val background = parseHexColor(style.backgroundColor) ?: Color.WHITE
        val accent = parseHexColor(style.buttonColor) ?: 0xFF111827.toInt()
        val buttonText = parseHexColor(style.buttonTextColor) ?: Color.WHITE
        if (overImage) {
            val keepsAccent = ColorUtils.calculateContrast(accent or opaque, 0xFF1F1F1F.toInt()) >= 2
            return Colors(
                text = Color.WHITE,
                muted = 0xD1FFFFFF.toInt(),
                eyebrow = 0xE6FFFFFF.toInt(),
                fill = if (keepsAccent) accent else Color.WHITE,
                onFill = if (keepsAccent) buttonText else 0xFF111111.toInt(),
            )
        }
        val muted = parseHexColor(style.mutedTextColor) ?: 0xFF4B5563.toInt()
        return Colors(
            text = parseHexColor(style.textColor) ?: 0xFF111827.toInt(),
            muted = muted,
            // The accent when it contrasts with the card at least 3:1, otherwise muted.
            eyebrow = if (ColorUtils.calculateContrast(accent or opaque, background or opaque) >= 3) accent else muted,
            fill = accent,
            onFill = buttonText,
        )
    }

    private fun label(item: InAppMessage, value: String, sizeSp: Float, flag: Int, color: Int, lines: Int = Int.MAX_VALUE) =
        TextView(context).apply {
            val family = item.style.fontFamilies.firstOrNull()
            this.text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            typeface = if (family != null) Typeface.create(family, flag) else Typeface.defaultFromStyle(flag)
            setTextColor(color)
            maxLines = lines
            if (lines != Int.MAX_VALUE) ellipsize = TextUtils.TruncateAt.END
        }

    /** The primary button (the first that acts, else the first) and the other one. */
    private fun splitButtons(buttons: List<InAppButton>): Pair<InAppButton?, InAppButton?> {
        if (buttons.isEmpty()) return null to null
        val index = buttons.indexOfFirst { it.action != InAppButton.Action.DISMISS }.takeIf { it >= 0 } ?: 0
        return buttons[index] to buttons.filterIndexed { i, _ -> i != index }.firstOrNull()
    }

    private fun loadImage(url: String, into: ImageView, onFailure: () -> Unit) {
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
            into.post { if (bitmap != null) into.setImageBitmap(bitmap) else onFailure() }
        }
    }

    private fun build(item: InAppMessage): View {
        val text = parseHexColor(item.style.textColor) ?: 0xFF111827.toInt()
        val chrome = GradientDrawable().apply {
            setColor(parseHexColor(item.style.backgroundColor) ?: Color.WHITE)
            cornerRadius = dp(item.style.cornerRadius.toFloat()).toFloat()
            setStroke(1, ColorUtils.setAlphaComponent(text, 20))
        }
        val image = item.imageUrl?.takeIf { it.startsWith("https://") }
        val view = when (item.layout) {
            InAppMessage.PlacementLayout.CARD -> buildCard(item, image)
            InAppMessage.PlacementLayout.BANNER -> buildBanner(item, image)
            InAppMessage.PlacementLayout.HERO -> buildHero(item, image)
        }
        return view.apply {
            background = chrome
            clipToOutline = true
            elevation = dp(1f).toFloat()
        }
    }

    /** Eyebrow, title and body in [column] (the card, banner or hero type ramp). */
    private fun addText(item: InAppMessage, column: LinearLayout, colors: Colors, hero: Boolean, bodyLines: Int) {
        fun add(view: View, topDp: Float) {
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (column.childCount > 0) params.topMargin = dp(topDp)
            column.addView(view, params)
        }
        item.eyebrow.trim().takeIf { it.isNotEmpty() }?.let {
            add(label(item, it, if (hero) 13f else 12f, Typeface.BOLD, colors.eyebrow), 0f)
        }
        if (item.title.isNotEmpty()) {
            add(label(item, item.title, if (hero) 20f else 16f, Typeface.BOLD, colors.text), if (hero) 6f else 4f)
        }
        if (item.body.isNotEmpty()) {
            val gap = if (item.layout == InAppMessage.PlacementLayout.BANNER) 4f else 6f
            add(label(item, item.body, 14f, Typeface.NORMAL, colors.muted, lines = bodyLines), gap)
        }
    }

    /** A filled primary button, then the other button as a text link. */
    private fun addFilledButtons(item: InAppMessage, column: LinearLayout, colors: Colors, topDp: Float) {
        val (primary, secondary) = splitButtons(item.buttons)
        primary ?: return
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val filled = label(item, primary.label, 14f, Typeface.BOLD, colors.onFill, lines = 1).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14f), 0, dp(14f), 0)
            background = GradientDrawable().apply {
                setColor(colors.fill)
                cornerRadius = dp(minOf(item.style.buttonRadius.toFloat(), 18f)).toFloat()
            }
            isClickable = true
            isFocusable = true
            contentDescription = primary.label
            setOnClickListener { tap(primary, item) }
        }
        row.addView(filled, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36f)))
        secondary?.let { button ->
            val link = label(item, button.label, 14f, Typeface.BOLD, colors.muted, lines = 1).apply {
                isClickable = true
                isFocusable = true
                contentDescription = button.label
                setOnClickListener { tap(button, item) }
            }
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.marginStart = dp(16f)
            row.addView(link, params)
        }
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (column.childCount > 0) params.topMargin = dp(topDp)
        column.addView(row, params)
    }

    /** Image across the top at 2:1, then the text and text-link buttons. */
    private fun buildCard(item: InAppMessage, imageUrl: String?): View {
        val colors = colors(item, overImage = false)
        val card = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        imageUrl?.let { url ->
            val image = object : ImageView(context) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    val width = View.MeasureSpec.getSize(widthMeasureSpec)
                    setMeasuredDimension(width, width / 2)
                }
            }.apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(ColorUtils.setAlphaComponent(colors.text, 15))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            card.addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            loadImage(url, image) { image.visibility = View.GONE }
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = dp(16f)
            setPadding(padding, padding, padding, padding)
        }
        card.addView(content)
        addText(item, content, colors, hero = false, bodyLines = 4)
        val (primary, secondary) = splitButtons(item.buttons)
        if (primary != null) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            listOf(primary to colors.eyebrow, secondary to colors.muted).forEach { (button, color) ->
                button ?: return@forEach
                val control = label(item, button.label, 15f, Typeface.BOLD, color).apply {
                    isClickable = true
                    isFocusable = true
                    contentDescription = button.label
                    setOnClickListener { tap(button, item) }
                }
                val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                if (row.childCount > 0) params.marginStart = dp(20f)
                row.addView(control, params)
            }
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (content.childCount > 0) params.topMargin = dp(12f)
            content.addView(row, params)
        }
        return card
    }

    /** A 56 thumbnail beside the text, with a filled primary button. */
    private fun buildBanner(item: InAppMessage, imageUrl: String?): View {
        val colors = colors(item, overImage = false)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val padding = dp(14f)
            setPadding(padding, padding, padding, padding)
        }
        imageUrl?.let { url ->
            val image = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                background = GradientDrawable().apply {
                    setColor(ColorUtils.setAlphaComponent(colors.text, 15))
                    cornerRadius = dp(minOf(item.style.cornerRadius.toFloat(), 12f)).toFloat()
                }
                clipToOutline = true
            }
            val params = LinearLayout.LayoutParams(dp(56f), dp(56f))
            params.marginEnd = dp(12f)
            row.addView(image, params)
            loadImage(url, image) { image.visibility = View.GONE }
        }
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addText(item, column, colors, hero = false, bodyLines = 2)
        addFilledButtons(item, column, colors, topDp = 10f)
        row.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    /** Text over the image (or the surface), at least half as tall as it is wide. */
    private fun buildHero(item: InAppMessage, imageUrl: String?): View {
        val colors = colors(item, overImage = imageUrl != null)
        val hero = object : FrameLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                minimumHeight = View.MeasureSpec.getSize(widthMeasureSpec) / 2
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }
        val fill = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        imageUrl?.let { url ->
            val image = BackdropImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(ColorUtils.setAlphaComponent(Color.BLACK, 15))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            hero.addView(image, fill)
            hero.addView(ScrimView(context), FrameLayout.LayoutParams(fill))
            // A failed image draws the item as without one.
            loadImage(url, image) {
                if (this.item?.id == item.id) {
                    removeAllViews()
                    addView(
                        build(item.copy(imageUrl = null)),
                        FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT),
                    )
                }
            }
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = dp(20f)
            setPadding(padding, padding, padding, padding)
        }
        addText(item, column, colors, hero = true, bodyLines = 3)
        addFilledButtons(item, column, colors, topDp = 14f)
        hero.addView(
            column,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
        )
        return hero
    }

    private fun tap(button: InAppButton, item: InAppMessage) {
        if (button.action == InAppButton.Action.DISMISS) {
            SequenzyPlacements.dismiss(item)
        } else {
            Sequenzy.handleButtonClick(context, button, item)
        }
    }
}

/** Fills its parent without sizing it: a hero's height comes from its text, never from the picture. */
private class BackdropImageView(context: Context) : ImageView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        fun size(spec: Int) =
            if (View.MeasureSpec.getMode(spec) == View.MeasureSpec.EXACTLY) View.MeasureSpec.getSize(spec) else 0
        setMeasuredDimension(size(widthMeasureSpec), size(heightMeasureSpec))
    }
}

/** The background-image gradient (mobile-sdks/DESIGN.md "Images"). */
private class ScrimView(context: Context) : View(context) {
    private val paint = Paint()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        fun size(spec: Int) =
            if (View.MeasureSpec.getMode(spec) == View.MeasureSpec.EXACTLY) View.MeasureSpec.getSize(spec) else 0
        setMeasuredDimension(size(widthMeasureSpec), size(heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        paint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(0x00000000, 0x8C000000.toInt(), 0xD1000000.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPaint(paint)
    }
}
