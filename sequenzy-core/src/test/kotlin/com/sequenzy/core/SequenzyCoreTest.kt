package com.sequenzy.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private object Contract {
    val dir = File(System.getProperty("sequenzy.contract"))
    fun text(name: String) = File(dir, name).readText()
    val requests: JsonObject = Json.parseToJsonElement(text("requests.json")).jsonObject
    fun request(name: String): JsonObject =
        requests["requests"]!!.jsonArray.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == name }
    val config = requests["config"]!!.jsonObject
    val token = request("registerAnonymousDevice")["body"]!!.jsonObject["token"]!!.jsonPrimitive.content
}

private class FakeTransport : SequenzyTransport {
    val requests = CopyOnWriteArrayList<HttpRequest>()
    private val responders = CopyOnWriteArrayList<(HttpRequest) -> HttpResponse?>()

    fun respond(responder: (HttpRequest) -> HttpResponse?) = responders.add(0, responder)

    fun respond(path: String, status: Int, body: String, times: Int = Int.MAX_VALUE) {
        var remaining = times
        respond { request ->
            if (!request.url.endsWith(path) || remaining <= 0) return@respond null
            remaining -= 1
            HttpResponse(status, body)
        }
    }

    override suspend fun send(request: HttpRequest): HttpResponse {
        requests.add(request)
        responders.forEach { responder -> responder(request)?.let { return it } }
        return when {
            request.url.endsWith("/api/v1/app-events") -> HttpResponse(202, """{"success":true,"accepted":1,"rejected":[]}""")
            request.url.endsWith("/api/v1/push/app-devices") -> HttpResponse(201, """{"success":true,"deviceId":"dev_1","identified":true,"pushConfigured":true}""")
            else -> HttpResponse(200, """{"success":true}""")
        }
    }

    fun to(path: String) = requests.filter { it.url.endsWith(path) }
}

private fun HttpRequest.json(): JsonObject = Json.parseToJsonElement(body).jsonObject

class SequenzyCoreTest {
    private lateinit var transport: FakeTransport
    private lateinit var store: InMemoryStore
    private lateinit var scope: CoroutineScope
    private var clock = 1_792_000_000_000L
    private val email = "jane@example.com"
    private val identityToken = "seq_wid_contract.token"

    @BeforeTest
    fun setUp() {
        transport = FakeTransport()
        store = InMemoryStore()
        // Retries wait at least a second of real time, so they never fire
        // during a test; the scope is cancelled afterwards.
        scope = CoroutineScope(Dispatchers.Default + Job())
        clock = 1_792_000_000_000L
    }

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun client(provider: IdentityTokenProvider? = null) = SequenzyCore(
        configuration = SequenzyConfiguration(
            publicKey = Contract.config["publicKey"]!!.jsonPrimitive.content,
            companyId = Contract.config["companyId"]!!.jsonPrimitive.content,
            appId = Contract.config["appId"]!!.jsonPrimitive.content,
            apiBaseUrl = Contract.config["apiBaseUrl"]!!.jsonPrimitive.content,
            platform = "ios",
        ),
        transport = transport,
        store = store,
        device = DeviceInfo(appVersion = "1.0", osVersion = "14", deviceName = "Pixel", locale = "en-US", timezone = "UTC"),
        identityTokenProvider = provider,
        logLevel = LogLevel.NONE,
        scope = scope,
        now = { clock },
    )

