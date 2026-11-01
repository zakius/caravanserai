package com.metrolist.innertube.models

import kotlinx.serialization.Serializable

@Serializable
class MusicEditablePlaylistDetailHeaderRenderer(
    val header: Header,
) {
    @Serializable
    class Header(
        val musicDetailHeaderRenderer: MusicDetailHeaderRenderer?,
        val musicResponsiveHeaderRenderer: MusicResponsiveHeaderRenderer?
    )
}

@Serializable
class MusicDetailHeaderRenderer(
    val title: Runs,
    val subtitle: Runs,
    val description: Runs?,
    val thumbnail: ThumbnailRenderer,
    val menu: Menu,
)
