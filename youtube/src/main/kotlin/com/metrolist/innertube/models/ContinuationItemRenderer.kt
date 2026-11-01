package com.metrolist.innertube.models

import kotlinx.serialization.Serializable

@Serializable
class ContinuationItemRenderer(
    val continuationEndpoint: ContinuationEndpoint?,
) {
    @Serializable
    class ContinuationEndpoint(
        val continuationCommand: ContinuationCommand?,
    ) {
        @Serializable
        class ContinuationCommand(
            val token: String?,
        )
    }
}