package roma.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.URLEncoder
import java.util.Base64
import java.text.Normalizer
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.Inflater
import kotlin.math.abs

@Serializable
class Word(val startMs: Long, val endMs: Long, val text: String)

/**
 * One lyric line. latin, wordLatin, pieces and pieceLatin are filled by [romanizeLyrics]: wordLatin has one entry per
 * karaoke word; pieces are the line's words as [Romanizer.pieces] splits them, with their romaji in pieceLatin.
 */
@Serializable
class Line(
    val startMs: Long,
    val text: String,
    val words: List<Word> = emptyList(),
    val translation: String? = null,
    val latin: String = "",
    val wordLatin: List<String> = emptyList(),
    val pieces: List<String> = emptyList(),
    val pieceLatin: List<String> = emptyList(),
)

/**
 * [translationSource] is set by [attachTranslation]. [lang] is the song's language tag (ISO 639), when the provider
 * gives one.
 */
@Serializable
class Lyrics(val source: String, val lines: List<Line>, val synced: Boolean, val translationSource: String? = null, val lang: String? = null) {
    val wordLevel get() = lines.any { it.words.isNotEmpty() }
}

/**
 * A translation layer, kept apart from the lyrics so it can come from another source than the lyrics (or later from
 * an AI translator) and be merged in by [attachTranslation]. Each line holds the original text, its start (0 when
 * unsynced) and the translation.
 */
@Serializable
class Translation(val source: String, val language: String, val lines: List<Line>, val synced: Boolean)

/** What is cached per song: the chosen lyrics without translations, every translation layer found, and the rule that chose. */
@Serializable
class LyricsResult(val lyrics: Lyrics, val translations: List<Translation> = emptyList(), val rule: Int = 0)

/** What providers search with. Always the original (not romanized) metadata, so it matches their catalogues. */
class LyricsQuery(val videoId: String, val title: String, val artist: String, val album: String?, val durationSec: Int)

interface LyricsProvider {
    val id: String
    val name: String

    /** Blocking. Null when the provider has nothing; throws when it couldn't answer (network, HTTP error). */
    fun fetch(q: LyricsQuery): Lyrics?
}

/**
 * [result] is null when no provider had lyrics. [complete] means every provider answered, so an empty result is
 * real.
 */
class LyricsSearch(val result: LyricsResult?, val complete: Boolean)

object LyricsProviders {
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "lyrics").apply { isDaemon = true } }

    /**
     * Asks every provider at once, each for at most [timeoutMs], then picks by [chooseLyrics], with [reference] (the
     * music source's own lyrics) as the text the others are matched against.
     */
    fun find(q: LyricsQuery, providers: List<LyricsProvider>, icu: Icu? = null, timeoutMs: Long = 5000, reference: LyricsProvider? = null): LyricsSearch {
        val futures = pool.invokeAll(providers.map { p -> Callable { p.fetch(q) } }, timeoutMs, TimeUnit.MILLISECONDS)
        val got = providers.zip(futures).map { (p, f) -> p to runCatching { f.get() } }
        val found = ArrayList<Pair<LyricsProvider, Lyrics>>()
        for ((p, r) in got) {
            val fetched = r.getOrNull() ?: continue
            val cleaned = withoutCredits(unescaped(fetched), q)
            if (cleaned.lines.any { l -> l.text.isNotBlank() }) found.add(p to cleaned)
        }
        val complete = got.all { it.second.isSuccess }
        val ref = found.firstOrNull { it.first == reference }?.second
        val others = found.filter { it.first != reference }.map { it.second }
        val chosen = chooseLyrics(ref, others, icu) ?: return LyricsSearch(null, complete)
        val (chosenLyrics, rule) = chosen
        val translations = found.mapNotNull { translationOf(it.second) }
        // A provider's own Latin lines (a server's romanization) stay. The reference's Latin lines go to the chosen
        // lines with the same text.
        val refLatinLines = ref?.lines.orEmpty().filter { it.latin.isNotEmpty() }
        val latin = refLatinLines.associate { normalizeLyrics(it.text) to it.latin }.filterKeys { it.isNotEmpty() }
        val bareLines = chosenLyrics.lines.map { line ->
            val lineLatin = line.latin.ifEmpty { latin[normalizeLyrics(line.text)] ?: "" }
            Line(line.startMs, line.text, line.words, latin = lineLatin, wordLatin = line.wordLatin)
        }
        val bare = Lyrics(chosenLyrics.source, bareLines, chosenLyrics.synced, lang = chosenLyrics.lang)
        return LyricsSearch(LyricsResult(bare, translations, rule), complete)
    }
}

