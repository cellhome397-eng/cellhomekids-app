package com.kidguard.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import java.util.concurrent.TimeUnit

/**
 * Gets the kid phone's position and uploads it, and fires arrive/leave alerts.
 * Uses Google Play Services' fused location (free). Only the last position is
 * stored on the device doc; there is no location history, to keep it private and small.
 */
object LocationTracker {

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Fetches a fresh location and processes it. Blocks up to ~20 s; call off the main thread. */
    fun updateBlocking(ctx: Context) {
        val prefs = Prefs(ctx)
        if (!prefs.setupComplete || prefs.parentUid == null || !hasPermission(ctx)) return
        try {
            val client = LocationServices.getFusedLocationProviderClient(ctx)
            val cts = CancellationTokenSource()
            val loc = Tasks.await(
                client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token),
                20, TimeUnit.SECONDS,
            ) ?: Tasks.await(client.lastLocation, 5, TimeUnit.SECONDS) ?: return
            process(ctx, loc)
            prefs.lastLocationAt = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.w("KidGuard", "Location update failed", e)
        }
    }

    /** Non-blocking fetch for the always-running service. */
    fun updateAsync(ctx: Context) {
        val prefs = Prefs(ctx)
        if (!prefs.setupComplete || prefs.parentUid == null || !hasPermission(ctx)) return
        try {
            val client = LocationServices.getFusedLocationProviderClient(ctx)
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
                .addOnSuccessListener { loc ->
                    if (loc != null) {
                        process(ctx, loc)
                        prefs.lastLocationAt = System.currentTimeMillis()
                    } else {
                        // No fresh fix (common in the background): use the last known position.
                        client.lastLocation.addOnSuccessListener { last ->
                            if (last != null) {
                                process(ctx, last)
                                prefs.lastLocationAt = System.currentTimeMillis()
                            }
                        }
                    }
                }
        } catch (e: SecurityException) {
            Log.w("KidGuard", "Location permission missing", e)
        }
    }

    private fun process(ctx: Context, loc: Location) {
        val prefs = Prefs(ctx)
        val uid = prefs.parentUid ?: return
        Fire.deviceRef(uid, prefs.deviceId).set(
            mapOf(
                "lastLat" to loc.latitude,
                "lastLng" to loc.longitude,
                "lastAccuracy" to loc.accuracy.toInt(),
                "lastLocationAt" to FieldValue.serverTimestamp(),
                "locationOn" to true,
            ),
            SetOptions.merge(),
        )
        checkGeofences(ctx, loc)
    }

    /** Arrive/leave detection, fully on-device so alerts cost nothing. */
    private fun checkGeofences(ctx: Context, loc: Location) {
        val places = RulesStore.rules.places
        if (places.isEmpty()) return
        val prefs = Prefs(ctx)
        val inside = prefs.geofenceInside.toMutableSet()
        val results = FloatArray(1)
        for (p in places) {
            Location.distanceBetween(loc.latitude, loc.longitude, p.lat, p.lng, results)
            val near = results[0] <= p.radius + loc.accuracy
            val wasInside = p.id in inside
            // Require decent accuracy before announcing, to avoid false in/out flips.
            if (loc.accuracy > 200) continue
            if (near && !wasInside) {
                inside.add(p.id)
                Alerts.send(
                    ctx, "geofence_enter", "Arrived at ${p.name}",
                    "${prefs.deviceName} arrived at ${p.name}.",
                )
            } else if (!near && wasInside) {
                inside.remove(p.id)
                Alerts.send(
                    ctx, "geofence_leave", "Left ${p.name}",
                    "${prefs.deviceName} left ${p.name}.",
                )
            }
        }
        // Forget places the parent deleted.
        val validIds = places.map { it.id }.toSet()
        prefs.geofenceInside = inside.filter { it in validIds }.toSet()
    }
}
