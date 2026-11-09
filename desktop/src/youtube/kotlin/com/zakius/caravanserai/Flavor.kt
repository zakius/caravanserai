package com.zakius.caravanserai

import java.io.File

/** Runs the app's own command lines before the app starts. Returns true when one ran. */
fun flavorMain(args: Array<String>): Boolean {
    if (args.firstOrNull() != "--cef-host") return false
    cefHost(args[1], args.drop(2))
    return true
}

/** BotGuard's PoTokens, minted in a hidden Chromium (Browser.kt). */
fun platformPoTokens() = PoTokens(15_000L) { ChromeBotGuard.start() }

/**
 * Runs `dragoman --cef-host <jars> [args]`, CEF's process (Browser.kt, Cef). CefHost and everything it uses are loaded again
 * in a class loader of their own, over the app's class path plus CEF's downloaded jars, because the app's own class loader
 * doesn't have those jars.
 */
private fun cefHost(jars: String, args: List<String>) {
    val paths = System.getProperty("java.class.path").split(File.pathSeparator) + jars.split(File.pathSeparator)
    val urls = paths.filter { it.isNotBlank() }.map { File(it).toURI().toURL() }
    val loader = java.net.URLClassLoader(urls.toTypedArray(), ClassLoader.getPlatformClassLoader())
    Thread.currentThread().contextClassLoader = loader
    val main = loader.loadClass("com.zakius.caravanserai.CefHostKt").getMethod("main", Array<String>::class.java)
    main.invoke(null, args.toTypedArray())
}
