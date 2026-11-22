package com.zakius.caravanserai

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.foundation.layout.width
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

/** Holds the seek-bar drag preview position so scrubbing only recomposes SeekBar and SeekPreviewOverlay. */
private class SeekPreviewState {
    var ms by mutableLongStateOf(-1L)
}

/**
 * The player. Only the area above the bottom block changes (song, lyrics, queue, comments). The bottom block has fixed
 * heights and fixed slots, so no control ever moves; an unavailable control is dimmed in place.
 *
 * State reads are split across child composables so toggling play/pause, scrubbing, or switching views never recomposes
 * unrelated parts of the player.
 */
@Composable
fun PlayerScreen() {
    PlayerBackHandler()
    var menuOpen by remember { mutableStateOf(false) }
    var sleepOpen by remember { mutableStateOf(false) }
    val seekPreview = remember { SeekPreviewState() }

    // Landscape (a phone on its side, the MatePad): the song, lyrics or queue on the left, the same bottom block on the
    // right. Otherwise one column; on a wide portrait screen the block keeps a phone's width, so the thumb reaches it.
    val screen = screenSizeDp()
    if (screen.width > screen.height) {
        Row(Modifier.fillMaxSize()) {
            PlayerUpperDeck(seekPreview, { sleepOpen = true }, Modifier.weight(1f).fillMaxHeight())
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                PlayerControlsBlock(
                    seekPreview,
                    onOpenMenu = { menuOpen = true },
                    onOpenSleep = { sleepOpen = true },
                    modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                )
            }
        }
    } else {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            PlayerUpperDeck(seekPreview, { sleepOpen = true }, Modifier.weight(1f).fillMaxWidth())
            PlayerControlsBlock(
                seekPreview,
                onOpenMenu = { menuOpen = true },
                onOpenSleep = { sleepOpen = true },
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
            )
        }
    }
    if (menuOpen) {
        val entry = Playback.currentEntryState
        if (entry != null) SongMenu(entry.song, queueIndex = null) { menuOpen = false } else menuOpen = false
    }
    if (sleepOpen) SleepDialog { sleepOpen = false }
}

@Composable
private fun PlayerBackHandler() {
    // Back closes the lyrics, queue or comments view first, then leaves the player as usual.
    BackHandler(enabled = Nav.playerView != "song" && LocalTabShown.current) { Nav.playerView = "song" }
}

