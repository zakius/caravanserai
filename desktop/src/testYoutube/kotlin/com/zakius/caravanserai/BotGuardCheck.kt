package com.zakius.caravanserai

import com.metrolist.innertube.YouTube
import kotlinx.coroutines.runBlocking
import roma.core.InnerTubeXStreams
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/**
 * Run with `./gradlew :desktop:botGuardCheck [-Pcef]`. It mints a PoToken from the browser chain (Browser.kt) and checks
 * it where it matters: the music web client's stream, read well past its first megabyte, which the server refuses
 * without a valid token. With -Pcef only CEF is used, as on a system with no Chromium-family browser.
 */
fun main(): Unit = runBlocking {
    val dir = File(System.getProperty("java.io.tmpdir"), "roma-botguard-check")
    dir.mkdirs()
    javax.swing.SwingUtilities.invokeAndWait { Store.init(Prefs(File(dir, "prefs.properties")), dir, dir) }
    YouTube.visitorData = YouTube.visitorData().getOrThrow()
    val cefOnly = System.getProperty("cef") != null
    if (cefOnly) {
        val t = System.currentTimeMillis()
        // Downloads CEF's jars and CEF itself, unless they are there.
        Cef.install()
        println("CEF ready in ${System.currentTimeMillis() - t} ms")
    }
    val tokens = PoTokens(120_000L) {
        if (cefOnly) ChromeBotGuard.open(Cef.start()) else ChromeBotGuard.start()
    }
    val t = System.currentTimeMillis()
    val minted = tokens.getPoToken("CXNv4_wQjKQ", YouTube.visitorData!!, null)
    println("minted: ${minted != null} in ${System.currentTimeMillis() - t} ms, browser ${Store.str("browser.worked", "?")}")
    InnerTubeXStreams.tokenProvider = tokens
    // These clients are skipped, so the stream comes from WEB_REMIX with the token.
    val skipped = setOf("VISIONOS", "VISIONOS_0_1", "WEB_EMBEDDED_PLAYER", "WEB_KIDS", "WEB_CREATOR", "TVHTML5_SIMPLY", "ANDROID_KIDS", "WEB_REMIX__nopo")
    val streamUrl = InnerTubeXStreams.audio("CXNv4_wQjKQ", skipped).url
    val c = URI(streamUrl).toURL().openConnection() as HttpURLConnection
    c.setRequestProperty("Range", "bytes=32768-")
    val got = if (c.responseCode == 206) c.inputStream.use { it.readAllBytes().size } else -1
    println("WEB_REMIX stream past 32 KB: HTTP ${c.responseCode}, $got bytes")
    if (got <= 1_500_000) {
        println("the token was refused")
        kotlin.system.exitProcess(1)
    }
    kotlin.system.exitProcess(0)
}
