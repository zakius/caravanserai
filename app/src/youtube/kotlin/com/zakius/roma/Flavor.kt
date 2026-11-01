package com.zakius.roma

import androidx.media3.extractor.Extractor
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.SubtitleParser

// roma's own parts of the Android app: its own updates, the containers YouTube serves.

fun flavorInit() = Update.init(appContext)

/** Back in the app: once, if the dictionary app of roma ytm 0.1.57 and earlier is still installed, that it can go. */
fun flavorResumed() {
    if (Store.bool("dict.appHint", false)) return
    Store.set("dict.appHint", true)
    if (runCatching { appContext.packageManager.getPackageInfo(BuildConfig.DICT_PACKAGE, 0) }.isSuccess) toast("The \"roma Japanese dictionary\" app is no longer used: you can uninstall it", long = true)
}

/** The containers ExoPlayer reads (ExoAudio): YouTube's, WebM and MP4. */
fun containers(): Array<Extractor> = SubtitleParser.Factory.UNSUPPORTED.let { arrayOf(MatroskaExtractor(it), FragmentedMp4Extractor(it), Mp4Extractor(it)) }
