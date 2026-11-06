package com.zakius.roma

import android.app.Application
import android.content.Context
import java.io.File
import kotlin.concurrent.thread

class App : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Starts, as early as an app can, the set-up that the first frame would otherwise wait for. That is the
        // RenderThread, which adding the window waits for, and the GPU context, created through a 1x1 surface that is
        // never drawn to (Vulkan's set-up takes about 25 ms, and onCreate is too late to hide it). It also loads ICU's
        // locale-key data, which the first line break needs. A locale name loads it, because measuring real text here
        // would race ASUS's font swap.
        thread(name = "hwui") {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val renderer = android.graphics.HardwareRenderer()
                val reader = android.media.ImageReader.newInstance(1, 1, android.graphics.PixelFormat.RGBA_8888, 1,
                    android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
                renderer.setSurface(reader.surface)
                renderer.destroy()
                reader.close()
            }
            val lineBreakTag = java.util.Locale.getDefault().toLanguageTag() + "-u-lb-normal"
            java.util.Locale.forLanguageTag(lineBreakTag).getDisplayName()
        }
    }

    @android.annotation.SuppressLint("MissingSuperCall") // super.onCreate may run on the "fonts" thread instead
    override fun onCreate() {
        // Android's own Application.onCreate is empty. ASUS's version applies its FlipFont font theme and rebuilds the
        // system font map, even with no theme set. That takes 25 to 40 ms, and Activity.onCreate waits for it under the
        // same lock. With the default font, roma marks the theme as already applied (skipFlipFont), so the rebuild never
        // runs. With another font, the rebuild runs on its own thread alongside the rest of start-up.
        val flipFont = android.provider.Settings.Global.getString(contentResolver, "flipfont_settings_path")
        val fontWait = flipFont != null && !(flipFont == "default" && skipFlipFont())
        if (fontWait) {
            thread(name = "fonts") { superOnCreate() }
        } else {
            super.onCreate()
        }
        appContext = this
        Store.init(getSharedPreferences("roma", MODE_PRIVATE), filesDir, cacheDir)
        Images.init(cacheDir, picMemory())
        if (Store.prefs.getInt("ui.tab", TAB_HOME) == TAB_HOME) Home.preload()
        flavorInit()
        Playback.init(ExoAudio(this))
        Startup.later {
            // Playback.romanizedFile only reads romanized lyrics tagged with this version code, so after an update the
            // ones that older versions wrote are deleted.
            if (Store.prefs.getInt("lyrics.romanizedBy", 0) != BuildConfig.VERSION_CODE) {
                thread(name = "old lyrics") {
                    val currentSuffix = ".${BuildConfig.VERSION_CODE}.json"
                    val stale = File(cacheDir, "lyrics").listFiles { f -> f.name.contains(".r") && !f.name.endsWith(currentSuffix) }
                    stale?.forEach { f -> f.delete() }
                    Store.prefs.edit().putInt("lyrics.romanizedBy", BuildConfig.VERSION_CODE).apply()
                }
            }
            thread(name = "sudachi") {
                // Background priority, so loading the dictionary doesn't slow the UI threads.
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                val t0 = System.nanoTime()
                Store.romanizer.warmUp()
                android.util.Log.i("roma", "Sudachi dictionary ready in ${(System.nanoTime() - t0) / 1_000_000} ms")
            }
        }
    }

    private fun superOnCreate() = super.onCreate()

    /**
     * ASUS's "already applied" check compares Typeface.FlipFontPath with the chosen font's path. A new process starts with
     * "" there, so the check never matches. Setting it to the default font's path ("default", with no file name) makes it
     * match, and the rebuild is skipped; the process already has the default fonts. The field is a blocked hidden API,
     * reached through HiddenApiBypass. Returns false if that fails.
     */
    private fun skipFlipFont(): Boolean = android.os.Build.VERSION.SDK_INT >= 28 && runCatching {
        val typefaceFields = org.lsposed.hiddenapibypass.HiddenApiBypass.getStaticFields(android.graphics.Typeface::class.java)
        val f = typefaceFields.first { field -> field.name == "FlipFontPath" }
        f.isAccessible = true
        f.set(null, "default")
        f.get(null) == "default"
    }.getOrDefault(false)
}
