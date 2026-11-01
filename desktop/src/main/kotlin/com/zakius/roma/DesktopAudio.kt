package com.zakius.roma

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * Playback's player on the desktop. The list, the repeat modes and the transitions are kept here with Media3's
 * meaning, so Playback behaves as on Android; [Output] decodes the current song (Media.kt) into the sound card. The
 * streams go through the audio cache (StreamFile), so a song played once replays without the network, and the next
 * song's first bytes are there before it starts: one song follows another with no gap. Main thread, except
 * Output's thread, whose news hops to it.
 */
class DesktopAudio : AudioPlayer {
    private val out = Output()
    private val list = ArrayList<Entry>()
    private var index = 0
    private var startAt = 0L // where the current song starts when it's loaded
    private var loaded: String? = null // the song Output has, or null
    private var announced: String? = null // the song the last onMediaItemTransition was for
    private var state = AudioPlayer.STATE_IDLE
    private var error: Throwable? = null

    override var listener: AudioPlayer.Listener? = null

    init {
        Startup.later { Thread(StreamFile::trim, "audio cache").apply { isDaemon = true }.start() }
    }

    override fun item(e: Entry) = e
    override fun entryAt(index: Int) = list.getOrNull(index)
    override val currentEntry get() = list.getOrNull(index)
    override val mediaItemCount get() = list.size
    override val currentMediaItemIndex get() = index
    override val nextMediaItemIndex get() = step(1)
    override fun hasNextMediaItem() = step(1) >= 0
    override fun hasPreviousMediaItem() = step(-1) >= 0
    override fun seekToNextMediaItem() { step(1).takeIf { it >= 0 }?.let { seekTo(it, 0) } }

    // Media3's navigation: Repeat one counts as off, Repeat all wraps.
    private fun step(dir: Int): Int {
        val j = index + dir
        return when {
            list.isEmpty() -> -1
            j in list.indices -> j
            repeatMode == AudioPlayer.REPEAT_MODE_ALL -> if (dir > 0) 0 else list.size - 1
            else -> -1
        }
    }

    override fun setMediaItems(items: List<Entry>, start: Int, positionMs: Long) {
        list.clear(); list.addAll(items)
        announced = null // a new list is a transition even to the same song: ExoPlayer's new items are new tags
        moveTo(start.coerceIn(0, maxOf(0, list.size - 1)), positionMs, listChanged = true)
    }

    override fun addMediaItem(index: Int, item: Entry) {
        list.add(index, item)
        if (index <= this.index && list.size > 1) this.index++
        changed(list = true)
    }

    override fun addMediaItems(items: List<Entry>) { list.addAll(items); changed(list = true) }

    override fun removeMediaItem(index: Int) {
        if (index !in list.indices) return
        list.removeAt(index)
        when {
            index < this.index -> this.index--
            index == this.index -> return moveTo(this.index.coerceIn(0, maxOf(0, list.size - 1)), 0, listChanged = true)
        }
        changed(list = true)
    }

    override fun replaceMediaItems(from: Int, to: Int, items: List<Entry>) {
        val current = list.getOrNull(index)
        for (i in from until to) list.removeAt(from)
        list.addAll(from, items)
        if (index >= to) index += items.size - (to - from) // the range was before the current song
        // Replacing the current song with itself (a romanization redo) keeps it playing.
        else if (index >= from && list.getOrNull(index)?.song?.id != current?.song?.id) return moveTo(index.coerceAtMost(list.size - 1), 0, listChanged = true)
        changed(list = true)
    }

    override fun seekTo(index: Int, positionMs: Long) {
        if (index == this.index && loaded != null) return seekTo(positionMs)
        moveTo(index, positionMs, listChanged = false)
    }

    override fun seekTo(positionMs: Long) {
        if (state == AudioPlayer.STATE_ENDED) { startAt = positionMs; state = AudioPlayer.STATE_IDLE; if (playWhenReady) prepare() }
        else if (loaded != null) out.seek(positionMs) else startAt = positionMs
        changed(discontinuity = true)
    }

    private fun moveTo(i: Int, positionMs: Long, listChanged: Boolean, gapless: Boolean = false) {
        val transition = i != index || list.getOrNull(i)?.song?.id != announced
        index = i
        startAt = positionMs
        if (list.isEmpty()) { unload(); state = AudioPlayer.STATE_IDLE; changed(list = listChanged); return }
        if (transition) { announced = list[i].song.id; listener?.onMediaItemTransition() }
        if (playWhenReady || state != AudioPlayer.STATE_IDLE) prepare(gapless) else unload()
        changed(list = listChanged, transition = transition, discontinuity = true)
    }

    private fun unload() {
        loaded = null
        out.load(null, 0, play = false, gapless = false)
    }

