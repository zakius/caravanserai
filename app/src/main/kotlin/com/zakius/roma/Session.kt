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
 * The player as the system sees it, through Android's own MediaSession. That covers the lock screen, Bluetooth and
 * headset buttons, the system's media controls and the media notification (previous, play/pause, next, Like). It is a
 * MediaBrowserService only for the system's "resume" after a restart: its recent root holds the song where the saved
 * queue stopped.
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
        // Two headset presses within this time skip to the next song instead of playing or pausing.
        private const val DOUBLE_PRESS_MS = 300L
        // Previous restarts the song once it has played longer than this.
        private const val RESTART_AFTER_MS = 3_000L
        const val LIKE = "roma.like"
        const val BUTTON = "roma.button"

        /** Runs the session while the app is open, so the system's controls and the lock screen can reach it. */
        fun start(c: Context) {
            if (instance == null) runCatching { c.startService(Intent(c, PlaybackService::class.java)) }
        }
    }

    override fun onCreate() {
        // The service can start with no screen at all (a headset's Play, the system's resume), and then no first frame
        // would ever release the start-up work.
        Startup.go()
        super.onCreate()
        instance = this
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val service = this
        session = MediaSession(this, "roma").apply {
            setSessionActivity(launch)
            if (Build.VERSION.SDK_INT >= 34) {
                setMediaButtonBroadcastReceiver(android.content.ComponentName(service, MediaButtons::class.java))
            } else {
                // The system puts the key into the intent, so before Android 14 the PendingIntent must be mutable.
                val buttonIntent = Intent(Intent.ACTION_MEDIA_BUTTON).setClass(service, MediaButtons::class.java)
                val buttons = PendingIntent.getBroadcast(service, 0, buttonIntent, PendingIntent.FLAG_MUTABLE)
                @Suppress("DEPRECATION") setMediaButtonReceiver(buttons)
            }
            setCallback(Callback(), Handler(Looper.getMainLooper()))
            isActive = true
        }
        sessionToken = session.sessionToken
        val channel = NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        Playback.playerChanged = ::update
        update()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A headset button started the service while roma wasn't running. It was started as a foreground service, so the
        // notification must go up at once. The key then plays the restored queue.
        if (intent?.action == BUTTON) {
            goForeground()
            @Suppress("DEPRECATION") val key = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
            if (key != null) session.controller.dispatchMediaButtonEvent(key)
        }
        return START_NOT_STICKY
    }

    private inner class Callback : MediaSession.Callback() {
        // These match Media3's button handling (Util.handlePlayButtonAction, Player.seekToNext and seekToPrevious).
        override fun onPlay() = Playback.afterRestore {
            goForeground()
            val p = Playback.player
            if (p.playbackState == AudioPlayer.STATE_IDLE) {
                p.prepare()
            } else if (p.playbackState == AudioPlayer.STATE_ENDED) {
                p.seekTo(p.currentMediaItemIndex, 0)
            }
            p.play()
        }

        override fun onPause() = Playback.player.pause()

        override fun onStop() = Playback.player.pause()

        override fun onSkipToNext() {
            if (Playback.player.hasNextMediaItem()) Playback.player.seekToNextMediaItem()
        }

        override fun onSkipToPrevious() {
            val p = Playback.player
            val previous = Playback.neighbour(p.currentMediaItemIndex, -1)
            if (previous >= 0 && p.currentPosition <= RESTART_AFTER_MS) {
                p.seekTo(previous, 0)
            } else {
                p.seekTo(0)
            }
        }

        override fun onSeekTo(pos: Long) = Playback.player.seekTo(pos)

        override fun onCustomAction(action: String, extras: Bundle?) {
            if (action == LIKE) Playback.toggleLike()
        }

        // A headset with a single button: one press plays or pauses, and two quick presses skip to the next song, like
        // Media3's handling.
        private val main = Handler(Looper.getMainLooper())
        private var pressed: Runnable? = null
        override fun onMediaButtonEvent(intent: Intent): Boolean {
            @Suppress("DEPRECATION") val key = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
            val singleButton = key.keyCode == KeyEvent.KEYCODE_HEADSETHOOK || key.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            if (!singleButton) return super.onMediaButtonEvent(intent)
            if (key.action != KeyEvent.ACTION_DOWN || key.repeatCount > 0) return true
            val firstPress = pressed
            if (firstPress != null) {
                main.removeCallbacks(firstPress)
                pressed = null
                onSkipToNext()
                return true
            }
            val press = Runnable {
                pressed = null
                if (Playback.player.playWhenReady) onPause() else onPlay()
            }
            main.postDelayed(press, DOUBLE_PRESS_MS)
            pressed = press
            return true
        }
    }

    fun dispatch(key: KeyEvent) = session.controller.dispatchMediaButtonEvent(key)

    /** Redraws the notification and lock screen controls, so the Like button shows the current state. */
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
        val actions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_SEEK_TO
        val playback = PlaybackState.Builder()
            .setState(state, p.currentPosition, if (p.isPlaying) p.speed else 0f)
            .setActions(actions)
        if (Playback.canLike(Playback.current()?.song)) {
            val likeLabel = likeAction(liked)
            val likeIcon = likeDrawable(liked)
            playback.addCustomAction(PlaybackState.CustomAction.Builder(LIKE, likeLabel, likeIcon).build())
        }
        session.setPlaybackState(playback.build())

        val thumb = song?.thumb?.let(::bigThumb)
        val meta = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, song?.id)
            .putText(MediaMetadata.METADATA_KEY_TITLE, song?.let { Store.shown(it.title) })
            .putText(MediaMetadata.METADATA_KEY_ARTIST, song?.let(::artistLine))
            .putText(MediaMetadata.METADATA_KEY_ALBUM, song?.album?.name?.let(Store::shown))
            .putLong(MediaMetadata.METADATA_KEY_DURATION, if (p.duration > 0) p.duration else -1)
        val shownArt = art
        if (shownArt != null && shownArt.first == thumb) meta.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, shownArt.second)
        session.setMetadata(meta.build())
        if (thumb != null && art?.first != thumb) {
            // A 1x1 placeholder until the picture loads, so each song's picture is requested only once.
            art = thumb to Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            scope.launch {
                val b = Images.load(thumb, BIG_PX, software = true) ?: return@launch
                if (art?.first == thumb) {
                    art = thumb to b
                    update()
                }
            }
        }

        // The notification shows once a song has been prepared; a restored queue alone doesn't post one, as with
        // Media3. While the song plays, the service is in the foreground, so the notification can't be swiped away
        // and the system doesn't stop the service.
        val manager = getSystemService(NotificationManager::class.java)
        when {
            p.mediaItemCount == 0 || p.playbackState == AudioPlayer.STATE_IDLE && !p.playWhenReady -> {
                if (foreground) stopForeground(STOP_FOREGROUND_REMOVE) else manager.cancel(NOTIFICATION)
                foreground = false
            }
            p.playWhenReady -> goForeground()
            foreground -> {
                stopForeground(STOP_FOREGROUND_DETACH)
                foreground = false
                manager.notify(NOTIFICATION, notification())
            }
            else -> manager.notify(NOTIFICATION, notification())
        }
    }

    private fun goForeground() {
        val n = notification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION, n)
        }
        // The system's resume only binds the service. Starting it as well keeps it alive when that client unbinds, and
        // Android allows the start here because the service is now in the foreground.
        if (!foreground) runCatching { startService(Intent(this, PlaybackService::class.java)) }
        foreground = true
    }

    private fun notification(): Notification {
        val p = Playback.player
        val song = Playback.current()?.song
        val liked = song?.id in Playback.liked
        fun action(icon: Int, title: String, key: Int): Notification.Action {
            val press = Intent(Intent.ACTION_MEDIA_BUTTON)
                .setClass(this, MediaButtons::class.java)
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, key))
            val onTap = PendingIntent.getBroadcast(this, key, press, PendingIntent.FLAG_IMMUTABLE)
            val iconImage = android.graphics.drawable.Icon.createWithResource(this, icon)
            return Notification.Action.Builder(iconImage, title, onTap).build()
        }
        val playOrPause = if (p.playWhenReady) {
            action(R.drawable.pause, "Pause", KeyEvent.KEYCODE_MEDIA_PAUSE)
        } else {
            action(R.drawable.play, "Play", KeyEvent.KEYCODE_MEDIA_PLAY)
        }
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(song?.let { Store.shown(it.title) })
            .setContentText(song?.let(::artistLine))
            .setContentIntent(session.controller.sessionActivity)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(action(R.drawable.previous, "Previous", KeyEvent.KEYCODE_MEDIA_PREVIOUS))
            .addAction(playOrPause)
            .addAction(action(R.drawable.next, "Next", KeyEvent.KEYCODE_MEDIA_NEXT))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
        if (Playback.canLike(Playback.current()?.song)) {
            val likeIcon = likeDrawable(liked)
            val likeLabel = likeAction(liked)
            val onTap = PendingIntent.getBroadcast(this, 1, Intent(LIKE).setClass(this, MediaButtons::class.java), PendingIntent.FLAG_IMMUTABLE)
            val iconImage = android.graphics.drawable.Icon.createWithResource(this, likeIcon)
            b.addAction(Notification.Action.Builder(iconImage, likeLabel, onTap).build())
        }
        // The 1x1 placeholder means the picture is still loading.
        val picture = art?.second
        if (picture != null && picture.width > 1) b.setLargeIcon(picture)
        return b.build()
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        if (rootHints?.getBoolean(BrowserRoot.EXTRA_RECENT) != true) return null
        val extras = Bundle()
        extras.putBoolean(BrowserRoot.EXTRA_RECENT, true)
        return BrowserRoot("recent", extras)
    }

    // After a restart, the system's resume asks for the last song. Play then resumes the saved queue (Callback.onPlay).
    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowser.MediaItem>>) {
        result.detach()
        Playback.afterRestore {
            val s = Playback.current()?.song
            if (s == null) {
                result.sendResult(mutableListOf())
            } else {
                val description = MediaDescription.Builder()
                    .setMediaId(s.id)
                    .setTitle(Store.shown(s.title))
                    .setSubtitle(artistLine(s))
                    .build()
                result.sendResult(mutableListOf(MediaBrowser.MediaItem(description, MediaBrowser.MediaItem.FLAG_PLAYABLE)))
            }
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

/**
 * Receives media buttons and the notification's buttons. When roma isn't running, it starts the service and hands it the
 * key.
 */
class MediaButtons : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val running = PlaybackService.instance
        if (intent.action == PlaybackService.LIKE) {
            if (running != null) Playback.toggleLike()
            return
        }
        @Suppress("DEPRECATION") val key = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        if (running != null) {
            running.dispatch(key)
            return
        }
        val start = Intent(c, PlaybackService::class.java).setAction(PlaybackService.BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, key)
        runCatching { c.startForegroundService(start) }
    }
}

/**
 * The artist line of the notification and the system's controls. It adds the original title when the title line shows
 * only its romanized form.
 */
fun artistLine(s: roma.core.Song) = if (s.title.isLatin || Store.reads(s.title.original)) s.artistLatin else "${s.artistLatin} · ${s.title.original}"

/** The notification's like button: a thumb, or a star on a source that stars. */
private fun likeDrawable(liked: Boolean) = when {
    backend.stars -> if (liked) R.drawable.star else R.drawable.star_outline
    liked -> R.drawable.thumb_up
    else -> R.drawable.thumb_up_outline
}
