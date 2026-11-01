package com.metrolist.innertube.models

import kotlinx.serialization.Serializable

@Serializable
class SearchSuggestionsSectionRenderer(
    val contents: List<Content>,
) {
    @Serializable
    class Content(
        val searchSuggestionRenderer: SearchSuggestionRenderer?,
    ) {
        @Serializable
        class SearchSuggestionRenderer(
            val suggestion: Runs,
        )
    }
}
