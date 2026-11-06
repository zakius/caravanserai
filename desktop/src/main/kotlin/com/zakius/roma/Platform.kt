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

    // The variable's value, or null when it is unset or blank.
    private fun env(name: String): String? {
        val value = System.getenv(name)
        return if (value.isNullOrBlank()) null else value
    }

    private val dataHome = if (windows) env("APPDATA") ?: "$home/AppData/Roaming" else env("XDG_DATA_HOME") ?: "$home/.local/share"
    val files: File = File(dataHome, BuildConfig.SLUG)

    init {
        files.mkdirs()
        ownerOnly(files, "rwx------")
    }

    val cache: File = if (windows) {
        File(env("LOCALAPPDATA") ?: "$home/AppData/Local", "${BuildConfig.SLUG}/cache")
    } else {
        File(env("XDG_CACHE_HOME") ?: "$home/.cache", BuildConfig.SLUG)
    }

    init {
        cache.mkdirs()
    }
}

/**
 * On POSIX systems, makes [f] accessible to the user alone, with [mode] as ls shows it, because user storage holds a
 * server's password and sign-ins. Windows already keeps a profile's folders to its user.
 */
fun ownerOnly(f: File, mode: String = "rw-------") {
    if ("posix" in java.nio.file.FileSystems.getDefault().supportedFileAttributeViews()) {
        val permissions = java.nio.file.attribute.PosixFilePermissions.fromString(mode)
        runCatching { java.nio.file.Files.setPosixFilePermissions(f.toPath(), permissions) }
    }
}

/**
 * Android's SharedPreferences, as much of it as roma uses, in a properties file that only the user can read
 * ([ownerOnly]). Edits are applied in memory at once, then written in order on their own thread to a file beside this
 * one, which is renamed over it.
 */
class Prefs(private val file: File) {
    private val values = Properties()

    init {
        runCatching { file.inputStream().use { values.load(it) } }
        if (file.exists()) ownerOnly(file)
    }

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
            synchronized(values) {
                for ((k, v) in changes) {
                    if (v == null) values.remove(k) else values.setProperty(k, v)
                }
            }
            writer.execute {
                val tmp = File(file.path + ".tmp")
                runCatching {
                    tmp.delete()
                    tmp.createNewFile()
                    ownerOnly(tmp) // before anything is written to it
                    synchronized(values) { tmp.outputStream().use { values.store(it, null) } }
                    java.nio.file.Files.move(
                        tmp.toPath(),
                        file.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    )
                }
            }
        }
    }

    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "prefs").apply { isDaemon = true }
    }
}

/** A decoded cover. */
typealias Pic = ImageBitmap

fun Pic.toImage(): ImageBitmap = this

fun picBytes(p: Pic) = p.width * p.height * 4

/** The covers' memory: 128 MB, about 200 home cards. */
fun picMemory() = 128L * 1024 * 1024

/**
 * Decodes a cover to at least [px] on its short side, or to exactly [px] wide when [exact]. [software] makes no
 * difference on the desktop.
 */
@Suppress("UNUSED_PARAMETER")
fun decodePic(data: ByteArray, px: Int, exact: Boolean, software: Boolean): Pic? {
    val image = org.jetbrains.skia.Image.makeFromEncoded(data)
    val short = minOf(image.width, image.height)
    if (short <= 0) return null
    val scale = when {
        exact -> px.toFloat() / image.width
        short > px * 2 -> px.toFloat() / short
        else -> 1f
    }
    if (scale == 1f) return image.toComposeImageBitmap()
    val w = maxOf(1, (image.width * scale).toInt())
    val h = maxOf(1, (image.height * scale).toInt())
    val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(w, h)
    val src = org.jetbrains.skia.Rect.makeWH(image.width.toFloat(), image.height.toFloat())
    val dst = org.jetbrains.skia.Rect.makeWH(w.toFloat(), h.toFloat())
    surface.canvas.drawImageRect(image, src, dst, org.jetbrains.skia.SamplingMode.CATMULL_ROM, null, true)
    return surface.makeImageSnapshot().toComposeImageBitmap()
}

@OptIn(DelicateCoroutinesApi::class)
fun postMain(delayMs: Long = 0, f: () -> Unit) {
    GlobalScope.launch(Dispatchers.Main) {
        if (delayMs > 0) delay(delayMs)
        f()
    }
}

private val started = System.nanoTime()

/** Milliseconds on a clock that never jumps: the sleep timer's. */
fun elapsedRealtime() = (System.nanoTime() - started) / 1_000_000

fun uptimeMillis() = elapsedRealtime()

/** The message the window shows at its bottom for a few seconds (Main.kt), as Android's toast. */
var toastText by mutableStateOf<Pair<String, Long>?>(null)

fun toast(text: String, long: Boolean = false) {
    val showMs = if (long) 3_500 else 2_000
    postMain { toastText = text to elapsedRealtime() + showMs }
}

fun openUrl(url: String) {
    // AWT's browse isn't available on every Linux desktop, but xdg-open is.
    val awt = runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
    if (awt.isFailure) {
        val xdg = runCatching { ProcessBuilder("xdg-open", url).start() }
        if (xdg.isFailure) toast("Couldn't open $url", long = true)
    }
}

