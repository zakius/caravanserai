package com.zakius.roma

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
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

/**
 * The player. Only the area above the bottom block changes (song, lyrics, queue). The bottom block has fixed
 * heights and fixed slots, so no control ever moves; an unavailable control is dimmed in place.
 */
@Composable
fun PlayerScreen() {
    Playback.tick
    var view by Nav::playerView
    // Back closes the lyrics or queue view first, then leaves the player as usual.
    BackHandler(enabled = view != "song" && LocalTabShown.current) { view = "song" }
    var menuOpen by remember { mutableStateOf(false) }
    var sleepOpen by remember { mutableStateOf(false) }
    // Where a seek-bar drag would land, -1 when there's no drag: the upper area shows the lyric line there.
    var seekPreview by remember { mutableLongStateOf(-1L) }
    val haptic = LocalHapticFeedback.current
    // While a mix loads, the song still playing is on its way out: its controls are dimmed.
    val entry = if (Playback.opening != null) null else Playback.current()
    val p = Playback.player
    val loadedLyrics = Playback.lyrics
    val lyricsState = if (loadedLyrics != null && loadedLyrics.songId == Playback.shown()?.id) loadedLyrics else null
    val lyrics = lyricsState?.lyrics
    val hasLyrics = lyrics != null && lyrics.lines.isNotEmpty() && Playback.opening == null
    if (view == "lyrics" && !hasLyrics && lyrics != null) view = "song"
    val visitedViews = remember { androidx.compose.runtime.mutableStateListOf(view) }
    if (view !in visitedViews) visitedViews += view
    // The comments view isn't built ahead, because building it loads the comments from the network.
    LaunchedEffect(Unit) { buildAhead(visitedViews, listOf("song", "queue", "lyrics")) }
    val tabShown = LocalTabShown.current

    val upper: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier) {
            // Each view stays composed once opened, as tabs do, so going back to it only lays out and draws. Building
            // the queue again costs about 10 ms of the main thread on a phone.
            for (v in visitedViews) {
                androidx.compose.runtime.key(v) {
                    val viewModifier = if (v == view && Playback.opening == null) Modifier.fillMaxSize() else Modifier.hidden()
                    Box(viewModifier) {
                        androidx.compose.runtime.CompositionLocalProvider(LocalTabShown provides (tabShown && v == view)) {
                            when (v) {
                                "lyrics" -> {
                                    LyricsBackdrop(Playback.shown()?.thumb)
                                    if (lyricsState != null && lyrics != null) {
                                        LyricsView(lyricsState, lyrics, Playback.shown()?.id)
                                    } else {
                                        Centered("Looking for lyrics…")
                                    }
                                }
                                "queue" -> QueueView()
                                "comments" -> backend.Comments()
                                else -> SongView()
                            }
                        }
                    }
                }
            }
            val opening = Playback.opening
            if (opening != null) OpeningPage(opening.first, opening.second)
            val lastBan = Playback.lastBan
            if (lastBan != null) {
                val song = lastBan.first
                Surface(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(8.dp)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                ) {
                    Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Banned · ${song.title.latin}",
                            Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = { Playback.undoBan() }) {
                            Text("Undo", color = MaterialTheme.colorScheme.inversePrimary)
                        }
                    }
                }
            }
            // Top left, clear of Translate on the right even with a large font; the lists start below the chips.
            SleepChip(Modifier.align(Alignment.TopStart)) { sleepOpen = true }
            val line = if (seekPreview >= 0 && lyrics != null && lyrics.synced) {
                lyrics.lines.lastOrNull { it.startMs <= seekPreview }
            } else {
                null
            }
            if (line != null && line.text.isNotBlank()) SeekPreview(line, Modifier.align(Alignment.BottomCenter))
        }
    }
    // The bottom block, top to bottom: seek bar, action row, playback row (nearest the thumb; the nav bar is under it).
    // It has fixed heights and slots; a wide screen only puts it beside the upper area.
    val controls: @Composable (Modifier) -> Unit = { modifier ->
        FixedRowsFont {
            Column(modifier) {
                SeekBar { seekPreview = it }
                // More (the song menu, where Ban lives) sits at the outer edge with two empty slots after it, and Like
                // at the other edge, under the thumb. Left-handed mode mirrors the row; otherwise slots never move.
                Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                    val liked = entry?.song?.id in Playback.liked
                    val slots: List<@Composable RowScope.() -> Unit> = listOf(
                        {
                            Slot(rememberVectorPainter(Icons.Default.MoreVert), "More", entry != null, label = "More") {
                                menuOpen = true
                            }
                        },
                        // Deliberately empty: no icon, no label, not tappable.
                        { Spacer(Modifier.weight(1f)) },
                        // A source without comments leaves this slot empty too.
                        if (!backend.hasComments) {
                            { Spacer(Modifier.weight(1f)) }
                        } else {
                            {
                                Slot(
                                    rememberVectorPainter(CommentsIcon),
                                    "Comments",
                                    entry != null,
                                    view == "comments",
                                    label = "Comments",
                                    state = if (view == "comments") "shown" else null,
                                ) {
                                    view = if (view == "comments") "song" else "comments"
                                }
                            }
                        },
                        {
                            Slot(
                                rememberVectorPainter(LyricsIcon),
                                "Lyrics",
                                hasLyrics || view == "lyrics",
                                view == "lyrics",
                                label = "Lyrics",
                                state = if (view == "lyrics") "shown" else null,
                            ) {
                                view = if (view == "lyrics") "song" else "lyrics"
                            }
                        },
                        {
                            Slot(
                                rememberVectorPainter(QueueIcon),
                                "Queue",
                                entry != null,
                                view == "queue",
                                label = "Queue",
                                state = if (view == "queue") "shown" else null,
                            ) {
                                view = if (view == "queue") "song" else "queue"
                            }
                        },
                        {
                            Slot(
                                rememberVectorPainter(likeIcon(liked)),
                                likeWord,
                                Playback.canLike(entry?.song),
                                liked,
                                label = likeWord,
                                state = if (liked) "${likeWord.lowercase()}d" else "not ${likeWord.lowercase()}d",
                            ) {
                                haptic.performHapticFeedback(if (liked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                                Playback.toggleLike()
                            }
                        },
                    )
                    val ordered = if (Store.bool("ui.leftHanded", false)) slots.reversed() else slots
                    for (slot in ordered) slot()
                }
                Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Slot(
                        painterResource(R.drawable.shuffle),
                        "Shuffle",
                        entry != null,
                        Playback.shuffled,
                        iconSize = 26,
                        state = if (Playback.shuffled) "on" else "off",
                    ) {
                        Playback.toggleShuffle()
                    }
                    Slot(painterResource(R.drawable.previous), "Previous", entry != null, iconSize = 30) { Playback.previous() }
                    // Shows what the user asked for: Pause while a song buffers or waits for the network, too.
                    val showPlay = p.showPlayButton
                    // A long press opens the sleep timer: a hidden extra on a button that's there anyway, so the row
                    // stays as it is.
                    Slot(
                        painterResource(if (showPlay) R.drawable.play else R.drawable.pause),
                        if (showPlay) "Play" else "Pause",
                        entry != null,
                        iconSize = 44,
                        onLongClick = { sleepOpen = true },
                        longClickLabel = "Sleep timer",
                    ) {
                        Playback.togglePlay()
                    }
                    val hasNext = Playback.neighbour(Playback.shownIndex(), 1) >= 0
                    Slot(painterResource(R.drawable.next), "Next", hasNext, iconSize = 30) { Playback.next() }
                    val repeatIcon = when (p.repeatMode) {
                        AudioPlayer.REPEAT_MODE_ONE -> R.drawable.repeat_one
                        AudioPlayer.REPEAT_MODE_ALL -> R.drawable.repeat
                        // Off is shown as muted (see Slot), not as another icon.
                        else -> R.drawable.repeat
                    }
                    val repeatState = when (p.repeatMode) {
                        AudioPlayer.REPEAT_MODE_ONE -> "this song"
                        AudioPlayer.REPEAT_MODE_ALL -> "all"
                        else -> "off"
                    }
                    Slot(
                        painterResource(repeatIcon),
                        "Repeat",
                        entry != null,
                        p.repeatMode != AudioPlayer.REPEAT_MODE_OFF,
                        iconSize = 26,
                        state = repeatState,
                    ) {
                        Playback.cycleRepeat()
                    }
                }
            }
        }
    }

    // Landscape (a phone on its side, the MatePad): the song, lyrics or queue on the left, the same bottom block on the
    // right. Otherwise one column; on a wide portrait screen the block keeps a phone's width, so the thumb reaches it.
    val screen = screenSizeDp()
    if (screen.width > screen.height) {
        Row(Modifier.fillMaxSize()) {
            upper(Modifier.weight(1f).fillMaxHeight())
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                controls(Modifier.widthIn(max = 560.dp).fillMaxWidth())
            }
        }
    } else {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            upper(Modifier.weight(1f).fillMaxWidth())
            controls(Modifier.widthIn(max = 560.dp).fillMaxWidth())
        }
    }
    if (menuOpen && entry != null) SongMenu(entry.song, queueIndex = null) { menuOpen = false }
    if (sleepOpen) SleepDialog { sleepOpen = false }
}

