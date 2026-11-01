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

/** A body over its limit; the same request would fail the same way, so it isn't retried. */
/** No stream for a song. The message is the reason in plain words; [offline]: for want of a network, so worth retrying. */
class StreamException(message: String, val offline: Boolean = false) : IOException(message)

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
    maxBytes: Int = 4 shl 20,
    errorBody: Boolean = true,
    redirects: Boolean = true,
    stopWhen: ((String) -> Boolean)? = null,
): HttpResponse {
    val c = open(url, headers, method, timeoutMs, redirects)
    return withTimeoutOrNull(totalMs) { coroutineScope {
        val done = AtomicBoolean()
        // Blocking reads don't see cancellation; disconnecting from here ends them. Undispatched, so a cancellation
        // before it would first run still reaches the finally block.
        val watch = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { if (!done.get()) c.disconnect() } }
        try {
            withContext(Dispatchers.IO) { exchange(c, body, maxBytes, errorBody, stopWhen) }
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw e
        } finally {
            done.set(true)
            watch.cancel()
        }
    } } ?: throw SocketTimeoutException("$url took over $totalMs ms")
}

/** A blocking request: the status code and the body, an error's body included. */
fun httpCall(url: String, body: ByteArray? = null, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 20_000): Pair<Int, String> {
    val r = exchange(open(url, headers, if (body == null) "GET" else "POST", timeoutMs, true), body, 4 shl 20, true, null)
    return r.status to r.text()
}

/** The body of a successful request; throws on an HTTP error. */
fun httpText(url: String, body: ByteArray? = null, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 20_000): String {
    val (code, text) = httpCall(url, body, headers, timeoutMs)
    if (code !in 200..299) throw IOException("HTTP $code")
    return text
}

/** [url] when it is HTTPS on the default port without user info, and [host] and [path] accept its host and path. */
fun httpsUrl(url: String, host: (String) -> Boolean, path: (String) -> Boolean): URI? = runCatching { URI(url) }.getOrNull()?.takeIf {
    it.scheme == "https" && (it.port == -1 || it.port == 443) && it.rawUserInfo == null && it.host?.let(host) == true && it.rawPath?.let(path) == true
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
        var bytes = ByteArray(minOf(maxOf(declared, 8192L), maxBytes.toLong(), if (stopWhen != null) 64L shl 10 else Long.MAX_VALUE).toInt())
        var size = 0
        var nextCheck = 32 shl 10
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
                // Ends the download: closing a half-read stream drops the connection instead of reading the rest.
                if (stopWhen(prefix)) { c.disconnect(); return HttpResponse(status, c.headerFields, prefix.encodeToByteArray()) }
            }
        }
        return HttpResponse(status, c.headerFields, if (size == bytes.size) bytes else bytes.copyOf(size))
    }
}
