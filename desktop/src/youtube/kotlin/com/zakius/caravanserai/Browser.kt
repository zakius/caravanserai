package com.zakius.caravanserai

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
 * A Chromium for the desktop jobs that need a real browser, which Android's WebView does on Android: BotGuard's
 * PoTokens and the YouTube sign-in. It is the system's Chromium-family browser, started with the app's own profile (never
 * the user's) and driven over the DevTools protocol. The app tries the browser that worked last time first, then every
 * known browser in [order] (oganesson first), then CEF, which is downloaded once when first needed (jcefmaven, about
 * 150 MB). Other engines can't be used, because BotGuard's tokens from JavaFX's WebKit are refused.
 */
object Browser {
    private val windows = System.getProperty("os.name").startsWith("Windows")
    private const val WORKED = "browser.worked"

    /** The Chromium-family browsers the app looks for, in order. Paths that don't exist are skipped. */
    fun order(): List<String> = if (windows) {
        val local = System.getenv("LOCALAPPDATA") ?: ""
        val pf = System.getenv("ProgramFiles") ?: "C:\\Program Files"
        val pf86 = System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)"
        listOf(
            "$local\\oganesson\\Application\\chrome.exe", "$pf\\oganesson\\Application\\chrome.exe",
            "$local\\imput\\Helium\\Application\\chrome.exe",
            "$local\\Chromium\\Application\\chrome.exe",
            "$pf\\Google\\Chrome\\Application\\chrome.exe",
            "$pf86\\Google\\Chrome\\Application\\chrome.exe",
            "$local\\Google\\Chrome\\Application\\chrome.exe",
            "$pf\\BraveSoftware\\Brave-Browser\\Application\\brave.exe",
            "$local\\BraveSoftware\\Brave-Browser\\Application\\brave.exe",
            "$local\\Vivaldi\\Application\\vivaldi.exe", "$pf\\Vivaldi\\Application\\vivaldi.exe",
            "$local\\Programs\\Opera\\opera.exe", "$local\\Programs\\Opera GX\\opera.exe",
            "$pf86\\Microsoft\\Edge\\Application\\msedge.exe", "$pf\\Microsoft\\Edge\\Application\\msedge.exe",
        )
    } else {
        val path = (System.getenv("PATH") ?: "").split(':') + listOf("/usr/bin", "/usr/local/bin", "/opt/google/chrome")
        val names = listOf(
            "oganesson", "helium", "chromium", "chromium-browser", "google-chrome-stable", "google-chrome", "chrome",
            "brave-browser", "brave", "vivaldi-stable", "vivaldi", "opera", "microsoft-edge-stable", "microsoft-edge",
        )
        val candidates = names.flatMap { name -> path.map { dir -> "$dir/$name" } }
        candidates.filter { File(it).canExecute() }.distinctBy { File(it).canonicalPath }
    }

    /**
     * Starts a browser for [profile], [headless] or in a window. It tries the one that worked last time, then each one
     * in [order], and keeps the first that starts and passes [test] (BotGuard's first token). CEF is tried only when no
     * system browser starts at all. The one that worked is remembered. A failing browser is closed and the next one
     * tried. When all fail, which is often the network rather than the browsers, calls in the next minute fail at once
     * instead of starting every browser again.
     */
    suspend fun <T> start(profile: String, headless: Boolean, test: suspend (Chrome) -> T): Pair<Chrome, T> {
        if (System.currentTimeMillis() - failedAt < 60_000) error("No browser worked a moment ago")
        val last = Store.str(WORKED, "")
        val found = order().filter { File(it).canExecute() }
        val lastFirst = if (last in found) listOf(last) else emptyList()
        val tries = (lastFirst + found.filter { it != last }).distinct()
        var started = false
        for (exe in tries + "cef") {
            if (exe == "cef" && started) break
            val chrome = try {
                if (exe == "cef") Cef.start() else Chrome.launch(exe, profileDir(profile), headless)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("dragoman browser: $exe didn't start: ${e.message}")
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
                // Names the browser for the log. Nothing here logs a token.
                println("dragoman browser: $exe failed: $e")
                chrome.close()
            }
        }
        failedAt = System.currentTimeMillis()
        val reason = if (started) "BotGuard failed in every browser" else "No Chromium-family browser started, and CEF isn't ready"
        error(reason)
    }

    @Volatile private var failedAt = 0L

    fun profileDir(name: String) = File(Dirs.files, "browser/$name")
}

/**
 * One Chromium over the DevTools protocol: a browser process the app started ([launch]) or CEF ([Cef]). [page] is its
 * first tab.
 */
