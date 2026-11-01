package com.zakius.roma

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import roma.core.Item
import roma.core.LyricsProvider
import roma.core.Scrobble
import roma.core.ScrobbleQueue
import roma.core.Song
import roma.core.SubsonicClient
import roma.core.SubsonicException
import roma.core.SubsonicIds
import roma.core.SubsonicIndex
import roma.core.SubsonicLyrics
import roma.core.item
import roma.core.LrcLib
import roma.core.matchDistance
import roma.core.song
import java.io.File

/** The Subsonic app's one source. */
val backend: Source = SubsonicSource

/**
 * A Subsonic or OpenSubsonic server (docs/subsonic.md). The whole library is synced into cache/subsonic and romanized
 * once, so pages and search read memory and work offline; only Home's play counts, playlists, stars, lyrics, radio and
 * the streams ask the server.
 */
object SubsonicSource : Source {
    /** Home's tiles: the starred songs, and the whole library at random. */
    const val STARRED = "s.~starred"
    const val SHUFFLE = "s.~shuffle"

    // One at a time, in order: a server change's deletion runs before the new server's sync.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val dir get() = File(Store.cache, "subsonic")

    // ---- The server, from the settings ----

    private var clientKey: String? = null
    private var cachedClient: SubsonicClient? = null

    /** Null until a server is set. */
    val client: SubsonicClient?
        @Synchronized get() {
            val url = Store.prefs.getString("subsonic.url", null)?.takeIf { it.isNotBlank() } ?: return null
            val user = Store.prefs.getString("subsonic.user", "") ?: ""
            val password = Store.prefs.getString("subsonic.password", "") ?: ""
            val key = "$url\n$user\n$password"
            if (key != clientKey) { clientKey = key; cachedClient = SubsonicClient(url, user, password) }
            return cachedClient
        }

    private fun need() = client ?: throw IllegalStateException("No server set. Add one in Settings → Music source.")

    override val name = "your server"
    // Subsonic's orange; as the icon (app/src/subsonic/res/values/app_icon.xml).
    override val brandColor = 0xFFF28C28.toInt()
    override val account get() = Store.prefs.getString("subsonic.url", null)?.let { it + "\n" + Store.prefs.getString("subsonic.user", "") }
    // Another server or user: what pages hold is the old one's.
    override fun changes(key: String) = key == "subsonic.url" || key == "subsonic.user"

    // ---- The library ----

    /** The synced library with every name romanized, in the order the server listed it. */
    class Library(val index: SubsonicIndex, val songs: List<Song>, val albums: List<Item>, val artists: List<Item>, val romanizedFor: Int) {
        private val subById = index.songs.associateBy { SubsonicIds.encode(it.id) }
        val byId = songs.associateBy { it.id }
        private fun ordered(list: List<Song>) = list.sortedWith(compareBy({ subById[it.id]?.disc ?: 0 }, { subById[it.id]?.track ?: 0 }))
        val albumSongs = songs.groupBy { it.album?.id }.mapValues { ordered(it.value) }
        val artistSongs = songs.groupBy { it.firstArtistId }.mapValues { (_, l) -> l.sortedWith(compareBy({ subById[it.id]?.year ?: 0 }, { it.album?.name?.original ?: "" }, { subById[it.id]?.disc ?: 0 }, { subById[it.id]?.track ?: 0 })) }
        val albumById = albums.associateBy { it.id }
        val artistById = artists.associateBy { it.id }
        fun sub(id: String) = subById[id]
    }

    @Serializable
    private class Romanized(val stamp: String, val songs: List<Song>, val albums: List<Item>, val artists: List<Item>)

    @Volatile var library: Library? = null
        private set
    /** Bumped when the library or the sync's state changes, for Settings. */
    var changes by mutableIntStateOf(0)
        private set
    var syncing by mutableStateOf(false)
        private set
    var syncError by mutableStateOf<String?>(null)
        private set
    private val lock = Mutex()

    private fun stamp(i: SubsonicIndex) = "${Store.romanizationVersion}/${BuildConfig.VERSION_CODE}/${i.syncedAt}"

    private fun romanize(i: SubsonicIndex): Library {
        val r = Store.romanizer
        val l = Library(i, i.songs.map { r.song(it) }, i.albums.map { r.item(it) }, i.artists.map { r.item(it) }, Store.romanizationVersion)
        runCatching { File(dir, "romanized.json").writeText(Store.json.encodeToString(Romanized(stamp(i), l.songs, l.albums, l.artists))) }
        return l
    }

