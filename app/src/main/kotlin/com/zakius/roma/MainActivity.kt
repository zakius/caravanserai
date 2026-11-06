package com.zakius.roma

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import roma.core.LastFm

class MainActivity : ComponentActivity() {
    override fun dispatchTouchEvent(e: android.view.MotionEvent): Boolean {
        val action = e.actionMasked
        Touch.down = action != android.view.MotionEvent.ACTION_UP && action != android.view.MotionEvent.ACTION_CANCEL
        Touch.last = e.eventTime
        // The GPU sleeps after about 80 ms without frames, and waking it costs several milliseconds. Drawing a frame as
        // the finger goes down wakes it early, so the frame that answers the tap doesn't pay for it. Presses that draw
        // nothing of their own, such as a list row, need this most.
        if (action == android.view.MotionEvent.ACTION_DOWN) window.decorView.invalidate()
        return super.dispatchTouchEvent(e)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The splash goes the moment roma's first frame is ready, skipping Android's fade-out of about 130 ms.
        if (Build.VERSION.SDK_INT >= 31) splashScreen.setOnExitAnimationListener { splash -> splash.remove() }
        enableEdgeToEdge()
        // Back on Home leaves roma in the background instead of closing it, as Android 12 and later do by default. The
        // next start then finds the activity and its UI still there.
        if (Build.VERSION.SDK_INT < 31) onBackPressedDispatcher.addCallback(this) { moveTaskToBack(true) }
        // The media notification matters for playing, so the permission is asked for once the first start's questions
        // are answered, before the first song: asked at the first tap on a song, it covered the player as it opened.
        // It is asked only once, because asking at every start opens the system's permission activity over ours, even
        // after the user refused for good.
        val notify = android.Manifest.permission.POST_NOTIFICATIONS
        val canAskToNotify = Build.VERSION.SDK_INT >= 33 && !Store.bool("notify.asked", false)
        if (canAskToNotify && checkSelfPermission(notify) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            lifecycleScope.launch {
                // Reading Store.version makes the flow re-check the setting whenever one changes.
                snapshotFlow {
                    Store.version
                    Store.bool("setup.done", false)
                }.first { done -> done }
                Store.set("notify.asked", true)
                requestPermissions(arrayOf(notify), 0)
            }
        }
        // A re-created activity would send the already used token again.
        if (savedInstanceState == null) handleLastFm(intent)
        // Before the first frame, so Home's request and its disk copy are already on their way.
        Home.start()
        setContent { Theme { Root() } }
        // Once the first frame is drawn, the start-up work that waited for it runs.
        val decor = window.decorView
        decor.viewTreeObserver.addOnDrawListener(object : android.view.ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                // A listener can't be removed from inside onDraw, so this waits for the next message.
                decor.post {
                    decor.viewTreeObserver.removeOnDrawListener(this)
                    Startup.go()
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLastFm(intent)
    }

    // The media session runs while roma is open, so the system's controls can reach it. The service starts in a post
    // because starting it directly from onStart would create it and its media session before roma's first frame.
    override fun onStart() {
        super.onStart()
        // Back in the foreground, a Home older than 30 minutes loads again behind the one on screen.
        Home.start()
        window.decorView.post {
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) PlaybackService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        flavorResumed()
        // This may show a dialog, so it waits until after the first frame.
        window.decorView.post { Dictionary.ensure() }
    }

    // Last.fm's browser sign-in returns to roma://lastfm?token=...
    private fun handleLastFm(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != BuildConfig.SLUG || data.host != "lastfm") return
        val token = data.getQueryParameter("token") ?: return
        val fm = LastFm(Store.str("lastfm.key", ""), Store.str("lastfm.secret", ""))
        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) { runCatching { fm.session(token) } }
            result.onSuccess { session ->
                Store.set("lastfm.session", session)
                Playback.flushScrobbles()
                Toast.makeText(this@MainActivity, "Last.fm connected", Toast.LENGTH_SHORT).show()
            }
            result.onFailure { error ->
                Toast.makeText(this@MainActivity, "Last.fm: ${error.message}", Toast.LENGTH_LONG).show()
            }
            Nav.tab = TAB_SETTINGS
        }
    }
}
