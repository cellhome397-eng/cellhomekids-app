package com.kidguard.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/** Full-screen "this app is locked" page shown after a block. */
class BlockActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_block)
        render(intent)
        findViewById<Button>(R.id.homeButton).setOnClickListener { goHome() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render(intent)
    }

    private fun render(i: Intent) {
        val reason = runCatching { BlockReason.valueOf(i.getStringExtra(EXTRA_REASON) ?: "") }
            .getOrDefault(BlockReason.BLOCKED_APP)
        val label = i.getStringExtra(EXTRA_LABEL) ?: "This app"
        val bedtimeEnd = RulesStore.rules.bedtime.end

        val (icon, title, message) = when (reason) {
            BlockReason.BLOCKED_APP -> Triple("🚫", "$label is blocked", "Your parent has turned this app off.")
            BlockReason.APP_LIMIT -> Triple("⏳", "Time's up for $label", "You've used today's time for this app. It unlocks tomorrow.")
            BlockReason.DAILY_LIMIT -> Triple("⏳", "Screen time is over", "You've reached today's limit. Apps unlock tomorrow.")
            BlockReason.BEDTIME -> Triple("🌙", "It's bedtime", "Apps are locked until $bedtimeEnd. Calls still work.")
            BlockReason.SETTINGS_LOCKED -> Triple("🔒", "Settings are locked", "Ask a parent to unlock them.")
        }
        findViewById<TextView>(R.id.blockIcon).text = icon
        findViewById<TextView>(R.id.blockTitle).text = title
        findViewById<TextView>(R.id.blockMessage).text = message
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
        const val EXTRA_REASON = "reason"
        const val EXTRA_LABEL = "label"
    }
}
