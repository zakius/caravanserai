package com.metrolist.innertube.models

import kotlinx.serialization.Serializable

@Serializable
class ThumbnailRenderer(
    val musicThumbnailRenderer: MusicThumbnailRenderer?,
    val musicAnimatedThumbnailRenderer: MusicAnimatedThumbnailRenderer?,
    val croppedSquareThumbnailRenderer: MusicThumbnailRenderer?,
) {
    @Serializable
    class MusicThumbnailRenderer(
        val thumbnail: Thumbnails,
    ) {
        fun getThumbnailUrl() = thumbnail.thumbnails.lastOrNull()?.url
    }

    fun getThumbnailUrl(): String? =
        musicThumbnailRenderer?.getThumbnailUrl()
            ?: musicAnimatedThumbnailRenderer?.backupRenderer?.getThumbnailUrl()
            ?: croppedSquareThumbnailRenderer?.getThumbnailUrl()

    @Serializable
    class MusicAnimatedThumbnailRenderer(
        val backupRenderer: MusicThumbnailRenderer,
    )
}
