package com.kidguard.app

import java.util.Calendar

enum class BlockReason { BLOCKED_APP, APP_LIMIT, DAILY_LIMIT, BEDTIME, SETTINGS_LOCKED }

/** Pure decision logic: should this app be blocked right now, and why? */
object Enforcer {
    fun check(
        pkg: String,
        rules: Rules,
        allowed: Set<String>,
        usageMs: Map<String, Long>,
        pausedUntil: Long,
        now: Long = System.currentTimeMillis(),
        settingsUnlockUntil: Long = 0L,
    ): BlockReason? {
        if (pkg in allowed) return null
        if (pausedUntil > now) return null
        if (pkg in Apps.SETTINGS_PACKAGES) {
            if (!rules.lockSettings) return null
            if (settingsUnlockUntil > now) return null
            return BlockReason.SETTINGS_LOCKED
        }
        if (isBedtime(rules.bedtime, now)) return BlockReason.BEDTIME
        if (pkg in rules.blockedApps) return BlockReason.BLOCKED_APP
        rules.appLimits[pkg]?.let { limit ->
            if ((usageMs[pkg] ?: 0L) >= limit * 60_000L) return BlockReason.APP_LIMIT
        }
        if (rules.dailyLimitMinutes > 0) {
            val total = usageMs.filterKeys { it !in allowed && it !in Apps.SETTINGS_PACKAGES }.values.sum()
            if (total >= rules.dailyLimitMinutes * 60_000L) return BlockReason.DAILY_LIMIT
        }
        return null
    }

    fun isBedtime(b: Bedtime, now: Long = System.currentTimeMillis()): Boolean {
        if (!b.enabled) return false
        val start = parse(b.start) ?: return false
        val end = parse(b.end) ?: return false
        if (start == end) return false
        val c = Calendar.getInstance().apply { timeInMillis = now }
        val cur = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        // e.g. 21:00 -> 07:00 crosses midnight
        return if (start < end) cur in start until end else cur >= start || cur < end
    }

    private fun parse(s: String): Int? {
        val parts = s.split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }
}
