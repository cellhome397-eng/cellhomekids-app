package com.kidguard.app

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.UserManager
import android.util.Log

/**
 * "Full control" mode. Active only when Cell Home Kids was made device owner by the USB setup script.
 * Then Android itself enforces:
 *  - Cell Home Kids can't be uninstalled
 *  - no safe mode, no factory reset, no extra users, no changing the date/time
 *  - no USB debugging (lifted while rules are paused, so the parent can update Cell Home Kids)
 *  - if the blocking service is ever off, apps are suspended by the system instead
 */
object DeviceOwner {
    private val ALWAYS_RESTRICT = listOf(
        UserManager.DISALLOW_SAFE_BOOT,
        UserManager.DISALLOW_FACTORY_RESET,
        UserManager.DISALLOW_ADD_USER,
        UserManager.DISALLOW_CONFIG_DATE_TIME,
    )
    private const val DEBUG = UserManager.DISALLOW_DEBUGGING_FEATURES
    private val ALL = ALWAYS_RESTRICT + DEBUG

    private fun dpm(ctx: Context) = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private fun admin(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)

    fun isOwner(ctx: Context): Boolean = dpm(ctx).isDeviceOwnerApp(ctx.packageName)

    /**
     * Applies the policies. Safe to call often.
     * @param usageMs pass today's usage to allow the suspension fallback; null skips it.
     */
    fun enforce(ctx: Context, rules: Rules, blockingServiceOn: Boolean, usageMs: Map<String, Long>? = null) {
        if (!isOwner(ctx)) return
        val prefs = Prefs(ctx)
        // Switched off by Cell Home: don't suspend any apps.
        if (prefs.serviceDisabled) {
            try { syncSuspended(ctx, emptySet()) } catch (e: Exception) {}
            return
        }
        try {
            dpm(ctx).setUninstallBlocked(admin(ctx), ctx.packageName, true)
            if (!prefs.setupComplete) {
                setRestrictions(ctx, emptySet())
                syncSuspended(ctx, emptySet())
                return
            }
            val pausedUntil = maxOf(rules.pausedUntil, prefs.localPauseUntil)
            val paused = pausedUntil > System.currentTimeMillis()
            val wanted = ALWAYS_RESTRICT.toMutableSet()
            if (!paused) wanted.add(DEBUG)
            setRestrictions(ctx, wanted)

            val toSuspend: Set<String>? = when {
                blockingServiceOn -> emptySet() // the service shows the nicer block screen + alerts
                usageMs == null -> null
                else -> {
                    val allowed = Apps.alwaysAllowed(ctx)
                    Apps.launcherApps(ctx).map { it.first }
                        .filter { it !in Apps.SETTINGS_PACKAGES }
                        .filter { Enforcer.check(it, rules, allowed, usageMs, pausedUntil) != null }
                        .toSet()
                }
            }
            if (toSuspend != null) syncSuspended(ctx, toSuspend)
        } catch (e: SecurityException) {
            Log.w("Cell Home Kids", "Device owner policy failed", e)
        }
    }

    /** Removes every restriction and gives up device owner. Used when unlinking. */
    @Suppress("DEPRECATION")
    fun release(ctx: Context) {
        if (!isOwner(ctx)) return
        try {
            syncSuspended(ctx, emptySet())
            setRestrictions(ctx, emptySet())
            dpm(ctx).setUninstallBlocked(admin(ctx), ctx.packageName, false)
            dpm(ctx).clearDeviceOwnerApp(ctx.packageName)
        } catch (e: Exception) {
            Log.w("Cell Home Kids", "Release failed", e)
        }
    }

    private fun setRestrictions(ctx: Context, wanted: Set<String>) {
        val dpm = dpm(ctx)
        val admin = admin(ctx)
        val current = dpm.getUserRestrictions(admin)
        for (r in ALL) {
            val on = current.getBoolean(r, false)
            if (r in wanted && !on) dpm.addUserRestriction(admin, r)
            if (r !in wanted && on) dpm.clearUserRestriction(admin, r)
        }
    }

    private fun syncSuspended(ctx: Context, want: Set<String>) {
        val prefs = Prefs(ctx)
        val have = prefs.suspended
        val add = want - have
        val remove = have - want
        if (add.isEmpty() && remove.isEmpty()) return
        val dpm = dpm(ctx)
        val admin = admin(ctx)
        val failed = if (add.isNotEmpty()) dpm.setPackagesSuspended(admin, add.toTypedArray(), true).toSet() else emptySet()
        if (remove.isNotEmpty()) dpm.setPackagesSuspended(admin, remove.toTypedArray(), false)
        prefs.suspended = (have - remove) + (add - failed)
    }
}