class Chrome(private val process: Process?, private val cdp: Cdp, private val onClose: () -> Unit = {}) : AutoCloseable {
    /** Attaches to the first page target: for CEF the browser it created, for a launched browser its first tab. */
    suspend fun page(urlPrefix: String = ""): Tab {
        // A browser that has just started (CEF's window) may not have its page yet, so this asks for 10 s.
        var t: JsonObject? = null
        for (i in 0 until 100) {
            val targets = cdp.call("Target.getTargets")["targetInfos"]!!.jsonArray.map { it.jsonObject }
            t = targets.firstOrNull { it.str("type") == "page" && it.str("url").startsWith(urlPrefix) }
            if (t != null) break
            delay(100)
        }
        t ?: error("No page")
        val params = buildJsonObject {
            put("targetId", t.str("targetId"))
            put("flatten", true)
        }
        val session = cdp.call("Target.attachToTarget", params).str("sessionId")
        return Tab(cdp, session, t.str("url"))
    }

    /** The URLs of the open pages. */
    suspend fun urls(): List<String> {
        val targets = cdp.call("Target.getTargets")["targetInfos"]!!.jsonArray.map { it.jsonObject }
        return targets.filter { it.str("type") == "page" }.map { it.str("url") }
    }

    val alive get() = cdp.open && (process?.isAlive ?: true)

    override fun close() {
        runCatching { cdp.close() }
        val p = process
        if (p != null) {
            running -= p
            p.descendants().forEach { it.destroy() }
            p.destroy()
        }
        onClose()
    }

    companion object {
        /** Starts [exe] with [profile] as its user data and connects to it, on the port it writes to DevToolsActivePort. */
        suspend fun launch(exe: String, profile: File, headless: Boolean): Chrome = withContext(Dispatchers.IO) {
            profile.mkdirs()
            val portFile = File(profile, "DevToolsActivePort")
            portFile.delete()
            val args = mutableListOf(
                exe,
                "--remote-debugging-port=0",
                "--user-data-dir=${profile.path}",
                "--no-first-run",
                "--no-default-browser-check",
            )
            if (headless) args += "--headless=new"
            args += "about:blank"
            val p = ProcessBuilder(args).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            running += p
            try {
                var waited = 0
                while (!portFile.exists() || portFile.readLines().size < 2) {
                    if (!p.isAlive || waited > 10_000) error("$exe didn't open its DevTools port")
                    delay(100)
                    waited += 100
                }
                val (port, path) = portFile.readLines()
                Chrome(p, Cdp.connect("ws://127.0.0.1:$port$path"))
            } catch (t: Throwable) {
                p.destroy()
                throw t
            }
        }

        fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull ?: ""

        // The browsers the app started are ended when the app ends.
        private val running = java.util.Collections.newSetFromMap(ConcurrentHashMap<Process, Boolean>())

        init {
            Runtime.getRuntime().addShutdownHook(Thread {
                for (p in running) {
                    p.descendants().forEach { it.destroy() }
                    p.destroy()
                }
            })
        }
    }
}

/** A tab of a [Chrome], attached through a flat session. */
class Tab(private val cdp: Cdp, private val session: String, val url: String) {
    suspend fun call(method: String, params: JsonObject = JsonObject(emptyMap())) = cdp.call(method, params, session)

    /** Evaluates [expression] (a promise is awaited) and returns its value, as JSON. Throws on a JavaScript error. */
    suspend fun eval(expression: String): JsonElement? {
        val params = buildJsonObject {
            put("expression", expression)
            put("awaitPromise", true)
            put("returnByValue", true)
        }
        val r = call("Runtime.evaluate", params)
        val error = r["exceptionDetails"]
        if (error != null) {
            val description = error.jsonObject["exception"]?.jsonObject?.get("description") ?: error
            throw IllegalStateException("JavaScript: $description")
        }
        return r["result"]?.jsonObject?.get("value")
    }

    /**
     * Opens [url] with [html] as its page, served by the app instead of fetched. The page gets [url]'s origin (BotGuard
     * wants youtube.com's) without a request leaving the machine.
     */
    suspend fun serve(url: String, html: String) {
        val pattern = buildJsonObject { put("urlPattern", url) }
        call("Fetch.enable", buildJsonObject { put("patterns", buildJsonArray { add(pattern) }) })
        val served = CompletableFuture<Unit>()
        cdp.on("Fetch.requestPaused", session) { e ->
            val body = java.util.Base64.getEncoder().encodeToString(html.toByteArray())
            val contentType = buildJsonObject {
                put("name", "Content-Type")
                put("value", "text/html; charset=utf-8")
            }
            val params = buildJsonObject {
                put("requestId", e.str("requestId"))
                put("responseCode", 200)
                put("body", body)
                put("responseHeaders", buildJsonArray { add(contentType) })
            }
            cdp.send("Fetch.fulfillRequest", params, session)
            served.complete(Unit)
        }
        call("Page.navigate", buildJsonObject { put("url", url) })
        served.await()
        while (eval("document.readyState")?.jsonPrimitive?.contentOrNull != "complete") delay(50)
    }

