package com.metrolist.innertube.pages

import com.metrolist.innertube.models.Album
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE
import com.metrolist.innertube.models.EpisodeItem
import com.metrolist.innertube.models.MusicResponsiveListItemRenderer
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.PodcastItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.models.splitBySeparator
import com.metrolist.innertube.utils.parseTime

class SearchResult(
    val items: List<YTItem>,
    // roma: the service's spelling correction. When autoCorrected, items are for correctedQuery and
    // originalParams searches the query as typed; otherwise correctedQuery is only a "did you mean".
    val correctedQuery: String? = null,
    val autoCorrected: Boolean = false,
    val originalParams: String? = null,
)

object SearchPage {
    fun toYTItem(
        renderer: MusicResponsiveListItemRenderer,
        fallbackArtists: List<Artist> = emptyList(),
    ): YTItem? {
        val secondaryLine =
            renderer.flexColumns
                .getOrNull(1)
                ?.musicResponsiveListItemFlexColumnRenderer
                ?.text
                ?.runs
                ?.splitBySeparator()
                .orEmpty()
        return when {
            // CRITICAL: Check isEpisode BEFORE isSong — both can match isSong (watchEndpoint or
            // null navigationEndpoint), so episodes must be identified first.
            renderer.isEpisode -> {
                // The subtitle line structure differs between filtered and unfiltered search:
                //   Unfiltered: ["Episode", "·", "Jan 2025", "·", "Podcast Name", "·", "1:00:00"]
                //     → secondaryLine = [["Episode"], ["Jan 2025"], ["Podcast Name"], ["1:00:00"]]
                //   Filtered:   ["Jan 2025", "·", "Podcast Name"]
                //     → secondaryLine = [["Jan 2025"], ["Podcast Name"]]
                //
                // Strategy: locate the podcast section by its PODCAST_SHOW_DETAIL_PAGE link.
                val podcastSectionIndex = secondaryLine.indexOfFirst { section ->
                    section.any { run ->
                        run.navigationEndpoint?.browseEndpoint
                            ?.browseEndpointContextSupportedConfigs
                            ?.browseEndpointContextMusicConfig
                            ?.pageType == MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE
                    }
                }

                // Unread, but it skips an episode whose podcast link has no browse id.
                val podcast = if (podcastSectionIndex >= 0) {
                    secondaryLine[podcastSectionIndex].firstOrNull()?.let { run ->
                        Album(
                            name = run.text,
                            id = run.navigationEndpoint?.browseEndpoint?.browseId ?: return null,
                        )
                    }
                } else null

                EpisodeItem(
                    id = renderer.playlistItemData?.videoId
                        ?: renderer.navigationEndpoint?.watchEndpoint?.videoId
                        ?: renderer.overlay?.musicItemThumbnailOverlayRenderer
                            ?.content?.musicPlayButtonRenderer
                            ?.playNavigationEndpoint?.watchEndpoint?.videoId
                        ?: renderer.flexColumns.firstOrNull()
                            ?.musicResponsiveListItemFlexColumnRenderer
                            ?.text?.runs?.firstOrNull()
                            ?.navigationEndpoint?.watchEndpoint?.videoId
                        ?: return null,
                    title =
                        renderer.flexColumns
                            .firstOrNull()
                            ?.musicResponsiveListItemFlexColumnRenderer
                            ?.text
                            ?.runs
                            ?.firstOrNull()
                            ?.text ?: return null,
                    thumbnail = renderer.thumbnail?.getThumbnailUrl() ?: return null,
                    // In filtered search the overlay play button may be absent; fall back to the
                    // item's own watchEndpoint so the episode is always playable.
                )
            }
            renderer.isSong -> {
                val metadataRuns = renderer.flexColumns
                    .drop(1)
                    .flatMap { it.musicResponsiveListItemFlexColumnRenderer.text?.runs.orEmpty() }
                val artists = PageHelper.extractArtists(metadataRuns).ifEmpty { fallbackArtists }
                val albumRun = PageHelper.extractRuns(renderer.flexColumns, "MUSIC_PAGE_TYPE_ALBUM").firstOrNull()

                SongItem(
                    id = renderer.playlistItemData?.videoId
                        ?: renderer.navigationEndpoint?.watchEndpoint?.videoId
                        ?: renderer.overlay?.musicItemThumbnailOverlayRenderer
                            ?.content?.musicPlayButtonRenderer
                            ?.playNavigationEndpoint?.watchEndpoint?.videoId
                        ?: renderer.flexColumns.firstOrNull()
                            ?.musicResponsiveListItemFlexColumnRenderer
                            ?.text?.runs?.firstOrNull()
                            ?.navigationEndpoint?.watchEndpoint?.videoId
                        ?: return null,
                    title =
                        renderer.flexColumns
                            .firstOrNull()
                            ?.musicResponsiveListItemFlexColumnRenderer
                            ?.text
                            ?.runs
                            ?.firstOrNull()
                            ?.text ?: return null,
                    artists = artists,
                    album =
                        albumRun?.let {
                            Album(
                                name = it.text,
                                id = it.navigationEndpoint?.browseEndpoint?.browseId ?: return@let null,
                            )
                        },
                    duration = PageHelper.extractDuration(metadataRuns),
                    musicVideoType = renderer.musicVideoType,
                    thumbnail = renderer.thumbnail?.getThumbnailUrl() ?: return null,
                )
            }
            renderer.isArtist -> {
                ArtistItem(
                    id = renderer.navigationEndpoint?.browseEndpoint?.browseId ?: return null,
                    title =
                        renderer.flexColumns
                            .firstOrNull()
                            ?.musicResponsiveListItemFlexColumnRenderer
                            ?.text
                            ?.runs
                            ?.firstOrNull()
                            ?.text
                            ?: return null,
                    thumbnail = renderer.thumbnail?.getThumbnailUrl() ?: return null,
                    shuffleEndpoint =
                        renderer.menu
                            ?.menuRenderer
                            ?.items
                            ?.find { it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE" }
                            ?.menuNavigationItemRenderer
                            ?.navigationEndpoint
                            ?.watchPlaylistEndpoint ?: return null,
                    radioEndpoint =
                        renderer.menu.menuRenderer.items
                            .find { it.menuNavigationItemRenderer?.icon?.iconType == "MIX" }
                            ?.menuNavigationItemRenderer
                            ?.navigationEndpoint
                            ?.watchPlaylistEndpoint ?: return null,
                )
            }
            renderer.isUserChannel -> {
                ArtistItem(
                    id = renderer.navigationEndpoint?.browseEndpoint?.browseId ?: return null,
                    title =
                        renderer.flexColumns
                            .firstOrNull()
                            ?.musicResponsiveListItemFlexColumnRenderer
                            ?.text
                            ?.runs
                            ?.firstOrNull()
                            ?.text
                            ?: return null,
                    thumbnail = renderer.thumbnail?.getThumbnailUrl() ?: return null,
                    shuffleEndpoint = renderer.menu
                        ?.menuRenderer
                        ?.items
                        ?.find { it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE" }
                        ?.menuNavigationItemRenderer
                        ?.navigationEndpoint
                        ?.watchPlaylistEndpoint,
                    radioEndpoint = renderer.menu
                        ?.menuRenderer
                        ?.items
                        ?.find { it.menuNavigationItemRenderer?.icon?.iconType == "MIX" }
                        ?.menuNavigationItemRenderer
                        ?.navigationEndpoint
                        ?.watchPlaylistEndpoint,
                )
            }
            renderer.isAlbum -> {
                AlbumItem(
                    browseId = renderer.navigationEndpoint?.browseEndpoint?.browseId ?: return null,
                    playlistId =
                        renderer.overlay
                            ?.musicItemThumbnailOverlayRenderer
                            ?.content
                            ?.musicPlayButtonRenderer
                            ?.playNavigationEndpoint
                            ?.anyWatchEndpoint
                            ?.playlistId
                            ?: return null,
                    title =
                        renderer.flexColumns
                            .firstOrNull()
                            ?.musicResponsiveListItemFlexColumnRenderer
                            ?.text
                            ?.runs
                            ?.firstOrNull()
                            ?.text ?: return null,
                    artists = PageHelper.extractArtists(secondaryLine.getOrNull(1)),
                    year =
                        secondaryLine
                            .getOrNull(2)
                            ?.firstOrNull()
                            ?.text
                            ?.toIntOrNull(),
                    thumbnail = renderer.thumbnail?.getThumbnailUrl() ?: return null,
                )
            }
            renderer.isPlaylist -> {
                PlaylistItem(
                    id =
                        renderer.navigationEndpoint
                            ?.browseEndpoint
                            ?.browseId
                            ?.removePrefix("VL") ?: return null,
                    title =
                        renderer.flexColumns
                            .firstOrNull()
                            ?.musicResponsiveListItemFlexColumnRenderer
                            ?.text
                            ?.runs
                            ?.firstOrNull()
                            ?.text ?: return null,
                    author =
                        PageHelper
                            .extractArtists(
                                renderer.flexColumns
                                    .drop(1)
                                    .flatMap {
                                        it.musicResponsiveListItemFlexColumnRenderer.text
                                            ?.runs
                                            .orEmpty()
                                    },
                            ).firstOrNull() ?: PlaylistPage.ownerFromByline(secondaryLine.dropLast(1).lastOrNull()),
                    songCountText =
                        renderer.flexColumns
                            .getOrNull(1)
                            ?.musicResponsiveListItemFlexColumnRenderer
                            ?.text
                            ?.runs
                            ?.lastOrNull()
                            ?.text ?: return null,
                    thumbnail = renderer.thumbnail?.getThumbnailUrl() ?: return null,
                    playEndpoint =
                        renderer.overlay
                            ?.musicItemThumbnailOverlayRenderer
                            ?.content
                            ?.musicPlayButtonRenderer
                            ?.playNavigationEndpoint
                            ?.watchPlaylistEndpoint ?: return null,
                    shuffleEndpoint =
                        renderer.menu
                            ?.menuRenderer
                            ?.items
                            ?.find { it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE" }
                            ?.menuNavigationItemRenderer
                            ?.navigationEndpoint
                            ?.watchPlaylistEndpoint ?: return null,
                    radioEndpoint =
                        renderer.menu.menuRenderer.items
                            .find { it.menuNavigationItemRenderer?.icon?.iconType == "MIX" }
                            ?.menuNavigationItemRenderer
                            ?.navigationEndpoint
                            ?.watchPlaylistEndpoint ?: return null,
                )
            }
            renderer.isPodcast -> {
                PodcastItem(
                    id =
                        renderer.navigationEndpoint
                            ?.browseEndpoint
                            ?.browseId
                            ?: return null,
                    title =
                        renderer.flexColumns
                            .firstOrNull()
                            ?.musicResponsiveListItemFlexColumnRenderer
                            ?.text
                            ?.runs
                            ?.firstOrNull()
                            ?.text ?: return null,
                    thumbnail = renderer.thumbnail?.getThumbnailUrl() ?: return null,
                )
            }
            else -> null
        }
    }
}
