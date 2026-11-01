package com.metrolist.innertube.models

import kotlinx.serialization.Serializable

@Serializable
class Tabs(
    val tabs: List<Tab>,
) {
    @Serializable
    class Tab(
        val tabRenderer: TabRenderer,
    ) {
        @Serializable
        class TabRenderer(
            val content: Content?,
            val endpoint: NavigationEndpoint?,
        ) {
            @Serializable
            class Content(
                val sectionListRenderer: SectionListRenderer?,
                val musicQueueRenderer: MusicQueueRenderer?,
            )
        }
    }
}
