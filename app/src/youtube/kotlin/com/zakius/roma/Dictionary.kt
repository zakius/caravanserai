package com.zakius.roma

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import java.io.FilterInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

/**
 * The Japanese dictionary is its own app (the :dict-app module): slimmed SudachiDict, 62 MB stored uncompressed, installed once
 * next to roma, so roma's updates stay small and nothing is unpacked into storage. roma downloads it gzipped (about
 * 31 MB, [BuildConfig.DICT_URL]) straight into a PackageInstaller session; Android asks the user to confirm. It is used
 * only when it is signed with roma's own key and is at least [BuildConfig.DICT_VERSION].
 */
object Dictionary {
    const val DOWNLOAD = "31 MB download, 62 MB on the phone"
    /** How it is installed, for roma's question (DictionaryPrompt). */
    const val HOW = "It's a separate app ($DOWNLOAD). Android will ask you to confirm the installation."
    private const val ACTION = "com.zakius.roma.DICTIONARY_INSTALL"

    var installed by mutableStateOf(false)
        private set
    /** What the install is doing, or why it stopped; null when nothing happened yet. */
    var status by mutableStateOf<String?>(null)
        private set
    private var busy = false
    // Sent to Android's "install unknown apps" switch; checked again when roma comes back (MainActivity.onResume).
    private var askedPermission = false
    // Android's confirmation screen, kept in case it couldn't open while roma was in the background.
    private var confirm: Intent? = null

    fun init(app: Context) {
        // Two package manager calls, off the main thread; only the UI reads the result.
        kotlin.concurrent.thread(name = "dictionary") {
            val ok = runCatching { verify(app) }.isSuccess
            android.os.Handler(android.os.Looper.getMainLooper()).post { installed = ok }
        }
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = finished(c, i)
        }, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun verify(c: Context) {
        val pm = c.packageManager
        check(pm.checkSignatures(c.packageName, BuildConfig.DICT_PACKAGE) == PackageManager.SIGNATURE_MATCH) { "The Japanese dictionary isn't installed" }
        val p = pm.getPackageInfo(BuildConfig.DICT_PACKAGE, 0)
        @Suppress("DEPRECATION") val version = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()
        check(version >= BuildConfig.DICT_VERSION) { "The Japanese dictionary is out of date" }
    }

    /** The dictionary, mapped from the dictionary app's APK; throws if it is missing, out of date or not signed by roma's key. */
    fun map(): java.nio.ByteBuffer {
        verify(appContext)
        val fd = appContext.createPackageContext(BuildConfig.DICT_PACKAGE, 0).assets.openFd("system_core.dic")
        return fd.createInputStream().channel.use { it.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.length) }.order(java.nio.ByteOrder.LITTLE_ENDIAN)
    }

    fun install() {
        val app = appContext
        val c = app
        confirm?.let { runCatching { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; return }
        if (busy || installed) return
        // Asked first: Android's own detour to this switch cancels the install, which would read as a refusal.
        if (!app.packageManager.canRequestPackageInstalls()) {
            askedPermission = true
            status = "Allow ${BuildConfig.APP_NAME} to install apps, then come back"
            c.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        busy = true
        status = "Downloading the Japanese dictionary…"
        thread(name = "dictionary") {
            try {
                installApk(app, BuildConfig.DICT_URL, BuildConfig.DICT_PACKAGE, gzip = true, ACTION) { status = "Downloading the Japanese dictionary… $it" }
                status = "Installing the Japanese dictionary…"
            } catch (e: Exception) {
                busy = false
                status = "Couldn't get the Japanese dictionary: ${plainError(e)}"
            }
        }
    }

    /** "install" or "update": roma asks first ([DictionaryPrompt]) before Android's own prompts. Null: nothing to ask. */
    var offer by mutableStateOf<String?>(null)
        private set

    /**
     * Japanese is romanized but the dictionary is missing or older than this roma needs (after an update of roma):
     * roma offers it, once per run. Not now, a refusal or a failure leaves it to Settings.
     */
    fun ensure(c: Context) {
        installed = runCatching { verify(c) }.isSuccess
        if (installed || status != null || offer != null || !Store.bool("setup.done", false) || "ja" in Store.readScripts) return
        val present = runCatching { c.packageManager.getPackageInfo(BuildConfig.DICT_PACKAGE, 0) }.isSuccess
        offer = if (present) "update" else "install"
    }

    fun answer(yes: Boolean) {
        offer = null
        if (yes) install() else status = "Not installed: kanji stay as written. Tap to install (${DOWNLOAD})"
    }

    /** Back from the "install unknown apps" switch: on, the install goes on; off counts as declining. */
    fun resumed(c: Context) {
        if (!askedPermission) return
        askedPermission = false
        if (c.packageManager.canRequestPackageInstalls()) install() else declined()
    }

    private fun declined() {
        // Japanese shows as written, until the user turns it on again in Settings.
        confirm = null
        busy = false
        status = "Not installed: Japanese shows as written"
        Store.setReads("ja", true)
    }

    private fun finished(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                confirm = IntentCompat.getParcelableExtra(i, Intent.EXTRA_INTENT, Intent::class.java)
                status = "Confirm the installation"
                confirm?.let { runCatching { c.startActivity(Intent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                confirm = null
                busy = false
                installed = true
                status = null
                Store.romanizer.retryDictionary()
                thread(name = "sudachi") { Store.romanizer.warmUp() }
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> declined()
            else -> {
                confirm = null
                busy = false
                status = "Couldn't install the Japanese dictionary: " + (i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown reason")
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
