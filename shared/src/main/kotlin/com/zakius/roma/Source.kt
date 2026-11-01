package com.zakius.roma

import androidx.compose.runtime.Composable
import kotlinx.serialization.Serializable
import roma.core.Item
import roma.core.LyricsProvider
import roma.core.Song

@Serializable
class Section(val title: roma.core.Text, val items: List<Item>)

@Serializable
class Detail(val title: roma.core.Text, val subtitle: String, val thumb: String?, val songs: List<Song>, val items: List<Item>)

/** Search results. [corrected], [autoCorrected] and [originalParams] are the source's spelling correction; null elsewhere. */
class Found(val items: List<Item>, val corrected: String? = null, val autoCorrected: Boolean = false, val originalParams: String? = null)

/** Songs and where the next page starts: null on the last page. */
class SongPage(val songs: List<Song>, val next: String?)

/**
 * Where music comes from. Each app has one: roma YouTube Music, the Subsonic app a Subsonic server; the build flavor
 * picks it as [backend] (docs/subsonic.md, Two apps). Names come back romanized, and songs that enter the app are
 * remembered in the local index. Besides the data, a source brings what only it shows: its settings page, Home's
 * pinned tiles, its first-start step, its comments.
 */
interface Source {
    /** Shown where results are headed and in Settings. */
    val name: String
    /** The app's colour (ARGB): its icon's, and the accent where the system gives none (Root's Theme). */
    val brandColor: Int
    /** Changes when the account or server does: what Home holds belongs to it. */
    val account: String?

    /** In Store.init, before the first frame: the source's own settings in. */
    fun init() {}
    /** After the first frame: background work such as a library sync or warming up the stream lookup. */
    fun start() {}
    /** A setting changed: true when what pages hold changes with it (the account). */
    fun changes(key: String) = false

    /** One page of Home's shelves, from [from] (null: the first). */
    suspend fun home(from: String?): Pair<String?, List<Section>>
    /** Home's pinned row holds the account's personal mixes, collected from the shelves ([mixesIn]); otherwise the library's own tiles. */
    val mixes: Boolean
    /** The personal mixes among Home's [items], in their order; they show in the pinned row only. */
    fun mixesIn(items: List<Item>): List<Item> = emptyList()
    /** Home's pinned row under its title: [mixes] as last collected (null: not read yet), [read] once the disk copy is in. */
    @Composable fun HomeTiles(mixes: List<Item>?, read: Boolean)

    /** The search runs over the local index: then there's no "Seen before" section above it. */
    val searchIsLocal: Boolean
    suspend fun suggestions(query: String): List<String>
    /** [filter]: Songs, Albums, Artists, Playlists, as Search lists them. [asTyped]: the source's params without correction. */
    suspend fun search(query: String, filter: Int, asTyped: String?, partial: suspend (Found) -> Unit): Found

    /** The Library tab has something to show (YouTube Music: signed in). */
    val libraryReady: Boolean
    /** What the Library tab says until it is [libraryReady]; a tap opens [libraryHintPage]. */
    val libraryHint: String
    val libraryHintPage: Page
    /** Library tab [tab] (Playlists, Albums, Artists) as a key for its kept copy. */
    fun libraryKey(tab: Int): String
    suspend fun library(tab: Int, partial: suspend (List<Item>) -> Unit): List<Item>

    /** An album, playlist or artist page. */
    suspend fun detail(p: Page, partial: suspend (Detail) -> Unit): Detail
    /** A page of the source's own kind ([Page.kind] "source"), such as a sign-in. */
    @Composable fun OwnPage(id: String) {}

    /** A page of radio after [seed]; null when it couldn't load. */
    suspend fun radio(seed: Song, from: String?): SongPage?
    /** The seed's related artists' ids, for the radio's leash. */
    suspend fun relatedArtists(seed: Song): Set<String>
    /** The leash setting's loosest choice: the source's radio as is. */
    val freeRadio: String
    /** A playlist or mix played as one: its first page, or the page at [from]. Null when it couldn't load. */
    suspend fun playlist(id: String, from: String? = null): SongPage?
    /** The id of the liked songs, played from Home's Liked tile. */
    val likedId: String

    val canLike: Boolean
    /** Blocking. Whether the server took it. */
    fun like(id: String, like: Boolean): Boolean
    /** Blocking. Null when the server couldn't say. */
    fun liked(id: String): Boolean?
    /** Blocking. Whether an album or artist page is in the library; null where the source can't add pages. */
    fun saved(p: Page): InLibrary? = null
    /** Blocking. Adds the page to the library or takes it out; whether the server took it. */
    fun save(p: Page, on: Boolean) = false

    /** Blocking, on the player's loader thread: the song's stream URL. */
    fun streamUrl(id: String, full: Boolean): String
    /** dB above the reference level, when known. */
    fun loudness(id: String): Float?

    /** A stored picture URL at [px] pixels, as lists draw it; [url] when the source can't size it. */
    fun thumbAt(url: String, px: Int): String = url
    /** The player's and the notification's picture, [BIG_PX] pixels or the nearest size the source has. */
    fun bigThumb(url: String): String = url
    /** Blocking. Where a stored picture URL is fetched from: a URL made now, when it needs fresh credentials. */
    fun fetchUrl(url: String): String = url

    /** The lyrics providers Settings lists, in their default order. */
    val lyricsProviders: List<LyricsProvider>
    /** The source's own lyrics, the reference the other providers' text is matched against; null when it has none. */
    val lyrics: LyricsProvider?
    /** Settings' line under the providers: how the lyrics are chosen. */
    val lyricsRule: String
    val hasComments: Boolean
    /** The player's comments view, for [hasComments] sources. */
    @Composable fun Comments() {}
    /** Blocking. A link to the song for Share; null when there's none. */
    fun shareUrl(s: Song): String?

    /** The app scrobbles this source's songs to Last.fm itself; otherwise the source reports them (docs/subsonic.md, Scrobbling). */
    val lastFm: Boolean
    /** A song of this source started: tell the source, if it takes plays. */
    fun nowPlaying(s: Song)
    /** A song of this source was listened to past the scrobble point. */
    fun played(s: roma.core.Scrobble)

    /** The first-start step before the scripts question is done, or the source has none. Reads state: call it in composition. */
    val setUp: Boolean get() = true
    @Composable fun Setup() {}

    /** Settings' category for the source: its title and summary, and the page it opens. */
    val settingsTitle: String
    @Composable fun settingsSummary(): String
    @Composable fun Settings()
}

/** [note]: how getting it is going, from servers that fetch what's added (oasis); null elsewhere. */
class InLibrary(val saved: Boolean, val note: String? = null)
