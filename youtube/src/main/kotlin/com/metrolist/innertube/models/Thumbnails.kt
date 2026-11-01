package com.metrolist.innertube.models

import kotlinx.serialization.Serializable

@Serializable
class Thumbnails(
    val thumbnails: List<Thumbnail>,
)

@Serializable
class Thumbnail(
    val url: String,
)
