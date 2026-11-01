package com.metrolist.innertube.pages

import com.metrolist.innertube.models.Album
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.MusicResponsiveListItemRenderer
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.oddElements
import com.metrolist.innertube.models.splitBySeparator
import com.metrolist.innertube.utils.parseTime

class AlbumPage(
    val album: AlbumItem,
    val songs: List<SongItem>,
) {
    companion object {
        fun getSong(renderer: MusicResponsiveListItemRenderer, album: AlbumItem? = null): SongItem? {
            val subtitle = renderer.flexColumns.getOrNull(1)?.musicResponsiveListItemFlexColumnRenderer?.text?.runs
            val artists = PageHelper.extractArtists(subtitle).ifEmpty {
                // Art tracks and uploads have a dedicated performer column that can
                // differ from the album's distributor or compilation artist.
                if (album?.playlistId?.startsWith("OLAK") == true ||
                    album?.browseId?.startsWith("FEmusic_library_privately_owned_release_detail") == true
                ) {
                    subtitle?.splitBySeparator()?.firstOrNull()?.oddElements()
                        ?.mapNotNull { run ->
                            run.text.trim().takeIf { it.isNotEmpty() && run.navigationEndpoint == null }
                                ?.let { Artist(it, null) }
                        }.orEmpty()
                } else emptyList()
            }.ifEmpty { album?.artists.orEmpty() }

            return SongItem(
                id =
                    renderer.playlistItemData?.videoId
                        ?: renderer.navigationEndpoint?.watchEndpoint?.videoId
                        ?: renderer.overlay
                            ?.musicItemThumbnailOverlayRenderer
                            ?.content
                            ?.musicPlayButtonRenderer
                            ?.playNavigationEndpoint
                            ?.watchEndpoint
                            ?.videoId
                        ?: renderer.flexColumns
                            .firstOrNull()
                            ?.musicResponsiveListItemFlexColumnRenderer
                            ?.text
                            ?.runs
                            ?.firstOrNull()
                            ?.navigationEndpoint
                            ?.watchEndpoint
                            ?.videoId
                        ?: return null,
                title = PageHelper.extractRuns(renderer.flexColumns, "MUSIC_VIDEO").firstOrNull()?.text ?: return null,
                artists = artists,
                album =
                    album?.let {
                        Album(it.title, it.browseId)
                    } ?: renderer.flexColumns.getOrNull(2)?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.let {
                        Album(
                            name = it.text,
                            id = it.navigationEndpoint?.browseEndpoint?.browseId!!,
                        )
                    }!!,
                duration =
                    renderer.fixedColumns
                        ?.firstOrNull()
                        ?.musicResponsiveListItemFlexColumnRenderer
                        ?.text
                        ?.runs
                        ?.firstOrNull()
                        ?.text
                        ?.parseTime() ?: return null,
                musicVideoType = renderer.musicVideoType,
                thumbnail = renderer.thumbnail?.getThumbnailUrl() ?: album?.thumbnail!!,
            )
        }
    }
}
