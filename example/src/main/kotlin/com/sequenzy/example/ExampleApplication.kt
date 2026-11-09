package com.sequenzy.example

import android.app.Application
import com.sequenzy.android.LiveUpdateContent
import com.sequenzy.android.Sequenzy
import com.sequenzy.android.SequenzyLiveUpdates
import com.sequenzy.core.IdentityTokenProvider
import com.sequenzy.core.LogLevel
import com.sequenzy.core.UserIdentityTokenProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ExampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // With -PsequenzyTokenUrl=..., tokens come from your backend: it gets
        // {"email"} or {"externalId", "email"?} and returns {"identityToken"},
        // minted with POST /api/v1/web-tracking-identities. Without it, paste
        // a token on the main screen.
        val tokenUrl = BuildConfig.SEQUENZY_TOKEN_URL.ifBlank { null }
        Sequenzy.configure(
            application = this,
            publicKey = BuildConfig.SEQUENZY_PUBLIC_KEY,
            companyId = BuildConfig.SEQUENZY_COMPANY_ID,
            apiBaseUrl = BuildConfig.SEQUENZY_API_BASE_URL,
            logLevel = LogLevel.DEBUG,
            identityTokenProvider = tokenUrl?.let { url ->
                IdentityTokenProvider { email -> fetchToken(url, JSONObject().put("email", email)) }
            },
            userIdentityTokenProvider = tokenUrl?.let { url ->
                UserIdentityTokenProvider { externalId, email ->
                    fetchToken(url, JSONObject().put("externalId", externalId).apply { email?.let { put("email", it) } })
                }
            },
        )
        // Same type name as the iOS DeliveryAttributes, so one backend call
        // starts, updates and ends the activity on both platforms.
        SequenzyLiveUpdates.observe("DeliveryAttributes") { _, activity ->
            val minutes = (activity.contentState["minutesAway"] as? Number)?.toInt()
            LiveUpdateContent(
                title = "Order ${activity.attributes["orderNumber"] ?: ""}".trim(),
                text = activity.contentState["status"] as? String,
                shortCriticalText = minutes?.let { "$it min" },
                progress = (activity.contentState["progress"] as? Number)?.toInt(),
            )
        }
    }

    private suspend fun fetchToken(url: String, body: JSONObject): String = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(text).getString("identityToken")
        } finally {
            connection.disconnect()
        }
    }
}
