package com.zakius.caravanserai

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
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

/**
 * A name in the chosen display mode: romanized, both (Latin, original small underneath), ruby (reading above) or
 * original. The original under the Latin form is in the name's own colour, only smaller, so it reads as part of the
 * name and apart from the dimmed artist line below it. The Latin form gets [maxLines] in every mode.
 */
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
            if (t.pieces.size == t.pieceLatin.size && t.pieces.isNotEmpty()) {
                val boxAlign = if (center) Alignment.TopCenter else Alignment.TopStart
                Box(mod, contentAlignment = boxAlign) {
                    RubyLine(t.pieces, t.pieceLatin, -1, wrapping, small.copy(color = dim), center, maxLines)
                }
            } else {
                Column(mod) {
                    Text(t.latin, Modifier.fillMaxWidth(), style = small, color = dim, textAlign = align, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(t.original, Modifier.fillMaxWidth(), style = style, textAlign = align, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        else -> Column(mod) {
            Text(t.latin, Modifier.fillMaxWidth(), style = wrapping, textAlign = align, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
            Text(t.original, Modifier.fillMaxWidth(), style = small.copy(color = style.color), textAlign = align, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Art at the size it is drawn, where the source can size it, because a 48–128 dp image doesn't need the largest one. */
@Composable
fun shownThumb(url: String?, size: Dp): String? = thumbAt(url, with(LocalDensity.current) { size.roundToPx() })

fun thumbAt(url: String?, px: Int): String? = url?.let { backend.thumbAt(it, px) }

/**
 * A song in a list, with its length on the right ([length]). On an album page [number] (its track number) takes the
 * cover's place, and the artist line is left out when it is the album's own ([artist] false).
 */
@Composable
fun SongRow(
    s: Song,
    highlight: Boolean = false,
    extra: String? = null,
    number: Int? = null,
    artist: Boolean = true,
    length: Boolean = true,
    onMenu: () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .lookUpOnPress(s)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (number != null) {
            val color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            Box(Modifier.size(width = 28.dp, height = 40.dp), contentAlignment = Alignment.CenterStart) {
                Text("$number", style = MaterialTheme.typography.bodyMedium, color = color, maxLines = 1)
            }
        } else {
            Thumb(shownThumb(s.thumb, 48.dp), 48.dp, Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val body = MaterialTheme.typography.bodyLarge
            val style = if (highlight) {
                body.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            } else {
                body
            }
            Name(s.title, Store.listMode, style)
            val sub = listOfNotNull(if (artist) s.artistLatin else null, extra).joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (length && s.durationSec > 0) {
            Text(
                time(s.durationSec * 1000L),
                Modifier.padding(start = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        IconButton(onClick = onMenu) { Icon(Icons.Default.MoreVert, "Song menu") }
    }
}

@Composable
fun ItemRow(item: Item, onClick: () -> Unit) {
    val rowModifier = Modifier
        .fillMaxWidth()
        .lookUpOnPress(item.playable)
        .clickable(onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 6.dp)
    Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
        val shape = if (item.kind == "artist") CircleShape else RoundedCornerShape(4.dp)
        Thumb(shownThumb(item.thumb, 48.dp), 48.dp, Modifier.size(48.dp).clip(shape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Name(item.title, Store.listMode)
            if (item.subtitle.latin.isNotBlank()) {
                Text(
                    item.subtitle.latin,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
internal fun Card(item: Item, onClick: () -> Unit) {
    // With a large font the card and its picture grow too, up to 1.6×, so a title fits its width instead of breaking
    // inside a word.
    val grow = LocalDensity.current.fontScale.coerceIn(1f, 1.6f)
    val cardModifier = Modifier
        .width(140.dp * grow)
        .lookUpOnPress(item.playable)
        .clickable(onClick = onClick)
        .padding(6.dp)
    Column(cardModifier) {
        val shape = if (item.kind == "artist") CircleShape else RoundedCornerShape(6.dp)
        Thumb(shownThumb(item.thumb, 128.dp * grow), 128.dp * grow, Modifier.size(128.dp * grow).clip(shape))
        Spacer(Modifier.height(4.dp))
        Name(item.title, Store.listMode, MaterialTheme.typography.bodyMedium)
        if (item.subtitle.latin.isNotBlank()) {
            val large = LocalDensity.current.fontScale > 1.3f
            Text(
                item.subtitle.latin,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (large) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The song a tap on this card or row plays, or null when the tap opens a page. */
private val Item.playable get() = if (kind == "song") song else null

/** Starts the song's stream lookup when a finger lands on its row, so it is under way before the tap is recognized. */
private fun Modifier.lookUpOnPress(s: Song?) = if (s == null) this else pointerInput(s.id) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        Playback.touched(s)
    }
}

/** A song plays alone and YouTube's autoplay follows; anything else opens its page. */
fun openItem(item: Item) {
    val s = item.playable
    if (s != null) {
        Playback.play(listOf(s))
        Nav.tab = TAB_PLAYER
    } else {
        Nav.open(Page(item.kind, item.id))
    }
}

/** The ⋯ menu of a song. It has the same items in the same order everywhere, and unavailable ones are dimmed. */
@Composable
fun SongMenu(s: Song, queueIndex: Int?, onClose: () -> Unit) {
    var fixing by remember { mutableStateOf(false) }
    val inQueue = queueIndex != null && queueIndex != Playback.player.currentMediaItemIndex
    val liked = s.id in Playback.liked
    LaunchedEffect(s.id) { Playback.checkLike(s) }
    val link by produceState<String?>(null, s.id) { value = withContext(Dispatchers.IO) { backend.shareUrl(s) } }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    // It opens fully at once, because half open, the bans at the bottom would need a drag first. It closes while the
    // correction dialog is open, so the dialog has no sheet behind it.
    if (!fixing) ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Name(s.title, "both", MaterialTheme.typography.titleMedium)
                Text(
                    s.artistLatin,
                    Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            MenuItem("Play next", true, Icons.Default.PlayArrow) {
                Playback.playNext(s)
                onClose()
            }
            MenuItem("Add to queue", true, QueueIcon) {
                Playback.addToQueue(s)
                onClose()
            }
            MenuItem("Start radio", true, RadioIcon) {
                Playback.startRadio(s)
                Nav.tab = TAB_PLAYER
                onClose()
            }
            MenuItem(likeAction(liked), backend.canLike, likeIcon(liked), whyOff = "Sign in to ${backend.name} to ${likeWord.lowercase()} songs") {
                Playback.toggleLike(s)
                onClose()
            }
            val page = Nav.stacks[Nav.tab].lastOrNull()
            val onArtist = page?.kind == "artist" && page.id == s.firstArtistId
            val onAlbum = page?.kind == "album" && page.id == s.album?.id
            MenuItem(
                "Go to artist",
                s.firstArtistId != null && !onArtist,
                Icons.Default.Person,
                whyOff = if (onArtist) "You're on this artist's page" else "This song has no artist page",
            ) {
                Nav.open(Page("artist", s.firstArtistId!!))
                onClose()
            }
            MenuItem(
                "Go to album",
                s.album?.id != null && !onAlbum,
                PlayerTabIcon,
                whyOff = if (onAlbum) "You're on this album" else "This song has no album",
            ) {
                Nav.open(Page("album", s.album!!.id!!))
                onClose()
            }
            if (backend.canShare) {
                MenuItem("Share…", link != null, Icons.Default.Share, whyOff = "This song has no link to share") {
                    share(link!!, "${s.title.latin} · ${s.artistLatin}")
                    onClose()
                }
            }
            MenuItem("Correct romanization…", s.romanizable, Icons.Default.Edit, whyOff = "Nothing to correct: the names are in Latin letters") { fixing = true }
            val titleKept = Store.keptOriginal("song", s.id, s.title.original)
            MenuItem(
                if (titleKept) "Romanize title" else "Don't romanize title",
                titleKept || !s.title.isLatin,
                ScriptIcon,
                whyOff = "The title is in Latin letters already",
            ) {
                Store.toggleKeepOriginal("song", s.id, s.title.original)
                onClose()
            }
            val artist = s.artists.firstOrNull { it.id != null }
            val artistKept = artist != null && Store.keptOriginal("artist", artist.id, artist.name.original)
            val artistLabel = if (artistKept) "Romanize artist" else "Don't romanize artist"
            MenuItem(artistLabel, artist != null && (artistKept || !artist.name.isLatin), ScriptIcon, whyOff = "The artist's name is in Latin letters already") {
                Store.toggleKeepOriginal("artist", artist!!.id!!, artist.name.original)
                onClose()
            }
            MenuItem("Remove from queue", inQueue, Icons.Default.Delete, whyOff = "Only songs in the queue, other than the playing one, can be removed") {
                Playback.remove(queueIndex!!)
                onClose()
            }
            // The bans are set apart at the bottom, in the error colour.
            HorizontalDivider()
            MenuItem("Ban song", true, BanIcon, danger = true) {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Reject)
                if (Playback.current()?.song?.id == s.id) {
                    // Bans the playing song, skips it and offers Undo.
                    Playback.never()
                } else {
                    Store.ban("song", s.id, s.title.latin)
                    toast("Banned ${s.title.latin}")
                }
                onClose()
            }
            MenuItem("Ban artist", s.firstArtistId != null, BanIcon, danger = true, whyOff = "This song has no artist page") {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Reject)
                val a = s.artists.first { it.id != null }
                Store.ban("artist", a.id!!, a.name.latin)
                toast("Banned ${a.name.latin}")
                onClose()
            }
        }
    }
    if (fixing) {
        CorrectDialog(s) {
            fixing = false
            onClose()
        }
    }
}

/**
 * A song menu item: its icon and label. A dimmed one stays tappable when it has [whyOff], and a tap says why it is
 * off, in a toast: the menu covers the player's controls, so a toast there hides nothing.
 */
@Composable
private fun MenuItem(
    label: String,
    enabled: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    danger: Boolean = false,
    whyOff: String? = null,
    onClick: () -> Unit,
) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled || whyOff != null, onClick = if (enabled) onClick else ({ toast(whyOff!!) }))
            .dim(if (enabled) 1f else 0.35f)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val iconColor = if (danger) color else MaterialTheme.colorScheme.onSurfaceVariant
        Icon(icon, null, Modifier.size(24.dp), tint = iconColor)
        Spacer(Modifier.width(16.dp))
        Text(label, color = color, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Whether some name of the song is in a non-Latin script. Only then is there a romanization to correct. */
val Song.romanizable get() = !title.isLatin || artists.any { !it.name.isLatin } || album?.name?.isLatin == false

/**
 * Corrects the Latin form of the song's title, an artist or the album, for this string, this song or this artist.
 * The field's words underneath lead to correcting one word's reading instead.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CorrectDialog(song: Song, onDone: () -> Unit) {
    // The song it was opened for, even if the player moves on to the next one meanwhile.
    val s = remember { song }
    // Only names with something to romanize, each labelled with what it is, because a title and its album are often
    // the same string.
    val titleField = listOf("Title" to s.title)
    val artistFields = s.artists.map { artist -> "Artist" to artist.name }
    val albumName = s.album?.name
    val albumField = if (albumName != null) listOf("Album" to albumName) else emptyList()
    val named = (titleField + artistFields + albumField).filter { !it.second.isLatin }
    val fields = named.map { it.second }
    var field by remember { mutableIntStateOf(0) }
    var scope by remember { mutableStateOf("song") }
    var latin by remember(field) { mutableStateOf(fields[field].latin) }
    var word by remember { mutableStateOf<String?>(null) }
    val pickedWord = word
    if (pickedWord != null) {
        WordDialog(pickedWord, null, s.id, onDone)
        return
    }
    // The field's words, each a button that opens its reading. They show only when there is more than one.
    val words = remember(field) {
        val f = fields[field]
        val pieces = Store.romanizer.pieces(f.original, fields.map { it.original })
        pieces
            .map { piece -> piece.trim() }
            .filter { piece -> Romanizer.hasNonLatin(piece) }
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Correct romanization") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                GroupLabel("Name")
                named.forEachIndexed { i, (what, t) -> Choice("$what: ${t.original}", field == i, modifier = onEdge) { field = i } }
                if (words.size > 1) {
                    Text("Or one word's reading:", style = MaterialTheme.typography.bodySmall)
                    androidx.compose.foundation.layout.FlowRow {
                        words.forEach { w ->
                            TextButton(onClick = { word = w }) { Text(w) }
                        }
                    }
                }
                GroupLabel("Applies to")
                Choice("Only this song", scope == "song", modifier = onEdge) { scope = "song" }
                Choice("This artist's songs", scope == "artist", enabled = s.firstArtistId != null, modifier = onEdge) { scope = "artist" }
                Choice("Everywhere", scope == "text", modifier = onEdge) { scope = "text" }
                OutlinedTextField(latin, { latin = it }, label = "Latin form (empty: automatic)")
            }
        },
        confirmButton = {
            Button(onClick = {
                val id = when (scope) {
                    "song" -> s.id
                    "artist" -> s.firstArtistId ?: ""
                    else -> ""
                }
                Store.saveOverride(Override(scope, id, fields[field].original, latin.trim()))
                onDone()
            }) { Text("Save") }
        },
        dismissButton = {
            run {
                TextButton(onClick = {
                    val id = when (scope) {
                        "song" -> s.id
                        "artist" -> s.firstArtistId ?: ""
                        else -> ""
                    }
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
 * reading for this song or everywhere. [inContext] is the romaji the word has where it was picked, and it fills the
 * field. The scope last used is offered first, so a run of fixes during a song needs no extra tap.
 */
@Composable
fun WordDialog(word: String, inContext: String?, songId: String, onDone: () -> Unit) {
    val existing = Store.wordOverride(word, songId)
    val info = remember(word) { runCatching { Store.romanizer.lookUp(word) }.getOrDefault(emptyList()) }
    var everywhere by remember {
        val initial = if (existing != null) existing.id == "" else Store.bool("fix.everywhere", false)
        mutableStateOf(initial)
    }
    var latin by remember {
        val initial = if (existing != null) {
            existing.latin
        } else {
            val guess = inContext ?: Store.romanizer.machine(word, sentence = true, songId = songId)
            // In context the word may carry the line's capital ("Kimi" first in a line), but the reading itself
            // doesn't. A word whose second letter is a capital too, such as an acronym, keeps its case.
            val keepsCase = guess.getOrNull(1)?.isUpperCase() == true
            if (keepsCase) guess else guess.replaceFirstChar(Char::lowercase)
        }
        mutableStateOf(initial)
    }
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
                Choice("In this song", !everywhere, modifier = onEdge) { everywhere = false }
                Choice("Everywhere: every song, title and line", everywhere, modifier = onEdge) { everywhere = true }
            }
        },
        confirmButton = { Button(onClick = { save(latin) }) { Text("Save") } },
        dismissButton = {
            run {
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
        text = { OutlinedTextField(value, { value = it }, singleLine = true, autoFocus = true, label = title) },
        confirmButton = {
            TextButton(onClick = {
                onSave(value)
                onDone()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

/** A radio button row. In a dialog, [modifier] can shift it so the circle, not its padding, sits on the text's edge. */
@Composable
internal fun Choice(label: String, selected: Boolean, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // The row is the one radio button TalkBack sees, with its label.
    Row(
        modifier
            .fillMaxWidth()
            .selectable(selected, enabled, role = androidx.compose.ui.semantics.Role.RadioButton, onClick = onClick)
            .dim(if (enabled) 1f else 0.4f)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, null)
        Text(label)
    }
}

/** Moves a dialog's radio rows left by the button's padding, so the circles line up with the dialog's text. */
private val onEdge = Modifier.offset(x = (-12).dp)

/** A loaded page, when it was fetched, and with which [Store.dataVersion]. */
private class Kept(val value: Any, val at: Long, val dataVersion: Int)

// The last results by key, so going back to a page or a tab shows it at once, with its scroll position (see Root).
// The map is in access order, so the least recently used page goes first. ponytail: 20 entries are enough for a few
// tabs' back stacks, and the disk keeps pages beyond that.
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
    failedTitle: String = "Couldn't load this page.",
    loading: @Composable () -> Unit = { Loading() },
    content: @Composable (T) -> Unit,
) {
    @Suppress("UNCHECKED_CAST")
    var shown by remember(key) { mutableStateOf(loaded[key]?.value as T?) }
    var failed by remember(key) { mutableStateOf<String?>(null) }
    var attempt by remember(key) { mutableIntStateOf(0) }
    LaunchedEffect(key, attempt, Store.dataVersion) {
        failed = null
        val kept = loaded[key]
        val keptIsRecent = kept != null &&
            kept.dataVersion == Store.dataVersion &&
            System.currentTimeMillis() - kept.at < 10 * 60_000
        if (attempt == 0 && keptIsRecent) return@LaunchedEffect
        if (shown == null && disk != null) shown = withContext(Dispatchers.IO) { Store.readPage(key, disk) }
        var partial = false
        val result = withContext(Dispatchers.IO) {
            runCatching {
                load { p ->
                    withContext(Dispatchers.Main) {
                        if (shown == null || partial) {
                            shown = p
                            partial = true
                        }
                    }
                }
            }
        }
        val fresh = result.getOrElse { error ->
            if (shown == null || partial) failed = plainError(error)
            return@LaunchedEffect
        }
        loaded[key] = Kept(fresh, System.currentTimeMillis(), Store.dataVersion)
        if (disk != null) Store.writePage(key, fresh, disk)
        val old = shown
        if (old != null && !partial && rows != null && list != null) {
            val a = rows(old)
            val b = rows(fresh)
            // Waits until every row on screen is the same in both copies.
            val visibleRows = snapshotFlow { list.layoutInfo.visibleItemsInfo.map { it.index } }
            visibleRows.first { on -> on.all { i -> a.getOrNull(i) == b.getOrNull(i) } }
        }
        shown = fresh
    }
    val f = failed
    val r = shown
    when {
        f != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Failed(failedTitle, f) { attempt++ }
        }
        r == null -> loading()
        else -> content(r)
    }
}

/**
 * Dims by multiplying each drawing's alpha instead of drawing into an offscreen layer first, as Modifier.alpha does, so
 * the GPU doesn't switch buffers for every dimmed control. The result is only right where the content's own parts
 * don't overlap.
 */
fun Modifier.dim(alpha: Float) = graphicsLayer {
    this.alpha = alpha
    compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
}

/** A tab's title at the top of its first screen: Home, Search, Library and Settings have one, the player doesn't. */
@Composable
internal fun ScreenTitle(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleLarge,
    )
}

/** A section's label in a list: settings groups, search sections, the queue, an artist's songs and albums. */
@Composable
internal fun Header(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** A group's label inside a dialog, in the same style as [Header]. */
@Composable
internal fun GroupLabel(text: String) {
    Text(
        text,
        Modifier.padding(top = 12.dp, bottom = 2.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * The top of a page opened on a tab's stack: a back arrow and, for a settings page, its title. Album, artist and
 * playlist pages show their title in their own header below it.
 */
@Composable
fun PageBar(title: String?) {
    // At least 48 dp, taller when a large font needs it, so the title never runs over the page below.
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { Nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        if (title != null) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A pushed page's title in its [PageBar]: a settings page's name, or what kind of page it is. */
@Composable
fun pageTitle(p: Page): String? {
    when (p.kind) {
        "album" -> return "Album"
        "artist" -> return "Artist"
        "playlist" -> return "Playlist"
        "settings" -> {}
        else -> return null
    }
    return when (p.id) {
        "source" -> backend.settingsTitle
        "appearance" -> "Appearance"
        "romanization" -> "Romanization"
        "corrections" -> "Corrections"
        "lyrics" -> "Lyrics"
        "playback" -> "Queue and radio"
        "banned" -> "Banned"
        else -> "Last.fm"
    }
}

private const val MIX_PAGES = 10

/**
 * Home: the pinned "Your mixes" tiles, then YouTube's shelves. When signed in, the first [MIX_PAGES] pages load at
 * once to collect the mixes. After that a page loads when the user nears the end.
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
    // Read from disk with the shelves, off the main thread. Until then the row keeps its place, empty.
    var mixes by mutableStateOf<List<Item>?>(null)
        private set
    var mixesRead by mutableStateOf(false)
        private set
    /** Mixes whose tiles are being changed. They are dimmed and not tappable until their new picture and title are in. */
    var updating by mutableStateOf(emptySet<String>())
        private set
    /** How many mixes the grid holds this run. Mixes found later go in the row below it, so no tile moves. */
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
    // Where paging stands. It is kept across Retry, so paging carries on from the failed page.
    private var cont: String? = null
    private var page = 0
    private val found = ArrayList<Item>()
    private var looking = false
    // This run's shelves. The disk copy is these plus the last copy's shelves that this run hasn't fetched again, so
    // an app closed after its first page doesn't leave a one-page copy for the next start.
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
                // Store.set has already dropped the other account's mixes from disk.
                mixes = null
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

    /** When a start shows Home first, this reads its copy on disk alongside the rest of start-up. */
    fun preload() {
        val read = java.util.concurrent.FutureTask { Store.mixes to Store.readPage("home", ListSerializer(Section.serializer())) }
        preloaded = read
        kotlin.concurrent.thread(name = "home") { read.run() }
    }

    // Decodes the pictures of the first screen, the mix tiles, into the memory cache now, so the first frame has them
    // instead of the next few.
    private fun decodeFirstPictures(mixes: List<Item>?) {
        if (mixes.isNullOrEmpty()) return
        val (_, shown, _) = mixGrid(screenWidthDp(), mixes.size)
        val px = tilePx(mixes.size)
        for (item in mixes.take(shown - 1)) {
            val thumb = thumbAt(item.thumb, px)
            if (thumb != null) Images.prefetch(thumb, px)
        }
    }

    private fun tilePx(n: Int) = (mixGrid(screenWidthDp(), n).third.value * screenDensity()).roundToInt()

    private fun showMixes(m: List<Item>?) {
        mixes = m
        if (gridFor == 0 && !m.isNullOrEmpty()) gridFor = m.size
    }

    /**
     * Puts fresh mixes into the tiles without changing anything under a finger. Known mixes keep their place (one
     * that YouTube dropped stays until the next start) and new ones go after them. A tile whose picture or title
     * changed waits for a second without touches, is dimmed and locked while its new picture loads, and then shows it
     * with the new title.
     */
    private fun updateMixes(m: List<Item>) {
        val old = mixes.orEmpty()
        fun key(i: Item) = i.id.removePrefix("VL")
        val byKey = m.associateBy(::key)
        val updated = old.map { o -> byKey[key(o)] ?: o }
        val added = m.filter { n -> old.none { o -> key(o) == key(n) } }
        val merged = updated + added
        Store.mixes = merged.filter { key(it) in byKey }
        val changed = merged.filter { n ->
            old.any { o -> key(o) == key(n) && (o.thumb != n.thumb || o.title.original != n.title.original) }
        }
        mixJob?.cancel()
        if (changed.isEmpty()) {
            showMixes(merged)
            return
        }
        mixJob = scope.launch {
            Touch.awaitIdle()
            updating = changed.map { it.id }.toSet()
            val tiles = if (gridFor > 0) gridFor else merged.size
            val px = tilePx(tiles)
            // ponytail: waits at most 5 s, then the tiles change anyway and a picture still missing loads as usual.
            withTimeoutOrNull(5_000) {
                val loads = changed.map { i ->
                    async {
                        val thumb = thumbAt(i.thumb, px)
                        if (thumb != null) Images.load(thumb, px) else null
                    }
                }
                loads.awaitAll()
            }
            showMixes(merged)
            // A tap already on its way when the tile changed must not count as a tap on the new mix.
            delay(300)
            updating = emptySet()
        }
    }

    private suspend fun load() = coroutineScope {
        if (sections.isEmpty()) {
            launch {
                val (savedMixes, disk) = withContext(Dispatchers.IO) {
                    val pre = preloaded?.get()
                    if (pre != null) {
                        preloaded = null
                        decodeFirstPictures(pre.first)
                    }
                    pre ?: (Store.mixes to Store.readPage("home", ListSerializer(Section.serializer())))
                }
                if (!mixesRead) {
                    if (mixes == null) showMixes(savedMixes)
                    mixesRead = true
                }
                if (disk != null) saved = disk
                if (sections.isEmpty() && disk != null) sections = disk
            }
        }
        // Waiting contents go in as their shelf leaves the screen. This runs only when the list scrolls.
        launch {
            snapshotFlow { list.layoutInfo.visibleItemsInfo.map { it.index }.toSet() }.collect { on ->
                // LazyColumn item i + 1 is shelf i.
                val off = waiting.keys.filter { shelf -> shelf + 1 !in on }
                if (off.isNotEmpty()) {
                    val updated = sections.toMutableList()
                    for (shelf in off) updated[shelf] = waiting.remove(shelf)!!
                    sections = updated
                }
            }
        }
        while (more) {
            if (!looking && page > 0) {
                // Waits until the user nears the end of the list.
                val nearEnd = snapshotFlow {
                    val info = list.layoutInfo
                    val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                    lastVisible >= info.totalItemsCount - 3
                }
                nearEnd.first { near -> near }
            }
            val from = cont
            val result = withContext(Dispatchers.IO) {
                runCatching { backend.home(from) }
            }
            val (next, shelves) = result.getOrElse { error ->
                failed = plainError(error)
                fetchedAt = 0
                return@coroutineScope
            }
            // A page loaded for the account before a switch is neither shown nor saved.
            if (account != backend.account) return@coroutineScope
            page++
            cont = next
            more = next != null
            val before = found.size
            val mixes = backend.mixesIn(shelves.flatMap { it.items })
            found += mixes.filter { m -> found.none { f -> f.id.removePrefix("VL") == m.id.removePrefix("VL") } }
            // The mixes live in the pinned row only.
            val add = shelves
                .map { shelf ->
                    val items = shelf.items.filter { i -> mixes.none { m -> m.id == i.id } }
                    Section(shelf.title, items)
                }
                .filter { shelf -> shelf.items.isNotEmpty() }
            fresh += add
            val notFetchedAgain = saved.filter { s -> fresh.none { f -> f.title.original == s.title.original } }
            Store.writePage("home", fresh + notFetchedAgain, ListSerializer(Section.serializer()))
            merge(add)
            // The mixes are spread over several pages (the numbered ones often come after the Supermix), so the
            // row waits for the first MIX_PAGES pages. Mixes on later pages still join it.
            val lookingDone = looking && (page >= MIX_PAGES || !more)
            val foundMoreLater = !looking && found.size > before
            if (lookingDone || foundMoreLater) {
                looking = false
                updateMixes(backend.mixesIn(found))
            }
        }
    }

    private fun visible() = list.layoutInfo.visibleItemsInfo.map { it.index }.toSet()

    private fun sameItems(a: List<Item>, b: List<Item>) = a.map { it.id } == b.map { it.id }

    /** Merges fresh shelves into the shown ones by title, as [Home] describes. LazyColumn item i + 1 is shelf i. */
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
                if (sameItems(shown[i].items, s.items) || i + 1 !in on) {
                    shown[i] = s
                    waiting.remove(i)
                } else {
                    waiting[i] = s
                }
            }
        }
        sections = shown
    }
}

@Composable
fun HomeScreen() {
    LaunchedEffect(Store.dataVersion) { Home.start() }
    // The first frame holds only the mixes, which are what Home is opened for. The shelves (YouTube's, which change
    // from start to start) and their pictures come in the frame after.
    var shelves by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { Startup.later { shelves = true } }
    // The mixes take at least the first screen, so the shelves begin below it even when there are few mixes.
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val height = maxHeight
        // This sits out here, not in a row, because a row is composed only once it scrolls into view. The first cards
        // of every shelf come before the later cards of any.
        if (shelves) {
            val byPosition = Home.sections.flatMap { it.items.withIndex() }.sortedBy { it.index }
            PrefetchCovers(byPosition.map { it.value.thumb }, 128.dp, near = 40)
        }
        LazyColumn(Modifier.fillMaxSize(), Home.list, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
            // A library's shelves don't change from start to start, so on a server they follow the tiles at once.
            item(key = "title") { ScreenTitle("Home") }
            item(key = "mixes") {
                // Signed out, the hint is short, and YouTube's shelves follow it at once.
                Column(if (backend.mixes && backend.libraryReady) Modifier.heightIn(min = height) else Modifier) {
                    Header(if (backend.mixes) "Your mixes" else "Your library")
                    backend.HomeTiles(Home.mixes, Home.mixesRead)
                }
            }
            if (!shelves) return@LazyColumn
            items(Home.sections) { sec ->
                // Shelf titles are section labels like every other list's, in the same style.
                val label = MaterialTheme.typography.titleSmall.copy(color = MaterialTheme.colorScheme.primary)
                Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
                    Name(sec.title, Store.listMode, label)
                }
                // The cards' own 6 dp and this 10 dp put the first picture at 16 dp, under the shelf's title.
                LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)) {
                    items(sec.items) { item -> Card(item) { openItem(item) } }
                }
            }
            item(key = "end") {
                val f = Home.failed
                when {
                    f != null -> Failed("Couldn't load Home.", f) { Home.start() }
                    Home.more -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

/**
 * The mixes' grid: three columns, every mix and then the Liked music tile, as (columns, tiles shown, tile side).
 * Titles get three lines whatever they say, so a tile's height never depends on its text.
 */
internal fun mixGrid(widthDp: Int, n: Int): Triple<Int, Int, Dp> {
    // 16 dp of padding on each side, and 12 dp between each pair of the three columns.
    val side = (widthDp - 16 * 2 - 12 * 2) / 3
    return Triple(3, n + 1, side.dp)
}

private val FILTERS = listOf("Songs", "Albums", "Artists", "Playlists")

@Composable
fun SearchScreen() {
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    // Set by "Search as typed" to YouTube's params for the query without its spelling correction.
    var asTyped by rememberSaveable { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<Song?>(null) }
    val focus = remember { FocusRequester() }
    // The keyboard comes up for a new search, not when coming back to results. It goes away when another tab is
    // shown, because this one stays composed, focus included.
    val shown = LocalTabShown.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(shown) {
        if (!shown) {
            focusManager.clearFocus()
        } else if (query.isEmpty()) {
            focus.requestFocus()
        }
    }
    fun search(q: String) {
        query = q
        submitted = q.trim()
        asTyped = null
        // Hides the keyboard, so the results take its place.
        focusManager.clearFocus()
    }
    val source = backend
    // A source that searches its own index (Subsonic) answers as you type; others search on Enter.
    if (source.searchIsLocal) {
        LaunchedEffect(query) {
            delay(150)
            if (query.trim() != submitted) {
                submitted = query.trim()
                asTyped = null
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Search")
        Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)) {
            OutlinedTextField(
                query, { query = it },
                Modifier.fillMaxWidth().focusRequester(focus),
                placeholder = "Songs, albums, artists",
                singleLine = true,
                onSearch = { search(query) },
            )
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = {
                        query = ""
                        submitted = ""
                        focus.requestFocus()
                    },
                    Modifier.align(Alignment.CenterEnd),
                ) { Icon(Icons.Default.Clear, "Clear") }
            }
        }
        // The filters keep a fixed order, and videos are left out on purpose. With a large system font they wrap onto a
        // second line, in the same order, instead of squeezing the last one into a column of letters.
        androidx.compose.foundation.layout.FlowRow(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FILTERS.forEachIndexed { i, label ->
                val onClick = {
                    filter = i
                    asTyped = null
                }
                FilterChip(filter == i, onClick, label = { Text(label) })
            }
        }
        // While the text differs from the last search, YouTube's suggestions take the results' place. The ones shown
        // stay while the next keystroke's suggestions load, so typing doesn't flash the old results between them.
        var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
        LaunchedEffect(query) {
            if (query.isBlank() || query.trim() == submitted) {
                suggestions = emptyList()
                return@LaunchedEffect
            }
            delay(250)
            suggestions = withContext(Dispatchers.IO) { source.suggestions(query) }
        }
        if (query.isBlank()) {
            Centered("Search ${source.name} for songs, albums, artists and playlists.")
        } else if (query.trim() != submitted && !source.searchIsLocal) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(suggestions) { q ->
                    val rowModifier = Modifier
                        .fillMaxWidth()
                        .clickable { search(q) }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                    Text(q, rowModifier)
                }
            }
        } else if (submitted.isNotEmpty()) {
            // Songs played before, by their Latin forms, so "kimi no" finds 君の声 even though YouTube wouldn't. These rows
            // show as soon as the local lookup is done and keep their place when YouTube's results arrive below them.
            // The lookup runs off the main thread, once per search, before this search's own results join the index.
            val local by produceState(emptyList<Song>(), submitted, filter) {
                value = if (filter == 0 && !source.searchIsLocal) {
                    withContext(Dispatchers.IO) { Store.searchLocal(submitted) }
                } else {
                    emptyList()
                }
            }
            // The top row is the likely tap, so the first "Seen before" song is prepared at once, and YouTube's top
            // song when it comes.
            LaunchedEffect(local) { Playback.prepare(local.take(1)) }
            fun LazyListScope.localRows() {
                if (local.isEmpty()) return
                item { Header("Seen before") }
                items(local) { s ->
                    SongRow(s, onMenu = { menuFor = s }) {
                        Playback.play(listOf(s))
                        Nav.tab = TAB_PLAYER
                    }
                }
                item { Header(source.name) }
            }
            fun LazyListScope.searchingRow() {
                item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            val searchKey = listOf(submitted, filter, asTyped).toString()
            Loader(
                searchKey,
                { partial -> source.search(submitted, filter, asTyped, partial) },
                failedTitle = "Couldn't search.",
                loading = {
                    LazyColumn(Modifier.fillMaxSize()) {
                        localRows()
                        searchingRow()
                    }
                },
            ) { found ->
                val songThumbs = local.map { it.thumb } + found.items.filter { it.song != null }.map { it.thumb }
                PrefetchCovers(songThumbs, 48.dp)
                PrefetchCovers(found.items.filter { it.song == null }.map { it.thumb }, 48.dp)
                LaunchedEffect(found) {
                    val topFound = listOfNotNull(found.items.firstOrNull()?.playable)
                    val likely = (local.take(1) + topFound).distinctBy { it.id }
                    Playback.prepare(likely)
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    localRows()
                    // YouTube's spelling correction, one line above its results. The corrected query gives way to "…",
                    // so the action stays readable on one line.
                    val corrected = found.corrected
                    if (corrected != null) {
                        item {
                            val rowModifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (found.autoCorrected) {
                                        asTyped = found.originalParams
                                    } else {
                                        search(corrected)
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                            Row(rowModifier) {
                                val style = MaterialTheme.typography.bodyMedium
                                val color = MaterialTheme.colorScheme.primary
                                val lead: String
                                val action: String
                                if (found.autoCorrected) {
                                    lead = "Showing results for “$corrected”"
                                    action = " · Search as typed"
                                } else {
                                    lead = "Did you mean “$corrected”"
                                    action = "?"
                                }
                                Text(
                                    lead,
                                    Modifier.weight(1f, fill = false),
                                    style = style,
                                    color = if (found.autoCorrected) Color.Unspecified else color,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(action, style = style, color = color, maxLines = 1)
                            }
                        }
                    }
                    if (found.items.isEmpty() && !found.searching) {
                        item {
                            Box(Modifier.fillParentMaxSize()) {
                                Centered("No results for “$submitted”. Check the spelling, or try another filter above.")
                            }
                        }
                    }
                    items(found.items) { item ->
                        val s = item.song
                        if (s != null) {
                            SongRow(s, onMenu = { menuFor = s }) { openItem(item) }
                        } else {
                            ItemRow(item) { openItem(item) }
                        }
                    }
                    // The source's first results are up and it still looks for more.
                    if (found.searching) searchingRow()
                }
            }
        }
    }
    val menuSong = menuFor
    if (menuSong != null) SongMenu(menuSong, null) { menuFor = null }
}

@Composable
fun LibraryScreen() {
    val source = backend
    if (!source.libraryReady) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(source.libraryHint, textAlign = TextAlign.Center, style = LocalTextStyle.current.copy(lineBreak = androidx.compose.ui.text.style.LineBreak.Heading))
            Spacer(Modifier.height(12.dp))
            Button(onClick = { Nav.open(source.libraryHintPage) }) {
                Text(if (source.libraryHintPage.kind == "settings") "Settings" else "Sign in")
            }
        }
        return
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = source.libraryTabs
    var menuFor by remember { mutableStateOf<Song?>(null) }
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Library")
        // A fixed row, like the nav bar: its labels follow the font only up to 1.3×, so four still fit.
        FixedRowsFont {
            TabRow(tab) {
                tabs.forEachIndexed { i, label ->
                    Tab(tab == i, { tab = i }, text = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                }
            }
        }
        val list = key(tab) { rememberLazyListState() }
        Loader(
            "library:${source.libraryKey(tab)}",
            { partial -> source.library(tab, partial) },
            list,
            { items -> items.map { it.id } },
            ListSerializer(Item.serializer()),
        ) { items ->
            PrefetchCovers(items.map { it.thumb }, 48.dp, list)
            if (items.isEmpty()) Centered(source.libraryEmpty(tab))
            Box {
                LazyColumn(Modifier.fillMaxSize(), list) {
                    // A song (in Starred) is a song row, with its length and ⋮ menu, as songs are everywhere else.
                    items(items) { item ->
                        val s = item.song
                        if (s != null) {
                            SongRow(s, onMenu = { menuFor = s }) { openItem(item) }
                        } else {
                            ItemRow(item) { openItem(item) }
                        }
                    }
                }
                FastScroll(list)
            }
        }
    }
    val menuSong = menuFor
    if (menuSong != null) SongMenu(menuSong, null) { menuFor = null }
}

@Composable
fun PageScreen(p: Page) {
    if (p.kind == "source") {
        backend.OwnPage(p.id)
        return
    }
    if (p.kind == "settings") {
        SettingsPage(p.id)
        return
    }
    var menuFor by remember { mutableStateOf<Song?>(null) }
    val list = rememberLazyListState()
    Loader("${p.kind}:${p.id}", { partial -> backend.detail(p, partial) }, list, ::detailRows, Detail.serializer()) { d ->
        // Play and the first row are the likely taps, but not while another page covers this one.
        val shown = LocalTabShown.current
        LaunchedEffect(d.songs.firstOrNull()?.id, shown) {
            if (shown) Playback.prepare(d.songs.take(1))
        }
        PrefetchCovers(d.songs.map { it.thumb }, 48.dp, list)
        val album = p.kind == "album"
        val artistPage = p.kind == "artist"
        Playback.tick
        val playingId = Playback.shown()?.id
        PrefetchCovers(d.items.map { it.thumb }, 48.dp)
        Box {
            LazyColumn(Modifier.fillMaxSize(), list) {
                item {
                    Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        val shape = if (p.kind == "artist") CircleShape else RoundedCornerShape(6.dp)
                        Thumb(shownThumb(d.thumb, 96.dp), 96.dp, Modifier.size(96.dp).clip(shape))
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Name(d.title, Store.listMode, MaterialTheme.typography.titleLarge)
                            val subtitle = listOf(d.subtitle, if (!artistPage) totalLength(d.songs) else "")
                                .filter { it.isNotBlank() }
                                .joinToString(" · ")
                            if (subtitle.isNotEmpty()) {
                                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            PageButtons(p, d)
                        }
                    }
                }
                // An artist's page heads its songs as well as its releases.
                if (d.songs.isNotEmpty() && d.items.isNotEmpty()) item { Header("Songs") }
                // An album's own artist is said once, in the header, and an artist page's is its title; a track by
                // someone else names them.
                val pageArtist = when {
                    album -> d.songs.groupingBy { it.artistLatin }.eachCount().maxByOrNull { it.value }?.key
                    artistPage -> d.title.latin
                    else -> null
                }
                itemsIndexed(d.songs) { i, s ->
                    val disc = d.discs.getOrNull(i)
                    if (disc != null && disc != d.discs.getOrNull(i - 1) && d.discs.toSet().size > 1) Header("Disc $disc")
                    SongRow(
                        s,
                        highlight = s.id == playingId,
                        number = if (album) d.tracks.getOrNull(i)?.takeIf { it > 0 } ?: (i + 1) else null,
                        artist = s.artistLatin != pageArtist,
                        onMenu = { menuFor = s },
                    ) {
                        Playback.play(d.songs, i)
                        Nav.tab = TAB_PLAYER
                    }
                }
                if (d.items.isNotEmpty()) {
                    val allAlbums = d.items.all { it.kind == "album" }
                    item { Header(if (allAlbums) "Albums" else "Albums and more") }
                    items(d.items) { item -> ItemRow(item) { openItem(item) } }
                }
            }
            FastScroll(list)
        }
    }
    val menuSong = menuFor
    if (menuSong != null) SongMenu(menuSong, null) { menuFor = null }
}

/**
 * Play, and Add to library or Remove from library on a page its source can save. Those buttons stay hidden until the
 * source has said whether the page is saved.
 */
@Composable
private fun PageButtons(p: Page, d: Detail) {
    val scope = rememberCoroutineScope()
    val source = backend
    var state by remember(p) { mutableStateOf<InLibrary?>(null) }
    LaunchedEffect(p) { state = withContext(Dispatchers.IO) { source.saved(p) } }
    // The first button's text starts where the title above it does.
    val padding = androidx.compose.foundation.layout.PaddingValues(start = 0.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (d.songs.isNotEmpty()) {
            Button(onClick = {
                playList(p, d.songs)
                Nav.tab = TAB_PLAYER
            }) { Text("Play") }
            Spacer(Modifier.width(8.dp))
        }
        val now = state
        if (now != null) {
            TextButton(onClick = {
                state = InLibrary(!now.saved)
                scope.launch {
                    val accepted = withContext(Dispatchers.IO) { source.save(p, !now.saved) }
                    if (!accepted) {
                        toast("Couldn't reach the server")
                        state = now
                    } else {
                        // Read back after a change, because the server may have started getting it.
                        val readBack = withContext(Dispatchers.IO) { source.saved(p) }
                        state = readBack ?: InLibrary(!now.saved)
                    }
                }
            }, contentPadding = padding) {
                val label = when {
                    source.stars -> if (now.saved) "Unstar" else "Star"
                    now.saved -> "Remove from library"
                    else -> "Add to library"
                }
                Text(label)
            }
        }
    }
    val note = state?.note
    if (note != null) {
        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** An album's length, such as "42 min" or "1 h 5 min", or "" when the songs' lengths aren't known. */
private fun totalLength(songs: List<Song>): String {
    if (songs.any { it.durationSec <= 0 }) return ""
    val minutes = (songs.sumOf { it.durationSec } + 30) / 60
    return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
}

/** Generated playlists (mixes, editorial RD… lists) load more pages as they play. Anything else plays what the page holds. */
private fun playList(p: Page, songs: List<Song>) =
    if (p.kind == "playlist" && p.id.removePrefix("VL").startsWith("RD")) Playback.playMix(p.id, songs) else Playback.play(songs)

/** What each LazyColumn item of a page shows, in order, for [Loader]. */
private fun detailRows(d: Detail): List<Any?> {
    val header = listOf(d.title.original, d.subtitle, d.thumb, d.songs.isEmpty())
    val more = if (d.items.isEmpty()) emptyList() else listOf("more") + d.items.map { it.id }
    return listOf<Any?>(header) + d.songs.map { it.id } + more
}

/** The Settings tab: one row per category, each opening its own page ([SettingsPage]) and summing up what it holds. */
@Composable
fun SettingsScreen() {
    Store.version
    // These are read here, not in the rows. They aren't snapshot state, and a lazy row only recomposes when the state
    // it reads or the values it holds change.
    val corrections = Store.romanizer.overrides.size
    val bannedSongs = Store.banned.count { it.kind != "artist" }
    val bannedArtists = Store.banned.size - bannedSongs
    val lastFm = Store.str("lastfm.session", "").isNotBlank()
    val place = PLACES.first { it.first == Store.place }.second.lowercase()
    val dictionary = if (Dictionary.installed || backend.romanizes) "" else " · no Japanese dictionary"
    LazyColumn(Modifier.fillMaxSize()) {
        item { ScreenTitle("Settings") }
        val update = Update.available
        if (update != null) {
            item {
                val status = Update.status ?: "Restarts ${BuildConfig.APP_NAME}, so the music stops"
                Row1("Update to ${BuildConfig.APP_NAME} $update", status) { Update.install() }
            }
        }
        item { Category("source", backend.settingsTitle, backend.settingsSummary()) }
        item {
            val correctionCount = count(corrections, "correction") ?: "0 corrections"
            Category("romanization", "Romanization", "Placement: $place · schemes · $correctionCount$dictionary")
        }
        item { Category("lyrics", "Lyrics", "Providers · original, romanization and translation lines") }
        item {
            val last = if (backend.knowsLoudness) "loudness · downloading ahead" else "downloading ahead"
            Category("playback", "Queue and radio", "Radio drift · filters · $last")
        }
        item { Category("appearance", "Appearance", "Left-handed controls · true black · accent colour") }
        item {
            val summary = if (bannedSongs + bannedArtists == 0) {
                "Nothing banned"
            } else {
                listOfNotNull(count(bannedSongs, "song"), count(bannedArtists, "artist")).joinToString(" · ")
            }
            Category("banned", "Banned", summary)
        }
        item { Category("lastfm", "Last.fm", if (lastFm) "Scrobbling" else "Not connected") }
        item {
            Text(
                "${BuildConfig.APP_NAME} ${BuildConfig.VERSION_NAME}",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Category(id: String, title: String, subtitle: String) = Row1(title, subtitle, opens = true) { Nav.open(Page("settings", id)) }

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
    item { Switch1("Left-handed player controls", "ui.leftHanded", false) }
    item { Switch1("True black in dark mode (saves power on OLED screens)", "ui.trueBlack", false) }
    item { Switch1("Accent colour from the song's artwork", "ui.artColors", false) }
}

@Composable
private fun RomanizationSettings() = LazyColumn(Modifier.fillMaxSize()) {
    item { Options("Placement", Store.PLACE_KEY, Store.place, PLACES) }
    // Custom placement sets each surface on its own, with the modes a single place can't express (Latin only,
    // original only).
    if (Store.place == "custom") {
        item { Options("Lists", "display.list", "both", MODES) }
        item { Options("Player", "display.player", "both", MODES) }
        item {
            val layouts = listOf(
                "lines" to "A line under the original",
                "ruby" to "Above each word, like furigana",
            )
            Options("Lyrics", "lyrics.layout", "lines", layouts)
        }
    }
    item {
        val n = Store.romanizer.overrides.size
        val summary = if (n == 0) "None yet · import a file" else "$n · export, import or remove them"
        Row1("Corrections", summary, opens = true) { Nav.open(Page("settings", "corrections")) }
    }
    item { Header("Schemes") }
    if (backend.romanizes) {
        item {
            Text(
                "Your server romanizes names and its lyrics; these apply to lyrics from elsewhere.",
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    item { Switch1("Japanese: long vowels with macrons (ō)", "rom.macrons", true) }
    item { Switch1("Chinese: pinyin with tone marks", "rom.tones", true) }
    item {
        val cyrillic = listOf(
            "english" to "Russian: English-style",
            "polish" to "Russian: Polish-style",
            "iso9" to "ISO 9, every language",
        )
        Options("Cyrillic (other languages use their national Latin form)", "rom.cyrillic", "english", cyrillic)
    }
    item {
        val greek = listOf(
            "classical" to "Classical, no accents (β = b)",
            "elot" to "Modern, ELOT 743 (β = v)",
        )
        Options("Greek", "rom.greek", "classical", greek)
    }
    item { Options("Han text without kana is", "rom.han", "ja", listOf("ja" to "Japanese", "zh" to "Chinese")) }
    // Text only in scripts the user reads shows as written, in lists, the player, the notification and lyrics.
    // The same question as the first start, checked meaning romanized; the others show as written.
    item { Header("Romanize these scripts") }
    items(SCRIPTS) { (k, label) ->
        val read = Store.readScripts
        val rowModifier = Modifier
            .fillMaxWidth()
            .clickable { Store.setReads(k, k !in read) }
            .padding(start = 5.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)
        Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(k !in read, null, Modifier.padding(end = 4.dp))
            Text(label)
        }
    }
    if (!backend.romanizes) {
        item {
            val dictionary = when {
                Dictionary.installed -> "Installed"
                Dictionary.status != null -> Dictionary.status!!
                else -> "Not installed: kanji stay as written. Tap to install (${Dictionary.DOWNLOAD})"
            }
            Row1("Japanese dictionary", dictionary, enabled = !Dictionary.installed) { Store.setReads("ja", false) }
        }
    }
}

@Composable
private fun CorrectionSettings() {
    val export = rememberSaveFile("${BuildConfig.SLUG}-corrections.json") { Store.exportOverrides().toByteArray() }
    val import = rememberOpenFile { text ->
        runCatching { Store.setOverrides(Store.json.decodeFromString<List<Override>>(text)) }
            .onFailure { error -> toast("Not an overrides file: ${error.message}", long = true) }
    }
    val corrections = Store.romanizer.overrides
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            val what = count(corrections.size, "correction") ?: "corrections"
            Row1("Export $what", "To one file", enabled = corrections.isNotEmpty()) { export() }
        }
        item { Row1("Import corrections", "Replaces the current ones") { import() } }
        if (corrections.isEmpty()) {
            item {
                Text(
                    "Long-press a title, artist or lyric word to correct its romanization.",
                    Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        items(corrections) { o ->
            val scope = when (o.scope) {
                "song" -> "This song"
                "artist" -> "This artist"
                "word" -> if (o.id == "") "Word, everywhere" else "Word, in one song"
                else -> "Everywhere"
            }
            val title = if (o.latin == o.original) "${o.original}: not romanized" else "${o.original} → ${o.latin}"
            Row1(title, "$scope · Tap to remove") {
                Store.saveOverride(Override(o.scope, o.id, o.original, ""))
            }
        }
    }
}

@Composable
private fun LyricsSettings() = LazyColumn(Modifier.fillMaxSize()) {
    // At least one of the two stays on, because with neither the lyrics would be blank.
    val original = Store.bool("lyrics.layer.original", true)
    val latin = Store.bool("lyrics.layer.latin", true)
    item { Switch1("Show original", "lyrics.layer.original", true, enabled = latin || !original) }
    item { Switch1("Show romanization", "lyrics.layer.latin", true, enabled = original || !latin) }
    item {
        val place = PLACES.first { it.first == Store.place }.second.lowercase()
        Row1("Romanization settings", "Placement: $place · schemes · corrections", opens = true) {
            Nav.open(Page("settings", "romanization"))
        }
    }
    // A global setting, so it is never dimmed for the current song. A song with no translation in the chosen language
    // just shows none.
    item { Switch1("Show translation", "lyrics.layer.translation", true) }
    item {
        val locale = java.util.Locale.getDefault()
        val device = locale.getDisplayLanguage(locale)
        val thenEnglish = if (java.util.Locale.getDefault().language == "en") "" else ", then English"
        val languages = listOf(
            "device" to "Device language ($device)$thenEnglish",
            "en" to "English",
            "ja" to "Japanese",
            "ko" to "Korean",
            "zh" to "Chinese",
            "ru" to "Russian",
        )
        Options("Translation language", "lyrics.translationLang", "device", languages)
    }
    item {
        Text(
            "Long-press a word in the lyrics to see its reading and correct it, for that song or everywhere.",
            Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    item { Header("Providers") }
    val order = Store.lyricsOrder()
    itemsIndexed(order) { i, p ->
        Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (order.size > 1) {
                Text("${i + 1}. ${p.name}", Modifier.weight(1f))
                IconButton(onClick = { move(order, i, -1) }, enabled = i > 0) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
                IconButton(onClick = { move(order, i, 1) }, enabled = i < order.size - 1) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
            } else {
                Text(p.name, Modifier.weight(1f))
            }
            Switch(Store.lyricsEnabled(p), { on -> Store.set("lyrics.on.${p.id}", on) }, Modifier.padding(end = 16.dp))
        }
    }
    item { Text(backend.lyricsRule, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
    item {
        // Read because the song can start or end while this page is open.
        Playback.tick
        val playing = Playback.current()?.song != null
        Row1("Search lyrics again for this song", "After changing the providers", enabled = playing) {
            Playback.refetchLyrics()
        }
    }
}

@Composable
private fun PlaybackSettings() = LazyColumn(Modifier.fillMaxSize()) {
    item {
        val leashes = listOf(
            "close" to "Close: same and related artists",
            "medium" to "Medium: at most half other artists",
            "free" to backend.freeRadio,
        )
        Options("Radio may drift", "radio.leash", "free", leashes)
    }
    if (backend.hasVideos) {
        item { Switch1("Prefer audio tracks over music videos", "radio.preferAudio", true) }
    }
    item { Switch1("Hide live, sped up, nightcore, slowed", "radio.hideVersions", true) }
    item { Switch1("Skip banned songs and artists", "radio.skipBanned", true) }
    item { Switch1("Stop when the queue ends", "queue.stopAtEnd", false) }
    item { Header("Playback") }
    if (backend.knowsLoudness) {
        item { Switch1("Even out loudness", "play.normalize", true) }
    }
    item {
        Switch1(
            "Download songs ahead",
            "play.ahead",
            false,
            subtitle = "This song and the next 3, so playback goes on without a network. About 4 MB per song you skip",
        )
    }
    item {
        Switch1("Resume when headphones reconnect", "play.resumeOnHeadphones", false, subtitle = "Within an hour of disconnecting")
    }
    item {
        Switch1(
            "Preload on mobile data",
            "prepare.always",
            false,
            subtitle = "And in Battery Saver: the first second of songs you'll likely tap, so they start at once. At most 3 MB an hour",
        )
    }
}

@Composable
private fun BannedSettings() = LazyColumn(Modifier.fillMaxSize()) {
    val banned = Store.banned
    if (banned.isEmpty()) {
        item { Box(Modifier.fillParentMaxSize()) { Centered("Nothing banned. Ban a song or artist from a song's ⋮ menu.") } }
    }
    items(banned) { b ->
        val kind = if (b.kind == "artist") "Artist" else "Song"
        Row1("$kind: ${b.name}", "Tap to unban") { Store.unban(b.kind, b.id) }
    }
}

@Composable
private fun LastFmSettings() {
    // The setting's key and the dialog's title.
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            val apiKey = Store.str("lastfm.key", "").ifBlank { "Not set" }
            Row1("API key", apiKey) { editing = "lastfm.key" to "Last.fm API key" }
        }
        item {
            Row1("Create an API key", "Opens last.fm/api/account/create; it gives the key and the shared secret") {
                openUrl("https://www.last.fm/api/account/create")
            }
        }
        item {
            val secret = if (Store.str("lastfm.secret", "").isBlank()) "Not set" else "Set"
            Row1("Shared secret", secret) { editing = "lastfm.secret" to "Last.fm shared secret" }
        }
        item {
            val connected = Store.str("lastfm.session", "").isNotBlank()
            // Reading the queue is a file read, so it runs off the main thread and a switch on this page doesn't
            // wait for it.
            val waiting by produceState(0, connected, Store.version) {
                value = withContext(Dispatchers.IO) {
                    if (connected) Playback.scrobbleQueue.load().size else 0
                }
            }
            val ready = Store.str("lastfm.key", "").isNotBlank() && Store.str("lastfm.secret", "").isNotBlank()
            val title = if (connected) "Connected: scrobbling" else "Connect"
            val subtitle = if (connected) "Tap to disconnect · $waiting waiting" else "Opens the browser to allow access"
            Row1(title, subtitle, ready || connected) {
                if (connected) {
                    Store.set("lastfm.session", null)
                } else {
                    connectLastFm()
                }
            }
        }
    }
    val edited = editing
    if (edited != null) {
        val (key, title) = edited
        TextInputDialog(title, Store.str(key, ""), { editing = null }) { value -> Store.set(key, value.trim()) }
    }
}

/** The app's question before the dictionary's download, when it is missing or out of date. */
@Composable
fun DictionaryPrompt() {
    val offer = Dictionary.offer ?: return
    AlertDialog(
        onDismissRequest = { Dictionary.answer(false) },
        title = { Text(if (offer == "update") "Update the Japanese dictionary?" else "Install the Japanese dictionary?") },
        text = {
            val why = if (offer == "update") {
                "This version of ${BuildConfig.APP_NAME} needs a newer Japanese dictionary. "
            } else {
                "${BuildConfig.APP_NAME} needs it to romanize kanji. "
            }
            Text(
                why + Dictionary.HOW + "\n\n" +
                    "Until then, Japanese gets kana-only romanization and kanji stay as written. You can install it later in Settings."
            )
        },
        confirmButton = { TextButton(onClick = { Dictionary.answer(true) }) { Text(if (offer == "update") "Update" else "Install") } },
        dismissButton = { TextButton(onClick = { Dictionary.answer(false) }) { Text("Not now") } },
    )
}

/**
 * The first start asks which scripts to romanize. Japanese needs the dictionary, fetched once if picked, unless the
 * source romanizes ([Source.romanizes]). The others need nothing. Unpicked scripts go into "scripts you read" and
 * show as written. It is shown until Continue.
 */
@Composable
fun SetupScreen() {
    Store.version
    if (!backend.setUp) {
        backend.Setup()
        return
    }
    val read = Store.readScripts
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text("Which scripts should ${BuildConfig.APP_NAME} romanize?", style = MaterialTheme.typography.headlineSmall)
        Text(
            "The others show as written. You can change this in Settings.",
            Modifier.padding(top = 8.dp, bottom = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        SCRIPTS.forEach { (k, label) ->
            // Shifted by the checkbox's own padding, so its box lines up with the title above.
            val rowModifier = Modifier.offset(x = (-11).dp).fillMaxWidth().clickable {
                val updated = if (k in read) read - k else read + k
                Store.set(Store.READ_KEY, updated.joinToString(","))
            }
            Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(k !in read, null, Modifier.padding(end = 8.dp))
                Column {
                    Text(label)
                    if (k == "ja" && !backend.romanizes) {
                        val status = if (Dictionary.installed) {
                            "Dictionary installed"
                        } else {
                            "Needs the Japanese dictionary: ${Dictionary.DOWNLOAD}"
                        }
                        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
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

private val SCRIPTS = listOf(
    "ja" to "Japanese",
    "zh" to "Chinese",
    "ko" to "Korean",
    "cyrillic" to "Cyrillic",
    "greek" to "Greek",
    "other" to "Other scripts",
)

private val PLACES = listOf(
    "below" to "On its own line",
    "above" to "Above each word, like furigana",
    "custom" to "Differently in lists, the player and lyrics",
)

private val MODES = listOf(
    "both" to "Latin, original underneath",
    "ruby" to "Reading above each word, like furigana",
    "romanized" to "Latin only",
    "original" to "Original only",
)

private fun move(order: List<roma.core.LyricsProvider>, i: Int, by: Int) {
    val list = order.toMutableList()
    val p = list.removeAt(i)
    list.add(i + by, p)
    Store.set("lyrics.order", list.joinToString(",") { it.id })
}

/**
 * A settings row: a title and what it holds. One that [opens] a page ends in a chevron. A " · " list in the subtitle
 * wraps before a dot, never after it.
 */
@Composable
internal fun Row1(title: String, subtitle: String, enabled: Boolean = true, opens: Boolean = false, onClick: () -> Unit) {
    val rowModifier = Modifier
        .fillMaxWidth()
        .clickable(enabled = enabled, onClick = onClick)
        .dim(if (enabled) 1f else 0.4f)
        .padding(horizontal = 16.dp, vertical = 10.dp)
    Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(
                subtitle.replace(" · ", "\u00A0· "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (opens) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                null,
                Modifier.padding(start = 8.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun Switch1(title: String, key: String, def: Boolean, enabled: Boolean = true, subtitle: String? = null) {
    val on = Store.bool(key, def)
    val rowModifier = Modifier
        .fillMaxWidth()
        .clickable(enabled) { Store.set(key, !on) }
        .dim(if (enabled) 1f else 0.4f)
        .padding(horizontal = 16.dp, vertical = 8.dp)
    Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title)
            if (subtitle != null) {
                Text(
                    subtitle,
                    Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(on, { checked -> Store.set(key, checked) }, enabled = enabled)
    }
}

/**
 * A group of choices under its label. The radio buttons' circles sit on the same 16 dp edge as the label and as
 * checkboxes and other rows' text: the button's own 12 dp of padding takes most of it.
 */
@Composable
internal fun Options(title: String, key: String, def: String, options: List<Pair<String, String>>) {
    val value = Store.str(key, def)
    Header(title)
    Column(Modifier.padding(start = 4.dp, end = 16.dp)) {
        options.forEach { (v, label) -> Choice(label, value == v) { Store.set(key, v) } }
    }
}

/** "1 song" or "3 songs", or null for none. */
private fun count(n: Int, what: String) = if (n == 0) null else "$n $what" + if (n == 1) "" else "s"

/** A failure in plain words, with what to do about it. */
fun plainError(t: Throwable): String {
    val ioCause = generateSequence(t) { e -> e.cause }.firstOrNull { e -> e is java.io.IOException }
    return when (ioCause ?: t) {
        is java.net.UnknownHostException,
        is java.net.ConnectException,
        is java.net.NoRouteToHostException -> "No connection. Check Wi-Fi or mobile data, then Retry."
        is java.net.SocketTimeoutException -> "${backend.name} took too long to answer. Retry in a moment."
        else -> t.message ?: t.toString()
    }
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
        if (list.isScrollInProgress || dragging) {
            shown = true
        } else {
            kotlinx.coroutines.delay(1500)
            shown = false
        }
    }
    val alpha by androidx.compose.animation.core.animateFloatAsState(if (shown) 1f else 0f, label = "fastScroll")
    if (alpha == 0f) return
    val scope = rememberCoroutineScope()
    var height by remember { mutableIntStateOf(1) }
    val thumb = with(LocalDensity.current) { 56.dp.toPx() }
    val fraction = list.firstVisibleItemIndex.toFloat() / (total - visible).coerceAtLeast(1)
    Box(
        Modifier
            .align(Alignment.TopEnd)
            .fillMaxHeight()
            .width(32.dp)
            .onSizeChanged { newSize -> height = newSize.height }
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
            Modifier
                .align(Alignment.TopEnd)
                .padding(end = 4.dp)
                .offset {
                    val y = fraction.coerceIn(0f, 1f) * (height - thumb)
                    androidx.compose.ui.unit.IntOffset(0, y.toInt())
                }
                .size(width = 8.dp, height = 56.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant),
        )
    }
}
