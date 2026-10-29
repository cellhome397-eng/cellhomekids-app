package com.kidguard.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/**
 * Lets the parent control the phone by SMS when it has NO internet (Wi-Fi and data off).
 * The parent texts the kid's phone, e.g.:
 *     CHK LOCK 7K2M      -> lock the phone now (calls + contacts still work)
 *     CHK UNLOCK 7K2M    -> unlock it
 *     CHK WHERE 7K2M     -> update its location (sent once it has any signal/data)
 * "7K2M" is this phone's SMS code, shown on the parent dashboard. The code only allows
 * locking/locating, so it's safe even if the child sees the message; unlocking the screen
 * on the device still needs the parent PIN.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val prefs = Prefs(ctx)
        if (!prefs.setupComplete) return
        val body = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
                ?.joinToString("") { it.messageBody ?: "" }?.trim() ?: return
        } catch (e: Exception) { return }

        val parts = body.split(Regex("\\s+"))
        if (parts.size < 3) return
        if (!parts[0].equals("CHK", ignoreCase = true)) return
        if (!parts[2].equals(prefs.smsCode, ignoreCase = true)) return

        val now = System.currentTimeMillis()
        when (parts[1].uppercase()) {
            "LOCK" -> {
                prefs.lockCallsAllowed = true
                if (now > prefs.lockOnAt) prefs.lockOnAt = now
                LockActivity.show(ctx)
                pushLocked(ctx, true)
            }
            "UNLOCK" -> {
                if (now > prefs.lockOffAt) prefs.lockOffAt = now
                pushLocked(ctx, false)
            }
            "WHERE", "LOCATE" -> LocationTracker.updateAsync(ctx)
            else -> Log.w("Cell Home Kids", "Unknown SMS command: ${parts[1]}")
        }
    }

    private fun pushLocked(ctx: Context, locked: Boolean) {
        val prefs = Prefs(ctx)
        val uid = prefs.parentUid ?: return
        try {
            Fire.deviceRef(uid, prefs.deviceId)
                .set(mapOf("locked" to locked), com.google.firebase.firestore.SetOptions.merge())
        } catch (e: Exception) { /* no internet is fine; the lock is already applied locally */ }
    }
}
