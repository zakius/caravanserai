package com.zakius.roma

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** A page opened from a list: an album, playlist or artist, or the sign-in page. [fromPlayer]: Back returns to the player. */
class Page(val kind: String, val id: String, val fromPlayer: Boolean = false)

const val TAB_HOME = 0
const val TAB_SEARCH = 1
const val TAB_LIBRARY = 2
const val TAB_PLAYER = 3
const val TAB_SETTINGS = 4

/** Navigation state, shared so any screen can open a page or switch tabs. The tab and the player's view (song,
 *  lyrics, queue) survive a restart; Root saves them. */
object Nav {
    private var shownTab by mutableStateOf(Store.prefs.getInt("ui.tab", TAB_HOME))
    var tab: Int
        get() = shownTab
        set(v) { if (v != shownTab) { before = shownTab; shownTab = v } }
    /** The tab shown before this one: Back and a swipe down on the player return there. */
    var before = TAB_HOME
        private set
    var playerView by mutableStateOf(Store.prefs.getString("ui.playerView", null) ?: "song")
    val stacks = List(5) { mutableStateListOf<Page>() }

    fun open(p: Page) {
        val fromPlayer = tab == TAB_PLAYER
        if (fromPlayer) tab = TAB_HOME
        stacks[tab].add(Page(p.kind, p.id, fromPlayer))
    }

    fun leavePlayer() {
        tab = if (before != TAB_PLAYER) before else TAB_HOME
    }
}

/** Whether a finger is on the screen, and when one last was: Home changes its tiles only when it isn't. */
object Touch {
    @Volatile var down = false
    @Volatile var last = 0L

    /** Returns once nothing has touched the screen for a second. */
    suspend fun awaitIdle() {
        while (down || uptimeMillis() - last < 1_000) kotlinx.coroutines.delay(250)
    }
}

@Composable
fun Theme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    // The system's colours (Android 12+, from the wallpaper), else the app's own accent on the base scheme.
    var scheme = systemColorScheme(dark) ?: accentScheme(if (dark) darkColorScheme() else lightColorScheme(), backend.brandColor, dark)
    // Options, both off by default. True black: OLED pixels that are off draw no power. Artwork colours: the accent
    // follows the playing song's art (one 24 px decode per song).
    if (dark && Store.bool("ui.trueBlack", false)) {
        val black = androidx.compose.ui.graphics.Color.Black
        scheme = scheme.copy(background = black, surface = black, surfaceContainer = black, surfaceContainerLowest = black)
    }
    if (Store.bool("ui.artColors", false)) {
        Playback.tick
        val thumb = Playback.shown()?.thumb
        var seed by androidx.compose.runtime.remember { mutableStateOf<Int?>(null) }
        androidx.compose.runtime.LaunchedEffect(thumb) { seed = withContext(Dispatchers.IO) { thumb?.let { artColor(it) } } }
        seed?.let { scheme = accentScheme(scheme, it, dark) }
    }
    MaterialTheme(colorScheme = scheme) { Surface(Modifier.fillMaxSize(), content = content) }
}

/** The art's most colourful average: pixels weighted by saturation, so a grey background doesn't wash it out. */
private suspend fun artColor(thumb: String): Int? {
    val image = Images.load(bigThumb(thumb), 24, exact = true, software = true)?.toImage() ?: return null // pixels are read below
    val w0 = image.width
    val h0 = image.height
    val pixels = IntArray(w0 * h0).also { image.readPixels(it) }
    var r = 0.0; var g = 0.0; var b = 0.0; var w = 0.0
    val hsl = FloatArray(3)
    for (x in 0 until w0) for (y in 0 until h0) {
        val px = pixels[y * w0 + x]
        colorToHsl(px, hsl)
        val weight = (hsl[1] * (1 - kotlin.math.abs(hsl[2] * 2 - 1))).toDouble() // saturated and not near black or white
        r += (px shr 16 and 0xff) * weight; g += (px shr 8 and 0xff) * weight; b += (px and 0xff) * weight; w += weight
    }
    if (w < w0 * h0 * 0.05) return null // a grey picture: keep the usual colours
    return rgb((r / w).toInt(), (g / w).toInt(), (b / w).toInt())
}

private fun rgb(r: Int, g: Int, b: Int) = (0xff shl 24) or (r shl 16) or (g shl 8) or b

