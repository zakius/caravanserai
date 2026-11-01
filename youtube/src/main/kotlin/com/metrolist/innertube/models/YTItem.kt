package com.metrolist.innertube.models

import com.metrolist.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_ATV

sealed class YTItem {
    abstract val id: String
    abstract val title: String
    abstract val thumbnail: String?
}

class Artist(
    val name: String,
    val id: String?,
)

class Album(
    val name: String,
    val id: String,
)

class SongItem(
    override val id: String,
    override val title: String,
    val artists: List<Artist>,
    val album: Album? = null,
    val duration: Int? = null,
    val musicVideoType: String? = null,
    override val thumbnail: String,
    val setVideoId: String? = null,
) : YTItem() {
    val isVideoSong: Boolean
        get() = musicVideoType != null && musicVideoType != MUSIC_VIDEO_TYPE_ATV
}

data class AlbumItem(
    val browseId: String,
    val playlistId: String,
    override val id: String = browseId,
    override val title: String,
    val artists: List<Artist>?,
    val year: Int? = null,
    override val thumbnail: String,
) : YTItem()

class PlaylistItem(
    override val id: String,
    override val title: String,
    val author: Artist?,
    val songCountText: String?,
    override val thumbnail: String?,
    val playEndpoint: WatchEndpoint?,
    val shuffleEndpoint: WatchEndpoint?,
    val radioEndpoint: WatchEndpoint?,
) : YTItem()

class ArtistItem(
    override val id: String,
    override val title: String,
    override val thumbnail: String?,
    val shuffleEndpoint: WatchEndpoint?,
    val radioEndpoint: WatchEndpoint?,
) : YTItem()

class PodcastItem(
    override val id: String,
    override val title: String,
    override val thumbnail: String?,
) : YTItem()

class EpisodeItem(
    override val id: String,
    override val title: String,
    override val thumbnail: String,
) : YTItem()
