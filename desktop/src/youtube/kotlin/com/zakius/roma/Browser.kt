package com.zakius.roma

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A Chromium for what needs a real browser on the desktop: BotGuard's PoTokens and the YouTube sign-in, the jobs
 * Android's WebView does. It is the system's Chromium-family browser, started with roma's own profile (never the
 * user's) and driven over the DevTools protocol: the one that worked last time first, else every known browser in
 * [order] (oganesson first), else CEF, downloaded once on first need (jcefmaven, about 150 MB). Other engines can't
 * be used: BotGuard's tokens from JavaFX's WebKit are refused.
 */
object Browser {
    private val windows = System.getProperty("os.name").startsWith("Windows")
    private const val WORKED = "browser.worked"

    /** The Chromium-family browsers roma looks for, in order. Paths that don't exist are skipped. */
    fun order(): List<String> = if (windows) {
        val local = System.getenv("LOCALAPPDATA") ?: ""
        val pf = System.getenv("ProgramFiles") ?: "C:\\Program Files"
        val pf86 = System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)"
        listOf(
            "$local\\oganesson\\Application\\chrome.exe", "$pf\\oganesson\\Application\\chrome.exe",
            "$local\\imput\\Helium\\Application\\chrome.exe",
            "$local\\Chromium\\Application\\chrome.exe",
            "$pf\\Google\\Chrome\\Application\\chrome.exe", "$pf86\\Google\\Chrome\\Application\\chrome.exe", "$local\\Google\\Chrome\\Application\\chrome.exe",
            "$pf\\BraveSoftware\\Brave-Browser\\Application\\brave.exe", "$local\\BraveSoftware\\Brave-Browser\\Application\\brave.exe",
            "$local\\Vivaldi\\Application\\vivaldi.exe", "$pf\\Vivaldi\\Application\\vivaldi.exe",
            "$local\\Programs\\Opera\\opera.exe", "$local\\Programs\\Opera GX\\opera.exe",
            "$pf86\\Microsoft\\Edge\\Application\\msedge.exe", "$pf\\Microsoft\\Edge\\Application\\msedge.exe",
        )
    } else {
        val path = (System.getenv("PATH") ?: "").split(':') + listOf("/usr/bin", "/usr/local/bin", "/opt/google/chrome")
        listOf(
            "oganesson", "helium", "chromium", "chromium-browser", "google-chrome-stable", "google-chrome", "chrome",
            "brave-browser", "brave", "vivaldi-stable", "vivaldi", "opera", "microsoft-edge-stable", "microsoft-edge",
        ).flatMap { name -> path.map { "$it/$name" } }.filter { File(it).canExecute() }.distinctBy { File(it).canonicalPath }
    }

    /**
     * A browser for [profile], [headless] or in a window: the one that worked last time, else the first in [order]
     * that starts and passes [test] (BotGuard's first token), else CEF, only when no system browser starts at all.
     * The one that worked is remembered. A failing browser is closed and the next one tried. When all fail (often the
     * network, not the browsers), the next minute's calls fail at once instead of starting every browser again.
     */
    suspend fun <T> start(profile: String, headless: Boolean, test: suspend (Chrome) -> T): Pair<Chrome, T> {
        if (System.currentTimeMillis() - failedAt < 60_000) error("No browser worked a moment ago")
        val last = Store.str(WORKED, "")
        val found = order().filter { File(it).canExecute() }
        val tries = (listOf(last).filter { it in found } + found.filter { it != last }).distinct()
        var started = false
        for (exe in tries + "cef") {
            if (exe == "cef" && started) break
            val chrome = try {
                if (exe == "cef") Cef.start() else Chrome.launch(exe, profileDir(profile), headless)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("roma browser: $exe didn't start: ${e.message}")
                continue
            }
            started = true
            try {
                val result = test(chrome)
                if (exe != last) postMain { Store.set(WORKED, exe) }
                return chrome to result
            } catch (e: CancellationException) {
                chrome.close()
                throw e
            } catch (e: Exception) {
                println("roma browser: $exe failed: $e") // which browser, for the log; no tokens
                chrome.close()
            }
        }
        failedAt = System.currentTimeMillis()
        error(if (started) "BotGuard failed in every browser" else "No Chromium-family browser started, and CEF isn't ready")
    }

    @Volatile private var failedAt = 0L

    fun profileDir(name: String) = File(Dirs.files, "browser/$name")
}

/**
 * One Chromium over the DevTools protocol: a browser process roma started ([launch]) or CEF ([Cef]). [page] is its
 * first tab.
 */
