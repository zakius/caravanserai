package com.zakius.caravanserai

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
 * The Japanese dictionary, shared by every app. The app downloads roma-dict-<version>.zip (32 MB, [BuildConfig.DICT_URL],
 * built by :core:dictZip), unpacks system_core.dic (62 MB) and its notices into its own storage, and memory-maps the
 * dictionary from there once it matches [BuildConfig.DICT_SHA256]. The dictionary is data, not an installed app: Google
 * Play lets an app download data but not install apps or run downloaded code. It is never used with a source that
 * romanizes on its own ([Source.romanizes]).
 */
object Dictionary {
    const val DOWNLOAD = "32 MB download, 62 MB on disk"
    val HOW = "${BuildConfig.APP_NAME} downloads it ($DOWNLOAD)."
    // The licence notices are unpacked beside the dictionary because they must travel with it (docs/design.md,
    // Japanese dictionary).
    private const val BUFFER_SIZE = 64 * 1024
    private val NOTICES = setOf("NOTICE", "LICENSE-2.0.txt", "sudachi-LEGAL.txt")

    private val dir get() = File(Store.files, "dict")
    private val file get() = File(dir, "system_core-${BuildConfig.DICT_VERSION}.dic")

    var installed by mutableStateOf(file.exists())
        private set
    /** What the install is doing, or why it stopped; null when nothing happened yet. */
    var status by mutableStateOf<String?>(null)
        private set
    /**
     * "install", or "update" when an older version is on disk. The app asks the user first ([DictionaryPrompt]).
     * Null when there is nothing to ask.
     */
    var offer by mutableStateOf<String?>(null)
        private set
    private var busy = false

    fun map(): java.nio.ByteBuffer {
        check(file.exists()) { "The Japanese dictionary isn't installed" }
        val buffer = FileChannel.open(file.toPath()).use { channel ->
            channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
        }
        return buffer.order(ByteOrder.LITTLE_ENDIAN)
    }

    /** Called at start. When Japanese is romanized but the dictionary is missing, the app offers it once per run. */
    fun ensure() {
        installed = file.exists()
        if (installed || status != null || offer != null) return
        if (!Store.bool("setup.done", false) || "ja" in Store.readScripts || backend.romanizes) return
        val olderOnDisk = dir.list().orEmpty().any { name -> name.endsWith(".dic") }
        offer = if (olderOnDisk) "update" else "install"
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
                postMain {
                    busy = false
                    status = "Couldn't get the Japanese dictionary: ${plainError(e)}"
                }
            }
        }
    }

    // The zip goes to a .part file, so a later try resumes where an earlier one stopped. The unpacked dictionary is
    // written beside the final file, checked, and renamed into place. A zip that doesn't unpack to the expected
    // dictionary is deleted, so the next try starts over. Older versions' files are deleted once the new one is there.
    private fun fetch(progress: (String) -> Unit) {
        dir.mkdirs()
        val part = File(dir, "roma-dict-${BuildConfig.DICT_VERSION}.zip.part")
        val conn = URI(BuildConfig.DICT_URL).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        if (part.length() > 0) conn.setRequestProperty("Range", "bytes=${part.length()}-")
        // 206 means the rest of the file follows. 200 means the whole file follows, from a server without range
        // support. 416 means the part file is already complete.
        val code = conn.responseCode
        check(code == 200 || code == 206 || code == 416 && part.length() > 0) { "The download answered HTTP $code" }
        if (code != 416) {
            var read = if (code == 206) part.length() else 0L
            val length = conn.contentLengthLong
            val total = if (length > 0) read + length else -1
            var shown = -1L
            FileOutputStream(part, code == 206).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        // Without a known length, this counts megabytes instead of percent.
                        val percent = if (total > 0) read * 100 / total else read / (1024 * 1024)
                        if (percent != shown) {
                            shown = percent
                            progress(if (total > 0) "$percent%" else "$percent MB")
                        }
                    }
                }
            }
        }
        val tmp = File(dir, file.name + ".tmp")
        try {
            ZipFile(part).use { zip ->
                for (e in zip.entries()) when (e.name) {
                    in NOTICES -> zip.getInputStream(e).use { input ->
                        File(dir, e.name).outputStream().use { output -> input.copyTo(output) }
                    }
                    "system_core.dic" -> {
                        val sha = MessageDigest.getInstance("SHA-256")
                        DigestInputStream(zip.getInputStream(e), sha).use { input ->
                            tmp.outputStream().use { output -> input.copyTo(output, BUFFER_SIZE) }
                        }
                        val digest = sha.digest().joinToString("") { byte -> "%02x".format(byte) }
                        check(digest == BuildConfig.DICT_SHA256) { "The downloaded dictionary is damaged (sha256 $digest)" }
                    }
                }
            }
            check(tmp.exists()) { "No dictionary in the download" }
        } catch (e: Exception) {
            part.delete()
            tmp.delete()
            throw e
        }
        check(tmp.renameTo(file)) { "Couldn't save the dictionary" }
        val leftovers = dir.listFiles().orEmpty().filter { old -> old != file && old.name !in NOTICES }
        for (old in leftovers) old.delete()
    }
}
