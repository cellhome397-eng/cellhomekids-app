package com.kidguard.app

import android.app.admin.DeviceAdminReceiver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/** Device admin: Android won't let the app be uninstalled while this is active. */
class AdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        context.getString(R.string.admin_disable_warning)

    override fun onDisabled(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        if (!prefs.setupComplete) return // parent is unlinking or still setting up
        Alerts.send(
            context, "admin_off", "Uninstall protection turned off",
            "On ${prefs.deviceName}'s phone, uninstall protection was turned off. Cell Home Kids could now be removed.",
        )
    }
}

/**
 * Lets the removal script (adb, from the parent's computer) release this phone.
 * Protected by android.permission.DUMP, which only the adb shell and the system hold,
 * so other apps can't trigger it. Also checks the parent PIN.
 *   adb shell am broadcast -a com.kidguard.app.action.RELEASE -n com.kidguard.app/.ReleaseReceiver --es pin p1234
 * Result data: OK or WRONG_PIN.
 */
class ReleaseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val prefs = Prefs(context)
        // Scripts send "p" + PIN so an empty PIN still arrives as an argument.
        val pin = (intent.getStringExtra("pin") ?: "").removePrefix("p")
        if (prefs.hasPin() && !prefs.checkPin(pin)) {
            resultData = "WRONG_PIN"
            return
        }
        val pending = goAsync()
        val handler = Handler(Looper.getMainLooper())
        var finished = false
        val finish = Runnable {
            if (!finished) {
                finished = true
                pending.resultData = "OK"
                pending.finish()
            }
        }
        handler.postDelayed(finish, 8_000L) // don't wait forever for the network
        Unlink.run(context) {
            handler.removeCallbacks(finish)
            finish.run()
        }
    }

    companion object {
        const val ACTION = "com.kidguard.app.action.RELEASE"
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Prefs(context).parentUid != null) {
            SyncWorker.schedule(context)
            SyncWorker.runNow(context)
        }
    }
}
