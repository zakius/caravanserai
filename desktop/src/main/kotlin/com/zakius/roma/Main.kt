package com.zakius.roma

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import java.awt.GraphicsEnvironment
import java.io.File
import kotlin.concurrent.thread

fun main(args: Array<String>) {
    if (flavorMain(args)) return
    if (args.firstOrNull() == "--check") return check()
    // Everything roma does at start, on the UI thread as on Android, in App.onCreate's order.
    javax.swing.SwingUtilities.invokeAndWait {
        Store.init(Prefs(File(Dirs.files, "prefs.properties")), Dirs.files, Dirs.cache)
        Images.init(Dirs.cache, picMemory())
        if (Store.prefs.getInt("ui.tab", TAB_HOME) == TAB_HOME) Home.preload()
        if (BuildConfig.UPDATES) Update.init()
        Playback.init(DesktopAudio())
        Startup.later {
            // After an update, the lyrics an older version romanized go: Playback.romanizedFile no longer reads them.
            if (Store.prefs.getInt("lyrics.romanizedBy", 0) != BuildConfig.VERSION_CODE) thread(name = "old lyrics", isDaemon = true) {
                File(Dirs.cache, "lyrics").listFiles { f -> f.name.contains(".r") && !f.name.endsWith(".${BuildConfig.VERSION_CODE}.json") }?.forEach { it.delete() }
                Store.prefs.edit().putInt("lyrics.romanizedBy", BuildConfig.VERSION_CODE).apply()
            }
            thread(name = "sudachi", isDaemon = true) { Thread.currentThread().priority = Thread.MIN_PRIORITY; Store.romanizer.warmUp() }
        }
        Home.start()
    }
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = BuildConfig.APP_NAME,
            icon = androidx.compose.ui.res.painterResource("icon.png"),
            // A phone's shape, but never past the screen's free area: the bottom bar is the only navigation, and a 768 px
            // screen, or 1080 p at 150 %, has less than 900 dp.
            state = rememberWindowState(
                position = WindowPosition(Alignment.Center),
                width = 440.dp,
                height = minOf(900, GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds.height).dp,
            ),
            onPreviewKeyEvent = { it.type == KeyEventType.KeyDown && it.key == Key.Escape && goBack() },
        ) {
            density = LocalDensity.current.density
            windowWidthDp = (LocalWindowInfo.current.containerSize.width / density).toInt()
            LaunchedEffect(Unit) {
                withFrameNanos {} // the first frame is drawn: the start-up work that waited for it runs
                Startup.go()
                Dictionary.ensure()
            }
            Theme {
                Box(Modifier.fillMaxSize().pointerInput(Unit) { trackPointer() }) {
                    Root()
                    Toast(Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp))
                }
            }
        }
    }
}

// Touch (Home changes no tile under the pointer) and the mouse's back button.
@OptIn(ExperimentalComposeUiApi::class)
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.trackPointer() = awaitPointerEventScope {
    while (true) {
        val e = awaitPointerEvent(PointerEventPass.Initial)
        Touch.down = e.changes.any { it.pressed }
        Touch.last = uptimeMillis()
        if (e.type == PointerEventType.Press && e.button == PointerButton.Back) goBack()
    }
}

@androidx.compose.runtime.Composable
private fun Toast(modifier: Modifier) {
    val (text, until) = toastText ?: return
    LaunchedEffect(text, until) { delay(until - elapsedRealtime()); if (toastText?.second == until) toastText = null }
    Text(
        text, modifier.background(Color(0xE0303030), RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
        color = Color.White, style = MaterialTheme.typography.bodyMedium,
    )
}

/**
 * roma --check: the package's parts start, without a window (CI runs it on each Windows build): Java, ICU's
 * transliteration and roma-aac. Exits 1 with the reason when one doesn't.
 */
private fun check() {
    val result = runCatching {
        println("${BuildConfig.APP_NAME} ${BuildConfig.VERSION_NAME} on Java ${System.getProperty("java.version")}")
        val pinyin = platformIcu.transliterate("Han-Latin", "晴天")
        check(pinyin == "qíng tiān") { "ICU gave $pinyin" }
        val p = ProcessBuilder(AacPcm.decoderPath(), "-hide_banner", "-version").redirectErrorStream(true).start()
        val version = p.inputStream.bufferedReader().readLine()
        check(p.waitFor() == 0 && version?.startsWith("ffmpeg version") == true) { "roma-aac said $version" }
        println("ICU: $pinyin; roma-aac: $version")
    }
    result.exceptionOrNull()?.let { System.err.println("${BuildConfig.APP_NAME} --check failed: $it") }
    kotlin.system.exitProcess(if (result.isSuccess) 0 else 1)
}
