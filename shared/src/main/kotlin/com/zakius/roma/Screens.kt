package com.zakius.roma

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.background
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlin.math.roundToInt
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import roma.core.Item
import roma.core.LastFm
import roma.core.Override
import roma.core.Romanizer
import roma.core.Song
import roma.core.item
import roma.core.song

// ---------------------------------------------------------------- Shared pieces

/** A name in the chosen display mode: romanized, both (Latin, original small underneath), ruby (reading above) or original. */
@Composable
fun Name(t: roma.core.Text, mode: String, style: TextStyle = MaterialTheme.typography.bodyLarge, center: Boolean = false, maxLines: Int = 2) {
    val align = if (center) TextAlign.Center else TextAlign.Start
    val small = MaterialTheme.typography.bodySmall
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val mod = if (center) Modifier.fillMaxWidth() else Modifier
    // The style's 1.5 line height parts a wrapped title's lines wider than the title from the artist line under it.
    val wrapping = style.copy(lineHeight = style.fontSize * 1.25f)
    when {
        t.isLatin || Store.reads(t.original) -> Text(t.original, mod, style = wrapping, textAlign = align, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        mode == "romanized" -> Text(t.latin, mod, style = wrapping, textAlign = align, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        mode == "original" -> Text(t.original, mod, style = wrapping, textAlign = align, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        mode == "ruby" -> {
            if (t.pieces.size == t.pieceLatin.size && t.pieces.isNotEmpty()) Box(mod, contentAlignment = if (center) Alignment.TopCenter else Alignment.TopStart) { RubyLine(t.pieces, t.pieceLatin, -1, wrapping, small.copy(color = dim), center, maxLines) }
            else Column(mod) {
                Text(t.latin, Modifier.fillMaxWidth(), style = small, color = dim, textAlign = align, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t.original, Modifier.fillMaxWidth(), style = style, textAlign = align, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        else -> Column(mod) {
            Text(t.latin, Modifier.fillMaxWidth(), style = style, textAlign = align, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.original, Modifier.fillMaxWidth(), style = small, color = dim, textAlign = align, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Art at the size it is drawn, where the source can size it: a 48–128 dp image doesn't need the largest one. */
@Composable
fun shownThumb(url: String?, size: Dp): String? = thumbAt(url, with(LocalDensity.current) { size.roundToPx() })

fun thumbAt(url: String?, px: Int): String? = url?.let { backend.thumbAt(it, px) }

@Composable
fun SongRow(s: Song, highlight: Boolean = false, extra: String? = null, onMenu: () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().lookUpOnPress(s).clickable(onClick = onClick).padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(shownThumb(s.thumb, 48.dp), 48.dp, Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val style = if (highlight) MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) else MaterialTheme.typography.bodyLarge
            Name(s.title, Store.listMode, style)
            val sub = s.artistLatin + (extra?.let { " · $it" } ?: "")
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onMenu) { Icon(Icons.Default.MoreVert, "Song menu") }
    }
}

@Composable
fun ItemRow(item: Item, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().lookUpOnPress(item.playable).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Thumb(shownThumb(item.thumb, 56.dp), 56.dp, Modifier.size(56.dp).clip(if (item.kind == "artist") CircleShape else RoundedCornerShape(4.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Name(item.title, Store.listMode)
            if (item.subtitle.latin.isNotBlank()) Text(item.subtitle.latin, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
internal fun Card(item: Item, onClick: () -> Unit) {
    Column(Modifier.width(140.dp).lookUpOnPress(item.playable).clickable(onClick = onClick).padding(6.dp)) {
        Thumb(shownThumb(item.thumb, 128.dp), 128.dp, Modifier.size(128.dp).clip(if (item.kind == "artist") CircleShape else RoundedCornerShape(6.dp)))
        Spacer(Modifier.height(4.dp))
        Name(item.title, Store.listMode, MaterialTheme.typography.bodyMedium)
        if (item.subtitle.latin.isNotBlank()) Text(item.subtitle.latin, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The song a tap on this card or row plays; null when the tap opens a page. */
private val Item.playable get() = song?.takeIf { kind == "song" }

/** Starts the song's stream lookup when a finger lands on its row, so it is under way before the tap is recognized. */
private fun Modifier.lookUpOnPress(s: Song?) = if (s == null) this else pointerInput(s.id) {
    awaitEachGesture { awaitFirstDown(requireUnconsumed = false); Playback.touched(s) }
}

/** A song plays alone and YouTube's autoplay follows; anything else opens its page. */
fun openItem(item: Item) {
    val s = item.playable
    if (s != null) {
        Playback.play(listOf(s))
        Nav.tab = TAB_PLAYER
    } else Nav.open(Page(item.kind, item.id))
}

/** The ⋯ menu of a song: the same items in the same order everywhere; unavailable ones are dimmed. */
@Composable
fun SongMenu(s: Song, queueIndex: Int?, onClose: () -> Unit) {
    var fixing by remember { mutableStateOf(false) }
    val inQueue = queueIndex != null && queueIndex != Playback.player.currentMediaItemIndex
    val liked = s.id in Playback.liked
    LaunchedEffect(s.id) { Playback.checkLike(s) }
    val link by produceState<String?>(null, s.id) { value = withContext(Dispatchers.IO) { backend.shareUrl(s) } }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    // Fully open at once: half open, the bans at the bottom would need a drag first.
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Name(s.title, "both", MaterialTheme.typography.titleMedium)
                Text(s.artistLatin, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            MenuItem("Play next", true) { Playback.playNext(s); onClose() }
            MenuItem("Add to queue", true) { Playback.addToQueue(s); onClose() }
            MenuItem("Start radio", true) { Playback.startRadio(s); Nav.tab = TAB_PLAYER; onClose() }
            MenuItem(if (liked) "Unlike" else "Like", backend.canLike) { Playback.toggleLike(s); onClose() }
            MenuItem("Go to artist", s.firstArtistId != null) { Nav.open(Page("artist", s.firstArtistId!!)); onClose() }
            MenuItem("Go to album", s.album?.id != null) { Nav.open(Page("album", s.album!!.id!!)); onClose() }
            MenuItem("Share…", link != null) {
                share(link!!, "${s.title.latin} · ${s.artistLatin}")
                onClose()
            }
            MenuItem("Correct romanization…", s.romanizable) { fixing = true }
            val titleKept = Store.keptOriginal("song", s.id, s.title.original)
            MenuItem(if (titleKept) "Romanize title" else "Don't romanize title", titleKept || !s.title.isLatin) {
                Store.toggleKeepOriginal("song", s.id, s.title.original); onClose()
            }
            val artist = s.artists.firstOrNull { it.id != null }
            val artistKept = artist != null && Store.keptOriginal("artist", artist.id, artist.name.original)
            MenuItem(if (artistKept) "Romanize artist" else "Don't romanize artist", artist != null && (artistKept || !artist.name.isLatin)) {
                Store.toggleKeepOriginal("artist", artist!!.id!!, artist.name.original); onClose()
            }
            MenuItem("Remove from queue", inQueue) { Playback.remove(queueIndex!!); onClose() }
            MenuItem("Ban song", true) {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Reject)
                if (Playback.current()?.song?.id == s.id) Playback.never() // bans it, skips it and offers Undo
                else { Store.ban("song", s.id, s.title.latin); toast("Banned ${s.title.latin}") }
                onClose()
            }
            MenuItem("Ban artist", s.firstArtistId != null) {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Reject)
                val a = s.artists.first { it.id != null }
                Store.ban("artist", a.id!!, a.name.latin)
                toast("Banned ${a.name.latin}")
                onClose()
            }
        }
    }
    if (fixing) CorrectDialog(s) { fixing = false; onClose() }
}

@Composable
private fun MenuItem(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        label,
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).dim(if (enabled) 1f else 0.35f).padding(horizontal = 24.dp, vertical = 14.dp),
        style = MaterialTheme.typography.bodyLarge,
    )
}

/** Some name of the song is in a non-Latin script: only then is there a romanization to correct. */
val Song.romanizable get() = !title.isLatin || artists.any { !it.name.isLatin } || album?.name?.isLatin == false

/**
 * Correct the Latin form of the song's title, an artist or the album, for this string, this song or this artist; or,
 * through the field's words underneath, one word's reading.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CorrectDialog(song: Song, onDone: () -> Unit) {
    // The song it was opened for, even if the player moves on to the next one meanwhile.
    val s = remember { song }
    // Only names with something to romanize, each saying what it is: a title and its album are often the same string.
    val named = (listOf("Title" to s.title) + s.artists.map { "Artist" to it.name } + listOfNotNull(s.album?.name?.let { "Album" to it })).filter { !it.second.isLatin }
    val fields = named.map { it.second }
    var field by remember { mutableIntStateOf(0) }
    var scope by remember { mutableStateOf("song") }
    var latin by remember(field) { mutableStateOf(fields[field].latin) }
    var word by remember { mutableStateOf<String?>(null) }
    word?.let { w -> return WordDialog(w, null, s.id, onDone) }
    // The field's words, each a button that opens its reading: only when there is more than one to choose from.
    val words = remember(field) {
        val f = fields[field]
        Store.romanizer.pieces(f.original, fields.map { it.original }).map { it.trim() }.filter { Romanizer.hasNonLatin(it) }
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Correct romanization") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                named.forEachIndexed { i, (what, t) -> Choice("$what: ${t.original}", field == i) { field = i } }
                if (words.size > 1) {
                    Text("Or one word's reading:", style = MaterialTheme.typography.bodySmall)
                    androidx.compose.foundation.layout.FlowRow { words.forEach { w -> TextButton(onClick = { word = w }) { Text(w) } } }
                }
                HorizontalDivider()
                Choice("Only this song", scope == "song") { scope = "song" }
                Choice("This artist's songs", scope == "artist", enabled = s.firstArtistId != null) { scope = "artist" }
                Choice("Everywhere", scope == "text") { scope = "text" }
                OutlinedTextField(latin, { latin = it }, label = "Latin form (empty: automatic)")
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val id = when (scope) { "song" -> s.id; "artist" -> s.firstArtistId ?: ""; else -> "" }
                Store.saveOverride(Override(scope, id, fields[field].original, latin.trim()))
                onDone()
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    val id = when (scope) { "song" -> s.id; "artist" -> s.firstArtistId ?: ""; else -> "" }
                    Store.saveOverride(Override(scope, id, fields[field].original, fields[field].original))
                    onDone()
                }) { Text("Keep original") }
                TextButton(onClick = onDone) { Text("Cancel") }
            }
        },
    )
}

/**
 * A word's reading: what the dictionary says (kana, romaji, dictionary form, part of speech), and the user's own
 * reading for this song or everywhere. [inContext] is the romaji the word has where it was picked; it fills the field.
 * The scope last used is offered first, so a run of fixes during a song needs no extra tap.
 */
@Composable
fun WordDialog(word: String, inContext: String?, songId: String, onDone: () -> Unit) {
    val existing = Store.wordOverride(word, songId)
    val info = remember(word) { runCatching { Store.romanizer.lookUp(word) }.getOrDefault(emptyList()) }
    var everywhere by remember { mutableStateOf(existing?.let { it.id == "" } ?: Store.bool("fix.everywhere", false)) }
    // In context the word may carry the line's capital ("Kimi" first in a line); the reading itself doesn't.
    var latin by remember { mutableStateOf(existing?.latin ?: (inContext ?: Store.romanizer.machine(word, sentence = true, songId = songId)).let { if (it.getOrNull(1)?.isUpperCase() == true) it else it.replaceFirstChar(Char::lowercase) }) }
    fun save(value: String) {
        Store.set("fix.everywhere", everywhere)
        Store.saveWord(word, songId, everywhere, value)
        onDone()
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(word) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val dim = MaterialTheme.colorScheme.onSurfaceVariant
                info.forEach { t ->
                    val base = if (t.dictionaryForm != t.surface) " · from ${t.dictionaryForm} (${t.dictionaryRomaji})" else ""
                    Text("${t.surface}  ${t.kana} · ${t.romaji} · ${t.partOfSpeech}$base", style = MaterialTheme.typography.bodySmall, color = dim)
                }
                OutlinedTextField(latin, { latin = it }, Modifier.padding(top = 8.dp), singleLine = true, label = "Reading (empty: automatic)")
                Choice("In this song", !everywhere) { everywhere = false }
                Choice("Everywhere: every song, title and line", everywhere) { everywhere = true }
            }
        },
        confirmButton = { TextButton(onClick = { save(latin) }) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = { save(word) }) { Text("Keep original") }
                TextButton(onClick = onDone) { Text("Cancel") }
            }
        },
    )
}

@Composable
fun TextInputDialog(title: String, initial: String, onDone: () -> Unit, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(title) },
        text = { OutlinedTextField(value, { value = it }, singleLine = true, autoFocus = true) },
        confirmButton = { TextButton(onClick = { onSave(value); onDone() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

@Composable
internal fun Choice(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    // The row is the one radio button TalkBack sees, with its label.
    Row(
        Modifier.fillMaxWidth().selectable(selected, enabled, role = androidx.compose.ui.semantics.Role.RadioButton, onClick = onClick)
            .dim(if (enabled) 1f else 0.4f).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, null)
        Text(label)
    }
}

/** A loaded page, when it was fetched, and with which [Store.dataVersion]. */
private class Kept(val value: Any, val at: Long, val dataVersion: Int)

// The last results by key, so going back to a page or a tab shows it at once (with its scroll position, see Root).
// ponytail: 20 entries, enough for a few tabs' back stacks; the disk keeps pages beyond that.
private val loaded = object : LinkedHashMap<String, Kept>(32, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Kept>) = size > 20
}

/**
 * Loads a page and shows [loading] (a spinner), the error, or the content.
 *
 * A page loaded before shows at once: from memory, else (with [disk]) from the copy kept across starts. It loads
 * again behind it unless it was fetched in the last 10 minutes with today's [Store.dataVersion], and a failed load
 * keeps the older copy on screen. [rows] lists what each item of the page's LazyColumn ([list]) shows, by index;
 * with it, the new copy takes over only while the items on screen are the same in both, so nothing moves under the
 * finger. Until then it waits for the user to scroll, and the next visit shows it. [load] may hand over its first
 * pages through `partial` while it fetches the rest (they only add rows at the bottom); they show when nothing else
 * is shown yet.
 */
@Composable
private fun <T : Any> Loader(
    key: String,
    load: suspend (partial: suspend (T) -> Unit) -> T,
    list: LazyListState? = null,
    rows: ((T) -> List<Any?>)? = null,
    disk: KSerializer<T>? = null,
    loading: @Composable () -> Unit = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } },
    content: @Composable (T) -> Unit,
) {
    @Suppress("UNCHECKED_CAST")
    var shown by remember(key) { mutableStateOf(loaded[key]?.value as T?) }
    var failed by remember(key) { mutableStateOf<String?>(null) }
    var attempt by remember(key) { mutableIntStateOf(0) }
    LaunchedEffect(key, attempt, Store.dataVersion) {
        failed = null
        val kept = loaded[key]
        if (attempt == 0 && kept != null && kept.dataVersion == Store.dataVersion && System.currentTimeMillis() - kept.at < 10 * 60_000) return@LaunchedEffect
        if (shown == null && disk != null) shown = withContext(Dispatchers.IO) { Store.readPage(key, disk) }
        var partial = false
        val result = withContext(Dispatchers.IO) {
            runCatching { load { p -> withContext(Dispatchers.Main) { if (shown == null || partial) { shown = p; partial = true } } } }
        }
        val fresh = result.getOrElse { if (shown == null || partial) failed = plainError(it); return@LaunchedEffect }
        loaded[key] = Kept(fresh, System.currentTimeMillis(), Store.dataVersion)
        if (disk != null) Store.writePage(key, fresh, disk)
        val old = shown
        if (old != null && !partial && rows != null && list != null) {
            val a = rows(old)
            val b = rows(fresh)
            snapshotFlow { list.layoutInfo.visibleItemsInfo.map { it.index } }.first { on -> on.all { a.getOrNull(it) == b.getOrNull(it) } }
        }
        shown = fresh
    }
    val f = failed
    val r = shown
    when {
        f != null -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Couldn't load this page.", textAlign = TextAlign.Center)
            Text(f, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            TextButton(onClick = { attempt++ }) { Text("Retry") }
        }
        r == null -> loading()
        else -> content(r)
    }
}

/**
 * Dimmed by multiplying each drawing's alpha, not by drawing into an offscreen layer first as Modifier.alpha does: the
 * GPU then doesn't switch buffers for every dimmed control. Fine where the content's own parts don't overlap.
 */
fun Modifier.dim(alpha: Float) = graphicsLayer { this.alpha = alpha; compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha }

@Composable
internal fun Header(text: String) {
    Text(text, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleMedium)
}

// ---------------------------------------------------------------- Home

private const val MIX_PAGES = 10

/**
 * Home: the pinned "Your mixes" tiles, then YouTube's shelves. Signed in, the first [MIX_PAGES] pages load at once
 * to collect the mixes; after that a page loads when the user nears the end.
 *
 * The state lives here rather than in the screen, so going back to Home shows it at once, where it was, and the
 * first page starts loading before the screen is drawn ([start] runs in MainActivity.onCreate). The last Home is
 * kept on disk and shows at once on the next start. It loads again after 30 minutes or a romanization change, and
 * the fresh shelves never move what is on screen: a shelf gets its new contents only if they hold the same items or
 * the shelf is off-screen (otherwise once it scrolls off), new shelves go at the bottom, and nothing is reordered or
 * removed. The mixes follow the same rule. Signing in or out starts Home over.
 */
object Home {
    var sections by mutableStateOf(listOf<Section>())
        private set
    // Read from disk with the shelves, off the main thread; until then the row keeps its place, empty.
    var mixes by mutableStateOf<List<Item>?>(null)
        private set
    var mixesRead by mutableStateOf(false)
        private set
    /** Mixes whose tiles are being changed: dimmed and not tappable until their new picture and title are in. */
    var updating by mutableStateOf(emptySet<String>())
        private set
    /** How many mixes the grid holds this run: mixes found meanwhile go in the row below it, so no tile moves. */
    var gridFor = 0
        private set
    var more by mutableStateOf(true)
        private set
    var failed by mutableStateOf<String?>(null)
        private set
    val list = LazyListState()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var dataVersion = -1
    private var account: String? = null
    private var fetchedAt = 0L
    // Where paging stands; kept across Retry so it carries on from the failed page.
    private var cont: String? = null
    private var page = 0
    private val found = ArrayList<Item>()
    private var looking = false
    // This run's shelves: what the disk copy becomes, with the shelves of the last copy this run hasn't fetched again.
    // Otherwise a start closed after its first page left a one-page copy, and the next start showed only that.
    private val fresh = ArrayList<Section>()
    private var saved = emptyList<Section>()
    // New contents for shelves that were on screen, by shelf index.
    private val waiting = HashMap<Int, Section>()
    private var mixJob: Job? = null

    /** Loads Home unless it is loading or fresh. Call it whenever Home may be shown. */
    fun start() {
        val stale = System.currentTimeMillis() - fetchedAt > 30 * 60_000 || dataVersion != Store.dataVersion
        if (job != null && failed == null && !stale) return
        if (dataVersion != Store.dataVersion || account != backend.account) saved = emptyList()
        dataVersion = Store.dataVersion
        if (account != backend.account) {
            account = backend.account
            if (job != null) {
                sections = emptyList()
                mixes = null // Store.set dropped the other account's mixes from disk
                gridFor = 0
                waiting.clear()
                mixJob?.cancel()
                updating = emptySet()
            }
        }
        if (failed == null) {
            cont = null
            page = 0
            found.clear()
            fresh.clear()
            more = true
            looking = backend.mixes && backend.libraryReady
        }
        failed = null
        fetchedAt = System.currentTimeMillis()
        job?.cancel()
        job = scope.launch { load() }
    }

    @Volatile private var preloaded: java.util.concurrent.FutureTask<Pair<List<Item>?, List<Section>?>>? = null

    /** When Home is the screen a start shows, its copy on disk is read beside the rest of start-up. */
    fun preload() {
        val read = java.util.concurrent.FutureTask { Store.mixes to Store.readPage("home", ListSerializer(Section.serializer())) }
        preloaded = read
        kotlin.concurrent.thread(name = "home") { read.run() }
    }

    // The pictures of the first screen, the mix tiles, decoded into the memory cache now so the first frame has them
    // instead of the next few.
    private fun decodeFirstPictures(mixes: List<Item>?) {
        if (mixes.isNullOrEmpty()) return
        val (_, shown, _) = mixGrid(screenWidthDp(), mixes.size)
        val px = tilePx(mixes.size)
        mixes.take(shown - 1).forEach { item -> thumbAt(item.thumb, px)?.let { Images.prefetch(it, px) } }
    }

    private fun tilePx(n: Int) = (mixGrid(screenWidthDp(), n).third.value * screenDensity()).roundToInt()

    private fun showMixes(m: List<Item>?) {
        mixes = m
        if (gridFor == 0 && !m.isNullOrEmpty()) gridFor = m.size
    }

    /**
     * Fresh mixes into the tiles without changing anything under a finger. Known mixes keep their place (one YouTube
     * dropped stays until the next start) and new ones go after them. A tile whose picture or title changed waits for
     * a second without touches, is dimmed and locked while its new picture loads, then shows it with the new title.
     */
    private fun updateMixes(m: List<Item>) {
        val old = mixes.orEmpty()
        fun key(i: Item) = i.id.removePrefix("VL")
        val byKey = m.associateBy(::key)
        val merged = old.map { byKey[key(it)] ?: it } + m.filter { n -> old.none { key(it) == key(n) } }
        Store.mixes = merged.filter { key(it) in byKey }
        val changed = merged.filter { n -> old.any { key(it) == key(n) && (it.thumb != n.thumb || it.title.original != n.title.original) } }
        mixJob?.cancel()
        if (changed.isEmpty()) return showMixes(merged)
        mixJob = scope.launch {
            Touch.awaitIdle()
            updating = changed.map { it.id }.toSet()
            val px = tilePx(gridFor.takeIf { it > 0 } ?: merged.size)
            // ponytail: 5 s, then the tiles change anyway and a picture still missing loads as usual.
            withTimeoutOrNull(5_000) {
                changed.map { i -> async { thumbAt(i.thumb, px)?.let { Images.load(it, px) } } }.awaitAll()
            }
            showMixes(merged)
            delay(300) // a tap already on its way when the tile changed is not taken for one on the new mix
            updating = emptySet()
        }
    }

    private suspend fun load() = coroutineScope {
        if (sections.isEmpty()) launch {
            val (savedMixes, disk) = withContext(Dispatchers.IO) {
                val pre = preloaded?.get()?.also { preloaded = null }
                pre?.let { decodeFirstPictures(it.first) }
                pre ?: (Store.mixes to Store.readPage("home", ListSerializer(Section.serializer())))
            }
            if (!mixesRead) { if (mixes == null) showMixes(savedMixes); mixesRead = true }
            if (disk != null) saved = disk
            if (sections.isEmpty() && disk != null) sections = disk
        }
        // Waiting contents go in as their shelf leaves the screen. This runs only when the list scrolls.
        launch {
            snapshotFlow { list.layoutInfo.visibleItemsInfo.map { it.index }.toSet() }.collect { on ->
                val off = waiting.keys.filter { it + 1 !in on }
                if (off.isNotEmpty()) sections = sections.toMutableList().also { l -> off.forEach { l[it] = waiting.remove(it)!! } }
            }
        }
        while (more) {
            if (!looking && page > 0) snapshotFlow { list.layoutInfo.run { (visibleItemsInfo.lastOrNull()?.index ?: 0) >= totalItemsCount - 3 } }.first { it }
            val from = cont
            val result = withContext(Dispatchers.IO) {
                runCatching { backend.home(from) }
            }
            val (next, shelves) = result.getOrElse { failed = plainError(it); fetchedAt = 0; return@coroutineScope }
            if (account != backend.account) return@coroutineScope // loaded for the account before a switch: not shown or saved
            page++
            cont = next
            more = next != null
            val before = found.size
            val mixes = backend.mixesIn(shelves.flatMap { it.items })
            found += mixes.filter { m -> found.none { it.id.removePrefix("VL") == m.id.removePrefix("VL") } }
            // The mixes live in the pinned row only.
            val add = shelves.map { Section(it.title, it.items.filter { i -> mixes.none { it.id == i.id } }) }.filter { it.items.isNotEmpty() }
            fresh += add
            Store.writePage("home", fresh + saved.filter { s -> fresh.none { it.title.original == s.title.original } }, ListSerializer(Section.serializer()))
            merge(add)
            // The mixes are spread over several pages (the numbered ones often come after the Supermix), so the
            // row waits for the first MIX_PAGES pages; mixes on pages after those still join it.
            if (looking && (page >= MIX_PAGES || !more) || !looking && found.size > before) {
                looking = false
                updateMixes(backend.mixesIn(found))
            }
        }
    }

    private fun visible() = list.layoutInfo.visibleItemsInfo.map { it.index }.toSet()

    private fun sameItems(a: List<Item>, b: List<Item>) = a.map { it.id } == b.map { it.id }

    /** Fresh shelves into the shown ones, by title; see [Home]. LazyColumn item i + 1 is shelf i. */
    private fun merge(add: List<Section>) {
        val shown = sections.toMutableList()
        val on = visible()
        val matched = HashSet<Int>()
        for (s in add) {
            val i = shown.indices.firstOrNull { it !in matched && shown[it].title.original == s.title.original }
            if (i == null) {
                matched += shown.size
                shown += s
            } else {
                matched += i
                if (sameItems(shown[i].items, s.items) || i + 1 !in on) { shown[i] = s; waiting.remove(i) } else waiting[i] = s
            }
        }
        sections = shown
    }
}

@Composable
fun HomeScreen() {
    LaunchedEffect(Store.dataVersion) { Home.start() }
    // The first frame holds the mixes only, the reason Home is opened; the shelves (YouTube's, which change from start
    // to start) and their pictures come in the frame after.
    var shelves by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { Startup.later { shelves = true } }
    // The mixes take at least the first screen, so the shelves begin below it even when there are few mixes.
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val height = maxHeight
        // Out here, not in a row: a row is composed only once it scrolls into view. The first cards of every shelf
        // come before the later ones of any.
        if (shelves) PrefetchCovers(Home.sections.flatMap { it.items.withIndex() }.sortedBy { it.index }.map { it.value.thumb }, 128.dp, near = 40)
        LazyColumn(Modifier.fillMaxSize(), Home.list) {
            // A library's shelves don't change from start to start, so on a server they follow the tiles at once.
            item(key = "mixes") {
                Column(if (backend.mixes) Modifier.heightIn(min = height) else Modifier) {
                    Text(if (backend.mixes) "Your mixes" else "Your library", Modifier.padding(start = 16.dp, top = 16.dp), style = MaterialTheme.typography.titleMedium)
                    backend.HomeTiles(Home.mixes, Home.mixesRead)
                }
            }
            if (!shelves) return@LazyColumn
            items(Home.sections) { sec ->
                Box(Modifier.padding(start = 16.dp, top = 16.dp)) { Name(sec.title, Store.listMode, MaterialTheme.typography.titleMedium) }
                LazyRow { items(sec.items) { item -> Card(item) { openItem(item) } } }
            }
            item(key = "end") {
                val f = Home.failed
                when {
                    f != null -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Couldn't load Home.", textAlign = TextAlign.Center)
                        Text(f, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                        TextButton(onClick = { Home.start() }) { Text("Retry") }
                    }
                    Home.more -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            }
        }
    }
}

/**
 * The mixes' grid: three columns, every mix and then the Liked music tile, as (columns, tiles shown, tile side).
 * Titles get three lines whatever they say, so a tile's height never depends on its text.
 */
internal fun mixGrid(widthDp: Int, n: Int): Triple<Int, Int, Dp> =
    Triple(3, n + 1, ((widthDp - 16 * 2 - 12 * 2) / 3).dp)

// ---------------------------------------------------------------- Search

private val FILTERS = listOf("Songs", "Albums", "Artists", "Playlists")

@Composable
fun SearchScreen() {
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    // Set by "Search for … instead": YouTube's params for the query as typed, without its spelling correction.
    var asTyped by rememberSaveable { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<Song?>(null) }
    val focus = remember { FocusRequester() }
    // The keyboard comes up for a new search, not when coming back to results; it goes when another tab is shown
    // (this one stays composed, focus included).
    val shown = LocalTabShown.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(shown) { if (!shown) focusManager.clearFocus() else if (query.isEmpty()) focus.requestFocus() }
    // The results take the keyboard's place.
    fun search(q: String) { query = q; submitted = q.trim(); asTyped = null; focusManager.clearFocus() }
    val source = backend
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            query, { query = it },
            Modifier.fillMaxWidth().padding(12.dp).focusRequester(focus),
            placeholder = "Songs, albums, artists",
            singleLine = true,
            onSearch = { search(query) },
        )
        // Fixed order; videos are left out on purpose. With a large system font they wrap onto a second line, in the
        // same order, instead of squeezing the last one into a column of letters.
        androidx.compose.foundation.layout.FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FILTERS.forEachIndexed { i, label -> FilterChip(filter == i, { filter = i; asTyped = null }, label = { Text(label) }) }
        }
        // While the text differs from the last search, YouTube's suggestions take the results' place. The ones shown
        // stay while the next keystroke's load, so typing doesn't flash the old results between them.
        var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
        LaunchedEffect(query) {
            if (query.isBlank() || query.trim() == submitted) { suggestions = emptyList(); return@LaunchedEffect }
            delay(250)
            suggestions = withContext(Dispatchers.IO) { source.suggestions(query) }
        }
        if (suggestions.isNotEmpty() && query.trim() != submitted) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(suggestions) { q -> Text(q, Modifier.fillMaxWidth().clickable { search(q) }.padding(horizontal = 16.dp, vertical = 12.dp)) }
            }
        } else if (submitted.isNotEmpty()) {
            // Songs played before, by their Latin forms, so "kimi no" finds 君の声 even though YouTube wouldn't. These rows
            // show as soon as the local lookup is done and keep their place when YouTube's results arrive below them.
            // The lookup runs off the main thread, once per search, before this search's own results join the index.
            val local by produceState(emptyList<Song>(), submitted, filter) {
                value = if (filter == 0 && !source.searchIsLocal) withContext(Dispatchers.IO) { Store.searchLocal(submitted) } else emptyList()
            }
            // The top row is the likely tap: the first "Seen before" song at once, YouTube's top song when it comes.
            LaunchedEffect(local) { Playback.prepare(local.take(1)) }
            fun LazyListScope.localRows() {
                if (local.isEmpty()) return
                item { Header("Seen before") }
                items(local) { s -> SongRow(s, onMenu = { menuFor = s }) { Playback.play(listOf(s)); Nav.tab = TAB_PLAYER } }
                item { Header(source.name) }
            }
            Loader(listOf(submitted, filter, asTyped).toString(), { partial -> source.search(submitted, filter, asTyped, partial) }, loading = {
                LazyColumn(Modifier.fillMaxSize()) {
                    localRows()
                    item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                }
            }) { found ->
                PrefetchCovers(local.map { it.thumb } + found.items.filter { it.song != null }.map { it.thumb }, 48.dp)
                PrefetchCovers(found.items.filter { it.song == null }.map { it.thumb }, 56.dp)
                LaunchedEffect(found) { Playback.prepare((local.take(1) + listOfNotNull(found.items.firstOrNull()?.playable)).distinctBy { it.id }) }
                LazyColumn(Modifier.fillMaxSize()) {
                    localRows()
                    // YouTube's spelling correction, one line above its results. The corrected query gives way to "…",
                    // so the action stays readable on one line.
                    val corrected = found.corrected
                    if (corrected != null) item {
                        Row(
                            Modifier.fillMaxWidth().clickable { if (found.autoCorrected) asTyped = found.originalParams else search(corrected) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            val style = MaterialTheme.typography.bodyMedium
                            val color = MaterialTheme.colorScheme.primary
                            val (lead, action) = if (found.autoCorrected) "Showing results for “$corrected”" to " · Search as typed" else "Did you mean “$corrected”" to "?"
                            Text(lead, Modifier.weight(1f, fill = false), style = style, color = if (found.autoCorrected) Color.Unspecified else color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(action, style = style, color = color, maxLines = 1)
                        }
                    }
                    if (found.items.isEmpty()) item { Text("No results for “$submitted”. Check the spelling, or try another filter above.", Modifier.padding(16.dp)) }
                    items(found.items) { item ->
                        val s = item.song
                        if (s != null) SongRow(s, onMenu = { menuFor = s }) { openItem(item) } else ItemRow(item) { openItem(item) }
                    }
                }
            }
        }
    }
    menuFor?.let { SongMenu(it, null) { menuFor = null } }
}

// ---------------------------------------------------------------- Library

@Composable
fun LibraryScreen() {
    val source = backend
    if (!source.libraryReady) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(source.libraryHint, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { Nav.open(source.libraryHintPage) }) { Text(if (source.libraryHintPage.kind == "settings") "Settings" else "Sign in") }
        }
        return
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("Playlists", "Albums", "Artists")
    Column(Modifier.fillMaxSize()) {
        TabRow(tab) { tabs.forEachIndexed { i, label -> Tab(tab == i, { tab = i }, text = { Text(label) }) } }
        val list = key(tab) { rememberLazyListState() }
        Loader("library:${source.libraryKey(tab)}", { partial -> source.library(tab, partial) }, list, { items -> items.map { it.id } }, ListSerializer(Item.serializer())) { items ->
            PrefetchCovers(items.map { it.thumb }, 56.dp, list)
            if (items.isEmpty()) Centered("Nothing here yet. ${tabs[tab]} you save in ${source.name} show here.")
            Box {
                LazyColumn(Modifier.fillMaxSize(), list) { items(items) { item -> ItemRow(item) { openItem(item) } } }
                FastScroll(list)
            }
        }
    }
}

// ---------------------------------------------------------------- Album, playlist, artist pages

@Composable
fun PageScreen(p: Page) {
    if (p.kind == "source") return backend.OwnPage(p.id)
    if (p.kind == "settings") return SettingsPage(p.id)
    var menuFor by remember { mutableStateOf<Song?>(null) }
    val list = rememberLazyListState()
    Loader("${p.kind}:${p.id}", { partial -> backend.detail(p, partial) }, list, ::detailRows, Detail.serializer()) { d ->
        // Play and the first row are the likely taps; not while another page covers this one.
        val shown = LocalTabShown.current
        LaunchedEffect(d.songs.firstOrNull()?.id, shown) { if (shown) Playback.prepare(d.songs.take(1)) }
        PrefetchCovers(d.songs.map { it.thumb }, 48.dp, list)
        PrefetchCovers(d.items.map { it.thumb }, 56.dp)
        Box {
        LazyColumn(Modifier.fillMaxSize(), list) {
            item {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Thumb(shownThumb(d.thumb, 96.dp), 96.dp, Modifier.size(96.dp).clip(if (p.kind == "artist") CircleShape else RoundedCornerShape(6.dp)))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Name(d.title, Store.listMode, MaterialTheme.typography.titleLarge)
                        if (d.subtitle.isNotBlank()) Text(d.subtitle, style = MaterialTheme.typography.bodySmall)
                        PageButtons(p, d)
                    }
                }
            }
            itemsIndexed(d.songs) { i, s -> SongRow(s, onMenu = { menuFor = s }) { Playback.play(d.songs, i); Nav.tab = TAB_PLAYER } }
            if (d.items.isNotEmpty()) {
                item { Header("Albums and more") }
                items(d.items) { item -> ItemRow(item) { openItem(item) } }
            }
        }
        FastScroll(list)
        }
    }
    menuFor?.let { SongMenu(it, null) { menuFor = null } }
}

