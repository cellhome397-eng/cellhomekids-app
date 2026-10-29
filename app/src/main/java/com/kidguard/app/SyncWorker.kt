package com.kidguard.app

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import java.util.concurrent.TimeUnit

/** Every ~15 min: uploads status + today's usage, and raises status alerts. */
class SyncWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result = try {
        Sync.run(applicationContext)
        Result.success()
    } catch (e: Exception) {
        Log.w("Cell Home Kids", "Sync failed", e)
        Result.retry()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork("kidguard-sync", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun runNow(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                "kidguard-sync-now", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>().build(),
            )
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork("kidguard-sync")
            WorkManager.getInstance(ctx).cancelUniqueWork("kidguard-sync-now")
        }
    }
}

object Sync {
    fun run(ctx: Context) {
        val prefs = Prefs(ctx)
        val uid = prefs.parentUid ?: return
        if (FirebaseAuth.getInstance().currentUser?.uid != prefs.authUid) return

        // Is this family switched off by the Cell Home owner?
        try {
            val fam = Tasks.await(Fire.db.collection("parents").document(uid).get(), 15, TimeUnit.SECONDS)
            prefs.serviceDisabled = fam.exists() && fam.getBoolean("disabled") == true
        } catch (e: Exception) {
            // keep the last known state if we can't reach it
        }

        // Pick up commands and rules even if the blocking service isn't running.
        try {
            val cmd = Tasks.await(Fire.commandsRef(uid, prefs.deviceId).get(), 20, TimeUnit.SECONDS)
            if (cmd.exists() && Commands.handle(ctx, cmd.data)) return // released by the parent
            val rulesSnap = Tasks.await(Fire.rulesRef(uid, prefs.deviceId).get(), 20, TimeUnit.SECONDS)
            if (rulesSnap.exists()) RulesStore.update(ctx, Rules.fromMap(rulesSnap.data))
        } catch (e: Exception) {
            Log.w("Cell Home Kids", "Couldn't fetch rules/commands, using cached rules", e)
            RulesStore.load(ctx)
        }

        val usageOn = Status.usageAccess(ctx)
        val protectionOn = Status.accessibilityOn(ctx)
        val adminOn = Status.adminOn(ctx)
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging

        val allowed = Apps.alwaysAllowed(ctx)
        val installed = Apps.launcherApps(ctx)
        val usage = if (usageOn) UsageTracker.todayMillis(ctx) else emptyMap()
        val appRows: List<Map<String, Any>> = usage
            .filter { (pkg, ms) -> pkg !in allowed && ms >= 60_000L }
            .map { (pkg, ms) ->
                mapOf("pkg" to pkg, "label" to Apps.label(ctx, pkg), "minutes" to (ms / 60_000L).toInt())
            }
            .sortedByDescending { it["minutes"] as Int }
        val total = appRows.sumOf { it["minutes"] as Int }
        val today = UsageTracker.todayKey()

        DeviceOwner.enforce(ctx, RulesStore.rules, blockingServiceOn = protectionOn, usageMs = usage)
        val deviceOwner = DeviceOwner.isOwner(ctx)

        val batch = Fire.db.batch()
        batch.set(
            Fire.deviceRef(uid, prefs.deviceId),
            mapOf(
                "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "androidVersion" to Build.VERSION.RELEASE,
                "appVersion" to BuildConfig.VERSION_NAME,
                "lastSeen" to FieldValue.serverTimestamp(),
                "battery" to battery,
                "charging" to charging,
                "protectionOn" to protectionOn,
                "usageAccessOn" to usageOn,
                "adminOn" to adminOn,
                "deviceOwner" to deviceOwner,
                "locationOn" to LocationTracker.hasPermission(ctx),
                "released" to false,
                "setupComplete" to prefs.setupComplete,
                "todayMinutes" to total,
                "todayDate" to today,
                "apps" to installed.map { mapOf("pkg" to it.first, "label" to it.second) },
            ),
            SetOptions.merge(),
        )
        batch.set(
            Fire.usageRef(uid, prefs.deviceId, today),
            mapOf(
                "date" to today,
                "totalMinutes" to total,
                "apps" to appRows,
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        )
        Tasks.await(batch.commit(), 30, TimeUnit.SECONDS)

        // Refresh the phone's position (also handles arrive/leave alerts).
        LocationTracker.updateBlocking(ctx)

        // Protection turned off (accessibility service or usage access disabled).
        if (prefs.setupComplete && (!protectionOn || !usageOn)) {
            if (!prefs.protectionAlerted) {
                val what = if (!protectionOn) "the blocking service" else "usage access"
                Alerts.send(
                    ctx, "protection_off", "Protection turned off",
                    "On ${prefs.deviceName}'s phone, $what was turned off. Open Cell Home Kids on the phone to fix it.",
                )
                prefs.protectionAlerted = true
            }
        } else {
            prefs.protectionAlerted = false
        }

        // Low battery (once until it charges back up).
        if (battery in 1..15 && !charging) {
            if (!prefs.batteryAlerted) {
                Alerts.send(ctx, "battery_low", "Low battery", "${prefs.deviceName}'s phone is at $battery%.")
                prefs.batteryAlerted = true
            }
        } else if (charging || battery >= 30) {
            prefs.batteryAlerted = false
        }
    }
}