    /** The cookies the browser would send to [url], as a Cookie header. */
    suspend fun cookies(url: String): String {
        val params = buildJsonObject { put("urls", buildJsonArray { add(JsonPrimitive(url)) }) }
        val cookies = call("Network.getCookies", params)["cookies"]!!.jsonArray
        return cookies.joinToString("; ") { c ->
            val cookie = c.jsonObject
            "${cookie.str("name")}=${cookie.str("value")}"
        }
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull ?: ""
}

/**
 * The DevTools protocol over the JDK's WebSocket. Answers are matched to calls by id, and events to handlers by method
 * and session.
 */
class Cdp private constructor(private val ws: WebSocket, private val listener: Listener) : AutoCloseable {
    private val ids = AtomicInteger()
    val open get() = !listener.closed

    suspend fun call(method: String, params: JsonObject = JsonObject(emptyMap()), session: String? = null): JsonObject =
        send(method, params, session).await()

    fun send(method: String, params: JsonObject, session: String? = null): CompletableFuture<JsonObject> {
        val id = ids.incrementAndGet()
        val f = CompletableFuture<JsonObject>()
        listener.pending[id] = f
        val msg = buildJsonObject {
            put("id", id)
            put("method", method)
            put("params", params)
            if (session != null) put("sessionId", session)
        }
        synchronized(ws) { ws.sendText(msg.toString(), true).join() }
        if (listener.closed) f.completeExceptionally(IllegalStateException("DevTools connection closed"))
        return f
    }

    fun on(method: String, session: String?, handler: (JsonObject) -> Unit) {
        listener.events["$method $session"] = handler
    }

    override fun close() {
        runCatching { ws.sendClose(WebSocket.NORMAL_CLOSURE, "").join() }
    }

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
                    val error = msg["error"]
                    if (error != null) {
                        f?.completeExceptionally(IllegalStateException("DevTools: $error"))
                    } else {
                        f?.complete(msg["result"]?.jsonObject ?: JsonObject(emptyMap()))
                    }
                } else {
                    val method = (msg["method"] as? JsonPrimitive)?.contentOrNull
                    val session = (msg["sessionId"] as? JsonPrimitive)?.contentOrNull
                    val handler = events["$method $session"]
                    if (handler != null) Thread { handler(msg["params"]!!.jsonObject) }.start()
                }
            }
            ws.request(1)
            return null
        }

        override fun onClose(ws: WebSocket, status: Int, reason: String?): CompletionStage<*>? {
            gone()
            return null
        }
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
 * CEF, the last resort. All of it is downloaded when first needed, and none of it is in the package: its Java side
 * (jcefmaven and its dependencies, about 3 MB of jars from Maven Central, each checked against the SHA-256 pinned at
 * build time in BuildConfig.CEF_JARS) and CEF itself (about 150 MB, which jcefmaven fetches). It runs in a process of
 * its own (CefHost.kt) on the app's Java with those jars, because CEF must start before anything in the process opens an
 * AWT or X11 window. The app drives it over DevTools like any other browser.
 */
object Cef {
    val installDir get() = File(Dirs.files, "cef")
    private val installed get() = File(installDir, "install.lock").exists()
    /** The download's progress for the sign-in page, or null when nothing is downloading. */
    @Volatile var status: String? = null
        private set
    @Volatile private var installing = false

    /** Blocking. Returns CEF's jars, downloading any that are missing. */
    private fun jars(): List<File> {
        // Not in installDir, because jcefmaven empties it.
        val dir = File(Dirs.files, "cef-java")
        dir.mkdirs()
        return BuildConfig.CEF_JARS.split(';').map { entry ->
            val (path, sha256) = entry.split(' ')
            val jar = File(dir, path.substringAfterLast('/'))
            if (!jar.exists()) {
                status = "Downloading Chromium (CEF)…"
                val url = "https://repo1.maven.org/maven2/" + path.replace("+", "%2B")
                val bytes = URI(url).toURL().readBytes()
                val got = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
                check(got == sha256) { "${jar.name} doesn't match its checksum" }
                // Written beside the jar and renamed, so an interrupted write never leaves a partial jar.
                val tmp = File(dir, jar.name + ".tmp")
                tmp.writeBytes(bytes)
                tmp.renameTo(jar)
            }
            jar
        }
    }

