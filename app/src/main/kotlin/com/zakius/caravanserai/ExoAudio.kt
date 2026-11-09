package com.zakius.caravanserai

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

/** Playback's player on Android: Media3 ExoPlayer, audio only, over a 1 GB disk cache. */
class ExoAudio(c: Context) : AudioPlayer {
    val exo: ExoPlayer
    private val cache: SimpleCache
    private val cached: CacheDataSource.Factory

    init {
        val cacheBytes = 1024L * 1024 * 1024
        cache = SimpleCache(File(c.cacheDir, "audio"), LeastRecentlyUsedCacheEvictor(cacheBytes), StandaloneDatabaseProvider(c))
        val http = DefaultHttpDataSource.Factory()
        // Media items carry roma://<videoId>. The stream URL is looked up (Playback.streamUrl) only when bytes are
        // needed and not cached.
        val resolving = ResolvingDataSource.Factory(http) { spec ->
            if (spec.uri.scheme != "roma") {
                spec
            } else {
                val streamUrl = Playback.streamUrl(spec.uri.host!!)
                spec.withUri(Uri.parse(streamUrl))
            }
        }
        val source = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(resolving)
        cached = source
        // Playback starts after 0.5 s of audio instead of ExoPlayer's default 2.5 s, because audio downloads far faster
        // than it plays. AheadLoadControl stops at 50 s ahead, unless "Download songs ahead" lets it fetch the whole
        // song, up to the 10 minute maximum.
        val minBufferMs = 15_000
        val maxBufferMs = 10 * 60 * 1000
        val startAfterMs = 500
        val restartAfterStallMs = 2_000
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBufferMs, maxBufferMs, startAfterMs, restartAfterStallMs)
            .build()
        // Audio only, in the containers the flavor's source serves (containers()). The default factories bring video,
        // text and other containers that R8 can't remove.
        val audio = RenderersFactory { handler, _, listener, _, _ ->
            val sink = DefaultAudioSink.Builder(c).build()
            arrayOf(MediaCodecAudioRenderer(c, MediaCodecSelector.DEFAULT, handler, listener, sink))
        }
        val containers = ExtractorsFactory(::containers)
        val mediaSource = ProgressiveMediaSource.Factory(source, containers).setLoadErrorHandlingPolicy(WaitForNetwork)
        // There is no bandwidth meter and no platform diagnostics. Neither is useful for one audio stream at a time, and
        // both cost binder calls during launch: the default bandwidth meter watches the network type and asks telephony
        // for the country, and the diagnostics report each playback session to MediaMetrics. The constructor that takes
        // every component also keeps the default bandwidth meter out of the APK.
        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        exo = ExoPlayer.Builder(c, audio, mediaSource, FirstAudioTrack, AheadLoadControl(load), NoBandwidthMeter, NoAnalytics)
            .setAudioAttributes(attributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setUsePlatformDiagnostics(false)
            .build()
        exo.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                listener?.onEvents(
                    events.contains(Player.EVENT_TIMELINE_CHANGED),
                    events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION),
                    events.contains(Player.EVENT_REPEAT_MODE_CHANGED),
                    events.contains(Player.EVENT_IS_PLAYING_CHANGED),
                    events.contains(Player.EVENT_POSITION_DISCONTINUITY),
                )
            }

            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                listener?.onMediaItemTransition()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                listener?.onPlayWhenReadyChanged(playWhenReady, reason)
            }

            override fun onPlayerError(e: PlaybackException) {
                val httpCode = (e.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                listener?.onPlayerError(e, httpCode, e.errorCodeName)
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

    // The key is the media item's cache key (the video ID). Cached under the roma:// URI instead, the bytes would not be
    // found by the player, which would download them again.
    override fun precache(id: String, bytes: Long) {
        val builder = DataSpec.Builder().setUri("roma://$id").setKey(id)
        if (bytes >= 0) builder.setPosition(0).setLength(bytes)
        val spec = builder.build()
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
                val unplugged = !playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY
                if (unplugged) headphones.unplugged() else headphones.cleared()
            }
        })
        headphones.watch()
    }

    /**
     * Without a connection the player keeps playing what it has buffered, then waits, retrying every 2 s for up to
     * 10 minutes, and carries on by itself once a retry succeeds. An attempt with no network fails at once, so the
     * retries cost nothing. Other errors keep Media3's usual 3 retries.
     * ponytail: the delay is a fixed 2 s; a nudge from the network callback would save up to 2 s.
     */
    private object WaitForNetwork : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
        private const val OFFLINE_RETRY_MS = 2_000L
        private const val OFFLINE_RETRIES = 300 // 10 minutes of 2 s retries

        override fun getRetryDelayMsFor(info: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long = when {
            noConnection(info.exception) -> if (info.errorCount <= OFFLINE_RETRIES) OFFLINE_RETRY_MS else C.TIME_UNSET
            info.errorCount > DEFAULT_MIN_LOADABLE_RETRY_COUNT -> C.TIME_UNSET
            else -> super.getRetryDelayMsFor(info)
        }

        // The error reaches the player only when getRetryDelayMsFor gives up, not after a fixed count.
        override fun getMinimumLoadableRetryCount(dataType: Int) = Int.MAX_VALUE

        private fun noConnection(e: Throwable): Boolean {
            val causes = generateSequence(e) { error -> error.cause }
            return causes.any { cause ->
                cause is java.net.UnknownHostException ||
                    cause is java.net.ConnectException ||
                    cause is java.net.SocketTimeoutException ||
                    cause is java.net.NoRouteToHostException ||
                    cause is java.net.SocketException ||
                    (cause as? StreamException)?.offline == true
            }
        }
    }
}

