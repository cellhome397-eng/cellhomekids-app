package com.kidguard.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Over-the-air update: the app checks a small version file on the Cell Home site and,
 * if a newer build is published, downloads the APK and opens the installer — no USB cable.
 * The phone still shows one "install this update?" prompt (Android requires it unless the
 * app is device owner), so one tap on the phone finishes it.
 */
object Updater {
    private const val MANIFEST_URL =
        "https://raw.githubusercontent.com/cellhome397-eng/cellhomekids-app/main/kids-version.json"
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    data class Info(val code: Int, val name: String, val url: String)

    /** Checks in the background; calls back on the main thread with an update if one is newer. */
    fun checkAsync(ctx: Context, onResult: (Info?) -> Unit) {
        exec.execute {
            val info = fetch()
            val newer = info != null && info.code > BuildConfig.VERSION_CODE
            main.post { onResult(if (newer) info else null) }
        }
    }

    private fun fetch(): Info? = try {
        val c = (URL(MANIFEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 10_000
        }
        c.inputStream.bufferedReader().use { r ->
            val o = JSONObject(r.readText())
            Info(o.getInt("versionCode"), o.optString("versionName", ""), o.getString("url"))
        }
    } catch (e: Exception) {
        Log.w("Cell Home Kids", "Update check failed", e); null
    }

    /** Downloads the APK then opens the installer. [status] reports progress on the main thread. */
    fun downloadAndInstall(ctx: Context, info: Info, status: (String) -> Unit) {
        exec.execute {
            try {
                main.post { status("Downloading update…") }
                val out = File(ctx.cacheDir, "update.apk")
                val c = (URL(info.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000; readTimeout = 30_000
                }
                c.inputStream.use { inp -> out.outputStream().use { outp -> inp.copyTo(outp) } }
                main.post { install(ctx, out, status) }
            } catch (e: Exception) {
                Log.w("Cell Home Kids", "Update download failed", e)
                main.post { status("Update failed. Check the internet and try again.") }
            }
        }
    }

    private fun install(ctx: Context, apk: File, status: (String) -> Unit) {
        // From Android 8, the app needs permission to install apps.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ctx.packageManager.canRequestPackageInstalls()) {
            status("Allow Cell Home Kids to install updates, then tap Update again.")
            try {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                Log.w("Cell Home Kids", "Can't open install-permission screen", e)
            }
            return
        }
        try {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            status("Opening the installer…")
        } catch (e: Exception) {
            Log.w("Cell Home Kids", "Install intent failed", e)
            status("Couldn't open the installer.")
        }
    }
}
