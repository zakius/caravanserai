package com.metrolist.innertube

import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.BrowseEndpoint
import com.metrolist.innertube.models.ItemSectionRenderer
import com.metrolist.innertube.models.GridRenderer
import com.metrolist.innertube.models.MusicShelfRenderer
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SearchSuggestions
import com.metrolist.innertube.models.SectionListRenderer
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.models.YouTubeClient
import com.metrolist.innertube.models.YouTubeLocale
import com.metrolist.innertube.models.getContinuation
import com.metrolist.innertube.models.getItems
import com.metrolist.innertube.models.response.BrowseResponse
import com.metrolist.innertube.models.response.GetSearchSuggestionsResponse
import com.metrolist.innertube.models.response.NextResponse
import com.metrolist.innertube.models.response.PlayerResponse
import com.metrolist.innertube.models.response.SearchResponse
import com.metrolist.innertube.pages.AlbumPage
import com.metrolist.innertube.pages.ArtistPage
import com.metrolist.innertube.pages.HomePage
import com.metrolist.innertube.pages.LibraryContinuationPage
import com.metrolist.innertube.pages.LibraryPage
import com.metrolist.innertube.pages.NextPage
import com.metrolist.innertube.pages.NextResult
import com.metrolist.innertube.pages.PageHelper
import com.metrolist.innertube.pages.PlaylistContinuationPage
import com.metrolist.innertube.pages.PlaylistPage
import com.metrolist.innertube.pages.SearchPage
import com.metrolist.innertube.pages.SearchResult
import com.metrolist.innertubex.models.YouTubeClient.Companion.WEB
import com.metrolist.innertubex.models.YouTubeClient.Companion.WEB_REMIX
import com.metrolist.innertubex.body
import timber.log.Timber

/**
 * Parse useful data with [InnerTube] sending requests.
 * Modified from [ViMusic](https://github.com/vfsfitvnm/ViMusic)
 */
object YouTube {
    private val innerTube = InnerTube()

    var locale: YouTubeLocale
        get() = innerTube.locale
        set(value) {
            innerTube.locale = value
        }
    var visitorData: String?
        get() = innerTube.visitorData
        set(value) {
            innerTube.visitorData = value
        }
    var dataSyncId: String?
        get() = innerTube.dataSyncId
        set(value) {
            innerTube.dataSyncId = value
        }
    var authUser: String
        get() = innerTube.authUser
        set(value) {
            innerTube.authUser = value
        }
    var cookie: String?
        get() = innerTube.cookie
        set(value) {
            innerTube.cookie = value
        }
    var useLoginForBrowse: Boolean
        get() = innerTube.useLoginForBrowse
        set(value) {
            innerTube.useLoginForBrowse = value
        }

    fun extractionTransport() = innerTube.extractionTransport()

    suspend fun searchSuggestions(query: String): Result<SearchSuggestions> =
        runCatching {
            val response = innerTube.getSearchSuggestions(WEB_REMIX, query).body<GetSearchSuggestionsResponse>()
            SearchSuggestions(
                queries =
                    response.contents
                        ?.getOrNull(0)
                        ?.searchSuggestionsSectionRenderer
                        ?.contents
                        ?.mapNotNull { content ->
                            content.searchSuggestionRenderer
                                ?.suggestion
                                ?.runs
                                ?.joinToString(separator = "") { it.text }
                        }.orEmpty(),
            )
        }

    suspend fun search(
        query: String,
        filter: SearchFilter,
        recordSearchHistory: Boolean = false,
    ): Result<SearchResult> =
        runCatching {
            val response = innerTube.search(WEB_REMIX, query, filter.value, setLogin = recordSearchHistory).body<SearchResponse>()
            val searchItems = mutableListOf<YTItem>()
            var searchContinuation: String? = null
            var correction: ItemSectionRenderer.Correction? = null
            var autoCorrected = false

            response.contents
                ?.tabbedSearchResultsRenderer
                ?.tabs
                ?.firstOrNull()
                ?.tabRenderer
                ?.content
                ?.sectionListRenderer
                ?.contents
                ?.forEach { section ->
                    if (section.musicShelfRenderer != null) {
                        val shelf = section.musicShelfRenderer
                        val items = shelf.contents?.getItems()?.mapNotNull { SearchPage.toYTItem(it) } ?: emptyList()
                        searchItems.addAll(items)
                        if (searchContinuation == null) {
                            searchContinuation = shelf.continuations?.getContinuation()
                        }
                    } else if (section.itemSectionRenderer != null) {
                        val items =
                            section.itemSectionRenderer.contents
                                ?.mapNotNull { it.musicResponsiveListItemRenderer }
                                ?.mapNotNull { SearchPage.toYTItem(it) }
                                ?: emptyList()
                        searchItems.addAll(items)
                        section.itemSectionRenderer.contents?.forEach { c ->
                            c.showingResultsForRenderer?.let { correction = it; autoCorrected = true }
                            c.didYouMeanRenderer?.let { correction = it }
                        }
                    }
                }

            SearchResult(
                items = searchItems.distinctBy { it.id },
                correctedQuery = correction?.correctedQuery?.runs?.joinToString("") { it.text },
                autoCorrected = autoCorrected,
                originalParams = correction?.originalQueryEndpoint?.searchEndpoint?.params,
            )
        }

