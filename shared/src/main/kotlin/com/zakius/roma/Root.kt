package com.zakius.roma

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
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

/** A page opened from a list: an album, playlist or artist, a settings category, or the sign-in page. */
class Page(val kind: String, val id: String)

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
        set(v) {
            // Showing the player shows the player itself, not a page opened from it.
            if (v == TAB_PLAYER) stacks[TAB_PLAYER].clear()
            if (v != shownTab) {
                before = shownTab
                shownTab = v
            }
        }
    /** The tab shown before this one. Back and a swipe down on the player return there. */
    var before = TAB_HOME
        private set
    var playerView by mutableStateOf(Store.prefs.getString("ui.playerView", null) ?: "song")
    val stacks = List(5) { mutableStateListOf<Page>() }

    /** Opens [p] on the shown tab's stack. A page opened from the player stays on the Player tab, so Back returns there. */
    fun open(p: Page) {
        stacks[tab].add(p)
    }

    /**
     * A tap on a tab in the nav bar: another tab is shown as it was left, pages included; a tap on the shown tab goes
     * back to its first screen.
     */
    fun select(t: Int) {
        if (t == tab) stacks[t].clear()
        tab = t
    }

    /** Back: closes the top page, else leaves the player for the tab before it, else goes Home. */
    fun back() {
        val stack = stacks[tab]
        when {
            stack.isNotEmpty() -> stack.removeAt(stack.size - 1)
            tab == TAB_PLAYER -> leavePlayer()
            else -> tab = TAB_HOME
        }
    }

    fun leavePlayer() {
        tab = if (before != TAB_PLAYER) before else TAB_HOME
    }
}

/** Whether a finger is on the screen, and when one last was. Home changes its tiles only while no finger is down. */
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
    // The system's colours (Android 12+, from the wallpaper), or else the app's own accent on the base scheme.
    var scheme = systemColorScheme(dark)
        ?: accentScheme(if (dark) darkColorScheme() else lightColorScheme(), backend.brandColor, dark)
    // Two options, both off by default. True black saves power, because OLED pixels that are off draw none. Artwork
    // colours make the accent follow the playing song's art, at the cost of one 24 px decode per song.
    if (dark && Store.bool("ui.trueBlack", false)) {
        val black = androidx.compose.ui.graphics.Color.Black
        scheme = scheme.copy(
            background = black,
            surface = black,
            surfaceContainer = black,
            surfaceContainerLowest = black,
        )
    }
    if (Store.bool("ui.artColors", false)) {
        Playback.tick
        val thumb = Playback.shown()?.thumb
        var seed by androidx.compose.runtime.remember { mutableStateOf<Int?>(null) }
        androidx.compose.runtime.LaunchedEffect(thumb) {
            seed = withContext(Dispatchers.IO) {
                if (thumb != null) artColor(thumb) else null
            }
        }
        val artSeed = seed
        if (artSeed != null) scheme = accentScheme(scheme, artSeed, dark)
    }
    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize(), content = content)
    }
}

/** The art's most colourful average: pixels weighted by saturation, so a grey background doesn't wash it out. */
private suspend fun artColor(thumb: String): Int? {
    // A software bitmap, because the pixels are read below.
    val pic = Images.load(bigThumb(thumb), 24, exact = true, software = true) ?: return null
    val image = pic.toImage()
    val w0 = image.width
    val h0 = image.height
    val pixels = IntArray(w0 * h0)
    image.readPixels(pixels)
    var r = 0.0
    var g = 0.0
    var b = 0.0
    var w = 0.0
    val hsl = FloatArray(3)
    for (x in 0 until w0) {
        for (y in 0 until h0) {
            val px = pixels[y * w0 + x]
            colorToHsl(px, hsl)
            // Highest for saturated pixels that are not near black or white.
            val weight = (hsl[1] * (1 - kotlin.math.abs(hsl[2] * 2 - 1))).toDouble()
            r += red(px) * weight
            g += green(px) * weight
            b += blue(px) * weight
            w += weight
        }
    }
    // A grey picture keeps the usual colours.
    if (w < w0 * h0 * 0.05) return null
    return rgb((r / w).toInt(), (g / w).toInt(), (b / w).toInt())
}

// A colour is packed as ARGB, 8 bits per channel: alpha in the top byte, blue in the bottom one.
private fun red(c: Int) = (c shr 16) and 0xff
private fun green(c: Int) = (c shr 8) and 0xff
private fun blue(c: Int) = c and 0xff

private const val OPAQUE = 0xff shl 24

private fun rgb(r: Int, g: Int, b: Int) = OPAQUE or (r shl 16) or (g shl 8) or b

