package com.zakius.roma

import android.content.Context
import android.net.Uri
import android.os.Handler
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import roma.core.StreamException
import java.io.File

/** A queue entry as ExoPlayer holds it: a media item tagged with its [Entry]. */
typealias QueueItem = MediaItem

/**
 * Playback's player on Android: Media3 ExoPlayer, audio only, over a 1 GB disk cache. Media items carry
 * roma://<videoId>; the stream URL is looked up ([Playback.streamUrl]) only when bytes are needed and not cached.
 */
class ExoAudio(c: Context) : AudioPlayer {
    val exo: ExoPlayer
    private val cache: SimpleCache
    private val cached: CacheDataSource.Factory

    init {
        cache = SimpleCache(File(c.cacheDir, "audio"), LeastRecentlyUsedCacheEvictor(1L shl 30), StandaloneDatabaseProvider(c))
        val http = DefaultHttpDataSource.Factory()
        // Media items carry roma://<videoId>; the stream URL is looked up only when bytes are needed and not cached.
        val resolving = ResolvingDataSource.Factory(http) { spec ->
            if (spec.uri.scheme != "roma") spec else spec.withUri(Uri.parse(Playback.streamUrl(spec.uri.host!!)))
        }
        val source = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(resolving)
        cached = source
        // Start after 0.5 s of audio instead of ExoPlayer's 2.5 s; audio downloads far faster than it plays. It buffers
        // 50 s ahead, or with "Download songs ahead" the whole song (at most 10 min).
        val load = DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 10 * 60_000, 500, 2_000).build()
        // Audio only, in the containers the app's source serves (the flavor's containers()): the default factories
        // bring video, text and other containers that R8 can't remove.
        val audio = RenderersFactory { handler, _, listener, _, _ ->
            arrayOf(MediaCodecAudioRenderer(c, MediaCodecSelector.DEFAULT, handler, listener, DefaultAudioSink.Builder(c).build()))
        }
        val containers = ExtractorsFactory(::containers)
        // NoBandwidthMeter and no diagnostics: neither is used for one audio stream at a time, and both cost binder calls
        // during the launch: the default bandwidth meter watches the network type and asks telephony for the country,
        // the diagnostics report each playback session to MediaMetrics. The constructor that takes every component
        // also keeps the default bandwidth meter out of the APK.
        exo = ExoPlayer.Builder(
            c, audio, ProgressiveMediaSource.Factory(source, containers).setLoadErrorHandlingPolicy(WaitForNetwork),
            FirstAudioTrack, AheadLoadControl(load), NoBandwidthMeter, NoAnalytics,
        )
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setUsePlatformDiagnostics(false)
            .build()
        exo.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                listener?.onEvents(
                    events.contains(Player.EVENT_TIMELINE_CHANGED), events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION),
                    events.contains(Player.EVENT_REPEAT_MODE_CHANGED), events.contains(Player.EVENT_IS_PLAYING_CHANGED),
                    events.contains(Player.EVENT_POSITION_DISCONTINUITY),
                )
            }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) { listener?.onMediaItemTransition() }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { listener?.onPlayWhenReadyChanged(playWhenReady, reason) }
            override fun onPlayerError(e: PlaybackException) {
                listener?.onPlayerError(e, (e.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode, e.errorCodeName)
            }
        })
        Startup.later { watchHeadphones() }
    }

    override var listener: AudioPlayer.Listener? = null

    override fun item(e: Entry): MediaItem {
        val s = e.song
        return MediaItem.Builder()
            .setMediaId(s.id)
            .setUri("roma://${s.id}")
            .setCustomCacheKey(s.id)
            .setTag(e)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(Store.shown(s.title))
                    .setArtist(artistLine(s))
                    .setAlbumTitle(s.album?.name?.let(Store::shown))
                    .setArtworkUri(s.thumb?.let { Uri.parse(bigThumb(it)) })
                    .build(),
            )
            .build()
    }

    override fun entryAt(index: Int) = exo.getMediaItemAt(index).localConfiguration?.tag as? Entry
    override val currentEntry get() = exo.currentMediaItem?.localConfiguration?.tag as? Entry
    override val mediaItemCount get() = exo.mediaItemCount
    override val currentMediaItemIndex get() = exo.currentMediaItemIndex
    override val nextMediaItemIndex get() = exo.nextMediaItemIndex
    override fun hasNextMediaItem() = exo.hasNextMediaItem()
    override fun hasPreviousMediaItem() = exo.hasPreviousMediaItem()
    override fun seekToNextMediaItem() = exo.seekToNextMediaItem()
    override fun setMediaItems(items: List<MediaItem>, start: Int, positionMs: Long) = exo.setMediaItems(items, start, positionMs)
    override fun addMediaItem(index: Int, item: MediaItem) = exo.addMediaItem(index, item)
    override fun addMediaItems(items: List<MediaItem>) = exo.addMediaItems(items)
    override fun removeMediaItem(index: Int) = exo.removeMediaItem(index)
    override fun replaceMediaItems(from: Int, to: Int, items: List<MediaItem>) = exo.replaceMediaItems(from, to, items)
    override fun seekTo(index: Int, positionMs: Long) = exo.seekTo(index, positionMs)
    override fun seekTo(positionMs: Long) = exo.seekTo(positionMs)
    override val isPlaying get() = exo.isPlaying
    override val playWhenReady get() = exo.playWhenReady
    override val playbackState get() = exo.playbackState
    override val playerError get() = exo.playerError
    override val currentPosition get() = exo.currentPosition
    override val duration get() = exo.duration
    override val speed get() = exo.playbackParameters.speed
    override var volume: Float
        get() = exo.volume
        set(v) { exo.volume = v }
    override var repeatMode: Int
        get() = exo.repeatMode
        set(v) { exo.repeatMode = v }
    override var pauseAtEndOfMediaItems: Boolean
        get() = exo.pauseAtEndOfMediaItems
        set(v) { exo.pauseAtEndOfMediaItems = v }
    override fun prepare() = exo.prepare()
    override fun play() = exo.play()
    override fun pause() = exo.pause()
    override fun togglePlay() { androidx.media3.common.util.Util.handlePlayPauseButtonAction(exo) }
    override val showPlayButton get() = androidx.media3.common.util.Util.shouldShowPlayButton(exo)

    // The key is the media item's cache key (the video ID): under the roma:// URI the player wouldn't find the bytes
    // and would download them again.
    override fun precache(id: String, bytes: Long) {
        val spec = DataSpec.Builder().setUri("roma://$id").setKey(id).apply { if (bytes >= 0) setPosition(0).setLength(bytes) }.build()
        CacheWriter(cached.createDataSource(), spec, null, null).cache()
    }

    override fun fullyCached(id: String): Boolean {
        val length = androidx.media3.datasource.cache.ContentMetadata.getContentLength(cache.getContentMetadata(id))
        return length > 0 && cache.isCached(id, 0, length)
    }

    override fun clearCache() = cache.keys.forEach(cache::removeResource)

    // Resumes the song when the headphones that paused it come back (Headphones).
    private val headphones = Headphones { exo.play() }

    private fun watchHeadphones() {
        exo.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) headphones.unplugged() else headphones.cleared()
            }
        })
        headphones.watch()
    }

    /**
     * Without a connection the player keeps playing what it has, then waits, retrying every 2 s for up to 10 min
     * (an attempt with no network fails at once and costs nothing), and goes on by itself once a retry succeeds. Other
     * errors keep Media3's usual 3 retries. ponytail: fixed 2 s; a nudge from the network callback would save up to 2 s.
     */
    private object WaitForNetwork : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(info: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long = when {
            noConnection(info.exception) -> if (info.errorCount <= 300) 2_000 else C.TIME_UNSET
            info.errorCount > DEFAULT_MIN_LOADABLE_RETRY_COUNT -> C.TIME_UNSET
            else -> super.getRetryDelayMsFor(info)
        }

        // The error reaches the player only once retrying gives up (above), not after a fixed count.
        override fun getMinimumLoadableRetryCount(dataType: Int) = Int.MAX_VALUE

        private fun noConnection(e: Throwable): Boolean = generateSequence(e) { it.cause }.any {
            it is java.net.UnknownHostException || it is java.net.ConnectException || it is java.net.SocketTimeoutException ||
                it is java.net.NoRouteToHostException || it is java.net.SocketException || (it as? StreamException)?.offline == true
        }
    }
}

