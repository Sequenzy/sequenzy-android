package com.sequenzy.android.geofences

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.sequenzy.android.Sequenzy
import com.sequenzy.core.GeofenceRegion
import com.sequenzy.core.Geofencing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * Geofencing for Android: watches the nearest of your geofences with Google
 * Play services and reports when the signed-in user enters or leaves one.
 *
 * ```kotlin
 * // Application.onCreate, after Sequenzy.configure(...)
 * SequenzyGeofences.start(this)
 * // once the user agreed and granted fine + background location
 * SequenzyGeofences.enable(context)
 * ```
 *
 * The app declares and requests `ACCESS_FINE_LOCATION` and, on Android 10+,
 * `ACCESS_BACKGROUND_LOCATION` (Google Play asks apps that use it for a
 * declaration). The SDK registers the nearest geofences plus one region
 * around the device and fetches again when the device leaves it.
 */
object SequenzyGeofences {
    private const val TAG = "SequenzyGeofences"
    private const val PREFS = "com.sequenzy.geofences"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_REGIONS = "regions"
    private const val KEY_LAST_FETCH = "lastFetch"

    /** Regions this SDK may register. Lower it if the app registers its own. */
    @JvmStatic
    var maxRegions: Int = Geofencing.DEFAULT_MAX_REGIONS_ANDROID

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private var started = false
    private var lastRefreshRequest = 0L

    /** Call in Application.onCreate after Sequenzy.configure. */
    @JvmStatic
    fun start(context: Context) {
        if (started) return
        started = true
        val appContext = context.applicationContext
        scope.launch(Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    if (isEnabled(appContext) && isStale(appContext)) refresh(appContext)
                }
            })
        }
    }

    @JvmStatic
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    /** Turns geofencing on and fetches the nearest geofences. */
    @JvmStatic
    fun enable(context: Context) {
        prefs(context).edit().putBoolean(KEY_ENABLED, true).apply()
        refresh(context, force = true)
    }

    /** Turns geofencing off and removes every Sequenzy geofence. Call on sign-out. */
    @JvmStatic
    fun disable(context: Context) {
        val appContext = context.applicationContext
        prefs(appContext).edit().putBoolean(KEY_ENABLED, false).remove(KEY_LAST_FETCH).apply()
        scope.launch { mutex.withLock { apply(appContext, emptyList()) } }
    }

    /** Fetches the nearest geofences for the current position. */
    @JvmStatic
    @JvmOverloads
    fun refresh(context: Context, force: Boolean = false) {
        refreshInBackground(context, force)
    }

    // region Internals

    /** [refresh], returning the fetch it started, if any. */
    internal fun refreshInBackground(context: Context, force: Boolean): Job? {
        val appContext = context.applicationContext
        if (!isEnabled(appContext)) return null
        val now = System.currentTimeMillis()
        if (!force && now - lastRefreshRequest < Geofencing.MIN_REFRESH_INTERVAL_MS) return null
        lastRefreshRequest = now
        if (!hasPermissions(appContext)) {
            Log.i(TAG, "Geofencing needs fine and background location permission")
            return null
        }
        return scope.launch { mutex.withLock { fetchAndApply(appContext) } }
    }

    /** The plan no longer covers the device's area. */
    internal fun markStale(context: Context) {
        prefs(context).edit().remove(KEY_LAST_FETCH).apply()
    }

    internal fun registered(context: Context): List<GeofenceRegion> =
        GeofenceRegion.listFromJson(prefs(context).getString(KEY_REGIONS, null))

    /**
     * After a reboot or app update the system has dropped every geofence.
     * Returns the re-registration it started, if any.
     */
    internal fun restore(context: Context): Job? {
        val appContext = context.applicationContext
        if (!isEnabled(appContext) || !hasPermissions(appContext)) return null
        return scope.launch {
            mutex.withLock {
                val regions = registered(appContext)
                // Stale until it succeeds, so an interrupted restore is retried.
                val lastFetch = prefs(appContext).getLong(KEY_LAST_FETCH, 0)
                prefs(appContext).edit().remove(KEY_REGIONS).remove(KEY_LAST_FETCH).commit()
                if (apply(appContext, regions) && lastFetch > 0) {
                    prefs(appContext).edit().putLong(KEY_LAST_FETCH, lastFetch).apply()
                }
            }
        }
    }

    /** Location services were turned off: the system removed the geofences. */
    internal fun forgetRegistered(context: Context) {
        prefs(context).edit().remove(KEY_REGIONS).remove(KEY_LAST_FETCH).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun isStale(context: Context): Boolean {
        val last = prefs(context).getLong(KEY_LAST_FETCH, 0)
        return last == 0L || System.currentTimeMillis() - last > Geofencing.STALE_AFTER_MS
    }

    private fun hasPermissions(context: Context): Boolean {
        // ContextCompat answers from the manifest below Android 6, where
        // Context.checkSelfPermission does not exist (minSdk is 21).
        fun granted(permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val background = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        return fine && background
    }

    @SuppressLint("MissingPermission")
    private suspend fun fetchAndApply(context: Context) {
        if (Sequenzy.identifiedEmail() == null) {
            // Visits are recorded on a contact; nobody is signed in.
            apply(context, emptyList())
            return
        }
        try {
            val location = LocationServices.getFusedLocationProviderClient(context)
                .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                .await() ?: return
            val nearby = Sequenzy.fetchNearbyGeofences(
                location.latitude, location.longitude, Geofencing.fetchLimit(maxRegions),
            )
            if (!isEnabled(context)) return
            // A plan Play services did not take is retried on the next foreground.
            val applied = apply(context, Geofencing.plan(nearby, location.latitude, location.longitude, maxRegions))
            if (applied) {
                prefs(context).edit().putLong(KEY_LAST_FETCH, System.currentTimeMillis()).apply()
            } else {
                markStale(context)
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not refresh geofences: ${error.message}")
        }
    }

    /**
     * Registers [planned] and removes regions no longer in it. Returns whether
     * Play services took the whole plan; the stored regions are only those
     * it actually holds.
     */
    @SuppressLint("MissingPermission")
    private suspend fun apply(context: Context, planned: List<GeofenceRegion>): Boolean {
        val client = LocationServices.getGeofencingClient(context)
        val current = registered(context)
        val changes = Geofencing.diff(current, planned)
        fun store(regions: List<GeofenceRegion>) =
            prefs(context).edit().putString(KEY_REGIONS, GeofenceRegion.listToJson(regions)).apply()
        try {
            if (changes.remove.isNotEmpty()) {
                client.removeGeofences(changes.remove).await()
                val removed = changes.remove.toSet()
                store(current.filter { it.id !in removed })
            }
            if (changes.add.isNotEmpty()) {
                if (!hasPermissions(context)) return false
                val request = GeofencingRequest.Builder()
                    // No enter for regions the device is already in: a re-plan
                    // must not report visits again.
                    .setInitialTrigger(0)
                    .addGeofences(
                        changes.add.map { region ->
                            Geofence.Builder()
                                .setRequestId(region.id)
                                .setCircularRegion(region.latitude, region.longitude, region.radiusMeters.toFloat())
                                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                                .build()
                        },
                    )
                    .build()
                client.addGeofences(request, pendingIntent(context)).await()
            }
            store(planned)
            return true
        } catch (error: Exception) {
            Log.w(TAG, "Could not update geofences: ${error.message}")
            return false
        }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, GeofenceTransitionReceiver::class.java)
        // The system fills in the triggering geofences, so it must be mutable.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    // endregion
}