    private fun publish(l: Library) {
        library = l
        Playback.markLiked(l.index.songs.filter { it.starred }.map { SubsonicIds.encode(it.id) })
        postMain { changes++ }
    }

    /** The library, romanized as the settings say now. Read from disk, else synced first. Off the main thread. */
    suspend fun lib(): Library = lock.withLock {
        library?.takeIf { it.romanizedFor == Store.romanizationVersion }?.let { return it }
        withContext(Dispatchers.IO) {
            val index = library?.index ?: runCatching { Store.json.decodeFromString<SubsonicIndex>(File(dir, "index.json").readText()) }.getOrNull()
            val l = if (index == null) syncLocked() else {
                val kept = runCatching { Store.json.decodeFromString<Romanized>(File(dir, "romanized.json").readText()) }.getOrNull()?.takeIf { it.stamp == stamp(index) }
                if (kept != null) Library(index, kept.songs, kept.albums, kept.artists, Store.romanizationVersion) else romanize(index)
            }
            publish(l)
            l
        }
    }

    private fun syncLocked(): Library {
        postMain { syncing = true; syncError = null }
        try {
            val index = need().sync()
            dir.mkdirs()
            File(dir, "index.json").writeText(Store.json.encodeToString(index))
            return romanize(index)
        } catch (e: Exception) {
            postMain { syncError = plainError(e) }
            throw e
        } finally {
            postMain { syncing = false }
        }
    }

    /** Syncs the library again; the old one serves until the new one is in. */
    fun sync() {
        if (client == null) return
        scope.launch {
            runCatching { lock.withLock { publish(syncLocked()) } }
            postMain { Store.dataChanged() }
        }
    }

    /** At start: the library into memory, then a sync if the last one is over 6 hours old, and unsent plays out. */
    override fun start() {
        if (client == null) return
        scope.launch {
            val l = runCatching { lib() }.getOrNull()
            if (l != null && System.currentTimeMillis() - l.index.syncedAt > 6 * 3600_000) sync()
            flushPlays()
        }
    }

    /** The server changed or was removed: its library goes. */
    fun forget() {
        library = null
        scope.launch { lock.withLock { dir.deleteRecursively() } }
        changes++
    }

    // ---- Pages ----

    /** The server's mixes (playlists marked "oasisMix"; oasis publishes its streaming services' personal mixes so), for Home's tiles, in the server's order. Other servers have none. */
    var mixTiles by mutableStateOf<List<Item>>(emptyList())
        private set

    /** Reads the mixes again; returns how many there are. Blocking. */
    private fun refreshMixes(): Int {
        val tiles = need().playlists().filter { it.mix }.map { Store.romanizer.item(it) }
        postMain { mixTiles = tiles }
        return tiles.size
    }

    override suspend fun home(from: String?): Pair<String?, List<Section>> {
        val l = lib()
        val r = Store.romanizer
        runCatching { refreshMixes() }
        fun shelf(title: String, items: List<Item>) = Section(roma.core.Text(title, title), items)
        // Play counts live on the server; offline those shelves are left out and the library's own ones stay.
        fun server(type: String) = runCatching { need().albumList(type).map { l.albumById[SubsonicIds.encode(it.id)] ?: r.item(it) } }.getOrDefault(emptyList())
        val newest = l.index.albums.sortedByDescending { it.created ?: "" }.take(20).mapNotNull { l.albumById[SubsonicIds.encode(it.id)] }
        val shelves = listOf(
            shelf("Recently added", newest),
            shelf("Recently played", server("recent")),
            shelf("Most played", server("frequent")),
            shelf("Albums at random", l.albums.shuffled().take(20)),
            shelf("Artists", l.artists.shuffled().take(20)),
        ).filter { it.items.isNotEmpty() }
        return null to shelves
    }
    override val mixes = false
    @Composable override fun HomeTiles(mixes: List<Item>?, read: Boolean) = LibraryTiles()

    override val searchIsLocal = true
    override suspend fun suggestions(query: String) = emptyList<String>()

