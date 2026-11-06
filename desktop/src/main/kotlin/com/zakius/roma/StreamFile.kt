package com.zakius.roma

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

// The desktop's audio cache: each song's stream kept on disk as it downloads, so a song played once replays without
// the network.

/** The server refused the stream with HTTP [status]. A fresh URL may be needed. */
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
     * Blocking. Downloads from the end of what is on disk up to [limit] bytes, or to the end with -1, unless another
     * call already is downloading. Network errors, and [StreamRefused] for a refused URL, are kept for the readers.
     */
    fun fill(limit: Long = -1) {
        synchronized(lock) {
            if (filling || complete || (limit >= 0 && have >= limit)) return
            filling = true
            failure = null
        }
        try {
            val url = Playback.streamUrl(id)
            val c = URI(url).toURL().openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            // A Range on every request: without one some servers throttle the download to a trickle.
            c.setRequestProperty("Range", "bytes=$have-" + if (limit >= 0) "${limit - 1}" else "")
            val code = c.responseCode
            // setTotal writes the length beside the file, so the folder must exist first.
            dir.mkdirs()
            // The total after the slash in "Content-Range: bytes 0-99/N" (or "bytes */N" on a 416).
            val rangeTotal = c.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()
            // 416 means the request started past the end. The file is whole if the server's length is what is here.
            if (code == 416 && rangeTotal != null) setTotal(rangeTotal)
            if (code == 416 && total in 0..have) return
            if (code !in 200..299) throw StreamRefused(code)
            // A server that ignores Range sends the whole file, so it is written from the start, with its length if
            // the server gives one.
            if (code == 200) synchronized(lock) {
                have = 0
                val length = c.contentLengthLong
                if (length > 0) setTotal(length)
            }
            if (rangeTotal != null) setTotal(rangeTotal)
            RandomAccessFile(file, "rw").use { out ->
                out.seek(have)
                c.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        synchronized(lock) {
                            have += n
                            lock.notifyAll()
                        }
                    }
                }
                // A stream without a length (a transcoded one) is complete when it ends.
                if (total < 0 && limit < 0) setTotal(have)
            }
        } catch (e: IOException) {
            failure = e
        } catch (e: Exception) {
            // A failed stream lookup. Its message carries the reason Streams gave.
            failure = IOException(e.message, e)
        } finally {
            synchronized(lock) {
                filling = false
                lock.notifyAll()
            }
        }
    }

    private fun setTotal(n: Long) {
        if (total == n) return
        total = n
        lenFile.writeText("$n")
    }

    /**
     * Blocking. Reads [n] bytes at [pos] into [buf], fewer only at the end of the stream. Waits for the download, and
     * starts it in the background if nothing is filling. Throws the download's error once nothing more can come, and
     * [java.io.InterruptedIOException] once [stop] says the reader is gone.
     */
    fun read(pos: Long, buf: ByteArray, off: Int, n: Int, stop: () -> Boolean = { false }): Int {
        synchronized(lock) {
            while (have < pos + n && !(total >= 0 && have >= total)) {
                if (stop()) throw java.io.InterruptedIOException("$id is no longer read")
                val failed = failure
                if (failed != null) {
                    failure = null
                    throw failed
                }
                if (!filling) {
                    // Claimed here so the next pass of this loop doesn't start a second download. The thread lets go
                    // just before fill() claims it again.
                    filling = true
                    val download = Thread({
                        synchronized(lock) { filling = false }
                        fill()
                    }, "download $id")
                    download.isDaemon = true
                    download.start()
                }
                lock.wait(500)
            }
        }
        val m = minOf(n.toLong(), have - pos).toInt()
        if (m <= 0) return 0
        RandomAccessFile(file, "r").use { f ->
            f.seek(pos)
            f.readFully(buf, off, m)
        }
        return m
    }

    companion object {
        val dir get() = File(Store.cache, "audio")
        private val open = ConcurrentHashMap<String, StreamFile>()
        fun of(id: String): StreamFile = open.computeIfAbsent(id) { StreamFile(it) }

        /** Every song out of the cache, on disk and in memory. */
        fun clear() {
            open.clear()
            dir.deleteRecursively()
        }

        private const val MAX_BYTES = 1024L * 1024 * 1024

        /** ponytail: deletes the oldest files past 1 GB, once per start, as Android's audio cache does. */
        fun trim() {
            val songs = dir.listFiles { f -> !f.name.endsWith(".len") }.orEmpty()
            val newestFirst = songs.sortedByDescending { it.lastModified() }
            var kept = 0L
            for (f in newestFirst) {
                kept += f.length()
                if (kept > MAX_BYTES && !open.containsKey(f.name)) {
                    f.delete()
                    File(dir, "${f.name}.len").delete()
                }
            }
        }
    }
}

/** The failure is for want of a network, which a retry may fix. */
fun noConnection(e: Throwable): Boolean = generateSequence(e) { it.cause }.any { cause ->
    cause is java.net.UnknownHostException || cause is java.net.ConnectException || cause is java.net.SocketTimeoutException ||
        cause is java.net.NoRouteToHostException || cause is java.net.SocketException ||
        (cause as? roma.core.StreamException)?.offline == true
}

/** Tries with no connection: 10 minutes at one try every 2 s. */
private const val OFFLINE_TRIES = 300

/** Tries for any other error, as Media3's default. */
private const val OTHER_TRIES = 3

/**
 * [f] with Media3's patience: with no connection it is tried again every 2 s for up to 10 minutes, other errors 3
 * times, a refused URL not at all (Playback looks it up again). Null once [stop] says nobody waits any more.
 */
fun <T> retrying(stop: () -> Boolean, f: () -> T): T? {
    var tries = 0
    while (true) {
        if (stop()) return null
        try {
            return f()
        } catch (e: IOException) {
            if (e is java.io.InterruptedIOException && stop()) return null
            tries++
            if (e is StreamRefused) throw e
            val limit = if (noConnection(e)) OFFLINE_TRIES else OTHER_TRIES
            if (tries > limit) throw e
            Thread.sleep(2_000)
        }
    }
}
