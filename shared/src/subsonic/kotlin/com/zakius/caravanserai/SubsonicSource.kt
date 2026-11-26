package com.zakius.caravanserai

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import roma.core.SubsonicIds
import roma.core.SubsonicIndex
import roma.core.SubsonicLyrics
import roma.core.foldSearch
import roma.core.item
import roma.core.LrcLib
import roma.core.matchDistance
import roma.core.matchFolded
import roma.core.mixItem
import roma.core.prepareQuery
import roma.core.song
import roma.core.subsonicCover
import java.io.File
import java.util.concurrent.ConcurrentHashMap

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

    // Work runs one task at a time and in order, so a server change's deletion runs before the new server's sync.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)) }
    private val dir get() = File(Store.cache, "subsonic")

    private var clientKey: String? = null
    private var cachedClient: SubsonicClient? = null

    /** Null until a server is set. */
    val client: SubsonicClient?
        @Synchronized get() {
            val url = Store.prefs.getString("subsonic.url", null)
            if (url.isNullOrBlank()) return null
            val user = Store.prefs.getString("subsonic.user", "") ?: ""
            val password = Store.prefs.getString("subsonic.password", "") ?: ""
            val key = "$url\n$user\n$password"
            if (key != clientKey) {
                clientKey = key
                val created = SubsonicClient(url, user, password)
                created.romanization = romanizes
                created.oasis = library?.index?.supports(SubsonicClient.OASIS) == true
                cachedClient = created
            }
            return cachedClient
        }

    /**
     * The server lists the OpenSubsonic extension romanization: names come with its Latin forms, lyrics with its
     * pronunciation layer, and the Japanese dictionary is never fetched. It is kept in prefs so the first start's
     * scripts question and the dictionary's offer know it before the library is read. Connecting and every sync set
     * it.
     */
    override val romanizes get() = Store.bool(ROMANIZES, false)
    const val ROMANIZES = "subsonic.romanization"

    private fun need() = client ?: throw IllegalStateException("No server set. Add one in Settings → Music source.")

    override val name = "your server"
    // Subsonic's orange, the same as the app icon (app/src/subsonic/res/values/app_icon.xml).
    override val brandColor = 0xFFF28C28.toInt()
    override val account: String?
        get() {
            val url = Store.prefs.getString("subsonic.url", null) ?: return null
            return url + "\n" + Store.prefs.getString("subsonic.user", "")
        }
    // After a change of server or user, what the pages hold belongs to the old one.
    override fun changes(key: String) = key == "subsonic.url" || key == "subsonic.user"

    /** The synced library with every name romanized, in the order the server listed it. */
    class Library(val index: SubsonicIndex, val songs: List<Song>, val albums: List<Item>, val artists: List<Item>, val romanizedFor: Int) {
        private val subById = index.songs.associateBy { SubsonicIds.encode(it.id) }
        val subAlbumById = index.albums.associateBy { SubsonicIds.encode(it.id) }
        val subArtistById = index.artists.associateBy { SubsonicIds.encode(it.id) }
        val byId = songs.associateBy { it.id }
        private fun ordered(list: List<Song>) = list.sortedWith(
            compareBy(
                { song -> subById[song.id]?.disc ?: 0 },
                { song -> subById[song.id]?.track ?: 0 },
            ),
        )
        val albumSongs = songs.groupBy { it.album?.id }.mapValues { entry -> ordered(entry.value) }
        val artistSongs = songs.groupBy { it.firstArtistId }.mapValues { (_, list) ->
            list.sortedWith(
                compareBy(
                    { song -> subById[song.id]?.year ?: 0 },
                    { song -> song.album?.name?.original ?: "" },
                    { song -> subById[song.id]?.disc ?: 0 },
                    { song -> subById[song.id]?.track ?: 0 },
                ),
            )
        }
        val albumById = albums.associateBy { it.id }
        val artistById = artists.associateBy { it.id }
        /** Each artist's albums, newest first. */
        val artistAlbums: Map<String, List<Item>> by lazy {
            val out = HashMap<String, MutableList<Item>>()
            for (album in index.albums.sortedByDescending { it.released }) {
                val aid = album.artistId?.let(SubsonicIds::encode) ?: continue
                val item = albumById[SubsonicIds.encode(album.id)] ?: continue
                out.getOrPut(aid) { ArrayList() }.add(item)
            }
            out
        }
        val sortedAlbums: List<Item> by lazy { albums.sortedBy { it.title.latin.lowercase() } }
        val sortedArtists: List<Item> by lazy { artists.sortedBy { it.title.latin.lowercase() } }
        // Pre-folded search strings built lazily on first search, so keystrokes never re-run Unicode normalization.
        val foldedSongs: Array<Array<String>> by lazy {
            Array(songs.size) { i ->
                val s = songs[i]
                arrayOf(
                    foldSearch(s.title.original),
                    foldSearch(s.title.latin),
                    foldSearch(s.artistOriginal),
                    foldSearch(s.artistLatin),
                    foldSearch(s.artistLatin + s.title.latin),
                    foldSearch(s.album?.name?.original ?: ""),
                    foldSearch(s.album?.name?.latin ?: ""),
                )
            }
        }
        val foldedAlbums: Array<Array<String>> by lazy { foldItems(albums) }
        val foldedArtists: Array<Array<String>> by lazy { foldItems(artists) }
        /** The server sends ReplayGain. */
        val hasGain = index.songs.any { it.gain != null }
        fun sub(id: String) = subById[id]

        private fun foldItems(items: List<Item>) = Array(items.size) { i ->
            val it = items[i]
            arrayOf(
                foldSearch(it.title.original),
                foldSearch(it.title.latin),
                foldSearch(it.subtitle.original),
                foldSearch(it.subtitle.latin),
                foldSearch(it.subtitle.latin + it.title.latin),
            )
        }
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
        val l = Library(
            i,
            i.songs.map { r.song(it) },
            i.albums.map { r.item(it) },
            i.artists.map { r.item(it) },
            Store.romanizationVersion,
        )
        runCatching {
            val romanized = Romanized(stamp(i), l.songs, l.albums, l.artists)
            File(dir, "romanized.json").writeText(Store.json.encodeToString(romanized))
        }
        return l
    }

    private fun publish(l: Library) {
        library = l
        client?.oasis = l.index.supports(SubsonicClient.OASIS)
        Playback.markLiked(l.index.songs.filter { it.starred }.map { SubsonicIds.encode(it.id) })
        postMain { changes++ }
    }

    /** The library, romanized as the settings say now. Read from disk, else synced first. Off the main thread. */
    suspend fun lib(): Library = lock.withLock {
        val current = library
        if (current != null && current.romanizedFor == Store.romanizationVersion) return current
        withContext(Dispatchers.IO) {
            val index = library?.index ?: runCatching {
                Store.json.decodeFromString<SubsonicIndex>(File(dir, "index.json").readText())
            }.getOrNull()
            val l = if (index == null) {
                syncLocked()
            } else {
                val saved = runCatching {
                    Store.json.decodeFromString<Romanized>(File(dir, "romanized.json").readText())
                }.getOrNull()
                val kept = if (saved != null && saved.stamp == stamp(index)) saved else null
                if (kept != null) {
                    Library(index, kept.songs, kept.albums, kept.artists, Store.romanizationVersion)
                } else {
                    romanize(index)
                }
            }
            publish(l)
            l
        }
    }

    private fun syncLocked(): Library {
        postMain {
            syncing = true
            syncError = null
        }
        try {
            val index = need().sync()
            val romanizing = SubsonicClient.ROMANIZATION in index.extensionVersions
            // When the server gains or loses the extension, names come from the new index, and lyrics and the queue
            // start over.
            if (romanizing != romanizes) {
                postMain {
                    Store.set(ROMANIZES, romanizing)
                    Playback.romanizationChanged()
                }
            }
            postMain { Store.set(SERVER_SCROBBLES, index.serverScrobbles == true) }
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

    /**
     * Called at start. Loads the library into memory, syncs if the last sync is over 6 hours old, and sends unsent
     * plays.
     */
    override fun start() {
        if (client == null) return
        scope.launch {
            val l = runCatching { lib() }.getOrNull()
            if (l != null && System.currentTimeMillis() - l.index.syncedAt > 6 * 60 * 60 * 1000) sync()
            flushPlays()
        }
    }

    /**
     * Called when the server changed or was removed. Its library, covers, lyrics and cached songs are deleted, because
     * another server's ids can be the same. Its unsent plays are dropped too, before a flush could send them to the new
     * server.
     */
    fun forget() {
        library = null
        savedCache.clear()
        shareUrlCache.clear()
        Images.clear()
        plays.clear()
        scope.launch {
            lock.withLock {
                dir.deleteRecursively()
                File(Store.cache, "lyrics").deleteRecursively()
            }
            Playback.player.clearCache()
        }
        changes++
    }

    override suspend fun home(from: String?): Pair<String?, List<Section>> = coroutineScope {
        val l = lib()
        val r = Store.romanizer
        fun shelf(title: String, items: List<Item>) = Section(roma.core.Text(title, title), items)
        // Play counts and mixes live on the server; fetch all three concurrently instead of serially.
        fun server(type: String): List<Item> = runCatching {
            need().albumList(type).map { album -> l.albumById[SubsonicIds.encode(album.id)] ?: r.item(album) }
        }.getOrDefault(emptyList())
        val playlistsJob = async(Dispatchers.IO) { runCatching { need().playlists() }.getOrDefault(emptyList()) }
        val recentJob = async(Dispatchers.IO) { server("recent") }
        val frequentJob = async(Dispatchers.IO) { server("frequent") }
        val newest = l.index.albums
            .sortedByDescending { it.created ?: "" }
            .take(20)
            .mapNotNull { album -> l.albumById[SubsonicIds.encode(album.id)] }
        val allPlaylists = playlistsJob.await()
        val recent = recentJob.await()
        val frequent = frequentJob.await()
        val mixShelves = SubsonicClient.mixShelves(allPlaylists, client?.user ?: "").map { (title, list) ->
            shelf(title, list.map { r.mixItem(it) })
        }
        val shelves = (
            mixShelves + listOf(
                shelf("Recently added", newest),
                shelf("Recently played", recent),
                shelf("Most played", frequent),
                shelf("Albums at random", l.albums.shuffled().take(20)),
                shelf("Artists", l.artists.shuffled().take(20)),
            )
        ).filter { it.items.isNotEmpty() }
        null to shelves
    }
    override val mixes = false
    @Composable override fun HomeTiles(mixes: List<Item>?, read: Boolean) = LibraryTiles()

    override val searchIsLocal = true
    override suspend fun suggestions(query: String) = emptyList<String>()

    /**
     * The index's matches at once, then the server's own results after them, asked for the filter's kind alone. A server
     * can take many seconds: oasis asks YouTube Music, and MusicBrainz too for albums and artists. When it can't be
     * asked, the index's matches are the answer; with none of those the search fails, because "No results" would be
     * said of music the server may well have.
     */
    override suspend fun search(query: String, filter: Int, asTyped: String?, partial: suspend (Found) -> Unit): Found {
        val local = searchLocal(query, filter)
        if (filter == 3) return local
        partial(Found(local.items, searching = true))
        val l = lib()
        val r = Store.romanizer
        val found = try {
            need().search(query, filter)
        } catch (e: Exception) {
            if (local.items.isEmpty()) throw e
            return local
        }
        val remote = when (filter) {
            0 -> {
                val songs = found.first.map { sub -> l.byId[SubsonicIds.encode(sub.id)] ?: r.song(sub) }
                songs.map { song -> Item("song", song.id, song.title, song.artistText, song.thumb, song) }
            }
            1 -> found.second.map { l.albumById[SubsonicIds.encode(it.id)] ?: r.item(it) }
            else -> found.third.map { l.artistById[SubsonicIds.encode(it.id)] ?: r.item(it) }
        }
        val seen = local.items.map { it.id }.toSet()
        return Found(local.items + remote.filter { it.id !in seen })
    }

    private suspend fun searchLocal(query: String, filter: Int): Found {
        val l = lib()
        val prepared = prepareQuery(query) ?: return Found(emptyList())
        fun <T> bestFolded(list: List<T>, folded: Array<Array<String>>): List<T> {
            val scored = ArrayList<Pair<T, Int>>()
            for (i in list.indices) {
                val distance = matchFolded(folded[i], prepared) ?: continue
                scored.add(list[i] to distance)
            }
            return scored.sortedBy { it.second }.take(100).map { it.first }
        }
        fun Item.texts() = listOf(title.original, title.latin, subtitle.original, subtitle.latin, subtitle.latin + title.latin)
        val items = when (filter) {
            0 -> {
                val songs = bestFolded(l.songs, l.foldedSongs)
                songs.map { song -> Item("song", song.id, song.title, song.artistText, song.thumb, song) }
            }
            1 -> bestFolded(l.albums, l.foldedAlbums)
            2 -> bestFolded(l.artists, l.foldedArtists)
            else -> playlists(includeMixes = true)
                .mapNotNull { x -> matchDistance(x.texts(), query)?.let { x to it } }
                .sortedBy { it.second }
                .take(100)
                .map { it.first }
        }
        return Found(items)
    }

    override val libraryReady get() = client != null
    override val libraryHint = "Add your server in Settings to see its albums and playlists."
    override val libraryHintPage = Page("settings", "source")
    override val libraryTabs = listOf("Playlists", "Albums", "Artists", "Starred")
    override fun libraryKey(tab: Int) = "subsonic-" + libraryTabs[tab].lowercase()
    override fun libraryEmpty(tab: Int) = when (libraryTabs[tab]) {
        "Playlists" -> "No playlists on your server yet."
        "Starred" -> "Nothing starred yet. Star songs, albums and artists to find them here."
        else -> "Your server's library is empty."
    }

    override suspend fun library(tab: Int, partial: suspend (List<Item>) -> Unit): List<Item> {
        val l = lib()
        return when (libraryTabs[tab]) {
            "Albums" -> l.sortedAlbums
            "Artists" -> l.sortedArtists
            "Playlists" -> playlists()
            else -> starred(l)
        }
    }

    /** What is starred: artists, then albums, then songs, each as the library holds it. */
    private fun starred(l: Library): List<Item> {
        val r = Store.romanizer
        val (songs, albums, artists) = need().starred()
        val artistItems = artists.map { a -> l.artistById[SubsonicIds.encode(a.id)] ?: r.item(a) }
        val albumItems = albums.map { a -> l.albumById[SubsonicIds.encode(a.id)] ?: r.item(a) }
        val songItems = songs.map { sub ->
            val song = l.byId[SubsonicIds.encode(sub.id)] ?: r.song(sub)
            Item("song", song.id, song.title, song.artistText, song.thumb, song)
        }
        return artistItems + albumItems + songItems
    }

    // Generated mixes and radios live in Home's shelves; Library → Playlists holds actual playlists.
    private fun playlists(includeMixes: Boolean = false) =
        need().playlists().filter { includeMixes || !it.isHomeMix }.map { Store.romanizer.item(it) }

    override suspend fun detail(p: Page, partial: suspend (Detail) -> Unit): Detail {
        val l = lib()
        val r = Store.romanizer
        return when (p.kind) {
            "album" -> {
                // An album the synced lists don't hold (one seen in search or radio) comes from the server.
                if (l.albumSongs[p.id].isNullOrEmpty()) {
                    val (a, subs) = need().album(SubsonicIds.decode(p.id))
                    cacheSaved(p, a.starred, a.wanted, a.wantedError)
                    val songs = subs.map { l.byId[SubsonicIds.encode(it.id)] ?: r.song(it) }
                    val item = r.item(a)
                    val year = if (a.year > 0) a.year.toString() else null
                    val subtitle = listOfNotNull(item.subtitle.latin, year).joinToString(" · ")
                    return Detail(item.title, subtitle, item.thumb ?: songs.firstOrNull()?.thumb, songs, emptyList())
                }
                val a = l.albumById[p.id]
                val sub = l.subAlbumById[p.id]
                if (sub != null) cacheSaved(p, sub.starred, sub.wanted, sub.wantedError)
                val songs = l.albumSongs[p.id].orEmpty()
                val title = a?.title ?: songs.firstOrNull()?.album?.name ?: roma.core.Text("", "")
                val year = sub?.year
                val yearText = if (year != null && year > 0) year.toString() else null
                val subtitle = listOfNotNull(a?.subtitle?.latin, yearText).joinToString(" · ")
                val discs = songs.map { song -> l.sub(song.id)?.disc ?: 0 }
                val tracks = songs.map { song -> l.sub(song.id)?.track ?: 0 }
                Detail(title, subtitle, a?.thumb ?: songs.firstOrNull()?.thumb, songs, emptyList(), discs, tracks)
            }
            "playlist" -> {
                val (pl, subs) = need().playlist(SubsonicIds.decode(p.id))
                val songs = subs.map { l.byId[SubsonicIds.encode(it.id)] ?: r.song(it) }
                val thumb = subsonicCover(pl.coverArt) ?: songs.firstOrNull()?.thumb
                Detail(r.text(pl.name, served = pl.nameLatin), pl.owner, thumb, songs, emptyList())
            }
            else -> coroutineScope {
                fun count(n: Int, what: String) = "$n $what" + if (n == 1) "" else "s"
                // The library's own page comes first, at once. Then comes the server's page, which may hold more
                // releases and top songs. Offline, the library's page stays.
                val a = l.artistById[p.id]
                val subArtist = l.subArtistById[p.id]
                if (subArtist != null) cacheSaved(p, subArtist.starred, subArtist.wanted, subArtist.wantedError)
                val songs = l.artistSongs[p.id].orEmpty()
                val albums = l.artistAlbums[p.id].orEmpty()
                val name = a?.title ?: songs.firstOrNull()?.artists?.firstOrNull()?.name
                val local = if (name == null) {
                    null
                } else {
                    val albumCount = if (albums.isNotEmpty()) count(albums.size, "album") else null
                    val subtitle = listOfNotNull(albumCount, count(songs.size, "song")).joinToString(" · ")
                    Detail(name, subtitle, a?.thumb ?: albums.firstOrNull()?.thumb, songs, albums)
                }
                if (local != null) partial(local)
                // If the artist's original name is already known from the local index, fetch artist and topSongs in parallel.
                val knownName = subArtist?.name ?: name?.original
                val topJob = if (knownName != null) {
                    async(Dispatchers.IO) { runCatching { need().topSongs(knownName) }.getOrDefault(emptyList()) }
                } else {
                    null
                }
                val server = runCatching { need().artist(SubsonicIds.decode(p.id)) }.getOrElse { error ->
                    topJob?.cancel()
                    if (local != null) return@coroutineScope local else throw error
                }
                val (sa, subs) = server
                cacheSaved(p, sa.starred, sa.wanted, sa.wantedError)
                // Newest first. A release the library doesn't hold is labelled with its type and year, such as
                // "Single · 2021".
                val all = subs.sortedByDescending { it.released }.map { s ->
                    val known = l.albumById[SubsonicIds.encode(s.id)]
                    if (known != null) {
                        known
                    } else {
                        val i = r.item(s)
                        val year = if (s.year > 0) s.year.toString() else null
                        val label = listOfNotNull(s.releaseType?.replace("Ep", "EP"), year).joinToString(" · ")
                        if (label.isEmpty()) i else Item(i.kind, i.id, i.title, roma.core.Text(label, label), i.thumb)
                    }
                }
                val topSubs = if (topJob != null && sa.name == knownName) {
                    topJob.await()
                } else {
                    topJob?.cancel()
                    runCatching { need().topSongs(sa.name) }.getOrDefault(emptyList())
                }
                val top = topSubs.map { sub -> l.byId[SubsonicIds.encode(sub.id)] ?: r.song(sub) }
                val allSongs = songs + top.filter { t -> songs.none { it.id == t.id } }
                Detail(
                    local?.title ?: r.item(sa).title,
                    count(all.size, "release"),
                    local?.thumb ?: r.item(sa).thumb ?: all.firstOrNull()?.thumb,
                    allSongs,
                    all,
                )
            }
        }
    }

    /**
     * Songs like the seed when the server knows any (getSimilarSongs); each next page is seeded from the last song of
     * the page before, so the radio drifts on. Otherwise the library at random. The leash applies.
     */
    override suspend fun radio(seed: Song, from: String?): SongPage? {
        val l = runCatching { lib() }.getOrNull() ?: return null
        val similarSubs = runCatching { need().similarSongs(SubsonicIds.decode(from ?: seed.id)) }.getOrDefault(emptyList())
        val similar = similarSubs.map { sub -> l.byId[SubsonicIds.encode(sub.id)] ?: Store.romanizer.song(sub) }
        if (similar.isEmpty()) return SongPage(l.songs.shuffled().take(25), "more")
        return SongPage(similar, similar.last().id)
    }

    override suspend fun relatedArtists(seed: Song) = emptySet<String>()
    override val freeRadio = "Free: the server's radio as is"

    override suspend fun playlist(id: String, from: String?): SongPage? {
        val l = runCatching { lib() }.getOrNull() ?: return null
        return when (id) {
            STARRED -> if (from != null) {
                null
            } else {
                val starred = runCatching {
                    need().starredSongs().map { sub -> l.byId[SubsonicIds.encode(sub.id)] ?: Store.romanizer.song(sub) }
                }.getOrElse {
                    l.songs.filter { song -> l.sub(song.id)?.starred == true }
                }
                SongPage(starred, null)
            }
            SHUFFLE -> SongPage(l.songs.shuffled().take(50), "more")
            else -> if (from != null) {
                null
            } else {
                val songs = runCatching {
                    val subs = need().playlist(SubsonicIds.decode(id)).second
                    subs.map { sub -> l.byId[SubsonicIds.encode(sub.id)] ?: Store.romanizer.song(sub) }
                }.getOrNull()
                if (songs == null) null else SongPage(songs, null)
            }
        }
    }
    override val likedId = STARRED

    override val canLike get() = client != null
    override val stars = true
    override fun like(id: String, like: Boolean) = runCatching { need().star(SubsonicIds.decode(id), like) }.isSuccess
    override fun liked(id: String) = runCatching { need().song(SubsonicIds.decode(id)).starred }.getOrNull()

    // A starred album or artist is in the library. A server that then fetches what's missing (extension oasis) says
    // how that goes. Cached from detail() and saved() so PageButtons avoids a duplicate getAlbum/getArtist round trip.
    private val savedCache = ConcurrentHashMap<String, Pair<InLibrary, Long>>()
    private val shareUrlCache = ConcurrentHashMap<String, String>()
    private const val SAVED_TTL_MS = 60_000L

    private fun toInLibrary(starred: Boolean, wanted: String?, why: String?): InLibrary {
        val state = when (wanted) {
            "wanted" -> "On its way to the server"
            "downloading" -> "Downloading to the server"
            "failed" -> "The server couldn't find it"
            else -> null
        }
        val note = if (state == null) null else listOfNotNull(state, why).joinToString(": ")
        return InLibrary(starred, note)
    }

    private fun cacheSaved(p: Page, starred: Boolean, wanted: String?, why: String?) {
        savedCache["${p.kind}:${p.id}"] = toInLibrary(starred, wanted, why) to System.currentTimeMillis()
    }

    override fun peekSaved(p: Page): InLibrary? {
        if (p.kind !in setOf("album", "artist")) return null
        val entry = savedCache["${p.kind}:${p.id}"] ?: return null
        return if (System.currentTimeMillis() - entry.second < SAVED_TTL_MS) entry.first else null
    }

    override fun saved(p: Page): InLibrary? = runCatching {
        val id = SubsonicIds.decode(p.id)
        val (starred, wanted, why) = when (p.kind) {
            "album" -> {
                val album = need().album(id).first
                Triple(album.starred, album.wanted, album.wantedError)
            }
            "artist" -> {
                val artist = need().artist(id).first
                Triple(artist.starred, artist.wanted, artist.wantedError)
            }
            else -> return null
        }
        val result = toInLibrary(starred, wanted, why)
        savedCache["${p.kind}:${p.id}"] = result to System.currentTimeMillis()
        result
    }.getOrNull()

    override fun save(p: Page, on: Boolean): Boolean {
        val param = if (p.kind == "album") "albumId" else "artistId"
        val ok = runCatching { need().star(SubsonicIds.decode(p.id), on, param) }.isSuccess
        if (ok) savedCache.remove("${p.kind}:${p.id}")
        return ok
    }

    override fun streamUrl(id: String, full: Boolean) = need().streamUrl(SubsonicIds.decode(id))
    // OpenSubsonic's replayGain is the gain to a -18 LUFS reference. Like YouTube's loudness, this returns the dB above
    // -14 LUFS, which is the negated gain minus the 4 dB between the two references.
    override fun loudness(id: String): Float? {
        val gain = library?.sub(id)?.gain ?: return null
        return -gain - 4f
    }
    override val knowsLoudness get() = library?.hasGain == true
    override val hasVideos = false

    // Covers are stored as "subsonic:<id>" and sized as "subsonic:<id>?size=N", so stored songs hold no credentials.
    override fun thumbAt(url: String, px: Int) = if (url.startsWith("subsonic:")) "$url?size=$px" else url
    override fun bigThumb(url: String) = thumbAt(url, BIG_PX)

    /** The getCoverArt URL for a "subsonic:<id>[?size=N]" cover, made now with fresh auth. */
    override fun fetchUrl(url: String): String {
        if (!url.startsWith("subsonic:")) return url
        val rest = url.removePrefix("subsonic:")
        val c = client ?: return url
        val id = SubsonicIds.decode(SubsonicIds.PREFIX + rest.substringBefore('?'))
        val size = rest.substringAfter("size=", "").toIntOrNull()
        return c.coverUrl(id, size)
    }

    // With the extension and songLyrics v2 (enhanced lyrics), the app asks only for the layers the lyrics show: "main" for
    // the original and "pronunciation" for the romanization.
    private val lyricsKinds: List<String>?
        get() {
            if (!romanizes || library?.index?.supports(SubsonicClient.SONG_LYRICS, 2) != true) return null
            val main = if (Store.bool("lyrics.layer.original", true)) "main" else null
            val pronunciation = if (Store.bool("lyrics.layer.latin", true)) "pronunciation" else null
            return listOfNotNull(main, pronunciation)
        }
    override val lyricsVariant get() = lyricsKinds?.joinToString("+", prefix = ".") ?: ""
    private val ownLyrics = SubsonicLyrics(::need) { lyricsKinds }
    override val lyrics: LyricsProvider?
        get() = if (library?.index?.supports(SubsonicClient.SONG_LYRICS) == true) ownLyrics else null
    // LRCLIB (free, open) for songs the server has no lyrics for.
    override val lyricsProviders = listOf<LyricsProvider>(LrcLib)
    override val lyricsRule = "The server's lyrics are the reference, when it has any: word timing that matches them wins, then their own timing, then matching line timing, then their plain text."
    override val hasComments = false
    // The server's link for the song (extension shareUrl), shared as is. Checked in index/cache first so SongMenu enables Share on Frame 1.
    override fun peekShareUrl(s: Song): String? = library?.sub(s.id)?.shareUrl ?: shareUrlCache[s.id]
    override fun shareUrl(s: Song): String? {
        peekShareUrl(s)?.let { return it }
        return runCatching { need().song(SubsonicIds.decode(s.id)).shareUrl }.getOrNull()?.also { shareUrlCache[s.id] = it }
    }
    override val canShare get() = library?.index?.supports(SubsonicClient.SHARE_URL) == true

    // docs/subsonic.md describes how scrobbling works.

    /**
     * The default of "Also scrobble to Last.fm" until the user sets it. It is on unless the server says it scrobbles
     * to Last.fm itself (getUser's scrobblingEnabled). Connecting and every sync keep the server's answer in prefs, so
     * it holds before the library is read.
     */
    val lastFmDefault get() = !Store.bool(SERVER_SCROBBLES, false)
    const val SERVER_SCROBBLES = "subsonic.serverScrobbles"
    override val lastFm get() = Store.prefs.getBoolean("subsonic.lastfm", lastFmDefault)

    override fun nowPlaying(s: Song) {
        val c = client ?: return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { c.scrobble(listOf(SubsonicIds.decode(s.id)), emptyList(), submission = false) }
        }
    }

    // There must be one instance, because its lock keeps an add and a flush from losing or repeating plays.
    private val plays by lazy { ScrobbleQueue(File(Store.files, "subsonic-scrobbles.json")) }

    override fun played(s: Scrobble) {
        if (client == null) return
        plays.add(s)
        scope.launch { flushPlays() }
    }

    /** Sends the plays that couldn't go yet, 50 to a call, each with its start time in ms. */
    private fun flushPlays() {
        val c = client ?: return
        runCatching {
            plays.flush { batch ->
                val ids = batch.map { play -> SubsonicIds.decode(play.id!!) }
                val timesMs = batch.map { play -> play.timestamp * 1000 }
                c.submit(ids, timesMs)
            }
        }
    }

    override val setUp get() = Store.str("subsonic.url", "").isNotBlank()
    @Composable override fun Setup() = ServerSetup()

    override val settingsTitle = "Server"
    @Composable override fun settingsSummary(): String {
        // Reading changes makes the summary recompose when the library changes.
        changes
        val server = Store.str("subsonic.url", "").ifBlank { "Not set" }
        val current = library
        val songCount = if (current == null) null else "${current.songs.size} songs"
        return listOfNotNull(server, songCount).joinToString(" · ")
    }
    @Composable override fun Settings() = ServerSettings()
}