    /** The index's matches at once, then the server's own results after them. */
    override suspend fun search(query: String, filter: Int, asTyped: String?, partial: suspend (Found) -> Unit): Found {
        val local = searchLocal(query, filter)
        if (filter == 3) return local
        partial(local)
        val l = lib()
        val r = Store.romanizer
        val found = runCatching { need().search(query) }.getOrNull() ?: return local
        val remote = when (filter) {
            0 -> found.first.map { l.byId[SubsonicIds.encode(it.id)] ?: r.song(it) }.map { Item("song", it.id, it.title, it.artistText, it.thumb, it) }
            1 -> found.second.map { l.albumById[SubsonicIds.encode(it.id)] ?: r.item(it) }
            else -> found.third.map { l.artistById[SubsonicIds.encode(it.id)] ?: r.item(it) }
        }
        val seen = local.items.map { it.id }.toSet()
        return Found(local.items + remote.filter { it.id !in seen })
    }

    private suspend fun searchLocal(query: String, filter: Int): Found {
        val l = lib()
        fun <T> best(list: List<T>, texts: (T) -> List<String>) = list.mapNotNull { x -> matchDistance(texts(x), query)?.let { x to it } }.sortedBy { it.second }.take(100).map { it.first }
        fun Item.texts() = listOf(title.original, title.latin, subtitle.original, subtitle.latin, subtitle.latin + title.latin)
        val items = when (filter) {
            0 -> best(l.songs) { listOf(it.title.original, it.title.latin, it.artistOriginal, it.artistLatin, it.artistLatin + it.title.latin, it.album?.name?.original ?: "", it.album?.name?.latin ?: "") }
                .map { Item("song", it.id, it.title, it.artistText, it.thumb, it) }
            1 -> best(l.albums) { it.texts() }
            2 -> best(l.artists) { it.texts() }
            else -> best(playlists()) { it.texts() }
        }
        return Found(items)
    }

    override val libraryReady get() = client != null
    override val libraryHint = "Add your server in Settings to see its playlists and albums."
    override val libraryHintPage = Page("settings", "source")
    override fun libraryKey(tab: Int) = "subsonic-$tab"

    override suspend fun library(tab: Int, partial: suspend (List<Item>) -> Unit): List<Item> {
        val l = lib()
        return when (tab) {
            0 -> playlists()
            1 -> l.albums.sortedBy { it.title.latin.lowercase() }
            else -> l.artists.sortedBy { it.title.latin.lowercase() }
        }
    }

    // The mixes live in Home's tiles; Playlists holds the rest.
    private fun playlists() = need().playlists().filter { !it.mix }.map { Store.romanizer.item(it) }

