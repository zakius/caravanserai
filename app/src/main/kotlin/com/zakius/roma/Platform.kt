package com.zakius.roma

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import roma.core.Icu

// What the shared code (shared/) needs from Android. desktop/ has the same names.

typealias Prefs = android.content.SharedPreferences

/** A decoded cover, as Images keeps it: hardware bitmaps unless the pixels are read. */
typealias Pic = android.graphics.Bitmap

fun Pic.toImage(): ImageBitmap = asImageBitmap()

fun picBytes(p: Pic) = p.allocationByteCount

/**
 * The covers' memory, sized from the phone's RAM, not the Java heap: most covers are hardware bitmaps, whose pixels sit
 * in graphics memory. 1/32 of RAM, 48–128 MB: about 80–200 home cards, or 600+ list rows.
 */
fun picMemory(): Long {
    val ram = android.app.ActivityManager.MemoryInfo().also { appContext.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it) }.totalMem
    return (ram / 32).coerceIn(48L shl 20, 128L shl 20)
}

/**
 * Decoded at least [px] on its short side, sampled down by powers of two; [exact]: scaled to [px] across. Hardware
 * bitmaps unless [software] or [exact] (pixels read).
 */
fun decodePic(data: ByteArray, px: Int, exact: Boolean, software: Boolean): Pic? {
    val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, o)
    if (o.outWidth <= 0) return null
    var sample = 1
    while (minOf(o.outWidth, o.outHeight) / (sample * 2) >= px) sample *= 2
    val opts = android.graphics.BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = if (software || exact) android.graphics.Bitmap.Config.ARGB_8888 else android.graphics.Bitmap.Config.HARDWARE
    }
    val b = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, opts) ?: return null
    if (!exact) return b
    val h = maxOf(1, px * b.height / b.width)
    return android.graphics.Bitmap.createScaledBitmap(b, px, h, true)
}

/** The application context, set first thing at start (App). */
lateinit var appContext: Context

private val main = Handler(Looper.getMainLooper())

fun postMain(delayMs: Long = 0, f: () -> Unit) { main.postDelayed(f, delayMs) }

/** Milliseconds since boot, sleep included: the sleep timer's clock. */
fun elapsedRealtime() = SystemClock.elapsedRealtime()

fun uptimeMillis() = SystemClock.uptimeMillis()

fun toast(text: String, long: Boolean = false) = Toast.makeText(appContext, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

fun openUrl(url: String) = appContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

/** The system's share sheet with a link. */
fun share(text: String, title: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_TITLE, title)
    appContext.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) = androidx.activity.compose.BackHandler(enabled, onBack)

val platformIcu = Icu { id, text -> android.icu.text.Transliterator.getInstance(id).transliterate(text) }

/** Screen pixels per dp. */
fun screenDensity() = appContext.resources.displayMetrics.density

/** The like on the current song changed: the media notification's Like button follows. */
fun likeChanged() { PlaybackService.instance?.updateLikeButton() }

/**
 * [changed] gets whether a validated network is up, on the main thread, each time that changes. From the system's
 * callback, never from polling.
 */
fun watchNetwork(changed: (Boolean) -> Unit) {
    val cm = appContext.getSystemService(android.net.ConnectivityManager::class.java)
    cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(n: android.net.Network, caps: android.net.NetworkCapabilities) {
            val up = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            main.post { changed(up) }
        }

        override fun onLost(n: android.net.Network) { main.post { changed(false) } }
    }, main)
}

/** Mobile data or Battery Saver: guesses and prefetching wait unless the user allows them. */
fun meteredOrSaving(): Boolean {
    val metered = appContext.getSystemService(android.net.ConnectivityManager::class.java).isActiveNetworkMetered
    return metered || appContext.getSystemService(android.os.PowerManager::class.java).isPowerSaveMode
}

/** The system's colours (Android 12+), else null for roma's own. */
@Composable
fun systemColorScheme(dark: Boolean): ColorScheme? {
    if (Build.VERSION.SDK_INT < 31) return null
    val c = LocalContext.current
    return if (dark) dynamicDarkColorScheme(c) else dynamicLightColorScheme(c)
}

private fun c(argb: Long) = Color(argb)

