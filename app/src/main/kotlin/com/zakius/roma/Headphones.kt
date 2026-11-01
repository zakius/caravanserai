package com.zakius.roma

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * Opt-in: headphones, Bluetooth earbuds or a car that come back within an hour of pausing the song by going away
 * resume it ([resume]). Only that pause counts ([unplugged]); any other change of play or pause [cleared] it, so a song
 * the user paused stays paused. The system calls back on device changes; nothing polls.
 */
class Headphones(private val resume: () -> Unit) {
    // When the headphones went away and paused the song (Media3's "becoming noisy"); 0 otherwise.
    private var unpluggedAt = 0L

    fun unplugged() { unpluggedAt = elapsedRealtime() }
    fun cleared() { unpluggedAt = 0L }

    fun watch() {
        appContext.getSystemService(AudioManager::class.java).registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<AudioDeviceInfo>) {
                if (unpluggedAt == 0L || !Store.bool("play.resumeOnHeadphones", false)) return
                if (elapsedRealtime() - unpluggedAt > 3600_000) return
                if (added.none { it.isSink && it.type in HEADPHONES }) return
                unpluggedAt = 0L
                resume()
            }
        }, Handler(Looper.getMainLooper()))
    }

    private val HEADPHONES = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_USB_HEADSET,
        26 /* TYPE_BLE_HEADSET */, 27 /* TYPE_BLE_SPEAKER */, 23 /* TYPE_HEARING_AID */,
    )
}
