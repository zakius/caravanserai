package com.zakius.caravanserai

import androidx.compose.runtime.Composable
import kotlinx.serialization.Serializable
import roma.core.Item
import roma.core.LyricsProvider
import roma.core.Song

@Serializable
class Section(val title: roma.core.Text, val items: List<Item>)

/**
 * An album, playlist or artist page. On an album, [discs] and [tracks] give each song's disc and track number where
 * the source knows them (0 or missing: unknown).
 */
@Serializable
class Detail(
    val title: roma.core.Text,
    val subtitle: String,
    val thumb: String?,
    val songs: List<Song>,
    val items: List<Item>,
    val discs: List<Int> = emptyList(),
    val tracks: List<Int> = emptyList(),
)

/**
 * Search results. [corrected], [autoCorrected] and [originalParams] describe the source's spelling correction, and
 * are left empty by sources that don't correct. [searching] marks first results handed over while the source still
 * looks for more: the screen shows a spinner under them, and says "No results" only of a search that has ended.
 */
class Found(
    val items: List<Item>,
    val corrected: String? = null,
    val autoCorrected: Boolean = false,
    val originalParams: String? = null,
    val searching: Boolean = false,
)

/** Songs and where the next page starts. [next] is null on the last page. */
class SongPage(val songs: List<Song>, val next: String?)

/**
 * Where music comes from. Each app has one: dragoman uses YouTube Music and caravan a Subsonic server. The build
 * flavor picks it as [backend] (docs/subsonic.md, "Two apps"). Names come back romanized, and songs that enter the
 * app are remembered in the local index. Besides the data, a source brings the UI that only it shows: its settings
 * page, Home's pinned tiles, its first-start step and its comments.
 */
interface Source {
    /** Shown where results are headed and in Settings. */
    val name: String
    /** The app's colour as ARGB. It is the icon's colour, and Root's Theme uses it as the accent when the system gives none. */
    val brandColor: Int
    /** Changes when the account or server does, because what Home holds belongs to that account. */
    val account: String?

    /** Called in Store.init, before the first frame, to read the source's own settings. */
    fun init() {}
    /** Called after the first frame for background work, such as a library sync or warming up the stream lookup. */
    fun start() {}
    /** Called when setting [key] changed. Returns true when what pages hold changes with it, as with the account. */
    fun changes(key: String) = false

    /** One page of Home's shelves, starting at [from], or the first page when [from] is null. */
    suspend fun home(from: String?): Pair<String?, List<Section>>
    /**
     * When true, Home's pinned row holds the account's personal mixes, collected from the shelves by [mixesIn].
     * Otherwise it holds the library's own tiles.
     */
    val mixes: Boolean
    /** The personal mixes among Home's [items], in their order. They show in the pinned row only. */
    fun mixesIn(items: List<Item>): List<Item> = emptyList()
    /**
     * Home's pinned row under its title. [mixes] is the last collected list, or null when it hasn't been read yet.
     * [read] turns true once the disk copy is in.
     */
    @Composable fun HomeTiles(mixes: List<Item>?, read: Boolean)

    /** The search runs over the local index, so Search shows no "Seen before" section above the results. */
    val searchIsLocal: Boolean
    suspend fun suggestions(query: String): List<String>
    /**
     * [filter] is Songs, Albums, Artists or Playlists, in the order Search lists them. [asTyped] holds the source's
     * params for searching without spelling correction.
     */
    suspend fun search(query: String, filter: Int, asTyped: String?, partial: suspend (Found) -> Unit): Found

    /** The Library tab has something to show. For YouTube Music that means signed in. */
    val libraryReady: Boolean
    /** The Library tab's tabs, in order. [library], [libraryKey] and [libraryEmpty] take an index into it. */
    val libraryTabs: List<String> get() = listOf("Playlists", "Albums", "Artists")
    /** What an empty Library tab [tab] says. */
    fun libraryEmpty(tab: Int): String
    /** What the Library tab says until it is [libraryReady]. A tap on it opens [libraryHintPage]. */
    val libraryHint: String
    val libraryHintPage: Page
    /** The key under which Library tab [tab] keeps its copy. */
    fun libraryKey(tab: Int): String
    suspend fun library(tab: Int, partial: suspend (List<Item>) -> Unit): List<Item>

    /** An album, playlist or artist page. */
    suspend fun detail(p: Page, partial: suspend (Detail) -> Unit): Detail
    /** A page of the source's own kind ([Page.kind] "source"), such as a sign-in. */
    @Composable fun OwnPage(id: String) {}

