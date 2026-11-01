package com.metrolist.innertube.models.response

import com.metrolist.innertube.models.SearchSuggestionsSectionRenderer
import kotlinx.serialization.Serializable

@Serializable
class GetSearchSuggestionsResponse(
    val contents: List<Content>?,
) {
    @Serializable
    class Content(
        val searchSuggestionsSectionRenderer: SearchSuggestionsSectionRenderer,
    )
}
