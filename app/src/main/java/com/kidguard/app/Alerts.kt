package com.kidguard.app

import android.content.Context
import com.google.firebase.firestore.FieldValue

/** Writes alerts to parents/{uid}/alerts for the dashboard. */
object Alerts {
    fun send(ctx: Context, type: String, title: String, message: String, onceTodayKey: String? = null) {
        val prefs = Prefs(ctx)
        val uid = prefs.parentUid ?: return
        if (onceTodayKey != null && !prefs.firstTimeToday(onceTodayKey)) return
        Fire.alertsCol(uid).add(
            mapOf(
                "deviceId" to prefs.deviceId,
                "deviceName" to prefs.deviceName,
                "type" to type,
                "title" to title,
                "message" to message,
                "createdAt" to FieldValue.serverTimestamp(),
                "read" to false,
            )
        )
    }

    fun forBlock(ctx: Context, reason: BlockReason, pkg: String, label: String) {
        val name = Prefs(ctx).deviceName
        when (reason) {
            BlockReason.BLOCKED_APP -> send(
                ctx, "blocked_attempt", "Tried to open $label",
                "$name tried to open $label, which is blocked.", "blocked:$pkg",
            )
            BlockReason.APP_LIMIT -> send(
                ctx, "app_limit", "Time limit reached: $label",
                "$name used up today's time for $label.", "applimit:$pkg",
            )
            BlockReason.DAILY_LIMIT -> send(
                ctx, "daily_limit", "Daily screen time used up",
                "$name reached today's screen time limit.", "daily",
            )
            BlockReason.BEDTIME -> send(
                ctx, "bedtime_attempt", "Phone use at bedtime",
                "$name tried to open $label during bedtime.", "bedtime",
            )
            BlockReason.SETTINGS_LOCKED -> send(
                ctx, "settings_attempt", "Tried to open Settings",
                "$name tried to open Settings or uninstall an app.", "settings",
            )
        }
    }
}
