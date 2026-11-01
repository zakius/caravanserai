package com.metrolist.innertube.models

import kotlinx.serialization.Serializable

@Serializable
class NavigationEndpoint(
    val watchEndpoint: WatchEndpoint? = null,
    val watchPlaylistEndpoint: WatchEndpoint? = null,
    val browseEndpoint: BrowseEndpoint? = null,
    val searchEndpoint: SearchEndpoint? = null,
    val confirmDialogEndpoint: ConfirmDialogEndpoint? = null,
) {
    @Serializable
    class DeletePrivatelyOwnedEntityCommand(
        val entityId: String,
    )

    @Serializable
    class ConfirmDialogEndpoint(
        val content: ConfirmDialogContent? = null,
    ) {
        @Serializable
        class ConfirmDialogContent(
            val confirmDialogRenderer: ConfirmDialogRenderer? = null,
        )

        @Serializable
        class ConfirmDialogRenderer(
            val confirmButton: ConfirmButton? = null,
        )

        @Serializable
        class ConfirmButton(
            val buttonRenderer: ConfirmButtonRenderer? = null,
        )

        @Serializable
        class ConfirmButtonRenderer(
            val command: ConfirmCommand? = null,
        )

        @Serializable
        class ConfirmCommand(
            val musicDeletePrivatelyOwnedEntityCommand: DeletePrivatelyOwnedEntityCommand? = null,
        )
    }
    val endpoint: Endpoint?
        get() =
            watchEndpoint
                ?: watchPlaylistEndpoint
                ?: browseEndpoint
                ?: searchEndpoint

    val anyWatchEndpoint: WatchEndpoint?
        get() = watchEndpoint
            ?: watchPlaylistEndpoint

    val musicVideoType: String?
        get() = anyWatchEndpoint
            ?.watchEndpointMusicSupportedConfigs
            ?.watchEndpointMusicConfig
            ?.musicVideoType
}