@Composable
private fun PlayerUpperDeck(seekPreview: SeekPreviewState, onOpenSleep: () -> Unit, modifier: Modifier) {
    val view = Nav.playerView
    val opening = Playback.opening
    val shownSong = Playback.shownSong
    val loadedLyrics = Playback.lyrics
    val lyricsState = if (loadedLyrics != null && loadedLyrics.songId == shownSong?.id) loadedLyrics else null
    val lyrics = lyricsState?.lyrics
    val hasLyrics = lyrics != null && lyrics.lines.isNotEmpty() && opening == null
    LaunchedEffect(view, hasLyrics, lyrics) {
        if (view == "lyrics" && !hasLyrics && lyrics != null) Nav.playerView = "song"
    }

    val visitedViews = remember { androidx.compose.runtime.mutableStateListOf(view) }
    if (view !in visitedViews) visitedViews += view
    // All views (including comments, which defers network fetch until shown or pressed) are built ahead into
    // hardware layers so every view switch flips only RenderNode translation/zIndex.
    LaunchedEffect(Unit) { buildAhead(visitedViews, listOf("song", "queue", "lyrics", "comments")) }
    val tabShown = LocalTabShown.current

    Box(modifier.clipToBounds()) {
        for (v in visitedViews) {
            androidx.compose.runtime.key(v) {
                val active = v == view && opening == null
                DeckLayer(active) {
                    androidx.compose.runtime.CompositionLocalProvider(LocalTabShown provides (tabShown && active)) {
                        when (v) {
                            "lyrics" -> LyricsPanel()
                            "queue" -> QueueView()
                            "comments" -> backend.Comments()
                            else -> SongView()
                        }
                    }
                }
            }
        }
        if (opening != null) OpeningPage(opening.first, opening.second)
        BanBanner(Modifier.align(Alignment.BottomCenter))
        // Top left, clear of Translate on the right even with a large font; the lists start below the chips.
        SleepChip(Modifier.align(Alignment.TopStart), onClick = onOpenSleep)
        SeekPreviewOverlay(seekPreview, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun LyricsPanel() {
    val song = Playback.shownSong
    val loadedLyrics = Playback.lyrics
    val lyricsState = if (loadedLyrics != null && loadedLyrics.songId == song?.id) loadedLyrics else null
    val lyrics = lyricsState?.lyrics
    LyricsBackdrop(song?.thumb)
    if (lyricsState != null && lyrics != null) {
        LyricsView(lyricsState, lyrics, song?.id)
    } else {
        Loading("Looking for lyrics…")
    }
}

@Composable
private fun BanBanner(modifier: Modifier) {
    val lastBan = Playback.lastBan ?: return
    val song = lastBan.first
    Surface(
        modifier
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

@Composable
private fun SeekPreviewOverlay(seekPreview: SeekPreviewState, modifier: Modifier) {
    val ms = seekPreview.ms
    if (ms < 0) return
    val loadedLyrics = Playback.lyrics
    val lyrics = if (loadedLyrics != null && loadedLyrics.songId == Playback.shownSong?.id) loadedLyrics.lyrics else null
    val line = if (lyrics != null && lyrics.synced) lyrics.lines.lastOrNull { it.startMs <= ms } else null
    if (line != null && line.text.isNotBlank()) SeekPreview(line, modifier)
}

@Composable
private fun PlayerControlsBlock(
    seekPreview: SeekPreviewState,
    onOpenMenu: () -> Unit,
    onOpenSleep: () -> Unit,
    modifier: Modifier,
) {
    FixedRowsFont {
        Column(modifier) {
            SeekBar { seekPreview.ms = it }
            ActionRow(onOpenMenu)
            PlaybackRow(onOpenSleep)
        }
    }
}

@Composable
private fun ActionRow(onOpenMenu: () -> Unit) {
    val view = Nav.playerView
    val entry = Playback.currentEntryState
    val opening = Playback.opening
    val shownSong = Playback.shownSong
    val loadedLyrics = Playback.lyrics
    val lyrics = if (loadedLyrics != null && loadedLyrics.songId == shownSong?.id) loadedLyrics.lyrics else null
    val hasLyrics = lyrics != null && lyrics.lines.isNotEmpty() && opening == null
    val liked = entry?.song?.id in Playback.liked
    val haptic = LocalHapticFeedback.current
    // More (the song menu, where Ban lives) comes first and Like last, under the thumb. Each slot is a
    // sixth of the row wide whatever the source offers, and the slots are centred together, so a source
    // without Comments has its buttons no bigger and no further apart. Left-handed mode mirrors the row;
    // otherwise slots never move.
    Row(
        Modifier.fillMaxWidth().height(44.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val slots: List<(@Composable RowScope.() -> Unit)?> = listOf(
            {
                Slot(rememberVectorPainter(Icons.Default.MoreVert), "More", entry != null, label = "More") {
                    onOpenMenu()
                }
            },
            // A source without comments has no slot for them.
            if (!backend.hasComments) {
                null
            } else {
                {
                    Slot(
                        rememberVectorPainter(CommentsIcon),
                        "Comments",
                        entry != null,
                        view == "comments",
                        label = "Comments",
                        state = if (view == "comments") "shown" else null,
                        onPress = {
                            if (view != "comments") Playback.shownSong?.id?.let { backend.prefetchComments(it) }
                        },
                    ) {
                        Nav.playerView = if (view == "comments") "song" else "comments"
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
                    whyOff = when {
                        lyrics == null -> "Still looking for lyrics"
                        !Playback.online -> "No connection: lyrics are searched again when it's back"
                        else -> "No lyrics found for this song"
                    },
                ) {
                    Nav.playerView = if (view == "lyrics") "song" else "lyrics"
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
                    Nav.playerView = if (view == "queue") "song" else "queue"
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
                    whyOff = if (!backend.canLike) "Sign in to ${backend.name} to ${likeWord.lowercase()} songs" else null,
                ) {
                    haptic.performHapticFeedback(if (liked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                    Playback.toggleLike()
                }
            },
        )
        val shown = slots.filterNotNull()
        val ordered = if (Store.bool("ui.leftHanded", false)) shown.reversed() else shown
        Row(Modifier.fillMaxWidth(ordered.size / 6f), verticalAlignment = Alignment.CenterVertically) {
            for (slot in ordered) slot()
        }
    }
}

@Composable
private fun PlaybackRow(onOpenSleep: () -> Unit) {
    val entry = Playback.currentEntryState
    val shuffled = Playback.shuffledState
    val showPlay = Playback.showPlayButton
    val hasNext = Playback.hasNextState
    val repeatMode = Playback.repeatModeState
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        Slot(
            painterResource(R.drawable.shuffle),
            "Shuffle",
            entry != null,
            shuffled,
            iconSize = 26,
            state = if (shuffled) "on" else "off",
        ) {
            Playback.toggleShuffle()
        }
        Slot(painterResource(R.drawable.previous), "Previous", entry != null, iconSize = 30) { Playback.previous() }
        // A long press opens the sleep timer: a hidden extra on a button that's there anyway, so the row
        // stays as it is.
        Slot(
            painterResource(if (showPlay) R.drawable.play else R.drawable.pause),
            if (showPlay) "Play" else "Pause",
            entry != null,
            iconSize = 44,
            onLongClick = onOpenSleep,
            longClickLabel = "Sleep timer",
        ) {
            Playback.togglePlay()
        }
        Slot(painterResource(R.drawable.next), "Next", hasNext, iconSize = 30) { Playback.next() }
        val repeatIcon = when (repeatMode) {
            AudioPlayer.REPEAT_MODE_ONE -> R.drawable.repeat_one
            AudioPlayer.REPEAT_MODE_ALL -> R.drawable.repeat
            // Off is shown as muted (see Slot), not as another icon.
            else -> R.drawable.repeat
        }
        val repeatState = when (repeatMode) {
            AudioPlayer.REPEAT_MODE_ONE -> "this song"
            AudioPlayer.REPEAT_MODE_ALL -> "all"
            else -> "off"
        }
        Slot(
            painterResource(repeatIcon),
            "Repeat",
            entry != null,
            repeatMode != AudioPlayer.REPEAT_MODE_OFF,
            iconSize = 26,
            state = repeatState,
        ) {
            Playback.cycleRepeat()
        }
    }
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
    val edgeFade = remember(bg) {
        Brush.verticalGradient(0f to bg, 0.15f to Color.Transparent, 0.85f to Color.Transparent, 1f to bg)
    }
    Box(Modifier.fillMaxSize().background(edgeFade))
}

/** While the sleep timer runs: how long is left, over the top of the upper area. Tap: change or turn it off. */
@Composable
private fun SleepChip(modifier: Modifier, onClick: () -> Unit) {
    val afterSong = Playback.sleepAfterSong
    val sleepAt = Playback.sleepAt
    if (!afterSong && sleepAt == 0L) return
    // Only subscribe to the half-second clock while a timed sleep countdown is actually running and visible.
    if (sleepAt != 0L && LocalTabShown.current) Playback.clock
    val text = if (afterSong) {
        "Sleep when this song ends"
    } else {
        val minutes = ((sleepAt - elapsedRealtime()) / 60_000 + 1).coerceAtLeast(1)
        "Sleep in $minutes min"
    }
    // One line, at most about 60% of a phone's width: with a large font it ends in "…" rather than reaching Translate.
    Chip(text, enabled = true, modifier.widthIn(max = 256.dp), onClick = onClick)
}

/**
 * A chip over the top of the upper area (the sleep timer, Translate): a pill on the content's 16 dp edge, in a 48 dp
 * tall touch target that fills [CHIP_STRIP].
 */
@Composable
internal fun Chip(text: String, enabled: Boolean, modifier: Modifier, selected: Boolean = false, onClick: () -> Unit) {
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
                .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
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

/**
 * One equal-width control slot. Disabled slots keep their place and are dimmed; a tap on one with [whyOff] says why it
 * is off.
 */
@Composable
private fun RowScope.Slot(
    icon: Painter, description: String, enabled: Boolean, selected: Boolean = false, label: String? = null, iconSize: Int = 22,
    state: String? = null, onLongClick: (() -> Unit)? = null, longClickLabel: String? = null, whyOff: String? = null,
    onPress: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    // A switch without a label (Shuffle, Repeat) gets the pill when on. When off it is muted, but less than an
    // unavailable control (0.3).
    val offAlpha = if (state != null && label == null) 0.45f else 1f
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = offAlpha)
    // A rounded background only when selected, not a rounded clip on every slot: one GPU program less. It is 64 dp
    // wide in both rows, so the five-slot row's pill is no wider than the six-slot row's.
    val selectedBackground = if (selected) {
        Modifier.background(MaterialTheme.colorScheme.secondaryContainer, SlotSelectedShape)
    } else {
        Modifier
    }
    val latestPress by rememberUpdatedState(onPress)
    val pressMod = if (onPress == null || !enabled) {
        Modifier
    } else {
        Modifier.pointerInput(description) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                latestPress?.invoke()
            }
        }
    }
    Box(
        Modifier
            .weight(1f)
            .widthIn(min = 48.dp)
            .fillMaxHeight()
            .padding(horizontal = 2.dp)
            .then(pressMod)
            .combinedClickable(
                enabled = enabled || whyOff != null,
                onClickLabel = description,
                role = Role.Button,
                onLongClick = if (enabled) onLongClick else null,
                onLongClickLabel = longClickLabel,
                onClick = if (enabled) onClick else ({ Why.say(whyOff!!) }),
            )
            .semantics {
                val shownState = if (enabled) state else "unavailable"
                if (shownState != null) stateDescription = shownState
            }
            .dim(if (enabled) 1f else 0.3f),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxHeight().widthIn(max = 64.dp).fillMaxWidth().then(selectedBackground))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // With a label, the icon has no description, because the label is read instead.
            Icon(icon, if (label != null) null else description, Modifier.size(iconSize.dp), tint = color)
            // Shrinks to fit rather than losing letters: with a large system font "Comments" is wider than its slot.
            if (label != null) {
                androidx.compose.foundation.text.BasicText(
                    label,
                    style = androidx.compose.ui.text.TextStyle(color = color, fontSize = 10.sp, lineHeight = 12.sp),
                    maxLines = 1,
                    softWrap = false,
                    autoSize = if (LocalDensity.current.fontScale > 1.05f) {
                        androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 7.sp, maxFontSize = 10.sp)
                    } else {
                        null
                    },
                )
            }
        }
    }
}

private val SlotSelectedShape = RoundedCornerShape(12.dp)

@Composable
private fun SeekBar(onPreview: (Long) -> Unit) {
    // A hidden bar doesn't follow the clock, because redrawing the window twice a second wakes the GPU each time.
    // Shown again, reading LocalTabShown brings it up to date.
    if (LocalTabShown.current) Playback.clock
    val p = Playback.player
    val haptic = LocalHapticFeedback.current
    val duration = Playback.durationMsState
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    var snappedTo by remember { mutableLongStateOf(-1L) }
    var width by remember { mutableIntStateOf(1) }
    // Near a timed lyric line's start (12 dp of travel), a drag snaps to it with a tick, so a seek starts on a line
    // instead of a word into it.
    val lyricsState = Playback.lyrics
    val shownLyrics = if (lyricsState != null && lyricsState.songId == Playback.shownSong?.id) lyricsState.lyrics else null
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
    val s = Playback.shownSong
    if (s == null) {
        if (!Playback.restoring) Centered("Nothing playing. Pick a song in Home, Search or Library.")
        return
    }
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var dx by remember { mutableFloatStateOf(0f) }
    var dy by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(1) }
    val at = Playback.shownIndexState
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
        LikePopOverlay(pop, Modifier.align(Alignment.Center))
    }
}

@Composable
private fun LikePopOverlay(pop: Animatable<Float, *>, modifier: Modifier) {
    val v = pop.value
    if (v <= 0f) return
    Icon(
        likeIcon(true),
        null,
        modifier
            .size(96.dp)
            .graphicsLayer {
                alpha = v
                scaleX = 1.4f - 0.4f * v
                scaleY = scaleX
            },
        tint = MaterialTheme.colorScheme.primary,
    )
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
    // The names sit at the bottom, next to the controls, and the art is centred in the space above them, so a tall
    // screen's spare height falls evenly around the art rather than in one band.
    Column(
        modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Until the large art arrives, the list's thumbnail of this song (row or card size) stands in, if it's in memory.
        val density = LocalDensity.current
        val placeholder = remember(s.thumb, density) {
            listOf(48.dp, 56.dp, 128.dp).firstNotNullOfOrNull { d ->
                val px = with(density) { d.roundToPx() }
                val url = thumbAt(s.thumb, px)
                if (url == null) null else Images.peek(url, px)
            }
        }
        val thumb = s.thumb
        val big = remember(thumb) { if (thumb == null) null else bigThumb(thumb) }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            ThumbPx(
                big,
                BIG_PX,
                Modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                placeholder = placeholder,
            )
        }
        Spacer(Modifier.height(16.dp))
        // Tapping the title opens its album, the artist their page (as More → Go to album / artist). Long-pressing either
        // opens the correction dialog, the same as More → Correct romanization.
        var fixing by remember { mutableStateOf(false) }
        fun Modifier.goTo(kind: String, id: String?): Modifier {
            val clickLabel = if (id != null) "Go to $kind" else null
            val longClickLabel = if (s.romanizable) "Correct romanization" else null
            var onLongClick: (() -> Unit)? = null
            if (s.romanizable) onLongClick = { fixing = true }
            val prefetch = if (id == null) {
                Modifier
            } else {
                Modifier.pointerInput(kind, id) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        PageCache.prefetchDetail(Page(kind, id), thumb = s.thumb)
                    }
                }
            }
            return then(prefetch).combinedClickable(
                onClickLabel = clickLabel,
                onLongClickLabel = longClickLabel,
                onLongClick = onLongClick,
            ) {
                if (id != null) Nav.open(Page(kind, id))
            }
        }
        // The names' block keeps the height of the longest it can be (a two-line title with its original, the artist with
        // theirs, the album), so the art above stays put from song to song. It follows the font up to 1.3×, like the
        // controls under it, so a large font doesn't shrink the art to a thumbnail.
        FixedRowsFont {
        val title = MaterialTheme.typography.headlineSmall
        val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
        val artistStyle = remember(onSurfaceVariant) {
            MaterialTheme.typography.titleMedium.copy(
                fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
                color = onSurfaceVariant,
            )
        }
        val small = MaterialTheme.typography.bodySmall
        val localDensity = LocalDensity.current
        val namesHeight = remember(localDensity) {
            with(localDensity) {
                (title.fontSize * 1.25f * 2).toDp() + small.lineHeight.toDp() * 3 + (artistStyle.fontSize * 1.25f).toDp() + 4.dp
            }
        }
        // Bottom-aligned in it, so the names always sit against the seek bar.
        Column(Modifier.heightIn(min = namesHeight), Arrangement.Bottom, Alignment.CenterHorizontally) {
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
                    color = onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        }
        if (fixing) CorrectDialog(s) { fixing = false }
        if (current && Playback.waitingForNetworkState) {
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
                    if (Playback.hasNextState) {
                        TextButton(onClick = { Playback.next() }) { Text("Play the next song") }
                    }
                }
            }
        }
    }
}

