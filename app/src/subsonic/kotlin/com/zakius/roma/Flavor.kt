package com.zakius.roma

import androidx.media3.extractor.Extractor
import androidx.media3.extractor.flac.FlacExtractor
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.ogg.OggExtractor
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.AdtsExtractor
import androidx.media3.extractor.wav.WavExtractor

// The Subsonic app's own parts of the Android app: the dictionary is a download (shared/src/portable), with nothing to
// set up, and Google Play updates the app.

fun flavorInit() {}

fun flavorResumed() {}

/** The containers ExoPlayer reads (ExoAudio): what music servers stream as it is stored (MP3, FLAC, Ogg, WAV, M4A, ADTS AAC). */
fun containers(): Array<Extractor> =
    arrayOf(Mp3Extractor(), FlacExtractor(), OggExtractor(), WavExtractor(), Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED), AdtsExtractor())

/** Google Play updates the Subsonic app: it never has an update of its own to offer (Settings asks). */
object Update {
    val available: String? = null
    val status: String? = null
    fun install() {}
}
