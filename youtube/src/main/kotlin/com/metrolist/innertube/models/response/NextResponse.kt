package com.metrolist.innertube.models.response

import com.metrolist.innertube.models.PlaylistPanelRenderer
import com.metrolist.innertube.models.Tabs
import kotlinx.serialization.Serializable

@Serializable
class NextResponse(
    val contents: Contents,
    val continuationContents: ContinuationContents?,
) {
    @Serializable
    class Contents(
        val singleColumnMusicWatchNextResultsRenderer: SingleColumnMusicWatchNextResultsRenderer?,
    ) {
        @Serializable
        class SingleColumnMusicWatchNextResultsRenderer(
            val tabbedRenderer: TabbedRenderer?,
        ) {
            @Serializable
            class TabbedRenderer(
                val watchNextTabbedResultsRenderer: WatchNextTabbedResultsRenderer?,
            ) {
                @Serializable
                class WatchNextTabbedResultsRenderer(
                    val tabs: List<Tabs.Tab>,
                )
            }
        }
    }

    @Serializable
    class ContinuationContents(
        val playlistPanelContinuation: PlaylistPanelRenderer,
    )
}
