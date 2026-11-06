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

// The Subsonic app's own screens: Home's tiles, the server's settings and the first-start server form.

/**
 * Home's pinned tiles, three to a row: the server's mixes (oasis only), then the starred songs and the whole library
 * at random.
 */
@Composable
internal fun LibraryTiles(mixes: List<Item>) {
    val (cols, _, side) = mixGrid(screenSizeDp().width, mixes.size)
    val tint = MaterialTheme.colorScheme.onSecondaryContainer
    @Composable
    fun Tile(title: String, id: String, picture: @Composable () -> Unit) = Column(
        Modifier
            .width(side)
            .clickable {
                Playback.playMix(id, title = title)
                Nav.tab = TAB_PLAYER
            },
    ) {
        picture()
        Spacer(Modifier.height(4.dp))
        Text(title, style = MaterialTheme.typography.bodySmall, maxLines = 3)
    }
    @Composable
    fun Drawn(icon: @Composable () -> Unit) = Box(
        Modifier
            .size(side)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        icon()
    }
    // Each tile is (title, id, the mix's card). Starred and Shuffle all have no card, so they are drawn.
    val mixTiles = mixes.map { mix -> Triple(Store.shown(mix.title), mix.id, mix) }
    val drawnTiles: List<Triple<String, String, Item?>> = listOf(
        Triple("Starred", SubsonicSource.STARRED, null),
        Triple("Shuffle all", SubsonicSource.SHUFFLE, null),
    )
    val tiles = mixTiles + drawnTiles
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (line in tiles.chunked(cols)) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for ((title, id, m) in line) Tile(title, id) {
                if (m != null) {
                    Thumb(shownThumb(m.thumb, side), side, Modifier.size(side).clip(RoundedCornerShape(8.dp)))
                } else {
                    Drawn {
                        if (id == SubsonicSource.STARRED) {
                            Icon(Icons.Filled.ThumbUp, null, Modifier.size(side / 3), tint = tint)
                        } else {
                            val shuffle = androidx.compose.ui.res.painterResource(R.drawable.shuffle)
                            Icon(shuffle, null, Modifier.size(side / 3), tint = tint)
                        }
                    }
                }
            }
        }
    }
}

/** The server: its address and sign-in, the library's sync, and scrobbling. */
@Composable
internal fun ServerSettings() {
    // Read through Store, so connecting redraws the page.
    val configured = Store.str("subsonic.url", "").isNotBlank()
    LazyColumn(Modifier.fillMaxSize()) {
        item { Header("Server") }
        item { ServerForm(if (!configured) "Connect" else "Save") {} }
        if (!configured) return@LazyColumn
        if (Store.str("subsonic.url", "").startsWith("http://")) item {
            Text(
                "Not encrypted: plain http sends your music and a token made from your password as they are. Fine on your own network only.",
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        item {
            // Reading changes redraws this row when the library changes.
            SubsonicSource.changes
            val l = SubsonicSource.library
            val state = when {
                SubsonicSource.syncing -> "Syncing the library…"
                SubsonicSource.syncError != null -> "The last sync failed: ${SubsonicSource.syncError}"
                l != null -> {
                    val format = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                    val syncedAt = format.format(java.util.Date(l.index.syncedAt))
                    "${l.songs.size} songs, ${l.albums.size} albums, ${l.artists.size} artists · synced $syncedAt"
                }
                else -> "Not synced yet"
            }
            Row1("Sync now", state, enabled = !SubsonicSource.syncing) { SubsonicSource.sync() }
        }
        item {
            val serverNote = if (!SubsonicSource.lastFmDefault) {
                "The server says it scrobbles to Last.fm itself, so this starts off"
            } else {
                "Turn this off if the server scrobbles to Last.fm itself"
            }
            Switch1(
                "Also scrobble to Last.fm from ${BuildConfig.APP_NAME}",
                "subsonic.lastfm",
                SubsonicSource.lastFmDefault,
                subtitle = "${BuildConfig.APP_NAME} always tells the server. " + serverNote,
            )
        }
    }
}

/** Asks for the server at first start, before the scripts question. */
@Composable
internal fun ServerSetup() {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text("Where is your music?", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Your Subsonic or OpenSubsonic server. You can change this in Settings.",
            Modifier.padding(top = 8.dp, bottom = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        ServerForm("Continue") {}
    }
}

/**
 * A Subsonic server's address, user and password. [action] checks them with the server (ping) before keeping them, then
 * runs [onConnected] and syncs the library. A different server or user drops the old one's library. An address without
 * a scheme is https. When that fails and the same address answers as Subsonic over plain http on the user's own
 * network, the form says so and connects over http only on a second tap.
 */
@Composable
internal fun ServerForm(action: String, onConnected: () -> Unit) {
    var url by rememberSaveable { mutableStateOf(Store.str("subsonic.url", "")) }
    var user by rememberSaveable { mutableStateOf(Store.str("subsonic.user", "")) }
    var password by rememberSaveable { mutableStateOf(Store.str("subsonic.password", "")) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // The http address found when https failed, until the user agrees to it or edits the address.
    var plain by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            url,
            { typed ->
                url = typed
                plain = null
            },
            Modifier.fillMaxWidth(),
            singleLine = true,
            label = "Server address",
            placeholder = "music.example.com or http://192.168.1.10:4533",
        )
        OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), singleLine = true, label = "User")
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), singleLine = true, label = "Password", password = true)
        Button(enabled = !busy && url.isNotBlank(), onClick = {
            busy = true
            status = "Connecting…"
            scope.launch {
                val typed = url.trim()
                val r = withContext(Dispatchers.IO) {
                    runCatching {
                        val agreed = plain
                        if (agreed != null) return@runCatching connect(agreed, user.trim(), password)
                        try {
                            connect(typed, user.trim(), password)
                        } catch (e: Exception) {
                            // Plain http is offered only for an address typed without a scheme, when https got no
                            // Subsonic answer and the server does answer over http. Null asks the user to agree.
                            if ("://" in typed || e is roma.core.SubsonicException && e.http == null) throw e
                            if (!subsonicOverHttp("http://$typed")) throw e
                            null
                        }
                    }
                }
                busy = false
                r.onSuccess { c ->
                    if (c == null) {
                        plain = "http://$typed"
                        status = "This server has no https. Over plain http, your music and a token made from your password cross the network unencrypted: fine on your own network, not elsewhere."
                        return@onSuccess
                    }
                    plain = null
                    Store.set(SubsonicSource.ROMANIZES, roma.core.SubsonicClient.ROMANIZATION in c.extensions)
                    Store.set(SubsonicSource.SERVER_SCROBBLES, c.serverScrobbles == true)
                    val other = c.url != Store.str("subsonic.url", "") || user.trim() != Store.str("subsonic.user", "")
                    Store.set("subsonic.url", c.url)
                    Store.set("subsonic.user", user.trim())
                    Store.set("subsonic.password", password)
                    url = c.url
                    if (other) SubsonicSource.forget()
                    status = null
                    onConnected()
                    SubsonicSource.sync()
                }.onFailure { status = serverError(it) }
            }
        }) { Text(if (plain != null) "Connect over http" else action) }
        val shownStatus = status
        if (shownStatus != null) Text(shownStatus, style = MaterialTheme.typography.bodySmall)
    }
}

