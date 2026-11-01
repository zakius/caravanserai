package com.metrolist.innertube.models

import kotlinx.serialization.Serializable

@Serializable
class PlaylistPanelVideoRenderer(
    val title: Runs?,
    val lengthText: Runs?,
    val longBylineText: Runs?,
    val videoId: String?,
    val selected: Boolean,
    val thumbnail: Thumbnails,
    val navigationEndpoint: NavigationEndpoint,
)