// androidx.core's ColorUtils.colorToHSL and HSLToColor, the same arithmetic.
private fun colorToHsl(c: Int, out: FloatArray) {
    val rf = (c shr 16 and 0xff) / 255f
    val gf = (c shr 8 and 0xff) / 255f
    val bf = (c and 0xff) / 255f
    val max = maxOf(rf, gf, bf)
    val min = minOf(rf, gf, bf)
    val delta = max - min
    val l = (max + min) / 2f
    var h: Float
    val s: Float
    if (max == min) { h = 0f; s = 0f } else {
        h = when (max) { rf -> ((gf - bf) / delta) % 6f; gf -> (bf - rf) / delta + 2f; else -> (rf - gf) / delta + 4f }
        s = delta / (1f - kotlin.math.abs(2f * l - 1f))
    }
    h = (h * 60f) % 360f
    if (h < 0) h += 360f
    out[0] = h.coerceIn(0f, 360f); out[1] = s.coerceIn(0f, 1f); out[2] = l.coerceIn(0f, 1f)
}

private fun hslToColor(h: Float, s: Float, l: Float): Int {
    val c = (1f - kotlin.math.abs(2 * l - 1f)) * s
    val m = l - 0.5f * c
    val x = c * (1f - kotlin.math.abs(h / 60f % 2f - 1f))
    fun v(f: Float) = (255 * f).roundToInt().coerceIn(0, 255) // Math.round: halves up, as ColorUtils
    return when (h.toInt() / 60) {
        0 -> rgb(v(c + m), v(x + m), v(m))
        1 -> rgb(v(x + m), v(c + m), v(m))
        2 -> rgb(v(m), v(c + m), v(x + m))
        3 -> rgb(v(m), v(x + m), v(c + m))
        4 -> rgb(v(x + m), v(m), v(c + m))
        5, 6 -> rgb(v(c + m), v(m), v(x + m))
        else -> rgb(0, 0, 0)
    }
}

/** The scheme's accents in the art's hue, at the lightness the theme needs for contrast. */
private fun accentScheme(s: ColorScheme, seed: Int, dark: Boolean): ColorScheme {
    val hsl = FloatArray(3)
    colorToHsl(seed, hsl)
    fun tone(sat: Float, light: Float) = androidx.compose.ui.graphics.Color(hslToColor(hsl[0], sat, light))
    val sat = hsl[1].coerceIn(0.35f, 0.8f)
    return if (dark) s.copy(primary = tone(sat, 0.75f), onPrimary = tone(sat, 0.15f), secondaryContainer = tone(sat * 0.6f, 0.25f), onSecondaryContainer = tone(sat, 0.9f), inversePrimary = tone(sat, 0.4f))
    else s.copy(primary = tone(sat, 0.38f), onPrimary = tone(sat, 0.98f), secondaryContainer = tone(sat * 0.6f, 0.88f), onSecondaryContainer = tone(sat, 0.15f), inversePrimary = tone(sat, 0.8f))
}

/** Whether this screen is the one on screen: Root keeps visited tabs, and the screens under a tab's top page, composed;
 *  the player keeps its visited views. Not static: a change then recomposes only the screens that read it, instead of
 *  both tabs' whole trees on every switch (5.0 to 3.7 ms per switch on the phone, 2026-10-27). */
val LocalTabShown = androidx.compose.runtime.compositionLocalOf { true }

// Composed and measured at the size it would be shown at, but taking no space and never placed: not drawn or touched,
// and no semantics for accessibility. Measured, a lazy list has its rows composed and laid out, so showing it costs
// only placing and drawing.
fun Modifier.hidden() = clearAndSetSemantics {}.layout { m, c ->
    if (c.hasBoundedWidth && c.hasBoundedHeight) m.measure(androidx.compose.ui.unit.Constraints.fixed(c.maxWidth, c.maxHeight))
    layout(c.minWidth, c.minHeight) {}
}

/**
 * Adds [ahead] to [visited] one at a time, once start-up is over and while nothing touches the screen: the screens
 * are built hidden before they're first opened, so opening one costs what a return to it does.
 */
suspend fun <T> buildAhead(visited: MutableList<T>, ahead: List<T>) {
    for (v in ahead) {
        kotlinx.coroutines.delay(500)
        Touch.awaitIdle()
        if (v !in visited) visited += v
    }
}

