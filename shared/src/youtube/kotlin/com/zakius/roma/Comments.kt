package com.zakius.roma

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.semantics.stateDescription
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.mutableLongStateOf
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.text.SpanStyle
import roma.core.Romanizer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import roma.core.Line
import roma.core.Lyrics
import roma.core.attachTranslation
import roma.core.pickTranslation

// The player's comments view: YouTube's comments on the playing song, a page at a time.

/** A comment as shown: its text without HTML, and the romanization when it has non-Latin text the user doesn't read. */
private class ShownComment(val c: roma.core.Comment, val text: String, val latin: String?) {
    /** Its replies, loaded when first opened. */
    val replies by lazy { CommentList(c.thread) }
    var open by mutableStateOf(false)
    /** A long comment shows a few lines until tapped. Kept here, not in the row, so it survives scrolling away. */
    var unfolded by mutableStateOf(false)
    /**
     * Google's translation, once asked for (see [CommentList.translate]). It is also null when the comment is already
     * in that language.
     */
    var translation by mutableStateOf<String?>(null)
    var translated = false
}

/** Comments loaded a page at a time: the song's, or one comment's replies. */
private class CommentList(var next: (() -> roma.core.CommentPage)?, val songId: String? = null) {
    val items = androidx.compose.runtime.mutableStateListOf<ShownComment>()
    var disabled by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    /** The Translate chip is on: each comment shown, and each reply, is translated as it comes into view. */
    var translate by mutableStateOf(false)

    suspend fun load() {
        val fetch = next ?: return
        if (loading) return
        loading = true
        error = null
        try {
            val page = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val p = fetch()
                val shown = p.comments.map { c ->
                    // A reply starting with an @mention has a zero-width space first.
                    val trimmed = htmlToText(c.text).trim { ch -> ch.isWhitespace() || ch == '\u200B' }
                    // Keep at most one blank line in a row.
                    val text = trimmed.replace(Regex("\n\\s*\n\\s*\n+"), "\n\n")
                    val latin = if (!Romanizer.hasNonLatin(text) || Store.reads(text)) {
                        null
                    } else {
                        text.lines().joinToString("\n") { line -> Store.romanizer.machine(line, sentence = true) }
                    }
                    ShownComment(c, text, latin)
                }
                p to shown
            }
            items += page.second
            disabled = page.first.disabled
            next = page.first.next
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = plainError(e)
        } finally {
            loading = false
        }
    }
}

/** The playing song's comments, kept while it plays so switching views doesn't load them again. */
private var songComments: CommentList? = null

