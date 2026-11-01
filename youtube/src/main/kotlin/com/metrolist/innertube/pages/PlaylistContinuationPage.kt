package com.metrolist.innertube.pages

import com.metrolist.innertube.models.SongItem

class PlaylistContinuationPage(
    val songs: List<SongItem>,
    val continuation: String?,
)
