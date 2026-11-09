package com.zakius.caravanserai

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

/** A decoded cover, as Images keeps it. It is a hardware bitmap unless something reads its pixels. */
typealias Pic = android.graphics.Bitmap

fun Pic.toImage(): ImageBitmap = asImageBitmap()

fun picBytes(p: Pic) = p.allocationByteCount

/**
 * The memory for covers, sized from the phone's RAM rather than the Java heap, because most covers are hardware bitmaps
 * whose pixels sit in graphics memory. It is 1/32 of RAM, kept between 48 and 128 MB: about 80 to 200 home cards, or
 * over 600 list rows.
 */
fun picMemory(): Long {
    val info = android.app.ActivityManager.MemoryInfo()
    appContext.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(info)
    val ram = info.totalMem
    val mb = 1024L * 1024
    return (ram / 32).coerceIn(48 * mb, 128 * mb)
}

/**
 * Decodes a cover at least [px] on its short side, sampled down by powers of two. With [exact], it is scaled to exactly
 * [px] wide. The result is a hardware bitmap unless [software] or [exact] asks for readable pixels.
 */
fun decodePic(data: ByteArray, px: Int, exact: Boolean, software: Boolean): Pic? {
    val o = android.graphics.BitmapFactory.Options()
    o.inJustDecodeBounds = true
    android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, o)
    if (o.outWidth <= 0) return null
    var sample = 1
    while (minOf(o.outWidth, o.outHeight) / (sample * 2) >= px) sample *= 2
    val opts = android.graphics.BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = if (software || exact) {
            android.graphics.Bitmap.Config.ARGB_8888
        } else {
            android.graphics.Bitmap.Config.HARDWARE
        }
    }
    val b = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, opts) ?: return null
    if (!exact) return b
    val h = maxOf(1, px * b.height / b.width)
    return android.graphics.Bitmap.createScaledBitmap(b, px, h, true)
}

/** The application context, set first thing at start (App). */
lateinit var appContext: Context

private val main = Handler(Looper.getMainLooper())

fun postMain(delayMs: Long = 0, f: () -> Unit) {
    main.postDelayed(f, delayMs)
}

/** Milliseconds since boot, sleep included. The sleep timer uses this clock. */
fun elapsedRealtime() = SystemClock.elapsedRealtime()

fun uptimeMillis() = SystemClock.uptimeMillis()

fun toast(text: String, long: Boolean = false) {
    val duration = if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
    Toast.makeText(appContext, text, duration).show()
}

fun openUrl(url: String) {
    val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    appContext.startActivity(view)
}

/** The system's share sheet with a link. */
fun share(text: String, title: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, text)
        .putExtra(Intent.EXTRA_TITLE, title)
    appContext.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) = androidx.activity.compose.BackHandler(enabled, onBack)

val platformIcu = Icu { id, text -> android.icu.text.Transliterator.getInstance(id).transliterate(text) }

/** Screen pixels per dp. */
fun screenDensity() = appContext.resources.displayMetrics.density

/** Called when the current song's like changes, so the media notification's Like button follows. */
fun likeChanged() {
    PlaybackService.instance?.updateLikeButton()
}

/**
 * Calls [changed] on the main thread with whether a validated network is up, each time that changes. It uses the
 * system's callback, never polling.
 */
fun watchNetwork(changed: (Boolean) -> Unit) {
    val cm = appContext.getSystemService(android.net.ConnectivityManager::class.java)
    cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(n: android.net.Network, caps: android.net.NetworkCapabilities) {
            val up = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            main.post { changed(up) }
        }

        override fun onLost(n: android.net.Network) {
            main.post { changed(false) }
        }
    }, main)
}

/** True on mobile data or in Battery Saver, where guesses and prefetching wait unless the user allows them. */
fun meteredOrSaving(): Boolean {
    val metered = appContext.getSystemService(android.net.ConnectivityManager::class.java).isActiveNetworkMetered
    return metered || appContext.getSystemService(android.os.PowerManager::class.java).isPowerSaveMode
}

/** The system's colours on Android 12 and later, or null to use the app's own. */
@Composable
fun systemColorScheme(dark: Boolean): ColorScheme? {
    if (Build.VERSION.SDK_INT < 31) return null
    val c = LocalContext.current
    return if (dark) dynamicDarkColorScheme(c) else dynamicLightColorScheme(c)
}

