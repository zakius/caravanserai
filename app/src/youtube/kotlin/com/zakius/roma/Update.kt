package com.zakius.roma

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
