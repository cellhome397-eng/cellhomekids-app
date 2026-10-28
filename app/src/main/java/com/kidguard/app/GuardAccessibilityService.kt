package com.kidguard.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.Executors

/**
 * Watches which app is in front and sends the kid back home when the rules say no.
 * It only reads the package name of the foreground app, never screen content.
 */
class GuardAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var prefs: Prefs

    private var currentPkg: String? = null
    private var allowed: Set<String> = emptySet()
    private var imes: Set<String> = emptySet()
    private var allowedAt = 0L

    private var usage: Map<String, Long> = emptyMap()
    private var usageAt = 0L
    @Volatile private var refreshing = false

    private var lastBlockPkg: String? = null
    private var lastBlockAt = 0L
    private var lastSyncKick = 0L
    private var lastLocationKick = 0L

    /** Re-checks every 30 s so limits and bedtime kick in while an app is already open. */
    private val tick = object : Runnable {
        override fun run() {
            val ctx = this@GuardAccessibilityService
            RulesStore.startListening(ctx)
            DeviceOwner.enforce(ctx, RulesStore.rules, blockingServiceOn = true)
            // Re-assert a parent hold-lock so it can't be escaped by sitting on the home screen.
            // Never re-launch while our own lock screen is already in front, or the PIN dialog
            // the parent is typing into would be cleared.
            val cp = currentPkg
            if (prefs.screenLocked() && cp != packageName && (cp == null || cp !in Apps.CALL_PACKAGES)) {
                LockActivity.show(ctx)
            }
            currentPkg?.let { evaluate(it) }
            val now = System.currentTimeMillis()
            if (now - lastLocationKick > LOCATION_EVERY_MS) {
                lastLocationKick = now
                LocationTracker.updateAsync(ctx)
            }
            if (now - lastSyncKick > SYNC_EVERY_MS) {
                lastSyncKick = now
                SyncWorker.runNow(this@GuardAccessibilityService)
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        RulesStore.load(this)
        refreshAllowed()
        handler.post(tick)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!::prefs.isInitialized) return
        val pkg = event.packageName?.toString() ?: return
        // Notification shade and keyboards pop over the current app; ignore them.
        if (pkg == "com.android.systemui" || pkg in imes) return
        currentPkg = pkg
        evaluate(pkg)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        RulesStore.stop()
        super.onDestroy()
    }

    private fun evaluate(pkg: String) {
        if (!prefs.setupComplete) return
        // Parent hold-lock wins over everything except calls and our own lock screen.
        if (prefs.screenLocked()) {
            if (pkg !in Apps.CALL_PACKAGES && pkg != packageName) LockActivity.show(this)
            return
        }
        val now = System.currentTimeMillis()
        if (now - allowedAt > ALLOWED_REFRESH_MS) refreshAllowed()
        val rules = RulesStore.rules
        val pausedUntil = maxOf(rules.pausedUntil, prefs.localPauseUntil)
        val reason = Enforcer.check(pkg, rules, allowed, usage, pausedUntil, now, prefs.settingsUnlockUntil)
        if (reason != null) block(pkg, reason)
        if (now - usageAt > USAGE_REFRESH_MS) refreshUsageAsync()
    }

    private fun refreshAllowed() {
        allowed = Apps.alwaysAllowed(this)
        imes = Apps.imePackages(this)
        allowedAt = System.currentTimeMillis()
    }

    private fun refreshUsageAsync() {
        if (refreshing) return
        refreshing = true
        executor.execute {
            val fresh = try {
                if (Status.usageAccess(this)) UsageTracker.todayMillis(this) else emptyMap()
            } catch (e: Exception) {
                emptyMap()
            }
            handler.post {
                usage = fresh
                usageAt = System.currentTimeMillis()
                refreshing = false
                currentPkg?.let { evaluate(it) } // usage is fresh now, so this won't loop
            }
        }
    }

    private fun block(pkg: String, reason: BlockReason) {
        val now = System.currentTimeMillis()
        if (pkg == lastBlockPkg && now - lastBlockAt < 1500) return
        lastBlockPkg = pkg
        lastBlockAt = now

        val label = Apps.label(this, pkg)

        // Settings/uninstall: ask for the parent PIN instead of just bouncing home,
        // so a parent with the code can get in but the kid can't.
        if (reason == BlockReason.SETTINGS_LOCKED) {
            val gate = Intent(this, SettingsGateActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            try {
                startActivity(gate)
            } catch (e: Exception) {
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            Alerts.forBlock(this, reason, pkg, label)
            return
        }

        performGlobalAction(GLOBAL_ACTION_HOME)
        val intent = Intent(this, BlockActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(BlockActivity.EXTRA_REASON, reason.name)
            .putExtra(BlockActivity.EXTRA_LABEL, label)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // Going home is already enough to block.
        }
        Alerts.forBlock(this, reason, pkg, label)
    }

    companion object {
        private const val TICK_MS = 30_000L
        private const val USAGE_REFRESH_MS = 60_000L
        private const val ALLOWED_REFRESH_MS = 5 * 60_000L
        private const val SYNC_EVERY_MS = 10 * 60_000L
        private const val LOCATION_EVERY_MS = 2 * 60_000L
    }
}
