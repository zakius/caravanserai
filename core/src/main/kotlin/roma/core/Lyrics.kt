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

/** translationSource is set by [attachTranslation]. */
@Serializable
class Lyrics(val source: String, val lines: List<Line>, val synced: Boolean, val translationSource: String? = null) {
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

/** [result] is null when no provider had lyrics. [complete]: every provider answered, so an empty result is real. */
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
        val found = got.mapNotNull { (p, r) -> r.getOrNull()?.let { withoutCredits(unescaped(it), q) }?.takeIf { it.lines.any { l -> l.text.isNotBlank() } }?.let { p to it } }
        val complete = got.all { it.second.isSuccess }
        val ref = found.firstOrNull { it.first == reference }?.second
        val chosen = chooseLyrics(ref, found.filter { it.first != reference }.map { it.second }, icu) ?: return LyricsSearch(null, complete)
        val translations = found.mapNotNull { translationOf(it.second) }
        // A provider's own Latin lines (a server's romanization) stay; the reference's go to the chosen lines with the same text.
        val latin = ref?.lines.orEmpty().filter { it.latin.isNotEmpty() }.associate { normalizeLyrics(it.text) to it.latin }.filterKeys { it.isNotEmpty() }
        val bare = Lyrics(chosen.first.source, chosen.first.lines.map { Line(it.startMs, it.text, it.words, latin = it.latin.ifEmpty { latin[normalizeLyrics(it.text)] ?: "" }, wordLatin = it.wordLatin) }, chosen.first.synced)
        return LyricsSearch(LyricsResult(bare, translations, chosen.second), complete)
    }
}

// ---- Entities ----

private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#\\d+|amp|apos|quot|lt|gt|nbsp);")

/** "don&apos;t" as "don't": some providers send their lyrics HTML-escaped. */
fun unescapeHtml(s: String): String = if ('&' !in s) s else ENTITY.replace(s) {
    when (val e = it.groupValues[1]) {
        "amp" -> "&"; "apos" -> "'"; "quot" -> "\""; "lt" -> "<"; "gt" -> ">"; "nbsp" -> " "
        else -> runCatching { String(Character.toChars(if (e[1] == 'x') e.drop(2).toInt(16) else e.drop(1).toInt())) }.getOrDefault(it.value)
    }
}

private val BACKSLASHED = Regex("""\\(['"])""")

/** "it\'s" as "it's": some providers' lyrics keep a backslash from escaping quotes for a string literal. */
fun unescapeQuotes(s: String): String = if ('\\' !in s) s else BACKSLASHED.replace(s, "$1")

private fun unescapedText(s: String) = unescapeQuotes(unescapeHtml(s))

private fun unescaped(l: Lyrics) = Lyrics(
    l.source,
    l.lines.map { Line(it.startMs, unescapedText(it.text), it.words.map { w -> Word(w.startMs, w.endMs, unescapedText(w.text)) }, it.translation?.let(::unescapedText), it.latin, it.wordLatin) },
    l.synced,
    l.translationSource,
)

// ---- Credits ----

// "作词 : X", "作曲：X", "编曲 X", "Lyrics by X": a role, then a colon. Chinese providers put these at the top.
private val CREDIT = Regex("""^\s*(作词|作詞|作曲|编曲|編曲|词|詞|曲|制作人|制作|製作|监制|監修|混音|混缩|母带|和声|吉他|贝斯|鼓|弦乐|录音|出品|发行|企划|OP|SP|Lyrics|Lyricist|Music|Composer|Arranger|Arrangement|Producer|Produced by|Written by)\s*(by)?\s*[:：]""", RegexOption.IGNORE_CASE)

/**
 * Drops credit lines anywhere, and a leading "Artist - Title" line (KuGou starts with one): they aren't sung.
 * Word-timed lines lose their words with them, so karaoke stays aligned.
 */