// The same arithmetic as androidx.core's ColorUtils.colorToHSL and HSLToColor.
private fun colorToHsl(c: Int, out: FloatArray) {
    val rf = red(c) / 255f
    val gf = green(c) / 255f
    val bf = blue(c) / 255f
    val max = maxOf(rf, gf, bf)
    val min = minOf(rf, gf, bf)
    val delta = max - min
    val l = (max + min) / 2f
    var h: Float
    val s: Float
    if (max == min) {
        h = 0f
        s = 0f
    } else {
        h = when (max) {
            rf -> ((gf - bf) / delta) % 6f
            gf -> (bf - rf) / delta + 2f
            else -> (rf - gf) / delta + 4f
        }
        s = delta / (1f - kotlin.math.abs(2f * l - 1f))
    }
    h = (h * 60f) % 360f
    if (h < 0) h += 360f
    out[0] = h.coerceIn(0f, 360f)
    out[1] = s.coerceIn(0f, 1f)
    out[2] = l.coerceIn(0f, 1f)
}

private fun hslToColor(h: Float, s: Float, l: Float): Int {
    val c = (1f - kotlin.math.abs(2 * l - 1f)) * s
    val m = l - 0.5f * c
    val x = c * (1f - kotlin.math.abs(h / 60f % 2f - 1f))
    // roundToInt rounds halves up, like the Math.round that ColorUtils uses.
    fun v(f: Float) = (255 * f).roundToInt().coerceIn(0, 255)
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
    if (dark) {
        return s.copy(
            primary = tone(sat, 0.75f),
            onPrimary = tone(sat, 0.15f),
            secondaryContainer = tone(sat * 0.6f, 0.25f),
            onSecondaryContainer = tone(sat, 0.9f),
            inversePrimary = tone(sat, 0.4f),
        )
    }
    return s.copy(
        primary = tone(sat, 0.38f),
        onPrimary = tone(sat, 0.98f),
        secondaryContainer = tone(sat * 0.6f, 0.88f),
        onSecondaryContainer = tone(sat, 0.15f),
        inversePrimary = tone(sat, 0.8f),
    )
}

/**
 * Whether this screen is the one shown. Root keeps visited tabs, and the screens under a tab's top page, composed, and
 * the player keeps its visited views. It is a compositionLocalOf rather than a static one, so a change recomposes only
 * the screens that read it instead of both tabs' whole trees on every switch.
 */
val LocalTabShown = androidx.compose.runtime.compositionLocalOf { true }

// The content is composed and measured at the size it would be shown at, but takes no space and is never placed, so
// it is not drawn or touched and has no semantics for accessibility. Measuring it matters: a lazy list then has its
// rows composed and laid out, so showing it later costs only placing and drawing.
fun Modifier.hidden() = clearAndSetSemantics {}.layout { m, c ->
    if (c.hasBoundedWidth && c.hasBoundedHeight) {
        m.measure(androidx.compose.ui.unit.Constraints.fixed(c.maxWidth, c.maxHeight))
    }
    layout(c.minWidth, c.minHeight) {}
}

/**
 * Adds [ahead] to [visited] one at a time, once start-up is over and while nothing touches the screen. The screens are
 * built hidden before they're first opened, so opening one costs no more than returning to it.
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
    // Picking a song jumps to the player, so leaving it returns to the list it was picked from.
    BackHandler(enabled = stack.isNotEmpty() || tab != TAB_HOME) { Nav.back() }
    if (!Store.bool("setup.done", false)) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) { SetupScreen() }
        return
    }
    DictionaryPrompt()
    // Read so the Player tab's dimming redraws when the player changes.
    Playback.tick
    val loaded = Playback.player.mediaItemCount > 0 || Playback.pending != null || Playback.restoring
    androidx.compose.runtime.LaunchedEffect(tab, Nav.playerView) {
        Store.prefs.edit().putInt("ui.tab", tab).putString("ui.playerView", Nav.playerView).apply()
    }
    val saved = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    // Tabs stay composed once visited, so switching back only lays out and draws instead of rebuilding a screen such
    // as Home on the main thread. Most tabs are built ahead (buildAhead). The pages under a tab's top page stay
    // composed too, so Back shows the one below as it was. A hidden screen isn't drawn, touched or read by
    // accessibility services, and it checks LocalTabShown to pause its own work and Back handling.
    val visited = androidx.compose.runtime.remember { mutableStateListOf(tab) }
    if (tab !in visited) visited += tab
    // Library isn't built ahead: building it loads the library from the network.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        buildAhead(visited, listOf(TAB_PLAYER, TAB_HOME, TAB_SEARCH, TAB_SETTINGS))
    }
    // The nav bar's background runs under the system's gesture strip; its items stay above it. The keyboard, when up,
    // covers the nav bar, and the screen above ends at the keyboard.
    val sides = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    Column(Modifier.fillMaxSize().windowInsetsPadding(sides).imePadding()) {
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
                                    // one is shown, so going back lands where it was. ponytail: the states of closed pages
                                    // stay until the app ends, which is acceptable because they are small.
                                    val stateKey = if (page != null) "$t/$level/${page.kind}/${page.id}" else "$t"
                                    saved.SaveableStateProvider(stateKey) {
                                        when {
                                            page != null -> Column(Modifier.fillMaxSize()) {
                                                PageBar(pageTitle(page))
                                                Box(Modifier.weight(1f)) { PageScreen(page) }
                                            }
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
        // The tabs keep a fixed order with Player in the middle, dimmed until something is loaded. The bar is compact
        // (48 dp), with an icon and a small label but no pill, so the player's controls get the height.
        FixedRowsFont { NavBar(tab, loaded) }
    }
}

@Composable
private fun NavBar(tab: Int, loaded: Boolean) {
    val barModifier = Modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.surfaceContainer)
        .navigationBarsPadding()
        .height(48.dp)
    androidx.compose.foundation.layout.Row(barModifier) {
        NavItem("Home", Icons.Default.Home, tab == TAB_HOME, true) { Nav.select(TAB_HOME) }
        NavItem("Search", Icons.Default.Search, tab == TAB_SEARCH, true) { Nav.select(TAB_SEARCH) }
        NavItem("Player", PlayerTabIcon, tab == TAB_PLAYER, loaded) {
            Nav.tab = TAB_PLAYER
        }
        NavItem("Library", LibraryIcon, tab == TAB_LIBRARY, true) { Nav.select(TAB_LIBRARY) }
        NavItem("Settings", Icons.Default.Settings, tab == TAB_SETTINGS, true, dot = Update.available != null) {
            Nav.select(TAB_SETTINGS)
        }
    }
}

/**
 * The fixed-height rows (player controls, nav bar) follow the system font size only up to 1.3×. Any larger and their
 * labels and times would be cut off, and the rows can't grow without moving the controls. Everything else scales fully.
 */
