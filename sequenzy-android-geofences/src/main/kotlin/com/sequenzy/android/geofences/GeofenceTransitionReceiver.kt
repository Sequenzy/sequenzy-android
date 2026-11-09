package com.sequenzy.android.geofences

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import com.sequenzy.android.Sequenzy
import com.sequenzy.core.GeofenceRegion
import com.sequenzy.core.GeofenceTransition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Keeps the process alive for [work], finishing the broadcast when it is done
 * or after a few seconds, whichever comes first: a broadcast must finish
 * within its deadline, and everything the work needs to survive is already
 * persisted.
 */
private fun BroadcastReceiver.finishWithin(work: suspend () -> Unit) {
    val pending = goAsync()
    val finished = AtomicBoolean(false)
    val done = { if (finished.compareAndSet(false, true)) pending.finish() }
    Handler(Looper.getMainLooper()).postDelayed({ done() }, 9_000)
    receiverScope.launch {
        try {
            work()
        } finally {
            done()
        }
    }
}

/** Receives enter/exit from Play services, also when the app is not running. */
class GeofenceTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            if (event.errorCode == GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE) {
                SequenzyGeofences.forgetRegistered(context)
            }
            Log.w("SequenzyGeofences", "Geofence error ${event.errorCode}")
            return
        }
        val transition = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> GeofenceTransition.ENTER
            Geofence.GEOFENCE_TRANSITION_EXIT -> GeofenceTransition.EXIT
            else -> return
        }
        val ids = event.triggeringGeofences.orEmpty().map { it.requestId }
        var replan: Job? = null
        if (ids.contains(GeofenceRegion.REFRESH_REGION_ID) && transition == GeofenceTransition.EXIT) {
            // The device left the area the plan covers: until a fetch succeeds,
            // the next foreground retries it.
            SequenzyGeofences.markStale(context)
            replan = SequenzyGeofences.refreshInBackground(context, force = false)
        }
        val known = SequenzyGeofences.registered(context).map { it.id }.toSet()
        val report = ids.filter { it != GeofenceRegion.REFRESH_REGION_ID && it in known }
        if (replan == null && (report.isEmpty() || Sequenzy.identifiedContact() == null)) return

        val at = event.triggeringLocation?.time?.takeIf { it > 0 } ?: System.currentTimeMillis()
        // Every transition is stored before anything is sent; sending that
        // takes longer than the broadcast allows continues while the process
        // lives, or on the next transition or launch.
        finishWithin {
            if (report.isNotEmpty()) Sequenzy.trackGeofenceTransitions(report, transition, at)
            replan?.join()
        }
    }
}

/** Re-registers geofences after a reboot or app update. */
class GeofenceRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val restoring = SequenzyGeofences.restore(context) ?: return
                finishWithin { restoring.join() }
            }
        }
    }
}
