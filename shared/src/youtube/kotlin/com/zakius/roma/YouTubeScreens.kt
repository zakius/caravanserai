package com.zakius.roma

import com.metrolist.innertube.YouTube
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

// roma's screens that only YouTube Music has: the personal mixes on Home, the sign-in and its settings.

/** The platform's sign-in page (LoginScreen). */
val SIGN_IN = Page("source", "login")

/**
 * Always first on Home and never gone: the mixes as tiles (see [mixGrid]), with any found during the run in a row
 * below. When signed out or with no mixes, it holds a dimmed hint instead.
 */
@Composable
internal fun MixTiles(mixes: List<Item>?, read: Boolean) {
    @Composable
    fun Hint(text: String) = Text(
        text,
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).dim(0.5f),
        style = MaterialTheme.typography.bodyMedium,
    )
    // Signed out: the same hint and button as the Library tab's.
    if (!YouTubeMusic.signedIn) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Sign in to YouTube Music to see your mixes.", textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { Nav.open(SIGN_IN) }) { Text("Sign in") }
        }
        return
    }
    val width = screenSizeDp().width
    val list = mixes.orEmpty()
    fun play(m: Item) {
        Playback.playMix(m.id, title = Store.shown(m.title), thumb = m.thumb)
        Nav.tab = TAB_PLAYER
    }
    val gridFor = Home.gridFor
    val (cols, fit, side) = mixGrid(width, if (gridFor > 0) gridFor else list.size)
    // The mixes in the grid. Liked music is the last tile.
    val shown = minOf(fit - 1, list.size)
    val textStyle = MaterialTheme.typography.bodySmall
    @Composable
    fun Tile(title: String, locked: Boolean, onClick: () -> Unit, picture: @Composable () -> Unit) = Column(
        Modifier
            .width(side)
            .dim(if (locked) 0.35f else 1f)
            .clickable(enabled = !locked, onClick = onClick),
    ) {
        picture()
        Spacer(Modifier.height(4.dp))
        val density = androidx.compose.ui.platform.LocalDensity.current
        val threeLines = with(density) { (textStyle.lineHeight * 3).toDp() }
        Text(title, Modifier.height(threeLines), style = textStyle, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The null entry stands for the Liked music tile.
        val gridItems = list.take(shown) + listOf<Item?>(null)
        for (line in gridItems.chunked(cols)) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (m in line) {
                if (m == null) {
                    Tile(
                        "Liked music",
                        false,
                        {
                            Playback.playMix("LM", title = "Liked music")
                            Nav.tab = TAB_PLAYER
                        },
                    ) {
                        Box(
                            Modifier
                                .size(side)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.ThumbUp, null, Modifier.size(side / 3), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                } else {
                    Tile(Store.shown(m.title), m.id in Home.updating, { play(m) }) {
                        Thumb(shownThumb(m.thumb, side), side, Modifier.size(side).clip(RoundedCornerShape(8.dp)))
                    }
                }
            }
        }
    }
    when {
        mixes == null && !read -> {}
        mixes == null -> Hint("Looking for your mixes…")
        mixes.isEmpty() -> Hint("No mixes yet")
    }
    if (list.size > shown) {
        PrefetchCovers(list.drop(shown).map { it.thumb }, 128.dp)
        LazyRow {
            items(list.drop(shown)) { m ->
                Card(m) { play(m) }
            }
        }
    }
}

/** Called after signing in on the platform's sign-in page (LoginScreen). Keeps the account's cookie and its ids. */
fun signedIn(cookie: String, visitorData: String?, dataSyncId: String?) {
    YouTube.cookie = cookie
    if (visitorData != null) {
        YouTube.visitorData = visitorData
        Store.set("visitorData", visitorData)
    }
    if (dataSyncId != null) {
        val id = dataSyncId.substringBefore("||")
        YouTube.dataSyncId = id
        Store.set("dataSyncId", id)
    }
    Store.set("cookie", cookie)
    toast("Signed in")
    Nav.stacks[Nav.tab].clear()
    Playback.checkLike(Playback.current()?.song)
}

@Composable
internal fun YouTubeSettings() = LazyColumn(Modifier.fillMaxSize()) {
    item {
        if (YouTubeMusic.signedIn) {
            Row1("Signed in to YouTube Music", "Tap to sign out") {
                forgetSignIn()
                YouTube.cookie = null
                Store.set("cookie", null)
            }
        } else {
            Row1("Sign in to YouTube Music", "For your library and likes") { Nav.open(SIGN_IN) }
        }
    }
    item { Options("Shared links open", "share.site", "music", SITES) }
}

private val SITES = listOf("music" to "YouTube Music", "youtube" to "YouTube")
