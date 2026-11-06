package com.sequenzy.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GeofenceTest {
    private val contract: JsonObject = Json.parseToJsonElement(
        File(File(System.getProperty("sequenzy.contract")), "requests.json").readText(),
    ).jsonObject
    private fun request(name: String) =
        contract["requests"]!!.jsonArray.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == name }

    private val requests = CopyOnWriteArrayList<HttpRequest>()
    private lateinit var scope: CoroutineScope
    private var clock = 1_792_000_000_000L

    private val transport = object : SequenzyTransport {
        override suspend fun send(request: HttpRequest): HttpResponse {
            requests.add(request)
            return when {
                request.url.endsWith("/api/v1/geofences/nearby") ->
                    HttpResponse(200, contract["geofences"]!!.jsonObject["nearbyResponse"].toString())
                request.url.endsWith("/api/v1/app-events") ->
                    HttpResponse(202, """{"success":true,"accepted":1,"rejected":[]}""")
                else -> HttpResponse(200, """{"success":true}""")
            }
        }
    }

    @BeforeTest
    fun setUp() {
        requests.clear()
        scope = CoroutineScope(Dispatchers.Default + Job())
        clock = 1_792_000_000_000L
    }

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun client(): SequenzyCore {
        val config = contract["config"]!!.jsonObject
        return SequenzyCore(
            configuration = SequenzyConfiguration(
                publicKey = config["publicKey"]!!.jsonPrimitive.content,
                companyId = config["companyId"]!!.jsonPrimitive.content,
                appId = config["appId"]!!.jsonPrimitive.content,
                apiBaseUrl = config["apiBaseUrl"]!!.jsonPrimitive.content,
                platform = "android",
            ),
            transport = transport,
            store = InMemoryStore(),
            device = DeviceInfo(appVersion = "1.0", osVersion = "14", deviceName = "Pixel", locale = "en-US", timezone = "UTC"),
            identityTokenProvider = null,
            logLevel = LogLevel.NONE,
            scope = scope,
            now = { clock },
        )
    }

    private fun HttpRequest.json(): JsonObject = Json.parseToJsonElement(body).jsonObject

    @Test
    fun fetchNearbyMatchesTheContractAndRoundsThePosition() = runBlocking {
        val sdk = client()
        sdk.identify("jane@example.com", "seq_wid_contract.token")

        val nearby = sdk.fetchNearbyGeofences(51.513678, -0.1365, Geofencing.fetchLimit(20))

        val sent = requests.last { it.url.endsWith("/api/v1/geofences/nearby") }.json()
        val expected = request("fetchNearbyGeofences")["body"]!!.jsonObject
        assertEquals(expected.keys, sent.keys)
        expected.forEach { (key, value) ->
            assertEquals(value.jsonPrimitive.content.toDoubleOrNull() ?: value.jsonPrimitive.content,
                sent[key]!!.jsonPrimitive.content.toDoubleOrNull() ?: sent[key]!!.jsonPrimitive.content, key)
        }
        assertEquals(listOf("geo_1", "geo_2"), nearby.geofences.map { it.id })
        assertEquals(4200.0, nearby.refreshRadiusMeters)
    }

    @Test
    fun fetchNearbyNeedsAnIdentity() = runBlocking<Unit> {
        assertFailsWith<SequenzyException.NotIdentified> { client().fetchNearbyGeofences(1.0, 2.0, 19) }
    }

    @Test
    fun transitionsAreQueuedAsGeofenceEventsAndDebounced() = runBlocking {
        val sdk = client()
        sdk.identify("jane@example.com", "seq_wid_contract.token")

        val results = mutableListOf(
            sdk.trackGeofenceTransition("geo_1", GeofenceTransition.ENTER),
            sdk.trackGeofenceTransition("geo_1", GeofenceTransition.ENTER),
            sdk.trackGeofenceTransition(GeofenceRegion.REFRESH_REGION_ID, GeofenceTransition.EXIT),
        )
        clock += 61_000
        results += sdk.trackGeofenceTransition("geo_1", GeofenceTransition.ENTER)

        assertEquals(listOf(true, false, false, true), results)
        val sends = requests.filter { it.url.endsWith("/api/v1/app-events") }
        assertEquals(2, sends.size)
        val expected = request("trackGeofenceTransition")["body"]!!.jsonObject
        val sent = sends.first().json()
        expected.filterKeys { it != "events" }.forEach { (key, value) -> assertEquals(value, sent[key], key) }
        val event = sent["events"]!!.jsonArray[0].jsonObject
        expected["events"]!!.jsonArray[0].jsonObject.forEach { (key, value) -> assertEquals(value, event[key], key) }
        assertTrue(event["eventId"]!!.jsonPrimitive.content.isNotEmpty())
        assertTrue(event["occurredAt"]!!.jsonPrimitive.content.isNotEmpty())
    }

    @Test
    fun dropsTransitionsWhileNobodyIsIdentified() = runBlocking {
        val sdk = client()
        assertEquals(false, sdk.trackGeofenceTransition("geo_1", GeofenceTransition.ENTER))
        assertEquals(0, sdk.queuedEventCount())
    }

    @Test
    fun anotherContactIsNotDebouncedByThePreviousOne() = runBlocking {
        val sdk = client()
        sdk.identify("jane@example.com", "seq_wid_contract.token")
        val jane = sdk.trackGeofenceTransition("geo_1", GeofenceTransition.ENTER)
        sdk.identify("sam@example.com", "seq_wid_contract.token")
        val sam = sdk.trackGeofenceTransition("geo_1", GeofenceTransition.ENTER)
        assertEquals(listOf(true, true), listOf(jane, sam))
    }

    @Test
    fun planAddsARefreshRegionAroundTheDevice() {
        val nearby = NearbyGeofences((1..25).map { GeofenceRegion("g$it", 0.0, 0.0, 200.0) }, 4200.0)
        val plan = Geofencing.plan(nearby, 51.5136, -0.1365, 50)
        assertEquals(26, plan.size)
        assertEquals(GeofenceRegion("sequenzy.refresh", 51.5136, -0.1365, 4200.0), plan.last())
        val capped = Geofencing.plan(nearby, 0.0, 0.0, 20)
        assertEquals(20, capped.size)
        assertEquals(listOf("g1", "g2"), Geofencing.plan(NearbyGeofences(nearby.geofences.take(2), null), 0.0, 0.0, 20).map { it.id })
    }

    @Test
    fun diffAndRoundTripTouchOnlyChangedRegions() {
        val a = GeofenceRegion("a", 0.0, 0.0, 200.0)
        val b = GeofenceRegion("b", 0.0, 0.0, 200.0)
        val result = Geofencing.diff(listOf(a, b), listOf(b.copy(latitude = 1.0), GeofenceRegion("c", 0.0, 0.0, 200.0), a))
        assertEquals(listOf("b"), result.remove)
        assertEquals(listOf("b", "c"), result.add.map { it.id })
        assertEquals(listOf(a, b), GeofenceRegion.listFromJson(GeofenceRegion.listToJson(listOf(a, b))))
        assertEquals(emptyList(), GeofenceRegion.listFromJson("not json"))
        assertEquals(51.51, Geofencing.roundCoordinate(51.513678))
        assertEquals(-0.14, Geofencing.roundCoordinate(-0.1365))
        assertEquals(19, Geofencing.fetchLimit(20))
        assertEquals(99, Geofencing.fetchLimit(500))
    }
}