    override suspend fun detail(p: Page, partial: suspend (Detail) -> Unit): Detail {
        val l = lib()
        val r = Store.romanizer
        return when (p.kind) {
            "album" -> {
                // An album the synced lists don't hold (one seen in search or radio) comes from the server.
                if (l.albumSongs[p.id].isNullOrEmpty()) {
                    val (a, subs) = need().album(SubsonicIds.decode(p.id))
                    val songs = subs.map { l.byId[SubsonicIds.encode(it.id)] ?: r.song(it) }
                    val item = r.item(a)
                    return Detail(item.title, listOfNotNull(item.subtitle.latin, a.year.takeIf { it > 0 }?.toString()).joinToString(" · "), item.thumb ?: songs.firstOrNull()?.thumb, songs, emptyList())
                }
                val a = l.albumById[p.id]
                val sub = l.index.albums.firstOrNull { SubsonicIds.encode(it.id) == p.id }
                val songs = l.albumSongs[p.id].orEmpty()
                val title = a?.title ?: songs.firstOrNull()?.album?.name ?: roma.core.Text("", "")
                Detail(title, listOfNotNull(a?.subtitle?.latin, sub?.year?.takeIf { it > 0 }?.toString()).joinToString(" · "), a?.thumb ?: songs.firstOrNull()?.thumb, songs, emptyList())
            }
            "playlist" -> {
                val (pl, subs) = need().playlist(SubsonicIds.decode(p.id))
                val songs = subs.map { l.byId[SubsonicIds.encode(it.id)] ?: r.song(it) }
                Detail(r.text(pl.name), pl.owner, songs.firstOrNull()?.thumb, songs, emptyList())
            }
            else -> {
                fun count(n: Int, what: String) = "$n $what" + if (n == 1) "" else "s"
                // The library's own first, at once; then the server's page, which may hold more releases and top songs.
                // Offline the library's stays.
                val a = l.artistById[p.id]
                val songs = l.artistSongs[p.id].orEmpty()
                val albums = l.index.albums.filter { it.artistId?.let(SubsonicIds::encode) == p.id }.mapNotNull { l.albumById[SubsonicIds.encode(it.id)] }
                val name = a?.title ?: songs.firstOrNull()?.artists?.firstOrNull()?.name
                val local = name?.let { Detail(it, listOfNotNull(count(albums.size, "album").takeIf { albums.isNotEmpty() }, count(songs.size, "song")).joinToString(" · "), a?.thumb ?: albums.firstOrNull()?.thumb, songs, albums) }
                local?.let { partial(it) }
                val server = runCatching { need().artist(SubsonicIds.decode(p.id)) }.getOrElse { if (local != null) return local else throw it }
                val (sa, subs) = server
                // A release the library doesn't hold is labelled with its type and year: Single · 2021.
                val all = subs.map { s -> l.albumById[SubsonicIds.encode(s.id)] ?: r.item(s).let { i ->
                    val label = listOfNotNull(s.releaseType?.replace("Ep", "EP"), s.year.takeIf { it > 0 }?.toString()).joinToString(" · ")
                    if (label.isEmpty()) i else Item(i.kind, i.id, i.title, roma.core.Text(label, label), i.thumb)
                } }
                val top = runCatching { need().topSongs(sa.name) }.getOrDefault(emptyList()).map { l.byId[SubsonicIds.encode(it.id)] ?: r.song(it) }
                val allSongs = songs + top.filter { t -> songs.none { it.id == t.id } }
                Detail(local?.title ?: r.item(sa).title, count(all.size, "release"), local?.thumb ?: r.item(sa).thumb ?: all.firstOrNull()?.thumb, allSongs, all)
            }
        }
    }

    // ---- Radio, playlists played as one, likes ----

    /**
     * Songs like the seed when the server knows any (getSimilarSongs); each next page is seeded from the last song of
     * the page before, so the radio drifts on. Otherwise the library at random. The leash applies.
     */
    override suspend fun radio(seed: Song, from: String?): SongPage? {
        val l = runCatching { lib() }.getOrNull() ?: return null
        val similar = runCatching { need().similarSongs(SubsonicIds.decode(from ?: seed.id)) }.getOrDefault(emptyList())
            .map { l.byId[SubsonicIds.encode(it.id)] ?: Store.romanizer.song(it) }
        if (similar.isEmpty()) return SongPage(l.songs.shuffled().take(25), "more")
        return SongPage(similar, similar.last().id)
    }

    override suspend fun relatedArtists(seed: Song) = emptySet<String>()
    override val freeRadio = "Free: the server's radio as is"

    override suspend fun playlist(id: String, from: String?): SongPage? {
        val l = runCatching { lib() }.getOrNull() ?: return null
        return when (id) {
            STARRED -> if (from != null) null else SongPage(runCatching { need().starredSongs().map { l.byId[SubsonicIds.encode(it.id)] ?: Store.romanizer.song(it) } }.getOrElse { l.songs.filter { l.sub(it.id)?.starred == true } }, null)
            SHUFFLE -> SongPage(l.songs.shuffled().take(50), "more")
            else -> if (from != null) null else runCatching { need().playlist(SubsonicIds.decode(id)).second.map { l.byId[SubsonicIds.encode(it.id)] ?: Store.romanizer.song(it) } }.getOrNull()?.let { SongPage(it, null) }
        }
    }
    override val likedId = STARRED

    override val canLike get() = client != null
    override fun like(id: String, like: Boolean) = runCatching { need().star(SubsonicIds.decode(id), like) }.isSuccess
    override fun liked(id: String) = runCatching { need().song(SubsonicIds.decode(id)).starred }.getOrNull()