    suspend fun album(
        browseId: String,
        withSongs: Boolean = true,
    ): Result<AlbumPage> =
        runCatching {
            val response = innerTube.browse(WEB_REMIX, browseId).body<BrowseResponse>()
            if (browseId.contains("FEmusic_library_privately_owned_release_detail")) {
                val playlistId =
                    response.header
                        ?.musicDetailHeaderRenderer
                        ?.menu
                        ?.menuRenderer
                        ?.topLevelButtons
                        ?.firstOrNull()
                        ?.buttonRenderer
                        ?.navigationEndpoint
                        ?.watchPlaylistEndpoint
                        ?.playlistId!!
                val albumItem =
                    AlbumItem(
                        browseId = browseId,
                        playlistId = playlistId,
                        title =
                            response.header.musicDetailHeaderRenderer.title.runs
                                ?.firstOrNull()
                                ?.text!!,
                        artists =
                            PageHelper
                                .extractArtists(response.header.musicDetailHeaderRenderer.subtitle.runs)
                                .takeIf { it.isNotEmpty() },
                        year =
                            response.header.musicDetailHeaderRenderer.subtitle.runs
                                ?.lastOrNull()
                                ?.text
                                ?.toIntOrNull(),
                        thumbnail =
                            response.header.musicDetailHeaderRenderer.thumbnail.croppedSquareThumbnailRenderer
                                ?.thumbnail
                                ?.thumbnails
                                ?.lastOrNull()!!
                                .url,
                    )
                return@runCatching AlbumPage(
                    album = albumItem,
                    songs =
                        response.contents
                            ?.singleColumnBrowseResultsRenderer
                            ?.tabs
                            ?.firstOrNull()
                            ?.tabRenderer
                            ?.content
                            ?.sectionListRenderer
                            ?.contents
                            ?.firstOrNull()
                            ?.musicShelfRenderer
                            ?.contents
                            ?.getItems()
                            ?.mapNotNull {
                                AlbumPage.getSong(it, albumItem)
                            }!!
                            .toMutableList(),
                )
            } else {
                val playlistId =
                    response.microformat
                        ?.microformatDataRenderer
                        ?.urlCanonical
                        ?.substringAfterLast('=')!!
                val albumItem =
                    AlbumItem(
                        browseId = browseId,
                        playlistId = playlistId,
                        title =
                            response.contents
                                ?.twoColumnBrowseResultsRenderer
                                ?.tabs
                                ?.firstOrNull()
                                ?.tabRenderer
                                ?.content
                                ?.sectionListRenderer
                                ?.contents
                                ?.firstOrNull()
                                ?.musicResponsiveHeaderRenderer
                                ?.title
                                ?.runs
                                ?.firstOrNull()
                                ?.text!!,
                        artists =
                            response.contents.twoColumnBrowseResultsRenderer.tabs
                                .firstOrNull()
                                ?.tabRenderer
                                ?.content
                                ?.sectionListRenderer
                                ?.contents
                                ?.firstOrNull()
                                ?.musicResponsiveHeaderRenderer
                                ?.straplineTextOne
                                ?.runs
                                ?.let(PageHelper::extractArtists)
                                ?.takeIf { it.isNotEmpty() },
                        year =
                            response.contents.twoColumnBrowseResultsRenderer.tabs
                                .firstOrNull()
                                ?.tabRenderer
                                ?.content
                                ?.sectionListRenderer
                                ?.contents
                                ?.firstOrNull()
                                ?.musicResponsiveHeaderRenderer
                                ?.subtitle
                                ?.runs
                                ?.lastOrNull()
                                ?.text
                                ?.toIntOrNull(),
                        thumbnail =
                            response.contents.twoColumnBrowseResultsRenderer.tabs
                                .firstOrNull()
                                ?.tabRenderer
                                ?.content
                                ?.sectionListRenderer
                                ?.contents
                                ?.firstOrNull()
                                ?.musicResponsiveHeaderRenderer
                                ?.thumbnail
                                ?.musicThumbnailRenderer
                                ?.thumbnail
                                ?.thumbnails
                                ?.lastOrNull()
                                ?.url!!,
                    )
                val albumSongsList =
                    if (withSongs) {
                        albumSongs(
                            playlistId,
                            albumItem,
                        ).getOrThrow()
                    } else {
                        emptyList()
                    }
                // When YouTube credits the album to a label/distributor channel (the header
                // strapline) but every track names the same performing artist, surface the
                // performer as the album artist instead of the label.
                val performer =
                    albumSongsList.firstOrNull()?.artists?.firstOrNull()?.takeIf { first ->
                        first.name.isNotBlank() &&
                            albumSongsList.all { it.artists.firstOrNull()?.name == first.name }
                    }
                val resolvedAlbum =
                    if (performer != null && albumItem.artists?.any { it.name == performer.name } != true) {
                        albumItem.copy(artists = listOf(performer))
                    } else {
                        albumItem
                    }
                return@runCatching AlbumPage(
                    album = resolvedAlbum,
                    songs = albumSongsList,
                )
            }
        }

