package com.zakius.roma

import androidx.media3.extractor.Extractor
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.SubtitleParser

// The roma ytm flavor's parts of the Android app: its own updates and the containers YouTube serves.

fun flavorInit() = Update.init(appContext)

/**
 * Runs when the app comes back to the foreground. Once only, it tells the user that the separate dictionary app used by
 * roma ytm 0.1.57 and earlier can be uninstalled, if it is still installed.
 */
fun flavorResumed() {
    if (Store.bool("dict.appHint", false)) return
    Store.set("dict.appHint", true)
    val dictionaryInstalled = runCatching { appContext.packageManager.getPackageInfo(BuildConfig.DICT_PACKAGE, 0) }.isSuccess
    if (dictionaryInstalled) {
        toast("The \"roma Japanese dictionary\" app is no longer used: you can uninstall it", long = true)
    }
}

/** The containers ExoAudio's player reads: the WebM and MP4 that YouTube serves. */
fun containers(): Array<Extractor> {
    val noSubtitles = SubtitleParser.Factory.UNSUPPORTED
    return arrayOf(MatroskaExtractor(noSubtitles), FragmentedMp4Extractor(noSubtitles), Mp4Extractor(noSubtitles))
}
