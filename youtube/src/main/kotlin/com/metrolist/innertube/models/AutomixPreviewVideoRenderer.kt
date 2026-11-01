package com.metrolist.innertube.models

import kotlinx.serialization.Serializable

@Serializable
class AutomixPreviewVideoRenderer(
    val content: Content,
) {
    @Serializable
    class Content(
        val automixPlaylistVideoRenderer: AutomixPlaylistVideoRenderer,
    ) {
        @Serializable
        class AutomixPlaylistVideoRenderer(
            val navigationEndpoint: NavigationEndpoint,
        )
    }
}