/** Play, and Add to library / Remove from library on a page its source can save (none until the source has said). */
@Composable
private fun PageButtons(p: Page, d: Detail) {
    val scope = rememberCoroutineScope()
    val source = backend
    var state by remember(p) { mutableStateOf<InLibrary?>(null) }
    LaunchedEffect(p) { state = withContext(Dispatchers.IO) { source.saved(p) } }
    Row {
        if (d.songs.isNotEmpty()) TextButton(onClick = { playList(p, d.songs); Nav.tab = TAB_PLAYER }) { Text("Play") }
        state?.let { now ->
            TextButton(onClick = {
                state = InLibrary(!now.saved)
                scope.launch {
                    // Read back after a change: the server may have started getting it.
                    state = if (!withContext(Dispatchers.IO) { source.save(p, !now.saved) }) now.also { toast("Couldn't reach the server") }
                        else withContext(Dispatchers.IO) { source.saved(p) } ?: InLibrary(!now.saved)
                }
            }) { Text(if (now.saved) "Remove from library" else "Add to library") }
        }
    }
    state?.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

/** Generated playlists (mixes, editorial RD… lists) page on as they play; anything else plays what the page holds. */
private fun playList(p: Page, songs: List<Song>) =
    if (p.kind == "playlist" && p.id.removePrefix("VL").startsWith("RD")) Playback.playMix(p.id, songs) else Playback.play(songs)

/** What each LazyColumn item of a page shows, in order: see [Loader]. */
private fun detailRows(d: Detail): List<Any?> =
    listOf(listOf(d.title.original, d.subtitle, d.thumb, d.songs.isEmpty())) + d.songs.map { it.id } +
        (if (d.items.isEmpty()) emptyList() else listOf("more") + d.items.map { it.id })

// ---------------------------------------------------------------- Sign-in

// ---------------------------------------------------------------- Settings

/** The Settings tab: one row per category, each opening its own page ([SettingsPage]) with what it holds right now. */
@Composable
fun SettingsScreen() {
    Store.version
    // Read here, not in the rows: neither is state, and a lazy row only recomposes when what it reads or holds changes.
    val corrections = Store.romanizer.overrides.size
    val bannedSongs = Store.banned.count { it.kind != "artist" }
    val bannedArtists = Store.banned.size - bannedSongs
    val lastFm = Store.str("lastfm.session", "").isNotBlank()
    val place = PLACES.first { it.first == Store.place }.second.lowercase()
    val dictionary = if (Dictionary.installed || backend.romanizes) "" else " · no Japanese dictionary"
    LazyColumn(Modifier.fillMaxSize()) {
        Update.available?.let { v ->
            item { Row1("Update to ${BuildConfig.APP_NAME} $v", Update.status ?: "Restarts ${BuildConfig.APP_NAME}, so the music stops") { Update.install() } }
        }
        item { Category("source", backend.settingsTitle, backend.settingsSummary()) }
        item { Category("romanization", "Romanization", "Placement: $place · schemes · ${count(corrections, "correction") ?: "0 corrections"}$dictionary") }
        item { Category("lyrics", "Lyrics", "Providers · original, romanization and translation lines") }
        item { Category("playback", "Queue and radio", "Radio drift · filters · loudness · downloading ahead") }
        item { Category("appearance", "Appearance", "Left-handed controls · true black · accent colour") }
        item { Category("banned", "Banned", if (bannedSongs + bannedArtists == 0) "Nothing banned" else listOfNotNull(count(bannedSongs, "song"), count(bannedArtists, "artist")).joinToString(" · ")) }
        item { Category("lastfm", "Last.fm", if (lastFm) "Scrobbling" else "Not connected") }
        item { Text("${BuildConfig.APP_NAME} ${BuildConfig.VERSION_NAME}", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun Category(id: String, title: String, subtitle: String) = Row1(title, subtitle) { Nav.open(Page("settings", id)) }

/** One category of [SettingsScreen], opened as a page of the Settings tab, so Back returns to the list. */
@Composable
fun SettingsPage(id: String) {
    Store.version
    when (id) {
        "source" -> backend.Settings()
        "appearance" -> AppearanceSettings()
        "romanization" -> RomanizationSettings()
        "corrections" -> CorrectionSettings()
        "lyrics" -> LyricsSettings()
        "playback" -> PlaybackSettings()
        "banned" -> BannedSettings()
        else -> LastFmSettings()
    }
}

@Composable
private fun AppearanceSettings() = LazyColumn(Modifier.fillMaxSize()) {
    item { Header("Appearance") }
    item { Switch1("Left-handed player controls", "ui.leftHanded", false) }
    item { Switch1("True black in dark mode (saves power on OLED screens)", "ui.trueBlack", false) }
    item { Switch1("Accent colour from the song's artwork", "ui.artColors", false) }
}

@Composable
private fun RomanizationSettings() = LazyColumn(Modifier.fillMaxSize()) {
    item { Header("Romanization") }
    item { Options("Placement", Store.PLACE_KEY, Store.place, PLACES) }
    // Custom: each surface on its own, with the modes a single place can't express (Latin only, original only).
    if (Store.place == "custom") {
        item { Options("Lists", "display.list", "both", MODES) }
        item { Options("Player", "display.player", "both", MODES) }
        item { Options("Lyrics", "lyrics.layout", "lines", listOf("lines" to "A line under the original", "ruby" to "Above each word, like furigana")) }
    }
    item {
        val n = Store.romanizer.overrides.size
        Row1("Corrections", if (n == 0) "None yet · import a file" else "$n · export, import or remove them") { Nav.open(Page("settings", "corrections")) }
    }
    item { Header("Schemes") }
    if (backend.romanizes) item { Text("Your server romanizes names and its lyrics; these apply to lyrics from elsewhere.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
    item { Switch1("Japanese: long vowels with macrons (ō)", "rom.macrons", true) }
    item { Switch1("Chinese: pinyin with tone marks", "rom.tones", true) }
    item { Options("Cyrillic (other languages use their national Latin form)", "rom.cyrillic", "english", listOf("english" to "Russian: English-style", "polish" to "Russian: Polish-style", "iso9" to "ISO 9, every language")) }
    item { Options("Greek", "rom.greek", "classical", listOf("classical" to "Classical, no accents (β = b)", "elot" to "Modern, ELOT 743 (β = v)")) }
    item { Options("Han text without kana is", "rom.han", "ja", listOf("ja" to "Japanese", "zh" to "Chinese")) }
    // Text only in scripts the user reads shows as written, in lists, the player, the notification and lyrics.
    item { Text("Scripts you read (shown as written, not romanized)", Modifier.padding(start = 16.dp, top = 8.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary) }
    items(SCRIPTS) { (k, label) ->
        val read = Store.readScripts
        Row(Modifier.fillMaxWidth().clickable { Store.setReads(k, k !in read) }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(k in read, null, Modifier.padding(end = 8.dp))
            Text(label)
        }
    }
    if (!backend.romanizes) item {
        val dictionary = when {
            Dictionary.installed -> "Installed"
            Dictionary.status != null -> Dictionary.status!!
            else -> "Not installed: kanji stay as written. Tap to install (${Dictionary.DOWNLOAD})"
        }
        Row1("Japanese dictionary", dictionary, enabled = !Dictionary.installed) { Store.setReads("ja", false) }
    }
}

@Composable
private fun CorrectionSettings() {
    val export = rememberSaveFile("${BuildConfig.SLUG}-corrections.json") { Store.exportOverrides().toByteArray() }
    val import = rememberOpenFile { text ->
        runCatching { Store.setOverrides(Store.json.decodeFromString<List<Override>>(text)) }
            .onFailure { toast("Not an overrides file: ${it.message}", long = true) }
    }
    val corrections = Store.romanizer.overrides
    LazyColumn(Modifier.fillMaxSize()) {
        item { Header("Corrections") }
        item { Row1("Export ${count(corrections.size, "correction") ?: "corrections"}", "To one file", enabled = corrections.isNotEmpty()) { export() } }
        item { Row1("Import corrections", "Replaces the current ones") { import() } }
        if (corrections.isEmpty()) item { Text("Long-press a title, artist or lyric word to correct its romanization.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
        items(corrections) { o ->
            val scope = when (o.scope) {
                "song" -> "This song"; "artist" -> "This artist"
                "word" -> if (o.id == "") "Word, everywhere" else "Word, in one song"
                else -> "Everywhere"
            }
            Row1(if (o.latin == o.original) "${o.original}: not romanized" else "${o.original} → ${o.latin}", "$scope · Tap to remove") {
                Store.saveOverride(Override(o.scope, o.id, o.original, ""))
            }
        }
    }
}

@Composable
private fun LyricsSettings() = LazyColumn(Modifier.fillMaxSize()) {
    item { Header("Lyrics") }
    // At least one of the two stays on: with neither, the lyrics would be blank.
    val original = Store.bool("lyrics.layer.original", true)
    val latin = Store.bool("lyrics.layer.latin", true)
    item { Switch1("Show original", "lyrics.layer.original", true, enabled = latin || !original) }
    item { Switch1("Show romanization", "lyrics.layer.latin", true, enabled = original || !latin) }
    item {
        val place = PLACES.first { it.first == Store.place }.second.lowercase()
        Row1("Romanization settings", "Placement: $place · schemes · corrections") { Nav.open(Page("settings", "romanization")) }
    }
    // A global setting: never dimmed by the current song. A song whose translation isn't in the chosen language
    // just shows none.
    item { Switch1("Show translation", "lyrics.layer.translation", true) }
    item {
        val device = java.util.Locale.getDefault().let { it.getDisplayLanguage(it) }
        Options("Translation language", "lyrics.translationLang", "device", listOf("device" to "Device language ($device)" + if (java.util.Locale.getDefault().language == "en") "" else ", then English", "en" to "English", "ja" to "Japanese", "ko" to "Korean", "zh" to "Chinese", "ru" to "Russian"))
    }
    item { Text("Long-press a word in the lyrics to see its reading and correct it, for that song or everywhere.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
    item { Header("Providers") }
    val order = Store.lyricsOrder()
    itemsIndexed(order) { i, p ->
        Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${i + 1}. ${p.name}", Modifier.weight(1f))
            IconButton(onClick = { move(order, i, -1) }, enabled = i > 0) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
            IconButton(onClick = { move(order, i, 1) }, enabled = i < order.size - 1) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
            Switch(Store.lyricsEnabled(p), { Store.set("lyrics.on.${p.id}", it) }, Modifier.padding(end = 16.dp))
        }
    }
    item { Text(backend.lyricsRule, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
    item {
        Playback.tick // the song can start or end while this page is open
        Row1("Search lyrics again for this song", "After changing the providers", enabled = Playback.current()?.song != null) { Playback.refetchLyrics() }
    }
}

@Composable
private fun PlaybackSettings() = LazyColumn(Modifier.fillMaxSize()) {
    item { Header("Queue and radio") }
    item { Options("Radio may drift", "radio.leash", "free", listOf("close" to "Close: same and related artists", "medium" to "Medium: at most half other artists", "free" to backend.freeRadio)) }
    item { Switch1("Prefer audio tracks over music videos", "radio.preferAudio", true) }
    item { Switch1("Hide live, sped up, nightcore, slowed", "radio.hideVersions", true) }
    item { Switch1("Skip banned songs and artists", "radio.skipBanned", true) }
    item { Switch1("Stop when the queue ends", "queue.stopAtEnd", false) }
    item { Header("Playback") }
    item { Switch1("Even out loudness", "play.normalize", true) }
    item { Switch1("Download songs ahead", "play.ahead", false, subtitle = "This song and the next 3, so playback goes on without a network. About 4 MB per song you skip") }
    item { Switch1("Resume when headphones reconnect", "play.resumeOnHeadphones", false, subtitle = "Within an hour of disconnecting") }
    item { Switch1("Preload on mobile data", "prepare.always", false, subtitle = "And in Battery Saver: the first second of songs you'll likely tap, so they start at once. At most 3 MB an hour") }
}

@Composable
private fun BannedSettings() = LazyColumn(Modifier.fillMaxSize()) {
    item { Header("Banned") }
    val banned = Store.banned
    if (banned.isEmpty()) item { Text("Nothing banned. Ban a song or artist from a song's ⋯ menu.", Modifier.padding(horizontal = 16.dp)) }
    items(banned) { b -> Row1("${if (b.kind == "artist") "Artist" else "Song"}: ${b.name}", "Tap to unban") { Store.unban(b.kind, b.id) } }
}

@Composable
private fun LastFmSettings() {
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) } // key, title
    LazyColumn(Modifier.fillMaxSize()) {
        item { Header("Last.fm") }
        item { Row1("API key", Store.str("lastfm.key", "").ifBlank { "Not set" }) { editing = "lastfm.key" to "Last.fm API key" } }
        item {
            Row1("Create an API key", "Opens last.fm/api/account/create; it gives the key and the shared secret") {
                openUrl("https://www.last.fm/api/account/create")
            }
        }
        item { Row1("Shared secret", if (Store.str("lastfm.secret", "").isBlank()) "Not set" else "Set") { editing = "lastfm.secret" to "Last.fm shared secret" } }
        item {
            val connected = Store.str("lastfm.session", "").isNotBlank()
            // A file read: off the main thread, so a switch on this page doesn't wait for it.
            val waiting by produceState(0, connected, Store.version) { value = withContext(Dispatchers.IO) { if (connected) Playback.scrobbleQueue.load().size else 0 } }
            val ready = Store.str("lastfm.key", "").isNotBlank() && Store.str("lastfm.secret", "").isNotBlank()
            Row1(if (connected) "Connected: scrobbling" else "Connect", if (connected) "Tap to disconnect · $waiting waiting" else "Opens the browser to allow access", ready || connected) {
                if (connected) Store.set("lastfm.session", null)
                else connectLastFm()
            }
        }
    }
    editing?.let { (key, title) -> TextInputDialog(title, Store.str(key, ""), { editing = null }) { Store.set(key, it.trim()) } }
}

/** roma's own question before Android's install prompts, when the dictionary is missing or out of date. */
@Composable
fun DictionaryPrompt() {
    val offer = Dictionary.offer ?: return
    AlertDialog(
        onDismissRequest = { Dictionary.answer(false) },
        title = { Text(if (offer == "update") "Update the Japanese dictionary?" else "Install the Japanese dictionary?") },
        text = {
            Text(
                (if (offer == "update") "This version of ${BuildConfig.APP_NAME} needs a newer Japanese dictionary. " else "${BuildConfig.APP_NAME} needs it to romanize kanji. ") +
                    Dictionary.HOW + "\n\n" +
                    "Until then, Japanese gets kana-only romanization and kanji stay as written. You can install it later in Settings."
            )
        },
        confirmButton = { TextButton(onClick = { Dictionary.answer(true) }) { Text(if (offer == "update") "Update" else "Install") } },
        dismissButton = { TextButton(onClick = { Dictionary.answer(false) }) { Text("Not now") } },
    )
}

/**
 * First start: which scripts to romanize. Japanese needs the dictionary app, fetched once if picked, unless the
 * source romanizes ([Source.romanizes]); the others need nothing. Unpicked scripts go into "scripts you read" and show
 * as written. Shown until Continue.
 */
@Composable
fun SetupScreen() {
    Store.version
    if (!backend.setUp) return backend.Setup()
    val read = Store.readScripts
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text("Which scripts should ${BuildConfig.APP_NAME} romanize?", style = MaterialTheme.typography.headlineSmall)
        Text("The others show as written. You can change this in Settings.", Modifier.padding(top = 8.dp, bottom = 16.dp), style = MaterialTheme.typography.bodyMedium)
        SCRIPTS.forEach { (k, label) ->
            Row(Modifier.fillMaxWidth().clickable { Store.set(Store.READ_KEY, (if (k in read) read - k else read + k).joinToString(",")) }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(k !in read, null, Modifier.padding(end = 8.dp))
                Column {
                    Text(label)
                    if (k == "ja" && !backend.romanizes) Text(if (Dictionary.installed) "Dictionary installed" else "Needs the Japanese dictionary: ${Dictionary.DOWNLOAD}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Button(
            onClick = {
                Store.set("setup.done", true)
                if ("ja" !in read) Dictionary.install()
            },
            Modifier.padding(top = 24.dp).align(Alignment.End),
        ) { Text("Continue") }
    }
}

private val SCRIPTS = listOf("ja" to "Japanese", "zh" to "Chinese", "ko" to "Korean", "cyrillic" to "Cyrillic", "greek" to "Greek", "other" to "Other scripts")

private val PLACES = listOf("below" to "Under the original", "above" to "Above each word, like furigana", "custom" to "Differently in lists, the player and lyrics")

private val MODES = listOf("both" to "Latin, original underneath", "ruby" to "Reading above each word, like furigana", "romanized" to "Latin only", "original" to "Original only")

private fun move(order: List<roma.core.LyricsProvider>, i: Int, by: Int) {
    val list = order.toMutableList()
    val p = list.removeAt(i)
    list.add(i + by, p)
    Store.set("lyrics.order", list.joinToString(",") { it.id })
}

@Composable
internal fun Row1(title: String, subtitle: String, enabled: Boolean = true, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).dim(if (enabled) 1f else 0.4f).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun Switch1(title: String, key: String, def: Boolean, enabled: Boolean = true, subtitle: String? = null) {
    val on = Store.bool(key, def)
    Row(Modifier.fillMaxWidth().clickable(enabled) { Store.set(key, !on) }.dim(if (enabled) 1f else 0.4f).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title)
            if (subtitle != null) Text(subtitle, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(on, { Store.set(key, it) }, enabled = enabled)
    }
}

@Composable
internal fun Options(title: String, key: String, def: String, options: List<Pair<String, String>>) {
    val value = Store.str(key, def)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(title, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        options.forEach { (v, label) -> Choice(label, value == v) { Store.set(key, v) } }
    }
}

/** "1 song", "3 songs"; null for none. */
private fun count(n: Int, what: String) = if (n == 0) null else "$n $what" + if (n == 1) "" else "s"

/** A failure in plain words, with what to do about it. */
fun plainError(t: Throwable): String = when (generateSequence(t) { it.cause }.firstOrNull { it is java.io.IOException } ?: t) {
    is java.net.UnknownHostException, is java.net.ConnectException, is java.net.NoRouteToHostException -> "No connection. Check Wi-Fi or mobile data, then Retry."
    is java.net.SocketTimeoutException -> "${backend.name} took too long to answer. Retry in a moment."
    else -> t.message ?: t.toString()
}

/**
 * A thumb on the right edge of a long list (over 3 screens) to drag through it. It shows while the list scrolls and
 * for a moment after, and only then takes touches, so the rows' ⋯ buttons under it work the rest of the time.
 */
@Composable
fun androidx.compose.foundation.layout.BoxScope.FastScroll(list: LazyListState) {
    val info = list.layoutInfo
    val visible = info.visibleItemsInfo.size
    val total = info.totalItemsCount
    if (total <= visible * 3 || visible == 0) return
    var dragging by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(list.isScrollInProgress, dragging) {
        if (list.isScrollInProgress || dragging) shown = true else { kotlinx.coroutines.delay(1500); shown = false }
    }
    val alpha by androidx.compose.animation.core.animateFloatAsState(if (shown) 1f else 0f, label = "fastScroll")
    if (alpha == 0f) return
    val scope = rememberCoroutineScope()
    var height by remember { mutableIntStateOf(1) }
    val thumb = with(LocalDensity.current) { 56.dp.toPx() }
    val fraction = list.firstVisibleItemIndex.toFloat() / (total - visible).coerceAtLeast(1)
    Box(
        Modifier.align(Alignment.TopEnd).fillMaxHeight().width(32.dp).onSizeChanged { height = it.height }
            .pointerInput(total) {
                detectVerticalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, _ ->
                    change.consume()
                    val f = ((change.position.y - thumb / 2) / (height - thumb)).coerceIn(0f, 1f)
                    scope.launch { list.scrollToItem((f * (total - visible)).toInt()) }
                }
            }
            .dim(alpha),
    ) {
        Box(
            Modifier.align(Alignment.TopEnd).padding(end = 4.dp)
                .offset { androidx.compose.ui.unit.IntOffset(0, (fraction.coerceIn(0f, 1f) * (height - thumb)).toInt()) }
                .size(width = 8.dp, height = 56.dp).clip(RoundedCornerShape(4.dp))
                .background(if (dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant),
        )
    }
}