    suspend fun albumSongs(
        playlistId: String,
        album: AlbumItem? = null,
    ): Result<List<SongItem>> =
        runCatching {
            var response = innerTube.browse(WEB_REMIX, "VL$playlistId").body<BrowseResponse>()
            val shelf =
                response.contents
                    ?.twoColumnBrowseResultsRenderer
                    ?.secondaryContents
                    ?.sectionListRenderer
                    ?.contents
                    ?.firstOrNull()
            val shelfContents =
                shelf?.musicPlaylistShelfRenderer?.contents
                    ?: shelf?.musicShelfRenderer?.contents
                    ?: emptyList()
            val songs =
                shelfContents
                    .getItems()
                    .mapNotNull {
                        AlbumPage.getSong(it, album)
                    }
                    .toMutableList()
            var continuation =
                shelfContents
                    .getContinuation()
            val seenContinuations = mutableSetOf<String>()
            var requestCount = 0
            val maxRequests = 50 // Prevent excessive API calls

            while (continuation != null && requestCount < maxRequests) {
                // Prevent infinite loops by tracking seen continuations
                if (continuation in seenContinuations) {
                    break
                }
                seenContinuations.add(continuation)
                requestCount++

                response =
                    innerTube
                        .browse(
                            client = WEB_REMIX,
                            continuation = continuation,
                        ).body<BrowseResponse>()
                songs +=
                    response.onResponseReceivedActions
                        ?.firstOrNull()
                        ?.appendContinuationItemsAction
                        ?.continuationItems
                        ?.getItems()
                        ?.mapNotNull {
                            AlbumPage.getSong(it, album)
                        }.orEmpty()
                continuation =
                    response.continuationContents
                        ?.musicPlaylistShelfContinuation
                        ?.continuations
                        ?.getContinuation()
            }
            songs
        }

    suspend fun artist(browseId: String): Result<ArtistPage> =
        runCatching {
            val response = innerTube.browse(WEB_REMIX, browseId).body<BrowseResponse>()

            ArtistPage(
                artist =
                    ArtistItem(
                        id = browseId,
                        title =
                            response.header
                                ?.musicImmersiveHeaderRenderer
                                ?.title
                                ?.runs
                                ?.firstOrNull()
                                ?.text
                                ?: response.header
                                    ?.musicVisualHeaderRenderer
                                    ?.title
                                    ?.runs
                                    ?.firstOrNull()
                                    ?.text
                                ?: response.header
                                    ?.musicHeaderRenderer
                                    ?.title
                                    ?.runs
                                    ?.firstOrNull()
                                    ?.text!!,
                        thumbnail =
                            response.header
                                ?.musicImmersiveHeaderRenderer
                                ?.thumbnail
                                ?.musicThumbnailRenderer
                                ?.getThumbnailUrl()
                                ?: response.header
                                    ?.musicVisualHeaderRenderer
                                    ?.foregroundThumbnail
                                    ?.musicThumbnailRenderer
                                    ?.getThumbnailUrl()
                                ?: response.header
                                    ?.musicDetailHeaderRenderer
                                    ?.thumbnail
                                    ?.musicThumbnailRenderer
                                    ?.getThumbnailUrl(),
                        shuffleEndpoint =
                            response.header
                                ?.musicImmersiveHeaderRenderer
                                ?.playButton
                                ?.buttonRenderer
                                ?.navigationEndpoint
                                ?.watchEndpoint
                                ?: response.contents
                                    ?.singleColumnBrowseResultsRenderer
                                    ?.tabs
                                    ?.firstOrNull()
                                    ?.tabRenderer
                                    ?.content
                                    ?.sectionListRenderer
                                    ?.contents
                                    ?.firstOrNull()
                                    ?.musicShelfRenderer
                                    ?.contents
                                    ?.firstOrNull()
                                    ?.musicResponsiveListItemRenderer
                                    ?.navigationEndpoint
                                    ?.watchPlaylistEndpoint,
                        radioEndpoint =
                            response.header
                                ?.musicImmersiveHeaderRenderer
                                ?.startRadioButton
                                ?.buttonRenderer
                                ?.navigationEndpoint
                                ?.watchEndpoint,
                    ),
                sections =
                    response.contents
                        ?.singleColumnBrowseResultsRenderer
                        ?.tabs
                        ?.firstOrNull()
                        ?.tabRenderer
                        ?.content
                        ?.sectionListRenderer
                        ?.contents
                        ?.mapNotNull(ArtistPage::fromSectionListRendererContent)!!,
                subscriberCountText =
                    response.header
                        ?.musicImmersiveHeaderRenderer
                        ?.subscriptionButton2
                        ?.subscribeButtonRenderer
                        ?.subscriberCountWithSubscribeText
                        ?.runs
                        ?.firstOrNull()
                        ?.text
                        ?: response.header
                            ?.musicImmersiveHeaderRenderer
                            ?.subscriptionButton
                            ?.subscribeButtonRenderer
                            ?.longSubscriberCountText
                            ?.runs
                            ?.firstOrNull()
                            ?.text
                        ?: response.header
                            ?.musicImmersiveHeaderRenderer
                            ?.subscriptionButton
                            ?.subscribeButtonRenderer
                            ?.shortSubscriberCountText
                            ?.runs
                            ?.firstOrNull()
                            ?.text,
                monthlyListenerCount =
                    response.header
                        ?.musicImmersiveHeaderRenderer
                        ?.monthlyListenerCount
                        ?.runs
                        ?.firstOrNull()
                        ?.text,
            )
        }

