package roma.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LyricsTest {
    @Test fun lrc() {
        val lines = parseLrc("[ar:YOASOBI]\n[00:12.50]沈むように\n[00:15.00][01:15.00]溶けてゆくように\n")
        assertEquals(listOf(12_500L, 15_000L, 75_000L), lines.map { it.startMs })
        assertEquals("溶けてゆくように", lines[2].text)
        assertTrue(lines.all { it.words.isEmpty() })
    }

    @Test fun enhancedLrc() {
        val lines = parseLrc("[00:01.00]<00:01.00>君<00:01.50>の<00:02.00>声<00:03.00>\n[00:04.00]<00:04.00>next")
        val w = lines[0].words
        assertEquals(listOf("君", "の", "声"), w.map { it.text })
        assertEquals(listOf(1000L to 1500L, 1500L to 2000L, 2000L to 3000L), w.map { it.startMs to it.endMs })
        assertEquals("君の声", lines[0].text)
        assertEquals(5000L, lines[1].words[0].endMs) // last word of the last line: one second
    }

    @Test fun htmlEntities() {
        assertEquals("rock an' roll \"A & B\" <3 it's", unescapeHtml("rock an&apos; roll &quot;A &amp; B&quot; &lt;3 it&#39;s"))
        assertEquals("&amp x &#xZZ;", unescapeHtml("&amp x &#xZZ;"))
        assertEquals("it's \"A\" \\n", unescapeQuotes("it\\'s \\\"A\\\" \\n"))
    }

    @Test fun romanizedWordsMatchTimings() {
        val r = Romanizer(jvmIcu)
        val l = r.romanizeLyrics(Lyrics("t", parseLrc("[00:01.00]<00:01.00>君<00:01.50>の<00:02.00>声<00:03.00>"), true), emptyList(), "x")
        assertEquals(3, l.lines[0].wordLatin.size)
        assertEquals("Kimi no koe", l.lines[0].latin)
    }

    @Test fun cyrillicLineOverridesSong() {
        // The song is Ukrainian (і), but ы can't be Ukrainian: that line is read as Russian.
        val r = Romanizer(jvmIcu)
        val l = r.romanizeLyrics(Lyrics("t", parseLrc("[00:01.00]Добрий вечір\n[00:02.00]Мы были вместе\n[00:03.00]Добрий день"), true), listOf("Artist"), "x")
        assertEquals(listOf("Dobryi vechir", "My byli vmeste", "Dobryi den"), l.lines.map { it.latin })
    }

    @Test fun japaneseDecidedPerSong() {
        // A kanji-only line in a song whose other lines have kana is Japanese, even with a Chinese default.
        val r = Romanizer(jvmIcu, RomanizeSettings(hanDefault = "zh"))
        val l = r.romanizeLyrics(Lyrics("t", parseLrc("[00:01.00]夜に駆ける\n[00:02.00]花火\n[00:03.00]Hello"), true), listOf("Artist"), "x")
        assertEquals(listOf("Yoru ni kakeru", "Hanabi", "Hello"), l.lines.map { it.latin })
        val zh = r.romanizeLyrics(Lyrics("t", parseLrc("[00:01.00]花火"), true), listOf("Artist"), "x")
        assertEquals("Huā huǒ", zh.lines[0].latin)
    }

    @Test fun translationLanguageFromScript() {
        assertEquals("zh", translationLanguage(listOf("你的声音")))
        assertEquals("en", translationLanguage(listOf("your voice")))
        assertEquals(null, translationLanguage(emptyList()))
    }
}

// Made-up fixture lyrics: a Japanese song, the same with small differences, its English version, another song.
private val JA = listOf("青い月が窓を照らして", "眠れない夜を数えてる", "君の声がまだ聞こえる", "遠い街の灯りの中で", "青い月が窓を照らして", "明日へ続く道を探す")
private val JA_VARIANT = listOf("青い月が 窓を照らして、", "眠れない夜を数えてる…", "君の声が まだ聴こえる", "遠い街の灯りの中で", "明日へ続く道を探す")
private val EN = listOf("The blue moon lights the window", "Counting nights I cannot sleep", "I can still hear your voice", "In the lights of a distant town", "Looking for the road to tomorrow")
private val OTHER = listOf("夏の終わりに花が咲いた", "笑顔のままで手を振った", "もう一度だけ会いたいな", "風が止まったら歌おう")

