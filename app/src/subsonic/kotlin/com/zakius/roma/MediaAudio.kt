package com.zakius.roma

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/** A queue entry as the player holds it. */
typealias QueueItem = Entry

/**
 * Playback's player in the Subsonic app: a [QueuePlayer] over Android's own MediaPlayer, which decodes what music
 * servers stream (MP3, FLAC, Ogg, WAV, AAC, M4A) without Media3 (about a fifth of the APK). The stream comes through
 * the audio cache (StreamFile, read by [Source]), so a song replays without the network. What ExoPlayer did by itself
 * is here: audio focus, pausing when the headphones go, the wake and Wi-Fi locks while playing.
 * ponytail: a song starts once the one before has ended, so there's MediaPlayer's start-up between them (tens of ms,
 * the next song's first bytes being on disk); setNextMediaPlayer would make it gapless.
 */
class MediaAudio(c: Context) : QueuePlayer({ Engine(c, it) }) {
    private val audio = c.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private var focusLost = false // a call or another app took the audio for a while: back when it returns
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes).setOnAudioFocusChangeListener({ change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (playWhenReady) { focusLost = true; pause(4) } // Media3's PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS
            AudioManager.AUDIOFOCUS_GAIN -> if (focusLost) { focusLost = false; play() }
        }
    }, Handler(Looper.getMainLooper())).build()
    private val wifi = c.getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "roma:audio").apply { setReferenceCounted(false) }
    private val headphones = Headphones { play() }
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { if (playWhenReady) pause(AudioPlayer.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) }
    }
    private var noisyOn = false

    override fun play() {
        if (audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_FAILED) return
        focusLost = false
        headphones.cleared()
        wifi.acquire()
        if (!noisyOn) { appContext.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)); noisyOn = true }
        super.play()
    }

    // Paused, it lets go of what playing holds; a transient focus loss keeps the focus request, so the song comes back.
    override fun pause(reason: Int) {
        super.pause(reason)
        if (reason == AudioPlayer.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) headphones.unplugged() else headphones.cleared()
        if (!focusLost) audio.abandonAudioFocusRequest(focus)
        wifi.release()
        if (noisyOn) { appContext.unregisterReceiver(noisy); noisyOn = false }
    }

    init {
        Startup.later { headphones.watch() }
    }

    /**
     * One MediaPlayer per song, from the audio cache. Its own threads read [Source], which waits for the download;
     * a newer load or seek closes the old one's reads at once.
     */
    private class Engine(private val c: Context, private val player: QueuePlayer) : QueuePlayer.Engine {
        private var mp: MediaPlayer? = null
        private var source: Source? = null
        private var id: String? = null
        private var prepared = false
        private var playing = false
        private var startAt = 0L
        override var durationMs = -1L
            private set
        override var volume = 1f
            set(v) { field = v; mp?.setVolume(v, v) }

        override fun load(id: String?, atMs: Long, play: Boolean, gapless: Boolean) {
            release()
            this.id = id
            playing = play
            startAt = atMs
            durationMs = -1
            if (id == null) return
            val src = Source(StreamFile.of(id))
            source = src
            val m = MediaPlayer()
            mp = m
            m.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            m.setWakeMode(c, PowerManager.PARTIAL_WAKE_LOCK)
            m.setVolume(volume, volume)
            m.setOnPreparedListener {
                if (mp !== m) return@setOnPreparedListener
                prepared = true
                durationMs = m.duration.toLong().takeIf { it > 0 } ?: -1
                if (startAt > 0) m.seekTo(startAt, MediaPlayer.SEEK_CLOSEST)
                if (playing) m.start()
                player.started(id)
            }
            m.setOnCompletionListener { if (mp === m) { prepared = false; player.ended(id) } }
            m.setOnErrorListener { _, what, extra ->
                if (mp === m) {
                    val e = src.failure ?: java.io.IOException("Couldn't play this song (MediaPlayer $what/$extra)")
                    startAt = positionMs()
                    release()
                    player.failed(id, e)
                }
                true
            }
            m.setDataSource(src)
            m.prepareAsync()
        }

        override fun seek(ms: Long) {
            startAt = ms
            if (prepared) mp?.seekTo(ms, MediaPlayer.SEEK_CLOSEST)
        }

        override fun play(on: Boolean) {
            playing = on
            val m = mp ?: return
            if (!prepared) return
            if (on) m.start() else m.pause()
        }

        override fun positionMs(): Long = if (prepared) mp?.currentPosition?.toLong() ?: startAt else startAt

        private fun release() {
            source?.closed = true
            source = null
            mp?.let { it.setOnErrorListener(null); it.release() }
            mp = null
            prepared = false
        }
    }

    /** A song's stream for MediaPlayer: read from the audio cache with Media3's patience for the network ([retrying]). */
    private class Source(private val f: StreamFile) : MediaDataSource() {
        @Volatile var closed = false
        /** Why the last read failed, for the player's error. */
        @Volatile var failure: java.io.IOException? = null

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (size == 0) return 0
            return try {
                val n = retrying({ closed }) { f.read(position, buffer, offset, size) { closed } } ?: return -1
                if (n <= 0) -1 else n
            } catch (e: java.io.IOException) {
                failure = e
                throw e
            }
        }

        // Unknown until the first answer gives it (or a stream without a length ends): MediaPlayer then reads to the end.
        override fun getSize(): Long {
            if (f.total < 0 && !closed) runCatching { readAt(0, ByteArray(1), 0, 1) }
            return f.total
        }

        override fun close() { closed = true }
    }
}