/** The desktop has no share sheet, so the link goes to the clipboard. */
@Suppress("UNUSED_PARAMETER")
fun share(text: String, title: String) {
    val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
    clipboard.setContents(java.awt.datatransfer.StringSelection(text), null)
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

/** Back, as on Android: runs the innermost enabled handler. Returns false when there is none. */
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

/** The desktop has no network callback, so roma counts as online and a failed load retries as usual. */
@Suppress("UNUSED_PARAMETER")
fun watchNetwork(changed: (Boolean) -> Unit) {}

/** Asks where to save [name]. The returned function opens the dialog, and [bytes] gives what is written. */
@Composable
fun rememberSaveFile(name: String, bytes: () -> ByteArray): () -> Unit {
    val write by rememberUpdatedState(bytes)
    return {
        val d = java.awt.FileDialog(null as java.awt.Frame?, "Export", java.awt.FileDialog.SAVE)
        d.file = name
        d.isVisible = true // blocks until the dialog closes
        if (d.file != null) {
            runCatching { File(d.directory, d.file).writeBytes(write()) }
                .onFailure { e -> toast("Couldn't export: ${e.message}", long = true) }
        }
    }
}

/** Asks for a file. The returned function opens the dialog, and [text] gets the file's text. */
@Composable
fun rememberOpenFile(text: (String) -> Unit): () -> Unit {
    val read by rememberUpdatedState(text)
    return {
        val d = java.awt.FileDialog(null as java.awt.Frame?, "Import", java.awt.FileDialog.LOAD)
        d.isVisible = true // blocks until the dialog closes
        if (d.file != null) {
            runCatching { File(d.directory, d.file).readText() }
                .onSuccess(read)
                .onFailure { e -> toast("Couldn't read the file: ${e.message}", long = true) }
        }
    }
}

/**
 * Last.fm's browser auth, with roma itself as the callback. A one-request server on localhost takes the token that
 * Last.fm sends the browser back with, then closes. The path carries a random secret, so another program that finds the
 * port can't hand roma a token of its own. A new tap replaces a server that is still waiting.
 */
fun connectLastFm() {
    lastFmServer?.stop(0)
    val secret = java.util.UUID.randomUUID().toString()
    val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
    lastFmServer = server
    server.createContext("/lastfm/$secret") { ex ->
        val params = ex.requestURI.query?.split('&')
        val token = params?.firstOrNull { it.startsWith("token=") }?.substringAfter('=')
        val message = if (token != null) "Last.fm connected: you can close this tab." else "No token from Last.fm."
        val page = "<html><body style='font-family:sans-serif'>$message</body></html>".toByteArray()
        ex.sendResponseHeaders(200, page.size.toLong())
        ex.responseBody.use { it.write(page) }
        Thread { server.stop(0) }.start()
        if (token != null) lastFmToken(token)
    }
    server.start()
    val callback = "http://127.0.0.1:${server.address.port}/lastfm/$secret"
    openUrl(roma.core.LastFm(Store.str("lastfm.key", ""), "").authUrl(callback))
}

@Volatile private var lastFmServer: com.sun.net.httpserver.HttpServer? = null

private fun lastFmToken(token: String) {
    val fm = roma.core.LastFm(Store.str("lastfm.key", ""), Store.str("lastfm.secret", ""))
    val result = runCatching { fm.session(token) }
    result.onSuccess { sk ->
        postMain {
            Store.set("lastfm.session", sk)
            Playback.flushScrobbles()
            toast("Last.fm connected")
        }
    }
    result.onFailure { e -> toast("Last.fm: ${e.message}", long = true) }
}

/**
 * A text field in an outlined box, as on Android. The desktop uses Compose's own field, because the 120 KB it costs on
 * Android doesn't matter here. With [onSearch], Enter searches. With [autoFocus], the field is focused as soon as it
 * is on screen.
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
        modifier
            .defaultMinSize(minWidth = 280.dp, minHeight = 56.dp)
            .border(if (focused) 2.dp else 1.dp, if (focused) s.primary else s.outline, RoundedCornerShape(4.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        if (label != null) {
            Text(
                label,
                Modifier.clearAndSetSemantics {},
                color = if (focused) s.primary else s.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        BasicTextField(
            value,
            onValueChange,
            Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .onFocusChanged { focused = it.isFocused },
            textStyle = style,
            singleLine = singleLine,
            cursorBrush = SolidColor(s.primary),
            visualTransformation = if (password) {
                androidx.compose.ui.text.input.PasswordVisualTransformation()
            } else {
                androidx.compose.ui.text.input.VisualTransformation.None
            },
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

/** A queue entry as QueuePlayer holds it: the entry itself. */
typealias QueueItem = Entry

private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#\\d+|amp|lt|gt|quot|apos|nbsp|#39);")

/** A comment's HTML as plain text: line breaks kept, tags dropped, entities decoded. */
fun htmlToText(html: String): String {
    val withBreaks = html.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
    val withoutTags = withBreaks.replace(Regex("<[^>]+>"), "")
    return withoutTags.replace(ENTITY) { m ->
        when (val e = m.groupValues[1]) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos", "#39" -> "'"
            "nbsp" -> " "
            // A numeric entity, &#x41; in hex or &#65; in decimal. An invalid one stays as it is.
            else -> runCatching {
                val code = if (e[1] == 'x') e.substring(2).toInt(16) else e.substring(1).toInt()
                String(Character.toChars(code))
            }.getOrDefault(m.value)
        }
    }
}

/** Balanced wrapping evens out the lyric lines' lengths; Compose for Desktop has no phrase breaking. */
val lyricBreak = androidx.compose.ui.text.style.LineBreak.Paragraph

val sheetDialogProperties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