class Chrome(private val process: Process?, private val cdp: Cdp, private val onClose: () -> Unit = {}) : AutoCloseable {
    /** The first page target, attached; for CEF the browser it created, for a launched browser its first tab. */
    suspend fun page(urlPrefix: String = ""): Tab {
        // A browser just started (CEF's window) may not have its page yet.
        var t: JsonObject? = null
        for (i in 0 until 100) {
            val targets = cdp.call("Target.getTargets")["targetInfos"]!!.jsonArray.map { it.jsonObject }
            t = targets.firstOrNull { it.str("type") == "page" && it.str("url").startsWith(urlPrefix) } ?: run { delay(100); null } ?: continue
            break
        }
        t ?: error("No page")
        val session = cdp.call("Target.attachToTarget", buildJsonObject { put("targetId", t.str("targetId")); put("flatten", true) }).str("sessionId")
        return Tab(cdp, session, t.str("url"))
    }

    /** The URLs of the open pages. */
    suspend fun urls(): List<String> =
        cdp.call("Target.getTargets")["targetInfos"]!!.jsonArray.map { it.jsonObject }.filter { it.str("type") == "page" }.map { it.str("url") }

    val alive get() = cdp.open && (process?.isAlive ?: true)

    override fun close() {
        runCatching { cdp.close() }
        process?.let { p -> running -= p; p.descendants().forEach { it.destroy() }; p.destroy() }
        onClose()
    }

    companion object {
        /** Starts [exe] with [profile] as its user data and connects; the port is read from DevToolsActivePort. */
        suspend fun launch(exe: String, profile: File, headless: Boolean): Chrome = withContext(Dispatchers.IO) {
            profile.mkdirs()
            val portFile = File(profile, "DevToolsActivePort").also { it.delete() }
            val args = mutableListOf(exe, "--remote-debugging-port=0", "--user-data-dir=${profile.path}", "--no-first-run", "--no-default-browser-check")
            if (headless) args += "--headless=new"
            args += "about:blank"
            val p = ProcessBuilder(args).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            running += p
            try {
                var waited = 0
                while (!portFile.exists() || portFile.readLines().size < 2) {
                    if (!p.isAlive || waited > 10_000) error("$exe didn't open its DevTools port")
                    delay(100); waited += 100
                }
                val (port, path) = portFile.readLines()
                Chrome(p, Cdp.connect("ws://127.0.0.1:$port$path"))
            } catch (t: Throwable) {
                p.destroy()
                throw t
            }
        }

        fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull ?: ""

        // The browsers roma started go when roma does.
        private val running = java.util.Collections.newSetFromMap(ConcurrentHashMap<Process, Boolean>())
            .also { set -> Runtime.getRuntime().addShutdownHook(Thread { set.forEach { p -> p.descendants().forEach { it.destroy() }; p.destroy() } }) }
    }
}

/** A tab of a [Chrome], attached through a flat session. */
class Tab(private val cdp: Cdp, private val session: String, val url: String) {
    suspend fun call(method: String, params: JsonObject = JsonObject(emptyMap())) = cdp.call(method, params, session)

    /** Evaluates [expression] (a promise is awaited) and returns its value, as JSON. Throws on a JavaScript error. */
    suspend fun eval(expression: String): JsonElement? {
        val r = call("Runtime.evaluate", buildJsonObject { put("expression", expression); put("awaitPromise", true); put("returnByValue", true) })
        r["exceptionDetails"]?.let { throw IllegalStateException("JavaScript: " + (it.jsonObject["exception"]?.jsonObject?.get("description") ?: it)) }
        return r["result"]?.jsonObject?.get("value")
    }

    /**
     * Opens [url] with [html] as its page, served by roma, not fetched: the page gets [url]'s origin (BotGuard wants
     * youtube.com's) without a request leaving the machine.
     */
    suspend fun serve(url: String, html: String) {
        call("Fetch.enable", buildJsonObject { put("patterns", buildJsonArray { add(buildJsonObject { put("urlPattern", url) }) }) })
        val served = CompletableFuture<Unit>()
        cdp.on("Fetch.requestPaused", session) { e ->
            val body = java.util.Base64.getEncoder().encodeToString(html.toByteArray())
            cdp.send("Fetch.fulfillRequest", buildJsonObject {
                put("requestId", e.str("requestId")); put("responseCode", 200); put("body", body)
                put("responseHeaders", buildJsonArray { add(buildJsonObject { put("name", "Content-Type"); put("value", "text/html; charset=utf-8") }) })
            }, session)
            served.complete(Unit)
        }
        call("Page.navigate", buildJsonObject { put("url", url) })
        served.await()
        while (eval("document.readyState")?.jsonPrimitive?.contentOrNull != "complete") delay(50)
    }

