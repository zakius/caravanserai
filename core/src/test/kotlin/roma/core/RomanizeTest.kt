package roma.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RomanizeTest {
    private val r = Romanizer(jvmIcu)

    @Test fun japanese() {
        assertEquals("Yoru ni Kakeru", r.machine("夜に駆ける"))
        assertEquals("Kimi no Koe", r.machine("君の声"))
        assertEquals("Kimi no Na wa", r.machine("君の名は"))
        assertEquals("Tōkyō", r.machine("東京"))
        assertEquals("Eiga", r.machine("映画"))
        assertEquals("Kimi wa", r.machine("君は"))
        assertEquals("Kōhī", r.machine("コーヒー"))
        assertEquals("Shizumu Yō ni", r.machine("沈むように"))
        assertEquals("Sayonara dake Datta", r.machine("さよならだけだった"))
        assertEquals("Subete ga Wakatta", r.machine("全てが分かった"))
        assertEquals("matcha", r.kana("マッチャ"))
        assertEquals("kon'ya", r.kana("コンヤ"))
        assertEquals("fan", r.kana("ファン"))
    }

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

    @Test fun sentenceCase() {
        // Lyric lines: only the first letter is capitalized.
        assertEquals("Shizumu yō ni", r.machine("沈むように", sentence = true))
        assertEquals("Sayonara dake datta", r.machine("さよならだけだった", sentence = true))
        assertEquals("Subete ga wakatta", r.machine("全てが分かった", sentence = true))
        assertEquals("No uta", r.machine("の歌", sentence = true))
    }

    @Test fun titleCase() {
        assertEquals("Departures ~Anata ni Okuru Ai no Uta~", r.machine("Departures ~あなたにおくるアイの歌~"))
        // Latin parts are never changed.
        assertEquals("Bokura no LIVE Kimi to no LIFE", r.machine("ぼくらのLIVE 君とのLIFE"))
        assertEquals("LiSA no Uta (Ballade Ver.)", r.machine("LiSAの歌 (Ballade Ver.)"))
        // Korean and Chinese: every word; Chinese has one word per syllable.
        assertEquals("BTS Bomnal", r.machine("BTS 봄날"))
        assertEquals("Qíng Tiān", Romanizer(jvmIcu, RomanizeSettings(hanDefault = "zh")).machine("晴天"))
        // Cyrillic keeps the original's capitals.
        assertEquals("Gruppa krovi", r.machine("Группа крови"))
        assertEquals("Ya lyublyu tebya", r.machine("Я люблю тебя"))
    }

    @Test fun subsidiaryVerbs() {
        assertEquals("Tokete Yuku", r.machine("溶けてゆく"))
        assertEquals("tokete yuku", r.machine("溶けてゆく", sentence = true).lowercase())
        assertEquals("Tabete iru", r.machine("食べている", sentence = true))
        assertEquals("Tabeteru", r.machine("食べてる", sentence = true))
        assertEquals("Tabechau", r.machine("食べちゃう", sentence = true))
    }

    @Test fun mixedScript() {
        // Full-width Latin and half-width kana are normalized first.
        assertEquals("LOVE", r.machine("ＬＯＶＥ"))
        assertEquals("Ai", r.machine("ｱｲ"))
        assertEquals("Kimi ni, Todoke.", r.machine("君に、届け。"))
        assertEquals("Romio Jurietto", r.machine("ロミオ・ジュリエット"))
        assertEquals("Kimi no \"Uta\"", r.machine("君の「歌」"))
        // Code points, not UTF-16 chars: an emoji doesn't make a title non-Latin.
        assertTrue(r.text("Happy 🎉").isLatin)
        assertEquals("Yě Jiā", Romanizer(jvmIcu, RomanizeSettings(hanDefault = "zh")).machine("𠮷野家").substringAfter(' '))
    }

    @Test fun stylizedStaysAsIs() {
        assertEquals("(´・ω・`)", r.machine("(´・ω・`)"))
        assertEquals("ΛRCTIC", r.machine("ΛRCTIC"))
        assertEquals("ЯOCK", r.machine("ЯOCK"))
        assertEquals("μ's", r.machine("μ's"))
        assertEquals("Ya i ty", r.machine("Я и ты"))
        assertEquals("Zvyozdy i ya", r.machine("звёзды и я"))
    }

    @Test fun greek() {
        assertEquals("Bios", r.machine("Βίος"))
        assertEquals("Bios", r.machine("βίος"))
        assertEquals("Athena", r.machine("Αθηνα"))
        val elot = Romanizer(jvmIcu, RomanizeSettings(greek = "elot"))
        assertEquals("Víos", elot.machine("βίος"))
        assertEquals("Efcharistó", elot.machine("ευχαριστώ"))
    }

    @Test fun ownLatinForm() {
        assertEquals("Spica", r.text("スピカ - Spica", titleOf = true).latin)
        assertEquals("Idol", r.text("アイドル - Idol", titleOf = true).latin)
        assertEquals("Suzume (feat. Toaka)", r.text("すずめ - Suzume (feat. Toaka)", titleOf = true).latin)
        assertEquals("Yoru ni Kakeru - Live", r.text("夜に駆ける - Live", titleOf = true).latin)
        assertEquals("Silhouette", r.text("シルエット (Silhouette)", titleOf = true).latin)
        // Version tags are not a Latin form.
        assertEquals("Yoru ni Kakeru (Ballade Ver.)", r.text("夜に駆ける (Ballade Ver.)", titleOf = true).latin)
        assertEquals("Tetorisu (feat. Kasane Teto)", r.text("テトリス（feat. Kasane Teto）", titleOf = true).latin)
        // Only titles: an artist name is romanized whole.
        assertEquals("Supika - Spica", r.text("スピカ - Spica").latin)
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
        assertTrue(r.isJapanese("君の声", emptyList()))
        assertTrue(r.isJapanese("紅蓮華", listOf("LiSA", "紅蓮華 (ぐれんげ)")))
        val zh = Romanizer(jvmIcu, RomanizeSettings(hanDefault = "zh"))
        assertFalse(zh.isJapanese("晴天", listOf("周杰伦")))
        assertTrue(zh.isJapanese("晴天", listOf("あいみょん")))
        assertEquals("Qíng Tiān", zh.machine("晴天"))
        assertEquals("Qing Tian", Romanizer(jvmIcu, RomanizeSettings(hanDefault = "zh", chineseTones = false)).machine("晴天"))
    }

    @Test fun chineseEvidence() {
        // With Japanese as the default, Han text without kana is Chinese only on evidence: a character outside
        // JIS X 0208 (說, 伦), a word the dictionary doesn't know (瓷), or characters that don't form words (周|杰|倫).
        for (s in listOf("不能說的秘密", "周杰伦", "青花瓷", "周杰倫", "超人不會飛", "張學友")) assertTrue(r.looksChinese(s), s)
        // Japanese without kana: names, compounds, rare kanji and variant forms.
        for (s in listOf("米津玄師", "紅蓮華", "千本桜", "薔薇", "津軽海峡冬景色", "椎名林檎", "倖田來未", "山﨑", "𠮷野家")) assertFalse(r.looksChinese(s), s)
        // A sibling's evidence decides the title: every song by 周杰倫 is Chinese.
        assertFalse(r.isJapanese("晴天", listOf("周杰倫")))
        assertTrue(r.isJapanese("晴天", emptyList()))
        assertEquals("Zhōu Jié Lún", r.machine("周杰倫"))
        // Kana still wins.
        assertTrue(r.isJapanese("晴天", listOf("周杰倫", "あいみょん")))
    }

    @Test fun korean() {
        assertEquals("Saranghae", r.machine("사랑해"))
        assertEquals("Hangugeo", r.machine("한국어"))
        assertEquals("Gamsahamnida", r.machine("감사합니다"))
        assertEquals("Silla", r.machine("신라"))
        assertEquals("Joahae", r.machine("좋아해"))
        assertEquals("BTS Bomnal", r.machine("BTS 봄날"))
    }

    @Test fun cyrillic() {
        assertEquals("Kino", r.machine("Кино"))
        assertEquals("Zhanna Aguzarova", r.machine("Жанна Агузарова"))
        assertEquals("Yevgeniy", r.machine("Евгений"))
        val pl = Romanizer(jvmIcu, RomanizeSettings(cyrillic = "polish"))
        assertEquals("Jewgienij", pl.machine("Евгений"))
        assertEquals("Szczedrin", pl.machine("Щедрин"))
        assertEquals("Ałła", pl.machine("Алла"))
        assertEquals("SZUM", pl.machine("ШУМ"))
        assertEquals("PŁASZCZ", pl.machine("ПЛАЩ"))
        assertEquals("PLASHCH", r.machine("ПЛАЩ"))
        assertEquals("Žanna", Romanizer(jvmIcu, RomanizeSettings(cyrillic = "iso9")).machine("Жанна"))
    }

    @Test fun otherCyrillicLanguages() {
        assertEquals("kk", Romanizer.cyrillicLanguage("Ерлерін елін сүйген"))
        assertEquals("Izı qalğan babalardyñ qasterlı jerım!", r.machine("Ізі қалған бабалардың қастерлі жерім!", sentence = true))
        assertEquals("Erlerın elın süigen", r.machine("Ерлерін елін сүйген", sentence = true))
        assertEquals("İt pen mysyq", r.machine("Ит пен мысық", sentence = true))
        assertEquals("Dobryi vechir, my z Ukrainy", r.machine("Добрий вечір, ми з України", sentence = true))
        assertEquals("Yizhak i zghoda, miaso", r.machine("Їжак і згода, м'ясо", sentence = true))
        assertEquals("Dobraj ranicy, siabry z Bielarusi", r.machine("Добрай раніцы, сябры з Беларусі", sentence = true))
        assertEquals("Jon liubić svaju siamju ŭ Homieli", r.machine("Ён любіць сваю сям'ю ў Гомелі", sentence = true))
        assertEquals("Ljubav i život u Beogradu", r.machine("Љубав и живот у Београду", sentence = true))
        assertEquals("Đorđe, ćerka, džem, njega, LJUBAV", r.machine("Ђорђе, ћерка, џем, њега, ЉУБАВ", sentence = true))
        assertEquals("Gjorgji, kje dojdam", r.machine("Ѓорѓи, ќе дојдам", sentence = true))
        assertEquals("Savsem sam v Balgaria", r.machine("Съвсем сам в България", sentence = true))
        assertEquals("Öglöönii mend", r.machine("Өглөөний мэнд", sentence = true))
        // Russian keeps its schemes; ISO 9 applies to every language.
        assertEquals("ru", Romanizer.cyrillicLanguage("Объявление в подъезде"))
        val iso = Romanizer(jvmIcu, RomanizeSettings(cyrillic = "iso9"))
        assertEquals("Dobrij večìr", iso.machine("Добрий вечір", sentence = true))
        // Lyrics decide once per song: a line without Kazakh letters in a Kazakh song is still Kazakh.
        assertEquals(listOf("Ūly dala", "Erlerın"), r.parts(listOf("Ұлы дала"), cyrLang = "kk") + r.parts(listOf("Ерлерін"), cyrLang = "kk"))
        // Whatever no table covers goes through ICU, so no Cyrillic letter is left (Tatar җ).
        val out = r.machine("Җир, Ӂ, Ԥ, ҷ") + r.machine("Ізі җир")
        assertFalse(out.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.CYRILLIC }, out)
    }

    @Test fun latinUntouched() {
        assertEquals("Daft Punk", r.machine("Daft Punk"))
        assertEquals("Beyoncé", r.machine("Beyoncé"))
    }

    @Test fun overridesWin() {
        val o = Romanizer(jvmIcu)
        o.overrides = listOf(Override("artist", "UC1", "米津玄師", "Kenshi Yonezu"), Override("text", "", "君の声", "Kimi no Koe"))
        assertEquals("Kenshi Yonezu", o.text("米津玄師", artistId = "UC1").latin)
        assertEquals("Kimi no Koe", o.text("君の声").latin)
        assertEquals(o.machine("米津玄師"), o.text("米津玄師", artistId = "UC2").latin)
    }

    @Test fun partsMapOneToOne() {
        val words = listOf("夜", "に", "駆", "ける")
        val latin = r.parts(words)
        assertEquals(4, latin.size)
        assertEquals("Yoru ni kakeru", latin.joinToString("").trim())
        assertEquals(listOf("Ni", "hao"), Romanizer(jvmIcu, RomanizeSettings(hanDefault = "zh", chineseTones = false)).parts(listOf("你", "好")))
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

    @Test fun japaneseFromChineseSources() {
        // 一 typed for ー after katakana; a real 一 before a kanji stays; an unknown simplified kanji isn't left as is.
        assertEquals("Me o ubatte burū raito", r.machine("目を奪ってブル一ライト", sentence = true))
        assertEquals("Me o ubatte burū raito", r.machine("目を奪ってブルーライト", sentence = true))
        assertEquals("カード一枚", Romanizer.longVowelMarks("カード一枚"))
        assertFalse(Romanizer.hasNonLatin(r.machine("作词 : R-指定")))
        assertEquals("Terashitette moonlight", r.machine("照らしてってmoonlight", sentence = true))
        assertEquals("Burū raito", r.parts(listOf("ブル", "一", "ライト")).joinToString("").trim())
    }

    @Test fun japaneseReadings() {
        // Word splits IPADIC got wrong.
        assertEquals("Yofukashi no Uta", r.machine("よふかしのうた"))
        assertEquals("Futari", r.machine("二人"))
        assertEquals("Sebuntīn", r.machine("セブンティーン"))
        // A small vowel kana stretches the word before it; it never starts a word of its own.
        assertEquals(listOf("うっせぇ", "うっせぇ"), r.pieces("うっせぇうっせぇ"))
        assertEquals(listOf("クソ", "だりぃ", "な"), r.pieces("クソだりぃな"))
        // Long vowels from the spelling: ō ū from オウ オオ ウウ, but eiga, ōkii, and a verb's own final u.
        assertEquals("tōkyō", r.machine("東京", sentence = true).lowercase())
        assertEquals("eiga", r.machine("映画", sentence = true).lowercase())
        assertEquals("Ōkii", r.machine("大きい", sentence = true))
        assertEquals("Kūki", r.machine("空気", sentence = true))
        assertEquals("Omou", r.machine("思う", sentence = true))
        assertEquals("Aitai to omou", r.machine("会いたいと思う", sentence = true))
        assertEquals("Gakkō e ikō", r.machine("学校へ行こう", sentence = true))
        // Particles は へ を.
        assertEquals("Kyō wa hon o yomu", r.machine("今日は本を読む", sentence = true))
        assertEquals("Umi e", r.machine("海へ", sentence = true))
        // Readings come from the dictionary alone (no word list); 何 by the rule before t, d, n sounds.
        assertEquals("Mō ichido", r.machine("もう一度", sentence = true))
        assertEquals("Otōsan to okaasan", r.machine("お父さんとお母さん", sentence = true)) // aa stays: in 場合 (baai) it spans two morphemes
        assertEquals("Nani mo nai", r.machine("何もない", sentence = true))
        assertEquals("Nan de", r.machine("何で", sentence = true))
        // Glue: number + counter, verb + subsidiary verb, negative adjective.
        assertEquals("Sannin", r.machine("三人", sentence = true))
        assertEquals("Hashiridasu", r.machine("走り出す", sentence = true))
        assertEquals("Ureshikunai", r.machine("嬉しくない", sentence = true))
        assertEquals("Shinja dame", r.machine("死んじゃだめ", sentence = true))
        assertEquals("Iku deshō", r.machine("行くでしょう", sentence = true))
        assertEquals("Kirei na Hana", r.machine("綺麗な花"))
        // A reading in brackets (yomigana) is dropped; karaoke parts stay aligned.
        assertEquals(listOf("Kimi", " no", " uta"), r.parts(listOf("君(きみ)", "の", "歌")))
    }

    @Test fun nominalizerAndSuffixKanjiGlue() {
        assertEquals("Konnan ja kitto monotarinai", r.machine("こんなんじゃきっと物足りない", sentence = true))
        assertEquals("Kishikan nani ga sonna fuman nan da?", r.machine("既視感何がそんな不満なんだ?", sentence = true))
        assertEquals("Anshinkan", r.machine("安心感", sentence = true))
        assertEquals("Mono kin ai gen", r.machine("物 金 愛 言", sentence = true)) // separate words stay separate
    }

    @Test fun karaokeSplitsAWordLikeItsParts() {
        // 忙しい timed as 忙|し|い: the romaji follows ("isoga", "shi", "i"); a long vowel across parts stays whole.
        assertEquals(listOf("Yoru wa", " isoga", "shi", "i"), r.parts(listOf("夜は", "忙", "し", "い")))
        assertEquals(listOf("Ikō", "", ""), r.parts(listOf("行", "こ", "う")))
    }

    @Test fun kyrgyz() {
        assertEquals("ky", Romanizer.cyrillicLanguage("Мең сени сүйөм"))
        assertEquals("Meng seni süyöm", r.machine("Мең сени сүйөм", sentence = true))
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

    @Test fun piecesJoinBackIntoTheLine() {
        for (line in listOf("君に、会いたい", "食べますか？", "「夜に駆ける」 私は", "Группа крови на рукаве", "  あの日の夏")) {
            val p = r.pieces(line)
            assertEquals(line, p.joinToString(""), "$p")
        }
        assertEquals(listOf("君", "に、", "会いたい"), r.pieces("君に、会いたい"))
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

    @Test fun japaneseWithoutDictionary() {
        // It crashed the app: lyrics romanized at launch before the dictionary app was installed.
        val none = Romanizer(jvmIcu, dictionary = { error("no dictionary") })
        assertEquals("Konnichiha", none.machine("こんにちは")) // kana read as written
        none.machine("走って行く")
    }
}
