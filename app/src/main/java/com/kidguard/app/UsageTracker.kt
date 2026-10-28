package com.kidguard.app

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object UsageTracker {
    // Event type values (named constants differ across API levels).
    private const val RESUMED = 1        // ACTIVITY_RESUMED / MOVE_TO_FOREGROUND
    private const val PAUSED = 2         // ACTIVITY_PAUSED / MOVE_TO_BACKGROUND
    private const val SCREEN_OFF = 16    // SCREEN_NON_INTERACTIVE
    private const val SHUTDOWN = 26      // DEVICE_SHUTDOWN

    fun todayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Foreground time per package since midnight, in milliseconds. Needs usage access. */
    fun todayMillis(ctx: Context): Map<String, Long> {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(startOfToday(), now)
        val totals = HashMap<String, Long>()
        var curPkg: String? = null
        var curStart = 0L

        fun close(at: Long) {
            val p = curPkg ?: return
            totals[p] = (totals[p] ?: 0L) + (at - curStart).coerceAtLeast(0L)
            curPkg = null
        }

        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                RESUMED -> if (curPkg != e.packageName) {
                    close(e.timeStamp)
                    curPkg = e.packageName
                    curStart = e.timeStamp
                }
                PAUSED -> if (curPkg == e.packageName) close(e.timeStamp)
                SCREEN_OFF, SHUTDOWN -> close(e.timeStamp)
            }
        }
        close(now) // app still open right now
        return totals
    }
}
