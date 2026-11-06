package com.zakius.roma

import androidx.compose.runtime.Composable
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import roma.core.Item
import roma.core.LyricsProvider
import roma.core.Song
import roma.core.Streams
import roma.core.YouTubeLyrics
import roma.core.item
import roma.core.lyricsProviders as allLyricsProviders
import roma.core.personalMixes
import roma.core.song
import java.io.File

/** roma's one source. */
val backend: Source = YouTubeMusic

/** YouTube Music through innertube. */
object YouTubeMusic : Source {
    override val name = "YouTube Music"
    // YouTube Music's red, a shade off YouTube's own, the same as the app icon (app/src/youtube/res/values/app_icon.xml).
    override val brandColor = 0xFFE62117.toInt()
    override val account get() = cookie

    /** The account's cookie, as saved. YouTube's own copy isn't read on the main thread: that would build its client. */
    @Volatile var cookie: String? = null
        private set
    val signedIn get() = cookie?.contains("SAPISID") == true

    override fun init() {
        val prefs = Store.prefs
        cookie = prefs.getString("cookie", null)
        YouTube.cookie = cookie
        // InnerTubeX sends the account cookies on browse and next calls (home, radio) only with this on. Without it,
        // Home is the anonymous one and never has the personal mixes. Metrolist turns it on too.
        YouTube.useLoginForBrowse = true
        // The language is English, like the app. InnerTubeX would send the phone's language, and in Polish YouTube
        // Music's Songs search leaves out the original "Youngblood" (5SOS) while English finds it first. The region
        // stays the phone's.
        val region = java.util.Locale.getDefault().country.ifEmpty { "US" }
        YouTube.locale = com.metrolist.innertube.models.YouTubeLocale(gl = region, hl = "en")
        prefs.getString("visitorData", null)?.let { YouTube.visitorData = it }
        prefs.getString("dataSyncId", null)?.let { YouTube.dataSyncId = it }
        Streams.refuseVisionOs = BuildConfig.REFUSE_VISIONOS
        roma.core.InnerTubeXStreams.tokenProvider = platformPoTokens()
    }