fun withoutCredits(l: Lyrics, q: LyricsQuery): Lyrics {
    val lines = l.lines.filterIndexed { i, line ->
        val t = line.text.trim()
        !CREDIT.containsMatchIn(t) && !(i < 3 && " - " in t && sameTitle(t.substringAfter(" - "), q.title))
    }
    return if (lines.size == l.lines.size) l else Lyrics(l.source, lines, l.synced, l.translationSource)
}

// ---- Choosing ----

/** Lowercase, NFKC, letters and digits only: what [similarity] compares. */
fun normalizeLyrics(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase().filter { it.isLetterOrDigit() }

/**
 * Traditional Chinese as Simplified, when an ICU is given: YouTube Music often has Traditional where NetEase and
 * KuGou have Simplified, which is the same text for this purpose.
 */
private fun Icu?.fold(s: String) = if (this == null || s.none { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) s else transliterate("Traditional-Simplified", s)

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
 * Above this the lyrics count as the same text as YouTube Music's. Measured: the same lyrics from two providers score
 * 0.77–1.0 (credit lines, a missing repeat, Traditional vs Simplified once folded); an other-language version under
 * 0.1; a different song in the same language around 0.3.
 */
const val SAME_LYRICS = 0.6

private fun Lyrics.text() = lines.joinToString("\n") { it.text }

/**
 * The rules, the music source's own text being the reference (YouTube Music's in roma). Returns the lyrics and the rule
 * number (design.md, Lyrics): 1. word-level that matches the reference (any word-level when it has nothing), 2. the
 * reference line-timed, 3. third-party synced that matches it, 4. the reference plain, 5. (no reference) third-party
 * synced, then plain. [others] are in the user's order,
 * which breaks ties in rule 5 and among equally similar candidates.
 */
fun chooseLyrics(reference: Lyrics?, others: List<Lyrics>, icu: Icu? = null): Pair<Lyrics, Int>? {
    if (reference == null) {
        others.firstOrNull { it.wordLevel }?.let { return it to 1 }
        others.firstOrNull { it.synced }?.let { return it to 5 }
        return others.firstOrNull()?.let { it to 5 }
    }
    val ref = reference.text()
    fun best(c: List<Lyrics>) = c.map { it to similarity(it.text(), ref, icu) }.filter { it.second > SAME_LYRICS }.maxByOrNull { it.second }?.first
    best(others.filter { it.wordLevel })?.let { return it to 1 }
    if (reference.synced) return reference to 2
    best(others.filter { it.synced })?.let { return it to 3 }
    return reference to 4
}

// ---- Translations ----

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
    val ok = wanted.firstNotNullOfOrNull { lang -> translations.filter { it.language == lang }.ifEmpty { null } } ?: return null
    return ok.firstOrNull { l.source == it.source } ?: ok.first()
}

/**
 * Puts [t] on the lyrics' lines: a line takes the translation of the line with the same normalized text, else, when
 * both are synced, of the line starting within 1 s. Lines with neither get none. Null [t] removes translations.
 */
fun attachTranslation(l: Lyrics, t: Translation?): Lyrics {
    val byText = t?.lines?.associate { normalizeLyrics(it.text) to it.translation } ?: emptyMap()
    return Lyrics(
        l.source,
        l.lines.map { line ->
            val tr = t?.let {
                byText[normalizeLyrics(line.text)].takeIf { normalizeLyrics(line.text).isNotEmpty() }
                    ?: if (l.synced && it.synced) it.lines.filter { o -> abs(o.startMs - line.startMs) <= 1000 }.minByOrNull { o -> abs(o.startMs - line.startMs) }?.translation else null
            }
            Line(line.startMs, line.text, line.words, tr, line.latin, line.wordLatin, line.pieces, line.pieceLatin)
        },
        l.synced,
        t?.source,
    )
}

/**
 * Lines in sentence case. The language is decided once per song: kana in any line makes every line Japanese, else
 * Chinese evidence in any line ([Romanizer.looksChinese]) makes every Han line Chinese, and the Cyrillic language comes from the whole lyrics, so a line without Kazakh letters in a Kazakh song is Kazakh;
 * a line with a letter the song's language lacks uses its own (see Romanizer.cyrillicLanguageOfLine).
 */
fun Romanizer.romanizeLyrics(l: Lyrics, siblings: List<String>, songId: String): Lyrics {
    val texts = l.lines.map { it.text }
    val sib = siblings + listOfNotNull(texts.firstOrNull { it.any(Romanizer::isKana) } ?: texts.firstOrNull(::looksChinese))
    val cyr = Romanizer.cyrillicLanguage(l.lines.joinToString("\n") { it.text })
    return Lyrics(
        l.source,
        l.lines.map { line ->
            val lang = Romanizer.cyrillicLanguageOfLine(line.text, cyr)
            // A correction of the whole line (or of the title, when a line repeats it) wins over per-word output. Karaoke
            // then highlights the corrected line as one piece: it sits on the first word, the other words get nothing.
            val whole = overrides.firstOrNull { it.original == line.text.trim() && (it.scope == "song" && it.id == songId || it.scope == "text") }?.latin
            if (whole != null) return@map Line(line.startMs, line.text, line.words, line.translation, whole, line.words.indices.map { if (it == 0) whole else "" }, listOf(line.text), listOf(whole))
            if (line.latin.isNotEmpty()) return@map served(line, songId)
            val pieces = pieces(line.text, sib)
            val pieceLatin = parts(pieces, sib, songId, lang)
            val wordLatin = if (line.words.isEmpty()) emptyList() else parts(line.words.map { it.text }, sib, songId, lang)
            val latin = (if (line.words.isEmpty()) pieceLatin else wordLatin).joinToString("").trim()
            Line(line.startMs, line.text, line.words, line.translation, latin, wordLatin, pieces, pieceLatin)
        },
        l.synced,
        l.translationSource,
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
    val pieceLatin = (if (karaoke) line.wordLatin else listOf(line.latin)).mapIndexed { k, l ->
        readings[Romanizer.normalize(pieces[k]).trim()]?.let { it + if (l.endsWith(' ')) " " else "" } ?: l
    }
    val latin = if (readings.isEmpty()) line.latin else pieceLatin.joinToString("").trim()
    return Line(line.startMs, line.text, line.words, line.translation, latin, if (karaoke) pieceLatin else line.wordLatin, pieces, pieceLatin)
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
        else -> "en" // ponytail: Latin script is taken for English; no provider sends other Latin-script translations yet
    }
}

// ---- Parsers ----

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
        if (stamps.isEmpty()) continue // metadata such as [ar:...] or plain text
        val words = ArrayList<Word>()
        val marks = WORD_TIME.findAll(rest).toList()
        if (marks.isNotEmpty()) {
            // Text before the first word stamp starts with the line.
            rest.substring(0, marks[0].range.first).takeIf { it.isNotBlank() }?.let { words.add(Word(stamps[0], -1, it)) }
            for ((i, m) in marks.withIndex()) {
                val end = marks.getOrNull(i + 1)?.range?.first ?: rest.length
                val w = rest.substring(m.range.last + 1, end)
                val start = ms(m.groupValues[1], m.groupValues[2])
                if (words.isNotEmpty()) words[words.size - 1] = Word(words.last().startMs, start, words.last().text)
                if (w.isNotEmpty()) words.add(Word(start, -1, w))
            }
        }
        val plain = if (marks.isEmpty()) rest else words.joinToString("") { it.text }
        for (s in stamps) lines.add(Line(s, plain.trim(), words))
    }
    lines.sortBy { it.startMs }
    return closeWords(lines)
}