    override var playWhenReady = false
        private set
    override val playbackState get() = state
    override val playerError get() = error
    override val isPlaying get() = playWhenReady && state == AudioPlayer.STATE_READY
    override val currentPosition: Long get() = if (loaded != null && state != AudioPlayer.STATE_ENDED) out.positionMs() else startAt
    override val duration: Long get() = if (loaded != null) out.durationMs else -1
    override val speed = 1f
    override var volume
        get() = out.volume
        set(v) { out.volume = v }
    override var repeatMode = AudioPlayer.REPEAT_MODE_OFF
        set(v) { field = v; changed(repeat = true) }
    override var pauseAtEndOfMediaItems = false

    override fun prepare() = prepare(gapless = false)

    private fun prepare(gapless: Boolean) {
        val e = list.getOrNull(index) ?: return
        error = null
        state = AudioPlayer.STATE_BUFFERING
        loaded = e.song.id
        out.load(e.song.id, startAt, playWhenReady, gapless)
        changed()
    }

    override fun play() {
        if (!playWhenReady) { playWhenReady = true; listener?.onPlayWhenReadyChanged(true, 1) }
        if (loaded != null && state != AudioPlayer.STATE_IDLE) out.play(true) else prepare()
        changed(playing = true)
    }

    override fun pause() {
        if (playWhenReady) { playWhenReady = false; listener?.onPlayWhenReadyChanged(false, 1) }
        out.play(false)
        changed(playing = true)
    }

    override val showPlayButton get() = !playWhenReady || state == AudioPlayer.STATE_IDLE || state == AudioPlayer.STATE_ENDED

    override fun togglePlay() {
        if (!showPlayButton) return pause()
        if (state == AudioPlayer.STATE_ENDED) { state = AudioPlayer.STATE_IDLE; startAt = 0 }
        play()
    }

    // ---- What Output reports, on the main thread ----

    private fun started(id: String) {
        if (id != loaded || state == AudioPlayer.STATE_READY) return
        state = AudioPlayer.STATE_READY
        changed(playing = true)
    }

    // The song's last samples are written: the next one follows at once, as Media3 does (Repeat one: the same again;
    // at the end with Repeat off: ended).
    private fun ended(id: String) {
        if (id != loaded) return
        val next = if (repeatMode == AudioPlayer.REPEAT_MODE_ONE) index else step(1)
        announced = null // the same song again (Repeat one) is a transition too, as in Media3
        if (pauseAtEndOfMediaItems) {
            playWhenReady = false
            listener?.onPlayWhenReadyChanged(false, AudioPlayer.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)
        }
        if (next < 0) {
            state = AudioPlayer.STATE_ENDED
            loaded = null
            changed(playing = true)
            return
        }
        loaded = null
        moveTo(next, 0, listChanged = false, gapless = playWhenReady)
    }

    private fun failed(id: String, t: Throwable) {
        if (id != loaded) return
        startAt = out.positionMs() // where it stopped: Retry, or Playback's retry after a refused URL, goes on from there
        unload()
        error = t
        state = AudioPlayer.STATE_IDLE
        changed(playing = true)
        listener?.onPlayerError(t, (t as? StreamRefused)?.status, t.message ?: "Couldn't play this song")
    }

    override fun precache(id: String, bytes: Long) {
        val f = StreamFile.of(id)
        f.fill(bytes)
        check(f.have > 0) { "Couldn't download $id" }
    }

    override fun fullyCached(id: String) = StreamFile.of(id).complete

    private fun changed(list: Boolean = false, transition: Boolean = false, repeat: Boolean = false, playing: Boolean = false, discontinuity: Boolean = false) =
        listener?.onEvents(list, transition, repeat, playing, discontinuity)

    /**
     * The sound card's side: one thread decodes the loaded song into a SourceDataLine, which paces it. The line stays
     * open from song to song while the format holds, so a song started after another's end ([load] with gapless)
     * continues the same stream of samples.
     */
    private inner class Output : Runnable {
        private val lock = Object()
        private var want: String? = null // the song asked for
        private var wantAt = 0L
        private var gen = 0 // bumped per load or seek; the thread starts over when it changes
        private var playing = false
        private var flush = false
        @Volatile var volume = 1f
        @Volatile var durationMs = -1L
            private set
        private var line: SourceDataLine? = null
        private var format: AudioFormat? = null
        // Position: frames the line has played since this song's first frame went in, plus where it started.
        @Volatile private var firstFrame = 0L
        @Volatile private var fromMs = 0L
        @Volatile private var rate = 48_000
        private var written = 0L

        private val thread = Thread(this, "audio").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }
        @Volatile private var source: Pcm? = null

        fun load(id: String?, atMs: Long, play: Boolean, gapless: Boolean) = synchronized(lock) {
            want = id; wantAt = atMs; playing = play; flush = !gapless; gen++
            at(atMs)
            if (!gapless) line?.let { it.stop(); it.flush() }
            durationMs = -1
            replaced()
        }

