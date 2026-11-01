package com.metrolist.innertube.models

import kotlinx.serialization.Serializable

@Serializable
class MusicPlaylistShelfRenderer(
    val contents: List<MusicShelfRenderer.Content> = emptyList(),
    val continuations: List<Continuation>? = null,
)