    /**
     * Starts the app again with --cef-host (Flavor.kt), which runs CefHost.kt with CEF's jars. A packaged the app goes
     * through its launcher, because its Java has no `java` command. A run from Gradle goes through `java` and the
     * class path.
     */
    private fun host(vararg args: String): Process {
        val jars = jars().joinToString(File.pathSeparator) { it.path }
        val javaName = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        val java = File(System.getProperty("java.home"), "bin/$javaName")
        val self = if (java.canExecute()) {
            listOf(java.path, "-cp", System.getProperty("java.class.path"), "com.zakius.caravanserai.MainKt")
        } else {
            listOf(ProcessHandle.current().info().command().get())
        }
        val command = self + listOf("--cef-host", jars) + args
        return ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    }

    /** Blocking. Downloads the jars and CEF unless they are there. The host reports the download's progress. */
    fun install() {
        jars()
        if (installed) return
        val p = host("--install")
        p.inputStream.bufferedReader().forEachLine { line ->
            if (line.startsWith("status ")) status = line.removePrefix("status ").ifEmpty { null }
        }
        status = null
        check(p.waitFor() == 0 && installed) { "CEF didn't install" }
    }

    /**
     * Starts CEF in its own process, with a hidden page or a window showing [url]. Before CEF is installed, a hidden
     * page fails at once and the download starts in the background, because a stream lookup can't wait for 150 MB. That
     * song goes without a token. A window (the sign-in) waits for the download.
     */
    suspend fun start(url: String? = null): Chrome = withContext(Dispatchers.IO) {
        if (!installed) {
            if (url == null) {
                if (!installing) {
                    installing = true
                    val installer = Thread({
                        runCatching { install() }
                        installing = false
                    }, "cef install")
                    installer.isDaemon = true
                    installer.start()
                }
                error("CEF is downloading")
            }
            install()
        }
        val p = host(*listOfNotNull(url).toTypedArray())
        try {
            val out = p.inputStream.bufferedReader()
            // A host that says nothing for 20 s is ended, which ends the read.
            val watchdog = Thread {
                runCatching {
                    Thread.sleep(20_000)
                    p.destroy()
                }
            }
            watchdog.isDaemon = true
            watchdog.start()
            val lines = generateSequence { out.readLine() }
            val line = lines.firstOrNull { it.startsWith("DevTools port ") } ?: error("CEF didn't start")
            watchdog.interrupt()
            val port = line.removePrefix("DevTools port ").toInt()
            val json = URI("http://127.0.0.1:$port/json/version").toURL().readText()
            val webSocketUrl = Json.parseToJsonElement(json).jsonObject["webSocketDebuggerUrl"]!!.jsonPrimitive.content
            Chrome(p, Cdp.connect(webSocketUrl))
        } catch (t: Throwable) {
            p.destroy()
            throw t
        }
    }
}

/**
 * BotGuard in a hidden Chromium page. po_token.html is served at youtube.com's origin, then the page's functions are
 * called through [Tab.eval] in the same steps as Android's WebView (PoToken.kt). The browser is closed after 5 idle
 * minutes so it doesn't stay in memory, and the next song that needs a token starts it again, in about 2 s.
 */