/**
 * [load] as is, except that it stops 50 s ahead unless "Download songs ahead" is on. Kotlin's `by` doesn't forward
 * Java's default methods, so the ones the player calls are forwarded by hand.
 */
private class AheadLoadControl(val load: DefaultLoadControl) : androidx.media3.exoplayer.LoadControl {
    override fun shouldContinueLoading(p: androidx.media3.exoplayer.LoadControl.Parameters) =
        load.shouldContinueLoading(p) && (p.bufferedDurationUs < 50_000_000 || Store.prefs.getBoolean("play.ahead", false))
    override fun getAllocator(id: androidx.media3.exoplayer.analytics.PlayerId) = load.getAllocator(id)
    override fun onPrepared(id: androidx.media3.exoplayer.analytics.PlayerId) = load.onPrepared(id)
    override fun onStopped(id: androidx.media3.exoplayer.analytics.PlayerId) = load.onStopped(id)
    override fun onReleased(id: androidx.media3.exoplayer.analytics.PlayerId) = load.onReleased(id)
    override fun onTracksSelected(p: androidx.media3.exoplayer.LoadControl.Parameters, groups: androidx.media3.exoplayer.source.TrackGroupArray, selections: Array<out androidx.media3.exoplayer.trackselection.ExoTrackSelection?>) = load.onTracksSelected(p, groups, selections)
    override fun getBackBufferDurationUs(id: androidx.media3.exoplayer.analytics.PlayerId) = load.getBackBufferDurationUs(id)
    override fun retainBackBufferFromKeyframe(id: androidx.media3.exoplayer.analytics.PlayerId) = load.retainBackBufferFromKeyframe(id)
    override fun shouldStartPlayback(p: androidx.media3.exoplayer.LoadControl.Parameters) = load.shouldStartPlayback(p)
    override fun shouldContinuePreloading(id: androidx.media3.exoplayer.analytics.PlayerId, t: androidx.media3.common.Timeline, period: androidx.media3.exoplayer.source.MediaSource.MediaPeriodId, bufferedDurationUs: Long) =
        load.shouldContinuePreloading(id, t, period, bufferedDurationUs)
}