    /** The cookies the browser would send to [url], as a Cookie header. */
    suspend fun cookies(url: String): String =
        call("Network.getCookies", buildJsonObject { put("urls", buildJsonArray { add(JsonPrimitive(url)) }) })["cookies"]!!.jsonArray
            .joinToString("; ") { c -> c.jsonObject.let { "${it.str("name")}=${it.str("value")}" } }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull ?: ""
}

/** The DevTools protocol over the JDK's WebSocket: calls by id, events by method (and session). */
class Cdp private constructor(private val ws: WebSocket, private val listener: Listener) : AutoCloseable {
    private val ids = AtomicInteger()
    val open get() = !listener.closed

    suspend fun call(method: String, params: JsonObject = JsonObject(emptyMap()), session: String? = null): JsonObject =
        send(method, params, session).await()

    fun send(method: String, params: JsonObject, session: String? = null): CompletableFuture<JsonObject> {
        val id = ids.incrementAndGet()
        val f = CompletableFuture<JsonObject>()
        listener.pending[id] = f
        val msg = buildJsonObject { put("id", id); put("method", method); put("params", params); session?.let { put("sessionId", it) } }
        synchronized(ws) { ws.sendText(msg.toString(), true).join() }
        if (listener.closed) f.completeExceptionally(IllegalStateException("DevTools connection closed"))
        return f
    }

    fun on(method: String, session: String?, handler: (JsonObject) -> Unit) { listener.events["$method $session"] = handler }

    override fun close() { runCatching { ws.sendClose(WebSocket.NORMAL_CLOSURE, "").join() } }

    private class Listener : WebSocket.Listener {
        val pending = ConcurrentHashMap<Int, CompletableFuture<JsonObject>>()
        val events = ConcurrentHashMap<String, (JsonObject) -> Unit>()
        @Volatile var closed = false
        private val text = StringBuilder()

        override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            text.append(data)
            if (last) {
                val msg = Json.parseToJsonElement(text.toString()).jsonObject
                text.setLength(0)
                val id = (msg["id"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
                if (id != null) {
                    val f = pending.remove(id)
                    msg["error"]?.let { f?.completeExceptionally(IllegalStateException("DevTools: $it")) } ?: f?.complete(msg["result"]?.jsonObject ?: JsonObject(emptyMap()))
                } else {
                    val method = (msg["method"] as? JsonPrimitive)?.contentOrNull
                    val session = (msg["sessionId"] as? JsonPrimitive)?.contentOrNull
                    events["$method $session"]?.let { h -> Thread { h(msg["params"]!!.jsonObject) }.start() }
                }
            }
            ws.request(1)
            return null
        }

        override fun onClose(ws: WebSocket, status: Int, reason: String?): CompletionStage<*>? { gone(); return null }
        override fun onError(ws: WebSocket, error: Throwable) = gone()

        private fun gone() {
            closed = true
            pending.values.forEach { it.completeExceptionally(IllegalStateException("DevTools connection closed")) }
            pending.clear()
        }
    }

    companion object {
        suspend fun connect(url: String): Cdp {
            val listener = Listener()
            val ws = HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(URI(url), listener).await()
            return Cdp(ws, listener)
        }
    }
}

/**
 * CEF, the last resort, all of it downloaded on first need, none of it in the package: its Java side (jcefmaven and
 * what it uses, about 3 MB of jars from Maven Central, each checked against the SHA-256 pinned at build time,
 * BuildConfig.CEF_JARS) and CEF itself (about 150 MB, which jcefmaven fetches). It runs in a process of its own,
 * [CefHost] on roma's Java with those jars: CEF must start before anything in the process opens an AWT or X11
 * window. roma drives it over DevTools like any other browser.
 */
object Cef {
    val installDir get() = File(Dirs.files, "cef")
    private val installed get() = File(installDir, "install.lock").exists()
    /** What the download is doing, for the sign-in page; null when nothing happens. */
    @Volatile var status: String? = null
        private set
    @Volatile private var installing = false