/** A server that answered: its address with the scheme, its extensions, and whether it scrobbles to Last.fm itself. */
private class Connected(val url: String, val extensions: Map<String, List<Int>>, val serverScrobbles: Boolean?)

// Blocking. Plain http is allowed only to a server on the user's own network (SubsonicClient.localHost).
private fun connect(url: String, user: String, password: String): Connected {
    val client = roma.core.SubsonicClient(url, user, password)
    val uri = runCatching { java.net.URI(client.base) }.getOrNull()
    if (uri?.host == null || uri.scheme != "https" && uri.scheme != "http") {
        throw roma.core.SubsonicException(0, "That isn't a web address.")
    }
    if (uri.scheme == "http" && !roma.core.SubsonicClient.localHost(uri.host)) {
        throw roma.core.SubsonicException(0, "Plain http only reaches a server on your own network. Use https:// for this one.")
    }
    client.ping()
    return Connected(client.base, client.extensions(), client.scrobblingEnabled())
}

// Blocking. Checks whether [url] is a Subsonic server on the user's own network. It asks without the user's password,
// which mustn't cross the network unencrypted before the user agrees. Any Subsonic answer will do, a refusal included.
private fun subsonicOverHttp(url: String): Boolean {
    val local = runCatching {
        val host = java.net.URI(url).host
        host != null && roma.core.SubsonicClient.localHost(host)
    }.getOrDefault(false)
    if (!local) return false
    val error = runCatching { roma.core.SubsonicClient(url, "", "").ping() }.exceptionOrNull()
    return error == null || error is roma.core.SubsonicException && error.http == null
}

private fun serverError(t: Throwable): String {
    val causes = generateSequence(t) { it.cause }
    return when {
        t is roma.core.SubsonicException && t.code == 40 -> "Wrong user or password."
        t is roma.core.SubsonicException -> t.message ?: "The server refused."
        causes.any { e -> e is java.net.SocketTimeoutException } -> {
            "The server took too long to answer. Check the address, then try again."
        }
        causes.any { e -> e is java.net.UnknownHostException || e is java.net.ConnectException || e is java.net.NoRouteToHostException } -> {
            "Couldn't reach the server. Check the address and the network, then try again."
        }
        else -> plainError(t)
    }
}
