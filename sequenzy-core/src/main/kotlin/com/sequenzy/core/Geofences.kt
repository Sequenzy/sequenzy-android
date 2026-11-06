package com.sequenzy.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.roundToLong

/** A circular region from `POST /api/v1/geofences/nearby`, or the refresh region around the device. */
data class GeofenceRegion(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
) {
    val isRefreshRegion: Boolean get() = id == REFRESH_REGION_ID

    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("latitude", latitude)
        put("longitude", longitude)
        put("radiusMeters", radiusMeters)
    }

    companion object {
        /** Registered around the device to know when to fetch again; never reported. */
        const val REFRESH_REGION_ID = "sequenzy.refresh"

        fun fromJson(element: JsonElement): GeofenceRegion? = runCatching {
            val obj = element.jsonObject
            GeofenceRegion(
                id = obj["id"]!!.jsonPrimitive.content,
                latitude = obj["latitude"]!!.jsonPrimitive.double,
                longitude = obj["longitude"]!!.jsonPrimitive.double,
                radiusMeters = obj["radiusMeters"]!!.jsonPrimitive.double,
            )
        }.getOrNull()

        fun listToJson(regions: List<GeofenceRegion>): String =
            buildJsonArray { regions.forEach { add(it.toJson()) } }.toString()

        fun listFromJson(raw: String?): List<GeofenceRegion> = runCatching {
            if (raw == null) return emptyList()
            kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonArray.mapNotNull { fromJson(it) }
        }.getOrElse { emptyList() }
    }
}

enum class GeofenceTransition(val wire: String) { ENTER("enter"), EXIT("exit") }

/** The nearest active geofences and how far the device can move before others come into reach. */
data class NearbyGeofences(
    val geofences: List<GeofenceRegion>,
    /** Null when every geofence of the workspace was returned. */
    val refreshRadiusMeters: Double?,
) {
    companion object {
        fun fromJson(element: JsonElement): NearbyGeofences {
            val obj = element.jsonObject
            val radius = obj["refreshRadiusMeters"]
            return NearbyGeofences(
                geofences = obj["geofences"]?.jsonArray?.mapNotNull { GeofenceRegion.fromJson(it) }.orEmpty(),
                refreshRadiusMeters = if (radius == null || radius is JsonNull) null else radius.jsonPrimitive.doubleOrNull,
            )
        }
    }
}

object Geofencing {
    /** iOS lets an app monitor 20 regions; one is the refresh region. */
    const val DEFAULT_MAX_REGIONS_IOS = 20

    /** Android allows 100 per app; 50 leaves room for the host app's own. */
    const val DEFAULT_MAX_REGIONS_ANDROID = 50

    /** Repeats of the same transition for the same geofence within this window are dropped. */
    const val DEBOUNCE_MS = 60_000L

    /** Leaving the refresh region fetches again at most this often. */
    const val MIN_REFRESH_INTERVAL_MS = 60_000L

    /** A foreground fetch happens when the last one is older than this. */
    const val STALE_AFTER_MS = 6L * 60 * 60 * 1000

    /** Two decimals, about 1.1 km. */
    fun roundCoordinate(value: Double): Double = (value * 100).roundToLong() / 100.0

    fun fetchLimit(maxRegions: Int): Int = (maxRegions - 1).coerceIn(1, 99)

    /** Returned geofences (at most maxRegions - 1) plus a refresh region on the precise position. */
    fun plan(nearby: NearbyGeofences, latitude: Double, longitude: Double, maxRegions: Int): List<GeofenceRegion> {
        val regions = nearby.geofences.take(fetchLimit(maxRegions)).toMutableList()
        val radius = nearby.refreshRadiusMeters
        if (radius != null && radius > 0) {
            regions.add(GeofenceRegion(GeofenceRegion.REFRESH_REGION_ID, latitude, longitude, radius))
        }
        return regions
    }

    data class Diff(val remove: List<String>, val add: List<GeofenceRegion>)

    /** Only the regions that changed. */
    fun diff(registered: List<GeofenceRegion>, planned: List<GeofenceRegion>): Diff {
        val current = registered.associateBy { it.id }
        val next = planned.associateBy { it.id }
        return Diff(
            remove = registered.filter { next[it.id] != it }.map { it.id },
            add = planned.filter { current[it.id] != it },
        )
    }

    internal fun isDuplicate(last: Long?, at: Long): Boolean = last != null && abs(at - last) < DEBOUNCE_MS
}
