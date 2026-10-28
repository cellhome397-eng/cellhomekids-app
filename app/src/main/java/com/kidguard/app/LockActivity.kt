package com.kidguard.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.SetOptions

/**
 * Full-screen "phone locked by parent" page. Stays in front until the parent
 * unlocks from the dashboard or enters the parent PIN here. Calls still work.
 */
class LockActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverEverything()
        setContentView(R.layout.activity_lock)

        findViewById<Button>(R.id.parentUnlock).setOnClickListener { askPin() }
        // Back does nothing: the kid can't leave the lock.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })
    }

    private val handler = Handler(Looper.getMainLooper())
    private val watchUnlock = object : Runnable {
        override fun run() {
            // Closes the lock as soon as the parent unlocks remotely from the dashboard.
            if (!Prefs(this@LockActivity).screenLocked()) { finish(); return }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onResume() {
        super.onResume()
        // If the parent unlocked (remotely or by PIN), close the lock.
        if (!Prefs(this).screenLocked()) { finish(); return }
        handler.post(watchUnlock)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(watchUnlock)
    }

    private fun showOverEverything() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }

    private fun askPin() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Parent PIN"
        }
        AlertDialog.Builder(this)
            .setTitle("Parent unlock")
            .setMessage("Enter the parent PIN to unlock this phone.")
            .setView(input)
            .setPositiveButton("Unlock") { _, _ ->
                val prefs = Prefs(this)
                if (prefs.checkPin(input.text.toString())) {
                    unlockNow(prefs)
                } else {
                    android.widget.Toast.makeText(this, "Wrong PIN", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Clears the lock locally and tells the dashboard it's unlocked. */
    private fun unlockNow(prefs: Prefs) {
        val now = System.currentTimeMillis()
        prefs.lockOffAt = maxOf(prefs.lockOffAt, now)
        val uid = prefs.parentUid
        if (uid != null) {
            // Best-effort: reflect the unlock on the dashboard (needs internet).
            Fire.commandsRef(uid, prefs.deviceId)
                .set(mapOf("holdUnlockAt" to now), SetOptions.merge())
            Fire.deviceRef(uid, prefs.deviceId)
                .set(mapOf("locked" to false), SetOptions.merge())
        }
        finish()
    }

    companion object {
        /** Brings the lock screen to the front. Safe to call often. */
        fun show(ctx: Context) {
            val i = Intent(ctx, LockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            try {
                ctx.startActivity(i)
            } catch (e: Exception) {
                // ignore: the accessibility tick will try again
            }
        }
    }
}
