package com.metrolist.innertube.pages

import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_ARTIST
import com.metrolist.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_LIBRARY_ARTIST
import com.metrolist.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_USER_CHANNEL
import com.metrolist.innertube.models.MusicResponsiveListItemRenderer.FlexColumn
import com.metrolist.innertube.models.Run
import com.metrolist.innertube.utils.parseTime

object PageHelper {
    fun extractRuns(
        columns: List<FlexColumn>,
        typeLike: String,
    ): List<Run> {
        val filteredRuns = mutableListOf<Run>()
        for (column in columns) {
            val runs =
                column.musicResponsiveListItemFlexColumnRenderer.text?.runs
                    ?: continue

            for (run in runs) {
                val typeStr =
                    run.navigationEndpoint
                        ?.watchEndpoint
                        ?.watchEndpointMusicSupportedConfigs
                        ?.watchEndpointMusicConfig
                        ?.musicVideoType
                        ?: run.navigationEndpoint
                            ?.browseEndpoint
                            ?.browseEndpointContextSupportedConfigs
                            ?.browseEndpointContextMusicConfig
                            ?.pageType
                        ?: continue

                if (typeLike in typeStr) {
                    filteredRuns.add(run)
                }
            }
        }
        return filteredRuns
    }

    fun extractArtists(runs: List<Run>?): List<Artist> =
        runs.orEmpty().mapNotNull { run ->
            val endpoint = run.navigationEndpoint?.browseEndpoint ?: return@mapNotNull null
            val pageType = endpoint.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType
            if (run.text.isBlank() ||
                !(
                    pageType == MUSIC_PAGE_TYPE_ARTIST || pageType == MUSIC_PAGE_TYPE_LIBRARY_ARTIST ||
                        pageType == MUSIC_PAGE_TYPE_USER_CHANNEL || pageType == null && endpoint.browseId.startsWith("UC")
                )
            ) {
                null
            } else {
                Artist(run.text.trim(), endpoint.browseId)
            }
        }

    fun extractDuration(runs: List<Run>?): Int? =
        runs.orEmpty().firstNotNullOfOrNull { run ->
            run.text
                .trim()
                .takeIf { it.isDurationText() }
                ?.parseTime()
        }

    private fun String.isDurationText(): Boolean = matches(Regex("""\d{1,2}[:.,]\d{2}(?:[:.,]\d{2})?"""))
}