    override fun start() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            Streams.keepIn(File(Store.cache, "streams.txt"))
            // The first stream lookup of a run sets up NewPipe and asks YouTube for visitor data. Doing it now means
            // the first tap doesn't pay for it. It uses the song Play would resume, so that song's URL is then known
            // too (kept 5 hours).
            val resumeId = Playback.resumeId() ?: Store.anySong()?.id
            if (resumeId != null) runCatching { Playback.streamUrl(resumeId) }
            // When a song needed the InnerTubeX fallback in the last week, warm that path too: its player script,
            // cipher config and the PoToken WebView take a few seconds cold. Like other speculative work, it is skipped
            // on a metered or data-saving connection (Playback.thrifty). It waits instead of running at launch, because
            // creating the first WebView blocks the main thread while Home is still coming in.
            if (Playback.thrifty()) return@launch
            delay(10_000)
            val fallbackId = Streams.recentFallback()
            if (fallbackId != null) runCatching { Playback.streamUrl(fallbackId) }
        }
    }

    // Signing in or out changes everything: the mixes are the account's, and every page holds the account's things.
    override fun changes(key: String): Boolean {
        if (key != "cookie") return false
        cookie = Store.prefs.getString("cookie", null)
        File(Store.cache, "mixes.json").delete()
        return true
    }

    /** Signed out, continuation pages come back empty unless every request carries the same visitor data. */
    suspend fun ensureVisitorData() {
        if (YouTube.visitorData != null) return
        val v = YouTube.visitorData().getOrNull() ?: return
        YouTube.visitorData = v
        // Not Store.set(), which bumps the version and reloads every page.
        Store.prefs.edit().putString("visitorData", v).apply()
    }

    override suspend fun home(from: String?): Pair<String?, List<Section>> {
        ensureVisitorData()
        val p = YouTube.home(from).getOrThrow()
        return p.continuation to p.sections.map { sec ->
            val items = sec.items.mapNotNull { Store.romanizer.item(it) }
            Store.remember(items.mapNotNull { it.song })
            Section(Store.romanizer.text(sec.title), items)
        }
    }
    override val mixes = true
    override fun mixesIn(items: List<Item>) = personalMixes(items)
    @Composable override fun HomeTiles(mixes: List<Item>?, read: Boolean) = MixTiles(mixes, read)

    private val FILTERS = listOf(
        YouTube.SearchFilter.FILTER_SONG,
        YouTube.SearchFilter.FILTER_ALBUM,
        YouTube.SearchFilter.FILTER_ARTIST,
        YouTube.SearchFilter.FILTER_COMMUNITY_PLAYLIST,
    )

    override val searchIsLocal = false
    override suspend fun suggestions(query: String) = YouTube.searchSuggestions(query).getOrNull()?.queries.orEmpty()

    override suspend fun search(query: String, filter: Int, asTyped: String?, partial: suspend (Found) -> Unit): Found {
        val searchFilter = if (asTyped != null) YouTube.SearchFilter(asTyped) else FILTERS[filter]
        val r = YouTube.search(query, searchFilter).getOrThrow()
        val items = r.items
            .filter { it !is SongItem || !it.isVideoSong || filter != 0 }
            .mapNotNull { Store.romanizer.item(it) }
        Store.remember(items.mapNotNull { it.song })
        return Found(items, r.correctedQuery, r.autoCorrected, r.originalParams)
    }

    override val libraryReady get() = signedIn
    override val libraryHint = "Sign in to YouTube Music to see your playlists and albums."
    override val libraryHintPage = SIGN_IN
    private val LIBRARY = listOf("FEmusic_liked_playlists", "FEmusic_liked_albums", "FEmusic_library_corpus_track_artists")
    override fun libraryKey(tab: Int) = LIBRARY[tab]

    /** Loads every page of a library tab, because the first page holds only about 25 items. Each page shows as it arrives. */
    override suspend fun library(tab: Int, partial: suspend (List<Item>) -> Unit): List<Item> {
        val first = YouTube.library(LIBRARY[tab]).getOrThrow()
        val all = first.items.mapNotNull { Store.romanizer.item(it) }.toMutableList()
        var cont = first.continuation
        // ponytail: stops at 50 pages (about 1,250 items). Raise it if a library is bigger.
        repeat(50) {
            val c = cont ?: return@repeat
            partial(all.toList())
            val more = YouTube.libraryContinuation(c).getOrNull() ?: return@repeat
            all.addAll(more.items.mapNotNull { Store.romanizer.item(it) })
            cont = more.continuation
        }
        return all
    }

    override suspend fun detail(p: Page, partial: suspend (Detail) -> Unit): Detail {
        val r = Store.romanizer
        return when (p.kind) {
            "album" -> {
                val a = YouTube.album(p.id).getOrThrow()
                val artists = a.album.artists.orEmpty().joinToString(", ") { it.name }
                val songs = a.songs.map { r.song(it) }
                Store.remember(songs)
                val title = r.text(a.album.title, listOf(artists))
                val subtitle = listOfNotNull(r.text(artists).latin, a.album.year?.toString()).joinToString(" · ")
                Detail(title, subtitle, a.album.thumbnail, songs, emptyList())
            }
            "playlist" -> {
                val id = p.id.removePrefix("VL")
                val pl = YouTube.playlist(id).getOrThrow()
                val title = r.text(pl.playlist.title)
                val authorName = pl.playlist.author?.name
                val author = if (authorName == null) "" else r.text(authorName).latin
                // Generated playlists (RD…) loop, so their continuations return songs already listed.
                val songs = ArrayList<Song>()
                val seen = HashSet<String>()
                fun add(items: List<SongItem>) {
                    val fresh = items.filter { seen.add(it.id) }.map { r.song(it) }
                    songs += fresh
                    Store.remember(fresh)
                }
                add(pl.songs)
                var cont = pl.songsContinuation
                // ponytail: loads at most 10 continuation pages (about 1000 songs). Page on scroll if libraries get bigger.
                repeat(10) {
                    val c = cont ?: return@repeat
                    partial(Detail(title, author, pl.playlist.thumbnail, songs.toList(), emptyList()))
                    val more = YouTube.playlistContinuation(c).getOrNull() ?: return@repeat
                    add(more.songs)
                    cont = more.continuation
                }
                if (id == "LM") Playback.markLiked(songs.map { it.id })
                Detail(title, author, pl.playlist.thumbnail, songs, emptyList())
            }
            else -> {
                val a = YouTube.artist(p.id).getOrThrow()
                val songs = a.sections.flatMap { it.items }.filterIsInstance<SongItem>().map { r.song(it) }
                Store.remember(songs)
                val items = a.sections.flatMap { it.items }.filter { it !is SongItem }.mapNotNull { r.item(it) }
                val title = r.text(a.artist.title, artistId = a.artist.id)
                val subtitle = a.monthlyListenerCount ?: a.subscriberCountText ?: ""
                Detail(title, subtitle, a.artist.thumbnail, songs, items)
            }
        }
    }

    @Composable override fun OwnPage(id: String) = LoginScreen()

    override suspend fun radio(seed: Song, from: String?): SongPage? {
        val endpoint = WatchEndpoint(videoId = seed.id, playlistId = "RDAMVM${seed.id}")
        val page = YouTube.next(endpoint, from).getOrNull() ?: return null
        return SongPage(page.items.map { Store.romanizer.song(it) }, page.continuation)
    }

    override suspend fun relatedArtists(seed: Song): Set<String> {
        val id = seed.firstArtistId ?: return emptySet()
        val page = YouTube.artist(id).getOrNull() ?: return emptySet()
        return page.sections.flatMap { it.items }.filterIsInstance<ArtistItem>().map { it.id }.toSet()
    }

    override suspend fun playlist(id: String, from: String?): SongPage? {
        if (from != null) {
            val next = YouTube.playlistContinuation(from).getOrNull() ?: return null
            return SongPage(next.songs.map { Store.romanizer.song(it) }, next.continuation)
        }
        val page = YouTube.playlist(id.removePrefix("VL")).getOrNull() ?: return null
        return SongPage(page.songs.map { Store.romanizer.song(it) }, page.songsContinuation)
    }
    override val freeRadio = "Free: YouTube's autoplay as is"
    override val likedId = "LM"

    override val canLike get() = signedIn
    override fun like(id: String, like: Boolean) = kotlinx.coroutines.runBlocking { YouTube.likeVideo(id, like).isSuccess }
    override fun liked(id: String) = kotlinx.coroutines.runBlocking { YouTube.likeStatus(id).getOrNull() }

    // An album is saved through its audio playlist and an artist by subscribing. The page says which target to use
    // and its state.
    private val libraryTargets = java.util.concurrent.ConcurrentHashMap<String, String>()

    override fun saved(p: Page): InLibrary? {
        if (!signedIn || p.kind !in setOf("album", "artist")) return null
        val state = kotlinx.coroutines.runBlocking { YouTube.libraryState(p.id).getOrNull() } ?: return null
        val (saved, target) = state
        libraryTargets[p.id] = target
        return InLibrary(saved)
    }

    override fun save(p: Page, on: Boolean): Boolean {
        val target = libraryTargets[p.id] ?: return false
        return kotlinx.coroutines.runBlocking { YouTube.saveToLibrary(target, on).isSuccess }
    }

    override fun streamUrl(id: String, full: Boolean): String {
        // Without visitor data, YouTube refuses the direct path's client as a bot.
        kotlinx.coroutines.runBlocking { ensureVisitorData() }
        return Streams.audio(id, full = full).url
    }
    // YouTube's loudness is relative to its reference level.
    override fun loudness(id: String) = Streams.loudness(id)
    override val knowsLoudness = true
    override val hasVideos = true

    // Google-hosted art takes its size in the URL, and innertube hands over the largest thumbnail (often 544 px or
    // more, 80–115 KB). YouTube video stills come in fixed sizes. For the player, hqdefault (480 px) always exists.
    override fun thumbAt(url: String, px: Int): String {
        if ("googleusercontent.com/" !in url) return url
        return url.replace(SIZE_WH, "=w$px-h$px").replace(SIZE_S, "=s$px")
    }
    override fun bigThumb(url: String) = url.replace(SIZE_WH, "=w$BIG_PX-h$BIG_PX").replace(SMALL_STILL, "/hqdefault.jpg")
    // Compiled once, because thumbAt runs for every row drawn while a list scrolls, and bigThumb for each song of a
    // 1,000-song playlist.
    private val SIZE_WH = Regex("=w\\d+-h\\d+")
    private val SIZE_S = Regex("=s\\d+")
    private val SMALL_STILL = Regex("/(default|mqdefault)\\.jpg")

    override val lyricsProviders = allLyricsProviders
    override val lyrics: LyricsProvider = YouTubeLyrics
    override val lyricsRule = "All are asked at once. YouTube Music's text is the reference: word timing that matches it wins, then its own timed lyrics, then matching line timing, then its plain text. The order above only matters when YouTube Music has no lyrics."
    override val hasComments = true
    @Composable override fun Comments() = CommentsView()

    override fun shareUrl(s: Song) = link(s.id)
    override val canShare = true

    // The same video ID plays on both sites. On YouTube, a song is its "Topic" upload.
    fun link(videoId: String): String {
        val onMusic = Store.str("share.site", "music") == "music"
        return if (onMusic) "https://music.youtube.com/watch?v=$videoId" else "https://www.youtube.com/watch?v=$videoId"
    }

    // YouTube Music never hears of plays from roma, not even in its history. Last.fm does.
    override val lastFm = true
    override fun nowPlaying(s: Song) {}
    override fun played(s: roma.core.Scrobble) {}

    override val settingsTitle = "YouTube"
    @Composable override fun settingsSummary(): String {
        val site = if (Store.str("share.site", "music") == "music") "YouTube Music" else "YouTube"
        return (if (signedIn) "Signed in" else "Not signed in") + " · shared links open $site"
    }
    @Composable override fun Settings() = YouTubeSettings()
}
