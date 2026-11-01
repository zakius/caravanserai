package com.zakius.roma

import java.io.File

/**
 * The CEF process (Browser.kt, Cef), the only code that uses CEF's classes; they aren't in roma's package but in the
 * jars Cef downloads, which this process gets on its class path. With --install it downloads CEF and ends, reporting
 * on stdout ("status …"). Otherwise CEF starts first (jcefmaven's build, before any window), then a hidden page or a
 * window showing the URL given; the DevTools port goes to stdout, and the process ends when roma closes its stdin
 * or the window closes.
 */
fun main(args: Array<String>) {
    val builder = me.friwi.jcefmaven.CefAppBuilder()
    builder.setInstallDir(File(Dirs.files, "cef"))
    builder.setProgressHandler { state, percent ->
        println("status " + if (state == me.friwi.jcefmaven.EnumProgress.DOWNLOADING && percent >= 0) "Downloading Chromium (CEF)… ${percent.toInt()}%" else "")
        System.out.flush()
    }
    if (args.firstOrNull() == "--install") {
        builder.install()
        return
    }
    val url = args.firstOrNull()
    val port = java.net.ServerSocket(0).use { it.localPort }
    // JCEF draws only through X11 on Linux: on a Wayland desktop, XWayland.
    if (!System.getProperty("os.name").startsWith("Windows")) builder.addJcefArgs("--ozone-platform=x11")
    // The hidden page renders off-screen (no window); the sign-in page is a normal window.
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
    while (System.`in`.read() >= 0) Unit // roma closed it, or went
    app.dispose()
    kotlin.system.exitProcess(0)
}
