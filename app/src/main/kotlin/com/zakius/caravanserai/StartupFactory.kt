package com.zakius.caravanserai

import android.app.AppComponentFactory
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import java.lang.reflect.Field
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * Instantiated by LoadedApk immediately after OpenDexFilesFromOat — ~4–5 ms before App.attachBaseContext().
 * On a single background thread:
 * 1. Invokes HardwareRenderer.preload() so RenderThread begins Vulkan CreateInstance right alongside
 *    setupGraphicsSupport() and finishes initShaderDiskCache before activityResume.
 * 2. Caches ASUS's Typeface.FlipFontPath field (if present) off-thread so App.onCreate() skips FlipFont in ~0.01 ms.
 * 3. Once App.attachBaseContext() supplies the Context, pre-acquires SettingsProvider (Global/System/Secure) in
 *    parallel with makeApplication so activityStart doesn't stall on getContentProvider("settings").
 */
@RequiresApi(28)
class StartupFactory : AppComponentFactory() {
    init {
        if (!started) {
            started = true
            thread(name = "hwui-preload") {
                if (Build.VERSION.SDK_INT >= 29) {
                    val ok = runCatching {
                        org.lsposed.hiddenapibypass.HiddenApiBypass.invoke(
                            android.graphics.HardwareRenderer::class.java,
                            null,
                            "preload",
                        )
                    }.isSuccess
                    if (!ok) {
                        runCatching {
                            val renderer = android.graphics.HardwareRenderer()
                            val reader = android.media.ImageReader.newInstance(
                                1, 1, android.graphics.PixelFormat.RGBA_8888, 1,
                                android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
                            )
                            renderer.setSurface(reader.surface)
                            renderer.destroy()
                            reader.close()
                        }
                    }
                }
                val isAsus = Build.MANUFACTURER.equals("asus", ignoreCase = true)
                if (isAsus) {
                    flipFontField = runCatching {
                        org.lsposed.hiddenapibypass.HiddenApiBypass
                            .getStaticFields(android.graphics.Typeface::class.java)
                            .firstOrNull { it.name == "FlipFontPath" }
                            ?.apply { isAccessible = true }
                    }.getOrNull()
                }
                // Wait for App.attachBaseContext() to hand us the Context, then pre-warm the exact Settings.Global
                // keys Android/OEM reads during activityStart/performCreate/ViewRootImpl so MAIN makes 0 Settings IPCs.
                contextReady.await()
                val ctx = baseContext ?: return@thread
                val cr = ctx.contentResolver
                if (isAsus) {
                    flipFontSetting = runCatching {
                        android.provider.Settings.Global.getString(cr, "flipfont_settings_path")
                    }.getOrNull()
                    flipFontChecked.countDown()
                    runCatching { android.provider.Settings.Global.getString(cr, "flipfont_settings_name") }
                }
                runCatching { android.provider.Settings.Global.getString(cr, "force_resizable_activities") }
                runCatching { android.provider.Settings.Global.getString(cr, "show_angle_in_use_dialog_box") }
                runCatching { android.provider.Settings.Global.getString(cr, "render_shadows_in_compositor") }
            }
        }
    }

    companion object {
        @Volatile private var started = false
        @Volatile var flipFontField: Field? = null
            private set
        @Volatile var flipFontSetting: String? = null
            private set
        @Volatile private var baseContext: Context? = null
        private val contextReady = CountDownLatch(1)
        val flipFontChecked = CountDownLatch(1)

        fun onAttachBaseContext(base: Context) {
            baseContext = base
            contextReady.countDown()
        }
    }
}
