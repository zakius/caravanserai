package com.zakius.roma

import org.jetbrains.skia.BreakIterator
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.ParagraphStyle
import org.jetbrains.skia.paragraph.TextStyle
import java.io.File
import java.security.MessageDigest

/**
 * Run with `./gradlew :desktop:skiaCheck`. It checks that Skia's library with ICU's data trimmed (trimSkiaIcu in
 * desktop/build.gradle.kts) breaks text as the original does. Each library runs in a JVM of its own. For each script,
 * a text of its assigned code points is laid out at several widths to get line breaks, walked with the line and
 * character break iterators, and digested. Word breaking isn't compared, because without the Chinese and Japanese
 * dictionary it splits those scripts per character.
 */
fun main(args: Array<String>) {
    if (args[0] == "run") return println(digests())
    fun run(dir: String): String {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process = ProcessBuilder(
            java, "-Dskiko.library.path=$dir",
            "-cp", System.getProperty("java.class.path"), "com.zakius.roma.SkiaCheckKt", "run",
        ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        return process.inputStream.bufferedReader().readText().trim()
    }
    val full = run(args[0])
    val trimmed = run(args[1])
    println("full    $full\ntrimmed $trimmed")
    check(full.isNotEmpty() && full == trimmed) { "Skia breaks text differently with the trimmed ICU data" }
}

/** Code points per script that go into its text. */
private const val SCRIPT_SAMPLE = 600

/** A space after every this many chars, so the text has break opportunities in every script. */
private const val WORD_LENGTH = 7

private fun digests(): String {
    val texts = sortedMapOf<String, StringBuilder>()
    for (c in 0x20..0x2FFFF) {
        val type = Character.getType(c)
        if (!Character.isDefined(c) || Character.isISOControl(c)) continue
        if (type == Character.SURROGATE.toInt() || type == Character.PRIVATE_USE.toInt()) continue
        val sb = texts.getOrPut(Character.UnicodeScript.of(c).name) { StringBuilder() }
        if (sb.codePointCount(0, sb.length) >= SCRIPT_SAMPLE) continue
        sb.appendCodePoint(c)
        if (sb.length % WORD_LENGTH == 0) sb.append(' ')
    }
    val fonts = FontCollection().setDefaultFontManager(FontMgr.default)
    val md = MessageDigest.getInstance("SHA-256")
    for ((_, sb) in texts) {
        val text = sb.toString()
        for (width in listOf(60f, 150f, 400f)) {
            val builder = ParagraphBuilder(ParagraphStyle(), fonts)
            builder.pushStyle(TextStyle().setFontSize(14f))
            builder.addText(text)
            val p = builder.build()
            p.layout(width)
            for (line in p.lineMetrics) md.update("${line.startIndex}-${line.endIndex};".toByteArray())
            p.close()
        }
        for (iterator in listOf(BreakIterator.makeLineInstance(), BreakIterator.makeCharacterInstance())) {
            iterator.setText(text)
            var at = iterator.first()
            while (at != BreakIterator.DONE) {
                md.update("$at,".toByteArray())
                at = iterator.next()
            }
            iterator.close()
        }
    }
    return md.digest().toHexString()
}
