package com.metrolist.innertube.models.response

import com.metrolist.innertube.models.Tabs
import kotlinx.serialization.Serializable

@Serializable
class SearchResponse(
    val contents: Contents?,
) {
    @Serializable
    class Contents(
        val tabbedSearchResultsRenderer: Tabs?,
    )
}
