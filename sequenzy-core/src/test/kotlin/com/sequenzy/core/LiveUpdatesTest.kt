package com.sequenzy.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private object LiveUpdateContract {
    private val dir = File(System.getProperty("sequenzy.contract"))
    val fixture: JsonObject = Json.parseToJsonElement(File(dir, "live-update-fcm-data.json").readText()).jsonObject
    private val requests = Json.parseToJsonElement(File(dir, "requests.json").readText()).jsonObject
    val config = requests["config"]!!.jsonObject

    fun request(name: String): JsonObject =
        requests["requests"]!!.jsonArray.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == name }

    fun message(name: String): JsonObject =
        fixture["messages"]!!.jsonArray.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == name }

    fun data(entry: JsonObject): Map<String, String> =
        entry["data"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
}

private class RecordingTransport : SequenzyTransport {
    val requests = CopyOnWriteArrayList<HttpRequest>()

    override suspend fun send(request: HttpRequest): HttpResponse {
        requests.add(request)
        return when {
            request.url.endsWith("/api/v1/push/app-devices") ->
                HttpResponse(201, """{"success":true,"deviceId":"dev_1","identified":true,"pushConfigured":true}""")
            else -> HttpResponse(200, """{"success":true}""")
        }
    }

    fun to(path: String) = requests.filter { it.url.endsWith(path) }.map { Json.parseToJsonElement(it.body).jsonObject }
}

class LiveUpdatesTest {
    private lateinit var transport: RecordingTransport
    private lateinit var store: InMemoryStore
    private lateinit var scope: CoroutineScope
    private var clock = 1_792_000_050_000L
    private val email = "jane@example.com"
    private val identityToken = "seq_wid_contract.token"
    private val fcmToken = "fcm_contract_token"
    private val registerPath = "/api/v1/push/live-activities"
    private val removePath = "/api/v1/push/live-activities/remove"

    @BeforeTest
    fun setUp() {
        transport = RecordingTransport()
        store = InMemoryStore()
        scope = CoroutineScope(Dispatchers.Default + Job())
        clock = 1_792_000_050_000L
    }

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun client() = SequenzyCore(
        configuration = SequenzyConfiguration(
            publicKey = LiveUpdateContract.config["publicKey"]!!.jsonPrimitive.content,
            companyId = LiveUpdateContract.config["companyId"]!!.jsonPrimitive.content,
            appId = LiveUpdateContract.config["appId"]!!.jsonPrimitive.content,
            apiBaseUrl = LiveUpdateContract.config["apiBaseUrl"]!!.jsonPrimitive.content,
        ),
        transport = transport,
        store = store,
        device = DeviceInfo(appVersion = "1.0", osVersion = "16"),
        logLevel = LogLevel.NONE,
        scope = scope,
        now = { clock },
    )

    private fun payload(name: String) =
        assertNotNull(LiveUpdatePayload.fromData(LiveUpdateContract.data(LiveUpdateContract.message(name))))

