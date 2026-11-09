package com.zakius.caravanserai

/**
 * The platform's player as [Playback] drives it: a list of queue items (the platform's [QueueItem], each carrying its
 * [Entry]), one of them current. ExoPlayer on Android (ExoAudio.kt), a pure-Java player on the desktop (DesktopAudio.kt). Main thread
 * only. The names and constants are Media3's, so on Android each call passes straight through.
 */
interface AudioPlayer {
    /** Builds the item for [e]. Safe on any thread, because a restored queue builds hundreds of them off the main thread. */
    fun item(e: Entry): QueueItem
    fun entryAt(index: Int): Entry?
    val currentEntry: Entry?

    val mediaItemCount: Int
    val currentMediaItemIndex: Int
    /** The song after the current one. With Repeat all, the first song follows the last. -1 when there is none. */
    val nextMediaItemIndex: Int
    fun hasNextMediaItem(): Boolean
    fun hasPreviousMediaItem(): Boolean
    fun seekToNextMediaItem()
    fun setMediaItems(items: List<QueueItem>, start: Int, positionMs: Long)
    fun addMediaItem(index: Int, item: QueueItem)
    fun addMediaItems(items: List<QueueItem>)
    fun removeMediaItem(index: Int)
    fun replaceMediaItems(from: Int, to: Int, items: List<QueueItem>)
    fun seekTo(index: Int, positionMs: Long)
    fun seekTo(positionMs: Long)

    val isPlaying: Boolean
    val playWhenReady: Boolean
    val playbackState: Int
    val playerError: Throwable?
    val currentPosition: Long
    /** The song's length in ms, or a negative value while it isn't known. */
    val duration: Long
    val speed: Float
    var volume: Float
    var repeatMode: Int
    var pauseAtEndOfMediaItems: Boolean
    fun prepare()
    fun play()
    fun pause()
    /**
     * Pressing it while buffering (waiting for the network) pauses. Play prepares an idle player and restarts an
     * ended one.
     */
    fun togglePlay()
    /** Whether the play/pause button shows Play. While buffering it shows Pause. */
    val showPlayButton: Boolean
    var listener: Listener?

    /** Blocking. Resolves [id]'s stream and caches its first [bytes], or the whole song when [bytes] is -1. */
    fun precache(id: String, bytes: Long)
    /** Whether the whole song is in the audio cache, so it plays, seeks and repeats without the network. */
    fun fullyCached(id: String): Boolean
    /**
     * Blocking. Drops every song in the audio cache. SubsonicSource.forget needs this because another server's songs
     * can have the same ids.
     */
    fun clearCache()

    interface Listener {
        /** Something changed: the list, the current item, the repeat mode, playing or not, or a seek. */
        fun onEvents(listChanged: Boolean, transition: Boolean, repeatChanged: Boolean, playingChanged: Boolean, discontinuity: Boolean)
        fun onMediaItemTransition()
        fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int)
        /** [httpStatus] is the stream's HTTP status when the server refused it. [message] is the player's own description. */
        fun onPlayerError(e: Throwable, httpStatus: Int?, message: String)
    }

    // Media3's values (androidx.media3.common.Player), which Android passes through unchanged.
    companion object {
        const val STATE_IDLE = 1
        const val STATE_BUFFERING = 2
        const val STATE_READY = 3
        const val STATE_ENDED = 4
        const val REPEAT_MODE_OFF = 0
        const val REPEAT_MODE_ONE = 1
        const val REPEAT_MODE_ALL = 2
        const val PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY = 3
        const val PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM = 5
    }
}
