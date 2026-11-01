package com.zakius.roma

import java.io.File
import java.net.URLClassLoader
import java.security.MessageDigest

// ./gradlew :desktop:icuCheck: the released ICU4J (slimmed, desktop/build.gradle.kts icuSlim, and shrunk by ProGuard)
// transliterates as the full jar does: IcuTrim.sample() through each transliterator roma uses, compared by digest.
fun main(args: Array<String>) {
    val ids = IcuTrim.IDS
    val text = IcuTrim.sample()
    // Each jar in a class loader of its own, so neither sees the other's data.
    fun digests(jar: String): List<String> = URLClassLoader(arrayOf(File(jar).toURI().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
        val t = loader.loadClass("com.ibm.icu.text.Transliterator")
        val get = t.getMethod("getInstance", String::class.java)
        val run = t.getMethod("transliterate", String::class.java)
        ids.map { id ->
            val tr = get.invoke(null, id)
            val md = MessageDigest.getInstance("SHA-256")
            for (s in text) md.update((run.invoke(tr, s) as String).toByteArray())
            md.digest().toHexString().take(16)
        }
    }
    val full = digests(args[0])
    val slim = digests(args[1])
    ids.indices.forEach { println("${ids[it]}: full ${full[it]}, slim ${slim[it]}" + if (full[it] == slim[it]) "" else "  DIFFERENT") }
    check(full == slim) { "the slim ICU4J transliterates differently" }
}
