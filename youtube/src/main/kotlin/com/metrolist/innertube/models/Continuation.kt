package com.metrolist.innertube.models

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@OptIn(ExperimentalSerializationApi::class)
@Serializable
class Continuation(
    @JsonNames("nextContinuationData", "nextRadioContinuationData")
    val nextContinuationData: NextContinuationData?,
) {
    @Serializable
    class NextContinuationData(
        val continuation: String,
    )
}

fun List<Continuation>.getContinuation() =
    firstOrNull()?.nextContinuationData?.continuation