private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#\\d+|amp|apos|quot|lt|gt|nbsp);")

/** "don&apos;t" as "don't": some providers send their lyrics HTML-escaped. */
fun unescapeHtml(s: String): String {
    if ('&' !in s) return s
    return ENTITY.replace(s) { match ->
        when (val e = match.groupValues[1]) {
            "amp" -> "&"
            "apos" -> "'"
            "quot" -> "\""
            "lt" -> "<"
            "gt" -> ">"
            "nbsp" -> " "
            else -> runCatching {
                val code = if (e[1] == 'x') e.drop(2).toInt(16) else e.drop(1).toInt()
                String(Character.toChars(code))
            }.getOrDefault(match.value)
        }
    }
}

private val BACKSLASHED = Regex("""\\(['"])""")

/** "it\'s" as "it's": some providers' lyrics keep a backslash from escaping quotes for a string literal. */
fun unescapeQuotes(s: String): String = if ('\\' !in s) s else BACKSLASHED.replace(s, "$1")

private fun unescapedText(s: String) = unescapeQuotes(unescapeHtml(s))

private fun unescaped(l: Lyrics): Lyrics {
    val lines = l.lines.map { line ->
        val text = unescapedText(line.text)
        val words = line.words.map { w -> Word(w.startMs, w.endMs, unescapedText(w.text)) }
        val translation = line.translation?.let(::unescapedText)
        Line(line.startMs, text, words, translation, line.latin, line.wordLatin)
    }
    return Lyrics(l.source, lines, l.synced, l.translationSource, l.lang)
}

// Credit lines are a role and then a colon, such as "作词 : X", "作曲：X" or "Lyrics by: X".
// Chinese providers put them at the top.
private val CREDIT = Regex("""^\s*(作词|作詞|作曲|编曲|編曲|词|詞|曲|制作人|制作|製作|监制|監修|混音|混缩|母带|和声|吉他|贝斯|鼓|弦乐|录音|出品|发行|企划|OP|SP|Lyrics|Lyricist|Music|Composer|Arranger|Arrangement|Producer|Produced by|Written by)\s*(by)?\s*[:：]""", RegexOption.IGNORE_CASE)

/**
 * Drops credit lines anywhere, and a leading "Artist - Title" line (KuGou starts with one): they aren't sung.
 * Word-timed lines lose their words with them, so karaoke stays aligned.
 */
fun withoutCredits(l: Lyrics, q: LyricsQuery): Lyrics {
    val lines = l.lines.filterIndexed { i, line ->
        val t = line.text.trim()
        if (CREDIT.containsMatchIn(t)) {
            false
        } else {
            val artistAndTitle = i < 3 && " - " in t && sameTitle(t.substringAfter(" - "), q.title)
            !artistAndTitle
        }
    }
    return if (lines.size == l.lines.size) l else Lyrics(l.source, lines, l.synced, l.translationSource, l.lang)
}