/**
 * Behind the lyrics: the song's art, blurred and faint, fading into the background at the top and bottom edges so the
 * first and last lines read as well as the rest. The blur is a 16 px copy stretched to the full size: at 20 % it
 * reads the same as a real blur, without blurring a full-screen layer on the GPU every frame.
 */
@Composable
private fun LyricsBackdrop(thumb: String?) {
    if (thumb == null) return
    ThumbPx(
        bigThumb(thumb),
        16,
        Modifier.fillMaxSize(),
        alpha = 0.2f,
        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        exact = true,
    )
    val bg = MaterialTheme.colorScheme.surface
    val edgeFade = Brush.verticalGradient(0f to bg, 0.15f to Color.Transparent, 0.85f to Color.Transparent, 1f to bg)
    Box(Modifier.fillMaxSize().background(edgeFade))
}

/** While the sleep timer runs: how long is left, over the top of the upper area. Tap: change or turn it off. */
@Composable
private fun SleepChip(modifier: Modifier, onClick: () -> Unit) {
    // Reading the clock makes the chip count down twice a second, also while paused. A hidden chip doesn't follow it.
    if (LocalTabShown.current) Playback.clock
    val text = when {
        Playback.sleepAfterSong -> "Sleep when this song ends"
        Playback.sleepAt != 0L -> {
            val minutes = ((Playback.sleepAt - elapsedRealtime()) / 60_000 + 1).coerceAtLeast(1)
            "Sleep in $minutes min"
        }
        else -> return
    }
    // One line, at most about 60% of a phone's width: with a large font it ends in "…" rather than reaching Translate.
    Chip(text, enabled = true, modifier.widthIn(max = 256.dp), onClick)
}

/**
 * A chip over the top of the upper area (the sleep timer, Translate): a pill on the content's 16 dp edge, in a 48 dp
 * tall touch target that fills [CHIP_STRIP].
 */
