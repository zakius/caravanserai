package com.zakius.roma

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.media.MediaBrowserService
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The player as the system sees it, on Android's own MediaSession: lock screen, Bluetooth and headset buttons, the
 * system's media controls and the media notification (previous, play/pause, next, Like). A MediaBrowserService only
 * for the system's "resume" after a restart: its recent root holds the song the saved queue stops at.
 */
class PlaybackService : MediaBrowserService() {
    private lateinit var session: MediaSession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var art: Pair<String, Bitmap>? = null
    private var foreground = false

    companion object {
        var instance: PlaybackService? = null
        private const val CHANNEL = "playback"
        private const val NOTIFICATION = 1
        const val LIKE = "roma.like"
        const val BUTTON = "roma.button"

        /** Runs the session while the app is open, so the system's controls and the lock screen have it. */
        fun start(c: Context) {
            if (instance == null) runCatching { c.startService(Intent(c, PlaybackService::class.java)) }
        }
    }

    override fun onCreate() {
        Startup.go() // started without a screen (a headset's Play, the system's resume): nothing waits for a frame
        super.onCreate()
        instance = this
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession(this, "roma").apply {
            setSessionActivity(launch)
            // The key goes into the intent, so before Android 14 the PendingIntent must be mutable.
            if (Build.VERSION.SDK_INT >= 34) setMediaButtonBroadcastReceiver(android.content.ComponentName(this@PlaybackService, MediaButtons::class.java))
            else @Suppress("DEPRECATION") setMediaButtonReceiver(PendingIntent.getBroadcast(this@PlaybackService, 0, Intent(Intent.ACTION_MEDIA_BUTTON).setClass(this@PlaybackService, MediaButtons::class.java), PendingIntent.FLAG_MUTABLE))
            setCallback(Callback(), Handler(Looper.getMainLooper()))
            isActive = true
        }
        sessionToken = session.sessionToken
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        Playback.playerChanged = ::update
        update()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // From a headset button while roma wasn't running: started as a foreground service, so the notification goes up
        // at once, then the key plays the restored queue.
        if (intent?.action == BUTTON) {
            goForeground()
            @Suppress("DEPRECATION") val key = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
            if (key != null) session.controller.dispatchMediaButtonEvent(key)
        }
        return START_NOT_STICKY
    }

    private inner class Callback : MediaSession.Callback() {
        // As Media3's buttons do (Util.handlePlayButtonAction, Player.seekToNext and seekToPrevious).
        override fun onPlay() = Playback.afterRestore {
            goForeground()
            val p = Playback.player
            if (p.playbackState == AudioPlayer.STATE_IDLE) p.prepare()
            else if (p.playbackState == AudioPlayer.STATE_ENDED) p.seekTo(p.currentMediaItemIndex, 0)
            p.play()
        }
        override fun onPause() = Playback.player.pause()
        override fun onStop() = Playback.player.pause()
        override fun onSkipToNext() { if (Playback.player.hasNextMediaItem()) Playback.player.seekToNextMediaItem() }
        override fun onSkipToPrevious() {
            val p = Playback.player
            val previous = Playback.neighbour(p.currentMediaItemIndex, -1)
            if (previous >= 0 && p.currentPosition <= 3_000) p.seekTo(previous, 0) else p.seekTo(0)
        }
        override fun onSeekTo(pos: Long) = Playback.player.seekTo(pos)
        override fun onCustomAction(action: String, extras: Bundle?) { if (action == LIKE) Playback.toggleLike() }

        // A headset's single button: one press plays or pauses, two quick presses skip to the next song (as Media3 did).
        private val main = Handler(Looper.getMainLooper())
        private var pressed: Runnable? = null
        override fun onMediaButtonEvent(intent: Intent): Boolean {
            @Suppress("DEPRECATION") val key = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
            if (key.keyCode != KeyEvent.KEYCODE_HEADSETHOOK && key.keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) return super.onMediaButtonEvent(intent)
            if (key.action != KeyEvent.ACTION_DOWN || key.repeatCount > 0) return true
            pressed?.let { main.removeCallbacks(it); pressed = null; onSkipToNext(); return true }
            pressed = Runnable { pressed = null; if (Playback.player.playWhenReady) onPause() else onPlay() }.also { main.postDelayed(it, 300) }
            return true
        }
    }

    fun dispatch(key: KeyEvent) = session.controller.dispatchMediaButtonEvent(key)

    /** Notification and lock screen: prev, play/pause, next from the system, plus Like. Never is left out on purpose. */
    fun updateLikeButton() = update()

