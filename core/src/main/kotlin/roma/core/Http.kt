package roma.core

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/** A response: the status, the headers and the body, which is empty when it wasn't read. */
class HttpResponse(val status: Int, private val headers: Map<String?, List<String>>, val body: ByteArray) {
    val ok get() = status in 200..299
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    fun text() = body.decodeToString()
}

/** No stream for a song. The message is the reason in plain words; [offline]: for want of a network, so worth retrying. */
class StreamException(message: String, val offline: Boolean = false) : IOException(message)

/** A body over its limit; the same request would fail the same way, so it isn't retried. */
class ResponseTooLargeException(maxBytes: Int) : IllegalStateException("Response exceeded the $maxBytes byte limit")

/**
 * A request through HttpURLConnection (on Android, its built-in HTTP stack with transparent gzip). The body is read up
 * to [maxBytes]; [stopWhen], checked at doubling sizes, ends the read early with what came so far. An error's body is
 * read only with [errorBody]; redirects are followed only with [redirects]. [totalMs] bounds the whole request, as
 * [timeoutMs] bounds connecting and each read; past it, a SocketTimeoutException. Cancelling the coroutine disconnects.
 */
suspend fun http(
    url: String,
    headers: Map<String, String> = emptyMap(),
    body: ByteArray? = null,
    method: String = if (body == null) "GET" else "POST",
    timeoutMs: Int = 30_000,
    totalMs: Long = 60_000,
    maxBytes: Int = 4 * 1024 * 1024,
    errorBody: Boolean = true,
    redirects: Boolean = true,
    stopWhen: ((String) -> Boolean)? = null,
): HttpResponse {
    val c = open(url, headers, method, timeoutMs, redirects)
    val response = withTimeoutOrNull(totalMs) {
        coroutineScope {
            val done = AtomicBoolean()
            // Blocking reads don't see cancellation, so disconnecting from here is what ends them. The watcher starts
            // undispatched so that a cancellation arriving before it would first run still reaches its finally block.
            val watch = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    if (!done.get()) c.disconnect()
                }
            }
            try {
                withContext(Dispatchers.IO) { exchange(c, body, maxBytes, errorBody, stopWhen) }
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                throw e
            } finally {
                done.set(true)
                watch.cancel()
            }
        }
    }
    return response ?: throw SocketTimeoutException("$url took over $totalMs ms")
}

/** A blocking request: the status code and the body, an error's body included, up to [maxBytes]. */
fun httpCall(url: String, body: ByteArray? = null, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 20_000, maxBytes: Int = 4 * 1024 * 1024): Pair<Int, String> {
    val method = if (body == null) "GET" else "POST"
    val connection = open(url, headers, method, timeoutMs, true)
    val r = exchange(connection, body, maxBytes, true, null)
    return r.status to r.text()
}

/** The body of a successful request; throws on an HTTP error. */
fun httpText(url: String, body: ByteArray? = null, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 20_000): String {
    val (code, text) = httpCall(url, body, headers, timeoutMs)
    if (code !in 200..299) throw IOException("HTTP $code")
    return text
}

/** [url] when it is HTTPS on the default port without user info, and [host] and [path] accept its host and path. */
fun httpsUrl(url: String, host: (String) -> Boolean, path: (String) -> Boolean): URI? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    val defaultPort = uri.port == -1 || uri.port == 443
    if (uri.scheme != "https" || !defaultPort || uri.rawUserInfo != null) return null
    val uriHost = uri.host
    if (uriHost == null || !host(uriHost)) return null
    val uriPath = uri.rawPath
    if (uriPath == null || !path(uriPath)) return null
    return uri
}

private fun open(url: String, headers: Map<String, String>, method: String, timeoutMs: Int, redirects: Boolean) =
    (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = timeoutMs
        readTimeout = timeoutMs
        instanceFollowRedirects = redirects
        headers.forEach(::setRequestProperty)
    }

private fun exchange(c: HttpURLConnection, body: ByteArray?, maxBytes: Int, errorBody: Boolean, stopWhen: ((String) -> Boolean)?): HttpResponse {
    if (body != null) {
        c.doOutput = true
        c.outputStream.use { it.write(body) }
    }
    val status = c.responseCode
    val stream = if (status < 400) c.inputStream else c.errorStream
    if (stream == null || (!errorBody && status !in 200..299)) {
        stream?.close()
        return HttpResponse(status, c.headerFields, ByteArray(0))
    }
    stream.use { input ->
        val declared = c.contentLengthLong
        if (stopWhen == null && declared > maxBytes) throw ResponseTooLargeException(maxBytes)
        // A read that stops early usually ends well before a full page's declared length, so it grows into it instead.
        val earlyStopCap = if (stopWhen != null) 64L * 1024 else Long.MAX_VALUE
        val initialSize = minOf(maxOf(declared, 8192L), maxBytes.toLong(), earlyStopCap)
        var bytes = ByteArray(initialSize.toInt())
        var size = 0
        var nextCheck = 32 * 1024
        while (true) {
            if (size == bytes.size) {
                if (size == maxBytes) {
                    if (input.read() == -1) break
                    throw ResponseTooLargeException(maxBytes)
                }
                bytes = bytes.copyOf(minOf(maxBytes.toLong(), size * 2L).toInt())
            }
            val n = input.read(bytes, size, bytes.size - size)
            if (n == -1) break
            size += n
            if (stopWhen != null && size >= nextCheck) {
                nextCheck = size * 2
                val prefix = bytes.decodeToString(endIndex = size)
                if (stopWhen(prefix)) {
                    // Disconnecting ends the download. Closing a half-read stream drops the connection instead of
                    // reading the rest.
                    c.disconnect()
                    return HttpResponse(status, c.headerFields, prefix.encodeToByteArray())
                }
            }
        }
        val read = if (size == bytes.size) bytes else bytes.copyOf(size)
        return HttpResponse(status, c.headerFields, read)
    }
}
