package com.zakius.roma

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

// The audio cache of the players without Media3 (the desktop's, the Subsonic app's on Android): each song's stream
// kept on disk as it downloads, so a song played once replays without the network.

/** The stream refused (HTTP [status]): a fresh URL may be needed. */
class StreamRefused(val status: Int) : IOException("The stream answered HTTP $status")

/**
 * A song's stream in the audio cache (cache/audio/<id>), filled from the front by Range requests: the first bytes
 * ahead of a tap ([fill] with a limit), the rest while it plays. Readers block until the bytes they want arrive.
 * The total length is kept beside it (<id>.len), so a complete file is known without the network.
 */
class StreamFile private constructor(val id: String) {
    private val file = File(dir, id)
    private val lenFile = File(dir, "$id.len")
    @Volatile var total: Long = runCatching { lenFile.readText().toLong() }.getOrDefault(-1L)
        private set
    @Volatile var have: Long = file.length()
        private set
    @Volatile private var failure: IOException? = null
    private val lock = Object()
    private var filling = false

    val complete get() = total >= 0 && have >= total

    /**
     * Blocking: downloads from what is there up to [limit] bytes (all with -1), unless another call already is.
     * Network errors are kept for the readers; [StreamRefused] for a refused URL.
     */
    fun fill(limit: Long = -1) {
        synchronized(lock) { if (filling || complete || (limit >= 0 && have >= limit)) return; filling = true; failure = null }
        try {
            val url = Playback.streamUrl(id)
            val c = URI(url).toURL().openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            // A Range on every request: without one some servers throttle the download to a trickle.
            c.setRequestProperty("Range", "bytes=$have-" + if (limit >= 0) "${limit - 1}" else "")
            val code = c.responseCode
            if (code == 416 && total >= 0) return
            if (code !in 200..299) throw StreamRefused(code)
            // A server that ignores Range sends the whole file: it is written from the start, with its length if it says.
            if (code == 200) synchronized(lock) { have = 0; c.contentLengthLong.takeIf { it > 0 }?.let(::setTotal) }
            c.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()?.let(::setTotal)
            dir.mkdirs()
            RandomAccessFile(file, "rw").use { out ->
                out.seek(have)
                c.inputStream.use { input ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        synchronized(lock) { have += n; lock.notifyAll() }
                    }
                }
                // A stream without a length (a transcoded one) is complete when it ends.
                if (total < 0 && limit < 0) setTotal(have)
            }
        } catch (e: IOException) {
            failure = e
        } catch (e: Exception) {
            failure = IOException(e.message, e) // a failed lookup: Streams' reason in its message
        } finally {
            synchronized(lock) { filling = false; lock.notifyAll() }
        }
    }

    private fun setTotal(n: Long) { if (total != n) { total = n; lenFile.writeText("$n") } }

    /**
     * Blocking: [n] bytes at [pos] into [buf], fewer only at the end of the stream. Waits for the download, which it
     * starts (in the background) if nothing is filling; throws the download's error once nothing more can come, and
     * [java.io.InterruptedIOException] once [stop] says the reader is gone.
     */
    fun read(pos: Long, buf: ByteArray, off: Int, n: Int, stop: () -> Boolean = { false }): Int {
        synchronized(lock) {
            while (have < pos + n && !(total >= 0 && have >= total)) {
                if (stop()) throw java.io.InterruptedIOException("$id is no longer read")
                failure?.let { failure = null; throw it }
                if (!filling) { filling = true; Thread({ synchronized(lock) { filling = false }; fill() }, "download $id").apply { isDaemon = true }.start() }
                lock.wait(500)
            }
        }
        val m = minOf(n.toLong(), have - pos).toInt()
        if (m <= 0) return 0
        RandomAccessFile(file, "r").use { it.seek(pos); it.readFully(buf, off, m) }
        return m
    }

    companion object {
        val dir get() = File(Store.cache, "audio")
        private val open = ConcurrentHashMap<String, StreamFile>()
        fun of(id: String): StreamFile = open.computeIfAbsent(id) { StreamFile(it) }

        /** ponytail: oldest files out past 1 GB, once per start, as Android's audio cache. */
        fun trim() {
            val files = dir.listFiles { f -> !f.name.endsWith(".len") }.orEmpty().sortedByDescending { it.lastModified() }
            var kept = 0L
            for (f in files) { kept += f.length(); if (kept > 1L shl 30 && !open.containsKey(f.name)) { f.delete(); File(dir, "${f.name}.len").delete() } }
        }
    }
}

/** The failure is for want of a network, which a retry may fix. */
fun noConnection(e: Throwable): Boolean = generateSequence(e) { it.cause }.any {
    it is java.net.UnknownHostException || it is java.net.ConnectException || it is java.net.SocketTimeoutException ||
        it is java.net.NoRouteToHostException || it is java.net.SocketException || (it as? roma.core.StreamException)?.offline == true
}

/**
 * [f] with Media3's patience: with no connection it is tried again every 2 s for up to 10 minutes, other errors 3
 * times, a refused URL not at all (Playback looks it up again). Null once [stop] says nobody waits any more.
 */
fun <T> retrying(stop: () -> Boolean, f: () -> T): T? {
    var tries = 0
    while (true) {
        if (stop()) return null
        try { return f() } catch (e: IOException) {
            if (e is java.io.InterruptedIOException && stop()) return null
            tries++
            if (e is StreamRefused || if (noConnection(e)) tries > 300 else tries > 3) throw e
            Thread.sleep(2_000)
        }
    }
}