// A word without an end ends where the next line starts.
internal fun closeWords(lines: List<Line>): List<Line> = lines.mapIndexed { i, l ->
    if (l.words.none { it.endMs < 0 }) l
    else {
        val nextStart = lines.getOrNull(i + 1)?.startMs ?: (l.words.last().startMs + 1000)
        Line(l.startMs, l.text, l.words.map { if (it.endMs < 0) Word(it.startMs, nextStart, it.text) else it }, l.translation)
    }
}


// ---- Providers ----

/** Throws on an HTTP error, so a provider that couldn't answer isn't taken for one that has no lyrics. */
fun lyricsGet(url: String, vararg headers: Pair<String, String>, body: String? = null): String =
    httpText(url, body?.toByteArray(), mapOf("User-Agent" to "Mozilla/5.0 roma") + headers, timeoutMs = 12_000)

fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content
fun JsonElement?.obj(): JsonObject? = this as? JsonObject
fun JsonElement?.arr(): JsonArray? = this as? JsonArray

private fun foldTitle(s: String) = Romanizer.stripMarks(s).lowercase().replace(Regex("""[(\[（【].*?[)\]）】]"""), "").replace(Regex("""[\s\p{P}]+"""), "")

/** Same song title, ignoring case, spaces, punctuation and bracketed extras such as "(TV Size)". */
fun sameTitle(a: String, b: String): Boolean {
    val x = foldTitle(a)
    val y = foldTitle(b)
    return x.isNotEmpty() && y.isNotEmpty() && (x.contains(y) || y.contains(x))
}

