package com.kidguard.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.Executors

/**
 * Watches which app is in front and sends the kid back home when the rules say no.
 * It reads the foreground package name; on Settings screens it also checks whether the
 * screen is about Cell Home Kids (to stop the kid uninstalling or disabling it).
 */
class GuardAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var prefs: Prefs

    private var currentPkg: String? = null
    private var allowed: Set<String> = emptySet()
    private var imes: Set<String> = emptySet()
    private var homes: Set<String> = emptySet()
    private var allowedAt = 0L

    private var usage: Map<String, Long> = emptyMap()
    private var usageAt = 0L
    @Volatile private var refreshing = false

    private var lastBlockPkg: String? = null
    private var lastBlockAt = 0L
    private var lastSyncKick = 0L
    private var lastLocationKick = 0L

    // Lowercased words that mark a Settings screen as being about this app.
    private var selfTargets: List<String> = emptyList()

    /** While the parent lock is on, re-assert it fast so no app stays in front. */
    private val lockWatch = object : Runnable {
        override fun run() {
            if (::prefs.isInitialized && prefs.screenLocked()) {
                val cp = currentPkg
                if (cp != packageName && (cp == null || cp !in lockAllowed())) {
                    LockActivity.show(this@GuardAccessibilityService)
                }
                handler.postDelayed(this, 1200)
            } else {
                handler.postDelayed(this, 4000)
            }
        }
    }

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
            if (prefs.screenLocked() && cp != packageName && (cp == null || cp !in lockAllowed())) {
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
        selfTargets = listOf(
            getString(R.string.app_name).lowercase(),
            getString(R.string.accessibility_label).lowercase(),
            packageName.lowercase(),
        )
        handler.post(tick)
        handler.post(lockWatch)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::prefs.isInitialized) return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // Notification shade and keyboards pop over the current app; ignore them.
        if (pkg == "com.android.systemui" || pkg in imes) return

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            currentPkg = pkg
            evaluate(pkg)
            return
        }
        // Content changed: cheap early-outs so we don't scan on every little UI change.
        if (prefs.screenLocked()) { evaluate(pkg); return }
        if (pkg in Apps.SETTINGS_PACKAGES && prefs.setupComplete) guardSettingsScreen(pkg)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        RulesStore.stop()
        super.onDestroy()
    }

    /** What the kid can still open while locked: calls + contacts, or nothing (full lock). */
    private fun lockAllowed(): Set<String> =
        if (prefs.lockCallsAllowed) Apps.LOCK_ALLOWED else emptySet()

    private fun evaluate(pkg: String) {
        if (!prefs.setupComplete) return
        // Switched off by Cell Home: the app controls nothing until it's enabled again.
        if (prefs.serviceDisabled) return
        // Parent hold-lock wins over everything except calls and our own lock screen.
        if (prefs.screenLocked()) {
            if (pkg !in lockAllowed() && pkg != packageName) LockActivity.show(this)
            return
        }
        val now = System.currentTimeMillis()
        if (now - allowedAt > ALLOWED_REFRESH_MS) refreshAllowed()
        val rules = RulesStore.rules
        val pausedUntil = maxOf(rules.pausedUntil, prefs.localPauseUntil)
        val reason = Enforcer.check(pkg, rules, allowed, usage, pausedUntil, now, prefs.settingsUnlockUntil, homes)
        if (reason != null) {
            block(pkg, reason)
        } else if (pkg in Apps.SETTINGS_PACKAGES) {
            // Settings is allowed (e.g. for Wi-Fi), but never let the kid reach a screen
            // that could uninstall Cell Home or turn off its permissions.
            guardSettingsScreen(pkg)
        }
        if (now - usageAt > USAGE_REFRESH_MS) refreshUsageAsync()
    }

    /**
     * Self-protection: while the kid is inside Settings / the uninstaller, if the screen is
     * about Cell Home Kids (its App info, uninstall, accessibility or device-admin screen),
     * send them back. Normal screens (Wi-Fi, Bluetooth, hotspot…) are left alone.
     */
    private fun guardSettingsScreen(pkg: String) {
        if (!windowMentionsSelf()) return
        performGlobalAction(GLOBAL_ACTION_BACK)
        handler.postDelayed({
            if (currentPkg in Apps.SETTINGS_PACKAGES && windowMentionsSelf()) {
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }, 350)
        Alerts.send(
            this, "settings_attempt", "Tried to change Cell Home Kids",
            "${prefs.deviceName} opened a Settings screen for Cell Home Kids (uninstall or permissions). It was blocked.",
            "self-protect",
        )
    }

    /** True if the current screen's text mentions this app, its service, or its package. */
    private fun windowMentionsSelf(): Boolean {
        val root = try { rootInActiveWindow } catch (e: Exception) { null } ?: return false
        return try {
            nodeContains(root, selfTargets, 0)
        } catch (e: Exception) {
            false
        } finally {
            try { root.recycle() } catch (e: Exception) {}
        }
    }

    private fun nodeContains(node: android.view.accessibility.AccessibilityNodeInfo?, targets: List<String>, depth: Int): Boolean {
        if (node == null || depth > 40) return false
        val t = ((node.text?.toString() ?: "") + " " + (node.contentDescription?.toString() ?: "")).lowercase()
        if (t.isNotBlank() && targets.any { it.isNotEmpty() && t.contains(it) }) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = nodeContains(child, targets, depth + 1)
            try { child.recycle() } catch (e: Exception) {}
            if (hit) return true
        }
        return false
    }

    private fun refreshAllowed() {
        allowed = Apps.alwaysAllowed(this)
        imes = Apps.imePackages(this)
        homes = Apps.homePackages(this)
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