    suspend fun playlist(playlistId: String): Result<PlaylistPage> =
        runCatching {
            val response =
                innerTube
                    .browse(
                        client = WEB_REMIX,
                        browseId = "VL$playlistId",
                        setLogin = true,
                    ).body<BrowseResponse>()
            val base =
                response.contents
                    ?.twoColumnBrowseResultsRenderer
                    ?.tabs
                    ?.firstOrNull()
                    ?.tabRenderer
                    ?.content
                    ?.sectionListRenderer
                    ?.contents
                    ?.firstOrNull()
            val header =
                base?.musicResponsiveHeaderRenderer
                    ?: base?.musicEditablePlaylistDetailHeaderRenderer?.header?.musicResponsiveHeaderRenderer

            val description: String? =
                header?.description?.musicDescriptionShelfRenderer?.description?.runs?.joinToString("") { it.text }
                    ?: base?.musicEditablePlaylistDetailHeaderRenderer
                        ?.header?.musicDetailHeaderRenderer
                        ?.description?.runs?.joinToString("") { it.text }
                    ?: response.header?.musicDetailHeaderRenderer
                        ?.description?.runs?.joinToString("") { it.text }

            val author: Artist? = run {
                val fromStrapline = header?.straplineTextOne?.runs
                    ?.firstOrNull()
                    ?.let { Artist(name = it.text, id = it.navigationEndpoint?.browseEndpoint?.browseId) }
                if (fromStrapline != null) return@run fromStrapline

                    val detailSubtitle =
                        base
                            ?.musicEditablePlaylistDetailHeaderRenderer
                            ?.header
                            ?.musicDetailHeaderRenderer
                            ?.subtitle
                            ?.runs
                            ?: response.header
                                ?.musicDetailHeaderRenderer
                                ?.subtitle
                                ?.runs
                    val fromDetail = PageHelper.extractArtists(detailSubtitle).firstOrNull()
                    if (fromDetail != null) return@run fromDetail

                    val fromHeaderSubtitle = PageHelper.extractArtists(header?.subtitle?.runs).firstOrNull()
                    if (fromHeaderSubtitle != null) return@run fromHeaderSubtitle

                val facepile = header?.facepile?.avatarStackViewModel
                if (facepile != null) {
                    val name = facepile.text?.content
                    val browseId = facepile.rendererContext?.commandContext?.onTap?.innertubeCommand?.browseEndpoint?.browseId
                    if (name != null) return@run Artist(name = name, id = browseId)
                }

                val fromMusicHeaderStrapline = response.header?.musicHeaderRenderer
                    ?.straplineTextOne?.runs?.firstOrNull()
                    ?.let { Artist(name = it.text, id = it.navigationEndpoint?.browseEndpoint?.browseId) }
                if (fromMusicHeaderStrapline != null) return@run fromMusicHeaderStrapline

                PlaylistPage.ownerFromDetailSubtitle(detailSubtitle)
            }

            PlaylistPage(
                playlist =
                    PlaylistItem(
                        id = playlistId,
                        title =
                            header?.title?.runs?.firstOrNull()?.text
                                ?: response.header?.musicHeaderRenderer?.title?.runs?.firstOrNull()?.text
                                ?: "",
                        author = author,
                        songCountText =
                            (header?.secondSubtitle
                                ?: response.header?.musicHeaderRenderer?.secondSubtitle)
                                ?.runs
                                ?.findLast {
                                    it.text.any { c -> c.isDigit() } &&
                                        !it.text.contains("view", ignoreCase = true) &&
                                        !it.text.contains("hour", ignoreCase = true) &&
                                        !it.text.contains("minute", ignoreCase = true)
                                }?.text,
                        thumbnail =
                            header?.thumbnail
                                ?.musicThumbnailRenderer
                                ?.thumbnail
                                ?.thumbnails
                                ?.lastOrNull()
                                ?.url
                                ?: response.header
                                    ?.musicHeaderRenderer
                                    ?.thumbnail
                                    ?.musicThumbnailRenderer
                                    ?.thumbnails
                                    ?.lastOrNull()
                                    ?.url
                                ?: "",
                        playEndpoint = null,
                        shuffleEndpoint =
                            header?.buttons?.lastOrNull()
                                ?.menuRenderer?.items?.firstOrNull()
                                ?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint
                                ?: response.header?.musicHeaderRenderer?.buttons?.lastOrNull()
                                    ?.menuRenderer?.items?.firstOrNull()
                                    ?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint,
                        radioEndpoint =
                            header?.buttons?.getOrNull(2)
                                ?.menuRenderer?.items?.find {
                                    it.menuNavigationItemRenderer?.icon?.iconType == "MIX"
                                }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint
                                ?: response.header?.musicHeaderRenderer?.buttons?.getOrNull(2)
                                    ?.menuRenderer?.items?.find {
                                        it.menuNavigationItemRenderer?.icon?.iconType == "MIX"
                                    }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint,
                    ),
                songs = run {
                    val twoColShelf =
                        response.contents
                            ?.twoColumnBrowseResultsRenderer
                            ?.secondaryContents
                            ?.sectionListRenderer
                            ?.contents
                            ?.firstOrNull()
                    val twoColContents =
                        twoColShelf?.musicPlaylistShelfRenderer?.contents
                            ?: twoColShelf?.musicShelfRenderer?.contents
                    val singleColShelf =
                        response.contents
                            ?.singleColumnBrowseResultsRenderer
                            ?.tabs
                            ?.firstOrNull()
                            ?.tabRenderer
                            ?.content
                            ?.sectionListRenderer
                            ?.contents
                            ?.firstOrNull()
                    val singleColContents =
                        singleColShelf?.musicPlaylistShelfRenderer?.contents
                            ?: singleColShelf?.musicShelfRenderer?.contents
                    (twoColContents ?: singleColContents)
                        ?.getItems()
                        ?.mapNotNull { PlaylistPage.fromMusicResponsiveListItemRenderer(it) }
                        ?: emptyList()
                },
                songsContinuation = run {
                    val twoColShelf =
                        response.contents
                            ?.twoColumnBrowseResultsRenderer
                            ?.secondaryContents
                            ?.sectionListRenderer
                            ?.contents
                            ?.firstOrNull()
                    val twoColContents =
                        twoColShelf?.musicPlaylistShelfRenderer?.contents
                            ?: twoColShelf?.musicShelfRenderer?.contents
                    val twoColContinuations =
                        twoColShelf?.musicPlaylistShelfRenderer?.continuations
                            ?: twoColShelf?.musicShelfRenderer?.continuations
                    val singleColShelf =
                        response.contents
                            ?.singleColumnBrowseResultsRenderer
                            ?.tabs
                            ?.firstOrNull()
                            ?.tabRenderer
                            ?.content
                            ?.sectionListRenderer
                            ?.contents
                            ?.firstOrNull()
                    val singleColContents =
                        singleColShelf?.musicPlaylistShelfRenderer?.contents
                            ?: singleColShelf?.musicShelfRenderer?.contents
                    val singleColContinuations =
                        singleColShelf?.musicPlaylistShelfRenderer?.continuations
                            ?: singleColShelf?.musicShelfRenderer?.continuations
                    val mergedContents = twoColContents ?: singleColContents
                    val mergedContinuations = twoColContinuations ?: singleColContinuations
                    mergedContents?.getContinuation() ?: mergedContinuations?.getContinuation()
                },
            )
        }

