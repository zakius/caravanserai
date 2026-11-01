package com.metrolist.innertube.models

import kotlinx.serialization.Serializable

@Serializable
class TwoColumnBrowseResultsRenderer(
    val secondaryContents: SecondaryContents?,
    val tabs: List<Tabs.Tab>?
) {
    @Serializable
    class SecondaryContents(
        val sectionListRenderer: SectionListRenderer?
    )

    @Serializable
    class SectionListRenderer(
        val contents: List<Content>?,
        val continuations: List<Continuation>?,
    ) {
        @Serializable
        class Content(
            val musicPlaylistShelfRenderer: MusicPlaylistShelfRenderer?,
            val musicShelfRenderer: MusicShelfRenderer?
        )
    }
}
