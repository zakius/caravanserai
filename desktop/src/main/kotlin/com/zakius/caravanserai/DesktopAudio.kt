package com.zakius.caravanserai

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * Playback's player on the desktop: a [QueuePlayer] whose engine ([Output]) decodes the current song (Media.kt) into
 * the sound card. One song follows another with no gap.
 */
class DesktopAudio : QueuePlayer({ Output(it) }) {
    /**
     * The sound card's side. One thread decodes the loaded song into a SourceDataLine, which paces it. The line stays
     * open from song to song while the format stays the same, so a song started after another's end ([load] with
     * gapless) continues the same stream of samples.
     */
    private class Output(private val player: QueuePlayer) : QueuePlayer.Engine, Runnable {
        private val lock = Object()
        private var want: String? = null // the song asked for
        private var wantAt = 0L
        private var gen = 0 // bumped on each load or seek; the thread starts over when it changes
        private var playing = false
        private var flush = false
        @Volatile override var volume = 1f
        @Volatile override var durationMs = -1L
            private set
        private var line: SourceDataLine? = null
        private var format: AudioFormat? = null
        // The position is the frames the line has played since this song's first frame went in, plus where it started.
        @Volatile private var firstFrame = 0L
        @Volatile private var fromMs = 0L
        @Volatile private var rate = 48_000
        private var written = 0L

        private val thread = Thread(this, "audio").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
        @Volatile private var source: Pcm? = null

        override fun load(id: String?, atMs: Long, play: Boolean, gapless: Boolean) = synchronized(lock) {
            want = id
            wantAt = atMs
            playing = play
            flush = !gapless
            gen++
            at(atMs)
            val l = line
            if (!gapless && l != null) {
                l.stop()
                l.flush()
            }
            durationMs = -1
            replaced()
        }

        override fun seek(ms: Long) = synchronized(lock) {
            wantAt = ms
            flush = true
            gen++
            at(ms)
            val l = line
            if (l != null) {
                l.stop()
                l.flush()
            }
            replaced()
        }

        // After a newer load or seek, the thread lets go of the old song at once, even while it waits for the network
        // (its download or roma-aac), instead of when the wait ends. The old song closes on its own thread, because
        // this runs on the UI thread under the lock the audio thread takes, so nothing here may wait for a decoder.
        private fun replaced() {
            val s = source
            if (s != null) closer.execute(s::close)
            thread.interrupt()
            lock.notifyAll()
        }
        private val closer = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task, "audio close").apply { isDaemon = true }
        }

        // Until the song's first samples go in, the position is where it was asked to start, as Media3 reports a seek.
        private fun at(ms: Long) {
            fromMs = ms
            firstFrame = Long.MAX_VALUE
        }

        override fun play(on: Boolean) = synchronized(lock) {
            playing = on
            val l = line
            if (l != null) {
                if (on) l.start() else l.stop()
            }
            lock.notifyAll()
        }

        override fun positionMs(): Long {
            val l = line ?: return fromMs
            val played = (l.longFramePosition - firstFrame).coerceAtLeast(0)
            return fromMs + played * 1000 / rate
        }

        override fun run() {
            while (true) {
                try {
                    Thread.interrupted() // clears an interrupt meant for the previous song
                    val (id, at, g) = synchronized(lock) {
                        while (want == null || !playing && line == null) lock.wait()
                        Triple(want!!, wantAt, gen)
                    }
                    try {
                        playSong(id, at, g)
                    } catch (t: Throwable) {
                        // A newer load or seek ended this one, by an interrupt or by closing its source. That isn't a
                        // failure.
                        if (t is InterruptedException || synchronized(lock) { gen } != g) continue
                        synchronized(lock) { if (gen == g) want = null }
                        postMain { player.failed(id, t) }
                    }
                } catch (_: InterruptedException) {
                }
            }
        }

        // Plays [id] from [at] until it ends or another load or seek comes, which changes gen.
        private fun playSong(id: String, at: Long, g: Int) {
            val src = retrying({ synchronized(lock) { gen } != g }) { pcm(StreamFile.of(id), at) } ?: return
            source = src
            try {
                if (synchronized(lock) { gen } == g) playPcm(id, src, at, g)
            } finally {
                source = null
                src.close()
            }
        }

        private fun playPcm(id: String, src: Pcm, at: Long, g: Int) {
            durationMs = src.durationMs
            val f = AudioFormat(src.sampleRate.toFloat(), 16, src.channels, true, false)
            val l = openLine(f, g) ?: return
            val pcm = ShortArray(MAX_PACKET_SAMPLES * src.channels)
            val bytes = ByteArray(pcm.size * 2)
            var first = true
            while (true) {
                if (synchronized(lock) { gen } != g) return
                val n = retrying({ synchronized(lock) { gen } != g }) { src.read(pcm) } ?: return
                if (n < 0) break
                val v = volume
                var k = 0
                for (i in 0 until n * src.channels) {
                    val s = (pcm[i] * v).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    // 16-bit little-endian, the line's format.
                    bytes[k++] = s.toByte()
                    bytes[k++] = (s shr 8).toByte()
                }
                if (first) {
                    synchronized(lock) {
                        if (gen != g) return
                        firstFrame = written
                        fromMs = at
                        rate = src.sampleRate
                        if (playing) l.start()
                    }
                    first = false
                    postMain { player.started(id) }
                }
                var off = 0
                while (off < k) {
                    if (synchronized(lock) { gen } != g) return
                    if (!synchronized(lock) { playing }) {
                        synchronized(lock) { if (!playing && gen == g) lock.wait(200) }
                        continue
                    }
                    val m = l.write(bytes, off, k - off)
                    off += m
                    written += m / (2 * src.channels)
                }
            }
            synchronized(lock) {
                if (gen != g) return
                want = null
            }
            postMain { player.ended(id) }
        }

        // The line for [f]: the open one while the format stays the same (gapless), or else a new one.
        private fun openLine(f: AudioFormat, g: Int): SourceDataLine? = synchronized(lock) {
            if (gen != g) return null
            val l = line
            if (l != null && format?.matches(f) == true) {
                if (flush) {
                    l.stop()
                    l.flush()
                    written = l.longFramePosition
                }
                return l
            }
            if (l != null) {
                l.drain()
                l.close()
            }
            val n = AudioSystem.getSourceDataLine(f)
            val quarterSecond = (f.sampleRate * f.frameSize / 4).toInt()
            n.open(f, quarterSecond)
            line = n
            format = f
            written = 0
            n
        }
    }
}