// The system's tonal palettes (Android 12+), by tone: _0 is tone 100, _500 tone 50, _1000 tone 0. Tones between two
// resources are blended.
@RequiresApi(31)
private fun dynamicLightColorScheme(ctx: Context): ColorScheme {
    fun r(id: Int) = Color(ctx.getColor(id))
    val n1 = { id: Int -> r(id) }
    return ColorScheme(
        r(android.R.color.system_accent1_600), r(android.R.color.system_accent1_0), r(android.R.color.system_accent1_200),
        r(android.R.color.system_accent2_100), r(android.R.color.system_accent2_900),
        lerp(n1(android.R.color.system_neutral1_10), n1(android.R.color.system_neutral1_50), 0.25f),
        lerp(n1(android.R.color.system_neutral1_10), n1(android.R.color.system_neutral1_50), 0.25f),
        r(android.R.color.system_neutral1_900), r(android.R.color.system_neutral2_700),
        lerp(n1(android.R.color.system_neutral1_50), n1(android.R.color.system_neutral1_100), 0.2f), r(android.R.color.system_neutral1_0),
        lerp(n1(android.R.color.system_neutral1_50), n1(android.R.color.system_neutral1_100), 0.6f),
        r(android.R.color.system_neutral1_800), r(android.R.color.system_neutral1_50),
        r(android.R.color.system_neutral2_500), r(android.R.color.system_neutral2_200), c(0xFFB3261E),
    )
}

@RequiresApi(31)
private fun dynamicDarkColorScheme(ctx: Context): ColorScheme {
    fun r(id: Int) = Color(ctx.getColor(id))
    val n900 = r(android.R.color.system_neutral1_900)
    val n800 = r(android.R.color.system_neutral1_800)
    val black = r(android.R.color.system_neutral1_1000)
    return ColorScheme(
        r(android.R.color.system_accent1_200), r(android.R.color.system_accent1_800), r(android.R.color.system_accent1_600),
        r(android.R.color.system_accent2_700), r(android.R.color.system_accent2_100),
        lerp(n900, black, 0.4f), lerp(n900, black, 0.4f),
        r(android.R.color.system_neutral1_100), r(android.R.color.system_neutral2_200),
        lerp(n900, n800, 0.2f), lerp(n900, black, 0.6f), lerp(n900, n800, 0.7f),
        r(android.R.color.system_neutral1_100), n800,
        r(android.R.color.system_neutral2_400), r(android.R.color.system_neutral2_700), c(0xFFF2B8B5),
    )
}

/**
 * A text field: Android's EditText in an outlined box. Compose's own text field brings in about 120 KB of the APK (its
 * text input, selection and context menu); EditText is in the platform. Text set from outside (a picked search
 * suggestion) puts the caret at its end. [onSearch] gives the keyboard a Search key. [autoFocus]: focused, with the
 * keyboard up, as soon as it is on screen (a dialog that is only for typing).
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
    val shape = RoundedCornerShape(4.dp)
    val change by rememberUpdatedState(onValueChange)
    val search by rememberUpdatedState(onSearch)
    var edit by remember { mutableStateOf<EditText?>(null) }
    // Made when the field is first on screen: an EditText made in a tab built hidden (buildAhead) stays blank, no
    // hint or caret, the first time the tab is shown.
    val shown = remember { BooleanArray(1) }
    if (LocalTabShown.current) shown[0] = true
    Column(
        modifier.defaultMinSize(minWidth = 280.dp, minHeight = 56.dp)
            .border(if (focused) 2.dp else 1.dp, if (focused) s.primary else s.outline, shape)
            // The whole box takes a tap, as Compose's field did, not only the line of text.
            .pointerInput(Unit) { detectTapGestures { edit?.let { it.requestFocus(); it.showKeyboard() } } }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        if (label != null) Text(label, Modifier.clearAndSetSemantics {}, color = if (focused) s.primary else s.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        val style = MaterialTheme.typography.bodyLarge
        if (shown[0]) AndroidView(
            factory = { c ->
                EditText(c).apply {
                    background = null
                    setPadding(0, 0, 0, 0)
                    includeFontPadding = false
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, style.fontSize.value)
                    letterSpacing = style.letterSpacing.value / style.fontSize.value
                    if (Build.VERSION.SDK_INT >= 28) lineHeight = (style.lineHeight.value * resources.displayMetrics.scaledDensity).toInt()
                    inputType = if (password) android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                    else android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or
                        (if (singleLine) 0 else android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE)
                    if (singleLine) maxLines = 1
                    imeOptions = when {
                        onSearch != null -> android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                        singleLine -> android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                        else -> 0
                    }
                    // TalkBack reads the label with the field, as it did when the label was inside Compose's field.
                    if (label != null) accessibilityDelegate = object : View.AccessibilityDelegate() {
                        override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                            super.onInitializeAccessibilityNodeInfo(host, info)
                            info.hintText = label
                        }
                    }
                    setOnEditorActionListener { _, action, _ ->
                        (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH).also { if (it) search?.invoke() }
                    }
                    setText(value)
                    setSelection(value.length)
                    addTextChangedListener(object : android.text.TextWatcher {
                        override fun beforeTextChanged(t: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(t: CharSequence?, start: Int, before: Int, count: Int) {}
                        override fun afterTextChanged(t: android.text.Editable) = change(t.toString())
                    })
                    // Focus from code (a new search) brings the keyboard up too, and leaving the field puts it away.
                    setOnFocusChangeListener { v, f -> focused = f; if (f) v.showKeyboard() else v.context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(v.windowToken, 0) }
                    edit = this
                    if (autoFocus) post { requestFocus() }
                }
            },
            Modifier.fillMaxWidth(),
            update = { e ->
                if (e.text.toString() != value) { e.setText(value); e.setSelection(value.length) }
                e.setTextColor(s.onSurface.toArgb())
                e.setHintTextColor(s.onSurfaceVariant.toArgb())
                e.hint = placeholder
                e.highlightColor = s.primary.copy(alpha = 0.4f).toArgb()
                if (Build.VERSION.SDK_INT >= 29) {
                    e.textCursorDrawable = android.graphics.drawable.GradientDrawable().apply {
                        setColor(s.primary.toArgb()); setSize((2 * e.resources.displayMetrics.density).toInt(), 0)
                    }
                    listOf(e.textSelectHandle, e.textSelectHandleLeft, e.textSelectHandleRight).forEach { it?.setTint(s.primary.toArgb()) }
                }
            },
        )
    }
}

// Android ignores the request until the window has focus: a field focused as its dialog opens waits for that.
private fun View.showKeyboard() {
    val show = { post { context.getSystemService(InputMethodManager::class.java).showSoftInput(this, 0) } }
    if (hasWindowFocus()) { show(); return }
    viewTreeObserver.addOnWindowFocusChangeListener(object : android.view.ViewTreeObserver.OnWindowFocusChangeListener {
        override fun onWindowFocusChanged(hasFocus: Boolean) {
            if (!hasFocus) return
            viewTreeObserver.removeOnWindowFocusChangeListener(this)
            if (isFocused) show()
        }
    })
}

/** The screen's width in dp, outside composition (Home's first pictures). */
fun screenWidthDp() = appContext.resources.configuration.screenWidthDp