    suspend fun playlistContinuation(continuation: String): Result<PlaylistContinuationPage> =
        runCatching {
            val response =
                innerTube
                    .browse(
                        client = WEB_REMIX,
                        continuation = continuation,
                        setLogin = true,
                    ).body<BrowseResponse>()

            val mainContents: List<MusicShelfRenderer.Content> =
                response.continuationContents
                    ?.sectionListContinuation
                    ?.contents
                    ?.mapNotNull { content: SectionListRenderer.Content ->
                        content.musicPlaylistShelfRenderer?.contents
                            ?: content.musicShelfRenderer?.contents
                    }
                    ?.flatten()
                    ?: emptyList()

            val shelfContents: List<MusicShelfRenderer.Content> =
                response.continuationContents?.musicPlaylistShelfContinuation?.contents ?: emptyList()

            val musicShelfContinuationContents: List<MusicShelfRenderer.Content> =
                response.continuationContents?.musicShelfContinuation?.contents ?: emptyList()

            val appendedContents: List<MusicShelfRenderer.Content> =
                response.onResponseReceivedActions
                    .orEmpty()
                    .flatMap { it.appendContinuationItemsAction?.continuationItems.orEmpty() }

            val allContents = mainContents + shelfContents + musicShelfContinuationContents + appendedContents

            val songs =
                allContents
                    .mapNotNull { content: MusicShelfRenderer.Content -> content.musicResponsiveListItemRenderer }
                    .mapNotNull { renderer -> PlaylistPage.fromMusicResponsiveListItemRenderer(renderer) }

            val nextContinuation =
                response.continuationContents
                    ?.sectionListContinuation
                    ?.continuations
                    ?.getContinuation()
                    ?: response.continuationContents
                        ?.musicPlaylistShelfContinuation
                        ?.continuations
                        ?.getContinuation()
                    ?: response.continuationContents
                        ?.musicShelfContinuation
                        ?.continuations
                        ?.getContinuation()
                    ?: appendedContents.getContinuation()

            PlaylistContinuationPage(
                songs = songs,
                continuation = nextContinuation,
            )
        }

