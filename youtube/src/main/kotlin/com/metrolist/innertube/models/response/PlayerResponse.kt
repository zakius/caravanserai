package com.metrolist.innertube.models.response

import kotlinx.serialization.Serializable

/**
 * PlayerResponse with [com.metrolist.innertube.models.YouTubeClient.WEB_REMIX] client
 */
@Serializable
class PlayerResponse(
    val playabilityStatus: PlayabilityStatus,
    val playerConfig: PlayerConfig?,
    val streamingData: StreamingData?,
) {
    @Serializable
    class PlayabilityStatus(
        val status: String,
        val reason: String?,
    )

    @Serializable
    class PlayerConfig(
        val audioConfig: AudioConfig,
    ) {
        @Serializable
        class AudioConfig(
            val loudnessDb: Double?,
        )
    }

    @Serializable
    class StreamingData(
        val adaptiveFormats: List<Format>,
    ) {
        @Serializable
        class Format(
            val url: String?,
            val mimeType: String,
            val bitrate: Int,
            val averageBitrate: Int?,
            val audioTrack: AudioTrack?
        ) {
            @Serializable
            class AudioTrack(
                val id: String?,
            )
        }
    }
}