    private fun update() {
        val p = Playback.player
        val song = Playback.current()?.song
        val liked = song?.id in Playback.liked
        val state = when {
            p.playbackState == AudioPlayer.STATE_BUFFERING && p.playWhenReady -> PlaybackState.STATE_BUFFERING
            p.isPlaying -> PlaybackState.STATE_PLAYING
            p.mediaItemCount == 0 -> PlaybackState.STATE_NONE
            else -> PlaybackState.STATE_PAUSED
        }
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO
        val playback = PlaybackState.Builder()
            .setState(state, p.currentPosition, if (p.isPlaying) p.speed else 0f)
            .setActions(actions)
        if (Playback.canLike(Playback.current()?.song)) playback.addCustomAction(PlaybackState.CustomAction.Builder(LIKE, if (liked) "Unlike" else "Like", if (liked) R.drawable.thumb_up else R.drawable.thumb_up_outline).build())
        session.setPlaybackState(playback.build())

        val thumb = song?.thumb?.let(::bigThumb)
        val meta = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, song?.id)
            .putText(MediaMetadata.METADATA_KEY_TITLE, song?.let { Store.shown(it.title) })
            .putText(MediaMetadata.METADATA_KEY_ARTIST, song?.let(::artistLine))
            .putText(MediaMetadata.METADATA_KEY_ALBUM, song?.album?.name?.let(Store::shown))
            .putLong(MediaMetadata.METADATA_KEY_DURATION, if (p.duration > 0) p.duration else -1)
        art?.takeIf { it.first == thumb }?.let { meta.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it.second) }
        session.setMetadata(meta.build())
        if (thumb != null && art?.first != thumb) {
            art = thumb to Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) // asked for once per song
            scope.launch {
                val b = Images.load(thumb, BIG_PX, software = true) ?: return@launch
                if (art?.first == thumb) { art = thumb to b; update() }
            }
        }

        // Shown once a song has been prepared (a restored queue alone doesn't post one, as with Media3); in the
        // foreground (not swiped away, not stopped by the system) while it plays.
        val manager = getSystemService(NotificationManager::class.java)
        when {
            p.mediaItemCount == 0 || p.playbackState == AudioPlayer.STATE_IDLE && !p.playWhenReady -> {
                if (foreground) stopForeground(STOP_FOREGROUND_REMOVE) else manager.cancel(NOTIFICATION)
                foreground = false
            }
            p.playWhenReady -> goForeground()
            foreground -> { stopForeground(STOP_FOREGROUND_DETACH); foreground = false; manager.notify(NOTIFICATION, notification()) }
            else -> manager.notify(NOTIFICATION, notification())
        }
    }

    private fun goForeground() {
        val n = notification()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIFICATION, n)
        // Started, not only bound (the system's resume binds it): it stays when its client goes. Allowed now, in the foreground.
        if (!foreground) runCatching { startService(Intent(this, PlaybackService::class.java)) }
        foreground = true
    }

    private fun notification(): Notification {
        val p = Playback.player
        val song = Playback.current()?.song
        val liked = song?.id in Playback.liked
        fun action(icon: Int, title: String, key: Int) = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, icon), title,
            PendingIntent.getBroadcast(this, key, Intent(Intent.ACTION_MEDIA_BUTTON).setClass(this, MediaButtons::class.java)
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, key)), PendingIntent.FLAG_IMMUTABLE),
        ).build()
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(song?.let { Store.shown(it.title) })
            .setContentText(song?.let(::artistLine))
            .setContentIntent(session.controller.sessionActivity)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(action(R.drawable.previous, "Previous", KeyEvent.KEYCODE_MEDIA_PREVIOUS))
            .addAction(if (p.playWhenReady) action(R.drawable.pause, "Pause", KeyEvent.KEYCODE_MEDIA_PAUSE) else action(R.drawable.play, "Play", KeyEvent.KEYCODE_MEDIA_PLAY))
            .addAction(action(R.drawable.next, "Next", KeyEvent.KEYCODE_MEDIA_NEXT))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
        if (Playback.canLike(Playback.current()?.song)) b.addAction(
            Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, if (liked) R.drawable.thumb_up else R.drawable.thumb_up_outline), if (liked) "Unlike" else "Like",
                PendingIntent.getBroadcast(this, 1, Intent(LIKE).setClass(this, MediaButtons::class.java), PendingIntent.FLAG_IMMUTABLE),
            ).build(),
        )
        art?.second?.takeIf { it.width > 1 }?.let(b::setLargeIcon)
        return b.build()
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? =
        if (rootHints?.getBoolean(BrowserRoot.EXTRA_RECENT) == true) BrowserRoot("recent", Bundle().apply { putBoolean(BrowserRoot.EXTRA_RECENT, true) }) else null

    // The system's resume after a restart asks for the last song; Play then resumes the saved queue (Callback.onPlay).
    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowser.MediaItem>>) {
        result.detach()
        Playback.afterRestore {
            val s = Playback.current()?.song
            result.sendResult(
                if (s == null) mutableListOf()
                else mutableListOf(MediaBrowser.MediaItem(MediaDescription.Builder().setMediaId(s.id).setTitle(Store.shown(s.title)).setSubtitle(artistLine(s)).build(), MediaBrowser.MediaItem.FLAG_PLAYABLE)),
            )
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!Playback.player.isPlaying) stopSelf()
    }

    override fun onDestroy() {
        instance = null
        Playback.playerChanged = null
        scope.cancel()
        session.release()
        super.onDestroy()
    }
}

/** Media buttons and the notification's buttons, also when roma isn't running: the service starts and takes the key. */
class MediaButtons : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val running = PlaybackService.instance
        if (intent.action == PlaybackService.LIKE) { if (running != null) Playback.toggleLike(); return }
        @Suppress("DEPRECATION") val key = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        if (running != null) {
            running.dispatch(key)
            return
        }
        val start = Intent(c, PlaybackService::class.java).setAction(PlaybackService.BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, key)
        runCatching { c.startForegroundService(start) }
    }
}

/** The artist line of the notification and the system's controls: the original title too, when it isn't shown above. */
fun artistLine(s: roma.core.Song) = if (s.title.isLatin || Store.reads(s.title.original)) s.artistLatin else "${s.artistLatin} · ${s.title.original}"