class ChromeBotGuard private constructor(
    private val chrome: Chrome,
    private val page: Tab,
    private val expiresAt: Long,
) : BotGuardSession {
    @Volatile private var closed = false
    @Volatile private var used = System.currentTimeMillis()
    override val expired get() = System.currentTimeMillis() > expiresAt
    override val dead get() = closed || !chrome.alive

    init {
        val idle = Thread({
            while (!closed) {
                Thread.sleep(30_000)
                if (System.currentTimeMillis() - used > 5 * 60_000) close()
            }
        }, "botguard idle")
        idle.isDaemon = true
        idle.start()
    }

    override suspend fun mint(identifier: String): String {
        used = System.currentTimeMillis()
        val identifierBytes = BotGuard.u8Array(identifier.toByteArray())
        val u8 = page.eval("obtainPoToken($identifierBytes).then(u => Array.from(u).join(','))")
        return BotGuard.token(u8!!.jsonPrimitive.content)
    }

    override fun close() {
        if (closed) return
        closed = true
        chrome.close()
    }

    companion object {
        private val html by lazy { ChromeBotGuard::class.java.getResource("/po_token.html")!!.readText() }

        /** Opens a session in the first browser that can run BotGuard (Browser.start). Opening it is the browser's test. */
        suspend fun start(): BotGuardSession {
            return Browser.start("botguard", headless = true) { chrome -> open(chrome) }.second
        }

        internal suspend fun open(chrome: Chrome): ChromeBotGuard {
            val page = chrome.page()
            page.serve("https://www.youtube.com/roma-botguard", html)
            val challenge = withContext(Dispatchers.IO) {
                val body = "[ \"${BotGuard.REQUEST_KEY}\" ]"
                BotGuard.parseChallenge(BotGuard.request("https://www.youtube.com/api/jnn/v1/Create", body))
            }
            val script = "runBotGuard($challenge).then(r => { window.webPoSignalOutput = r.webPoSignalOutput; return r.botguardResponse })"
            val response = page.eval(script)!!.jsonPrimitive.content
            val (integrity, expires) = withContext(Dispatchers.IO) {
                val body = "[ \"${BotGuard.REQUEST_KEY}\", \"$response\" ]"
                BotGuard.integrity(BotGuard.request("https://www.youtube.com/api/jnn/v1/GenerateIT", body))
            }
            page.eval("createPoTokenMinter(window.webPoSignalOutput, $integrity).then(() => '')")
            return ChromeBotGuard(chrome, page, expires)
        }
    }
}

/**
 * The YouTube Music sign-in, in a browser window with the app's own sign-in profile. Google refuses to sign in a browser
 * whose DevTools port is open ("This browser or app may not be secure"), so a system browser's window is started
 * without one. Once it closes, a hidden browser on the same profile reads the account's cookies, as Android's WebView
 * sign-in does. Without a system browser, CEF's window is used and watched until it reaches music.youtube.com.
 * Returns false when the window closed without a sign-in.
 */
suspend fun browserSignIn(): Boolean {
    val url = "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"
    val profile = Browser.profileDir("signin")
    var chrome: Chrome? = null
    var window: Process? = null
    try {
        val found = Browser.order().filter { File(it).canExecute() }
        val last = Store.str("browser.worked", "")
        val exe = if (last in found) last else found.firstOrNull()
        if (exe != null) {
            profile.mkdirs()
            window = withContext(Dispatchers.IO) {
                ProcessBuilder(exe, "--user-data-dir=${profile.path}", "--no-first-run", "--no-default-browser-check", url)
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            }
            // A wrapper script may hand over to the browser and exit, so the browser is found through the profile's
            // lock, which on Linux links to "<host>-<pid>". A killed browser leaves its lock behind, so what counts is
            // whether that process still runs.
            val lock = File(profile, "SingletonLock").toPath()
            fun browserRunning(): Boolean {
                val pid = runCatching {
                    java.nio.file.Files.readSymbolicLink(lock).toString().substringAfterLast('-').toLong()
                }.getOrNull()
                return pid != null && ProcessHandle.of(pid).isPresent
            }
            while (window.isAlive || browserRunning()) delay(500)
            chrome = Chrome.launch(exe, profile, headless = true)
            return take(chrome.page())
        }
        val c = Cef.start(url)
        chrome = c
        var music: Tab? = null
        while (c.alive) {
            delay(1_000)
            if (c.urls().none { it.startsWith("https://music.youtube.com") }) continue
            if (music == null) music = c.page("https://music.youtube.com")
            if (take(music)) return true
        }
        return false // the window was closed
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // No browser started, CEF couldn't start, or the window closed in the middle of a call.
        val prefix = if (chrome == null && window == null) "Couldn't open a browser to sign in: " else "Couldn't sign in: "
        toast(prefix + e.message, long = true)
        return false
    } finally {
        window?.destroy()
        chrome?.close()
    }
}

/**
 * Signs in with the YouTube Music cookies of [page]'s browser, when they hold an account's. The visitor and account ids
 * come from music.youtube.com's page fetched with those cookies, because a headless browser's copy of the page never
 * sets them.
 */
private suspend fun take(page: Tab): Boolean {
    val cookie = page.cookies("https://music.youtube.com")
    if ("SAPISID" !in cookie) return false
    val html = withContext(Dispatchers.IO) {
        runCatching {
            val request = java.net.http.HttpRequest.newBuilder(java.net.URI("https://music.youtube.com/"))
                .header("Cookie", cookie)
                .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36")
                .build()
            java.net.http.HttpClient.newHttpClient().send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).body()
        }.getOrDefault("")
    }
    fun id(key: String) = Regex("\"$key\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)
    withContext(Dispatchers.Main) { signedIn(cookie, id("VISITOR_DATA"), id("DATASYNC_ID")) }
    return true
}
