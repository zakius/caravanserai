package roma.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LyricsProvidersTest {
    @Test fun krcAndYrc() {
        val krc = parseKrc("[id:1]\n[1000,2000]<0,500,0>君<500,500,0>の<1000,1000,0>声")
        assertEquals(listOf(1000L, 1500L, 2000L), krc[0].words.map { it.startMs })
        assertEquals("君の声", krc[0].text)
        val yrc = parseYrc("{\"t\":0}\n[1000,2000](1000,500,0)你(1500,500,0)好")
        assertEquals(listOf("你", "好"), yrc[0].words.map { it.text })
        assertEquals(2000L, yrc[0].words[1].endMs)
    }

    @Test fun bracketsInWordsAndTextBeforeTheFirstWordStamp() {
        val yrc = parseYrc("[0,3000](0,500,0)Hello (500,400,0)(oh)")
        assertEquals(listOf("Hello ", "(oh)"), yrc[0].words.map { it.text })
        assertEquals("Hello (oh)", yrc[0].text)
        val lrc = parseLrc("[00:01.00]Hello <00:01.50>world")
        assertEquals("Hello world", lrc[0].text)
        assertEquals(1000L to 1500L, lrc[0].words[0].let { it.startMs to it.endMs })
    }

    @Test fun lyricsPlus() {
        val body = """{"type":"Word","metadata":{"source":"Apple","title":"Spring Day"},"lyrics":[
            {"time":1000,"duration":2000,"text":"보고 싶다 (보고 싶다)","syllabus":[{"time":1000,"duration":500,"text":"보고 "},{"time":1500,"duration":500,"text":"싶다 "},{"time":2000,"duration":1000,"text":"(보고 싶다)","isBackground":true}],"translation":{"lang":"en","text":"I miss you"}},
            {"time":4000,"duration":1000,"text":"Line only"}]}"""
        val q = LyricsQuery("v", "Spring Day", "BTS", null, 0) // duration unknown: the timing isn't checked
        val l = LyricsPlus.parse(body, q)!!
        assertEquals("LyricsPlus · Apple", l.source)
        assertEquals("보고 싶다 (보고 싶다)", l.lines[0].text)
        assertEquals(listOf(1000L to 1500L, 1500L to 2000L, 2000L to 3000L), l.lines[0].words.map { it.startMs to it.endMs })
        assertEquals("I miss you", l.lines[0].translation)
        assertEquals("Line only", l.lines[1].text)
        assertTrue(l.synced && l.wordLevel)
        assertEquals(null, LyricsPlus.parse(body, LyricsQuery("v", "Lemon", "BTS", null, 0))) // the server matched another song
        assertEquals(null, LyricsPlus.parse(body, LyricsQuery("v", "Spring Day", "BTS", null, 600))) // over at 0:05 of a 10-minute song
    }

    @Test fun translationMerge() {
        val l = withTranslation(parseLrc("[00:01.00]君の声"), "[00:01.00]your voice")
        assertEquals("your voice", l[0].translation)
    }
}
