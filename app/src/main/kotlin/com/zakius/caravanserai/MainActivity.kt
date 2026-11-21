package com.zakius.caravanserai

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver

/**
 * Minimal android.app.Activity with zero AndroidX/Compose/Coroutines imports on its cold-start path.
 * Frame 1 paints ColdStartHomeView directly into @android:id/content; MainComposeBridge attaches
 * Compose and Lifecycle owners immediately after Frame 1 draws.
 */
class MainActivity : Activity() {
    private var bridge: MainComposeBridge? = null
    private var savedBundle: Bundle? = null
    private var started = false
    private var resumed = false
    private var pendingResultCallback: ((Uri?) -> Unit)? = null
    private var nextReqCode = 100

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        val action = e.actionMasked
        Touch.down = action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL
        Touch.last = e.eventTime
        // The GPU sleeps after about 80 ms without frames, and waking it costs several milliseconds. Drawing a frame as
        // the finger goes down wakes it early, so the frame that answers the tap doesn't pay for it.
        if (action == MotionEvent.ACTION_DOWN) window.decorView.invalidate()
        return super.dispatchTouchEvent(e)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedBundle = savedInstanceState
        if (Build.VERSION.SDK_INT >= 31) splashScreen.setOnExitAnimationListener { splash -> splash.remove() }
        // Android 15+ enforces edge-to-edge by default for targetSdk >= 35; only API < 35 need runtime setup.
        if (Build.VERSION.SDK_INT < 35) {
            @Suppress("DEPRECATION")
            run {
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
                if (Build.VERSION.SDK_INT >= 30) {
                    window.setDecorFitsSystemWindows(false)
                    window.attributes.layoutInDisplayCutoutMode =
                        android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    window.decorView.systemUiVisibility =
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                }
                if (Build.VERSION.SDK_INT >= 29) {
                    window.isNavigationBarContrastEnforced = false
                }
            }
        }

        val prefs = App.appPrefs
        val useColdShell = !Startup.started &&
            savedInstanceState == null &&
            intent?.data == null &&
            prefs.getBoolean("setup.done", false) &&
            prefs.getInt("ui.tab", TAB_HOME) == TAB_HOME

        val decor = window.decorView
        if (useColdShell) {
            val isYouTube = BuildConfig.SLUG == "dragoman"
            val signedIn = isYouTube && prefs.getString("cookie", null)?.contains("SAPISID") == true
            val hasQueue = ColdStartHomeView.hasSavedQueue(filesDir)
            val coldView = ColdStartHomeView(this, prefs, isYouTube, signedIn, hasQueue)
            setContentView(coldView)
            decor.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
                override fun onDraw() {
                    decor.post {
                        decor.viewTreeObserver.removeOnDrawListener(this)
                        if (isDestroyed) return@post
                        App.ensureInitialized(applicationContext)
                        Startup.go()
                        val b = ensureBridge()
                        b.checkNotifyPermission()
                        Home.start()
                        b.mountUnder(coldView)
                    }
                }
            })
        } else {
            App.ensureInitialized(applicationContext)
            if (savedInstanceState == null && intent?.data != null) {
                MainComposeBridge.handleLastFm(this, intent)
            }
            val b = ensureBridge()
            Startup.later {
                b.checkNotifyPermission()
                Home.start()
            }
            b.mountDirectly()
            decor.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
                override fun onDraw() {
                    decor.post {
                        decor.viewTreeObserver.removeOnDrawListener(this)
                        Startup.go()
                    }
                }
            })
        }
    }

    private fun ensureBridge(): MainComposeBridge {
        bridge?.let { return it }
        val b = MainComposeBridge(this, savedBundle)
        savedBundle = null
        b.syncState(started, resumed)
        bridge = b
        return b
    }

    override fun onStart() {
        super.onStart()
        started = true
        bridge?.onStart()
        // Back in the foreground, a Home older than 30 minutes loads again behind the one on screen.
        if (Startup.started) Home.start()
        Startup.later {
            if (started) PlaybackService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        bridge?.onResume()
        Startup.later { flavorResumed() }
        // This may show a dialog, so it waits until after the first frame.
        window.decorView.post { Startup.later { Dictionary.ensure() } }
    }

    override fun onPause() {
        resumed = false
        bridge?.onPause()
        super.onPause()
    }

    override fun onStop() {
        started = false
        bridge?.onStop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        bridge?.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        bridge?.destroy()
        bridge = null
        super.onDestroy()
    }

    @Deprecated("Handled for API < 33; API 33+ uses OnBackInvokedDispatcher")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (bridge?.onBackPressed() == true) return
        if (Build.VERSION.SDK_INT < 31) {
            moveTaskToBack(true)
        } else {
            super.onBackPressed()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        App.ensureInitialized(applicationContext)
        MainComposeBridge.handleLastFm(this, intent)
    }

    /** Opens a system document picker (export/import JSON) without ComponentActivity's ActivityResultRegistry. */
    @Suppress("DEPRECATION")
    fun launchForResult(intent: Intent, onResult: (Uri?) -> Unit) {
        val code = nextReqCode++
        pendingResultCallback = onResult
        startActivityForResult(intent, code)
    }

    @Deprecated("Used for document picker without ActivityResultRegistry")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val cb = pendingResultCallback ?: return
        pendingResultCallback = null
        cb(if (resultCode == RESULT_OK) data?.data else null)
    }
}
