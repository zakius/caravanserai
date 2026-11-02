package com.zakius.roma

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import roma.core.Icu
import java.io.File
import java.util.Properties
import java.util.concurrent.Executors

// What the shared code (shared/) needs from the desktop. app/ has the same names for Android.

/** Where roma keeps its files: user storage (settings, sign-ins, corrections, the queue) and the cache. */
object Dirs {
    private val windows = System.getProperty("os.name").startsWith("Windows")
    private val home = System.getProperty("user.home")
    private fun env(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() }

    val files: File = File(
        if (windows) env("APPDATA") ?: "$home/AppData/Roaming" else env("XDG_DATA_HOME") ?: "$home/.local/share", BuildConfig.SLUG,
    ).also { it.mkdirs(); ownerOnly(it, "rwx------") }
    val cache: File = (if (windows) File(env("LOCALAPPDATA") ?: "$home/AppData/Local", "${BuildConfig.SLUG}/cache") else File(env("XDG_CACHE_HOME") ?: "$home/.cache", BuildConfig.SLUG))
        .also { it.mkdirs() }
}

/**
 * On POSIX systems, [f] for the user alone ([mode] as ls shows it): user storage holds a server's password and sign-ins.
 * Windows keeps a profile's folders to its user already.
 */
fun ownerOnly(f: File, mode: String = "rw-------") {
    if ("posix" in java.nio.file.FileSystems.getDefault().supportedFileAttributeViews())
        runCatching { java.nio.file.Files.setPosixFilePermissions(f.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString(mode)) }
}

/**
 * Android's SharedPreferences, as much of it as roma uses, in a properties file (the user's alone, [ownerOnly]). Edits are applied in memory at once
 * and written in order on their own thread, beside the file and renamed over it.
 */
class Prefs(private val file: File) {
    private val values = Properties().also { p -> runCatching { file.inputStream().use { p.load(it) } }; if (file.exists()) ownerOnly(file) }

    fun getString(key: String, def: String?): String? = values.getProperty(key) ?: def
    fun getBoolean(key: String, def: Boolean) = values.getProperty(key)?.toBooleanStrictOrNull() ?: def
    fun getInt(key: String, def: Int) = values.getProperty(key)?.toIntOrNull() ?: def
    fun contains(key: String) = values.containsKey(key)
    fun edit() = Editor()

    inner class Editor {
        private val changes = LinkedHashMap<String, String?>()
        fun putString(key: String, value: String?) = apply { changes[key] = value }
        fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value.toString() }
        fun putInt(key: String, value: Int) = apply { changes[key] = value.toString() }
        fun remove(key: String) = apply { changes[key] = null }
        fun apply() {
            synchronized(values) { for ((k, v) in changes) if (v == null) values.remove(k) else values.setProperty(k, v) }
            writer.execute {
                val tmp = File(file.path + ".tmp")
                runCatching {
                    tmp.delete()
                    tmp.createNewFile()
                    ownerOnly(tmp) // before anything is in it
                    synchronized(values) { tmp.outputStream().use { values.store(it, null) } }
                    java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                }
            }
        }
    }

    private val writer = Executors.newSingleThreadExecutor { Thread(it, "prefs").apply { isDaemon = true } }
}

/** A decoded cover. */
typealias Pic = ImageBitmap

fun Pic.toImage(): ImageBitmap = this

fun picBytes(p: Pic) = p.width * p.height * 4

/** The covers' memory: 128 MB, about 200 home cards. */
fun picMemory() = 128L shl 20

/** Decoded at least [px] on its short side; [exact]: scaled to [px] across. [software] makes no difference here. */
@Suppress("UNUSED_PARAMETER")
fun decodePic(data: ByteArray, px: Int, exact: Boolean, software: Boolean): Pic? {
    val image = org.jetbrains.skia.Image.makeFromEncoded(data)
    val short = minOf(image.width, image.height)
    if (short <= 0) return null
    val scale = if (exact) px.toFloat() / image.width else if (short > px * 2) px.toFloat() / short else 1f
    if (scale == 1f) return image.toComposeImageBitmap()
    val w = maxOf(1, (image.width * scale).toInt())
    val h = maxOf(1, (image.height * scale).toInt())
    val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(w, h)
    surface.canvas.drawImageRect(image, org.jetbrains.skia.Rect.makeWH(image.width.toFloat(), image.height.toFloat()), org.jetbrains.skia.Rect.makeWH(w.toFloat(), h.toFloat()), org.jetbrains.skia.SamplingMode.CATMULL_ROM, null, true)
    return surface.makeImageSnapshot().toComposeImageBitmap()
}

