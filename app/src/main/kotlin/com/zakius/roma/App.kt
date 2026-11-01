package com.zakius.roma

import android.app.Application
import android.content.Context
import java.io.File
import kotlin.concurrent.thread

class App : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Set-up the first frame would otherwise wait for, started as early as an app can: the RenderThread, which
        // adding the window waited for (8 to 10 ms on the phone, 2026-10-26), the GPU context, through a 1x1 surface
        // that is never drawn to (Vulkan's set-up takes about 25 ms; the window waited 7 to 15 ms for it when it started
        // from onCreate), and ICU's locale-key data, which the first line break loaded (11 ms); text itself would race
        // ASUS's font swap, so a locale name does it.
        thread(name = "hwui") {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val renderer = android.graphics.HardwareRenderer()
                val reader = android.media.ImageReader.newInstance(1, 1, android.graphics.PixelFormat.RGBA_8888, 1,
                    android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
                renderer.setSurface(reader.surface)
                renderer.destroy()
                reader.close()
            }
            java.util.Locale.forLanguageTag(java.util.Locale.getDefault().toLanguageTag() + "-u-lb-normal").getDisplayName()
        }
    }

    @android.annotation.SuppressLint("MissingSuperCall") // maybe called on the fonts thread
    override fun onCreate() {
        // Android's own Application.onCreate is empty. ASUS's applies its FlipFont font theme, and rebuilds the system
        // font map while at it, theme or not: 25 to 40 ms on the phone, which Activity.onCreate waits for under the same
        // lock. With the default font roma marks it applied (skipFlipFont) and the rebuild never runs; with another
        // font, it runs on its own thread beside the rest of start-up.
        val flipFont = android.provider.Settings.Global.getString(contentResolver, "flipfont_settings_path")
        val fontWait = flipFont != null && !(flipFont == "default" && skipFlipFont())
        if (fontWait) thread(name = "fonts") { superOnCreate() } else super.onCreate()
        appContext = this
        Store.init(getSharedPreferences("roma", MODE_PRIVATE), filesDir, cacheDir)
        Images.init(cacheDir, picMemory())
        if (Store.prefs.getInt("ui.tab", TAB_HOME) == TAB_HOME) Home.preload()
        flavorInit()
        Playback.init(ExoAudio(this))
        Startup.later {
            // After an update, the lyrics an older version romanized go: Playback.romanizedFile no longer reads them.
            if (Store.prefs.getInt("lyrics.romanizedBy", 0) != BuildConfig.VERSION_CODE) thread(name = "old lyrics") {
                File(cacheDir, "lyrics").listFiles { f -> f.name.contains(".r") && !f.name.endsWith(".${BuildConfig.VERSION_CODE}.json") }?.forEach { it.delete() }
                Store.prefs.edit().putInt("lyrics.romanizedBy", BuildConfig.VERSION_CODE).apply()
            }
            thread(name = "sudachi") {
                // Below the UI threads, so loading the dictionary doesn't slow them.
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                val t0 = System.nanoTime()
                Store.romanizer.warmUp()
                android.util.Log.i("roma", "Sudachi dictionary ready in ${(System.nanoTime() - t0) / 1_000_000} ms")
            }
        }
    }

    private fun superOnCreate() = super.onCreate()

    /**
     * ASUS's "already applied" check compares Typeface.FlipFontPath with the chosen font's path, but a new process starts
     * with "" there, so it never matches. Set to the default font's ("default", no file name), it matches and the rebuild
     * is skipped: the process already has the default fonts. A blocked hidden API, reached through HiddenApiBypass;
     * false if that fails.
     */
    private fun skipFlipFont(): Boolean = android.os.Build.VERSION.SDK_INT >= 28 && runCatching {
        val f = org.lsposed.hiddenapibypass.HiddenApiBypass.getStaticFields(android.graphics.Typeface::class.java).first { it.name == "FlipFontPath" }
        f.isAccessible = true
        f.set(null, "default")
        f.get(null) == "default"
    }.getOrDefault(false)
}
