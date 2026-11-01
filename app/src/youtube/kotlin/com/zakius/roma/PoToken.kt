package com.zakius.roma

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import roma.core.PoTokenResult
import roma.core.TokenProvider

/** Android's PoTokens: BotGuard in the system WebView (10 s for a cold start: WebView, BotGuard, first token). */
fun platformPoTokens() = PoTokens(10_000L) { PoTokenView.create(appContext) }

private class PoTokenException(message: String) : Exception(message)

/** One BotGuard session in a WebView: created with [create], then [mint] per identifier until [expired]. */
private class PoTokenView private constructor(context: Context, private val ready: Continuation<PoTokenView>) : BotGuardSession {
    private val webView = WebView(context)
    private val main = Handler(Looper.getMainLooper())
    private val started = AtomicBoolean(false) // [ready] resumed
    private val pending = ConcurrentHashMap<String, Continuation<String>>()
    private val counter = AtomicLong()
    private var expiresAt = Long.MAX_VALUE
    @Volatile private var closed = false
    @Volatile override var dead = false
        private set
    override val expired get() = System.currentTimeMillis() > expiresAt

    init {
        @Suppress("SetJavaScriptEnabled")
        webView.settings.javaScriptEnabled = true
        webView.settings.userAgentString = BotGuard.USER_AGENT
        webView.settings.blockNetworkLoads = true // BotGuard's requests go through OkHttp below
        webView.addJavascriptInterface(this, JS)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if ("Uncaught" in m.message()) {
                    // Before the page is up, a broken WebView; after, a transient error in Google's own script.
                    fail(if (started.get()) PoTokenException(m.message()) else BotGuardBroken(m.message()))
                }
                return true
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                fail(PoTokenException("WebView render process gone"))
                return true // keep the app alive
            }
        }
    }

    private fun load() {
        val html = webView.context.assets.open("po_token.html").bufferedReader().use { it.readText() }
        val page = html.replaceFirst("</script>", "\n$JS.downloadAndRunBotguard()</script>")
        webView.loadDataWithBaseURL("https://www.youtube.com", page, "text/html", "utf-8", null)
    }

    @JavascriptInterface
    fun downloadAndRunBotguard() = botguard("https://www.youtube.com/api/jnn/v1/Create", "[ \"${BotGuard.REQUEST_KEY}\" ]") { body ->
        val challenge = BotGuard.parseChallenge(body)
        js("""try {
            data = $challenge
            runBotGuard(data).then(function (result) {
                this.webPoSignalOutput = result.webPoSignalOutput
                $JS.onRunBotguardResult(result.botguardResponse)
            }, function (error) { $JS.onJsInitializationError(error + "\n" + error.stack) })
        } catch (error) { $JS.onJsInitializationError(error + "\n" + error.stack) }""")
    }

    @JavascriptInterface
    fun onRunBotguardResult(response: String) = botguard("https://www.youtube.com/api/jnn/v1/GenerateIT", "[ \"${BotGuard.REQUEST_KEY}\", \"$response\" ]") { body ->
        val (integrity, expires) = BotGuard.integrity(body)
        expiresAt = expires
        js("""try {
            this.integrityToken = $integrity
            createPoTokenMinter(webPoSignalOutput, integrityToken).then(function() { $JS.onMinterCreated() })
                .catch(function(error) { $JS.onJsInitializationError(error + "\n" + (error.stack || '')) })
        } catch (error) { $JS.onJsInitializationError(error + "\n" + error.stack) }""")
    }

    @JavascriptInterface
    fun onJsInitializationError(error: String) = fail(if ("SyntaxError" in error) BotGuardBroken(error) else PoTokenException(error))

    @JavascriptInterface
    fun onMinterCreated() {
        if (started.compareAndSet(false, true)) ready.resume(this)
    }

    override suspend fun mint(identifier: String): String {
        if (dead || closed) throw PoTokenException("PoToken WebView is gone")
        val key = "$identifier#${counter.incrementAndGet()}" // concurrent calls for one video keep their own answer
        return try {
            withTimeout(MINT_TIMEOUT_MS) {
                withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine { cont ->
                        pending[key] = cont
                        js("""(function() {
                            var key = "$key"
                            try {
                                obtainPoToken(${BotGuard.u8Array(identifier.toByteArray())}).then(function(u8) { $JS.onToken(key, u8.join(",")) })
                                    .catch(function(error) { $JS.onTokenError(key, error + "\n" + (error.stack || '')) })
                            } catch (error) { $JS.onTokenError(key, error + "\n" + error.stack) }
                        })()""")
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            dead = true
            pending.remove(key)
            throw PoTokenException("PoToken timed out")
        }
    }

    @JavascriptInterface
    fun onToken(key: String, u8: String) {
        val cont = pending.remove(key) ?: return
        runCatching { BotGuard.token(u8) }.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
    }

    @JavascriptInterface
    fun onTokenError(key: String, error: String) {
        pending.remove(key)?.resumeWithException(PoTokenException(error))
    }

    private fun js(code: String) = main.post { if (!closed) webView.evaluateJavascript(code, null) }

    private fun botguard(url: String, body: String, then: (String) -> Unit) {
        Thread {
            try {
                then(BotGuard.request(url, body))
            } catch (e: Exception) {
                fail(e)
            }
        }.start()
    }

    private fun fail(e: Exception) {
        dead = true
        close()
        if (started.compareAndSet(false, true)) runCatching { ready.resumeWithException(e) }
        pending.keys.toList().forEach { k -> pending.remove(k)?.let { runCatching { it.resumeWithException(e) } } }
    }

    override fun close() {
        if (closed) return
        closed = true
        main.post { runCatching { webView.loadUrl("about:blank"); webView.destroy() } }
    }

    companion object {
        private const val JS = "PoTokenWebView"
        private const val INIT_TIMEOUT_MS = 30_000L
        private const val MINT_TIMEOUT_MS = 5_000L

        suspend fun create(context: Context): PoTokenView {
            var created: PoTokenView? = null
            try {
                return withTimeout(INIT_TIMEOUT_MS) {
                    withContext(Dispatchers.Main) {
                        suspendCancellableCoroutine { cont -> created = PoTokenView(context, cont).also { it.load() } }
                    }
                }
            } catch (e: Exception) {
                // Not withContext: when an outer timeout cancelled us it would throw at once and leak the WebView.
                created?.let { it.started.set(true); it.close() }
                throw if (e is TimeoutCancellationException) PoTokenException("PoToken WebView didn't start") else e
            }
        }
    }
}