        fun seek(ms: Long) = synchronized(lock) { wantAt = ms; flush = true; gen++; at(ms); line?.let { it.stop(); it.flush() }; replaced() }

        // A newer load or seek: the thread lets go of the old song at once, even when it waits for the network (its
        // download or roma-aac), instead of when the wait ends.
        private fun replaced() {
            source?.close()
            thread.interrupt()
            lock.notifyAll()
        }

        // Until the song's first samples go in, the position is where it was asked to start, as Media3 reports a seek.
        private fun at(ms: Long) { fromMs = ms; firstFrame = Long.MAX_VALUE }

        fun play(on: Boolean) = synchronized(lock) {
            playing = on
            line?.let { if (on) it.start() else it.stop() }
            lock.notifyAll()
        }

        fun positionMs(): Long {
            val l = line ?: return fromMs
            val played = (l.longFramePosition - firstFrame).coerceAtLeast(0)
            return fromMs + played * 1000 / rate
        }

        override fun run() {
            while (true) {
                try {
                    Thread.interrupted() // one meant for the song before
                    val (id, at, g) = synchronized(lock) {
                        while (want == null || !playing && line == null) lock.wait()
                        Triple(want!!, wantAt, gen)
                    }
                    try {
                        playSong(id, at, g)
                    } catch (t: Throwable) {
                        // A newer load or seek ended this one (interrupted, its source closed): not a failure.
                        if (t is InterruptedException || synchronized(lock) { gen } != g) continue
                        synchronized(lock) { if (gen == g) want = null }
                        postMain { failed(id, t) }
                    }
                } catch (_: InterruptedException) {
                }
            }
        }

        // Plays [id] from [at] until it ends or another load or seek comes (gen changes).
        private fun playSong(id: String, at: Long, g: Int) {
            val src = retrying(g) { pcm(StreamFile.of(id), at) } ?: return
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
            val pcm = ShortArray(5760 * src.channels)
            val bytes = ByteArray(pcm.size * 2)
            var first = true
            while (true) {
                if (synchronized(lock) { gen } != g) return
                val n = retrying(g) { src.read(pcm) } ?: return
                if (n < 0) break
                val v = volume
                var k = 0
                for (i in 0 until n * src.channels) {
                    val s = (pcm[i] * v).toInt().coerceIn(-32768, 32767)
                    bytes[k++] = s.toByte(); bytes[k++] = (s shr 8).toByte()
                }
                if (first) {
                    synchronized(lock) {
                        if (gen != g) return
                        firstFrame = written; fromMs = at; rate = src.sampleRate
                        if (playing) l.start()
                    }
                    first = false
                    postMain { started(id) }
                }
                var off = 0
                while (off < k) {
                    if (synchronized(lock) { gen } != g) return
                    if (!synchronized(lock) { playing }) { synchronized(lock) { if (!playing && gen == g) lock.wait(200) }; continue }
                    val m = l.write(bytes, off, k - off)
                    off += m
                    written += m / (2 * src.channels)
                }
            }
            synchronized(lock) { if (gen != g) return; want = null }
            postMain { ended(id) }
        }

        // The line for [f]: the open one while the format holds (gapless), else a new one.
        private fun openLine(f: AudioFormat, g: Int): SourceDataLine? = synchronized(lock) {
            if (gen != g) return null
            val l = line
            if (l != null && format?.matches(f) == true) { if (flush) { l.stop(); l.flush(); written = l.longFramePosition } ; return l }
            l?.let { it.drain(); it.close() }
            val n = AudioSystem.getSourceDataLine(f)
            n.open(f, (f.sampleRate * f.frameSize / 4).toInt()) // 250 ms
            line = n; format = f; written = 0
            n
        }

        /**
         * [f] with Android's patience (ExoAudio's WaitForNetwork): with no connection it is tried again every 2 s for
         * up to 10 minutes, the player buffering meanwhile; other errors 3 times; a refused URL fails at once, for
         * Playback to look it up again. Null when a newer load or seek came meanwhile.
         */
        private fun <T> retrying(g: Int, f: () -> T): T? {
            var tries = 0
            while (true) {
                if (synchronized(lock) { gen } != g) return null
                try { return f() } catch (e: java.io.IOException) {
                    tries++
                    if (e is StreamRefused || if (noConnection(e)) tries > 300 else tries > 3) throw e
                    Thread.sleep(2_000)
                }
            }
        }

        private fun noConnection(e: Throwable): Boolean = generateSequence(e) { it.cause }.any {
            it is java.net.UnknownHostException || it is java.net.ConnectException || it is java.net.SocketTimeoutException ||
                it is java.net.NoRouteToHostException || it is java.net.SocketException || (it as? roma.core.StreamException)?.offline == true
        }
    }

}