// The system's tonal palettes (Android 12+), by tone: _0 is tone 100, _500 tone 50, _1000 tone 0. Tones between two
// resources are blended.
@RequiresApi(31)
private fun dynamicLightColorScheme(ctx: Context): ColorScheme {
    fun r(id: Int) = Color(ctx.getColor(id))
    val n10 = r(android.R.color.system_neutral1_10)
    val n50 = r(android.R.color.system_neutral1_50)
    val n100 = r(android.R.color.system_neutral1_100)
    return ColorScheme(
        primary = r(android.R.color.system_accent1_600),
        onPrimary = r(android.R.color.system_accent1_0),
        inversePrimary = r(android.R.color.system_accent1_200),
        secondaryContainer = r(android.R.color.system_accent2_100),
        onSecondaryContainer = r(android.R.color.system_accent2_900),
        background = lerp(n10, n50, 0.25f),
        surface = lerp(n10, n50, 0.25f),
        onSurface = r(android.R.color.system_neutral1_900),
        onSurfaceVariant = r(android.R.color.system_neutral2_700),
        surfaceContainer = lerp(n50, n100, 0.2f),
        surfaceContainerLowest = r(android.R.color.system_neutral1_0),
        surfaceContainerHigh = lerp(n50, n100, 0.6f),
        inverseSurface = r(android.R.color.system_neutral1_800),
        inverseOnSurface = n50,
        outline = r(android.R.color.system_neutral2_500),
        outlineVariant = r(android.R.color.system_neutral2_200),
        error = Color(0xFFB3261E), // Material 3's baseline light error colour
    )
}

@RequiresApi(31)
private fun dynamicDarkColorScheme(ctx: Context): ColorScheme {
    fun r(id: Int) = Color(ctx.getColor(id))
    val n900 = r(android.R.color.system_neutral1_900)
    val n800 = r(android.R.color.system_neutral1_800)
    val black = r(android.R.color.system_neutral1_1000)
    return ColorScheme(
        primary = r(android.R.color.system_accent1_200),
        onPrimary = r(android.R.color.system_accent1_800),
        inversePrimary = r(android.R.color.system_accent1_600),
        secondaryContainer = r(android.R.color.system_accent2_700),
        onSecondaryContainer = r(android.R.color.system_accent2_100),
        background = lerp(n900, black, 0.4f),
        surface = lerp(n900, black, 0.4f),
        onSurface = r(android.R.color.system_neutral1_100),
        onSurfaceVariant = r(android.R.color.system_neutral2_200),
        surfaceContainer = lerp(n900, n800, 0.2f),
        surfaceContainerLowest = lerp(n900, black, 0.6f),
        surfaceContainerHigh = lerp(n900, n800, 0.7f),
        inverseSurface = r(android.R.color.system_neutral1_100),
        inverseOnSurface = n800,
        outline = r(android.R.color.system_neutral2_400),
        outlineVariant = r(android.R.color.system_neutral2_700),
        error = Color(0xFFF2B8B5), // Material 3's baseline dark error colour
    )
}

/**
 * A text field: Android's EditText in an outlined box. Compose's own text field adds about 120 KB to the APK (its text
 * input, selection and context menu), while EditText is part of the platform. Text set from outside, such as a picked
 * search suggestion, puts the caret at its end. [onSearch] gives the keyboard a Search key. With [autoFocus], the field
 * takes focus and raises the keyboard as soon as it is on screen, for a dialog that is only for typing.
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
    // The EditText is created only once the field is first on screen. One created in a tab that is built while
    // hidden (buildAhead) stays blank, with no hint or caret, the first time the tab is shown.
    val shown = remember { BooleanArray(1) }
    if (LocalTabShown.current) shown[0] = true
    Column(
        modifier
            .defaultMinSize(minWidth = 280.dp, minHeight = 56.dp)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) s.primary else s.outline,
                shape = shape,
            )
            // The whole box takes a tap, not only the line of text, like Compose's own text field.
            .pointerInput(Unit) {
                detectTapGestures {
                    val field = edit
                    if (field != null) {
                        field.requestFocus()
                        field.showKeyboard()
                    }
                }
            }
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
        val style = MaterialTheme.typography.bodyLarge
        if (shown[0]) AndroidView(
            factory = { c ->
                EditText(c).apply {
                    background = null
                    setPadding(0, 0, 0, 0)
                    includeFontPadding = false
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, style.fontSize.value)
                    letterSpacing = style.letterSpacing.value / style.fontSize.value
                    if (Build.VERSION.SDK_INT >= 28) {
                        lineHeight = (style.lineHeight.value * resources.displayMetrics.scaledDensity).toInt()
                    }
                    val textClass = android.text.InputType.TYPE_CLASS_TEXT
                    if (password) {
                        inputType = textClass or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                    } else if (singleLine) {
                        // Searches, addresses and names: no spell check, which underlines every word in another
                        // language (Кино) or a server name.
                        inputType = textClass or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    } else {
                        inputType = textClass or android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    }
                    if (singleLine) {
                        maxLines = 1
                        // A placeholder too long for the field (a large font) ends in "…", not mid-word.
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }
                    imeOptions = when {
                        onSearch != null -> android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                        singleLine -> android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                        else -> 0
                    }
                    // The label is drawn outside the EditText and hidden from TalkBack, so it is handed to the field as
                    // its hint text, and TalkBack reads it with the field.
                    if (label != null) {
                        accessibilityDelegate = object : View.AccessibilityDelegate() {
                            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                                super.onInitializeAccessibilityNodeInfo(host, info)
                                info.hintText = label
                            }
                        }
                    }
                    setOnEditorActionListener { _, action, _ ->
                        val isSearch = action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                        if (isSearch) search?.invoke()
                        isSearch
                    }
                    setText(value)
                    setSelection(value.length)
                    addTextChangedListener(object : android.text.TextWatcher {
                        override fun beforeTextChanged(t: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(t: CharSequence?, start: Int, before: Int, count: Int) {}
                        override fun afterTextChanged(t: android.text.Editable) = change(t.toString())
                    })
                    // Focus from code (a new search) brings the keyboard up too, and leaving the field puts it away.
                    setOnFocusChangeListener { v, hasFocus ->
                        focused = hasFocus
                        if (hasFocus) {
                            v.showKeyboard()
                        } else {
                            v.context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(v.windowToken, 0)
                        }
                    }
                    edit = this
                    if (autoFocus) post { requestFocus() }
                }
            },
            Modifier.fillMaxWidth(),
            update = { e ->
                if (e.text.toString() != value) {
                    e.setText(value)
                    e.setSelection(value.length)
                }
                e.setTextColor(s.onSurface.toArgb())
                // The placeholder is fainter than typed text, and under a label it shows only while the field has
                // focus, so an empty field never looks filled in.
                e.setHintTextColor(s.onSurfaceVariant.copy(alpha = 0.6f).toArgb())
                e.hint = if (label == null || focused) placeholder else null
                e.highlightColor = s.primary.copy(alpha = 0.4f).toArgb()
                if (Build.VERSION.SDK_INT >= 29) {
                    e.textCursorDrawable = android.graphics.drawable.GradientDrawable().apply {
                        setColor(s.primary.toArgb())
                        setSize((2 * e.resources.displayMetrics.density).toInt(), 0)
                    }
                    val handles = listOf(e.textSelectHandle, e.textSelectHandleLeft, e.textSelectHandleRight)
                    handles.forEach { handle -> handle?.setTint(s.primary.toArgb()) }
                }
            },
        )
    }
}

// Android ignores the request until the window has focus, so a field focused as its dialog opens waits for that.
private fun View.showKeyboard() {
    val show = { post { context.getSystemService(InputMethodManager::class.java).showSoftInput(this, 0) } }
    if (hasWindowFocus()) {
        show()
        return
    }
    viewTreeObserver.addOnWindowFocusChangeListener(object : android.view.ViewTreeObserver.OnWindowFocusChangeListener {
        override fun onWindowFocusChanged(hasFocus: Boolean) {
            if (!hasFocus) return
            viewTreeObserver.removeOnWindowFocusChangeListener(this)
            if (isFocused) show()
        }
    })
}

/** The screen's width in dp, for use outside composition (Home's first pictures). */
fun screenWidthDp() = appContext.resources.configuration.screenWidthDp

