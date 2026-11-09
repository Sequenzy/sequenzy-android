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
import com.sequenzy.android.SequenzyLiveUpdates
import com.sequenzy.android.SequenzyPlacementView
import com.sequenzy.core.InAppEventType
import com.sequenzy.core.ProfileUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One screen that exercises the SDK: identify by email or by your own user
 * ID (with a token minted by your backend), track events, update the profile,
 * ask for push permission, show two in-app placements, list content cards,
 * run a Live Update and reset.
 */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var status: TextView
    private lateinit var identity: TextView
    private lateinit var cards: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        val userId = EditText(this).apply {
            hint = "User ID (identifyUser)"
            contentDescription = "userId"
        }
        val email = EditText(this).apply {
            hint = "Email (optional with a user ID)"
            inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            contentDescription = "email"
        }
        val token = EditText(this).apply {
            hint = "Identity token (blank = ask your backend)"
            contentDescription = "identityToken"
        }
        identity = TextView(this)
        status = TextView(this).apply { text = "Ready" }
        cards = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        column.addView(identity)
        column.addView(userId)
        column.addView(email)
        column.addView(token)
        column.addView(button("Sign in with user ID") {
            Sequenzy.identifyUser(
                userId.text.toString(),
                email = email.text.toString().ifBlank { null },
                identityToken = token.text.toString().ifBlank { null },
            )
            status.text = "identifyUser(${userId.text})"
            refreshIdentity()
        })
        column.addView(button("Sign in with email") {
            Sequenzy.identify(email.text.toString(), token.text.toString().ifBlank { null })
            status.text = "identify(${email.text})"
            refreshIdentity()
        })
        column.addView(button("Track workout_completed") {
            Sequenzy.track("workout_completed", mapOf("minutes" to 30, "source" to "example"))
            status.text = "Tracked workout_completed"
        })
        column.addView(button("Set profile (plan = pro)") {
            Sequenzy.setProfile(ProfileUpdate(firstName = "Ava", attributes = mapOf("plan" to "pro")))
            status.text = "Profile sent"
        })
        column.addView(button("Enable push") {
            scope.launch { status.text = "Push permission: ${Sequenzy.requestPushPermission()}" }
        })
        column.addView(button("Load cards") { loadCards() })
        column.addView(button("Start a Live Update") {
            SequenzyLiveUpdates.start(
                activityType = "DeliveryAttributes",
                activityId = "example-order",
                attributes = mapOf("orderNumber" to "1234"),
                contentState = mapOf("status" to "Preparing", "minutesAway" to 30, "progress" to 10),
            )
            status.text = "Live Update started; your backend can update example-order"
        })
        column.addView(button("End the Live Update") {
            SequenzyLiveUpdates.end("example-order", mapOf("status" to "Delivered", "progress" to 100))
            status.text = "Live Update ended"
        })
        column.addView(button("Reset") {
            Sequenzy.reset()
            cards.removeAllViews()
            status.text = "Reset"
            refreshIdentity()
        })
        column.addView(status)
        // In-app placements: inline slots, GONE while there is nothing to show.
        val gap = (12 * resources.displayMetrics.density).toInt()
        listOf("home_hero", "settings_banner").forEach { key ->
            column.addView(
                SequenzyPlacementView(this, key),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = gap },
            )
        }
        column.addView(cards)
        setContentView(ScrollView(this).apply { addView(column) })
        refreshIdentity()
    }

    /** Identify runs in the background; show the result once it has. */
    private fun refreshIdentity() {
        scope.launch {
            repeat(3) {
                identity.text = Sequenzy.identifiedContact()?.let { "Signed in: $it" } ?: "Anonymous"
                delay(700)
            }
        }
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
