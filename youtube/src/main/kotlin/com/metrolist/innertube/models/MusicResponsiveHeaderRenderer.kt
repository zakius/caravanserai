package com.metrolist.innertube.models

import kotlinx.serialization.Serializable

@Serializable
class MusicResponsiveHeaderRenderer(
    val thumbnail: ThumbnailRenderer? = null,
    val buttons: List<Button> = emptyList(),
    val title: Runs = Runs(null),
    val subtitle: Runs = Runs(null),
    val secondSubtitle: Runs? = null,
    val straplineTextOne: Runs? = null,
    val description: DescriptionWrapper? = null,
    val facepile: FacepileWrapper? = null,
) {
    @Serializable
    class Button(
        val menuRenderer: Menu.MenuRenderer? = null,
    )
}

@Serializable
class AvatarStackViewModel(
    val text: AvatarText?,
    val rendererContext: RendererContext?,
) {
    @Serializable
    class AvatarText(
        val content: String?,
    )

    @Serializable
    class RendererContext(
        val commandContext: CommandContext?,
    )

    @Serializable
    class CommandContext(
        val onTap: OnTap?,
    )

    @Serializable
    class OnTap(
        val innertubeCommand: InnerTubeBrowseCommand?,
    )

    @Serializable
    class InnerTubeBrowseCommand(
        val browseEndpoint: BrowseEndpoint?,
    )

    @Serializable
    class BrowseEndpoint(
        val browseId: String?,
    )
}

@Serializable
class DescriptionWrapper(
    val musicDescriptionShelfRenderer: MusicDescriptionShelfRenderer?,
)

@Serializable
class FacepileWrapper(
    val avatarStackViewModel: AvatarStackViewModel?,
)
