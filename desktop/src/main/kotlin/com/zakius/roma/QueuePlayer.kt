package com.zakius.roma

/** Media3's PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST, which AudioPlayer doesn't name. */
private const val REASON_USER_REQUEST = 1

/**
 * Playback's player on the desktop, where Media3 isn't available. The list, the repeat modes and the transitions are
 * kept here with Media3's meaning, so Playback behaves as it does with ExoPlayer. An [Engine] (DesktopAudio's Output)
 * plays the current song. The streams go through the audio cache (StreamFile), so a song played once replays without
 * the network, and the next song's first bytes are there before it starts. Everything here runs on the main thread,
 * and the engine posts its reports to it.
 */
open class QueuePlayer(engine: (QueuePlayer) -> Engine) : AudioPlayer {
    private val out = engine(this)
    private val list = ArrayList<Entry>()
    private var index = 0
    private var startAt = 0L // where the current song starts when it's loaded
    private var loaded: String? = null // the song Output has, or null
    private var announced: String? = null // the song the last onMediaItemTransition was for
    private var state = AudioPlayer.STATE_IDLE
    private var error: Throwable? = null

    override var listener: AudioPlayer.Listener? = null

    init {
        Startup.later {
            val trim = Thread(StreamFile::trim, "audio cache")
            trim.isDaemon = true
            trim.start()
        }
    }

    /**
     * The audio's engine. It reports when the song it was given starts playing ([started]), ends ([ended]) or fails
     * ([failed]).
     */
    interface Engine {
        /**
         * Loads [id] at [atMs], or stops when [id] is null, and plays if [play]. [gapless] means it follows the song
         * that just ended.
         */
        fun load(id: String?, atMs: Long, play: Boolean, gapless: Boolean)
        fun seek(ms: Long)
        fun play(on: Boolean)
        fun positionMs(): Long
        val durationMs: Long
        var volume: Float
    }

    override fun item(e: Entry) = e
    override fun entryAt(index: Int) = list.getOrNull(index)
    override val currentEntry get() = list.getOrNull(index)
    override val mediaItemCount get() = list.size
    override val currentMediaItemIndex get() = index
    override val nextMediaItemIndex get() = step(1)
    override fun hasNextMediaItem() = step(1) >= 0
    override fun hasPreviousMediaItem() = step(-1) >= 0
    override fun seekToNextMediaItem() {
        val next = step(1)
        if (next >= 0) seekTo(next, 0)
    }

    // Media3's navigation: Repeat one counts as off, and Repeat all wraps around.
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
        list.clear()
        list.addAll(items)
        // A new list is a transition even to the same song, because ExoPlayer's new items carry new tags.
        announced = null
        moveTo(start.coerceIn(0, maxOf(0, list.size - 1)), positionMs, listChanged = true)
    }

    override fun addMediaItem(index: Int, item: Entry) {
        list.add(index, item)
        if (index <= this.index && list.size > 1) this.index++
        changed(list = true)
    }

    override fun addMediaItems(items: List<Entry>) {
        list.addAll(items)
        changed(list = true)
    }

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
        if (index >= to) {
            // The range was before the current song.
            index += items.size - (to - from)
        } else if (index >= from && list.getOrNull(index)?.song?.id != current?.song?.id) {
            // The current song was replaced by another. Replacing it with itself (a romanization redo) keeps it playing.
            return moveTo(index.coerceAtMost(list.size - 1), 0, listChanged = true)
        }
        changed(list = true)
    }

    override fun seekTo(index: Int, positionMs: Long) {
        if (index == this.index && loaded != null) return seekTo(positionMs)
        moveTo(index, positionMs, listChanged = false)
    }

    override fun seekTo(positionMs: Long) {
        if (state == AudioPlayer.STATE_ENDED) {
            startAt = positionMs
            state = AudioPlayer.STATE_IDLE
            if (playWhenReady) prepare()
        } else if (loaded != null) {
            out.seek(positionMs)
        } else {
            startAt = positionMs
        }
        changed(discontinuity = true)
    }

    private fun moveTo(i: Int, positionMs: Long, listChanged: Boolean, gapless: Boolean = false) {
        val transition = i != index || list.getOrNull(i)?.song?.id != announced
        index = i
        startAt = positionMs
        if (list.isEmpty()) {
            unload()
            state = AudioPlayer.STATE_IDLE
            changed(list = listChanged)
            return
        }
        if (transition) {
            announced = list[i].song.id
            listener?.onMediaItemTransition()
        }
        if (playWhenReady || state != AudioPlayer.STATE_IDLE) prepare(gapless) else unload()
        changed(list = listChanged, transition = transition, discontinuity = true)
    }

    private fun unload() {
        loaded = null
        out.load(null, 0, play = false, gapless = false)
    }

    final override var playWhenReady = false
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
        set(v) {
            field = v
            changed(repeat = true)
        }
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
        if (!playWhenReady) {
            playWhenReady = true
            listener?.onPlayWhenReadyChanged(true, REASON_USER_REQUEST)
        }
        if (loaded != null && state != AudioPlayer.STATE_IDLE) out.play(true) else prepare()
        changed(playing = true)
    }

    override fun pause() = pause(REASON_USER_REQUEST)

    /** Pauses for [reason], one of Media3's PLAY_WHEN_READY_CHANGE_REASON_… values. */
    open fun pause(reason: Int) {
        if (playWhenReady) {
            playWhenReady = false
            listener?.onPlayWhenReadyChanged(false, reason)
        }
        out.play(false)
        changed(playing = true)
    }

    override val showPlayButton get() = !playWhenReady || state == AudioPlayer.STATE_IDLE || state == AudioPlayer.STATE_ENDED

    override fun togglePlay() {
        if (!showPlayButton) return pause()
        if (state == AudioPlayer.STATE_ENDED) {
            state = AudioPlayer.STATE_IDLE
            startAt = 0
        }
        play()
    }

    // The engine's reports, posted to the main thread.

    fun started(id: String) {
        if (id != loaded || state == AudioPlayer.STATE_READY) return
        state = AudioPlayer.STATE_READY
        changed(playing = true)
    }

    // The song's last samples are written, so the next song follows at once, as in Media3. With Repeat one the same
    // song plays again, and at the end of the list with Repeat off the player ends.
    fun ended(id: String) {
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

    fun failed(id: String, t: Throwable) {
        if (id != loaded) return
        // Retry, or Playback's retry after a refused URL, resumes from where it stopped.
        startAt = out.positionMs()
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

    override fun clearCache() = StreamFile.clear()

    private fun changed(
        list: Boolean = false,
        transition: Boolean = false,
        repeat: Boolean = false,
        playing: Boolean = false,
        discontinuity: Boolean = false,
    ) = listener?.onEvents(list, transition, repeat, playing, discontinuity)
}
