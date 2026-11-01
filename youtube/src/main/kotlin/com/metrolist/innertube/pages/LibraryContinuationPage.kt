package com.metrolist.innertube.pages

import com.metrolist.innertube.models.YTItem

class LibraryContinuationPage(
    val items: List<YTItem>,
    val continuation: String?,
)
