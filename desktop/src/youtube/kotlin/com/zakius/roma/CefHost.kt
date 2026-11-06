package com.zakius.roma

import java.io.File

/**
 * The CEF process (Browser.kt, Cef). This is the only code that uses CEF's classes. They aren't in roma's package but in
 * the jars Cef downloads, which this process gets on its class path.
 *
 * With --install it downloads CEF, reports progress on stdout as "status …" lines, and ends. Otherwise CEF starts first
 * (jcefmaven's build must run before any window exists), then opens a hidden page, or a window showing the given URL.
 * It prints the DevTools port to stdout and ends when roma closes its stdin or the window closes.
 */
fun main(args: Array<String>) {
    val builder = me.friwi.jcefmaven.CefAppBuilder()
    builder.setInstallDir(File(Dirs.files, "cef"))
    builder.setProgressHandler { state, percent ->
        val downloading = state == me.friwi.jcefmaven.EnumProgress.DOWNLOADING && percent >= 0
        val status = if (downloading) "Downloading Chromium (CEF)… ${percent.toInt()}%" else ""
        println("status $status")
        System.out.flush()
    }
    if (args.firstOrNull() == "--install") {
        builder.install()
        return
    }
    val url = args.firstOrNull()
    val port = java.net.ServerSocket(0).use { it.localPort }
    // On Linux, JCEF draws only through X11, which on a Wayland desktop means XWayland.
    if (!System.getProperty("os.name").startsWith("Windows")) builder.addJcefArgs("--ozone-platform=x11")
    // The hidden page renders off-screen with no window. The sign-in page is a normal window.
    builder.cefSettings.windowless_rendering_enabled = url == null
    builder.cefSettings.remote_debugging_port = port
    builder.cefSettings.root_cache_path = File(Dirs.files, "browser/cef").path
    val app = builder.build()
    val browser = app.createClient().createBrowser(url ?: "about:blank", url == null, false)
    if (url == null) browser.createImmediately()
    else javax.swing.SwingUtilities.invokeLater {
        javax.swing.JFrame("Sign in to YouTube Music").apply {
            contentPane.add(browser.uiComponent)
            setSize(480, 720)
            defaultCloseOperation = javax.swing.WindowConstants.EXIT_ON_CLOSE
            isVisible = true
        }
    }
    println("DevTools port $port")
    System.out.flush()
    // Blocks until roma closes stdin or exits.
    while (System.`in`.read() >= 0) Unit
    app.dispose()
    kotlin.system.exitProcess(0)
}