    /** Blocking: CEF's jars, downloaded unless they are there. */
    private fun jars(): List<File> {
        val dir = File(Dirs.files, "cef-java").also { it.mkdirs() } // not in installDir, which jcefmaven empties
        return BuildConfig.CEF_JARS.split(';').map { entry ->
            val (path, sha256) = entry.split(' ')
            val jar = File(dir, path.substringAfterLast('/'))
            if (!jar.exists()) {
                status = "Downloading Chromium (CEF)…"
                val bytes = URI("https://repo1.maven.org/maven2/" + path.replace("+", "%2B")).toURL().readBytes()
                val got = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
                check(got == sha256) { "${jar.name} doesn't match its checksum" }
                File(dir, jar.name + ".tmp").also { it.writeBytes(bytes) }.renameTo(jar)
            }
            jar
        }
    }

    /**
     * roma again with --cef-host (Main.kt), which runs [CefHost] with CEF's jars: through the packaged launcher (its
     * Java has no `java` command), or through `java` and the class path (a run from Gradle).
     */
    private fun host(vararg args: String): Process {
        val jars = jars().joinToString(File.pathSeparator) { it.path }
        val java = File(System.getProperty("java.home"), "bin/" + if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val self = if (java.canExecute()) listOf(java.path, "-cp", System.getProperty("java.class.path"), "com.zakius.roma.MainKt")
            else listOf(ProcessHandle.current().info().command().get())
        return ProcessBuilder(self + listOf("--cef-host", jars) + args).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    }

    /** Blocking: jars and CEF, unless they are there; the host reports its download as it goes. */
    fun install() {
        jars()
        if (installed) return
        val p = host("--install")
        p.inputStream.bufferedReader().forEachLine { if (it.startsWith("status ")) status = it.removePrefix("status ").ifEmpty { null } }
        status = null
        check(p.waitFor() == 0 && installed) { "CEF didn't install" }
    }

    /**
     * CEF in its own process: a hidden page, or a window showing [url]. Before CEF is there, a hidden page fails at
     * once and the download starts in the background: a stream lookup can't wait for 150 MB, so that song goes
     * without a token. A window (sign-in) waits for the download.
     */
    suspend fun start(url: String? = null): Chrome = withContext(Dispatchers.IO) {
        if (!installed) {
            if (url == null) {
                if (!installing) { installing = true; Thread({ runCatching { install() }; installing = false }, "cef install").apply { isDaemon = true }.start() }
                error("CEF is downloading")
            }
            install()
        }
        val p = host(*listOfNotNull(url).toTypedArray())
        try {
            val out = p.inputStream.bufferedReader()
            // A host that says nothing for 20 s is ended, which ends the read.
            val watchdog = Thread { runCatching { Thread.sleep(20_000); p.destroy() } }.apply { isDaemon = true; start() }
            val line = generateSequence { out.readLine() }.firstOrNull { it.startsWith("DevTools port ") } ?: error("CEF didn't start")
            watchdog.interrupt()
            val port = line.removePrefix("DevTools port ").toInt()
            val json = URI("http://127.0.0.1:$port/json/version").toURL().readText()
            Chrome(p, Cdp.connect(Json.parseToJsonElement(json).jsonObject["webSocketDebuggerUrl"]!!.jsonPrimitive.content))
        } catch (t: Throwable) {
            p.destroy()
            throw t
        }
    }
}

/**
 * BotGuard in a hidden Chromium page: po_token.html served at youtube.com's origin, then the same steps as Android's
 * WebView (PoToken.kt), the page's functions called through [Tab.eval]. Closed after 5 idle minutes, so the
 * browser doesn't stay in memory; the next song needing a token starts it again (about 2 s).
 */
class ChromeBotGuard private constructor(private val chrome: Chrome, private val page: Tab, private val expiresAt: Long) : BotGuardSession {
    @Volatile private var closed = false
    @Volatile private var used = System.currentTimeMillis()
    override val expired get() = System.currentTimeMillis() > expiresAt
    override val dead get() = closed || !chrome.alive

    init {
        Thread({
            while (!closed) {
                Thread.sleep(30_000)
                if (System.currentTimeMillis() - used > 5 * 60_000) close()
            }
        }, "botguard idle").apply { isDaemon = true }.start()
    }

    override suspend fun mint(identifier: String): String {
        used = System.currentTimeMillis()
        val u8 = page.eval("obtainPoToken(${BotGuard.u8Array(identifier.toByteArray())}).then(u => Array.from(u).join(','))")
        return BotGuard.token(u8!!.jsonPrimitive.content)
    }

    override fun close() {
        if (closed) return
        closed = true
        chrome.close()
    }