@Composable
internal fun CommentsView() {
    // Reading tick redraws this view when the restored queue arrives after the first frame.
    Playback.tick
    val songId = Playback.shown()?.id
    if (songId == null) {
        if (!Playback.restoring) Centered("Nothing playing.")
        return
    }
    val state = remember(songId) {
        val kept = songComments
        if (kept != null && kept.songId == songId) {
            kept
        } else {
            val created = CommentList({ roma.core.Comments.of(songId) }, songId)
            songComments = created
            created
        }
    }
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    // The first page loads at once, and the next when the list nears its end.
    val nearEnd by remember {
        derivedStateOf {
            val lastVisible = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= list.layoutInfo.totalItemsCount - 5
        }
    }
    // When hidden, the list stays measured but doesn't page on.
    val shown = LocalTabShown.current
    LaunchedEffect(state, nearEnd, state.error, state.items.size, shown) {
        if (state.error == null && (state.items.isEmpty() || nearEnd && shown)) state.load()
    }
    val scope = rememberCoroutineScope()
    when {
        state.disabled && state.items.isEmpty() -> return Centered("Comments are turned off for this song.")
        state.items.isEmpty() && state.error != null -> return Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
            Text(state.error!!, Modifier.padding(horizontal = 24.dp), textAlign = TextAlign.Center)
            TextButton(onClick = { scope.launch { state.load() } }) { Text("Retry") }
        }
        state.items.isEmpty() -> return Centered(if (state.loading) "Loading comments…" else "No comments yet.")
    }
    // A strip at the top holds the Translate and sleep chips, as in the lyrics. The comments scroll under the strip,
    // not under the chips.
    Box {
        LazyColumn(Modifier.fillMaxSize().padding(top = CHIP_STRIP), list) {
            items(state.items.size) { i ->
                val s = state.items[i]
                val r = s.replies
                val toggleReplies: () -> Unit = {
                    s.open = !s.open
                    if (s.open && r.items.isEmpty()) scope.launch { r.load() }
                }
                CommentRow(s, state, onReplies = if (s.c.thread != null) toggleReplies else null)
                // A thread opens under its comment, with its avatars in line with the comment's text. Later pages load
                // on "More replies".
                if (s.open) {
                    Column(Modifier.padding(start = 48.dp, bottom = 4.dp)) {
                        for (reply in r.items) CommentRow(reply, state, avatar = 24.dp)
                        val more = Modifier.padding(start = 4.dp)
                        when {
                            r.loading -> Text(
                                "Loading…",
                                Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            r.error != null -> TextButton(onClick = { scope.launch { r.load() } }, more) { Text("${r.error} Retry") }
                            r.next != null -> TextButton(onClick = { scope.launch { r.load() } }, more) { Text("More replies") }
                        }
                    }
                }
            }
            if (state.next != null || state.error != null) {
                item {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        if (state.error != null) {
                            TextButton(onClick = { scope.launch { state.load() } }) { Text("Retry") }
                        } else {
                            Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        FastScroll(list)
        // Like the lyrics' chip, but a toggle: comments keep coming as the list scrolls, so it stays on for them.
        Surface(
            Modifier.align(Alignment.TopEnd).padding(4.dp),
            shape = RoundedCornerShape(50),
            color = if (state.translate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Text(
                if (state.translate) "Original" else "Translate",
                Modifier
                    .clickable { state.translate = !state.translate }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun CommentRow(s: ShownComment, list: CommentList, avatar: androidx.compose.ui.unit.Dp = 36.dp, onReplies: (() -> Unit)? = null) {
    val c = s.c
    // Sent to Google only after the user's tap, one comment at a time, because comments mix languages and Google
    // detects one language per request.
    LaunchedEffect(list.translate) {
        if (!list.translate || s.translated) return@LaunchedEffect
        val lang = Store.translationLanguages().first()
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { roma.core.GoogleTranslate.text(s.text, lang) }
        }
        result.onSuccess { translation ->
            s.translation = translation
            s.translated = true
        }
        result.onFailure {
            if (list.translate) {
                list.translate = false
                toast("Couldn't translate. Check Wi-Fi or mobile data, then try again.", long = true)
            }
        }
    }
    val type = MaterialTheme.typography
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = if (onReplies == null) 8.dp else 0.dp),
    ) {
        // The author's initial, not their picture, so there is no image to fetch for every comment.
        Box(
            Modifier
                .size(avatar)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                c.author.removePrefix("@").take(1).uppercase(),
                style = if (avatar < 36.dp) type.labelSmall else type.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            if (c.pinned) Text("Pinned", style = type.labelSmall, color = MaterialTheme.colorScheme.primary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.author, Modifier.weight(1f, fill = false), style = type.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (c.ago.isNotEmpty()) Text(" · ${c.ago}", style = type.labelMedium, color = muted, maxLines = 1)
            }
            Spacer(Modifier.height(2.dp))
            var folds by remember { mutableStateOf(false) }
            val lines = if (s.unfolded) Int.MAX_VALUE else 6
            val foldToggle = if (folds) {
                Modifier.clickable(onClickLabel = if (s.unfolded) "Show less" else "Read more") { s.unfolded = !s.unfolded }
            } else {
                Modifier
            }
            Column(foldToggle) {
                Text(
                    s.latin ?: s.text,
                    style = type.bodyMedium,
                    maxLines = lines,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { layout -> folds = folds || layout.hasVisualOverflow },
                )
                if (s.latin != null) {
                    Text(
                        s.text,
                        style = type.bodySmall,
                        color = muted,
                        maxLines = lines,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { layout -> folds = folds || layout.hasVisualOverflow },
                    )
                }
                val translation = s.translation
                if (list.translate && translation != null) {
                    Text(
                        translation,
                        Modifier.padding(top = 4.dp),
                        style = type.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = lines,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { layout -> folds = folds || layout.hasVisualOverflow },
                    )
                }
                if (folds) {
                    val label = if (s.unfolded) "Show less" else "Read more"
                    Text(label, Modifier.padding(top = 2.dp), style = type.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (c.likes.isNotBlank() || onReplies != null) {
                val rowModifier = if (onReplies != null) Modifier.heightIn(min = 40.dp) else Modifier.padding(top = 6.dp)
                Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
                    // With a replies button and no likes, the empty slot still holds the button's place.
                    if (c.likes.isBlank() && onReplies != null) Spacer(Modifier.width(72.dp))
                    if (c.likes.isNotBlank()) {
                        val likesLabel = if (c.likes == "1") "1 like" else "${c.likes} likes"
                        // A fixed width, so the replies button after it starts at the same place on every comment.
                        Row(
                            Modifier
                                .widthIn(min = 64.dp)
                                .padding(end = 8.dp)
                                .clearAndSetSemantics { contentDescription = likesLabel },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.ThumbUp, null, Modifier.size(14.dp), tint = muted)
                            Spacer(Modifier.size(4.dp))
                            Text(c.likes, style = type.labelMedium, color = muted)
                        }
                    }
                    if (onReplies != null) {
                        TextButton(onReplies, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                            val repliesLabel = when {
                                s.open -> "Hide replies"
                                c.replies == 1 -> "1 reply"
                                else -> "${c.replies} replies"
                            }
                            Text(repliesLabel, style = type.labelLarge)
                            val arrow = if (s.open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown
                            Icon(arrow, null, Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}
