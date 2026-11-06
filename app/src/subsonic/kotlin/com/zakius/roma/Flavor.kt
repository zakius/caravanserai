package com.zakius.roma

import androidx.media3.extractor.Extractor
import androidx.media3.extractor.flac.FlacExtractor
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.ogg.OggExtractor
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.AdtsExtractor
import androidx.media3.extractor.wav.WavExtractor

// The caravan (Subsonic) flavor's parts of the Android app. It has nothing to set up, and Google Play updates it.

fun flavorInit() {}

fun flavorResumed() {}

/**
 * The containers ExoAudio's player reads. Music servers stream files as they are stored (MP3, FLAC, Ogg, WAV, M4A, ADTS
 * AAC). Servers that relay streaming services, such as oasis with YouTube Music songs, send fragmented MP4 and WebM.
 */
fun containers(): Array<Extractor> {
    val noSubtitles = SubtitleParser.Factory.UNSUPPORTED
    return arrayOf(
        Mp3Extractor(),
        FlacExtractor(),
        OggExtractor(),
        WavExtractor(),
        FragmentedMp4Extractor(noSubtitles),
        Mp4Extractor(noSubtitles),
        MatroskaExtractor(noSubtitles),
        AdtsExtractor(),
    )
}

/** Settings asks for an update, but Google Play updates this app, so it never has one of its own to offer. */
object Update {
    val available: String? = null
    val status: String? = null
    fun install() {}
}