    companion object {
        private val html by lazy { ChromeBotGuard::class.java.getResource("/po_token.html")!!.readText() }

        /** A session in the first browser that can run BotGuard (Browser.start); its first step is the test. */
        suspend fun start(): BotGuardSession {
            return Browser.start("botguard", headless = true) { chrome -> open(chrome) }.second
        }

        internal suspend fun open(chrome: Chrome): ChromeBotGuard {
            val page = chrome.page()
            page.serve("https://www.youtube.com/roma-botguard", html)
            val challenge = withContext(Dispatchers.IO) {
                BotGuard.parseChallenge(BotGuard.request("https://www.youtube.com/api/jnn/v1/Create", "[ \"${BotGuard.REQUEST_KEY}\" ]"))
            }
            val response = page.eval("runBotGuard($challenge).then(r => { window.webPoSignalOutput = r.webPoSignalOutput; return r.botguardResponse })")!!.jsonPrimitive.content
            val (integrity, expires) = withContext(Dispatchers.IO) {
                BotGuard.integrity(BotGuard.request("https://www.youtube.com/api/jnn/v1/GenerateIT", "[ \"${BotGuard.REQUEST_KEY}\", \"$response\" ]"))
            }
            page.eval("createPoTokenMinter(window.webPoSignalOutput, $integrity).then(() => '')")
            return ChromeBotGuard(chrome, page, expires)
        }
    }
}

/**
 * The YouTube Music sign-in, in a browser window with roma's own sign-in profile. Google refuses to sign in a browser
 * whose DevTools port is open ("This browser or app may not be secure"), so a system browser's window has none: once
 * it closes, a hidden browser on the same profile reads the account's cookies (as Android's WebView sign-in does).
 * Without a system browser, CEF's window, watched until it reaches music.youtube.com. Returns false when the window
 * closed without a sign-in.
 */
suspend fun browserSignIn(): Boolean {
    val url = "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"
    val profile = Browser.profileDir("signin")
    var chrome: Chrome? = null
    var window: Process? = null
    try {
        val found = Browser.order().filter { File(it).canExecute() }
        val exe = (listOf(Store.str("browser.worked", "")).filter { it in found } + found).firstOrNull()
        if (exe != null) {
            profile.mkdirs()
            window = withContext(Dispatchers.IO) {
                ProcessBuilder(exe, "--user-data-dir=${profile.path}", "--no-first-run", "--no-default-browser-check", url)
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            }
            // A wrapper script may hand over to the browser and end: the profile's lock (Linux, "<host>-<pid>") names
            // the browser. A browser that was killed leaves its lock behind, so the process is what counts.
            val lock = File(profile, "SingletonLock").toPath()
            fun browser() = runCatching { java.nio.file.Files.readSymbolicLink(lock).toString().substringAfterLast('-').toLong() }
                .getOrNull()?.let { ProcessHandle.of(it).isPresent } == true
            while (window.isAlive || browser()) delay(500)
            chrome = Chrome.launch(exe, profile, headless = true)
            return take(chrome.page())
        }
        val c = Cef.start(url).also { chrome = it }
        var music: Tab? = null
        while (c.alive) {
            delay(1_000)
            if (c.urls().none { it.startsWith("https://music.youtube.com") }) continue
            if (take(music ?: c.page("https://music.youtube.com").also { music = it })) return true
        }
        return false // the window was closed
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // No browser started, CEF couldn't, or the window went mid-call.
        toast((if (chrome == null && window == null) "Couldn't open a browser to sign in: " else "Couldn't sign in: ") + e.message, long = true)
        return false
    } finally {
        window?.destroy()
        chrome?.close()
    }
}

/**
 * Signs in with [page]'s browser's YouTube Music cookies, when they hold an account's. The visitor and account ids come
 * from music.youtube.com's page fetched with them: a headless browser's copy of the page never sets them.
 */
private suspend fun take(page: Tab): Boolean {
    val cookie = page.cookies("https://music.youtube.com")
    if ("SAPISID" !in cookie) return false
    val html = withContext(Dispatchers.IO) {
        runCatching {
            java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI("https://music.youtube.com/")).header("Cookie", cookie)
                    .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36").build(),
                java.net.http.HttpResponse.BodyHandlers.ofString(),
            ).body()
        }.getOrDefault("")
    }
    fun id(key: String) = Regex("\"$key\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)
    withContext(Dispatchers.Main) { signedIn(cookie, id("VISITOR_DATA"), id("DATASYNC_ID")) }
    return true
}