@OptIn(DelicateCoroutinesApi::class)
fun postMain(delayMs: Long = 0, f: () -> Unit) {
    GlobalScope.launch(Dispatchers.Main) { if (delayMs > 0) delay(delayMs); f() }
}

private val started = System.nanoTime()

/** Milliseconds on a clock that never jumps: the sleep timer's. */
fun elapsedRealtime() = (System.nanoTime() - started) / 1_000_000

fun uptimeMillis() = elapsedRealtime()

/** The message the window shows at its bottom for a few seconds (Main.kt), as Android's toast. */
var toastText by mutableStateOf<Pair<String, Long>?>(null)

fun toast(text: String, long: Boolean = false) = postMain { toastText = text to elapsedRealtime() + if (long) 3_500 else 2_000 }

fun openUrl(url: String) {
    // AWT's browse isn't there on every Linux desktop; xdg-open is.
    val awt = runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
    if (awt.isFailure) runCatching { ProcessBuilder("xdg-open", url).start() }.onFailure { toast("Couldn't open $url", long = true) }
}

/** No share sheet on the desktop: the link goes to the clipboard. */
@Suppress("UNUSED_PARAMETER")
fun share(text: String, title: String) {
    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null)
    toast("Link copied")
}

/** The Back handlers on screen, innermost last; Escape and the mouse's back button run the last enabled one (Main.kt). */
val backHandlers = mutableStateListOf<BackEntry>()

class BackEntry(var enabled: Boolean, var onBack: () -> Unit)

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val entry = remember { BackEntry(enabled, onBack) }
    entry.enabled = enabled
    entry.onBack = onBack
    DisposableEffect(entry) {
        backHandlers += entry
        onDispose { backHandlers -= entry }
    }
}

/** Back, as Android's: the innermost enabled handler. False when none took it. */
fun goBack(): Boolean {
    val e = backHandlers.lastOrNull { it.enabled } ?: return false
    e.onBack()
    return true
}

val platformIcu = Icu { id, text -> com.ibm.icu.text.Transliterator.getInstance(id).transliterate(text) }

/** The desktop has no Battery Saver or metered network to respect. */
fun meteredOrSaving() = false

/** roma's own colours: the desktop has no system palette Compose reads. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun systemColorScheme(dark: Boolean): ColorScheme? = null

/** Pixels per dp: the window's, set by Main.kt. */
@Volatile var density = 1f

fun screenDensity() = density

/** The window's width in dp, outside composition. */
@Volatile var windowWidthDp = 800

fun screenWidthDp() = windowWidthDp

/** The window's size in dp; recomposes when it changes. */
@Composable
fun screenSizeDp(): IntSize {
    val px = LocalWindowInfo.current.containerSize
    val d = LocalDensity.current.density
    return IntSize((px.width / d).toInt(), (px.height / d).toInt())
}

fun likeChanged() {}

/** No network callback on the desktop: roma counts as online, and a failed load retries as usual. */
@Suppress("UNUSED_PARAMETER")
fun watchNetwork(changed: (Boolean) -> Unit) {}

/** Asks where to save [name]; the returned function opens the dialog, [bytes] is what is written. */
@Composable
fun rememberSaveFile(name: String, bytes: () -> ByteArray): () -> Unit {
    val write by rememberUpdatedState(bytes)
    return {
        val d = java.awt.FileDialog(null as java.awt.Frame?, "Export", java.awt.FileDialog.SAVE).apply { file = name; isVisible = true }
        if (d.file != null) runCatching { File(d.directory, d.file).writeBytes(write()) }.onFailure { toast("Couldn't export: ${it.message}", long = true) }
    }
}

/** Asks for a file; the returned function opens the dialog, [text] gets the file's text. */
@Composable
fun rememberOpenFile(text: (String) -> Unit): () -> Unit {
    val read by rememberUpdatedState(text)
    return {
        val d = java.awt.FileDialog(null as java.awt.Frame?, "Import", java.awt.FileDialog.LOAD).apply { isVisible = true }
        if (d.file != null) runCatching { File(d.directory, d.file).readText() }.onSuccess(read).onFailure { toast("Couldn't read the file: ${it.message}", long = true) }
    }
}

