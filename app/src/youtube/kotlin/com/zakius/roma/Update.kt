package com.zakius.roma

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
import java.util.zip.GZIPInputStream

/**
 * roma's own updates, from its GitHub releases. Once per start, after the first frame, one HEAD request asks which
 * release is the latest (the dictionary's are never marked latest). A newer one only puts a dot on Settings and a row
 * at its top: installing restarts roma and stops the music, so it waits for a tap. Android asks to confirm the first
 * time; after roma installed itself once, Android 12+ usually updates without asking.
 */
object Update {
    private const val ACTION = "com.zakius.roma.UPDATE_INSTALL"
    private const val RELEASES = "https://github.com/zakius/roma/releases"

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
        // A debug build is another package (.debug): a release APK wouldn't update it.
        if (!BuildConfig.DEBUG) Startup.later { thread(name = "update") { check() } }
    }

    private fun check() {
        val latest = roma.core.latestRelease(RELEASES) ?: return
        if (roma.core.newerVersion(latest, BuildConfig.VERSION_NAME)) postMain { available = latest }
    }

    fun install() {
        val v = available ?: return
        val app = appContext
        val c = app
        if (busy) return
        if (!app.packageManager.canRequestPackageInstalls()) {
            status = "Allow roma to install apps, then tap again"
            c.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        busy = true
        status = "Downloading roma $v…"
        thread(name = "update") {
            try {
                installApk(app, "$RELEASES/download/v$v/roma-$v.apk", app.packageName, gzip = false, ACTION, userActionNotRequired = true) { status = "Downloading roma $v… $it" }
                status = "Installing roma $v…"
            } catch (e: Exception) {
                busy = false
                status = "Couldn't get roma $v: ${plainError(e)}"
            }
        }
    }

    // On success Android stops roma to replace it, so only the other outcomes are ever seen here.
    private fun finished(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                status = "Confirm the update"
                IntentCompat.getParcelableExtra(i, Intent.EXTRA_INTENT, Intent::class.java)?.let { runCatching { c.startActivity(Intent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED -> { busy = false; status = null }
            else -> {
                busy = false
                status = "Couldn't update: " + (i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown reason")
            }
        }
    }
}

/**
 * Streams the APK at [url] (gzipped when [gzip]) straight into a PackageInstaller session for [pkg] and commits it;
 * the outcome arrives as a broadcast of [action]. [progress] gets "n%" (or "n MB" without a length). Blocking.
 */
fun installApk(app: Context, url: String, pkg: String, gzip: Boolean, action: String, userActionNotRequired: Boolean = false, progress: (String) -> Unit) {
    val installer = app.packageManager.packageInstaller
    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
    params.setAppPackageName(pkg)
    if (userActionNotRequired && Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
    val id = installer.createSession(params)
    try {
        installer.openSession(id).use { session ->
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000 // a stalled download fails instead of keeping the install busy for good
            check(conn.responseCode == 200) { "The download answered HTTP ${conn.responseCode}" }
            val total = conn.contentLengthLong
            var read = 0L
            var shown = -1L
            val counted = object : FilterInputStream(conn.inputStream) {
                override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, len).also {
                    if (it > 0) read += it
                    val percent = if (total > 0) read * 100 / total else read shr 20
                    if (percent != shown) {
                        shown = percent
                        progress(if (total > 0) "$percent%" else "$percent MB")
                    }
                }
            }
            session.openWrite("app.apk", 0, -1).use { out ->
                (if (gzip) GZIPInputStream(counted, 1 shl 16) else counted).use { it.copyTo(out, 1 shl 16) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(app, id, Intent(action).setPackage(app.packageName), flags).intentSender)
        }
    } catch (e: Exception) {
        runCatching { installer.abandonSession(id) }
        throw e
    }
}
