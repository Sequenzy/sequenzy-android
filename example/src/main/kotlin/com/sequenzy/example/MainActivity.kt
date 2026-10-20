package com.sequenzy.example

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.sequenzy.android.Sequenzy
import com.sequenzy.core.InAppEventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * One screen that exercises the SDK: identify with a token minted by your
 * backend, track an event, list content cards and reset.
 */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var status: TextView
    private lateinit var cards: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        val email = EditText(this).apply {
            hint = "Email"
            inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            contentDescription = "email"
        }
        val token = EditText(this).apply {
            hint = "Identity token (seq_wid_...)"
            contentDescription = "identityToken"
        }
        status = TextView(this).apply { text = "Not identified" }
        cards = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        column.addView(email)
        column.addView(token)
        column.addView(button("Identify") {
            Sequenzy.identify(email.text.toString(), token.text.toString().ifBlank { null })
            status.text = "Identified as ${email.text}"
        })
        column.addView(button("Track workout_completed") {
            Sequenzy.track("workout_completed", mapOf("minutes" to 30, "source" to "example"))
            status.text = "Tracked workout_completed"
        })
        column.addView(button("Load cards") { loadCards() })
        column.addView(button("Reset") {
            Sequenzy.reset()
            cards.removeAllViews()
            status.text = "Not identified"
        })
        column.addView(status)
        column.addView(cards)
        setContentView(ScrollView(this).apply { addView(column) })
    }

    private fun loadCards() {
        scope.launch {
            val result = runCatching { Sequenzy.fetchCards() }
            cards.removeAllViews()
            result.onSuccess { list ->
                status.text = "${list.size} cards"
                list.forEach { card ->
                    cards.addView(TextView(this@MainActivity).apply {
                        text = "${if (card.pinned) "📌 " else ""}${card.title}\n${card.body}"
                        setPadding(0, 24, 0, 24)
                        setOnClickListener {
                            card.buttons.firstOrNull()?.let { Sequenzy.handleButtonClick(this@MainActivity, it, card) }
                        }
                    })
                    Sequenzy.report(InAppEventType.READ, card)
                }
            }.onFailure { status.text = "Could not load cards: ${it.message}" }
        }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener { onClick() }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
