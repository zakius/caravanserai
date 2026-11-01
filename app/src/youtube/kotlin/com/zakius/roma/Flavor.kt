package com.zakius.roma

import androidx.media3.extractor.Extractor
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.SubtitleParser

// roma's own parts of the Android app: the dictionary app, its own updates, the containers YouTube serves.

fun flavorInit() {
    Dictionary.init()
    Update.init(appContext)
}

/** Back in the app: the dictionary's install may go on. */
fun flavorResumed() = Dictionary.resumed()

/** The containers ExoPlayer reads (ExoAudio): YouTube's, WebM and MP4. */
fun containers(): Array<Extractor> = SubtitleParser.Factory.UNSUPPORTED.let { arrayOf(MatroskaExtractor(it), FragmentedMp4Extractor(it), Mp4Extractor(it)) }
