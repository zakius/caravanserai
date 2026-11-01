package com.zakius.roma

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.FilterInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/**
 * The Japanese dictionary on the desktop and in the Subsonic app on Android: the same download as roma's dictionary
 * app (roma-dict-<version>.apk.gz, 31 MB), whose system_core.dic is taken out once into the app's own storage (62 MB)
 * and memory-mapped from there. Nothing else of the APK is kept, and nothing is installed: on Google Play an app may
 * download data but not install apps or run downloaded code. Never with a source that romanizes ([Source.romanizes]).
 */
object Dictionary {
    const val DOWNLOAD = "31 MB download, 62 MB on disk"
    val HOW = "${BuildConfig.APP_NAME} downloads it ($DOWNLOAD)."

    private val file get() = File(Store.files, "dict/system_core-${BuildConfig.DICT_VERSION}.dic")

    var installed by mutableStateOf(file.exists())
        private set
    /** What the install is doing, or why it stopped; null when nothing happened yet. */
    var status by mutableStateOf<String?>(null)
        private set
    /** "install": roma asks first ([DictionaryPrompt]). Null: nothing to ask. */
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
        offer = "install"
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

    // Streams the gzipped APK and keeps only its dictionary entry, written beside and renamed into place; older
    // versions' files go once the new one is there.
    private fun fetch(progress: (String) -> Unit) {
        val conn = URI(BuildConfig.DICT_URL).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        check(conn.responseCode == 200) { "The download answered HTTP ${conn.responseCode}" }
        val total = conn.contentLengthLong
        var read = 0L
        var shown = -1L
        val counted = object : FilterInputStream(conn.inputStream) {
            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, len).also {
                if (it > 0) read += it
                val percent = if (total > 0) read * 100 / total else read shr 20
                if (percent != shown) { shown = percent; progress(if (total > 0) "$percent%" else "$percent MB") }
            }
        }
        val dir = file.parentFile.also { it.mkdirs() }
        val tmp = File(dir, file.name + ".tmp")
        ZipInputStream(GZIPInputStream(counted, 1 shl 16)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: error("No dictionary in the download")
                if (e.name == "assets/system_core.dic") { tmp.outputStream().use { zip.copyTo(it, 1 shl 16) }; break }
            }
        }
        check(tmp.renameTo(file)) { "Couldn't save the dictionary" }
        dir.listFiles()?.filter { it != file }?.forEach { it.delete() }
    }
}
