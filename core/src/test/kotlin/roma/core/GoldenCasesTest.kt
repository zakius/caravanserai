package roma.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The golden cases shared with oasis (romanization-cases.tsv, oasis's tests/romanization-cases.tsv): oasis's Python
 * port of this Romanizer must give the same output, and so must roma. A change here that alters a case updates the
 * file in both repositories.
 */
class GoldenCasesTest {
    private val r = Romanizer(jvmIcu)

    private fun run(kind: String, input: String, siblings: List<String>): String = when (kind) {
        "name", "title" -> r.text(input, siblings, titleOf = kind == "title").latin
        "line" -> r.machine(input, siblings, sentence = true)
        "parts" -> r.parts(input.split("|"), siblings).joinToString("|")
        "pieces" -> r.pieces(input, siblings).joinToString("|")
        "chinese" -> r.looksChinese(input).toString()
        "lyrics" -> r.romanizeLyrics(Lyrics("", input.split("\\n").map { Line(0, it) }, false), siblings, "").lines.joinToString("\\n") { it.latin }
        else -> error("unknown kind $kind")
    }

    @Test fun sameAsOasis() {
        val cases = javaClass.getResource("/romanization-cases.tsv")!!.readText().lines().filter { it.isNotEmpty() && !it.startsWith("#") }.map { it.split("\t") }
        val wrong = cases.mapNotNull { (kind, input, siblings, expected) ->
            val got = run(kind, input, siblings.split("|").filter { it.isNotEmpty() })
            if (got == expected) null else "$kind\t$input\t$siblings\texpected $expected\tgot $got"
        }
        assertEquals("", wrong.joinToString("\n"))
    }
}