/**
 * Last.fm's browser auth, with roma itself as the callback: a one-request server on localhost takes the token Last.fm
 * sends the browser back with, then closes. The path carries a random secret, so another program that finds the port
 * can't hand roma a token of its own; a new tap replaces a server still waiting.
 */
fun connectLastFm() {
    lastFmServer?.stop(0)
    val secret = java.util.UUID.randomUUID().toString()
    val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
    lastFmServer = server
    server.createContext("/lastfm/$secret") { ex ->
        val token = ex.requestURI.query?.split('&')?.firstOrNull { it.startsWith("token=") }?.substringAfter('=')
        val page = "<html><body style='font-family:sans-serif'>${if (token != null) "Last.fm connected: you can close this tab." else "No token from Last.fm."}</body></html>".toByteArray()
        ex.sendResponseHeaders(200, page.size.toLong())
        ex.responseBody.use { it.write(page) }
        Thread { server.stop(0) }.start()
        if (token != null) lastFmToken(token)
    }
    server.start()
    openUrl(roma.core.LastFm(Store.str("lastfm.key", ""), "").authUrl("http://127.0.0.1:${server.address.port}/lastfm/$secret"))
}

@Volatile private var lastFmServer: com.sun.net.httpserver.HttpServer? = null

private fun lastFmToken(token: String) {
    val fm = roma.core.LastFm(Store.str("lastfm.key", ""), Store.str("lastfm.secret", ""))
    runCatching { fm.session(token) }.onSuccess { sk ->
        postMain { Store.set("lastfm.session", sk); Playback.flushScrobbles(); toast("Last.fm connected") }
    }.onFailure { toast("Last.fm: ${it.message}", long = true) }
}

/**
 * A text field in an outlined box, as Android's: Compose's own field here, where the 120 KB it costs on Android
 * doesn't matter. [onSearch]: Enter searches. [autoFocus]: focused as soon as it is on screen.
 */
@Composable
fun OutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    label: String? = null,
    placeholder: String? = null,
    onSearch: (() -> Unit)? = null,
    autoFocus: Boolean = false,
    password: Boolean = false,
) {
    val s = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val style = MaterialTheme.typography.bodyLarge.copy(color = s.onSurface)
    Column(
        modifier.defaultMinSize(minWidth = 280.dp, minHeight = 56.dp)
            .border(if (focused) 2.dp else 1.dp, if (focused) s.primary else s.outline, RoundedCornerShape(4.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        if (label != null) Text(label, Modifier.clearAndSetSemantics {}, color = if (focused) s.primary else s.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        BasicTextField(
            value, onValueChange,
            Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { focused = it.isFocused },
            textStyle = style,
            singleLine = singleLine,
            cursorBrush = SolidColor(s.primary),
            visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(imeAction = if (onSearch != null) ImeAction.Search else ImeAction.Default),
            keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }, onDone = { onSearch?.invoke() }),
            decorationBox = { inner ->
                androidx.compose.foundation.layout.Box {
                    if (value.isEmpty() && placeholder != null) Text(placeholder, color = s.onSurfaceVariant, style = style)
                    inner()
                }
            },
        )
    }
}

/** A queue entry as VlcAudio holds it: the entry itself. */
typealias QueueItem = Entry

private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#\\d+|amp|lt|gt|quot|apos|nbsp|#39);")

/** A comment's HTML as plain text: line breaks kept, tags dropped, entities decoded. */
fun htmlToText(html: String): String = html.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n").replace(Regex("<[^>]+>"), "")
    .replace(ENTITY) { m ->
        when (val e = m.groupValues[1]) {
            "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos", "#39" -> "'"; "nbsp" -> " "
            else -> runCatching { String(Character.toChars(if (e[1] == 'x') e.substring(2).toInt(16) else e.substring(1).toInt())) }.getOrDefault(m.value)
        }
    }

/** Balanced wrapping evens out the lyric lines' lengths; Compose for Desktop has no phrase breaking. */
val lyricBreak = androidx.compose.ui.text.style.LineBreak.Paragraph

val sheetDialogProperties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