@Composable
private fun Chip(text: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp)
            .dim(if (enabled) 1f else 0.38f),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            Modifier
                .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Top right of the lyrics, always there. Translate asks Google for this song's lyrics in the first translation
 * language. Once the song has a translation in that language, it reads Original and hides the translation, and then
 * Translate again shows it. Dimmed while it works, when the song has no lyrics, or when translations are switched
 * off. Settings → Search lyrics again drops a Google translation.
 */
@Composable
private fun TranslateChip(found: LyricsState, lyricsShown: Boolean, translated: Boolean, hidden: Boolean, onHide: (Boolean) -> Unit, modifier: Modifier) {
    var busy by remember(found.songId) { mutableStateOf(false) }
    val text = when {
        busy -> "Translating…"
        translated && !hidden -> "Original"
        else -> "Translate"
    }
    Chip(text, !busy && lyricsShown, modifier) {
        if (translated) {
            onHide(!hidden)
        } else {
            busy = true
            Playback.googleTranslate(found.songId) { msg ->
                busy = false
                if (msg != null) toast(msg, long = true)
            }
        }
    }
}

/** The lyric line at the seek bar's drag position, over the bottom of the upper area: where the drag would land. */
@Composable
private fun SeekPreview(line: Line, modifier: Modifier) {
    Surface(
        modifier.padding(8.dp).fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(line.text, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (line.latin.isNotBlank() && line.latin != line.text && Romanizer.hasNonLatin(line.text)) {
                Text(line.latin, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SleepDialog(onDone: () -> Unit) {
    val active = Playback.sleepAt != 0L || Playback.sleepAfterSong
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Sleep timer") },
        text = {
            Column {
                listOf(15, 30, 45, 60, 90).forEach { m ->
                    val label = when {
                        m < 60 -> "$m minutes"
                        m == 60 -> "1 hour"
                        else -> "1.5 hours"
                    }
                    Text(
                        label,
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                Playback.sleepIn(m)
                                onDone()
                            }
                            .padding(vertical = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Text(
                    "When this song ends",
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            Playback.sleepAtSongEnd()
                            onDone()
                        }
                        .padding(vertical = 12.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        },
        confirmButton = {
            if (active) {
                TextButton(
                    onClick = {
                        Playback.sleepOff()
                        onDone()
                    },
                ) {
                    Text("Turn off")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

/** One equal-width control slot. Disabled slots keep their place and are dimmed. */
@Composable
private fun RowScope.Slot(
    icon: Painter, description: String, enabled: Boolean, selected: Boolean = false, label: String? = null, iconSize: Int = 22,
    state: String? = null, onLongClick: (() -> Unit)? = null, longClickLabel: String? = null, onClick: () -> Unit,
) {
    // A switch without a label (Shuffle, Repeat) gets the pill when on. When off it is muted, but less than an
    // unavailable control (0.3).
    val offAlpha = if (state != null && label == null) 0.6f else 1f
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = offAlpha)
    // A rounded background only when selected, not a rounded clip on every slot: one GPU program less.
    val selectedBackground = if (selected) {
        Modifier.background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp))
    } else {
        Modifier
    }
    Box(
        Modifier
            .weight(1f)
            .widthIn(min = 48.dp)
            .fillMaxHeight()
            .padding(horizontal = 2.dp)
            .then(selectedBackground)
            .combinedClickable(
                enabled = enabled,
                onClickLabel = description,
                role = Role.Button,
                onLongClick = onLongClick,
                onLongClickLabel = longClickLabel,
                onClick = onClick,
            )
            .semantics { if (state != null) stateDescription = state }
            .dim(if (enabled) 1f else 0.3f),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // With a label, the icon has no description, because the label is read instead.
            Icon(icon, if (label != null) null else description, Modifier.size(iconSize.dp), tint = color)
            // Shrinks to fit rather than losing letters: with a large system font "Comments" is wider than its slot.
            if (label != null) {
                androidx.compose.foundation.text.BasicText(
                    label,
                    style = androidx.compose.ui.text.TextStyle(color = color, lineHeight = 12.sp),
                    maxLines = 1,
                    softWrap = false,
                    autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 7.sp, maxFontSize = 10.sp),
                )
            }
        }
    }
}

@Composable
private fun SeekBar(onPreview: (Long) -> Unit) {
    Playback.tick
    // A hidden bar doesn't follow the clock, because redrawing the window twice a second wakes the GPU each time.
    // Shown again, reading LocalTabShown brings it up to date.
    if (LocalTabShown.current) Playback.clock
    val p = Playback.player
    val haptic = LocalHapticFeedback.current
    // The length comes from the song being prepared, else the player. A song restored after a restart isn't prepared
    // until Play, so its known length stands in.
    val duration = if (Playback.opening != null) {
        0L
    } else {
        Playback.pending?.let { it.durationSec * 1000L }
            ?: p.duration.takeIf { it > 0 }
            ?: Playback.current()?.song?.durationSec?.let { it * 1000L }
            ?: 0L
    }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    var snappedTo by remember { mutableLongStateOf(-1L) }
    var width by remember { mutableIntStateOf(1) }
    // Near a timed lyric line's start (12 dp of travel), a drag snaps to it with a tick, so a seek starts on a line
    // instead of a word into it.
    val lyricsState = Playback.lyrics
    val shownLyrics = if (lyricsState != null && lyricsState.songId == Playback.shown()?.id) lyricsState.lyrics else null
    val lyrics = if (shownLyrics != null && shownLyrics.synced) shownLyrics else null
    val snapMs = with(LocalDensity.current) { 12.dp.toPx() } / width * duration
    // While a picked song is being prepared, the old one's progress would belong to another title: show the new start.
    val preparing = Playback.pending != null || Playback.opening != null
    val pos = when {
        preparing -> 0f
        dragging -> dragValue
        else -> p.currentPosition.toFloat()
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(time(pos.toLong()), Modifier.widthIn(min = 36.dp), fontSize = 12.sp, textAlign = TextAlign.Start, maxLines = 1)
        Slider(
            value = pos.coerceIn(0f, duration.toFloat().coerceAtLeast(1f)),
            onValueChange = { v ->
                val nearest = lyrics?.lines?.minByOrNull { line -> abs(line.startMs - v) }?.startMs
                val near = if (nearest != null && abs(nearest - v) < snapMs) nearest else null
                if (near != null && near != snappedTo) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                snappedTo = near ?: -1L
                dragging = true
                dragValue = near?.toFloat() ?: v
                onPreview(dragValue.toLong())
            },
            onValueChangeFinished = {
                p.seekTo(dragValue.toLong())
                dragging = false
                snappedTo = -1L
                onPreview(-1L)
            },
            valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
            enabled = duration > 0 && Playback.pending == null,
            modifier = Modifier
                .weight(1f)
                .height(28.dp)
                .padding(horizontal = 8.dp)
                .onSizeChanged { width = it.width.coerceAtLeast(1) }
                .semantics { stateDescription = "${time(pos.toLong())} of ${time(duration)}" },
        )
        Text(
            if (duration > 0) time(duration) else "",
            Modifier.widthIn(min = 36.dp),
            fontSize = 12.sp,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
    }
}

/** A length or position as m:ss. */
internal fun time(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/**
 * The song: art and names. Gestures on it, none of which moves a control: swipe sideways for the next or previous song
 * (it peeks in as you drag), double-tap to like, swipe down to leave the player.
 */
@Composable
private fun SongView() {
    Playback.tick
    val s = Playback.shown()
    if (s == null) {
        if (!Playback.restoring) Centered("Nothing playing. Pick a song in Home, Search or Library.")
        return
    }
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var dx by remember { mutableFloatStateOf(0f) }
    var dy by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(1) }
    val at = Playback.shownIndex()
    val targets by rememberUpdatedState(Playback.neighbour(at, 1) to Playback.neighbour(at, -1))
    val side by remember { derivedStateOf { kotlin.math.sign(dx) } }
    val leave = with(LocalDensity.current) { 96.dp.toPx() }
    val pop = remember { Animatable(0f) }
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    // Only likes, never unlikes: a double tap by accident shouldn't undo one.
                    val song = Playback.shown() ?: return@detectTapGestures
                    if (!Playback.canLike(song)) {
                        haptic.performHapticFeedback(HapticFeedbackType.Reject)
                        return@detectTapGestures
                    }
                    if (song.id !in Playback.liked) Playback.toggleLike(song)
                    haptic.performHapticFeedback(HapticFeedbackType.ToggleOn)
                    scope.launch {
                        pop.snapTo(1f)
                        pop.animateTo(0f, tween(700))
                    }
                })
            }
            .pointerInput(Unit) {
                val tracker = VelocityTracker()
                // 0 while the direction isn't decided yet, 1 for sideways, 2 for down.
                var axis = 0
                var armed = false
                fun settle() = scope.launch {
                    launch { animate(dx, 0f) { v, _ -> dx = v } }
                    animate(dy, 0f) { v, _ -> dy = v }
                }
                detectDragGestures(
                    onDragStart = {
                        tracker.resetTracking()
                        axis = 0
                        armed = false
                    },
                    onDragCancel = { settle() },
                    onDrag = { change, d ->
                        tracker.addPointerInputChange(change)
                        if (axis == 0) axis = if (abs(d.x) >= abs(d.y)) 1 else 2
                        if (axis == 1) {
                            val target = if (dx + d.x < 0) targets.first else targets.second
                            // With no song on that side, the page resists the drag.
                            dx += if (target < 0) d.x * 0.3f else d.x
                            val past = target >= 0 && abs(dx) > width * 0.25f
                            if (past && !armed) haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                            armed = past
                        } else {
                            dy = (dy + d.y).coerceAtLeast(0f)
                        }
                    },
                    onDragEnd = {
                        val v = tracker.calculateVelocity()
                        if (axis == 2) {
                            if (dy > leave || v.y > 2000f) {
                                Nav.leavePlayer()
                                dy = 0f
                            } else {
                                settle()
                            }
                            return@detectDragGestures
                        }
                        val toNext = dx < 0
                        val target = if (toNext) targets.first else targets.second
                        val flung = abs(v.x) > 1000f && (v.x < 0) == toNext
                        if (target < 0 || !(armed || flung)) {
                            settle()
                            return@detectDragGestures
                        }
                        // The skip starts on release, so the seek bar, the lyrics and the queue switch with it. The page that
                        // was peeking becomes the current one where it is, and slides the rest of the way in.
                        Playback.skipTo(target)
                        dx += if (toNext) width.toFloat() else -width.toFloat()
                        scope.launch { animate(dx, 0f) { x, _ -> dx = x } }
                    },
                )
            },
    ) {
        // The neighbour is composed only while a drag shows it.
        val peek = when {
            side < 0 -> targets.first
            side > 0 -> targets.second
            else -> -1
        }
        val neighbour = Playback.songAt(peek)
        if (neighbour != null) {
            SongPage(neighbour, current = false, Modifier.graphicsLayer {
                val offset = if (dx < 0) width else -width
                translationX = dx + offset
            })
        }
        SongPage(s, current = true, Modifier.graphicsLayer {
            translationX = dx
            translationY = dy
            alpha = 1f - (dy / (leave * 3)).coerceIn(0f, 0.5f)
        })
        if (pop.value > 0f) {
            Icon(
                likeIcon(true),
                null,
                Modifier
                    .align(Alignment.Center)
                    .size(96.dp)
                    .graphicsLayer {
                        alpha = pop.value
                        scaleX = 1.4f - 0.4f * pop.value
                        scaleY = scaleX
                    },
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** A mix from Home while its songs load: its cover and name, then its first song as soon as that's known. */
@Composable
private fun OpeningPage(title: String, thumb: String?) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (thumb != null) Thumb(shownThumb(thumb, 240.dp), 240.dp, Modifier.size(240.dp).clip(RoundedCornerShape(8.dp)))
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SongPage(s: roma.core.Song, current: Boolean, modifier: Modifier) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Until the large art arrives, the list's thumbnail of this song (row or card size) stands in, if it's in memory.
        val density = androidx.compose.ui.platform.LocalDensity.current
        val placeholder = listOf(48.dp, 56.dp, 128.dp).firstNotNullOfOrNull { d ->
            val px = with(density) { d.roundToPx() }
            val url = thumbAt(s.thumb, px)
            if (url == null) null else Images.peek(url, px)
        }
        val thumb = s.thumb
        val big = if (thumb == null) null else bigThumb(thumb)
        ThumbPx(
            big,
            BIG_PX,
            Modifier
                .weight(1f, fill = false)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            placeholder = placeholder,
        )
        Spacer(Modifier.height(16.dp))
        // Tapping the title opens its album, the artist their page (as More → Go to album / artist). Long-pressing either
        // opens the correction dialog, the same as More → Correct romanization.
        var fixing by remember { mutableStateOf(false) }
        fun Modifier.goTo(kind: String, id: String?): Modifier {
            val clickLabel = if (id != null) "Go to $kind" else null
            val longClickLabel = if (s.romanizable) "Correct romanization" else null
            var onLongClick: (() -> Unit)? = null
            if (s.romanizable) onLongClick = { fixing = true }
            return combinedClickable(
                onClickLabel = clickLabel,
                onLongClickLabel = longClickLabel,
                onLongClick = onLongClick,
            ) {
                if (id != null) Nav.open(Page(kind, id))
            }
        }
        // The names' block keeps the height of the longest it can be (a two-line title with its original, the artist with
        // theirs, the album), so the art above stays put from song to song.
        val title = MaterialTheme.typography.headlineSmall
        val artistStyle = MaterialTheme.typography.titleMedium.copy(
            fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val small = MaterialTheme.typography.bodySmall
        val namesHeight = with(LocalDensity.current) {
            (title.fontSize * 1.25f * 2).toDp() + small.lineHeight.toDp() * 3 + (artistStyle.fontSize * 1.25f).toDp() + 4.dp
        }
        Column(Modifier.heightIn(min = namesHeight), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.goTo("album", s.album?.id)) {
                Name(s.title, Store.playerMode, style = title, center = true)
            }
            Spacer(Modifier.height(4.dp))
            Column(Modifier.goTo("artist", s.firstArtistId)) {
                Name(s.artistText, Store.playerMode, style = artistStyle, center = true, maxLines = 1)
            }
            // The album, when it isn't the song's own title (a single).
            val album = s.album?.name
            if (album != null && album.latin.isNotBlank() && album.original != s.title.original) {
                Text(
                    Store.shown(album),
                    Modifier.fillMaxWidth().goTo("album", s.album?.id),
                    style = small,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (fixing) CorrectDialog(s) { fixing = false }
        if (current && Playback.waitingForNetwork) {
            Spacer(Modifier.height(8.dp))
            Text("Waiting for network…", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        if (current) {
            val error = Playback.error
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text("Can't play this song: $error", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                Row {
                    TextButton(
                        onClick = {
                            Playback.player.prepare()
                            Playback.player.play()
                        },
                    ) {
                        Text("Retry")
                    }
                    if (Playback.player.hasNextMediaItem()) {
                        TextButton(onClick = { Playback.next() }) { Text("Play the next song") }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueView() {
    Playback.tick
    val all = Playback.entries()
    val cur = Playback.player.currentMediaItemIndex
    var menuFor by remember { mutableStateOf<Int?>(null) }
    // The next songs are the likely taps here.
    val shown = LocalTabShown.current
    LaunchedEffect(shown) { if (shown) Playback.prepare(all.drop(cur + 1).map { it.song }) }
    // Each row is a header with -1, or null with an entry's index.
    val rows = ArrayList<Pair<String?, Int>>()
    if (cur in all.indices) {
        rows.add("Now" to -1)
        rows.add(null to cur)
    }
    val queued = (cur + 1 until all.size).filter { all[it].section == "queued" }
    val radio = (cur + 1 until all.size).filter { all[it].section == "radio" }
    if (queued.isNotEmpty()) {
        rows.add("Up next" to -1)
        for (i in queued) rows.add(null to i)
    }
    if (radio.isNotEmpty()) {
        rows.add("Radio" to -1)
        for (i in radio) rows.add(null to i)
    }
    if (queued.isEmpty() && radio.isEmpty()) {
        val note = if (Store.stopAtEnd) "Nothing next: autoplay is off" else "Nothing next yet"
        rows.add(note to -1)
    }
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    PrefetchCovers(all.drop(cur + 1).map { it.song.thumb }, 48.dp, list)
    Box {
        // The strip is kept for the sleep timer's chip only while it runs.
        val sleeping = Playback.sleepAt != 0L || Playback.sleepAfterSong
        LazyColumn(Modifier.fillMaxSize().padding(top = if (sleeping) CHIP_STRIP else 0.dp), list) {
            itemsIndexed(rows, contentType = { _, (header, _) -> header == null }) { _, (header, i) ->
                if (header != null) {
                    Header(header)
                } else {
                    val e = all[i]
                    // "offline" means the whole song is on the phone, so it plays with no network.
                    val saved = if (Playback.player.fullyCached(e.song.id)) "offline" else null
                    val extra = listOfNotNull(e.song.reason, saved).joinToString(" · ").ifEmpty { null }
                    SongRow(e.song, highlight = i == cur, extra = extra, onMenu = { menuFor = i }) { Playback.skipTo(i) }
                }
            }
        }
        FastScroll(list)
    }
    val menuIndex = menuFor
    if (menuIndex != null && menuIndex in all.indices) {
        SongMenu(all[menuIndex].song, queueIndex = menuIndex) { menuFor = null }
    }
}

@Composable
private fun LyricsView(found: LyricsState, bare: Lyrics, songId: String?) {
    // KuGou and NetEase translate into Chinese: attach a translation layer only in the chosen language.
    val showTranslation = Store.bool("lyrics.layer.translation", true)
    val wantedLang = Store.str("lyrics.translationLang", "device")
    // Translate's chip hides a translation for this song and shows it again.
    var translationHidden by remember(songId) { mutableStateOf(false) }
    val shown = remember(found, showTranslation, wantedLang, translationHidden) {
        val translation = if (showTranslation && !translationHidden) {
            pickTranslation(bare, found.translations, Store.translationLanguages())
        } else {
            null
        }
        attachTranslation(bare, translation)
    }
    val lines = shown.lines
    val synced = shown.synced
    // The current line and word follow the position. The loop sleeps until the next line or word starts, or until a
    // player change (Playback.tick: a seek, pause, start or speed change) comes first. It doesn't run once per display
    // frame: at 120 Hz that would wake the main thread 120 times a second for a word change every few hundred ms.
    // While paused, only a player change moves the position.
    // The view opens (or the lyrics arrive) already at the current line: it is read from the player here, before the
    // first frame, and the list starts there. Only later line changes scroll smoothly.
    val startLine = remember(lines) {
        if (synced && Playback.pending == null) {
            lines.indexOfLast { it.startMs <= Playback.player.currentPosition }
        } else {
            -1
        }
    }
    var current by remember(lines) { mutableIntStateOf(startLine) }
    var currentWord by remember(lines) { mutableIntStateOf(-1) }
    // Paused while another tab is shown; back on screen, it starts again from the current position.
    val onScreen = LocalTabShown.current
    LaunchedEffect(lines, synced, onScreen) {
        if (!synced || !onScreen) return@LaunchedEffect
        while (true) {
            val seen = Playback.tick to Playback.pending
            val pos = if (Playback.pending != null) -1L else Playback.player.currentPosition
            val line = lines.indexOfLast { it.startMs <= pos }
            val words = lines.getOrNull(line)?.words
            val word = words?.indexOfLast { it.startMs <= pos } ?: -1
            current = line
            currentWord = word
            val nextLineStart = lines.getOrNull(line + 1)?.startMs ?: Long.MAX_VALUE
            val nextWordStart = words?.getOrNull(word + 1)?.startMs ?: Long.MAX_VALUE
            val next = minOf(nextLineStart, nextWordStart)
            val change = snapshotFlow { Playback.tick to Playback.pending }.filter { it != seen }
            if (!Playback.player.isPlaying || pos < 0 || next == Long.MAX_VALUE) {
                change.first()
            } else {
                val waitMs = ((next - pos) / Playback.player.speed).toLong().coerceAtLeast(1)
                withTimeoutOrNull(waitMs) { change.first() }
            }
        }
    }
    val state = remember(lines) { LazyListState(firstVisibleItemIndex = if (startLine > 2) startLine - 2 else 0) }
    val showOriginal = Store.bool("lyrics.layer.original", true)
    val showLatin = Store.bool("lyrics.layer.latin", true)
    val ruby = Store.lyricsRuby
    // Whether the song is Japanese, for the "scripts you read" check: kana in any line, as romanization decides it.
    val sib = remember(lines) { lines.map { it.text }.filter { t -> t.any(Romanizer::isKana) }.take(1) }
    // The word being corrected and its romaji in this line.
    var fixWord by remember { mutableStateOf<Pair<String, String>?>(null) }
    // At the start line the list is already in place, so this first run doesn't move it.
    LaunchedEffect(current) { if (current > 2) state.animateScrollToItem(current - 2) }
    // Lines scroll out below the chips' strip (Translate, the sleep timer), so a chip never covers one.
    Box {
        LazyColumn(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = CHIP_STRIP), state = state) {
            itemsIndexed(lines) { i, line ->
                val active = i == current
                // The other lines keep about 5:1 contrast. The words still to come in the current line have the same
                // opacity (WordsText), so the line being sung is never lighter than its neighbours.
                val alpha = if (!synced || active) 1f else UPCOMING
                Column(
                    Modifier
                        .fillMaxWidth()
                        .dim(alpha)
                        .pointerInput(line) {
                            detectTapGestures(onTap = { if (synced) Playback.player.seekTo(line.startMs) })
                        }
                        .padding(vertical = 8.dp),
                ) {
                    val wordIndex = if (active) currentWord else -1
                    // Every line has the same weight, because bolding the active line would widen it and re-wrap long
                    // lines mid-song.
                    val big = MaterialTheme.typography.titleLarge.copy(lineBreak = LYRIC_BREAK)
                    val seek = { if (synced) Playback.player.seekTo(line.startMs) }
                    // Karaoke lines show their timed words; other lines their words as romanization splits them.
                    val karaoke = line.words.isNotEmpty()
                    val units = if (karaoke) line.words.map { it.text } else line.pieces
                    val unitLatin = if (karaoke) line.wordLatin else line.pieceLatin
                    val latinShown = showLatin &&
                        line.latin.isNotBlank() &&
                        line.latin != line.text &&
                        Romanizer.hasNonLatin(line.text) &&
                        !Store.reads(line.text, sib)
                    // A romaji word covers every syllable of its Japanese word but sits on the first one's slot (さよなら:
                    // "sayonara" on さ, nothing on よなら), so it stays lit while the later syllables play.
                    val latinIndex = (wordIndex downTo 0).firstOrNull { k -> unitLatin.getOrNull(k)?.isNotBlank() == true } ?: wordIndex
                    val fix = { unit: Int -> fixWord = wordAt(line, units, unit) ?: fixWord }
                    if (ruby && showOriginal && latinShown && line.pieces.size == line.pieceLatin.size) {
                        // Ruby sets whole words, not karaoke syllables (which would scatter 二|人 apart), so the word holding
                        // the syllable being sung is lit.
                        val piece = if (karaoke && wordIndex >= 0) pieceAt(line, units, wordIndex) else wordIndex
                        RubyLine(line.pieces, line.pieceLatin, piece, big, onTap = seek) { pieceIndex ->
                            fixWord = wordAt(line, line.pieces, pieceIndex) ?: fixWord
                        }
                    } else {
                        // A line with no romanization shown (Latin, or a script the user reads) shows its original, because
                        // it is the only text there is.
                        if ((showOriginal || !latinShown) && line.text.isNotBlank()) WordsText(units, line.text, wordIndex, big, seek, fix)
                        if (latinShown) WordsText(unitLatin, line.latin, latinIndex, MaterialTheme.typography.bodyLarge, seek, fix)
                    }
                    val translation = line.translation
                    if (!translation.isNullOrBlank()) {
                        Text(translation, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                val small = MaterialTheme.typography.labelSmall
                val c = MaterialTheme.colorScheme.onSurfaceVariant
                Column(Modifier.padding(top = 16.dp, bottom = 24.dp)) {
                    Text("Lyrics: ${shown.source}", style = small, color = c)
                    val translationSource = shown.translationSource
                    if (translationSource != null) Text("Translation: $translationSource", style = small, color = c)
                }
            }
        }
        // Lines fade out at the bottom edge, and under the chips' strip at the top, as the backdrop does.
        val bg = MaterialTheme.colorScheme.surface
        val fade = 24.dp
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = CHIP_STRIP)
                .fillMaxWidth()
                .height(fade)
                .background(Brush.verticalGradient(listOf(bg, Color.Transparent))),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(fade)
                .background(Brush.verticalGradient(listOf(Color.Transparent, bg))),
        )
        val hasText = lines.any { it.text.isNotBlank() }
        val lang = Store.translationLanguages().first()
        val translated = found.translations.any { it.language == lang }
        TranslateChip(found, hasText && showTranslation, translated, translationHidden, { translationHidden = it }, Modifier.align(Alignment.TopEnd))
    }
    val fixing = fixWord
    if (fixing != null && songId != null) {
        WordDialog(fixing.first, fixing.second, songId) { fixWord = null }
    }
}

/**
 * The word (as [Romanizer.pieces] splits the line: whole dictionary words) under display unit [unit], with its romaji
 * in this line. Units are karaoke parts or the pieces themselves; a karaoke part inside a word (し of 忙|し|い) gives
 * the whole word. Null on a blank unit.
 */
private fun wordAt(line: Line, units: List<String>, unit: Int): Pair<String, String>? {
    val k = pieceAt(line, units, unit)
    val p = line.pieces.getOrNull(k)?.trim()
    if (p.isNullOrEmpty() || units[unit].isBlank()) return null
    return p to line.pieceLatin.getOrElse(k) { "" }.trim()
}

/** The index of the piece holding display unit [unit], or -1. */
private fun pieceAt(line: Line, units: List<String>, unit: Int): Int {
    val u = units.getOrNull(unit) ?: return -1
    val before = units.take(unit).sumOf { it.length }
    val leadingSpaces = u.length - u.trimStart().length
    // Karaoke parts join into the line plus, at most, the spaces it was trimmed of.
    val lineStart = units.joinToString("").indexOf(line.text).coerceAtLeast(0)
    val at = before + leadingSpaces - lineStart
    var start = 0
    line.pieces.forEachIndexed { k, p ->
        if (at < start + p.length) return k
        start += p.length
    }
    return -1
}

/**
 * Ruby layout: each word's romaji sits right above it, so the eye never has to match the two lines up. Karaoke
 * lights the current word in both, in the accent colour, as the lines layout does. Words wrap as wholes. Without
 * [onTap] the words take no touches, so a row around them stays clickable.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun RubyLine(
    units: List<String>,
    latin: List<String>,
    current: Int,
    style: androidx.compose.ui.text.TextStyle,
    latinStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
    center: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    onTap: (() -> Unit)? = null,
    onLongPress: (Int) -> Unit = {},
) {
    val on = MaterialTheme.colorScheme.onSurface
    val hi = MaterialTheme.colorScheme.primary
    fun color(i: Int, base: Color) = when {
        current < 0 || i < current -> base
        i == current -> hi
        else -> on.copy(alpha = UPCOMING)
    }
    val latest by rememberUpdatedState(onTap to onLongPress)
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp, if (center) Alignment.CenterHorizontally else Alignment.Start),
        maxLines = maxLines,
    ) {
        units.forEachIndexed { i, u ->
            if (u.isBlank()) return@forEachIndexed
            val taps = if (onTap == null) {
                Modifier
            } else {
                Modifier.pointerInput(i) {
                    detectTapGestures(
                        onTap = { latest.first?.invoke() },
                        onLongPress = { latest.second(i) },
                    )
                }
            }
            Column(taps, horizontalAlignment = Alignment.CenterHorizontally) {
                // An empty slot (punctuation, a word already in Latin letters) keeps the words on one baseline.
                val romaji = if (Romanizer.hasNonLatin(u)) latin[i].trim() else ""
                Text(romaji.ifEmpty { " " }, style = latinStyle, color = color(i, latinStyle.color.takeOrElse { on }))
                Text(u.trim(), style = style, color = color(i, style.color.takeOrElse { on }))
            }
        }
    }
}

/**
 * A lyric layer. In karaoke mode (current >= 0) sung words are full colour, the current word is in the accent colour,
 * and words still to come are dimmed as much as the lines that aren't being sung. Long-pressing a word offers to fix its reading.
 */
@Composable
private fun WordsText(
    words: List<String>,
    whole: String,
    current: Int,
    style: androidx.compose.ui.text.TextStyle,
    onTap: () -> Unit,
    onLongPressUnit: (Int) -> Unit,
) {
    val hi = MaterialTheme.colorScheme.primary
    val sung = SpanStyle(color = MaterialTheme.colorScheme.onSurface)
    val now = SpanStyle(color = hi)
    val coming = SpanStyle(color = MaterialTheme.colorScheme.onSurface.copy(alpha = UPCOMING))
    val ranges = ArrayList<IntRange>()
    val text: AnnotatedString = if (words.isEmpty()) {
        AnnotatedString(whole)
    } else {
        buildAnnotatedString {
            words.forEachIndexed { i, w ->
                val start = length
                val st = when {
                    current < 0 -> null
                    i < current -> sung
                    i == current -> now
                    else -> coming
                }
                if (st == null) append(w) else withStyle(st) { append(w) }
                ranges.add(start until length)
            }
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    // The handler reads the latest values instead of restarting with each karaoke step. Keyed on the styled text, it
    // would drop a tap or long press on the line being sung whenever the next word lit up.
    val latest by rememberUpdatedState(Triple(ranges, onTap, onLongPressUnit))
    Text(
        text,
        style = style.copy(lineBreak = LYRIC_BREAK),
        onTextLayout = { layout = it },
        modifier = Modifier.pointerInput(whole) {
            detectTapGestures(
                onTap = { latest.second() },
                onLongPress = { o ->
                    val offset = layout?.getOffsetForPosition(o) ?: return@detectTapGestures
                    val (rs, _, press) = latest
                    // Past the end of a unit (the gap after a line's last word) counts as the unit before.
                    var unit = rs.indexOfFirst { range -> offset in range }
                    if (unit < 0) unit = rs.indexOfLast { range -> range.first <= offset }
                    if (unit >= 0) press(unit)
                },
            )
        },
    )
}

/**
 * The top of the lyrics, comments and queue, where the Translate and sleep chips sit: a chip's 48 dp touch target, or
 * more when a large font makes the chip taller.
 */
internal val CHIP_STRIP: androidx.compose.ui.unit.Dp
    @Composable get() {
        val chip = with(LocalDensity.current) { MaterialTheme.typography.labelLarge.lineHeight.toDp() } + 12.dp
        return maxOf(48.dp, chip)
    }

@Composable
fun Centered(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center)
    }
}

/** The like button's icon: a thumb, or a star on a source that stars. */
fun likeIcon(liked: Boolean): ImageVector = when {
    backend.stars -> if (liked) Icons.Filled.Star else StarOutlineIcon
    liked -> Icons.Filled.ThumbUp
    else -> Icons.Outlined.ThumbUp
}

/** A speech-bubble-like lyrics icon; material-icons-core has none. */
val LyricsIcon: ImageVector by lazy {
    ImageVector.Builder("lyrics", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            androidx.compose.ui.graphics.vector.PathParser().parsePathString(
                "M4,4h16a2,2 0,0 1,2 2v10a2,2 0,0 1,-2 2H8l-4,4V6a2,2 0,0 1,2,-2zM7,8v2h10V8zM7,12v2h7v-2z",
            ).toNodes(),
            fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black),
        )
    }.build()
}

/** Two speech bubbles (Material's "forum"), apart from the lyrics icon's single one. */
val CommentsIcon: ImageVector by lazy {
    ImageVector.Builder("comments", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            androidx.compose.ui.graphics.vector.PathParser().parsePathString(
                "M21,6h-2v9H6v2c0,0.55 0.45,1 1,1h11l4,4V7c0,-0.55 -0.45,-1 -1,-1zM17,12V3c0,-0.55 -0.45,-1 -1,-1H3c-0.55,0 -1,0.45 -1,1v14l4,-4h10c0.55,0 1,-0.45 1,-1z",
            ).toNodes(),
            fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black),
        )
    }.build()
}

private val LYRIC_BREAK = lyricBreak

/** The opacity of lyrics not being sung: other lines, and the current line's words still to come. About 5:1 contrast. */
private const val UPCOMING = 0.65f