/** The screen's size in dp; recomposes when it turns. */
@Composable
fun screenSizeDp(): androidx.compose.ui.unit.IntSize = androidx.compose.ui.platform.LocalConfiguration.current.let { androidx.compose.ui.unit.IntSize(it.screenWidthDp, it.screenHeightDp) }

/** Asks where to save [name] (the system's file picker); the returned function opens it, [bytes] is what is written. */
@Composable
fun rememberSaveFile(name: String, bytes: () -> ByteArray): () -> Unit {
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { appContext.contentResolver.openOutputStream(uri)!!.use { o -> o.write(bytes()) } }
            .onFailure { toast("Couldn't export: ${it.message}", long = true) }
    }
    return { launcher.launch(name) }
}

/** Asks for a file (the system's file picker); the returned function opens it, [text] gets the file's text. */
@Composable
fun rememberOpenFile(text: (String) -> Unit): () -> Unit {
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { appContext.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() } }
            .onSuccess(text).onFailure { toast("Couldn't read the file: ${it.message}", long = true) }
    }
    return { launcher.launch(arrayOf("application/json", "*/*")) }
}

/** Last.fm's browser auth; it returns to roma://lastfm?token=… (MainActivity.handleLastFm). */
fun connectLastFm() = openUrl(roma.core.LastFm(Store.str("lastfm.key", ""), "").authUrl("${BuildConfig.SLUG}://lastfm"))

/** A comment's HTML as plain text. */
fun htmlToText(html: String) = androidx.core.text.HtmlCompat.fromHtml(html, androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY).toString()

// Balanced wrapping evens out the line lengths instead of leaving one word on the last line; Phrase keeps Japanese
// phrases (bunsetsu) together instead of breaking between any two characters (Android 13+, ignored before).
val lyricBreak = androidx.compose.ui.text.style.LineBreak(androidx.compose.ui.text.style.LineBreak.Strategy.Balanced, androidx.compose.ui.text.style.LineBreak.Strictness.Normal, androidx.compose.ui.text.style.LineBreak.WordBreak.Phrase)

/** The bottom sheet's window: edge to edge, the sheet draws behind the navigation bar. */
val sheetDialogProperties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
