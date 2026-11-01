package com.zakius.roma

import com.metrolist.innertube.YouTube
import kotlinx.coroutines.runBlocking
import roma.core.InnerTubeXStreams
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

// ./gradlew :desktop:botGuardCheck [-Pcef]: PoTokens from the browser chain (Browser.kt), checked where they matter: the
// music web client's stream, read past 1 MB, where a stream without a valid token is refused.
fun main(): Unit = runBlocking {
    val dir = File(System.getProperty("java.io.tmpdir"), "roma-botguard-check").also { it.mkdirs() }
    javax.swing.SwingUtilities.invokeAndWait { Store.init(Prefs(File(dir, "prefs.properties")), dir, dir) }
    YouTube.visitorData = YouTube.visitorData().getOrThrow()
    if (System.getProperty("cef") != null) {
        val t = System.currentTimeMillis()
        Cef.install() // its jars and CEF itself, unless they are there
        println("CEF ready in ${System.currentTimeMillis() - t} ms")
    }
    // -Dcef: CEF alone, as when the system has no Chromium-family browser.
    val tokens = PoTokens(120_000L) { if (System.getProperty("cef") != null) ChromeBotGuard.open(Cef.start()) else ChromeBotGuard.start() }
    val t = System.currentTimeMillis()
    val minted = tokens.getPoToken("CXNv4_wQjKQ", YouTube.visitorData!!, null)
    println("minted: ${minted != null} in ${System.currentTimeMillis() - t} ms, browser ${Store.str("browser.worked", "?")}")
    InnerTubeXStreams.tokenProvider = tokens
    val only = setOf("VISIONOS", "VISIONOS_0_1", "WEB_EMBEDDED_PLAYER", "WEB_KIDS", "WEB_CREATOR", "TVHTML5_SIMPLY", "ANDROID_KIDS", "WEB_REMIX__nopo")
    val c = URI(InnerTubeXStreams.audio("CXNv4_wQjKQ", only).url).toURL().openConnection() as HttpURLConnection
    c.setRequestProperty("Range", "bytes=32768-")
    val got = if (c.responseCode == 206) c.inputStream.use { it.readAllBytes().size } else -1
    println("WEB_REMIX stream past 32 KB: HTTP ${c.responseCode}, $got bytes")
    if (got <= 1_500_000) { println("the token was refused"); kotlin.system.exitProcess(1) }
    kotlin.system.exitProcess(0)
}
