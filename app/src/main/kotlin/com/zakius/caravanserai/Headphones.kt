package com.zakius.caravanserai

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * An opt-in setting. When headphones, Bluetooth earbuds or a car disconnect, the song pauses. If they come back within an
 * hour, [resume] plays it again. Only a pause caused by the disconnect counts ([unplugged]). Any other play or pause
 * calls [cleared], so a song the user paused stays paused. The system calls back on device changes, so nothing polls.
 */
class Headphones(private val resume: () -> Unit) {
    // When a disconnect paused the song (Media3's "becoming noisy" event), or 0 if it did not.
    private var unpluggedAt = 0L

    fun unplugged() {
        unpluggedAt = elapsedRealtime()
    }

    fun cleared() {
        unpluggedAt = 0L
    }

    fun watch() {
        appContext.getSystemService(AudioManager::class.java).registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<AudioDeviceInfo>) {
                if (unpluggedAt == 0L || !Store.bool("play.resumeOnHeadphones", false)) return
                if (elapsedRealtime() - unpluggedAt > RESUME_WITHIN_MS) return
                if (added.none { device -> device.isSink && device.type in HEADPHONES }) return
                unpluggedAt = 0L
                resume()
            }
        }, Handler(Looper.getMainLooper()))
    }

    private val RESUME_WITHIN_MS = 60 * 60 * 1000L

    // 23, 26 and 27 are spelled as numbers because their AudioDeviceInfo constants are newer than the minimum SDK.
    private val HEADPHONES = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_USB_HEADSET,
        26 /* TYPE_BLE_HEADSET */, 27 /* TYPE_BLE_SPEAKER */, 23 /* TYPE_HEARING_AID */,
    )
}