    private fun assertMatchesContract(body: JsonObject?, name: String) {
        assertNotNull(body, "missing request $name")
        val contract = LiveUpdateContract.request(name)
        val expected = contract["body"]!!.jsonObject
        for ((key, value) in expected) assertEquals(value, body[key], "body field $key")
        contract["absentBodyFields"]!!.jsonArray.forEach { assertNull(body[it.jsonPrimitive.content], "${it.jsonPrimitive.content} should be absent") }
        val dynamic = contract["dynamicBodyFields"]!!.jsonArray.map { it.jsonPrimitive.content }
        dynamic.forEach { assertNotNull(body[it], "$it should be present") }
        val allowed = expected.keys + dynamic + contract["optionalBodyFields"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(emptySet(), body.keys - allowed, "unexpected body fields")
    }

    @Test
    fun `parses the FCM messages the server sends`() {
        for (entry in LiveUpdateContract.fixture["messages"]!!.jsonArray.map { it.jsonObject }) {
            val parsed = assertNotNull(LiveUpdatePayload.fromData(LiveUpdateContract.data(entry)), entry["name"].toString())
            val expected = entry["expected"]!!.jsonObject
            assertEquals(expected["event"]!!.jsonPrimitive.content, parsed.event.wire)
            assertEquals(expected["activityType"]!!.jsonPrimitive.content, parsed.activityType)
            assertEquals(expected["activityId"]!!.jsonPrimitive.content, parsed.activityId)
            assertEquals(expected["timestamp"]!!.jsonPrimitive.longOrNull, parsed.timestamp)
            assertEquals(expected["contentState"]!!.jsonObject, parsed.contentState)
            assertEquals(expected["attributes"]!!.takeIf { it != JsonNull }?.jsonObject, parsed.attributes)
            val alert = expected["alert"]!!.takeIf { it != JsonNull }?.jsonObject
            assertEquals(alert?.get("title")?.takeIf { it != JsonNull }?.jsonPrimitive?.content, parsed.alert?.title)
            assertEquals(alert?.get("body")?.takeIf { it != JsonNull }?.jsonPrimitive?.content, parsed.alert?.body)
            assertEquals((expected["staleDate"] as? JsonPrimitive)?.longOrNull, parsed.staleDate)
            assertEquals((expected["dismissalDate"] as? JsonPrimitive)?.longOrNull, parsed.dismissalDate)
            assertEquals((expected["updateToken"] as? JsonPrimitive)?.takeIf { it.isString }?.content, parsed.updateToken)
        }
        for (entry in LiveUpdateContract.fixture["ignored"]!!.jsonArray.map { it.jsonObject }) {
            assertNull(LiveUpdatePayload.fromData(LiveUpdateContract.data(entry)), entry["name"].toString())
        }
    }

    @Test
    fun `observe waits for the FCM token, then registers a push-to-start token per type`() = runBlocking {
        val core = client()
        assertTrue(core.liveUpdates.observe("DeliveryAttributes"))
        assertEquals(emptyList(), transport.to(registerPath), "nothing to link without an FCM token")

        core.setPushToken(fcmToken)
        val sent = transport.to(registerPath)
        assertEquals(1, sent.size)
        assertMatchesContract(sent.single(), "registerLiveUpdatePushToStart")
        val token = sent.single()["token"]!!.jsonPrimitive.content
        assertTrue(Regex("^[0-9a-f]{64}$").matches(token))

        // Observing again at the next launch reuses the token without a request.
        core.liveUpdates.observe("DeliveryAttributes")
        assertEquals(1, transport.to(registerPath).size)
        assertEquals(listOf(token), core.liveUpdates.tokens.map { it.token })
        assertEquals(false, core.liveUpdates.observe("not a type"))
    }

    @Test
    fun `a received start shows the activity and reports the token the push carried`() = runBlocking {
        val core = client()
        core.setPushToken(fcmToken)
        core.identify(email, identityToken)

        val before = transport.to(registerPath).size
        val decision = core.liveUpdates.receive(payload("start"), canShow = true)
        // The FCM thread posts the notification before any request.
        assertEquals(before, transport.to(registerPath).size)
        core.liveUpdates.sync()

        val show = assertIs<LiveUpdateDecision.Show>(decision)
        assertEquals("order-1234", show.activity.activityId)
        assertEquals(mapOf("orderNumber" to "1234"), show.activity.attributes)
        assertEquals(mapOf("status" to "preparing", "progress" to 10L, "eta" to "12:30"), show.activity.contentState)
        assertEquals(1_792_003_600_000L, show.activity.staleDateMillis)
        assertEquals("Order confirmed", show.alert?.title)
        assertMatchesContract(transport.to(registerPath).last(), "registerLiveUpdateActivity")
        assertEquals(1, core.liveUpdates.active().size)
    }

    @Test
    fun `applies newer pushes only, then ends and ignores later updates`() = runBlocking {
        val core = client()
        core.setPushToken(fcmToken)
        core.liveUpdates.receive(payload("start"), canShow = true)

        // A duplicate delivery of the start carries the same timestamp.
        assertEquals(LiveUpdateDecision.Ignore("stale"), core.liveUpdates.receive(payload("start"), canShow = true))

        val update = assertIs<LiveUpdateDecision.Show>(core.liveUpdates.receive(payload("update"), canShow = true))
        assertEquals("on the way", update.activity.contentState["status"])
        // Attributes from the start are kept for every update.
        assertEquals("1234", update.activity.attributes["orderNumber"])

        val end = assertIs<LiveUpdateDecision.Show>(core.liveUpdates.receive(payload("end"), canShow = true))
        assertTrue(end.activity.isEnded)
        assertEquals(1_792_000_200_000L, end.dismissAtMillis)
        assertEquals(emptyList(), core.liveUpdates.active())
        assertTrue(core.liveUpdates.tokens.none { it.kind == LiveUpdateToken.Kind.UPDATE })

        val late = payload("update").copy(timestamp = 1_792_000_300)
        assertEquals(LiveUpdateDecision.Ignore("ended"), core.liveUpdates.receive(late, canShow = true))
    }

    @Test
    fun `an end without a dismissal date lingers for four hours`() = runBlocking {
        val core = client()
        core.liveUpdates.receive(payload("start"), canShow = true)
        val end = assertIs<LiveUpdateDecision.Show>(
            core.liveUpdates.receive(payload("end").copy(dismissalDate = null), canShow = true),
        )
        assertEquals(clock + LIVE_UPDATE_ENDED_LINGER_MS, end.dismissAtMillis)
    }

    @Test
    fun `ignores updates for activities this device never started`() = runBlocking {
        val core = client()
        assertEquals(LiveUpdateDecision.Ignore("unknown_activity"), core.liveUpdates.receive(payload("update"), canShow = true))
        assertEquals(LiveUpdateDecision.Ignore("unknown_activity"), core.liveUpdates.receive(payload("end"), canShow = true))
    }

    @Test
    fun `a start the app cannot show is reported ended so it is not counted as running`() = runBlocking {
        val core = client()
        core.setPushToken(fcmToken)
        val start = payload("start")

        assertEquals(LiveUpdateDecision.Ignore("not_shown"), core.liveUpdates.receive(start, canShow = false))
        assertEquals(emptyList(), transport.to(removePath))
        core.liveUpdates.sync()

        val removed = transport.to(removePath).single()
        assertEquals(start.updateToken, removed["token"]!!.jsonPrimitive.content)
        assertNull(removed["unlinkSubscriber"])
        assertEquals(emptyList(), core.liveUpdates.active())
    }

    @Test
    fun `a dismissed activity is reported and never shown again`() = runBlocking {
        val core = client()
        core.setPushToken(fcmToken)
        core.liveUpdates.receive(payload("start"), canShow = true)

        core.liveUpdates.dismissed("order-1234")

        assertEquals(payload("start").updateToken, transport.to(removePath).single()["token"]!!.jsonPrimitive.content)
        assertEquals(LiveUpdateDecision.Ignore("dismissed"), core.liveUpdates.receive(payload("update"), canShow = true))
        // Dismissing twice reports once.
        core.liveUpdates.dismissed("order-1234")
        assertEquals(1, transport.to(removePath).size)
    }

    @Test
    fun `activities the app starts itself are registered, updated and ended`() = runBlocking {
        val core = client()
        core.setPushToken(fcmToken)

        val started = assertIs<LiveUpdateDecision.Show>(
            core.liveUpdates.start("DeliveryAttributes", "order-9", mapOf("orderNumber" to "9"), mapOf("status" to "placed")),
        )
        assertEquals("placed", started.activity.contentState["status"])
        val registered = transport.to(registerPath).single()
        assertEquals("update", registered["kind"]!!.jsonPrimitive.content)
        assertEquals("order-9", registered["activityId"]!!.jsonPrimitive.content)

        val updated = assertIs<LiveUpdateDecision.Show>(core.liveUpdates.update("order-9", mapOf("status" to "packed")))
        assertEquals("packed", updated.activity.contentState["status"])

        val ended = assertIs<LiveUpdateDecision.Show>(core.liveUpdates.end("order-9", mapOf("status" to "done"), dismissAtMillis = 5))
        assertTrue(ended.activity.isEnded)
        assertEquals(5, ended.dismissAtMillis)
        assertEquals(registered["token"], transport.to(removePath).single()["token"])
        assertEquals(LiveUpdateDecision.Ignore("unknown_activity"), core.liveUpdates.update("order-9", mapOf("status" to "x")))
        assertEquals(LiveUpdateDecision.Ignore("invalid"), core.liveUpdates.start("Delivery", "bad id", emptyMap(), emptyMap()))
    }

    @Test
    fun `app changes never hide backend pushes, even with a fast device clock`() = runBlocking {
        val core = client()
        // The device clock runs an hour ahead of the server.
        clock = 1_792_003_600_000L
        core.liveUpdates.start("DeliveryAttributes", "order-1234", emptyMap(), mapOf("status" to "placed"))
        core.liveUpdates.update("order-1234", mapOf("status" to "app"))
        val update = assertIs<LiveUpdateDecision.Show>(core.liveUpdates.receive(payload("update"), canShow = true))
        assertEquals("on the way", update.activity.contentState["status"])
    }

    @Test
    fun `an end that arrives before its start keeps the late start from showing`() = runBlocking {
        val core = client()
        assertEquals(LiveUpdateDecision.Ignore("unknown_activity"), core.liveUpdates.receive(payload("end"), canShow = true))
        assertEquals(LiveUpdateDecision.Ignore("stale"), core.liveUpdates.receive(payload("start"), canShow = true))
        assertEquals(emptyList(), core.liveUpdates.active())
    }

    @Test
    fun `only the latest revision is drawn, and nothing after a reset`() = runBlocking {
        val core = client()
        val start = assertIs<LiveUpdateDecision.Show>(core.liveUpdates.receive(payload("start"), canShow = true))
        val update = assertIs<LiveUpdateDecision.Show>(core.liveUpdates.receive(payload("update"), canShow = true))
        assertTrue(update.activity.revision > start.activity.revision)
        assertNull(core.liveUpdates.ifCurrent(start.activity) { "posted" })
        assertEquals("posted", core.liveUpdates.ifCurrent(update.activity) { "posted" })
        core.reset()
        assertNull(core.liveUpdates.ifCurrent(update.activity) { "posted" })
    }

    @Test
    fun `reset also removes ended notifications`() = runBlocking {
        val core = client()
        val removed = CopyOnWriteArrayList<String>()
        core.onLiveUpdatesRemoved = { removed.addAll(it) }
        core.liveUpdates.receive(payload("start"), canShow = true)
        core.liveUpdates.receive(payload("end"), canShow = true)
        core.reset()
        assertEquals(listOf("order-1234"), removed)
    }

    @Test
    fun `reset unlinks every token, keeps push-to-start ones and removes running notifications`() = runBlocking {
        val core = client()
        val removed = CopyOnWriteArrayList<String>()
        core.onLiveUpdatesRemoved = { removed.addAll(it) }
        core.setPushToken(fcmToken)
        core.identify(email, identityToken)
        core.liveUpdates.observe("DeliveryAttributes")
        core.liveUpdates.receive(payload("start"), canShow = true)
        val pushToStart = core.liveUpdates.tokens.first { it.kind == LiveUpdateToken.Kind.PUSH_TO_START }

        core.reset()

        assertEquals(listOf("order-1234"), removed)
        val removals = transport.to(removePath)
        assertEquals(setOf(pushToStart.token, payload("start").updateToken), removals.map { it["token"]!!.jsonPrimitive.content }.toSet())
        assertTrue(removals.all { it["unlinkSubscriber"]?.jsonPrimitive?.content == "true" })
        assertEquals(listOf(pushToStart), core.liveUpdates.tokens)
        assertEquals(emptyList(), core.liveUpdates.active())

        // The next device registration links the push-to-start token again, anonymously.
        transport.requests.clear()
        core.registerPushIfNeeded()
        val again = transport.to(registerPath).single()
        assertEquals(pushToStart.token, again["token"]!!.jsonPrimitive.content)
        assertNull(again["email"])
    }

    @Test
    fun `another person signing in keeps only the device's push-to-start tokens`() = runBlocking {
        val core = client()
        val removed = CopyOnWriteArrayList<String>()
        core.onLiveUpdatesRemoved = { removed.addAll(it) }
        core.setPushToken(fcmToken)
        core.identify(email, identityToken)
        core.liveUpdates.observe("DeliveryAttributes")
        core.liveUpdates.receive(payload("start"), canShow = true)
        transport.requests.clear()

        core.identify("sam@example.com", "seq_wid_sam")

        assertEquals(listOf("order-1234"), removed)
        assertEquals(listOf(LiveUpdateToken.Kind.PUSH_TO_START), core.liveUpdates.tokens.map { it.kind })
        // The previous person's tokens are unlinked on the server too.
        val removals = transport.to(removePath)
        assertEquals(2, removals.size)
        assertTrue(removals.all { it["unlinkSubscriber"]?.jsonPrimitive?.content == "true" })
        val linked = transport.to(registerPath).single()
        assertEquals("push_to_start", linked["kind"]!!.jsonPrimitive.content)
        assertEquals("sam@example.com", linked["email"]!!.jsonPrimitive.content)
    }

    @Test
    fun `stored tokens and activities are capped`() = runBlocking {
        val core = client()
        repeat(LiveUpdates.MAX_ACTIVITIES + 5) { index ->
            core.liveUpdates.start("DeliveryAttributes", "order-$index", emptyMap(), mapOf("n" to index))
        }
        assertEquals(LiveUpdates.MAX_ACTIVITIES, core.liveUpdates.active().size)
        assertEquals(LiveUpdates.MAX_TOKENS, core.liveUpdates.tokens.size)
        assertEquals("order-${LiveUpdates.MAX_ACTIVITIES + 4}", core.liveUpdates.active().last().activityId)
    }

    @Test
    fun `converts JSON to plain Kotlin values`() {
        val json = Json.parseToJsonElement("""{"s":"a","i":3,"d":1.5,"b":true,"n":null,"l":[1,"x"],"o":{"k":"v"}}""").jsonObject
        assertEquals(
            mapOf("s" to "a", "i" to 3L, "d" to 1.5, "b" to true, "n" to null, "l" to listOf(1L, "x"), "o" to mapOf("k" to "v")),
            json.toKotlinMap(),
        )
    }
}