/** Picks the candidate whose duration is closest, within 5 s when the song's duration is known. */
fun <T> closest(items: List<T>, durationSec: Int, durationOf: (T) -> Double): T? {
    if (durationSec <= 0) return items.firstOrNull()
    return items.filter { abs(durationOf(it) - durationSec) <= 5 }.minByOrNull { abs(durationOf(it) - durationSec) }
}

/** LRCLIB: free, no key. Line-synced LRC, sometimes enhanced with word stamps. */
object LrcLib : LyricsProvider {
    override val id = "lrclib"
    override val name = "LRCLIB"

    override fun fetch(q: LyricsQuery): Lyrics? {
        val body = lyricsGet("https://lrclib.net/api/search?track_name=${enc(q.title)}&artist_name=${enc(q.artist)}")
        val items0 = Json.parseToJsonElement(body).arr()?.mapNotNull { it.obj() } ?: return null
        val items = items0.filter { sameTitle(it["trackName"].str() ?: "", q.title) }
        val withSynced = items.filter { !it["syncedLyrics"].str().isNullOrBlank() }
        val pick = closest(withSynced, q.durationSec) { it["duration"].str()?.toDoubleOrNull() ?: 0.0 }
        if (pick != null) return Lyrics(name, parseLrc(pick["syncedLyrics"].str()!!), true)
        val plain = closest(items.filter { !it["plainLyrics"].str().isNullOrBlank() }, q.durationSec) { it["duration"].str()?.toDoubleOrNull() ?: 0.0 }
            ?: return null
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
            // A line Google leaves as it was (a name, an English line in a J-pop song) gets no translation.
            Line(line.startMs, line.text, translation = byText[line.text.trim()]?.takeIf { it.isNotBlank() && it != line.text.trim() })
        }
        return Translation(SOURCE, lang, lines, l.synced)
    }

    /**
     * One text, such as a comment, in [lang]. Null when Google finds it already in that language or leaves it as it
     * was; throws when it couldn't answer. ponytail: cut at 4,500 characters, under Google's 5,000.
     */
    fun text(s: String, lang: String): String? {
        val (t, from) = translate(listOf(s.take(4500)), lang)
        return t[0].takeUnless { from.substringBefore('-').equals(lang, ignoreCase = true) || it.isBlank() || it == s.trim() }
    }

    /** One batch: its translations in order, and the language Google detected. */
    private fun translate(texts: List<String>, lang: String): Pair<List<String>, String> {
        val form = "client=gtx&sl=auto&tl=${enc(lang)}&dt=t&q=${enc(texts.joinToString("\n\uE000\n"))}"
        val root = Json.parseToJsonElement(lyricsGet("https://translate.googleapis.com/translate_a/single", body = form)).jsonArray
        val text = root[0].arr().orEmpty().joinToString("") { it.arr()?.getOrNull(0).str().orEmpty() }
        val parts = text.split('\uE000').map { it.trim() }
        if (parts.size != texts.size) throw IOException("Google Translate: ${parts.size} lines back for ${texts.size}")
        return parts to (root.getOrNull(2).str() ?: "")
    }
}
