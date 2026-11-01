package com.zakius.roma

import android.content.Context

// The Subsonic app's own parts of the Android app's start: Android's MediaPlayer instead of ExoPlayer; the dictionary
// is a download (shared/src/portable), with nothing to set up.

fun flavorInit() {}

fun flavorResumed() {}

fun platformPlayer(c: Context): AudioPlayer = MediaAudio(c)

/** Google Play updates the Subsonic app: it never has an update of its own to offer (Settings asks). */
object Update {
    val available: String? = null
    val status: String? = null
    fun install() {}
}