@Composable
fun Root() {
    val tab = Nav.tab
    val stack = Nav.stacks[tab]
    BackHandler(enabled = stack.isNotEmpty() || tab != TAB_HOME) {
        // Picking a song jumps to the player, so leaving it returns to the list it was picked from.
        if (stack.isNotEmpty()) { if (stack.removeAt(stack.size - 1).fromPlayer) Nav.tab = TAB_PLAYER } else if (tab == TAB_PLAYER) Nav.leavePlayer() else Nav.tab = TAB_HOME
    }
    if (!Store.bool("setup.done", false)) return Box(Modifier.fillMaxSize().safeDrawingPadding()) { SetupScreen() }
    DictionaryPrompt()
    Playback.tick // redraw when the player changes, for the Player tab's dimming
    val loaded = Playback.player.mediaItemCount > 0 || Playback.pending != null || Playback.restoring
    androidx.compose.runtime.LaunchedEffect(tab, Nav.playerView) {
        Store.prefs.edit().putInt("ui.tab", tab).putString("ui.playerView", Nav.playerView).apply()
    }
    val saved = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    // Tabs stay composed once visited, so switching back only lays out and draws: rebuilding Home took 10 to 14 ms of
    // the main thread on the phone (2026-10-26); most are built ahead (buildAhead). So do the tab's own screen and the pages under its top page: Back
    // shows the one below as it was. A hidden screen isn't measured, drawn, touched or read by accessibility
    // services; it checks LocalTabShown to pause its own work and Back handling.
    val visited = androidx.compose.runtime.remember { mutableStateListOf(tab) }
    if (tab !in visited) visited += tab
    // Library isn't built ahead: building it loads the library from the network.
    androidx.compose.runtime.LaunchedEffect(Unit) { buildAhead(visited, listOf(TAB_PLAYER, TAB_HOME, TAB_SEARCH, TAB_SETTINGS)) }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Box(Modifier.weight(1f)) {
            for (t in visited) androidx.compose.runtime.key(t) {
                Box(if (t == tab) Modifier.fillMaxSize() else Modifier.hidden()) {
                    val tabStack = Nav.stacks[t]
                    for (level in 0..tabStack.size) {
                        val page = tabStack.getOrNull(level - 1)
                        androidx.compose.runtime.key(level, page) {
                            val top = level == tabStack.size
                            Box(if (top) Modifier.fillMaxSize() else Modifier.hidden()) {
                                androidx.compose.runtime.CompositionLocalProvider(LocalTabShown provides (t == tab && top)) {
                                    // Each tab and page keeps its saved state (scroll position, search text) while another
                                    // one is shown, so going back lands where it was. ponytail: states of closed pages stay
                                    // until the app ends; small.
                                    saved.SaveableStateProvider(if (page != null) "$t/$level/${page.kind}/${page.id}" else "$t") {
                                        when {
                                            page != null -> PageScreen(page)
                                            t == TAB_HOME -> HomeScreen()
                                            t == TAB_SEARCH -> SearchScreen()
                                            t == TAB_LIBRARY -> LibraryScreen()
                                            t == TAB_PLAYER -> PlayerScreen()
                                            else -> SettingsScreen()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        // Fixed order, Player in the middle; Player is dimmed until something is loaded. Compact (48 dp): icon and a small
        // label, no pill, so the player's controls get the height.
        FixedRowsFont { NavBar(tab, loaded) }
    }
}

@Composable
private fun NavBar(tab: Int, loaded: Boolean) {
        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().height(48.dp).background(MaterialTheme.colorScheme.surfaceContainer)) {
            NavItem("Home", Icons.Default.Home, tab == TAB_HOME, true) { Nav.tab = TAB_HOME; Nav.stacks[TAB_HOME].clear() }
            NavItem("Search", Icons.Default.Search, tab == TAB_SEARCH, true) { Nav.tab = TAB_SEARCH; Nav.stacks[TAB_SEARCH].clear() }
            NavItem("Player", Icons.Default.PlayArrow, tab == TAB_PLAYER, loaded) { Nav.tab = TAB_PLAYER }
            NavItem("Library", Icons.AutoMirrored.Filled.List, tab == TAB_LIBRARY, true) { Nav.tab = TAB_LIBRARY; Nav.stacks[TAB_LIBRARY].clear() }
            NavItem("Settings", Icons.Default.Settings, tab == TAB_SETTINGS, true, dot = Update.available != null) { Nav.tab = TAB_SETTINGS; Nav.stacks[TAB_SETTINGS].clear() }
        }
}

/**
 * The fixed-height rows (player controls, nav bar) follow the system font size only up to 1.3×: larger, their labels
 * and times would be cut off, and the rows can't grow without moving the controls. Everything else scales fully.
 */
@Composable
fun FixedRowsFont(content: @Composable () -> Unit) {
    val d = androidx.compose.ui.platform.LocalDensity.current
    if (d.fontScale <= 1.3f) return content()
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, 1.3f), content = content)
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, enabled: Boolean, dot: Boolean = false, onClick: () -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    androidx.compose.foundation.layout.Column(
        Modifier.weight(1f).fillMaxHeight()
            .selectable(selected = selected, enabled = enabled, role = androidx.compose.ui.semantics.Role.Tab, onClick = onClick)
            .dim(if (enabled) 1f else 0.35f),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        androidx.compose.foundation.layout.Box {
            Icon(icon, null, Modifier.size(22.dp), tint = color)
            // Something waits there (an update): a dot, no count, nothing that moves.
            if (dot) androidx.compose.foundation.layout.Box(Modifier.align(androidx.compose.ui.Alignment.TopEnd).size(6.dp).background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape))
        }
        Text(label, fontSize = 10.sp, lineHeight = 12.sp, color = color, maxLines = 1)
    }
}