/**
 * The song's audio track for the one renderer: a stream here holds one. DefaultTrackSelector, which weighs video, text,
 * languages and adaptive qualities, would cost several times the code.
 */
private object FirstAudioTrack : androidx.media3.exoplayer.trackselection.TrackSelector() {
    override fun selectTracks(
        capabilities: Array<out androidx.media3.exoplayer.RendererCapabilities>, groups: androidx.media3.exoplayer.source.TrackGroupArray,
        period: androidx.media3.exoplayer.source.MediaSource.MediaPeriodId, timeline: androidx.media3.common.Timeline,
    ): androidx.media3.exoplayer.trackselection.TrackSelectorResult {
        val audio = (0 until groups.length).map(groups::get).firstOrNull { it.type == C.TRACK_TYPE_AUDIO }
        return androidx.media3.exoplayer.trackselection.TrackSelectorResult(
            arrayOf(if (audio != null) androidx.media3.exoplayer.RendererConfiguration.DEFAULT else null),
            arrayOf(audio?.let { androidx.media3.exoplayer.trackselection.FixedTrackSelection(it, 0) }), null,
        )
    }
    override fun onSelectionActivated(info: Any?) {}
}

/** Bitrate estimates only matter when choosing between video qualities; roma plays one audio format per song. */
private object NoBandwidthMeter : androidx.media3.exoplayer.upstream.BandwidthMeter {
    override fun getBitrateEstimate() = 1_000_000L
    override fun getTransferListener(): androidx.media3.datasource.TransferListener? = null
    override fun addEventListener(handler: Handler, listener: androidx.media3.exoplayer.upstream.BandwidthMeter.EventListener) {}
    override fun removeEventListener(listener: androidx.media3.exoplayer.upstream.BandwidthMeter.EventListener) {}
}

/** Nothing in roma listens for analytics events, and platform diagnostics are off: ExoPlayer's events go nowhere. */
private object NoAnalytics : androidx.media3.exoplayer.analytics.AnalyticsCollector {
    override fun addListener(listener: androidx.media3.exoplayer.analytics.AnalyticsListener) {}
    override fun removeListener(listener: androidx.media3.exoplayer.analytics.AnalyticsListener) {}
    override fun setPlayer(player: Player, looper: android.os.Looper) {}
    override fun release() {}
    override fun updateMediaPeriodQueueInfo(queue: List<androidx.media3.exoplayer.source.MediaSource.MediaPeriodId>, readingPeriod: androidx.media3.exoplayer.source.MediaSource.MediaPeriodId?) {}
    override fun notifySeekStarted() {}
    override fun onRendererReadyChanged(rendererIndex: Int, rendererTrackType: Int, isRendererReady: Boolean) {}
    override fun onAudioEnabled(counters: androidx.media3.exoplayer.DecoderCounters) {}
    override fun onAudioDecoderInitialized(decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {}
    override fun onAudioInputFormatChanged(format: androidx.media3.common.Format, decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {}
    override fun onAudioPositionAdvancing(playoutStartSystemTimeMs: Long) {}
    override fun onAudioUnderrun(bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {}
    override fun onAudioDecoderReleased(decoderName: String) {}
    override fun onAudioDisabled(counters: androidx.media3.exoplayer.DecoderCounters) {}
    override fun onAudioSinkError(audioSinkError: Exception) {}
    override fun onAudioCodecError(audioCodecError: Exception) {}
    override fun onAudioTrackInitialized(audioTrackConfig: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig) {}
    override fun onAudioTrackReleased(audioTrackConfig: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig) {}
    override fun onVideoEnabled(counters: androidx.media3.exoplayer.DecoderCounters) {}
    override fun onVideoDecoderInitialized(decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {}
    override fun onVideoInputFormatChanged(format: androidx.media3.common.Format, decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {}
    override fun onDroppedFrames(droppedFrames: Int, elapsedMs: Long) {}
    override fun onVideoDecoderReleased(decoderName: String) {}
    override fun onVideoDisabled(counters: androidx.media3.exoplayer.DecoderCounters) {}
    override fun onRenderedFirstFrame(output: Any, renderTimeMs: Long) {}
    override fun onVideoFrameProcessingOffset(totalProcessingOffsetUs: Long, frameCount: Int) {}
    override fun onVideoCodecError(videoCodecError: Exception) {}
    override fun onDroppedSeeksWhileScrubbing(droppedSeeks: Int) {}
    override fun onBandwidthSample(elapsedMs: Int, bytesTransferred: Long, bitrateEstimate: Long) {}
}

