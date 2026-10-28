package com.kidguard.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager

object Apps {
    /** Settings and uninstall screens. Locked when "Lock settings" is on. */
    val SETTINGS_PACKAGES = setOf(
        "com.android.settings",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
    )

    /** Dialer / in-call / emergency: allowed to show even while the phone is held locked. */
    val CALL_PACKAGES = setOf(
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.emergency",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.android.incallui",
        "com.samsung.android.incallui",
    )

    /** Never blocked: phone calls, emergency, system UI. */
    private val SYSTEM_ALLOWED = setOf(
        "android",
        "com.android.systemui",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.emergency",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.android.incallui",
        "com.samsung.android.incallui",
    )

    /** Installed apps that have a launcher icon: (package, label), sorted by label. */
    fun launcherApps(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .filter { it.first != ctx.packageName }
            .sortedBy { it.second.lowercase() }
    }

    fun homePackages(ctx: Context): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return ctx.packageManager.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }
            .toSet() - SETTINGS_PACKAGES // Settings registers a fallback home screen; don't trust it.
    }

    fun imePackages(ctx: Context): Set<String> {
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return emptySet()
        return imm.enabledInputMethodList.map { it.packageName }.toSet()
    }

    /** Apps that are never blocked and don't count toward screen time. */
    fun alwaysAllowed(ctx: Context): Set<String> {
        val set = HashSet(SYSTEM_ALLOWED)
        set.add(ctx.packageName)
        set.addAll(homePackages(ctx))
        set.addAll(imePackages(ctx))
        val telecom = ctx.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        telecom?.defaultDialerPackage?.let { set.add(it) }
        set.removeAll(SETTINGS_PACKAGES)
        return set
    }

    @Suppress("DEPRECATION")
    fun label(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }
}