    suspend fun home(
        continuation: String? = null,
        params: String? = null,
    ): Result<HomePage> =
        runCatching {
            Timber.d("home() called with continuation=$continuation, params=$params")
            if (continuation != null) {
                return@runCatching homeContinuation(continuation).getOrThrow()
            }

            val response = innerTube.browse(WEB_REMIX, browseId = "FEmusic_home", params = params).body<BrowseResponse>()
            Timber.d("home() response received")
            val continuation =
                response.contents
                    ?.singleColumnBrowseResultsRenderer
                    ?.tabs
                    ?.firstOrNull()
                    ?.tabRenderer
                    ?.content
                    ?.sectionListRenderer
                    ?.continuations
                    ?.getContinuation()
            val sectionListRender =
                response.contents
                    ?.singleColumnBrowseResultsRenderer
                    ?.tabs
                    ?.firstOrNull()
                    ?.tabRenderer
                    ?.content
                    ?.sectionListRenderer
            Timber.d("home() sectionListRender contents size: ${sectionListRender?.contents?.size ?: 0}")
            val carousels = sectionListRender?.contents?.mapNotNull { it.musicCarouselShelfRenderer } ?: emptyList()
            Timber.d("home() carousels count: ${carousels.size}")
            val sections =
                carousels
                    .mapNotNull {
                        HomePage.Section.fromMusicCarouselShelfRenderer(it)
                    }.toMutableList()
            Timber.d("home() sections parsed: ${sections.size}")
            HomePage(sections, continuation)
        }

    private suspend fun homeContinuation(continuation: String): Result<HomePage> =
        runCatching {
            val response =
                innerTube.browse(WEB_REMIX, continuation = continuation).body<BrowseResponse>()
            val continuation =
                response.continuationContents
                    ?.sectionListContinuation
                    ?.continuations
                    ?.getContinuation()
            HomePage(
                response.continuationContents
                    ?.sectionListContinuation
                    ?.contents
                    ?.mapNotNull { it.musicCarouselShelfRenderer }
                    ?.mapNotNull {
                        HomePage.Section.fromMusicCarouselShelfRenderer(it)
                    }.orEmpty(),
                continuation,
            )
        }

    suspend fun library(
        browseId: String,
        tabIndex: Int = 0,
    ): Result<LibraryPage> =
        runCatching {
            val response =
                innerTube
                    .browse(
                        client = WEB_REMIX,
                        browseId = browseId,
                        setLogin = true,
                    ).body<BrowseResponse>()

            val isUploaded = browseId.startsWith("FEmusic_library_privately_owned")
            val tab =
                response.contents
                    ?.singleColumnBrowseResultsRenderer
                    ?.tabs
                    ?.getOrNull(tabIndex)
                    ?: response.contents
                        ?.twoColumnBrowseResultsRenderer
                        ?.tabs
                        ?.getOrNull(tabIndex)
            val contents =
                tab
                    ?.tabRenderer
                    ?.content
                    ?.sectionListRenderer
                    ?.contents
                    ?.firstOrNull {
                        it.gridRenderer != null ||
                            it.musicShelfRenderer != null ||
                            it.musicPlaylistShelfRenderer != null ||
                            it.itemSectionRenderer?.contents.orEmpty().any { child ->
                                child.gridRenderer != null ||
                                    child.musicShelfRenderer != null ||
                                    child.musicPlaylistShelfRenderer != null
                            }
                    }
            val nestedContents = contents?.itemSectionRenderer?.contents.orEmpty()
            val gridRenderer = contents?.gridRenderer
                ?: nestedContents.firstNotNullOfOrNull { it.gridRenderer }
            val playlistShelfRenderer = contents?.musicPlaylistShelfRenderer
                ?: nestedContents.firstNotNullOfOrNull { it.musicPlaylistShelfRenderer }
            val musicShelfRenderer = contents?.musicShelfRenderer
                ?: nestedContents.firstNotNullOfOrNull { it.musicShelfRenderer }

            when {
                gridRenderer != null -> {
                    val gridItems = gridRenderer.items
                    val parsedItems =
                        gridItems
                            .mapNotNull(GridRenderer.Item::musicTwoRowItemRenderer)
                            .mapNotNull { LibraryPage.fromMusicTwoRowItemRenderer(it) }
                    LibraryPage(
                        items = parsedItems,
                        continuation = gridRenderer.continuations?.getContinuation(),
                    )
                }

                playlistShelfRenderer != null -> {
                    LibraryPage(
                        items =
                            playlistShelfRenderer.contents
                                .getItems()
                                .mapNotNull { LibraryPage.fromMusicResponsiveListItemRenderer(it, isUploaded) },
                        continuation = playlistShelfRenderer.continuations?.getContinuation(),
                    )
                }

                musicShelfRenderer != null -> {
                    val listItemRenderers = musicShelfRenderer.contents.orEmpty()
                        .mapNotNull(MusicShelfRenderer.Content::musicResponsiveListItemRenderer)
                    val parsedItems =
                        listItemRenderers.mapNotNull { renderer ->
                            LibraryPage.fromMusicResponsiveListItemRenderer(renderer, isUploaded)
                        }
                    LibraryPage(
                        items = parsedItems,
                        continuation = musicShelfRenderer.continuations?.getContinuation(),
                    )
                }

                else -> LibraryPage(items = emptyList(), continuation = null)
            }
        }

