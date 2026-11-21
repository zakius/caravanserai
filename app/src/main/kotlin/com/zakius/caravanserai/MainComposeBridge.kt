package com.zakius.caravanserai

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import roma.core.LastFm

/**
 * Holds all Compose / Lifecycle / SavedState / BackDispatcher / Coroutines / LastFm glue for MainActivity
 * in a separate class so ART never loads or verifies any AndroidX or Compose classes while MainActivity
 * renders Frame 1 through ColdStartHomeView.
 */
internal class MainComposeBridge(
    private val activity: MainActivity,
    savedState: Bundle?,
) : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner, OnBackPressedDispatcherOwner {

    private val registry = LifecycleRegistry(this)
    val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    private var scope: CoroutineScope? = null

    override val onBackPressedDispatcher = OnBackPressedDispatcher {
        if (Build.VERSION.SDK_INT < 31) activity.moveTaskToBack(true) else activity.finish()
    }

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    init {
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(savedState)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        val decor = activity.window.decorView
        decor.setViewTreeLifecycleOwner(this)
        decor.setViewTreeSavedStateRegistryOwner(this)
        decor.setViewTreeViewModelStoreOwner(this)
        decor.setViewTreeOnBackPressedDispatcherOwner(this)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackPressedDispatcher.setOnBackInvokedDispatcher(activity.onBackInvokedDispatcher)
        } else {
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    activity.moveTaskToBack(true)
                }
            })
        }
    }

    fun syncState(started: Boolean, resumed: Boolean) {
        if (started) registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        if (resumed) registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun onStart() = registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    fun onResume() = registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    fun onPause() = registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    fun onStop() = registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    fun onSaveInstanceState(outState: Bundle) = savedStateRegistryController.performSave(outState)

    fun onBackPressed(): Boolean {
        if (onBackPressedDispatcher.hasEnabledCallbacks()) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return false
    }

    fun mountUnder(coldView: ColdStartHomeView) {
        coldView.pendingTab?.let { Nav.select(it) }
        if (coldView.pendingSignIn) Nav.open(Page("source", "login"))
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val composeView = ComposeView(activity).apply {
            setContent { Theme { Root() } }
        }
        content.addView(
            composeView,
            0,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        composeView.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                composeView.post {
                    composeView.viewTreeObserver.removeOnDrawListener(this)
                    content.removeView(coldView)
                }
            }
        })
    }

    fun mountDirectly() {
        val composeView = ComposeView(activity).apply {
            setContent { Theme { Root() } }
        }
        activity.setContentView(composeView)
    }

    fun checkNotifyPermission() {
        val notify = android.Manifest.permission.POST_NOTIFICATIONS
        val canAskToNotify = Build.VERSION.SDK_INT >= 33 && !Store.bool("notify.asked", false)
        if (canAskToNotify && activity.checkSelfPermission(notify) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            val s = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main).also { scope = it }
            s.launch {
                snapshotFlow {
                    Store.version
                    Store.bool("setup.done", false)
                }.first { done -> done }
                Store.set("notify.asked", true)
                activity.requestPermissions(arrayOf(notify), 0)
            }
        }
    }

    fun destroy() {
        scope?.cancel()
        scope = null
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }

    companion object {
        // Last.fm's browser sign-in returns to roma://lastfm?token=...
        fun handleLastFm(activity: Activity, intent: Intent?) {
            val data = intent?.data ?: return
            if (data.scheme != BuildConfig.SLUG || data.host != "lastfm") return
            val token = data.getQueryParameter("token") ?: return
            val fm = LastFm(Store.str("lastfm.key", ""), Store.str("lastfm.secret", ""))
            CoroutineScope(Dispatchers.Main).launch {
                val result = withContext(Dispatchers.IO) { runCatching { fm.session(token) } }
                result.onSuccess { session ->
                    Store.set("lastfm.session", session)
                    Playback.flushScrobbles()
                    Toast.makeText(activity, "Last.fm connected", Toast.LENGTH_SHORT).show()
                }
                result.onFailure { error ->
                    Toast.makeText(activity, "Last.fm: ${error.message}", Toast.LENGTH_LONG).show()
                }
                Nav.tab = TAB_SETTINGS
            }
        }
    }
}