@Composable
fun FixedRowsFont(content: @Composable () -> Unit) {
    val d = androidx.compose.ui.platform.LocalDensity.current
    if (d.fontScale <= 1.3f) return content()
    val capped = androidx.compose.ui.unit.Density(d.density, 1.3f)
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides capped, content = content)
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    enabled: Boolean,
    dot: Boolean = false,
    onClick: () -> Unit,
) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    androidx.compose.foundation.layout.Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .selectable(selected = selected, enabled = enabled, role = androidx.compose.ui.semantics.Role.Tab, onClick = onClick)
            .dim(if (enabled) 1f else 0.35f),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        androidx.compose.foundation.layout.Box {
            Icon(icon, null, Modifier.size(22.dp), tint = color)
            // Something waits there, such as an update. It gets a plain dot: no count, nothing that moves.
            if (dot) {
                val dotModifier = Modifier
                    .align(androidx.compose.ui.Alignment.TopEnd)
                    .size(6.dp)
                    .background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape)
                androidx.compose.foundation.layout.Box(dotModifier)
            }
        }
        Text(label, fontSize = 10.sp, lineHeight = 12.sp, color = color, maxLines = 1)
    }
}

private fun icon(name: String, path: String) = androidx.compose.ui.graphics.vector.ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
    addPath(
        androidx.compose.ui.graphics.vector.PathParser().parsePathString(path).toNodes(),
        fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black),
    )
}.build()

/** The Player tab: a disc (Material's "album"), so it doesn't repeat the play button just above it. */
val PlayerTabIcon by lazy {
    icon(
        "player",
        "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM12,16.5c-2.49,0 -4.5,-2.01 -4.5,-4.5S9.51,7.5 12,7.5s4.5,2.01 4.5,4.5 -2.01,4.5 -4.5,4.5zM12,11c-0.55,0 -1,0.45 -1,1s0.45,1 1,1 1,-0.45 1,-1 -0.45,-1 -1,-1z",
    )
}

/** The Library tab: stacked records with a note (Material's "library_music"), apart from the queue's list. */
val LibraryIcon by lazy {
    icon(
        "library",
        "M20,2H8c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2zM18,7h-3v5.5c0,1.38 -1.12,2.5 -2.5,2.5S10,13.88 10,12.5s1.12,-2.5 2.5,-2.5c0.57,0 1.08,0.19 1.5,0.51V5h4v2zM4,6H2v14c0,1.1 0.9,2 2,2h14v-2H4V6z",
    )
}

/** The queue: lines with a note (Material's "queue_music"). */
val QueueIcon by lazy {
    icon(
        "queue",
        "M15,6H3v2h12V6zM15,10H3v2h12v-2zM3,16h8v-2H3V16zM17,6v8.18C16.69,14.07 16.35,14 16,14c-1.66,0 -3,1.34 -3,3s1.34,3 3,3 3,-1.34 3,-3V8h3V6H17z",
    )
}

/** An empty star (Material's "star_border"), for sources that star rather than like. */
val StarOutlineIcon by lazy {
    icon(
        "star_border",
        "M22,9.24l-7.19,-0.62L12,2 9.19,8.63 2,9.24l5.46,4.73L5.82,21 12,17.27 18.18,21l-1.63,-7.03L22,9.24zM12,15.4l-3.76,2.27 1,-4.28 -3.32,-2.88 4.38,-0.38L12,6.1l1.71,4.04 4.38,0.38 -3.32,2.88 1,4.28L12,15.4z",
    )
}
