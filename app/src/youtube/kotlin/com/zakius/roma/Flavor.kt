package com.zakius.roma

import android.content.Context

// roma's own parts of the Android app's start: the dictionary app, its own updates, ExoPlayer.

fun flavorInit() {
    Dictionary.init()
    Update.init(appContext)
}

/** Back in the app: the dictionary's install may go on. */
fun flavorResumed() = Dictionary.resumed()

fun platformPlayer(c: Context): AudioPlayer = ExoAudio(c)