    suspend fun libraryContinuation(
        continuation: String,
        isUploaded: Boolean = false,
    ) = runCatching {
        val response =
            innerTube
                .browse(
                    client = WEB_REMIX,
                    continuation = continuation,
                    setLogin = true,
                ).body<BrowseResponse>()

            val contents = response.continuationContents

            when {
                contents?.gridContinuation != null -> {
                    LibraryContinuationPage(
                        items =
                            contents.gridContinuation.items
                                .mapNotNull(GridRenderer.Item::musicTwoRowItemRenderer)
                                .mapNotNull { LibraryPage.fromMusicTwoRowItemRenderer(it) },
                        continuation = contents.gridContinuation.continuations?.getContinuation(),
                    )
                }

                else -> { // contents?.musicShelfContinuation != null
                    LibraryContinuationPage(
                        items =
                            contents
                                ?.musicShelfContinuation
                                ?.contents!!
                                .mapNotNull(MusicShelfRenderer.Content::musicResponsiveListItemRenderer)
                                .mapNotNull { LibraryPage.fromMusicResponsiveListItemRenderer(it, isUploaded) },
                        continuation = contents.musicShelfContinuation.continuations?.getContinuation(),
                    )
                }
            }
        }

    suspend fun likeVideo(
        videoId: String,
        like: Boolean,
    ) = runCatching {
        if (like) {
            innerTube.likeVideo(WEB_REMIX, videoId)
        } else {
            innerTube.unlikeVideo(WEB_REMIX, videoId)
        }
    }

    suspend fun player(
        videoId: String,
        playlistId: String? = null,
        client: YouTubeClient,
        signatureTimestamp: Int? = null,
        poToken: String? = null,
    ): Result<PlayerResponse> =
        runCatching {
            innerTube.player(client, videoId, playlistId, signatureTimestamp, poToken).body<PlayerResponse>()
        }

    /** The WEB client's raw `next` answer: a video's watch data, or a continuation of it (comments, replies). */
    suspend fun nextJson(videoId: String?, continuation: String? = null): Result<kotlinx.serialization.json.JsonObject> =
        runCatching {
            innerTube.next(WEB, videoId, null, null, null, null, continuation).body<kotlinx.serialization.json.JsonObject>()
        }

    /**
     * Whether the signed-in account has an album or artist page in its library: the album saved, the artist subscribed.
     * Also what to send to change it: the album's audio playlist id or the artist's channel id. Null when YouTube didn't say.
     */
    suspend fun libraryState(browseId: String): Result<Pair<Boolean, String>?> =
        runCatching {
            // Library artist pages (MPLA…) have no subscribe button; their channel page does.
            val json = innerTube.browse(WEB_REMIX, browseId.removePrefix("MPLA"), setLogin = true).body<kotlinx.serialization.json.JsonObject>()
            fun all(e: kotlinx.serialization.json.JsonElement, key: String): Sequence<kotlinx.serialization.json.JsonObject> = when (e) {
                is kotlinx.serialization.json.JsonObject -> e.entries.asSequence().flatMap { (k, v) -> (if (k == key && v is kotlinx.serialization.json.JsonObject) sequenceOf(v) else emptySequence()) + all(v, key) }
                is kotlinx.serialization.json.JsonArray -> e.asSequence().flatMap { all(it, key) }
                else -> emptySequence()
            }
            fun kotlinx.serialization.json.JsonObject.at(vararg path: String) = path.fold<String, kotlinx.serialization.json.JsonElement?>(this) { o, k -> (o as? kotlinx.serialization.json.JsonObject)?.get(k) }
            fun kotlinx.serialization.json.JsonElement?.text() = (this as? kotlinx.serialization.json.JsonPrimitive)?.content
            all(json, "subscribeButtonRenderer").firstNotNullOfOrNull { b ->
                b.at("channelId").text()?.let { (b.at("subscribed").text() == "true") to it }
            } ?: all(json, "toggleButtonRenderer").firstNotNullOfOrNull { b ->
                b.at("defaultServiceEndpoint", "likeEndpoint", "target", "playlistId").text()?.let { (b.at("isToggled").text() == "true") to it }
            }
        }

    suspend fun saveToLibrary(target: String, save: Boolean) = runCatching {
        if (target.startsWith("UC")) innerTube.subscribe(WEB_REMIX, target, save) else innerTube.likePlaylist(WEB_REMIX, target, save)
    }

