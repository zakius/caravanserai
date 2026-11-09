package com.zakius.caravanserai

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlin.concurrent.thread
import android.os.Build
import java.io.FilterInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The app's own updates, from its GitHub releases. Once per start, after the first frame, one HEAD request asks which
 * release is the latest (dictionary releases are never marked latest). A newer release only puts a dot on Settings and
 * a row at its top. Installing restarts the app and stops the music, so it waits for a tap. Android asks the user to
 * confirm the first install. After the app has installed itself once, Android 12 and later usually update without asking.
 */
object Update {
    private const val ACTION = "com.zakius.caravanserai.UPDATE_INSTALL"
    private const val RELEASES = "https://github.com/zakius/caravanserai/releases"

    /** The newer version out, null when this one is the latest or the check failed. */
    var available by mutableStateOf<String?>(null)
        private set
    /** What the update is doing, or why it stopped. */
    var status by mutableStateOf<String?>(null)
        private set
    private var busy = false

    fun init(app: Context) {
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = finished(c, i)
        }, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        // A debug build is a separate package (.debug), which a release APK can't update.
        if (!BuildConfig.DEBUG) Startup.later { thread(name = "update") { check() } }
    }

    private fun check() {
        val latest = roma.core.latestRelease(RELEASES) ?: return
        if (roma.core.newerVersion(latest, BuildConfig.VERSION_NAME)) postMain { available = latest }
    }

    fun install() {
        val v = available ?: return
        val app = appContext
        if (busy) return
        if (!app.packageManager.canRequestPackageInstalls()) {
            status = "Allow ${BuildConfig.APP_NAME} to install apps, then tap again"
            val packageUri = android.net.Uri.parse("package:${app.packageName}")
            val settings = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri)
            app.startActivity(settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        busy = true
        status = "Downloading ${BuildConfig.APP_NAME} $v…"
        thread(name = "update") {
            try {
                val apkUrl = "$RELEASES/download/v$v/${BuildConfig.SLUG}-$v.apk"
                installApk(app, apkUrl, app.packageName, ACTION, userActionNotRequired = true) { progress ->
                    status = "Downloading ${BuildConfig.APP_NAME} $v… $progress"
                }
                status = "Installing ${BuildConfig.APP_NAME} $v…"
            } catch (e: Exception) {
                busy = false
                status = "Couldn't get ${BuildConfig.APP_NAME} $v: ${plainError(e)}"
            }
        }
    }

    // On success Android stops the app to replace it, so only the other outcomes ever reach this.
    private fun finished(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                status = "Confirm the update"
                val confirm = IntentCompat.getParcelableExtra(i, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm != null) {
                    runCatching { c.startActivity(Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                busy = false
                status = null
            }
            else -> {
                busy = false
                status = "Couldn't update: " + (i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown reason")
            }
        }
    }
}

/**
 * Streams the APK at [url] straight into a PackageInstaller session for [pkg] and commits it. The outcome arrives as a
 * broadcast of [action]. [progress] gets "n%", or "n MB" when the server sends no length. Blocks until committed.
 */
fun installApk(app: Context, url: String, pkg: String, action: String, userActionNotRequired: Boolean = false, progress: (String) -> Unit) {
    val installer = app.packageManager.packageInstaller
    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
    params.setAppPackageName(pkg)
    if (userActionNotRequired && Build.VERSION.SDK_INT >= 31) {
        params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
    }
    val id = installer.createSession(params)
    try {
        installer.openSession(id).use { session ->
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            // A stalled download fails instead of keeping the install busy for good.
            conn.readTimeout = 30_000
            check(conn.responseCode == 200) { "The download answered HTTP ${conn.responseCode}" }
            val total = conn.contentLengthLong
            var read = 0L
            var shown = -1L
            val counted = object : FilterInputStream(conn.inputStream) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val count = super.read(b, off, len)
                    if (count > 0) read += count
                    val amount = if (total > 0) read * 100 / total else read / (1024 * 1024)
                    if (amount != shown) {
                        shown = amount
                        progress(if (total > 0) "$amount%" else "$amount MB")
                    }
                    return count
                }
            }
            session.openWrite("app.apk", 0, -1).use { out ->
                counted.use { input -> input.copyTo(out, 64 * 1024) }
                session.fsync(out)
            }
            // The installer adds its status extras to this intent, so it must be mutable.
            val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or mutable
            val outcome = Intent(action).setPackage(app.packageName)
            session.commit(PendingIntent.getBroadcast(app, id, outcome, flags).intentSender)
        }
    } catch (e: Exception) {
        runCatching { installer.abandonSession(id) }
        throw e
    }
}