    // A starred album or artist is in the library. A server that then fetches what's missing (oasis) says how that goes.
    override fun saved(p: Page) = runCatching {
        val id = SubsonicIds.decode(p.id)
        val (starred, wanted, why) = when (p.kind) {
            "album" -> need().album(id).first.let { Triple(it.starred, it.wanted, it.wantedError) }
            "artist" -> need().artist(id).first.let { Triple(it.starred, it.wanted, it.wantedError) }
            else -> return null
        }
        val state = when (wanted) { "wanted" -> "On its way to the server"; "downloading" -> "Downloading to the server"; "failed" -> "The server couldn't find it"; else -> null }
        InLibrary(starred, state?.let { listOfNotNull(it, why).joinToString(": ") })
    }.getOrNull()
    override fun save(p: Page, on: Boolean) = runCatching { need().star(SubsonicIds.decode(p.id), on, if (p.kind == "album") "albumId" else "artistId") }.isSuccess

    // ---- Streams, covers, lyrics ----

    override fun streamUrl(id: String, full: Boolean) = need().streamUrl(SubsonicIds.decode(id))
    override fun loudness(id: String): Float? = null

    // Covers are stored as "subsonic:<id>", sized as "subsonic:<id>?size=N": no credentials in stored songs.
    override fun thumbAt(url: String, px: Int) = if (url.startsWith("subsonic:")) "$url?size=$px" else url
    override fun bigThumb(url: String) = thumbAt(url, BIG_PX)

    /** The getCoverArt URL for a "subsonic:<id>[?size=N]" cover, made now with fresh auth. */
    override fun fetchUrl(url: String): String {
        if (!url.startsWith("subsonic:")) return url
        val rest = url.removePrefix("subsonic:")
        return client?.coverUrl(SubsonicIds.decode(SubsonicIds.PREFIX + rest.substringBefore('?')), rest.substringAfter("size=", "").toIntOrNull()) ?: url
    }

    private val ownLyrics = SubsonicLyrics(::need)
    override val lyrics: LyricsProvider? get() = ownLyrics.takeIf { library?.index?.extensions?.contains("songLyrics") == true }
    // LRCLIB (free, open) for songs the server has no lyrics for.
    override val lyricsProviders = listOf<LyricsProvider>(LrcLib)
    override val lyricsRule = "The server's lyrics are the reference, when it has any: word timing that matches them wins, then their own timing, then matching line timing, then their plain text."
    override val hasComments = false
    override fun shareUrl(s: Song): String? = null

    // ---- Scrobbling: see docs/subsonic.md ----

    /** "Also scrobble to Last.fm" until the user sets it: on unless the server says it scrobbles to Last.fm itself (getUser's scrobblingEnabled). */
    val lastFmDefault get() = library?.index?.serverScrobbles != true
    override val lastFm get() = Store.prefs.getBoolean("subsonic.lastfm", lastFmDefault)

    override fun nowPlaying(s: Song) {
        val c = client ?: return
        CoroutineScope(Dispatchers.IO).launch { runCatching { c.scrobble(listOf(SubsonicIds.decode(s.id)), emptyList(), submission = false) } }
    }

    // One instance: its lock keeps an add and a flush from losing or repeating plays.
    private val plays by lazy { ScrobbleQueue(File(Store.files, "subsonic-scrobbles.json")) }

    override fun played(s: Scrobble) {
        if (client == null) return
        plays.add(s)
        scope.launch { flushPlays() }
    }

    /** Sends the plays that couldn't go yet, 50 to a call, each with its start time in ms. */
    private fun flushPlays() {
        val c = client ?: return
        fun send(batch: List<Scrobble>) = c.scrobble(batch.map { SubsonicIds.decode(it.id!!) }, batch.map { it.timestamp * 1000 }, submission = true)
        // A song the server no longer has fails the whole batch, which would stay
        // queued for good: then the plays go one by one, and the missing songs' are dropped.
        runCatching {
            plays.flush { batch ->
                try { send(batch) } catch (e: SubsonicException) {
                    if (e.code != 70) throw e
                    batch.forEach { one -> try { send(listOf(one)) } catch (e: SubsonicException) { if (e.code != 70) throw e } }
                }
            }
        }
    }


    override val setUp get() = Store.str("subsonic.url", "").isNotBlank()
    @Composable override fun Setup() = ServerSetup()

    override val settingsTitle = "Server"
    @Composable override fun settingsSummary(): String {
        changes
        return listOfNotNull(Store.str("subsonic.url", "").ifBlank { "Not set" }, library?.let { "${it.songs.size} songs" }).joinToString(" · ")
    }
    @Composable override fun Settings() = ServerSettings()
}
