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
        Touch.down = e.actionMasked != android.view.MotionEvent.ACTION_UP && e.actionMasked != android.view.MotionEvent.ACTION_CANCEL
        Touch.last = e.eventTime
        // A frame while the finger is down wakes the GPU, which sleeps after about 80 ms without frames: waking it took
        // 7 to 12 ms of the frame that answered the tap (Snapdragon 8 Gen 2, 2026-10-27). A press that draws nothing
        // of its own (a list row) then left all of that to the tap's frame.
        if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) window.decorView.invalidate()
        return super.dispatchTouchEvent(e)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The splash goes the moment roma's first frame is ready, instead of after Android's fade-out (about 130 ms).
        if (Build.VERSION.SDK_INT >= 31) splashScreen.setOnExitAnimationListener { it.remove() }
        enableEdgeToEdge()
        // Back on Home leaves roma in the background instead of closing it, as Android 12+ does: the next start then
        // finds the activity and its UI still there.
        if (Build.VERSION.SDK_INT < 31) onBackPressedDispatcher.addCallback(this) { moveTaskToBack(true) }
        // The media notification matters once something plays, so that's when it's asked for, and only once: asking at
        // every start opened the system's permission activity over ours, even after the user refused for good.
        val notify = android.Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= 33 && !Store.bool("notify.asked", false) && checkSelfPermission(notify) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            lifecycleScope.launch {
                snapshotFlow { Playback.tick; Playback.player.isPlaying }.first { it }
                Store.set("notify.asked", true)
                requestPermissions(arrayOf(notify), 0)
            }
        }
        if (savedInstanceState == null) handleLastFm(intent) // a re-created activity would send the used token again
        Home.start() // before the first frame, so Home's request and its disk copy are on their way
        setContent { Theme { Root() } }
        // The first frame is drawn: the start-up work that waited for it runs.
        val decor = window.decorView
        decor.viewTreeObserver.addOnDrawListener(object : android.view.ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                decor.post { // not from inside onDraw
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

    // The media session runs while roma is open, so the system's controls have it. After the first frame: started from
    // onStart, the service and its media session were created before roma's first frame.
    override fun onStart() {
        super.onStart()
        Home.start() // back in the foreground: a Home older than 30 minutes loads again behind the shown one
        window.decorView.post {
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) PlaybackService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        Dictionary.resumed(this)
        window.decorView.post { Dictionary.ensure(this) } // package manager calls, and maybe a dialog: after the first frame
    }


    // Last.fm's browser auth returns to roma://lastfm?token=...
    private fun handleLastFm(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != BuildConfig.SLUG || data.host != "lastfm") return
        val token = data.getQueryParameter("token") ?: return
        val fm = LastFm(Store.str("lastfm.key", ""), Store.str("lastfm.secret", ""))
        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) { runCatching { fm.session(token) } }
            result.onSuccess {
                Store.set("lastfm.session", it)
                Playback.flushScrobbles()
                Toast.makeText(this@MainActivity, "Last.fm connected", Toast.LENGTH_SHORT).show()
            }.onFailure { Toast.makeText(this@MainActivity, "Last.fm: ${it.message}", Toast.LENGTH_LONG).show() }
            Nav.tab = TAB_SETTINGS
        }
    }
}
