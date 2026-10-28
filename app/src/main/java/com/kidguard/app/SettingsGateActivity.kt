package com.kidguard.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/**
 * Shown when the kid opens Settings / App info while "Lock Settings" is on.
 * The correct parent PIN unlocks Settings for a few minutes; otherwise it sends them home.
 * This is what stops a kid from pulling a permission or uninstalling without the code.
 */
class SettingsGateActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gate)
        prefs = Prefs(this)

        findViewById<Button>(R.id.gateUnlock).setOnClickListener { tryUnlock() }
        findViewById<Button>(R.id.gateHome).setOnClickListener { goHome() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })

        // If the parent turned off "code on the phone", the kid can't type a code to get in.
        // Settings can then only be unlocked remotely from the parent's dashboard.
        if (!RulesStore.rules.settingsPinOnPhone) {
            findViewById<EditText>(R.id.gatePin).visibility = View.GONE
            findViewById<Button>(R.id.gateUnlock).visibility = View.GONE
            findViewById<TextView>(R.id.gateError).visibility = View.GONE
            findViewById<TextView>(R.id.gateTitle).text = "Settings are locked"
            findViewById<TextView>(R.id.gateMsg).text =
                "Your parent locked settings. They can unlock it from their Cell Home app."
        }
    }

    private fun tryUnlock() {
        val input = findViewById<EditText>(R.id.gatePin)
        val error = findViewById<TextView>(R.id.gateError)
        if (prefs.checkPin(input.text.toString())) {
            fails = 0
            prefs.settingsUnlockUntil = System.currentTimeMillis() + UNLOCK_MS
            finish() // return to the Settings screen underneath, now allowed
        } else {
            fails++
            input.setText("")
            error.text = "Wrong PIN"
            error.visibility = View.VISIBLE
            if (fails >= 5) {
                fails = 0
                Alerts.send(
                    this, "pin_attempts", "Wrong PIN at settings",
                    "Someone entered the wrong PIN trying to open settings on ${prefs.deviceName}'s phone.",
                    "gatepin",
                )
                goHome()
            }
        }
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    companion object {
        private const val UNLOCK_MS = 5 * 60_000L
        private var fails = 0
    }
}
