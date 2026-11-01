package com.metrolist.innertube.models

import kotlinx.serialization.Serializable

@Serializable
class PlaylistPanelRenderer(
    val contents: List<Content>,
    val continuations: List<Continuation>?,
) {
    @Serializable
    class Content(
        val playlistPanelVideoRenderer: PlaylistPanelVideoRenderer?,
        val automixPreviewVideoRenderer: AutomixPreviewVideoRenderer?,
    )
}
