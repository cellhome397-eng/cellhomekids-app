package com.kidguard.app

import android.content.Context
import java.security.MessageDigest
import java.util.UUID

/** Small wrapper around SharedPreferences for everything the app stores locally. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("kidguard", Context.MODE_PRIVATE)

    /** Random, stable ID for this phone. Survives unlinking. */
    val deviceId: String
        get() {
            sp.getString(KEY_DEVICE_ID, null)?.let { return it }
            val id = UUID.randomUUID().toString()
            sp.edit().putString(KEY_DEVICE_ID, id).apply()
            return id
        }

    /** The family this phone belongs to (the uid of the parent who created the family). */
    var parentUid: String?
        get() = sp.getString("parentUid", null)
        set(v) { sp.edit().putString("parentUid", v).apply() }

    /** The account signed in on this phone: the family owner or a co-parent. */
    var authUid: String?
        get() = sp.getString("authUid", null) ?: parentUid
        set(v) { sp.edit().putString("authUid", v).apply() }

    var deviceName: String
        get() = sp.getString("deviceName", null) ?: "Kid's phone"
        set(v) { sp.edit().putString("deviceName", v).apply() }

    /** Blocking only starts once the parent taps "Finish setup". */
    var setupComplete: Boolean
        get() = sp.getBoolean("setupComplete", false)
        set(v) { sp.edit().putBoolean("setupComplete", v).apply() }

    /** Last rules received from Firestore, so blocking works offline. */
    var rulesJson: String?
        get() = sp.getString("rulesJson", null)
        set(v) { sp.edit().putString("rulesJson", v).apply() }

    /** Pause set with the parent PIN on the phone itself. */
    var localPauseUntil: Long
        get() = sp.getLong("localPauseUntil", 0L)
        set(v) { sp.edit().putLong("localPauseUntil", v).apply() }

    /** After a correct PIN at the Settings gate, Settings is allowed until this time. */
    var settingsUnlockUntil: Long
        get() = sp.getLong("settingsUnlockUntil", 0L)
        set(v) { sp.edit().putLong("settingsUnlockUntil", v).apply() }

    /** The Cell Home owner disabled this family's service: the app stops controlling the phone. */
    var serviceDisabled: Boolean
        get() = sp.getBoolean("serviceDisabled", false)
        set(v) { sp.edit().putBoolean("serviceDisabled", v).apply() }

    var protectionAlerted: Boolean
        get() = sp.getBoolean("protectionAlerted", false)
        set(v) { sp.edit().putBoolean("protectionAlerted", v).apply() }

    var batteryAlerted: Boolean
        get() = sp.getBoolean("batteryAlerted", false)
        set(v) { sp.edit().putBoolean("batteryAlerted", v).apply() }

    /** Dashboard commands older than these are ignored. Set to "now" when linking. */
    var lockHandledAt: Long
        get() = sp.getLong("lockHandledAt", 0L)
        set(v) { sp.edit().putLong("lockHandledAt", v).apply() }

    var releaseHandledAt: Long
        get() = sp.getLong("releaseHandledAt", 0L)
        set(v) { sp.edit().putLong("releaseHandledAt", v).apply() }

    /**
     * Persistent "hold lock": the parent locked the phone and it stays locked, even
     * offline or after a reboot, until the parent unlocks it (or enters the PIN here).
     * Locked when lockOnAt is newer than lockOffAt.
     */
    var lockOnAt: Long
        get() = sp.getLong("lockOnAt", 0L)
        set(v) { sp.edit().putLong("lockOnAt", v).apply() }

    var lockOffAt: Long
        get() = sp.getLong("lockOffAt", 0L)
        set(v) { sp.edit().putLong("lockOffAt", v).apply() }

    /** True while the phone is held locked by the parent. */
    fun screenLocked(): Boolean = setupComplete && lockOnAt > lockOffAt

    /** When locked, whether the kid can still open Phone + Contacts to call. Default yes. */
    var lockCallsAllowed: Boolean
        get() = sp.getBoolean("lockCallsAllowed", true)
        set(v) { sp.edit().putBoolean("lockCallsAllowed", v).apply() }

    var lastLocationAt: Long
        get() = sp.getLong("lastLocationAt", 0L)
        set(v) { sp.edit().putLong("lastLocationAt", v).apply() }

    var locateHandledAt: Long
        get() = sp.getLong("locateHandledAt", 0L)
        set(v) { sp.edit().putLong("locateHandledAt", v).apply() }

    var settingsUnlockHandledAt: Long
        get() = sp.getLong("settingsUnlockHandledAt", 0L)
        set(v) { sp.edit().putLong("settingsUnlockHandledAt", v).apply() }

    /** Which watched places the kid is currently inside (geofence state). */
    var geofenceInside: Set<String>
        get() = sp.getStringSet("geofenceInside", emptySet())?.toSet() ?: emptySet()
        set(v) { sp.edit().putStringSet("geofenceInside", v).apply() }

    /** Apps currently suspended by full-control mode. */
    var suspended: Set<String>
        get() = sp.getStringSet("suspended", emptySet())?.toSet() ?: emptySet()
        set(v) { sp.edit().putStringSet("suspended", v).apply() }

    /** Short code the parent puts in an SMS command. Lets them lock/locate with no internet. */
    val smsCode: String
        get() {
            sp.getString("smsCode", null)?.let { return it }
            val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            val code = (1..4).map { chars.random() }.joinToString("")
            sp.edit().putString("smsCode", code).apply()
            return code
        }

    fun hasPin(): Boolean = sp.contains("pinHash")
    fun setPin(pin: String) { sp.edit().putString("pinHash", hash(pin)).apply() }
    fun checkPin(pin: String): Boolean = sp.getString("pinHash", null) == hash(pin)

    /** Returns true only the first time [key] is seen today (used to avoid alert spam). */
    @Synchronized
    fun firstTimeToday(key: String): Boolean {
        val today = UsageTracker.todayKey()
        val full = "$today|$key"
        val current = sp.getStringSet("alerted", emptySet()) ?: emptySet()
        if (full in current) return false
        val next = current.filter { it.startsWith("$today|") }.toMutableSet()
        next.add(full)
        sp.edit().putStringSet("alerted", next).apply()
        return true
    }

    fun clearAll() {
        val id = deviceId
        sp.edit().clear().putString(KEY_DEVICE_ID, id).apply()
    }

    private fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("kidguard:$pin".toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val KEY_DEVICE_ID = "deviceId"
    }
}