    /** A page of radio after [seed], or null when it couldn't load. */
    suspend fun radio(seed: Song, from: String?): SongPage?
    /** The seed's related artists' ids, for the radio's leash. */
    suspend fun relatedArtists(seed: Song): Set<String>
    /** The leash setting's loosest choice, which plays the source's radio as is. */
    val freeRadio: String
    /** A playlist or mix played as one: its first page, or the page at [from]. Null when it couldn't load. */
    suspend fun playlist(id: String, from: String? = null): SongPage?
    /** The id of the liked songs, played from Home's Liked tile. */
    val likedId: String

    val canLike: Boolean
    /**
     * The source's word for a liked song is a star (Subsonic), so Like reads Star and shows a star, and adding an
     * album or artist to the library reads Star too.
     */
    val stars: Boolean get() = false
    /** Blocking. Returns whether the server accepted it. */
    fun like(id: String, like: Boolean): Boolean
    /** Blocking. Null when the server couldn't say. */
    fun liked(id: String): Boolean?
    /** Blocking. Whether an album or artist page is in the library; null where the source can't add pages. */
    fun saved(p: Page): InLibrary? = null
    /** Blocking. Adds the page to the library or takes it out, and returns whether the server accepted it. */
    fun save(p: Page, on: Boolean) = false

    /** Blocking. The song's stream URL, called on the player's loader thread. */
    fun streamUrl(id: String, full: Boolean): String
    /** dB above the reference level, when known. */
    fun loudness(id: String): Float?
    /** The source knows songs' loudness, so Settings offers to even it out. */
    val knowsLoudness: Boolean
    /** The source has music videos as well as songs, so Settings offers to prefer the songs in radio. */
    val hasVideos: Boolean

    /** A stored picture URL resized to [px] pixels for lists. Returns [url] unchanged when the source can't size it. */
    fun thumbAt(url: String, px: Int): String = url
    /** The player's and the notification's picture, [BIG_PX] pixels or the nearest size the source has. */
    fun bigThumb(url: String): String = url
    /**
     * Blocking. The URL a stored picture URL is fetched from. Sources whose URLs need fresh credentials build a new
     * one here.
     */
    fun fetchUrl(url: String): String = url

    /** The lyrics providers Settings lists, in their default order. */
    val lyricsProviders: List<LyricsProvider>
    /** The source's own lyrics, which the other providers' text is matched against. Null when it has none. */
    val lyrics: LyricsProvider?
    /**
     * Part of the kept lyrics' file names, so lyrics asked for differently are stored apart. Subsonic, for example,
     * asks for different layers depending on the display.
     */
    val lyricsVariant: String get() = ""
    /**
     * The source sends names and lyrics already romanized (Subsonic's romanization extension), so no Japanese
     * dictionary is needed.
     */
    val romanizes: Boolean get() = false
    /** The line Settings shows under the providers, explaining how the lyrics are chosen. */
    val lyricsRule: String
    val hasComments: Boolean
    /** The player's comments view, for [hasComments] sources. */
    @Composable fun Comments() {}
    /** Blocking. A link to the song for Share, or null when there's none. */
    fun shareUrl(s: Song): String?
    /** Songs have links to share, so the song menu has Share. */
    val canShare: Boolean

    /**
     * When true, the app scrobbles this source's songs to Last.fm itself. Otherwise the source reports them
     * (docs/subsonic.md, "Scrobbling").
     */
    val lastFm: Boolean
    /** Called when a song of this source starts, to tell the source if it takes plays. */
    fun nowPlaying(s: Song)
    /** Called when a song of this source was listened to past the scrobble point. */
    fun played(s: roma.core.Scrobble)

    /**
     * Whether the source's first-start step, shown before the scripts question, is done or absent. It reads Compose
     * state, so read it in composition.
     */
    val setUp: Boolean get() = true
    @Composable fun Setup() {}

    /** The source's category in Settings: its title and summary, and the page it opens. */
    val settingsTitle: String
    @Composable fun settingsSummary(): String
    @Composable fun Settings()
}

/** [note] reports the download progress on servers that fetch what's added (oasis), and is null elsewhere. */
class InLibrary(val saved: Boolean, val note: String? = null)

/** Like, or Star on a source that stars ([Source.stars]). */
val likeWord get() = if (backend.stars) "Star" else "Like"

/** The action on a song that is [liked] or not: Like or Unlike, Star or Unstar. */
fun likeAction(liked: Boolean) = when {
    backend.stars -> if (liked) "Unstar" else "Star"
    else -> if (liked) "Unlike" else "Like"
}
