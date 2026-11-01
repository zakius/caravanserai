package com.metrolist.innertube.models

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
class SectionListRenderer(
    val contents: List<Content>?,
    val continuations: List<Continuation>?,
) {
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    class Content(
        @JsonNames("musicImmersiveCarouselShelfRenderer")
        val musicCarouselShelfRenderer: MusicCarouselShelfRenderer?,
        val musicShelfRenderer: MusicShelfRenderer?,
        val musicPlaylistShelfRenderer: MusicPlaylistShelfRenderer?,
        val musicDescriptionShelfRenderer: MusicDescriptionShelfRenderer?,
        val musicResponsiveHeaderRenderer: MusicResponsiveHeaderRenderer?,
        val musicEditablePlaylistDetailHeaderRenderer: MusicEditablePlaylistDetailHeaderRenderer?,
        val gridRenderer: GridRenderer?,
        val itemSectionRenderer: ItemSectionRenderer?,
    )
}

@Serializable
class ItemSectionRenderer(
    val contents: List<Content>?,
) {
    @Serializable
    class Content(
        val musicResponsiveListItemRenderer: MusicResponsiveListItemRenderer? = null,
        val musicShelfRenderer: MusicShelfRenderer? = null,
        val musicPlaylistShelfRenderer: MusicPlaylistShelfRenderer? = null,
        val gridRenderer: GridRenderer? = null,
        // roma: search spelling corrections.
        val showingResultsForRenderer: Correction? = null,
        val didYouMeanRenderer: Correction? = null,
    )

    @Serializable
    class Correction(
        val correctedQuery: Runs? = null,
        val originalQueryEndpoint: NavigationEndpoint? = null,
    )
}
