package com.metrolist.innertube.models

import kotlinx.serialization.Serializable

@Serializable
class GridRenderer(
    val items: List<Item>,
    val continuations: List<Continuation>?,
) {
    @Serializable
    class Item(
        val musicTwoRowItemRenderer: MusicTwoRowItemRenderer?,
    )
}