    private fun assertMatchesContract(request: HttpRequest?, name: String) {
        assertNotNull(request, "missing request $name")
        val contract = Contract.request(name)
        val path = contract["path"]?.jsonPrimitive?.content
        if (path != null) {
            assertEquals("https://api.example.com$path", request.url)
            Contract.requests["headers"]!!.jsonObject.forEach { (header, value) ->
                assertEquals(value.jsonPrimitive.content, request.headers[header], "header $header")
            }
        } else {
            assertEquals(contract["url"]!!.jsonPrimitive.content, request.url)
        }
        assertEquals(contract["method"]!!.jsonPrimitive.content, request.method)
        val body = request.json()
        val expected = contract["body"]!!.jsonObject
        expected.filterKeys { it != "events" }.forEach { (key, value) -> assertEquals(value, body[key], "body.$key") }
        contract["absentBodyFields"]!!.jsonArray.forEach { assertNull(body[it.jsonPrimitive.content], "${it.jsonPrimitive.content} should be absent") }
        val dynamic = contract["dynamicBodyFields"]!!.jsonArray.map { it.jsonPrimitive.content }
        dynamic.filter { !it.contains("[]") }.forEach { assertTrue(body[it]?.jsonPrimitive?.content?.isNotEmpty() == true, "$it should be set") }
        val allowed = expected.keys + dynamic + contract["optionalBodyFields"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(emptySet(), body.keys - allowed, "unexpected body fields")
    }

    @Test
    fun `device registration matches the contract before and after identify`() = runBlocking {
        val client = client()
        client.setPushToken(Contract.token)
        assertMatchesContract(transport.requests.last(), "registerAnonymousDevice")
        val anonymousId = transport.requests.last().json()["anonymousId"]

        client.identify("Jane@Example.com ", identityToken)
        val identified = transport.to("/api/v1/push/app-devices").last()
        assertMatchesContract(identified, "registerIdentifiedDevice")
        assertEquals(anonymousId, identified.json()["anonymousId"])
        assertEquals(JsonPrimitive("1.0"), identified.json()["appVersion"])
    }

    @Test
    fun `track matches the contract`() = runBlocking {
        val client = client()
        client.identify(email, identityToken)
        client.track("workout_completed", mapOf("minutes" to 30))

        val request = transport.to("/api/v1/app-events").last()
        assertMatchesContract(request, "trackEvents")
        val event = request.json()["events"]!!.jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("workout_completed"), event["name"])
        assertEquals(Json.parseToJsonElement("""{"minutes":30}"""), event["properties"])
        assertTrue(event["eventId"]!!.jsonPrimitive.content.isNotEmpty())
        assertEquals("2026-10-14T17:46:40.000Z", event["occurredAt"]!!.jsonPrimitive.content)
        assertEquals(0, client.queuedEventCount())
    }

    @Test
    fun `feed and in-app events match the contract`() = runBlocking {
        transport.respond("/api/v1/in-app/feed", 200, Contract.text("feed-response.json"))
        val client = client()
        client.identify(email, identityToken)

        val messages = client.fetchFeed(InAppMessage.Kind.MESSAGE)
        assertMatchesContract(transport.to("/api/v1/in-app/feed").last(), "fetchFeed")
        assertEquals(listOf("iam_modal", "iam_banner", "iam_card"), messages.map { it.id })
        assertEquals(InAppMessage.Format.MODAL, messages[0].resolvedFormat)
        assertEquals(InAppButton("Upgrade", InAppButton.Action.OPEN_URL, "myapp://billing"), messages[0].buttons[0])
        assertEquals(InAppButton.Action.DISMISS, messages[0].buttons[1].action)
        assertNull(messages[0].buttons[1].url)
        assertEquals(listOf("Inter", "Arial"), messages[0].style.fontFamilies)
        assertEquals(16.0, messages[0].style.cornerRadius)
        assertEquals(mapOf("campaign" to "trial"), messages[0].data)
        assertEquals(InAppMessage.Position.TOP, messages[1].resolvedPosition)
        assertEquals(InAppMessage.Kind.CARD, messages[2].kind)
        assertTrue(messages[2].pinned)

        assertTrue(client.report(InAppEventType.DISPLAYED, "iam_modal"))
        assertMatchesContract(transport.requests.last(), "reportInAppEvent")
    }

    @Test
    fun `reset unregisters the device and forgets the user`() = runBlocking {
        val client = client()
        client.setPushToken(Contract.token)
        client.identify(email, identityToken)
        val before = client.anonymousId

        client.reset()

        assertMatchesContract(transport.requests.last(), "removeDevice")
        assertNotEquals(before, client.anonymousId)
        assertNull(client.email)
        assertNull(store.getString("identityToken"))
    }

    @Test
    fun `push payloads parse and clicks are reported to eventsUrl`() = runBlocking {
        val fcm = Json.parseToJsonElement(Contract.text("push-payload-fcm-data.json")).jsonObject
            .mapValues { it.value.jsonPrimitive.content }
        val payload = assertNotNull(PushPayload.fromData(fcm))
        assertEquals("myapp://orders/123", payload.url)
        assertEquals("https://cdn.example.com/box.png", payload.imageUrl)
        assertNull(PushPayload.fromData(mapOf("title" to "Other sender")))
        assertNull(PushPayload.fromData(mapOf("pushSendId" to "p", "deviceId" to "d", "clickToken" to "t", "eventsUrl" to "javascript:x")))

        client().reportPush(PushEventType.CLICKED, payload)
        assertMatchesContract(transport.requests.last(), "reportPushClicked")
        assertNull(transport.requests.last().headers["x-sequenzy-key"])
    }

