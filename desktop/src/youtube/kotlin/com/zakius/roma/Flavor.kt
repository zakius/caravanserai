package com.zakius.roma

import java.io.File

/** roma's own command lines before the app starts: true when one ran. */
fun flavorMain(args: Array<String>): Boolean {
    if (args.firstOrNull() != "--cef-host") return false
    cefHost(args[1], args.drop(2))
    return true
}

/** BotGuard's PoTokens in a hidden Chromium (Browser.kt). */
fun platformPoTokens() = PoTokens(15_000L) { ChromeBotGuard.start() }

/**
 * roma --cef-host <jars> [args]: CEF's process (Browser.kt, Cef). CefHost and everything it uses load again in a class
 * loader of their own, over roma's class path and CEF's downloaded jars, which roma's own class loader doesn't have.
 */
private fun cefHost(jars: String, args: List<String>) {
    val urls = (System.getProperty("java.class.path").split(File.pathSeparator) + jars.split(File.pathSeparator))
        .filter { it.isNotBlank() }.map { File(it).toURI().toURL() }
    val loader = java.net.URLClassLoader(urls.toTypedArray(), ClassLoader.getPlatformClassLoader())
    Thread.currentThread().contextClassLoader = loader
    loader.loadClass("com.zakius.roma.CefHostKt").getMethod("main", Array<String>::class.java).invoke(null, args.toTypedArray())
}
