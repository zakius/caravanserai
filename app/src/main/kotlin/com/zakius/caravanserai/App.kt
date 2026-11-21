package com.zakius.caravanserai

import android.app.Application
import android.content.Context
import java.io.File
import kotlin.concurrent.thread

class App : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Kick off SharedPreferences XML load on its background thread immediately so it finishes in parallel with
        // makeApplication / activityStart before MainActivity reads setup.done and ui.tab.
        appPrefs = getSharedPreferences("roma", MODE_PRIVATE)
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            StartupFactory.onAttachBaseContext(base)
        }
    }

    @android.annotation.SuppressLint("MissingSuperCall") // On ASUS, custom FlipFont runs super.onCreate on startup-bg
    override fun onCreate() {
        val isAsus = android.os.Build.MANUFACTURER.equals("asus", ignoreCase = true)
        val skippedFlipFont = if (isAsus) skipFlipFont() else true
        if (!isAsus) {
            super.onCreate()
        }
        appContext = this
        // Pre-render the combined ColdStartHomeView hardware atlas immediately (finishes before activityResume
        // because StartupFactory already kicked off RenderThread at +40 ms), then verify ASUS FlipFont + locale.
        thread(name = "startup-bg") {
            ColdStartHomeView.prewarm(this, appPrefs)
            if (isAsus) {
                val flipFont = if (android.os.Build.VERSION.SDK_INT >= 28) {
                    StartupFactory.flipFontChecked.await()
                    StartupFactory.flipFontSetting
                } else {
                    runCatching {
                        android.provider.Settings.Global.getString(contentResolver, "flipfont_settings_path")
                    }.getOrNull()
                }
                if (!skippedFlipFont || (flipFont != null && flipFont != "default")) {
                    superOnCreate()
                }
            }
            val lineBreakTag = java.util.Locale.getDefault().toLanguageTag() + "-u-lb-normal"
            java.util.Locale.forLanguageTag(lineBreakTag).getDisplayName()
        }
        // Defer Store / Images / Playback / Compose-snapshot initialization until either Frame 1 finishes
        // (Startup.go) or a non-shell screen / headless PlaybackService needs them.
        Startup.later { ensureInitialized(this) }
    }

    companion object {
        lateinit var appPrefs: android.content.SharedPreferences
            private set
        private var initialized = false

        fun ensureInitialized(app: Context) {
            if (initialized) return
            initialized = true
            val prefs = appPrefs
            val filesDir = app.filesDir
            val cacheDir = app.cacheDir
            Store.init(prefs, filesDir, cacheDir)
            Images.init(cacheDir, 64L * 1024 * 1024)
            val onHome = prefs.getInt("ui.tab", TAB_HOME) == TAB_HOME
            if (onHome && prefs.getBoolean("setup.done", false) && (!backend.mixes || backend.libraryReady)) {
                Home.preload()
            }
            flavorInit()
            val lazyAudio = LazyExoAudio(app)
            // Ensure ExoAudio initializes on the main thread in Startup.go() before Playback.restore() reads the cache.
            Startup.later {
                Images.setMemory(picMemory())
                lazyAudio.ensure()
            }
            Playback.init(lazyAudio)
            Startup.later {
                if (prefs.getInt("lyrics.romanizedBy", 0) != BuildConfig.VERSION_CODE) {
                    thread(name = "old lyrics") {
                        val currentSuffix = ".${BuildConfig.VERSION_CODE}.json"
                        val stale = File(cacheDir, "lyrics").listFiles { f -> f.name.contains(".r") && !f.name.endsWith(currentSuffix) }
                        stale?.forEach { f -> f.delete() }
                        prefs.edit().putInt("lyrics.romanizedBy", BuildConfig.VERSION_CODE).apply()
                    }
                }
                thread(name = "sudachi") {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                    val t0 = System.nanoTime()
                    Store.romanizer.warmUp()
                    android.util.Log.i("roma", "Sudachi dictionary ready in ${(System.nanoTime() - t0) / 1_000_000} ms")
                }
            }
        }
    }

    private fun superOnCreate() = super.onCreate()

    private fun skipFlipFont(): Boolean = android.os.Build.VERSION.SDK_INT >= 28 && runCatching {
        val f = StartupFactory.flipFontField ?: org.lsposed.hiddenapibypass.HiddenApiBypass
            .getStaticFields(android.graphics.Typeface::class.java)
            .first { field -> field.name == "FlipFontPath" }
            .apply { isAccessible = true }
        f.set(null, "default")
        f.get(null) == "default"
    }.getOrDefault(false)
}
