package com.zakius.roma

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.concurrent.thread

/**
 * The Japanese dictionary, in every app: data, not an app. roma downloads roma-dict-<version>.zip (32 MB,
 * [BuildConfig.DICT_URL], built by :core:dictZip), unpacks system_core.dic (62 MB) and its notices into its own storage
 * and memory-maps the dictionary from there, once it matches [BuildConfig.DICT_SHA256]. Nothing is installed: on Google
 * Play an app may download data but not install apps or run downloaded code. Never with a source that romanizes
 * ([Source.romanizes]).
 */
object Dictionary {
    const val DOWNLOAD = "32 MB download, 62 MB on disk"
    val HOW = "${BuildConfig.APP_NAME} downloads it ($DOWNLOAD)."
    // Unpacked beside the dictionary: they must travel with it (docs/design.md, Japanese dictionary).
    private val NOTICES = setOf("NOTICE", "LICENSE-2.0.txt", "sudachi-LEGAL.txt")

    private val dir get() = File(Store.files, "dict")
    private val file get() = File(dir, "system_core-${BuildConfig.DICT_VERSION}.dic")

    var installed by mutableStateOf(file.exists())
        private set
    /** What the install is doing, or why it stopped; null when nothing happened yet. */
    var status by mutableStateOf<String?>(null)
        private set
    /** "install" or "update" (an older version is on disk): roma asks first ([DictionaryPrompt]). Null: nothing to ask. */
    var offer by mutableStateOf<String?>(null)
        private set
    private var busy = false

    fun map(): java.nio.ByteBuffer {
        check(file.exists()) { "The Japanese dictionary isn't installed" }
        return FileChannel.open(file.toPath()).use { it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()) }.order(ByteOrder.LITTLE_ENDIAN)
    }

    /** At start: Japanese is romanized but the dictionary is missing, so roma offers it once per run. */
    fun ensure() {
        installed = file.exists()
        if (installed || status != null || offer != null || !Store.bool("setup.done", false) || "ja" in Store.readScripts || backend.romanizes) return
        offer = if (dir.list().orEmpty().any { it.endsWith(".dic") }) "update" else "install"
    }

    fun answer(yes: Boolean) {
        offer = null
        if (yes) install() else status = "Not installed: kanji stay as written. Tap to install ($DOWNLOAD)"
    }

    fun install() {
        if (busy || installed || backend.romanizes) return
        busy = true
        status = "Downloading the Japanese dictionary…"
        thread(name = "dictionary", isDaemon = true) {
            try {
                fetch { postMain { status = "Downloading the Japanese dictionary… $it" } }
                postMain {
                    busy = false
                    installed = true
                    status = null
                    Store.romanizer.retryDictionary()
                    thread(name = "sudachi", isDaemon = true) { Store.romanizer.warmUp() }
                }
            } catch (e: Exception) {
                postMain { busy = false; status = "Couldn't get the Japanese dictionary: ${plainError(e)}" }
            }
        }
    }

    // The zip goes to a .part file, resumed where an earlier try stopped. Unpacked, the dictionary is written beside,
    // checked and renamed into place; a zip that doesn't unpack to the expected dictionary goes, so the next try starts
    // over. Older versions' files go once the new one is there.
    private fun fetch(progress: (String) -> Unit) {
        val part = File(dir.also { it.mkdirs() }, "roma-dict-${BuildConfig.DICT_VERSION}.zip.part")
        val conn = URI(BuildConfig.DICT_URL).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        if (part.length() > 0) conn.setRequestProperty("Range", "bytes=${part.length()}-")
        // 206: the rest follows; 200: the whole file (a server without ranges); 416: the part is already whole.
        val code = conn.responseCode
        check(code == 200 || code == 206 || code == 416 && part.length() > 0) { "The download answered HTTP $code" }
        if (code != 416) {
            var read = if (code == 206) part.length() else 0L
            val total = conn.contentLengthLong.let { if (it > 0) read + it else -1 }
            var shown = -1L
            FileOutputStream(part, code == 206).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf).takeIf { it >= 0 } ?: break
                        out.write(buf, 0, n)
                        read += n
                        val percent = if (total > 0) read * 100 / total else read shr 20
                        if (percent != shown) { shown = percent; progress(if (total > 0) "$percent%" else "$percent MB") }
                    }
                }
            }
        }
        val tmp = File(dir, file.name + ".tmp")
        try {
            ZipFile(part).use { zip ->
                for (e in zip.entries()) when (e.name) {
                    in NOTICES -> zip.getInputStream(e).use { input -> File(dir, e.name).outputStream().use { input.copyTo(it) } }
                    "system_core.dic" -> {
                        val sha = MessageDigest.getInstance("SHA-256")
                        DigestInputStream(zip.getInputStream(e), sha).use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
                        val digest = sha.digest().joinToString("") { "%02x".format(it) }
                        check(digest == BuildConfig.DICT_SHA256) { "The downloaded dictionary is damaged (sha256 $digest)" }
                    }
                }
            }
            check(tmp.exists()) { "No dictionary in the download" }
        } catch (e: Exception) {
            part.delete(); tmp.delete()
            throw e
        }
        check(tmp.renameTo(file)) { "Couldn't save the dictionary" }
        dir.listFiles()?.filter { it != file && it.name !in NOTICES }?.forEach { it.delete() }
    }
}