/** Remote commands from the dashboard: parents/{uid}/devices/{id}/config/commands */
object Commands {
    /** Returns true if the phone was released (unlinked) by this command. */
    fun handle(ctx: Context, data: Map<String, Any?>?): Boolean {
        if (data == null) return false
        val prefs = Prefs(ctx)
        val now = System.currentTimeMillis()

        val lockAt = (data["lockNowAt"] as? Number)?.toLong() ?: 0L
        if (lockAt > prefs.lockHandledAt) {
            prefs.lockHandledAt = lockAt
            // Ignore stale lock requests (phone was offline for a long time).
            if (now - lockAt < 10 * 60_000L && Status.adminOn(ctx)) {
                try {
                    (ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).lockNow()
                } catch (e: SecurityException) {
                    Log.w("Cell Home Kids", "lockNow failed", e)
                }
            }
        }

        // Persistent "hold lock": stays locked until the parent unlocks (or the PIN is entered).
        val wasLocked = prefs.screenLocked()
        val holdLockAt = (data["holdLockAt"] as? Number)?.toLong() ?: 0L
        if (holdLockAt > prefs.lockOnAt) {
            prefs.lockOnAt = holdLockAt
            // Lock mode chosen by the parent for this lock: allow calls, or full lock.
            prefs.lockCallsAllowed = (data["lockCalls"] as? Boolean) ?: true
        }
        val holdUnlockAt = (data["holdUnlockAt"] as? Number)?.toLong() ?: 0L
        if (holdUnlockAt > prefs.lockOffAt) prefs.lockOffAt = holdUnlockAt
        val nowLocked = prefs.screenLocked()
        if (nowLocked != wasLocked) {
            prefs.parentUid?.let { uid ->
                Fire.deviceRef(uid, prefs.deviceId)
                    .set(mapOf("locked" to nowLocked), com.google.firebase.firestore.SetOptions.merge())
            }
        }
        if (nowLocked) LockActivity.show(ctx)

        val locateAt = (data["locateNowAt"] as? Number)?.toLong() ?: 0L
        if (locateAt > prefs.locateHandledAt) {
            prefs.locateHandledAt = locateAt
            if (now - locateAt < 10 * 60_000L) LocationTracker.updateAsync(ctx)
        }

        // Parent unlocks Settings on the phone from the dashboard (no code typed on the device).
        val settingsUnlockAt = (data["settingsUnlockAt"] as? Number)?.toLong() ?: 0L
        if (settingsUnlockAt > prefs.settingsUnlockHandledAt) {
            prefs.settingsUnlockHandledAt = settingsUnlockAt
            if (now - settingsUnlockAt < 10 * 60_000L) {
                prefs.settingsUnlockUntil = now + 10 * 60_000L
            }
        }

        val releaseAt = (data["releaseAt"] as? Number)?.toLong() ?: 0L
        if (releaseAt > prefs.releaseHandledAt) {
            prefs.releaseHandledAt = releaseAt
            Unlink.run(ctx)
            return true
        }
        return false
    }
}

/** Fully disconnects this phone: removes all control so Cell Home Kids can be uninstalled. */
object Unlink {
    fun run(ctx: Context, onDone: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        val prefs = Prefs(app)
        val uid = prefs.parentUid
        val deviceId = prefs.deviceId
        prefs.setupComplete = false
        DeviceOwner.release(app)
        val dpm = app.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(app, AdminReceiver::class.java)
        if (dpm.isAdminActive(admin)) dpm.removeActiveAdmin(admin)
        SyncWorker.cancel(app)
        RulesStore.stop()

        val finish = {
            com.google.firebase.auth.FirebaseAuth.getInstance().signOut()
            prefs.clearAll()
            onDone?.invoke()
        }
        if (uid == null) {
            finish()
            return
        }
        Fire.deviceRef(uid, deviceId)
            .set(
                mapOf("released" to true, "setupComplete" to false, "protectionOn" to false, "deviceOwner" to false),
                com.google.firebase.firestore.SetOptions.merge(),
            )
            .addOnCompleteListener { finish() }
    }
}