/** Lowercase, NFKC, letters and digits only: what [similarity] compares. */
fun normalizeLyrics(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase().filter { it.isLetterOrDigit() }

/**
 * Traditional Chinese as Simplified, when an ICU is given: YouTube Music often has Traditional where NetEase and
 * KuGou have Simplified, which is the same text for this purpose.
 */
private fun Icu?.fold(s: String): String {
    if (this == null) return s
    if (s.none { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) return s
    return transliterate("Traditional-Simplified", s)
}

/**
 * Dice coefficient over character bigrams (as a multiset) of the normalized text, 0..1. Bigrams rather than edit
 * distance: providers differ in whether repeated choruses are written out, and bigrams barely care about order.
 * Text in another language or script shares almost no bigrams.
 */
fun similarity(a: String, b: String, icu: Icu? = null): Double {
    fun grams(s: String): Map<String, Int> = normalizeLyrics(icu.fold(s)).windowed(2).groupingBy { it }.eachCount()
    val x = grams(a)
    val y = grams(b)
    val total = x.values.sum() + y.values.sum()
    if (total == 0) return 0.0
    val common = x.entries.sumOf { (g, n) -> minOf(n, y[g] ?: 0) }
    return 2.0 * common / total
}

/**
 * Above this the lyrics count as the same text as YouTube Music's. The same lyrics from two providers score 0.77–1.0
 * (with credit lines, a missing repeat, or Traditional against Simplified once folded). A version in another language
 * scores under 0.1, and a different song in the same language around 0.3.
 */
const val SAME_LYRICS = 0.6

private fun Lyrics.text() = lines.joinToString("\n") { it.text }

/**
 * The rules, the music source's own text being the reference (YouTube Music's in roma). Returns the lyrics and the rule
 * number (design.md, Lyrics): 1. word-level that matches the reference (any word-level when it has nothing), 2. the
 * reference line-timed, 3. third-party synced that matches it, 4. the reference plain, 5. (no reference) third-party
 * synced, then plain. [others] are in the user's order, which breaks ties in rule 5 and among equally similar
 * candidates.
 */
fun chooseLyrics(reference: Lyrics?, others: List<Lyrics>, icu: Icu? = null): Pair<Lyrics, Int>? {
    if (reference == null) {
        val wordLevel = others.firstOrNull { it.wordLevel }
        if (wordLevel != null) return wordLevel to 1
        val synced = others.firstOrNull { it.synced }
        if (synced != null) return synced to 5
        val first = others.firstOrNull() ?: return null
        return first to 5
    }
    val ref = reference.text()
    fun best(candidates: List<Lyrics>): Lyrics? {
        val scored = candidates.map { it to similarity(it.text(), ref, icu) }
        return scored.filter { it.second > SAME_LYRICS }.maxByOrNull { it.second }?.first
    }
    val bestWordLevel = best(others.filter { it.wordLevel })
    if (bestWordLevel != null) return bestWordLevel to 1
    if (reference.synced) return reference to 2
    val bestSynced = best(others.filter { it.synced })
    if (bestSynced != null) return bestSynced to 3
    return reference to 4
}

private fun translationOf(l: Lyrics): Translation? {
    val lines = l.lines.filter { !it.translation.isNullOrBlank() }
    val lang = translationLanguage(lines.map { it.translation!! }) ?: return null
    return Translation(l.source, lang, lines.map { Line(it.startMs, it.text, translation = it.translation) }, l.synced)
}

/**
 * The translation to show: one in the first of the [wanted] languages that has any, preferring the one from the same
 * source as the lyrics, then the first found.
 */
fun pickTranslation(l: Lyrics, translations: List<Translation>, wanted: List<String>): Translation? {
    for (lang in wanted) {
        val inLang = translations.filter { it.language == lang }
        if (inLang.isEmpty()) continue
        return inLang.firstOrNull { l.source == it.source } ?: inLang.first()
    }
    return null
}

/**
 * Puts [t] on the lyrics' lines: a line takes the translation of the line with the same normalized text, else, when
 * both are synced, of the line starting within 1 s. Lines with neither get none. Null [t] removes translations.
 */
fun attachTranslation(l: Lyrics, t: Translation?): Lyrics {
    val byText = t?.lines?.associate { normalizeLyrics(it.text) to it.translation } ?: emptyMap()
    val lines = l.lines.map { line ->
        var tr: String? = null
        if (t != null) {
            val key = normalizeLyrics(line.text)
            if (key.isNotEmpty()) tr = byText[key]
            if (tr == null && l.synced && t.synced) {
                val near = t.lines.filter { o -> abs(o.startMs - line.startMs) <= 1000 }
                tr = near.minByOrNull { o -> abs(o.startMs - line.startMs) }?.translation
            }
        }
        Line(line.startMs, line.text, line.words, tr, line.latin, line.wordLatin, line.pieces, line.pieceLatin)
    }
    return Lyrics(l.source, lines, l.synced, t?.source, l.lang)
}

/**
 * Lines in sentence case. The language is decided once per song: kana in any line makes every line Japanese, else
 * Chinese evidence in any line ([Romanizer.looksChinese]) makes every Han line Chinese, else a Chinese [Lyrics.lang]
 * (zh, zho, chi, cmn, yue) does, for Han text no clue settles. The Cyrillic language comes from the whole lyrics, so
 * a line without Kazakh letters in a Kazakh song is Kazakh. A line with a letter the song's language lacks uses its
 * own language (see Romanizer.cyrillicLanguageOfLine).
 */
fun Romanizer.romanizeLyrics(l: Lyrics, siblings: List<String>, songId: String): Lyrics {
    val texts = l.lines.map { it.text }
    val chineseTag = l.lang.orEmpty().lowercase().substringBefore('-') in listOf("zh", "zho", "chi", "cmn", "yue")
    // The line that settles the song's language joins the siblings: a kana line, else a Chinese one, else the hint.
    var languageLine: String? = texts.firstOrNull { it.any(Romanizer::isKana) }
    if (languageLine == null) languageLine = texts.firstOrNull(::looksChinese)
    if (languageLine == null && chineseTag) languageLine = CHINESE_HINT
    val sib = siblings + listOfNotNull(languageLine)
    val cyr = Romanizer.cyrillicLanguage(l.lines.joinToString("\n") { it.text })
    return Lyrics(
        l.source,
        l.lines.map { line ->
            val lang = Romanizer.cyrillicLanguageOfLine(line.text, cyr)
            // A correction of the whole line (or of the title, when a line repeats it) wins over per-word output. Karaoke
            // then highlights the corrected line as one piece: it sits on the first word, the other words get nothing.
            val wholeOverride = overrides.firstOrNull { o ->
                o.original == line.text.trim() && (o.scope == "song" && o.id == songId || o.scope == "text")
            }
            val whole = wholeOverride?.latin
            if (whole != null) {
                val wordLatin = line.words.indices.map { i -> if (i == 0) whole else "" }
                return@map Line(line.startMs, line.text, line.words, line.translation, whole, wordLatin, listOf(line.text), listOf(whole))
            }
            if (line.latin.isNotEmpty()) return@map served(line, songId)
            val pieces = pieces(line.text, sib)
            val pieceLatin = parts(pieces, sib, songId, lang)
            val wordLatin = if (line.words.isEmpty()) emptyList() else parts(line.words.map { it.text }, sib, songId, lang)
            val latinParts = if (line.words.isEmpty()) pieceLatin else wordLatin
            val latin = latinParts.joinToString("").trim()
            Line(line.startMs, line.text, line.words, line.translation, latin, wordLatin, pieces, pieceLatin)
        },
        l.synced,
        l.translationSource,
        l.lang,
    )
}

/**
 * A line the provider romanized (a server's pronunciation layer) keeps its Latin form, nothing romanized here: its
 * timed words, or the whole line, are its pieces, and the user's word readings replace the pieces they name.
 */
private fun Romanizer.served(line: Line, songId: String): Line {
    val karaoke = line.words.isNotEmpty() && line.wordLatin.size == line.words.size
    val pieces = if (karaoke) line.words.map { it.text } else listOf(line.text)
    val readings = wordReadings(songId)
    val servedLatin = if (karaoke) line.wordLatin else listOf(line.latin)
    val pieceLatin = servedLatin.mapIndexed { k, l ->
        val reading = readings[Romanizer.normalize(pieces[k]).trim()]
        if (reading == null) {
            l
        } else {
            // Keep the trailing space that separates this piece from the next.
            reading + if (l.endsWith(' ')) " " else ""
        }
    }
    val latin = if (readings.isEmpty()) line.latin else pieceLatin.joinToString("").trim()
    val wordLatin = if (karaoke) pieceLatin else line.wordLatin
    return Line(line.startMs, line.text, line.words, line.translation, latin, wordLatin, pieces, pieceLatin)
}

/**
 * The language of the translations, from their script, since no provider tags it: Han means Chinese.
 * Null when there are none.
 */
fun translationLanguage(translations: List<String>): String? {
    val t = translations.joinToString("")
    if (t.isBlank()) return null
    val scripts = t.codePoints().toArray().map { Character.UnicodeScript.of(it) }.toSet()
    return when {
        t.any(Romanizer::isKana) -> "ja"
        Character.UnicodeScript.HAN in scripts -> "zh"
        Character.UnicodeScript.HANGUL in scripts -> "ko"
        Character.UnicodeScript.CYRILLIC in scripts -> "ru"
        Character.UnicodeScript.GREEK in scripts -> "el"
        // ponytail: Latin script is taken for English, since no provider sends translations in other Latin-script
        // languages.
        else -> "en"
    }
}

private val TIME = Regex("""\[(\d+):(\d+(?:[.:]\d+)?)]""")
private val WORD_TIME = Regex("""<(\d+):(\d+(?:[.:]\d+)?)>""")

private fun ms(min: String, sec: String): Long {
    val s = sec.replace(':', '.')
    return min.toLong() * 60_000 + (s.toDouble() * 1000).toLong()
}

/** LRC, including the enhanced form with per-word <mm:ss.xx> stamps. */
fun parseLrc(text: String): List<Line> {
    val lines = ArrayList<Line>()
    for (raw in text.lines()) {
        var rest = raw.trim()
        val stamps = ArrayList<Long>()
        while (true) {
            val m = TIME.matchAt(rest, 0) ?: break
            stamps.add(ms(m.groupValues[1], m.groupValues[2]))
            rest = rest.substring(m.range.last + 1)
        }
        // A line without a time stamp is metadata, such as [ar:...], or plain text.
        if (stamps.isEmpty()) continue
        val words = ArrayList<Word>()
        val marks = WORD_TIME.findAll(rest).toList()
        if (marks.isNotEmpty()) {
            // Text before the first word stamp starts with the line.
            val lead = rest.substring(0, marks[0].range.first)
            if (lead.isNotBlank()) words.add(Word(stamps[0], -1, lead))
            for ((i, m) in marks.withIndex()) {
                val end = marks.getOrNull(i + 1)?.range?.first ?: rest.length
                val w = rest.substring(m.range.last + 1, end)
                val start = ms(m.groupValues[1], m.groupValues[2])
                if (words.isNotEmpty()) {
                    // The previous word ends where this one starts.
                    val previous = words.last()
                    words[words.size - 1] = Word(previous.startMs, start, previous.text)
                }
                if (w.isNotEmpty()) words.add(Word(start, -1, w))
            }
        }
        val plain = if (marks.isEmpty()) rest else words.joinToString("") { it.text }
        for (s in stamps) lines.add(Line(s, plain.trim(), words))
    }
    lines.sortBy { it.startMs }
    return closeWords(lines)
}

/**
 * A word without an end ends where the next line starts or, on the last line, a second after the line's last word
 * starts.
 */
internal fun closeWords(lines: List<Line>): List<Line> = lines.mapIndexed { i, l ->
    if (l.words.none { it.endMs < 0 }) {
        l
    } else {
        val nextStart = lines.getOrNull(i + 1)?.startMs ?: (l.words.last().startMs + 1000)
        val words = l.words.map { w -> if (w.endMs < 0) Word(w.startMs, nextStart, w.text) else w }
        Line(l.startMs, l.text, words, l.translation)
    }
}

/** Throws on an HTTP error, so a provider that couldn't answer isn't taken for one that has no lyrics. */
fun lyricsGet(url: String, vararg headers: Pair<String, String>, body: String? = null): String =
    httpText(url, body?.toByteArray(), mapOf("User-Agent" to "Mozilla/5.0 roma") + headers, timeoutMs = 12_000)

fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
fun JsonElement?.str(): String? {
    val p = this as? JsonPrimitive ?: return null
    // JSON null is a primitive whose content is "null"; a string "null" is kept.
    if (!p.isString && p.content == "null") return null
    return p.content
}
fun JsonElement?.obj(): JsonObject? = this as? JsonObject
fun JsonElement?.arr(): JsonArray? = this as? JsonArray

private fun foldTitle(s: String): String {
    val plain = Romanizer.stripMarks(s).lowercase()
    val withoutBrackets = plain.replace(Regex("""[(\[（【].*?[)\]）】]"""), "")
    return withoutBrackets.replace(Regex("""[\s\p{P}]+"""), "")
}

/** Same song title, ignoring case, spaces, punctuation and bracketed extras such as "(TV Size)". */
fun sameTitle(a: String, b: String): Boolean {
    val x = foldTitle(a)
    val y = foldTitle(b)
    return x.isNotEmpty() && y.isNotEmpty() && (x.contains(y) || y.contains(x))
}

/** Picks the candidate whose duration is closest, within 5 s when the song's duration is known. */
fun <T> closest(items: List<T>, durationSec: Int, durationOf: (T) -> Double): T? {
    if (durationSec <= 0) return items.firstOrNull()
    val near = items.filter { abs(durationOf(it) - durationSec) <= 5 }
    return near.minByOrNull { abs(durationOf(it) - durationSec) }
}

/** LRCLIB: free, no key. Line-synced LRC, sometimes enhanced with word stamps. */
object LrcLib : LyricsProvider {
    override val id = "lrclib"
    override val name = "LRCLIB"

    override fun fetch(q: LyricsQuery): Lyrics? {
        val body = lyricsGet("https://lrclib.net/api/search?track_name=${enc(q.title)}&artist_name=${enc(q.artist)}")
        val allItems = Json.parseToJsonElement(body).arr()?.mapNotNull { it.obj() } ?: return null
        val items = allItems.filter { sameTitle(it["trackName"].str() ?: "", q.title) }
        val withSynced = items.filter { !it["syncedLyrics"].str().isNullOrBlank() }
        val pick = closest(withSynced, q.durationSec) { it["duration"].str()?.toDoubleOrNull() ?: 0.0 }
        if (pick != null) return Lyrics(name, parseLrc(pick["syncedLyrics"].str()!!), true)
        val withPlain = items.filter { !it["plainLyrics"].str().isNullOrBlank() }
        val plain = closest(withPlain, q.durationSec) { it["duration"].str()?.toDoubleOrNull() ?: 0.0 } ?: return null
        return Lyrics(name, plain["plainLyrics"].str()!!.lines().map { Line(0, it) }, false)
    }
}

/**
 * Google Translate through the free endpoint browser extensions use (translate_a/single): no key, unofficial, so it
 * may change or stop. Only on the user's tap, per song, since it sends the lyrics to Google. Each distinct line is
 * sent once, 40 to a request (Google takes 5,000 characters), each on its own line between private-use \uE000 marks,
 * which Google keeps, so each translation lands on its own line.
 */
object GoogleTranslate {
    const val SOURCE = "Google Translate"

    /** The lyrics' layer in [lang]. Null when Google finds them already in that language; throws when it couldn't answer. */
    fun layer(l: Lyrics, lang: String): Translation? {
        val texts = l.lines.map { it.text.trim() }.filter { it.isNotEmpty() }.distinct()
        if (texts.isEmpty()) return null
        val answers = texts.chunked(40).map { translate(it, lang) }
        if (answers[0].second.substringBefore('-').equals(lang, ignoreCase = true)) return null
        val byText = texts.zip(answers.flatMap { it.first }).toMap()
        val lines = l.lines.filter { it.text.isNotBlank() }.map { line ->
            val original = line.text.trim()
            val translated = byText[original]
            // A line Google leaves as it was (a name, an English line in a J-pop song) gets no translation.
            val changed = translated != null && translated.isNotBlank() && translated != original
            Line(line.startMs, line.text, translation = if (changed) translated else null)
        }
        return Translation(SOURCE, lang, lines, l.synced)
    }

    /**
     * One text, such as a comment, in [lang]. Null when Google finds it already in that language or leaves it as it
     * was; throws when it couldn't answer. ponytail: the text is cut at 4,500 characters, under Google's limit of 5,000.
     */
    fun text(s: String, lang: String): String? {
        val (t, from) = translate(listOf(s.take(4500)), lang)
        val translated = t[0]
        val sameLanguage = from.substringBefore('-').equals(lang, ignoreCase = true)
        if (sameLanguage || translated.isBlank() || translated == s.trim()) return null
        return translated
    }

    /** One batch: its translations in order, and the language Google detected. */
    private fun translate(texts: List<String>, lang: String): Pair<List<String>, String> {
        val form = "client=gtx&sl=auto&tl=${enc(lang)}&dt=t&q=${enc(texts.joinToString("\n\uE000\n"))}"
        val response = lyricsGet("https://translate.googleapis.com/translate_a/single", body = form)
        val root = Json.parseToJsonElement(response).jsonArray
        val text = root[0].arr().orEmpty().joinToString("") { segment -> segment.arr()?.getOrNull(0).str().orEmpty() }
        val parts = text.split('\uE000').map { it.trim() }
        if (parts.size != texts.size) throw IOException("Google Translate: ${parts.size} lines back for ${texts.size}")
        return parts to (root.getOrNull(2).str() ?: "")
    }
}

/** A simplified character outside JIS X 0208: Chinese evidence as a sibling. */
private const val CHINESE_HINT = "们"