private fun synced(source: String, text: List<String>, word: Boolean = false) = Lyrics(
    source,
    text.mapIndexed { i, t -> Line(i * 4000L, t, if (word) listOf(Word(i * 4000L, i * 4000L + 1000, t)) else emptyList()) },
    true,
)
private fun plain(source: String, text: List<String>) = Lyrics(source, text.map { Line(0, it) }, false)

class LyricsChoiceTest {
    @Test fun similarityThreshold() {
        val ja = JA.joinToString("\n")
        assertTrue(similarity(ja, JA_VARIANT.joinToString("\n")) > SAME_LYRICS)
        assertTrue(similarity(ja, ja.replace("青", "靑").uppercase() + "\n（Ｌａｌａ）") > SAME_LYRICS) // NFKC, a typo, an extra line
        assertTrue(similarity(ja, EN.joinToString("\n")) < 0.1)
        assertTrue(similarity(ja, OTHER.joinToString("\n")) < SAME_LYRICS)
        assertEquals(1.0, similarity("Hello, World!", "hello world"))
    }

    @Test fun rule1WordLevelMatchingYtm() {
        val (l, rule) = chooseLyrics(synced("YTM", JA), listOf(synced("LRCLIB", JA), synced("KuGou", JA_VARIANT, word = true)))!!
        assertEquals("KuGou" to 1, l.source to rule)
    }

    @Test fun rule1WordLevelWithoutYtm() {
        val (l, rule) = chooseLyrics(null, listOf(synced("LRCLIB", JA), synced("NetEase", EN, word = true)))!!
        assertEquals("NetEase" to 1, l.source to rule)
    }

    @Test fun rule2YtmTimedBeatsMatchingSynced() {
        // The English word-level version doesn't match YTM's Japanese text, so it loses.
        val (l, rule) = chooseLyrics(synced("YTM", JA), listOf(synced("NetEase", EN, word = true), synced("LRCLIB", JA)))!!
        assertEquals("YTM" to 2, l.source to rule)
    }

    @Test fun rule3MatchingSyncedOverYtmPlain() {
        val (l, rule) = chooseLyrics(plain("YTM", JA), listOf(synced("LRCLIB", EN), synced("NetEase", JA_VARIANT)))!!
        assertEquals("NetEase" to 3, l.source to rule)
    }

    @Test fun rule4YtmPlain() {
        val (l, rule) = chooseLyrics(plain("YTM", JA), listOf(synced("LRCLIB", EN), plain("Other", JA)))!!
        assertEquals("YTM" to 4, l.source to rule)
    }

    @Test fun rule5UserOrderWithoutYtm() {
        assertEquals("B" to 5, chooseLyrics(null, listOf(plain("A", JA), synced("B", JA), synced("C", JA)))!!.let { it.first.source to it.second })
        assertEquals("A" to 5, chooseLyrics(null, listOf(plain("A", JA), plain("B", JA)))!!.let { it.first.source to it.second })
        assertEquals(null, chooseLyrics(null, emptyList()))
    }

    @Test fun translationAlignment() {
        val lyrics = Lyrics("YTM", listOf(Line(1000, "青い月が窓を照らして"), Line(5000, "眠れない夜"), Line(9000, "君の声"), Line(20000, "誰もいない")), true)
        val t = Translation("NetEase", "en", listOf(
            Line(1200, "青い月が、窓を照らして", translation = "blue moon"), // text match, despite the punctuation
            Line(5600, "眠れない夜を数えてる", translation = "sleepless"), // text differs, start within 1 s
            Line(30000, "君の声", translation = "your voice"), // text match wins over time
        ), true)
        val out = attachTranslation(lyrics, t)
        assertEquals(listOf("blue moon", "sleepless", "your voice", null), out.lines.map { it.translation })
        assertEquals("NetEase", out.translationSource)
        // Unsynced: only text matches count.
        val plainOut = attachTranslation(Lyrics("YTM", lyrics.lines.map { Line(0, it.text) }, false), t)
        assertEquals(listOf("blue moon", null, "your voice", null), plainOut.lines.map { it.translation })
        assertEquals(null, attachTranslation(lyrics, null).lines[0].translation)
    }

