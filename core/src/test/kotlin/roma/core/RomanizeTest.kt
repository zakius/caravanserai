package roma.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RomanizeTest {
    private val r = Romanizer(jvmIcu)

    @Test fun namesInWords() {
        // The furigana layout: each word with its slice of the title-cased Latin form.
        val t = r.text("夜に駆ける")
        assertEquals(t.original, t.pieces.joinToString(""))
        assertEquals("Yoru ni Kakeru", t.pieceLatin.joinToString(""))
        assertTrue(t.pieces.size > 1)
        // A Latin form that spells something else gets no words.
        assertEquals(emptyList(), r.pieced("夜に駆ける", "Racing into the Night").pieces)
        val both = Text.join(listOf(r.text("君の声"), Text("Anri", "Anri")), ", ")
        assertEquals("Kimi no Koe, Anri", both.pieceLatin.joinToString(""))
        assertEquals("君の声, Anri", both.pieces.joinToString(""))
    }

    @Test fun mixedScript() {
        // Code points, not UTF-16 chars: an emoji doesn't make a title non-Latin.
        assertTrue(r.text("Happy 🎉").isLatin)
        assertEquals("Yě Jiā", Romanizer(jvmIcu, RomanizeSettings(hanDefault = "zh")).machine("𠮷野家").substringAfter(' '))
    }

    @Test fun greek() {
        val elot = Romanizer(jvmIcu, RomanizeSettings(greek = "elot"))
        assertEquals("Víos", elot.machine("βίος"))
        assertEquals("Efcharistó", elot.machine("ευχαριστώ"))
    }

    @Test fun correctionShownAsTyped() {
        val o = Romanizer(jvmIcu)
        o.overrides = listOf(Override("song", "s1", "夜に駆ける", "yoru ni KAKERU"), Override("song", "s1", "アイドル", "アイドル"))
        assertEquals("yoru ni KAKERU", o.text("夜に駆ける", songId = "s1").latin)
        assertTrue(o.text("アイドル", songId = "s1").isLatin) // "Don't romanize"
    }

    @Test fun noMacrons() {
        val plain = Romanizer(jvmIcu, RomanizeSettings(japaneseMacrons = false))
        assertEquals("Tokyo", plain.machine("東京"))
    }

    @Test fun hanDecision() {
        // Kana in the string or a sibling means Japanese; otherwise the default language decides.
        val zh = Romanizer(jvmIcu, RomanizeSettings(hanDefault = "zh"))
        assertFalse(zh.isJapanese("晴天", listOf("周杰伦")))
        assertTrue(zh.isJapanese("晴天", listOf("あいみょん")))
        assertEquals("Qíng Tiān", zh.machine("晴天"))
        assertEquals("Qing Tian", Romanizer(jvmIcu, RomanizeSettings(hanDefault = "zh", chineseTones = false)).machine("晴天"))
    }

    @Test fun chineseEvidence() {
        // The first string a fresh Romanizer judges: 青花瓷 is Chinese only by the dictionary's evidence, which
        // looksChinese loads itself.
        assertTrue(r.looksChinese("青花瓷"))
        // A variant form outside the BMP is no evidence of Chinese.
        assertFalse(r.looksChinese("𠮷野家"))
    }

    @Test fun cyrillic() {
        val pl = Romanizer(jvmIcu, RomanizeSettings(cyrillic = "polish"))
        assertEquals("Jewgienij", pl.machine("Евгений"))
        assertEquals("Szczedrin", pl.machine("Щедрин"))
        assertEquals("Ałła", pl.machine("Алла"))
        assertEquals("SZUM", pl.machine("ШУМ"))
        assertEquals("PŁASZCZ", pl.machine("ПЛАЩ"))
        assertEquals("Žanna", Romanizer(jvmIcu, RomanizeSettings(cyrillic = "iso9")).machine("Жанна"))
    }

    @Test fun otherCyrillicLanguages() {
        assertEquals("kk", Romanizer.cyrillicLanguage("Ерлерін елін сүйген"))
        // Russian keeps its schemes; ISO 9 applies to every language.
        assertEquals("ru", Romanizer.cyrillicLanguage("Объявление в подъезде"))
        val iso = Romanizer(jvmIcu, RomanizeSettings(cyrillic = "iso9"))
        assertEquals("Dobrij večìr", iso.machine("Добрий вечір", sentence = true))
        // Lyrics decide once per song: a line without Kazakh letters in a Kazakh song is still Kazakh.
        assertEquals(listOf("Ūly dala", "Erlerın"), r.parts(listOf("Ұлы дала"), cyrLang = "kk") + r.parts(listOf("Ерлерін"), cyrLang = "kk"))
    }

    @Test fun overridesWin() {
        val o = Romanizer(jvmIcu)
        o.overrides = listOf(Override("artist", "UC1", "米津玄師", "Kenshi Yonezu"), Override("text", "", "君の声", "Kimi no Koe"))
        assertEquals("Kenshi Yonezu", o.text("米津玄師", artistId = "UC1").latin)
        assertEquals("Kimi no Koe", o.text("君の声").latin)
        assertEquals(o.machine("米津玄師"), o.text("米津玄師", artistId = "UC2").latin)
    }

    @Test fun partsMapOneToOne() {
        assertEquals(listOf("Ni", " hao"), Romanizer(jvmIcu, RomanizeSettings(hanDefault = "zh", chineseTones = false)).parts(listOf("你", "好")))
        assertEquals(listOf("an", "nyeong"), Romanizer(jvmIcu).parts(listOf("안", "녕")).map { it.lowercase() }) // Hangul stays joined
        // A 、 ending one part is followed by a space; an opening bracket timed alone doesn't take the line's capital.
        assertEquals("\"Kotae wa, itsu?\"", r.parts(listOf("「", "答えは、", "いつ?", "」")).joinToString("").trim())
    }

    @Test fun localSearch() {
        val s = Song("x", r.text("君の声"), listOf(Named(null, r.text("Artist"))))
        assertTrue(matches(s, "kimi no"))
        assertTrue(matches(s, "kiminokoe"))
        assertTrue(matches(s, "君の"))
        assertFalse(matches(s, "tokyo"))
        // Typos, spaces and macrons.
        val y = Song("y", r.text("夜に駆ける"), listOf(Named(null, Text("YOASOBI", "YOASOBI"))))
        assertEquals(1, matchDistance(y, "yoru ni kakru"))
        assertEquals(2, matchDistance(y, "yoasbi yoru ni kakru"))
        assertTrue(matches(Song("t", r.text("東京"), emptyList()), "tokyo"))
        assertTrue(matches(Song("k", Text("Lemon", "Lemon"), listOf(Named(null, Text("米津玄師", "Kenshi Yonezu")))), "yonezy"))
        // Short queries must match exactly, so the list isn't flooded.
        assertFalse(matches(Song("l", Text("Live", "Live"), emptyList()), "love"))
    }

    @Test fun kyrgyz() {
        assertEquals("ky", Romanizer.cyrillicLanguage("Мең сени сүйөм"))
        assertEquals("kk", Romanizer.cyrillicLanguage("Ізі қалған"))
        assertEquals("mn", Romanizer.cyrillicLanguage("Өнөөдөр сайхан өдөр"))
    }

    @Test fun widthAloneIsNotRomanization() {
        assertTrue(r.text("you to the monster （Yeah）").isLatin)
        assertTrue(r.text("ＬＯＶＥ").isLatin)
        assertFalse(r.text("君の声").isLatin)
    }

    @Test fun wordReadings() {
        val o = Romanizer(jvmIcu)
        val before = o.machine("私の歌", sentence = true)
        o.overrides = listOf(Override("word", "", "私", "watashi"))
        // A word reading applies to the token inside longer strings: titles, lyric lines, karaoke parts.
        assertEquals("Watashi no uta", o.machine("私の歌", sentence = true))
        assertEquals("Watashi no Uta", o.text("私の歌").latin)
        assertEquals("Uta to Watashi", o.text("歌と私").latin)
        assertEquals(listOf("Kimi to", " watashi"), o.parts(listOf("君と", "私")))
        assertEquals("Kimi to watashi", o.parts(listOf("君と私")).single())
        // Only whole tokens: 私立 (shiritsu) is another word.
        assertEquals(r.machine("私立", sentence = true), o.machine("私立", sentence = true))
        assertTrue(before != o.machine("私の歌", sentence = true))
        // A song's own reading wins over the everywhere one, only in that song.
        o.overrides = listOf(Override("word", "", "私", "watashi"), Override("word", "s1", "私", "atashi"))
        assertEquals("Atashi no Uta", o.text("私の歌", songId = "s1").latin)
        assertEquals("Watashi no Uta", o.text("私の歌", songId = "s2").latin)
        // A word that is several tokens matches as a run; Korean and Cyrillic match a word between spaces.
        o.overrides = listOf(Override("word", "", "明日", "asu"), Override("word", "", "봄날", "Spring Day"), Override("word", "", "крови", "krovee"))
        assertEquals("Asu no yoru", o.machine("明日の夜", sentence = true))
        assertEquals("BTS Spring Day", o.machine("BTS 봄날"))
        assertEquals("Gruppa krovee", o.machine("Группа крови"))
    }

    @Test fun piecesScriptsAndWordInfo() {
        val latin = r.parts(r.pieces("沈むように"))
        assertEquals("Shizumu yō ni", latin.joinToString("").trim())
        assertEquals(setOf("ja"), r.scripts("夜に駆ける"))
        assertEquals(setOf("zh"), Romanizer(jvmIcu, RomanizeSettings(hanDefault = "zh")).scripts("晴天"))
        assertEquals(setOf("ko", "cyrillic"), r.scripts("봄날 Я"))
        assertTrue(r.scripts("LiSA").isEmpty())
        val info = r.lookUp("食べました")
        assertEquals("taberu", info.first().dictionaryRomaji)
        assertEquals("verb", info.first().partOfSpeech)
    }

    @Test fun pairedPunctuationOpensTheNextWord() {
        // An opening quote or bracket goes with the word it opens, a closing one with the word it closes, and a space
        // stands on their outer side, in names, lyric lines and karaoke parts alike.
        val line = "「痛い」と「居たい」"
        assertEquals(listOf("\"Itai\"", " to", " \"itai\""), r.parts(r.pieces(line)))
        assertEquals("\"Itai\" to \"itai\"", r.romanizeLyrics(Lyrics("", listOf(Line(0, line)), false), emptyList(), "").lines[0].latin)
        assertEquals(listOf("（痛い）", "と", "（居たい）"), r.pieces("（痛い）と（居たい）"))
        assertEquals("“Itai” to “itai”", r.parts(r.pieces("“痛い”と“居たい”")).joinToString("").trim())
        assertEquals("Kimi wa \"itai\" to itta", r.parts(r.pieces("君は「痛い」と言った")).joinToString(""))
    }

    @Test fun japaneseWithoutDictionary() {
        // It crashed the app: lyrics romanized at launch before the dictionary app was installed.
        val none = Romanizer(jvmIcu, dictionary = { error("no dictionary") })
        assertEquals("Konnichiha", none.machine("こんにちは")) // kana read as written
        none.machine("走って行く")
    }
}