    @Test
    fun `events tracked before identify are sent afterwards`() = runBlocking {
        val client = client()
        client.track("viewed_onboarding")
        assertTrue(transport.to("/api/v1/app-events").isEmpty())
        assertEquals(1, client.queuedEventCount())

        client.identify(email, identityToken)
        val sent = transport.to("/api/v1/app-events").single()
        assertEquals("viewed_onboarding", sent.json()["events"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `queue survives a restart, is capped and batches by twenty`() = runBlocking {
        val first = client()
        repeat(105) { first.track("event_$it") }
        assertEquals(100, first.queuedEventCount())

        val second = client()
        assertEquals(100, second.queuedEventCount())
        second.identify(email, identityToken)
        assertEquals(listOf(20, 20, 20, 20, 20), transport.to("/api/v1/app-events").map { it.json()["events"]!!.jsonArray.size })
    }

    @Test
    fun `retryable failures keep the batch and back off`() = runBlocking {
        transport.respond("/api/v1/app-events", 503, "{}", times = 1)
        val client = client()
        client.identify(email, identityToken)
        client.track("a")
        assertEquals(1, client.queuedEventCount())

        client.flush()
        assertEquals(1, transport.to("/api/v1/app-events").size)

        clock += 2_000
        client.flush()
        assertEquals(0, client.queuedEventCount())
        val ids = transport.to("/api/v1/app-events").map { it.json()["events"]!!.jsonArray[0].jsonObject["eventId"] }
        assertEquals(1, ids.toSet().size)
    }

    @Test
    fun `network errors are retryable`() = runBlocking {
        var failures = 1
        transport.respond { request ->
            if (request.url.endsWith("/api/v1/app-events") && failures-- > 0) throw IOException("offline")
            null
        }
        val client = client()
        client.identify(email, identityToken)
        client.track("a")
        assertEquals(1, client.queuedEventCount())
        clock += 2_000
        client.flush()
        assertEquals(0, client.queuedEventCount())
    }

    @Test
    fun `permanent failures and partial rejections drop the batch`() = runBlocking {
        transport.respond("/api/v1/app-events", 400, """{"error":"bad"}""", times = 1)
        val client = client()
        client.identify(email, identityToken)
        client.track("a")
        assertEquals(0, client.queuedEventCount())

        transport.respond("/api/v1/app-events", 202, """{"success":true,"accepted":0,"rejected":[{"index":0,"eventId":"x","error":"built-in"}]}""", times = 1)
        client.track("saas.purchase")
        assertEquals(0, client.queuedEventCount())
    }

    @Test
    fun `stale events are dropped and switching users clears the queue`() = runBlocking {
        val client = client()
        client.track("old")
        clock += SequenzyCore.MAX_EVENT_AGE_MS + 1
        client.identify(email, identityToken)
        assertTrue(transport.to("/api/v1/app-events").isEmpty())

        transport.respond("/api/v1/app-events", 503, "{}")
        client.track("jane_only")
        client.identify("bob@example.com", "seq_wid_bob")
        assertEquals(0, client.queuedEventCount())
    }

    @Test
    fun `a rejected token is refreshed once from the provider`() = runBlocking {
        var calls = 0
        transport.respond { request ->
            if (!request.url.endsWith("/api/v1/app-events")) return@respond null
            if (request.json()["identityToken"]?.jsonPrimitive?.content == "seq_wid_fresh") return@respond null
            HttpResponse(403, """{"success":false,"code":"invalid_web_tracking_identity"}""")
        }
        val client = client { requested ->
            calls += 1
            assertEquals(email, requested)
            "seq_wid_fresh"
        }
        client.identify(email, "seq_wid_expired")
        client.track("a")
        assertEquals(1, calls)
        assertEquals(0, client.queuedEventCount())
        assertEquals("seq_wid_fresh", store.getString("identityToken"))
    }

    @Test
    fun `the provider supplies a token when identify has none`() = runBlocking {
        val client = client { "seq_wid_from_backend" }
        client.identify(email)
        client.track("a")
        assertEquals(JsonPrimitive("seq_wid_from_backend"), transport.to("/api/v1/app-events").last().json()["identityToken"])
    }

    @Test
    fun `without a provider a rejected token holds events until the next identify`() = runBlocking {
        transport.respond("/api/v1/app-events", 403, """{"code":"invalid_web_tracking_identity"}""", times = 1)
        val client = client()
        client.identify(email, "seq_wid_expired")
        client.track("a")
        client.track("b")
        assertEquals(1, transport.to("/api/v1/app-events").size)
        assertEquals(2, client.queuedEventCount())
        client.identify(email, identityToken)
        assertEquals(0, client.queuedEventCount())
    }

    @Test
    fun `custom HTML bridge messages match the shared contract`() {
        val fixture = Json.parseToJsonElement(Contract.text("in-app-html-bridge.json")).jsonObject
        fun describe(message: InAppHtmlBridgeMessage): JsonElement = when (message) {
            is InAppHtmlBridgeMessage.Close -> Json.parseToJsonElement("""{"method":"close"}""")
            is InAppHtmlBridgeMessage.OpenUrl -> JsonObject(
                buildMap {
                    put("method", JsonPrimitive("openUrl"))
                    put("url", JsonPrimitive(message.url))
                    put("external", JsonPrimitive(message.external))
                    message.buttonId?.let { put("buttonId", JsonPrimitive(it)) }
                },
            )
            is InAppHtmlBridgeMessage.LogClick -> JsonObject(
                buildMap {
                    put("method", JsonPrimitive("logClick"))
                    message.buttonId?.let { put("buttonId", JsonPrimitive(it)) }
                },
            )
            is InAppHtmlBridgeMessage.Track -> JsonObject(
                mapOf("method" to JsonPrimitive("track"), "name" to JsonPrimitive(message.name), "properties" to bridgeJson(message.properties)),
            )
            is InAppHtmlBridgeMessage.SetAttributes -> JsonObject(
                mapOf("method" to JsonPrimitive("setAttributes"), "attributes" to bridgeJson(message.attributes)),
            )
            is InAppHtmlBridgeMessage.SetName -> JsonObject(
                buildMap {
                    put("method", JsonPrimitive("setName"))
                    message.firstName?.let { put("firstName", JsonPrimitive(it)) }
                    message.lastName?.let { put("lastName", JsonPrimitive(it)) }
                },
            )
        }
        for (entry in fixture["valid"]!!.jsonArray) {
            val parsed = assertNotNull(InAppHtmlBridgeMessage.parse(entry.jsonObject["input"].toString()), entry.toString())
            assertEquals(normalize(entry.jsonObject["expected"]!!), normalize(describe(parsed)), entry.toString())
        }
        for (input in fixture["invalid"]!!.jsonArray) {
            assertNull(InAppHtmlBridgeMessage.parse(input.toString()), input.toString())
        }
        assertNull(InAppHtmlBridgeMessage.parse("""{"__sequenzyInApp":1,"method":"track","args":{"name":"${"x".repeat(40_000)}"}}"""))
        assertEquals(fixture["maxMessageBytes"]!!.jsonPrimitive.content.toInt(), InAppHtmlBridgeMessage.MAX_MESSAGE_BYTES)
        assertEquals(fixture["maxCallsPerDisplay"]!!.jsonPrimitive.content.toInt(), InAppHtmlBridgeMessage.MAX_CALLS_PER_DISPLAY)
        val urls = fixture["urls"]!!.jsonObject
        urls["allowed"]!!.jsonArray.forEach { assertTrue(InAppHtmlBridgeMessage.isAllowedUrl(it.jsonPrimitive.content), it.toString()) }
        urls["blocked"]!!.jsonArray.forEach { assertTrue(!InAppHtmlBridgeMessage.isAllowedUrl(it.jsonPrimitive.content), it.toString()) }
    }

    @Test
    fun `custom HTML messages are fetched only when asked for and clicks carry button ids`() = runBlocking<Unit> {
        transport.respond("/api/v1/in-app/feed", 200, Contract.text("feed-response-html.json"))
        val client = client()
        client.identify(email, identityToken)
        val messages = client.fetchFeed(
            InAppMessage.Kind.MESSAGE,
            null,
            listOf(InAppMessage.Format.MODAL, InAppMessage.Format.BANNER, InAppMessage.Format.FULLSCREEN, InAppMessage.Format.HTML),
        )
        assertMatchesContract(transport.to("/api/v1/in-app/feed").last(), "fetchFeedWithHtml")
        val message = messages.single()
        assertEquals(InAppMessage.Format.HTML, message.format)
        assertTrue(message.showCloseButton)
        assertTrue(message.html!!.contains("brazeBridge.logClick"))

        assertTrue(client.report(InAppEventType.CLICKED, "iam_html", "0"))
        assertMatchesContract(transport.requests.last(), "reportInAppHtmlClick")
        client.report(InAppEventType.CLICKED, "iam_html", "<b>")
        assertNull(transport.requests.last().json()["buttonId"])

        // Native items from older servers have no html fields.
        val native = InAppMessage.listFromFeed(Json.parseToJsonElement(Contract.text("feed-response.json")))
        assertNull(native.first().html)
        assertTrue(!native.first().showCloseButton)
    }

    @Test
    fun `fetching the feed requires identity`() = runBlocking<Unit> {
        assertFailsWith<SequenzyException.NotIdentified> { client().fetchFeed() }
    }

    @Test
    fun `push registration is throttled until the token or user changes`() = runBlocking {
        val client = client()
        client.setPushToken(Contract.token)
        client.setPushToken(Contract.token)
        client.registerPushIfNeeded()
        assertEquals(1, transport.to("/api/v1/push/app-devices").size)

        clock += SequenzyCore.PUSH_REFRESH_INTERVAL_MS + 1
        client.registerPushIfNeeded()
        assertEquals(2, transport.to("/api/v1/push/app-devices").size)

        client.setPushToken("b" + Contract.token.drop(1))
        assertEquals(3, transport.to("/api/v1/push/app-devices").size)
    }

    @Test
    fun `a rejected identity still registers the device anonymously`() = runBlocking {
        transport.respond("/api/v1/push/app-devices", 403, """{"code":"invalid_web_tracking_identity"}""", times = 1)
        val client = client()
        client.identify(email, "seq_wid_expired")
        client.setPushToken(Contract.token)
        val registrations = transport.to("/api/v1/push/app-devices")
        assertEquals(2, registrations.size)
        assertNull(registrations[1].json()["email"])
    }

    @Test
    fun `oversized and non-finite properties never wedge the queue`() = runBlocking {
        val client = client()
        client.identify(email, identityToken)
        client.track("huge", mapOf("blob" to "x".repeat(9_000)))
        assertEquals(0, client.queuedEventCount())
        assertTrue(transport.to("/api/v1/app-events").isEmpty())

        client.track("nan", mapOf("value" to Double.NaN))
        val sent = transport.to("/api/v1/app-events").single().json()["events"]!!.jsonArray[0].jsonObject
        assertEquals(Json.parseToJsonElement("""{"value":null}"""), sent["properties"])
    }

    @Test
    fun `a failing provider is not asked again for a minute`() = runBlocking {
        var calls = 0
        val client = client { calls += 1; throw IOException("backend down") }
        client.identify(email)
        client.track("a")
        client.track("b")
        assertEquals(1, calls)
        assertEquals(2, client.queuedEventCount())
        clock += SequenzyCore.PROVIDER_RETRY_MS + 1
        client.flush()
        assertEquals(2, calls)
    }

    @Test
    fun `properties convert to JSON`() {
        val element: JsonElement = toJsonElement(
            mapOf("s" to "x", "i" to 3, "d" to 1.5, "b" to true, "n" to null, "l" to listOf(1, "a"), "m" to mapOf("k" to 1L)),
        )
        assertEquals(Json.parseToJsonElement("""{"s":"x","i":3,"d":1.5,"b":true,"n":null,"l":[1,"a"],"m":{"k":1}}"""), element)
        assertEquals(JsonArray(listOf(JsonPrimitive(1L))), toJsonElement(arrayOf(1)))
    }

    @Test
    fun `hex colors parse`() {
        assertEquals(0xFF4F46E5.toInt(), parseHexColor("#4f46e5"))
        assertNull(parseHexColor("blue"))
        assertNull(parseHexColor("#12345"))
    }

    @Test
    fun `screens and commerce events are typed queue entries`() = runBlocking {
        val client = client()
        client.identify(email, identityToken)
        client.screen(" Pricing ", mapOf("plan" to "pro"))
        client.viewedProduct(CommerceProduct(providerProductId = "sku-1", title = "Shoe"))
        client.addedToCart(CommerceCartLine(CommerceProduct(providerProductId = "sku-1"), quantity = 2))
        client.track("custom_thing")
        val events = transport.to("/api/v1/app-events").map { it.json()["events"]!!.jsonArray.single().jsonObject }
        assertEquals(JsonPrimitive("screen"), events[0]["type"])
        assertEquals(JsonPrimitive("Pricing"), events[0]["properties"]!!.jsonObject["screen"])
        assertEquals(JsonPrimitive("commerce"), events[1]["type"])
        assertEquals(JsonPrimitive("product_viewed"), events[1]["name"])
        assertEquals(
            JsonPrimitive("Shoe"),
            events[1]["properties"]!!.jsonObject["product"]!!.jsonObject["title"],
        )
        assertEquals(
            JsonPrimitive(2),
            events[2]["properties"]!!.jsonObject["cartLine"]!!.jsonObject["quantity"],
        )
        assertNull(events[3]["type"])
        client.screen("  ")
        assertEquals(0, client.queuedEventCount())
    }

    @Test
    fun `profile updates merge, wait for identify and survive a failure`() = runBlocking {
        val client = client()
        client.setProfile(ProfileUpdate(firstName = "Jane", attributes = mapOf("goal" to "run")))
        client.setProfile(ProfileUpdate(attributes = mapOf("level" to 3), clearLastName = true))
        assertTrue(transport.to("/api/v1/app-attributes").isEmpty())
        transport.respond("/api/v1/app-attributes", 503, "{}", times = 1)
        client.identify(email, identityToken)
        assertEquals(1, transport.to("/api/v1/app-attributes").size)
        clock += 2_000
        client(null).flush()
        val sent = transport.to("/api/v1/app-attributes")
        assertEquals(2, sent.size)
        val body = sent[1].json()
        assertEquals(JsonPrimitive("Jane"), body["firstName"])
        assertEquals(JsonNull, body["lastName"])
        assertEquals(JsonPrimitive("run"), body["attributes"]!!.jsonObject["goal"])
        assertEquals(JsonPrimitive(3), body["attributes"]!!.jsonObject["level"])
        client.flush()
        assertEquals(2, transport.to("/api/v1/app-attributes").size)
    }

    @Test
    fun `reset forgets a pending profile`() = runBlocking {
        val client = client()
        client.setProfile(ProfileUpdate(firstName = "Old"))
        client.reset()
        client.identify(email, identityToken)
        assertTrue(transport.to("/api/v1/app-attributes").isEmpty())
    }

    @Test
    fun `subscribe posts to a form without identifying`() = runBlocking {
        val client = client()
        val ok = client.subscribe(SubscribeRequest(formId = "form_1", email = " new@example.com ", firstName = "Nia", phone = "+14155550123"))
        assertEquals(SubscribeResult.Success, ok)
        val body = transport.to("/api/v1/app-subscribe").single().json()
        assertEquals(JsonPrimitive("form_1"), body["formId"])
        assertEquals(JsonPrimitive("new@example.com"), body["email"])
        assertEquals(JsonPrimitive("+14155550123"), body["phone"])
        assertNull(client.email)
        transport.respond("/api/v1/app-subscribe", 429, """{"error":"Too many"}""", times = 1)
        assertEquals(SubscribeResult.Failure("Too many", retryable = true), client.subscribe(SubscribeRequest("form_1", "a@b.co")))
        val invalid = client.subscribe(SubscribeRequest("form_1", "nope"))
        assertTrue(invalid is SubscribeResult.Failure && !invalid.retryable)
    }

    @Test
    fun `feed items decode eyebrow, image placement and center position`() {
        val messages = InAppMessage.listFromFeed(Json.parseToJsonElement(Contract.text("feed-response.json")))
        val modal = messages.first { it.id == "iam_modal" }
        assertEquals("3 days left", modal.eyebrow)
        assertEquals(InAppMessage.ImagePlacement.BACKGROUND, modal.imagePlacement)
        assertEquals(InAppMessage.Position.BOTTOM, modal.position)
        // Older servers send neither field.
        assertEquals("", messages.first { it.id == "iam_card" }.eyebrow)
        val centered = InAppMessage.fromJson(
            Json.parseToJsonElement("""{"id":"m","kind":"message","format":"modal","position":"center"}"""),
        )
        assertEquals(InAppMessage.Position.CENTER, centered?.position)
    }
}

private fun bridgeJson(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { (key, item) -> key.toString() to bridgeJson(item) })
    is List<*> -> JsonArray(value.map(::bridgeJson))
    else -> JsonPrimitive(value.toString())
}

/** Numbers compare by value (9 and 9.0 are the same JSON number). */
private fun normalize(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.mapValues { normalize(it.value) })
    is JsonArray -> JsonArray(element.map(::normalize))
    is JsonPrimitive -> if (!element.isString && element.content.toDoubleOrNull() != null) {
        JsonPrimitive(element.content.toDouble())
    } else {
        element
    }
    else -> element
}