    @Test fun pickTranslationByLanguage() {
        val zh = Translation("KuGou", "zh", emptyList(), true)
        val en1 = Translation("LRCLIB", "en", emptyList(), true)
        val en2 = Translation("NetEase", "en", emptyList(), true)
        val l = synced("NetEase", JA)
        assertEquals("NetEase", pickTranslation(l, listOf(zh, en1, en2), listOf("en"))?.source)
        assertEquals("LRCLIB", pickTranslation(synced("YTM", JA), listOf(zh, en1, en2), listOf("en"))?.source)
        assertEquals(null, pickTranslation(l, listOf(zh), listOf("pl")))
        val pl = Translation(GoogleTranslate.SOURCE, "pl", emptyList(), true)
        assertEquals("pl", pickTranslation(l, listOf(en2, pl), listOf("pl", "en"))?.language) // the device language before English
    }

    @Test fun parallelFindCachesOnlyCompleteSearches() {
        class P(override val id: String, val r: () -> Lyrics?) : LyricsProvider {
            override val name = id
            override fun fetch(q: LyricsQuery) = r()
        }
        val q = LyricsQuery("v", "t", "a", null, 0)
        val none = P("none") { null }
        val broken = P("broken") { throw java.io.IOException("down") }
        val slow = P("slow") { Thread.sleep(3000); synced("slow", JA) }
        assertTrue(LyricsProviders.find(q, listOf(none)).let { it.result == null && it.complete })
        assertTrue(LyricsProviders.find(q, listOf(none, broken)).let { it.result == null && !it.complete })
        val t0 = System.currentTimeMillis()
        val r = LyricsProviders.find(q, listOf(slow, P("fast") { synced("fast", OTHER) }), timeoutMs = 300)
        assertTrue(System.currentTimeMillis() - t0 < 2000)
        assertEquals("fast", r.result?.lyrics?.source)
        assertTrue(!r.complete)
        // Translations are split off the chosen lyrics into their own layer.
        val withTr = P("tr") { Lyrics("tr", listOf(Line(0, "青い月", translation = "blue moon")), false) }
        val found = LyricsProviders.find(q, listOf(withTr)).result!!
        assertEquals(null, found.lyrics.lines[0].translation)
        assertEquals("en", found.translations.single().language)
    }
}

class TitleMatchTest {
    @Test fun titles() {
        assertTrue(sameTitle("夜に駆ける", "夜に駆ける (Racing into the Night)"))
        assertTrue(sameTitle("IDOL", "Idol"))
        kotlin.test.assertFalse(sameTitle("夜に駆ける", "Into The Night"))
    }

    @Test fun creditsDropped() {
        val q = LyricsQuery("x", "夜に駆ける", "YOASOBI", null, 260)
        val l = Lyrics("t", parseLrc("[00:00.00]YOASOBI - 夜に駆ける\n[00:00.50]作词 : Ayase\n[00:01.00]作曲：Ayase\n[00:02.00]Lyrics by someone: x\n[00:12.00]沈むように\n[00:15.00]曲がり角"), true)
        assertEquals(listOf("Lyrics by someone: x", "沈むように", "曲がり角"), withoutCredits(l, q).lines.map { it.text })
    }

    @Test fun wholeLineCorrectionInKaraoke() {
        val r = Romanizer(jvmIcu)
        r.overrides = listOf(Override("song", "x", "君の声", "Kimi no Koe!"))
        val l = r.romanizeLyrics(Lyrics("t", parseLrc("[00:01.00]<00:01.00>君<00:01.50>の<00:02.00>声<00:03.00>"), true), emptyList(), "x")
        assertEquals("Kimi no Koe!", l.lines[0].latin)
        assertEquals(listOf("Kimi no Koe!", "", ""), l.lines[0].wordLatin)
    }
}
