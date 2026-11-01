package com.metrolist.innertube.models.response

import com.metrolist.innertube.models.Button
import com.metrolist.innertube.models.Continuation
import com.metrolist.innertube.models.GridRenderer
import com.metrolist.innertube.models.Menu
import com.metrolist.innertube.models.MusicDetailHeaderRenderer
import com.metrolist.innertube.models.MusicShelfRenderer
import com.metrolist.innertube.models.Runs
import com.metrolist.innertube.models.SectionListRenderer
import com.metrolist.innertube.models.SubscriptionButton
import com.metrolist.innertube.models.Tabs
import com.metrolist.innertube.models.ThumbnailRenderer
import kotlinx.serialization.Serializable

@Serializable
class BrowseResponse(
    val contents: Contents?,
    val continuationContents: ContinuationContents?,
    val onResponseReceivedActions: List<ResponseAction>?,
    val header: Header?,
    val microformat: Microformat?,
) {
    @Serializable
    class Contents(
        val singleColumnBrowseResultsRenderer: Tabs?,
        val sectionListRenderer: SectionListRenderer?,
        val twoColumnBrowseResultsRenderer: TwoColumnBrowseResultsRenderer?,
    )

    @Serializable
    class TwoColumnBrowseResultsRenderer(
        val tabs: List<Tabs.Tab?>?,
        val secondaryContents: SecondaryContents?,
    )
    @Serializable
    class SecondaryContents(
        val sectionListRenderer: SectionListRenderer?,
    )

    @Serializable
    class ContinuationContents(
        val sectionListContinuation: SectionListContinuation?,
        val musicPlaylistShelfContinuation: MusicPlaylistShelfContinuation?,
        val gridContinuation: GridContinuation?,
        val musicShelfContinuation: MusicShelfRenderer?
    ) {
        @Serializable
        class SectionListContinuation(
            val contents: List<SectionListRenderer.Content> = emptyList(),
            val continuations: List<Continuation>?,
        )

        @Serializable
        class MusicPlaylistShelfContinuation(
            val contents: List<MusicShelfRenderer.Content> = emptyList(),
            val continuations: List<Continuation>?,
        )

        @Serializable
        class GridContinuation(
            val items: List<GridRenderer.Item> = emptyList(),
            val continuations: List<Continuation>?,
        )
    }

    @Serializable
    class ResponseAction(
        val appendContinuationItemsAction: ContinuationItems?,
    ) {
        @Serializable
        class ContinuationItems(
            val continuationItems: List<MusicShelfRenderer.Content>?,
        )
    }

    @Serializable
    class Header(
        val musicImmersiveHeaderRenderer: MusicImmersiveHeaderRenderer?,
        val musicDetailHeaderRenderer: MusicDetailHeaderRenderer?,
        val musicVisualHeaderRenderer: MusicVisualHeaderRenderer?,
        val musicHeaderRenderer: MusicHeaderRenderer?,
    ) {
        @Serializable
        class MusicImmersiveHeaderRenderer(
            val title: Runs,
            val thumbnail: ThumbnailRenderer?,
            val playButton: Button?,
            val startRadioButton: Button?,
            val subscriptionButton: SubscriptionButton?,
            val subscriptionButton2: SubscriptionButton2?,
            val monthlyListenerCount: Runs? = null,
        ) {
            @Serializable
            class SubscriptionButton2(
                val subscribeButtonRenderer: SubscribeButtonRenderer?,
            ) {
                @Serializable
                class SubscribeButtonRenderer(
                    val subscriberCountWithSubscribeText: Runs?,
                )
            }
        }

        @Serializable
        class MusicVisualHeaderRenderer(
            val title: Runs,
            val foregroundThumbnail: ThumbnailRenderer,
        )

        @Serializable
        class Buttons(
            val menuRenderer: Menu.MenuRenderer?,
        )

        @Serializable
        class MusicHeaderRenderer(
            val buttons: List<Buttons>?,
            val title: Runs?,
            val thumbnail: MusicThumbnailRenderer?,
            val secondSubtitle: Runs?,
            val straplineTextOne: Runs?,
        )
        @Serializable
        class MusicThumbnail(
            val url: String?,
        )
        @Serializable
        class MusicThumbnailRenderer(
            val musicThumbnailRenderer: MusicThumbnailRenderer,
            val thumbnails: List<MusicThumbnail>?,
        )
    }

    @Serializable
    class Microformat(
        val microformatDataRenderer: MicroformatDataRenderer?,
    ) {
        @Serializable
        class MicroformatDataRenderer(
            val urlCanonical: String?,
        )
    }
}
