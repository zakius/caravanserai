package com.zakius.roma

import java.io.File
import java.net.URLClassLoader
import java.security.MessageDigest

/**
 * Run with `./gradlew :desktop:icuCheck`. It checks that the released ICU4J (slimmed by icuSlim in
 * desktop/build.gradle.kts, then shrunk by ProGuard) transliterates as the full jar does. IcuTrim.sample() goes through
 * each transliterator roma uses in both jars, and the outputs are compared by digest.
 */
fun main(args: Array<String>) {
    val ids = IcuTrim.IDS
    val text = IcuTrim.sample()
    // Each jar gets a class loader of its own, so neither sees the other's data.
    fun digests(jar: String): List<String> {
        val jarUrl = File(jar).toURI().toURL()
        return URLClassLoader(arrayOf(jarUrl), ClassLoader.getPlatformClassLoader()).use { loader ->
            val t = loader.loadClass("com.ibm.icu.text.Transliterator")
            val get = t.getMethod("getInstance", String::class.java)
            val run = t.getMethod("transliterate", String::class.java)
            ids.map { id ->
                val tr = get.invoke(null, id)
                val md = MessageDigest.getInstance("SHA-256")
                for (s in text) {
                    val out = run.invoke(tr, s) as String
                    md.update(out.toByteArray())
                }
                md.digest().toHexString().take(16)
            }
        }
    }
    val full = digests(args[0])
    val slim = digests(args[1])
    for (i in ids.indices) {
        val mark = if (full[i] == slim[i]) "" else "  DIFFERENT"
        println("${ids[i]}: full ${full[i]}, slim ${slim[i]}$mark")
    }
    check(full == slim) { "the slim ICU4J transliterates differently" }
}