/** The screen's size in dp. Callers recompose when the screen turns. */
@Composable
fun screenSizeDp(): androidx.compose.ui.unit.IntSize {
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    return androidx.compose.ui.unit.IntSize(configuration.screenWidthDp, configuration.screenHeightDp)
}

/**
 * Returns a function that opens the system's file picker to choose where to save [name]. [bytes] gives what is written.
 */
@Composable
fun rememberSaveFile(name: String, bytes: () -> ByteArray): () -> Unit {
    val createJson = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(createJson) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { appContext.contentResolver.openOutputStream(uri)!!.use { o -> o.write(bytes()) } }
            .onFailure { error -> toast("Couldn't export: ${error.message}", long = true) }
    }
    return { launcher.launch(name) }
}

/** Returns a function that opens the system's file picker to choose a file. [text] gets the file's text. */
@Composable
fun rememberOpenFile(text: (String) -> Unit): () -> Unit {
    val openDocument = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(openDocument) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { appContext.contentResolver.openInputStream(uri)!!.use { input -> input.readBytes().decodeToString() } }
            .onSuccess(text)
            .onFailure { error -> toast("Couldn't read the file: ${error.message}", long = true) }
    }
    return { launcher.launch(arrayOf("application/json", "*/*")) }
}

/** Opens Last.fm's browser sign-in, which returns to roma://lastfm?token=… (MainActivity.handleLastFm). */
fun connectLastFm() {
    val lastFm = roma.core.LastFm(Store.str("lastfm.key", ""), "")
    openUrl(lastFm.authUrl("${BuildConfig.SLUG}://lastfm"))
}

/** A comment's HTML as plain text. */
fun htmlToText(html: String) = androidx.core.text.HtmlCompat.fromHtml(html, androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY).toString()

// Balanced wrapping evens out the line lengths instead of leaving one word on the last line. Phrase keeps Japanese
// phrases (bunsetsu) together instead of breaking between any two characters; Android 13 and later support it, and
// earlier versions ignore it.
val lyricBreak = androidx.compose.ui.text.style.LineBreak(
    androidx.compose.ui.text.style.LineBreak.Strategy.Balanced,
    androidx.compose.ui.text.style.LineBreak.Strictness.Normal,
    androidx.compose.ui.text.style.LineBreak.WordBreak.Phrase,
)

/** The bottom sheet's window is edge to edge, so the sheet draws behind the navigation bar. */
val sheetDialogProperties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