    /** Whether the signed-in user likes the video: true, false, or null when YouTube didn't say. */
    suspend fun likeStatus(videoId: String): Result<Boolean?> =
        runCatching {
            innerTube.next(WEB_REMIX, videoId, null, null, null, null)
                .body<com.metrolist.innertube.models.response.LikeStatusResponse>().liked(videoId)
        }

    suspend fun next(
        endpoint: WatchEndpoint,
        continuation: String? = null,
    ): Result<NextResult> =
        runCatching {
            val response =
                innerTube
                    .next(
                        WEB_REMIX,
                        endpoint.videoId,
                        endpoint.playlistId,
                        endpoint.playlistSetVideoId,
                        endpoint.index,
                        endpoint.params,
                        continuation,
                    ).body<NextResponse>()
            val playlistPanelRenderer =
                response.continuationContents?.playlistPanelContinuation
                    ?: response.contents.singleColumnMusicWatchNextResultsRenderer
                        ?.tabbedRenderer
                        ?.watchNextTabbedResultsRenderer
                        ?.tabs
                        ?.get(0)
                        ?.tabRenderer
                        ?.content
                        ?.musicQueueRenderer
                        ?.content
                        ?.playlistPanelRenderer!!
            val title =
                response.contents.singleColumnMusicWatchNextResultsRenderer
                    ?.tabbedRenderer
                    ?.watchNextTabbedResultsRenderer
                    ?.tabs
                    ?.get(0)
                    ?.tabRenderer
                    ?.content
                    ?.musicQueueRenderer
                    ?.header
                    ?.musicQueueHeaderRenderer
                    ?.subtitle
                    ?.runs
                    ?.firstOrNull()
                    ?.text
            val items =
                playlistPanelRenderer.contents.mapNotNull { content ->
                    content.playlistPanelVideoRenderer
                        ?.let(NextPage::fromPlaylistPanelVideoRenderer)
                        ?.let { it to content.playlistPanelVideoRenderer.selected }
                }
            val songs = items.map { it.first }

            // load automix items
            playlistPanelRenderer.contents
                .lastOrNull()
                ?.automixPreviewVideoRenderer
                ?.content
                ?.automixPlaylistVideoRenderer
                ?.navigationEndpoint
                ?.watchPlaylistEndpoint
                ?.let { watchPlaylistEndpoint ->
                    return@runCatching next(watchPlaylistEndpoint).getOrThrow().let { result ->
                        result.copy(
                            title = title,
                            items = songs + result.items,
                            lyricsEndpoint =
                                response.contents.singleColumnMusicWatchNextResultsRenderer
                                    ?.tabbedRenderer
                                    ?.watchNextTabbedResultsRenderer
                                    ?.tabs
                                    ?.getOrNull(
                                        1,
                                    )?.tabRenderer
                                    ?.endpoint
                                    ?.browseEndpoint,
                        )
                    }
                }
            NextResult(
                title = title,
                items = songs,
                lyricsEndpoint =
                    response.contents.singleColumnMusicWatchNextResultsRenderer
                        ?.tabbedRenderer
                        ?.watchNextTabbedResultsRenderer
                        ?.tabs
                        ?.getOrNull(
                            1,
                        )?.tabRenderer
                        ?.endpoint
                        ?.browseEndpoint,
                continuation = playlistPanelRenderer.continuations?.getContinuation(),
            )
        }

    suspend fun lyrics(endpoint: BrowseEndpoint): Result<String?> =
        runCatching {
            val response = innerTube.browse(WEB_REMIX, endpoint.browseId, endpoint.params).body<BrowseResponse>()
            response.contents
                ?.sectionListRenderer
                ?.contents
                ?.firstOrNull { it.musicDescriptionShelfRenderer != null }
                ?.musicDescriptionShelfRenderer
                ?.description
                ?.runs
                ?.joinToString(separator = "") { it.text }
        }

    suspend fun visitorData(): Result<String> =
        runCatching {
            checkNotNull(innerTube.fetchFreshVisitorData()) { "YouTube did not return visitor data" }
        }

    @JvmInline
    value class SearchFilter(
        val value: String,
    ) {
        companion object {
            val FILTER_SONG = SearchFilter("EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D")
            val FILTER_VIDEO = SearchFilter("EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D")
            val FILTER_ALBUM = SearchFilter("EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D")
            val FILTER_ARTIST = SearchFilter("EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D")
            val FILTER_FEATURED_PLAYLIST = SearchFilter("EgeKAQQoADgBagwQDhAKEAMQBRAJEAQ%3D")
            val FILTER_COMMUNITY_PLAYLIST = SearchFilter("EgeKAQQoAEABagoQAxAEEAoQCRAF")
            val FILTER_PODCAST = SearchFilter("EgWKAQJQAWoKEAkQChAFEAMQBA%3D%3D")
            val FILTER_EPISODE = SearchFilter("EgWKAQJYAWoKEAkQChAFEAMQBA%3D%3D")
            val FILTER_PROFILE = SearchFilter("EgWKAQJYAWoSEAUQCRADEAQQEBAVEAoQDhAR")
        }
    }
}
