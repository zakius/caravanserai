package com.metrolist.innertube.models

import kotlinx.serialization.Serializable

@Serializable
class MusicQueueRenderer(
    val content: Content?,
    val header: Header?,
) {
    @Serializable
    class Content(
        val playlistPanelRenderer: PlaylistPanelRenderer,
    )

    @Serializable
    class Header(
        val musicQueueHeaderRenderer: MusicQueueHeaderRenderer?,
    ) {
        @Serializable
        class MusicQueueHeaderRenderer(
            val subtitle: Runs?,
        )
    }
}
