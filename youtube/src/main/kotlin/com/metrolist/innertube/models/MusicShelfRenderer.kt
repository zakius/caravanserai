package com.metrolist.innertube.models

import kotlinx.serialization.Serializable

@Serializable
class MusicShelfRenderer(
    val title: Runs?,
    val contents: List<Content>?,
    val continuations: List<Continuation>?,
) {
    @Serializable
    class Content(
        val musicResponsiveListItemRenderer: MusicResponsiveListItemRenderer?,
        val continuationItemRenderer: ContinuationItemRenderer?,
    )
}

fun List<MusicShelfRenderer.Content>.getItems(): List<MusicResponsiveListItemRenderer> =
    mapNotNull { it.musicResponsiveListItemRenderer }

fun List<MusicShelfRenderer.Content>.getContinuation(): String? =
    firstOrNull { it.continuationItemRenderer != null }
        ?.continuationItemRenderer
        ?.continuationEndpoint
        ?.continuationCommand
        ?.token