private const val RADIO_LOADING = -2

/** A spinner where a song's cover would be, with what is loading beside it. */
@Composable
private fun LoadingRow(text: String) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        Spacer(Modifier.width(12.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun QueueView() {
    val all = Playback.queueEntries
    val cur = Playback.currentIndexState
    val cachedIds = Playback.cachedSongIds
    val loading = Playback.radioLoading
    val stopAtEnd = Store.stopAtEnd
    var menuFor by remember { mutableStateOf<Int?>(null) }
    // The next songs are the likely taps here.
    val shown = LocalTabShown.current
    LaunchedEffect(shown, all, cur) {
        if (shown && cur in all.indices) Playback.prepare(all.drop(cur + 1).map { it.song })
    }
    // Each row is a header with -1, the radio's loading row with RADIO_LOADING, or null with an entry's index.
    val rows = remember(all, cur, loading, stopAtEnd) {
        val out = ArrayList<Pair<String?, Int>>(all.size + 4)
        if (cur in all.indices) {
            out.add("Now" to -1)
            out.add(null to cur)
        }
        val queued = ArrayList<Int>()
        val radio = ArrayList<Int>()
        for (i in (cur + 1).coerceAtLeast(0) until all.size) {
            if (all[i].section == "queued") queued.add(i) else if (all[i].section == "radio") radio.add(i)
        }
        if (queued.isNotEmpty()) {
            out.add("Up next" to -1)
            for (i in queued) out.add(null to i)
        }
        if (radio.isNotEmpty()) {
            out.add("Radio" to -1)
            for (i in radio) out.add(null to i)
        } else if (loading != null) {
            out.add("Radio" to -1)
            out.add("Finding songs like ${loading.title.latin}" to RADIO_LOADING)
        }
        if (queued.isEmpty() && radio.isEmpty() && loading == null) {
            val note = if (stopAtEnd) "Nothing next: autoplay is off" else "Nothing next yet"
            out.add(note to -1)
        }
        out
    }
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    val coverUrls = remember(all, cur) {
        if (cur + 1 in all.indices) all.subList(cur + 1, all.size).map { it.song.thumb } else emptyList()
    }
    PrefetchCovers(coverUrls, 48.dp, list)
    Box {
        // The strip is kept for the sleep timer's chip only while it runs.
        val sleeping = Playback.sleepAt != 0L || Playback.sleepAfterSong
        LazyColumn(Modifier.fillMaxSize().padding(top = if (sleeping) CHIP_STRIP else 0.dp), list) {
            itemsIndexed(
                rows,
                key = { rowIdx, (header, i) -> if (header != null) "h:$header:$i" else "s:${all[i].song.id}#$i:$rowIdx" },
                contentType = { _, (header, i) -> if (header == null) 0 else i },
            ) { _, (header, i) ->
                if (i == RADIO_LOADING) {
                    LoadingRow(header!!)
                } else if (header != null) {
                    Header(header)
                } else {
                    val e = all[i]
                    // "downloaded" means the whole song is on the phone, so it plays with no network.
                    val saved = if (e.song.id in cachedIds) "downloaded" else null
                    val extra = if (e.song.reason == null) saved else if (saved == null) e.song.reason else "${e.song.reason} · $saved"
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

/** Precomputed display metadata for one lyric line, so karaoke word ticks don't re-map words or re-check scripts. */
private class PreparedLine(
    val karaoke: Boolean,
    val units: List<String>,
    val unitLatin: List<String>,
    val latinShown: Boolean,
)

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
    val startLine = remember(lines) {
        if (synced && Playback.pending == null) {
            lines.indexOfLast { it.startMs <= Playback.player.currentPosition }
        } else {
            -1
        }
    }
    var current by remember(lines) { mutableIntStateOf(startLine) }
    var currentWord by remember(lines) { mutableIntStateOf(-1) }
    val state = remember(lines) { LazyListState(firstVisibleItemIndex = if (startLine > 2) startLine - 2 else 0) }
    // Paused while another tab or view is hidden; when shown again, snaps directly to the current line instead of
    // animating across the whole song from wherever it was built ahead.
    val onScreen = LocalTabShown.current
    var snappedLine by remember(lines) { mutableIntStateOf(startLine) }
    LaunchedEffect(lines, synced, onScreen) {
        if (!synced || !onScreen) return@LaunchedEffect
        var snapNext = true
        while (true) {
            val pos = if (Playback.pending != null) -1L else Playback.player.currentPosition
            val line = lines.indexOfLast { it.startMs <= pos }
            val words = lines.getOrNull(line)?.words
            val word = words?.indexOfLast { it.startMs <= pos } ?: -1
            if (snapNext) {
                snapNext = false
                snappedLine = line
                current = line
                currentWord = word
                val target = if (line > 2) line - 2 else 0
                if (state.firstVisibleItemIndex != target) state.scrollToItem(target)
            } else {
                current = line
                currentWord = word
            }
            val nextLineStart = lines.getOrNull(line + 1)?.startMs ?: Long.MAX_VALUE
            val nextWordStart = words?.getOrNull(word + 1)?.startMs ?: Long.MAX_VALUE
            val next = minOf(nextLineStart, nextWordStart)
            if (!Playback.player.isPlaying || pos < 0 || next == Long.MAX_VALUE) {
                Playback.playbackSignal.first()
            } else {
                val waitMs = ((next - pos) / Playback.player.speed).toLong().coerceAtLeast(1)
                withTimeoutOrNull(waitMs) { Playback.playbackSignal.first() }
            }
        }
    }
    val showOriginal = Store.bool("lyrics.layer.original", true)
    val showLatin = Store.bool("lyrics.layer.latin", true)
    val ruby = Store.lyricsRuby
    val readScripts = Store.readScripts
    val prepared = remember(lines, showLatin, readScripts) {
        val sib = lines.map { it.text }.filter { t -> t.any(Romanizer::isKana) }.take(1)
        lines.map { line ->
            val karaoke = line.words.isNotEmpty()
            val units = if (karaoke) line.words.map { it.text } else line.pieces
            val unitLatin = if (karaoke) line.wordLatin else line.pieceLatin
            val latinShown = showLatin &&
                line.latin.isNotBlank() &&
                line.latin != line.text &&
                Romanizer.hasNonLatin(line.text) &&
                !Store.reads(line.text, sib)
            PreparedLine(karaoke, units, unitLatin, latinShown)
        }
    }
    // The word being corrected and its romaji in this line.
    var fixWord by remember { mutableStateOf<Pair<String, String>?>(null) }
    // Smooth-scroll only on live line advances while visible (initial open already snapped above).
    LaunchedEffect(current) {
        if (onScreen && current != snappedLine && current > 2) state.animateScrollToItem(current - 2)
    }
    // Lines scroll out below the chips' strip (Translate, the sleep timer), so a chip never covers one.
    Box {
        // The first line starts below the top fade, so at rest no line is under it.
        LazyColumn(
            Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = CHIP_STRIP),
            state = state,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 24.dp),
        ) {
            itemsIndexed(lines, key = { i, _ -> i }) { i, line ->
                val active = i == current
                LyricLineRow(
                    line = line,
                    meta = prepared[i],
                    synced = synced,
                    active = active,
                    wordIndex = if (active) currentWord else -1,
                    showOriginal = showOriginal,
                    ruby = ruby,
                    onFix = { fixWord = it },
                )
            }
            item(key = "credits") {
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
        val topFade = remember(bg) { Brush.verticalGradient(listOf(bg, Color.Transparent)) }
        val bottomFade = remember(bg) { Brush.verticalGradient(listOf(Color.Transparent, bg)) }
        val fade = 24.dp
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = CHIP_STRIP)
                .fillMaxWidth()
                .height(fade)
                .background(topFade),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(fade)
                .background(bottomFade),
        )
        val hasText = remember(lines) { lines.any { it.text.isNotBlank() } }
        val lang = Store.translationLanguages().first()
        val translated = remember(found.translations, lang) { found.translations.any { it.language == lang } }
        TranslateChip(found, hasText && showTranslation, translated, translationHidden, { translationHidden = it }, Modifier.align(Alignment.TopEnd))
    }
    val fixing = fixWord
    if (fixing != null && songId != null) {
        WordDialog(fixing.first, fixing.second, songId) { fixWord = null }
    }
}

@Composable
private fun LyricLineRow(
    line: Line,
    meta: PreparedLine,
    synced: Boolean,
    active: Boolean,
    wordIndex: Int,
    showOriginal: Boolean,
    ruby: Boolean,
    onFix: (Pair<String, String>) -> Unit,
) {
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
        // A line timed as a whole (no word timing) is in the accent colour while it plays, as a karaoke
        // line's sung words are.
        val lineColor = if (active && synced && line.words.isEmpty()) MaterialTheme.colorScheme.primary else Color.Unspecified
        // Every line has the same weight, because bolding the active line would widen it and re-wrap long
        // lines mid-song.
        val big = MaterialTheme.typography.titleLarge.copy(lineBreak = LYRIC_BREAK, color = lineColor)
        val seek = { if (synced) Playback.player.seekTo(line.startMs) }
        // A romaji word covers every syllable of its Japanese word but sits on the first one's slot (さよなら:
        // "sayonara" on さ, nothing on よなら), so it stays lit while the later syllables play.
        val latinIndex = if (wordIndex < 0) {
            -1
        } else {
            (wordIndex downTo 0).firstOrNull { k -> meta.unitLatin.getOrNull(k)?.isNotBlank() == true } ?: wordIndex
        }
        val fix = { unit: Int -> wordAt(line, meta.units, unit)?.let(onFix) ?: Unit }
        if (ruby && showOriginal && meta.latinShown && line.pieces.size == line.pieceLatin.size) {
            // Ruby sets whole words, not karaoke syllables (which would scatter 二|人 apart), so the word holding
            // the syllable being sung is lit.
            val piece = if (meta.karaoke && wordIndex >= 0) pieceAt(line, meta.units, wordIndex) else wordIndex
            RubyLine(line.pieces, line.pieceLatin, piece, big, onTap = seek) { pieceIndex ->
                wordAt(line, line.pieces, pieceIndex)?.let(onFix)
            }
        } else {
            // A line with no romanization shown (Latin, or a script the user reads) shows its original, because
            // it is the only text there is.
            if ((showOriginal || !meta.latinShown) && line.text.isNotBlank()) WordsText(meta.units, line.text, wordIndex, big, seek, fix)
            if (meta.latinShown) WordsText(meta.unitLatin, line.latin, latinIndex, MaterialTheme.typography.bodyLarge.copy(color = lineColor), seek, fix)
        }
        val translation = line.translation
        if (!translation.isNullOrBlank()) {
            val translationColor = if (lineColor != Color.Unspecified || (active && synced)) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(translation, style = MaterialTheme.typography.bodyMedium, color = translationColor)
        }
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
 * fills the words sung so far in the accent colour, in both, as the lines layout does. Words wrap as wholes. Without
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
        current < 0 -> base
        i <= current -> hi
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
 * A lyric layer. In karaoke mode (current >= 0) the words sung so far and the current one fill in the accent colour,
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
    val onSurface = MaterialTheme.colorScheme.onSurface
    val base = remember(words, whole) {
        val rs = ArrayList<IntRange>(words.size)
        val text = if (words.isEmpty()) {
            AnnotatedString(whole)
        } else {
            buildAnnotatedString {
                for (w in words) {
                    val start = length
                    append(w)
                    rs.add(start until length)
                }
            }
        }
        text to rs
    }
    val text: AnnotatedString
    val ranges: List<IntRange> = base.second
    if (current < 0 || words.isEmpty()) {
        text = base.first
    } else {
        text = remember(words, current, hi, onSurface) {
            val sung = SpanStyle(color = hi)
            val coming = SpanStyle(color = onSurface.copy(alpha = UPCOMING))
            buildAnnotatedString {
                words.forEachIndexed { i, w ->
                    withStyle(if (i <= current) sung else coming) { append(w) }
                }
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

/** A message in the middle of an empty screen. Its lines are balanced, so no word is left alone on the last one. */
@Composable
fun Centered(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center, style = LocalTextStyle.current.copy(lineBreak = LineBreak.Heading))
    }
}

/** Loading, the same everywhere: a spinner in the middle, with what is loading under it when that helps. */
@Composable
fun Loading(text: String? = null) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        if (text != null) {
            Spacer(Modifier.height(12.dp))
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

/** A failure, the same everywhere: what failed, why and what to do, and Retry. */
@Composable
fun Failed(title: String, detail: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, textAlign = TextAlign.Center)
        Text(detail, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onRetry) { Text("Retry") }
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
