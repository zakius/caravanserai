package com.metrolist.innertubex.models.response

import com.metrolist.innertubex.models.PlayerFormatListSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
@OptIn(ExperimentalSerializationApi::class)
class PlayerResponse(
    val playabilityStatus: PlayabilityStatus,
    val streamingData: StreamingData? = null,
    val videoDetails: VideoDetails? = null,
    val playbackTracking: PlaybackTracking? = null,
    val playerConfig: PlayerConfig? = null,
) {
    @Serializable
    class PlayabilityStatus(
        val status: String,
        val reason: String? = null,
    ) {
        // Keep this wire-only signal out of the public constructor and copy contract.
        internal var desktopLegacyAgeGateReason: Int? = null
    }

    @Serializable
    class StreamingData(
        @Serializable(with = PlayerFormatListSerializer::class)
        val formats: List<Format>? = null,
        @Serializable(with = PlayerFormatListSerializer::class)
        val adaptiveFormats: List<Format> = emptyList(),
        val hlsManifestUrl: String? = null,
    ) {
        @Serializable
        class Format(
            val itag: Int = -1,
            val url: String? = null,
            val mimeType: String = "",
            val bitrate: Int = 0,
            val width: Int? = null,
            val contentLength: Long? = null,
            val audioSampleRate: Int? = null,
            val audioChannels: Int? = null,
            val loudnessDb: Double? = null,
            val xtags: String? = null,
            val audioTrack: AudioTrack? = null,
            @JsonNames("signature_cipher")
            val signatureCipher: String? = null,
            val cipher: String? = null,
        ) {
            val isAudio: Boolean
                get() = width == null

            /** This format with a solved [url]: no cipher left. */
            fun withUrl(url: String?) =
                Format(itag, url, mimeType, bitrate, width, contentLength, audioSampleRate, audioChannels, loudnessDb, xtags, audioTrack)

            @Serializable
            class AudioTrack(
                val audioIsDefault: Boolean? = null,
            )
        }
    }

    @Serializable
    class PlayerConfig {
        var audioConfig: AudioConfig? = null
            private set

        @Serializable
        class AudioConfig(
            val loudnessDb: Double? = null,
        )
    }

    @Serializable
    class VideoDetails(
        val videoId: String = "",
    )

    @Serializable
    class PlaybackTracking(
        val videostatsPlaybackUrl: VideostatsUrl? = null,
    ) {
        @Serializable
        class VideostatsUrl(
            val baseUrl: String,
        )
    }
}