/**
 * Behaves like [load], except that it stops loading 50 s ahead unless "Download songs ahead" is on. Kotlin's `by`
 * doesn't forward Java's default methods, so the ones the player calls are forwarded by hand.
 */
private class AheadLoadControl(val load: DefaultLoadControl) : androidx.media3.exoplayer.LoadControl {
    private val aheadUs = 50 * 1_000_000L

    override fun shouldContinueLoading(p: androidx.media3.exoplayer.LoadControl.Parameters) =
        load.shouldContinueLoading(p) && (p.bufferedDurationUs < aheadUs || Store.prefs.getBoolean("play.ahead", false))
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
 * Picks the song's audio track for the one renderer, since each stream here holds exactly one. DefaultTrackSelector,
 * which weighs video, text, languages and adaptive qualities, would cost several times the code.
 */
private object FirstAudioTrack : androidx.media3.exoplayer.trackselection.TrackSelector() {
    override fun selectTracks(
        capabilities: Array<out androidx.media3.exoplayer.RendererCapabilities>, groups: androidx.media3.exoplayer.source.TrackGroupArray,
        period: androidx.media3.exoplayer.source.MediaSource.MediaPeriodId, timeline: androidx.media3.common.Timeline,
    ): androidx.media3.exoplayer.trackselection.TrackSelectorResult {
        val allGroups = (0 until groups.length).map(groups::get)
        val audio = allGroups.firstOrNull { group -> group.type == C.TRACK_TYPE_AUDIO }
        val configuration = if (audio != null) androidx.media3.exoplayer.RendererConfiguration.DEFAULT else null
        val selection = if (audio != null) androidx.media3.exoplayer.trackselection.FixedTrackSelection(audio, 0) else null
        return androidx.media3.exoplayer.trackselection.TrackSelectorResult(arrayOf(configuration), arrayOf(selection), null)
    }
    override fun onSelectionActivated(info: Any?) {}
}

/** Bitrate estimates only matter when choosing between video qualities; The app plays one audio format per song. */
private object NoBandwidthMeter : androidx.media3.exoplayer.upstream.BandwidthMeter {
    override fun getBitrateEstimate() = 1_000_000L
    override fun getTransferListener(): androidx.media3.datasource.TransferListener? = null
    override fun addEventListener(handler: Handler, listener: androidx.media3.exoplayer.upstream.BandwidthMeter.EventListener) {}
    override fun removeEventListener(listener: androidx.media3.exoplayer.upstream.BandwidthMeter.EventListener) {}
}

/** Nothing in the app listens for analytics events and platform diagnostics are off, so ExoPlayer's events go nowhere. */
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

