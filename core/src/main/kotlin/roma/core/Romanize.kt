package roma.core

import com.worksap.nlp.sudachi.Config
import com.worksap.nlp.sudachi.MMap
import com.worksap.nlp.sudachi.Tokenizer
import com.worksap.nlp.sudachi.dictionary.RomaDictionary
import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.text.Normalizer

/**
 * A string as it came from the service, next to its Latin form. It counts as Latin, with nothing to romanize, when it has
 * no letters of another script: a difference made only by normalizing width or punctuation (（Yeah） → (Yeah)) is not a
 * romanization. A kept original (latin == original) counts as Latin too.
 */
@Serializable
class Text(
    val original: String,
    val latin: String,
    /** The original split into words as lyrics split a line, with each word's part of [latin]; empty when unknown. */
    val pieces: List<String> = emptyList(),
    val pieceLatin: List<String> = emptyList(),
    /** The Latin form the source sent (a Subsonic server's romanization), the original where it sent none; null when romanized here. */
    val served: String? = null,
) {
    val isLatin get() = original == latin || !Romanizer.hasNonLatin(original)

    companion object {
        /** Several names as one ("A, B"), keeping their words. */
        fun join(texts: List<Text>, separator: String): Text {
            // Each name's words and their Latin parts. A name without them is null, and then the whole has none.
            val split = texts.map { t ->
                if (t.pieces.isNotEmpty()) {
                    t.pieces to t.pieceLatin
                } else if (t.isLatin) {
                    listOf(t.original) to listOf(t.latin)
                } else {
                    null
                }
            }
            // The separator goes on the last word of every name but the final one.
            fun List<List<String>>.joined() = flatMapIndexed { i, p -> if (i == size - 1) p else p.dropLast(1) + (p.last() + separator) }
            val original = texts.joinToString(separator) { it.original }
            val latin = texts.joinToString(separator) { it.latin }
            if (split.any { it == null }) return Text(original, latin, emptyList(), emptyList())
            val pieces = split.map { it!!.first }.joined()
            val pieceLatin = split.map { it!!.second }.joined()
            return Text(original, latin, pieces, pieceLatin)
        }
    }
}

/** Kept [Romanizer.machine] results by key; called from any thread. */
interface RomanizeCache {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

/** ICU transliteration, supplied by the platform: android.icu on Android, ICU4J on a desktop JVM. */
fun interface Icu {
    fun transliterate(id: String, text: String): String
}

class RomanizeSettings(
    val japaneseMacrons: Boolean = true,
    val chineseTones: Boolean = true,
    /** "english", "polish" or "iso9". */
    val cyrillic: String = "english",
    /** "classical" (β = b, no accents) or "elot" (ELOT 743, modern: β = v). */
    val greek: String = "classical",
    /** "ja" or "zh": what Han text is when neither it nor its sibling fields hold kana. */
    val hanDefault: String = "ja",
)

/**
 * A user correction. Scope "text" is a whole string everywhere, "song" and "artist" a whole string of that song or artist
 * (id). Scope "word" is a word's reading inside any string, a title or a lyric line: everywhere when id is "", else in
 * that song only. A word matches whole dictionary tokens (or a run of them), never part of one.
 */
@Serializable
class Override(val scope: String, val id: String, val original: String, val latin: String)

/**
 * [dictionary] supplies SudachiDict's system_core.dic, memory-mapped, little-endian. It is called on the first
 * Japanese string (or [warmUp]). By default it maps the file named by the system property roma.sudachi (the JVM tests).
 * If it fails (not installed), Japanese gets kana-only romanization, kanji stay as they are, and the next try comes a
 * minute later or after [retryDictionary].
 */
class Romanizer(
    private val icu: Icu,
    var settings: RomanizeSettings = RomanizeSettings(),
    private val dictionary: () -> ByteBuffer = { MMap.map(System.getProperty("roma.sudachi") ?: error("roma.sudachi: path to system_core.dic")) },
) {
    /**
     * Where [machine] keeps its results, if the app gives one (roma's lasts across starts). A string seen before then
     * costs a lookup, not the dictionary or an ICU table loaded for it. The app empties it whenever settings, overrides
     * or the dictionary's presence change the results.
     */
    @Volatile var cache: RomanizeCache? = null

    @Volatile private var loading: java.util.concurrent.Future<List<Override>>? = null
    /** The user's corrections. Reading waits for [loadOverrides]' file read if it hasn't finished. */
    var overrides: List<Override> = emptyList()
        get() {
            val pending = loading
            if (pending != null) {
                field = pending.get()
                loading = null
            }
            return field
        }
        set(value) {
            loading = null
            field = value
        }

    /** Takes the corrections from a read running elsewhere, so start-up doesn't wait for the file. */
    fun loadOverrides(read: java.util.concurrent.Future<List<Override>>) { loading = read }

    /**
     * Romanizes s, honouring overrides. Siblings are the other fields of the same item (artist, album, title).
     * A song or album title passes [titleOf] = true, so "スピカ - Spica" shows its own Latin form. A [served] Latin form
     * (the source's own romanization) is taken as it is, after the user's corrections: nothing is romanized here.
     */
    fun text(s: String, siblings: List<String> = emptyList(), songId: String? = null, artistId: String? = null, titleOf: Boolean = false, served: String? = null): Text {
        val o = overrides.firstOrNull { it.original == s && it.scope == "song" && it.id == songId }
            ?: overrides.firstOrNull { it.original == s && it.scope == "artist" && it.id == artistId }
            ?: overrides.firstOrNull { it.original == s && it.scope == "text" }
        if (served != null) return Text(s, o?.latin ?: served, served = served)
        var latin = o?.latin
        if (latin == null && titleOf) latin = ownLatin(s)
        if (latin == null) latin = machine(s, siblings, songId = songId)
        return pieced(s, latin, siblings, songId)
    }

    /**
     * [latin] cut into the words of [s] for the furigana layout. The cuts come from romanizing word by word, but the
     * text is [latin]'s, so title case and corrections stay. No pieces when the two spell different things (a
     * corrected or official Latin title): that name shows one reading above the whole of it.
     */
    fun pieced(s: String, latin: String, siblings: List<String> = emptyList(), songId: String? = null): Text {
        if (!hasNonLatin(s)) return Text(s, latin)
        // ponytail: two more dictionary passes per non-Latin name, ruby mode or not; skip them per mode if it shows in profiles
        val pieces = pieces(s, siblings)
        val parts = parts(pieces, siblings, songId)
        fun letters(t: String) = t.filter { it.isLetterOrDigit() }.lowercase()
        if (letters(parts.joinToString("")) != letters(latin)) return Text(s, latin)
        var at = 0
        val cut = parts.map { p ->
            var n = p.count { it.isLetterOrDigit() }
            val start = at
            // A piece takes its letters and whatever follows them up to the next piece's first letter.
            while (at < latin.length && (n > 0 || !latin[at].isLetterOrDigit())) {
                if (latin[at].isLetterOrDigit()) n--
                at++
            }
            latin.substring(start, at)
        }
        return Text(s, latin, pieces, cut)
    }

    /** Title case for names and titles; [sentence] case (only the first letter) for lyric lines. */
    fun machine(s: String, siblings: List<String> = emptyList(), sentence: Boolean = false, songId: String? = null): String {
        val n = normalize(s)
        if (!hasNonLatin(n)) return n
        val words = wordReadings(songId)
        // The siblings only decide whether it's Japanese; a song's own word readings are left out of the cache.
        val japanese = isJapanese(n, siblings)
        // The key starts with the case (s for sentence, t for title) and the language (j for Japanese, o for other).
        val caseTag = if (sentence) 's' else 't'
        val languageTag = if (japanese) 'j' else 'o'
        val key = if (words.isEmpty()) "$caseTag$languageTag$n" else null
        if (key != null) {
            val cached = cache?.get(key)
            if (cached != null) return cached
        }
        val out = capFirst(n, raw(n, siblings, !sentence, words = words))
        // Japanese romanized without the dictionary (kana only) is redone once it loads, so it isn't kept.
        if (key != null && (!japanese || loaded != null)) cache?.put(key, out)
        return out
    }

    private fun raw(n: String, siblings: List<String>, title: Boolean, cyrLang: String = cyrillicLanguage(n), words: Map<String, String> = emptyMap()): String {
        val latin = if (isJapanese(n, siblings)) {
            japanese(n, title, words).joinToString("") { it.second }
        } else {
            other(n, title, cyrLang, words)
        }
        return punctuation(latin)
    }

    /** The user's word readings that apply in this song: its own ones win over the everywhere ones. */
    fun wordReadings(songId: String?): Map<String, String> {
        val all = overrides.filter { it.scope == "word" && (it.id == "" || it.id == songId) }
        if (all.isEmpty()) return emptyMap()
        return all.sortedBy { it.id != "" }.associate { normalize(it.original).trim() to it.latin }
    }

    /**
     * Romanizes a line split into parts (karaoke words), one result per part, so word timings map 1:1.
     * Japanese is read as a whole line, so each reading stays right in context; a token that spans several parts
     * goes to the part where it starts. Lyrics are in sentence case: only the line's first letter is capitalized.
     * [cyrLang] is the Cyrillic language decided for the whole song; by default it is read from this line.
     */
    fun parts(parts: List<String>, siblings: List<String> = emptyList(), songId: String? = null, cyrLang: String? = null): List<String> {
        // Karaoke splits ブル|一|ライト into separate parts, so the long-vowel fix runs again across the joined line
        // (it replaces one character with one, so the parts keep their lengths).
        val normalized = parts.map(::normalize)
        val joined = longVowelMarks(normalized.joinToString(""))
        val bounds = normalized.runningFold(0) { at, p -> at + p.length }
        val norm = bounds.zipWithNext { a, b -> joined.substring(a, b) }
        val line = joined
        val override = overrides.filter { it.scope == "song" && it.id == songId }
        val readings = wordReadings(songId)
        val out: MutableList<String> = if (!isJapanese(line, siblings)) {
            val lang = cyrLang ?: cyrillicLanguage(line)
            val latin = norm.map { if (hasNonLatin(it)) raw(it, siblings, false, lang, readings) else it }.toMutableList()
            // Chinese words are one or two syllables apart ("gù shì de"); Hangul syllables join into their word.
            for (k in 1 until latin.size) {
                if (latin[k].firstOrNull()?.isLetterOrDigit() != true) continue
                if (norm[k].isEmpty()) continue
                val firstChar = norm[k].substring(0, Character.charCount(norm[k].codePointAt(0)))
                if (!hasHan(firstChar)) continue
                val before = latin.subList(0, k).joinToString("")
                if (before.lastOrNull()?.isLetterOrDigit() == true) latin[k] = " " + latin[k]
            }
            latin
        } else {
            val words = Array(norm.size) { StringBuilder() }
            val starts = norm.runningFold(0) { acc, p -> acc + p.length }
            var pos = 0
            for ((surface, romaji, reading) in japanese(line, false, readings)) {
                val i = (starts.indexOfLast { it <= pos }).coerceIn(0, norm.size - 1)
                val last = (starts.indexOfLast { it < pos + surface.length }).coerceIn(i, norm.size - 1)
                val split = if (last > i && reading != null) splitReading(surface, pos, reading, romaji, starts, i..last) else null
                if (split == null) {
                    words[i].append(romaji)
                } else {
                    split.forEachIndexed { k, r -> words[i + k].append(r) }
                }
                pos += surface.length
            }
            words.map { punctuation(it.toString()) }.toMutableList()
        }
        val first = out.indexOfFirst { it.isNotBlank() }
        if (first >= 0) out[first] = capFirst(norm[first], out[first])
        return parts.indices.map { i ->
            val partOverride = override.firstOrNull { it.original == parts[i].trim() }
            partOverride?.latin ?: out[i]
        }
    }

    /**
     * A word that karaoke times over several parts (忙|し|い) gets its romaji split the same way: kana parts at either
     * end take their own reading, the kanji in between the rest ("isoga" | "shi" | "i"). Null when the pieces wouldn't
     * add up to the word's romaji exactly (long vowels across parts, 行|こ|う), so the caller keeps it whole.
     */
    private fun splitReading(surface: String, at: Int, reading: String, romaji: String, starts: List<Int>, parts: IntRange): List<String>? {
        val pieces = parts.map { p ->
            val from = (starts[p] - at).coerceAtLeast(0)
            val to = (starts[p + 1] - at).coerceAtMost(surface.length)
            surface.substring(from, to)
        }
        val out = MutableList(pieces.size) { "" }
        var rest = reading
        var lo = 0
        var hi = pieces.size - 1
        while (lo <= hi && pieces[lo].all(::isKana) && rest.startsWith(toKatakana(pieces[lo]))) {
            out[lo] = kana(toKatakana(pieces[lo]))
            rest = rest.drop(pieces[lo].length)
            lo++
        }
        while (hi >= lo && pieces[hi].all(::isKana) && rest.endsWith(toKatakana(pieces[hi]))) {
            out[hi] = kana(toKatakana(pieces[hi]))
            rest = rest.dropLast(pieces[hi].length)
            hi--
        }
        if (lo <= hi) {
            out[lo] = kana(rest)
        } else if (rest.isNotEmpty()) {
            return null
        }
        val lead = romaji.takeWhile { it == ' ' }
        if (out.joinToString("") != romaji.trimStart()) return null
        val firstFilled = out.indexOfFirst { it.isNotEmpty() }.coerceAtLeast(0)
        out[firstFilled] = lead + out[firstFilled]
        return out
    }

    fun isJapanese(s: String, siblings: List<String>): Boolean = when {
        s.any(::isKana) -> true
        siblings.any { sib -> sib.any(::isKana) } -> true
        !hasHan(s) -> false
        settings.hanDefault != "ja" -> false
        else -> !looksChinese(s) && siblings.none(::looksChinese)
    }

    /**
     * Evidence that Han text is Chinese, from the standards and the dictionary rather than from words: a character
     * outside JIS X 0208, Japan's standard set; a Han word the Japanese dictionary doesn't know (both leave out the
     * variant forms Japanese names use, such as 﨑 and 𠮷); or three one-character Han
     * words in a row, as Chinese names and phrases fall apart into characters (周|杰|倫, 不|會|飛) where Japanese
     * compounds are dictionary words. In a sample, none of 120 Japanese titles and names without kana
     * matched. About 60% of Chinese titles did, and most artist names, which then decide their songs as siblings.
     */
    fun looksChinese(s: String): Boolean {
        if (!hasHan(s)) return false
        val cached = synchronized(chinese) { chinese[s] }
        if (cached != null) return cached
        val jis = SHIFT_JIS.newEncoder()
        val outsideJis = s.codePoints().anyMatch { c ->
            Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN && !variant(c) && !jis.canEncode(c.toChar())
        }
        // The dictionary's evidence counts only with the dictionary: without it every run of kanji is unknown.
        val dictionary = loaded ?: tokenizer()
        var result = outsideJis
        if (!result && dictionary != null) {
            val tokens = tokens(s)
            result = tokens.any { t -> hasHan(t.surface) && t.reading.none(::isKana) && t.surface.codePoints().noneMatch(::variant) }
            if (!result) {
                var run = 0
                result = tokens.any { t ->
                    run = if (t.surface.length == 1 && hasHan(t.surface)) run + 1 else 0
                    run >= 3
                }
            }
        }
        if (dictionary != null) synchronized(chinese) { chinese[s] = result }
        return result
    }

    /** A compatibility ideograph or a supplementary-plane character: variant forms that Japanese names use (﨑, 𠮷). */
    private fun variant(c: Int) = c >= 0x10000 || c in 0xF900..0xFAFF

    // ponytail: the results for the last 512 strings. Without them, looksChinese runs again for each song's artist.
    private val chinese = object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>) = size > 512
    }

    // Non-Japanese text converts each run of one script and keeps a space between words. Stylized words stay as
    // they are.
    private fun other(s: String, title: Boolean, cyrLang: String, words: Map<String, String> = emptyMap()): String {
        val out = StringBuilder()
        for (chunk in Regex("""\s+|\S+""").findAll(s).map { it.value }) {
            if (chunk.isBlank() || stylized(chunk)) {
                out.append(chunk)
                continue
            }
            val chunkReading = words[chunk]
            if (chunkReading != null) {
                appendWord(out, chunkReading)
                continue
            }
            val cps = chunk.codePoints().toArray()
            var i = 0
            while (i < cps.size) {
                val script = Character.UnicodeScript.of(cps[i])
                var j = i + 1
                // An apostrophe inside a Cyrillic word belongs to it: Ukrainian м'ясо, Belarusian сям'я.
                while (j < cps.size && (Character.UnicodeScript.of(cps[j]) == script ||
                        script == Character.UnicodeScript.CYRILLIC && cps[j].toChar() in APOSTROPHES &&
                        cps.getOrNull(j + 1)?.let { Character.UnicodeScript.of(it) } == script)) j++
                val run = String(cps, i, j - i)
                // A lone Greek or Cyrillic letter between symbols is part of a kaomoji: (´・ω・`)
                val kaomoji = j - i == 1 && i > 0 && j < cps.size && !Character.isLetter(cps[i - 1]) && !Character.isLetter(cps[j])
                val converted = words[run] ?: when (script) {
                    Character.UnicodeScript.HAN -> words(chinese(run), title)
                    Character.UnicodeScript.HANGUL -> words(hangul(run), title)
                    Character.UnicodeScript.CYRILLIC -> if (kaomoji) run else cyrillic(run, cyrLang)
                    Character.UnicodeScript.GREEK -> if (kaomoji) run else greek(run)
                    Character.UnicodeScript.LATIN, Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED -> run
                    else -> words(icu.transliterate("Any-Latin", run), title)
                }
                appendWord(out, converted)
                i = j
            }
        }
        return out.toString()
    }

    private fun chinese(s: String): String {
        val pinyin = icu.transliterate("Han-Latin", s)
        return if (settings.chineseTones) pinyin else stripMarks(pinyin)
    }

    // ICU's Greek-Latin is the classical scheme (β = b, η = ē), and its marks are dropped. UNGEGN is ELOT 743, whose
    // macron below (η = i̱) only serves reversibility.
    fun greek(s: String): String =
        if (settings.greek == "elot") icu.transliterate("Greek-Latin/UNGEGN", s).replace("\u0331", "")
        else stripMarks(icu.transliterate("Greek-Latin", s))

    // A Sudachi tokenizer isn't thread-safe, so calls take turns on it. A line takes well under a millisecond.
    @Volatile private var loaded: Tokenizer? = null
    private var failedAt = 0L
    private var told = false
    private val tokenizerLock = Any()

    private fun tokenizer(): Tokenizer? {
        val ready = loaded
        if (ready != null) return ready
        return synchronized(tokenizerLock) {
            val loadedMeanwhile = loaded
            if (loadedMeanwhile != null) return loadedMeanwhile
            // After a failure the next try waits a minute.
            if (failedAt != 0L && System.currentTimeMillis() - failedAt < 60_000) return null
            runCatching {
                // roma's config is Sudachi's default with the slim dictionary's renumbered unknown-word ids (:dict-tool).
                RomaDictionary(dictionary()).tokenizer(Config.fromClasspath("roma-sudachi.json"))
            }.onSuccess { loaded = it }.onFailure { e ->
                failedAt = System.currentTimeMillis()
                // Logged once per run, to say why kanji stay as written.
                if (!told) {
                    told = true
                    println("roma dictionary: not loaded: $e")
                }
            }.getOrNull()
        }
    }

    /** Loads the dictionary now, so the first Japanese string doesn't wait for it. Call off the main thread. */
    fun warmUp() { tokenizer() }

    /** The next Japanese string tries the dictionary again at once (it was just installed, or a setting changed). */
    fun retryDictionary() = synchronized(tokenizerLock) { if (loaded == null) failedAt = 0L }

    private class Token(val surface: String, val reading: String, val pos: List<String>, val begin: Int, val end: Int)

    private fun tokens(s: String): List<Token> {
        val t = tokenizer()
        if (t == null) {
            // Without the dictionary, kana is read as written and a run of kanji is kept as it is (NO_DICTIONARY).
            val out = ArrayList<Token>()
            var begin = 0
            for (i in 1..s.length) {
                val runEnds = i == s.length || hasHan(s.substring(i - 1, i)) != hasHan(s.substring(i, i + 1))
                if (!runEnds) continue
                val run = s.substring(begin, i)
                val han = hasHan(run)
                val reading = if (han) "" else toKatakana(run)
                // Six fields, like Sudachi's part of speech: the rules below read pos[1], pos[4] and pos[5].
                val pos = listOf(if (han) NO_DICTIONARY else "名詞", "*", "*", "*", "*", "*")
                out.add(Token(run, reading, pos, begin, i))
                begin = i
            }
            return out
        }
        // A morpheme's begin and end index the original text,
        // while surface() can be rewritten (君(きみ) keeps its yomigana).
        return synchronized(t) {
            t.tokenize(Tokenizer.SplitMode.C, s).map { m ->
                Token(s.substring(m.begin(), m.end()), m.readingForm(), m.partOfSpeech(), m.begin(), m.end())
            }
        }
    }

    /**
     * [NAME_READINGS] applied; in [title] case also kana after a surname that Sudachi split into function words and
     * that runs to the end of the name (幾田|り|ら, 椎名|も|た) made one given name, as a name has no grammar to split.
     */
    private fun names(found: List<Token>, title: Boolean): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        while (i < found.size) {
            val key = NAME_READINGS.keys.firstOrNull { k ->
                val surfaces = found.subList(i, minOf(i + k.size, found.size)).map { it.surface }
                k == surfaces
            }
            if (key != null) {
                val readings = NAME_READINGS.getValue(key)
                for (k in key.indices) {
                    val token = found[i + k]
                    out.add(Token(token.surface, readings[k], token.pos, token.begin, token.end))
                }
                i += key.size
                continue
            }
            out.add(found[i])
            var j = i + 1
            if (title && found[i].pos[3] == "姓") {
                while (j < found.size && found[j].surface.all(::isKana) && found[j].pos[0] in NAME_FRAGMENTS) j++
            }
            val runEndsName = j == found.size || !found[j].surface.any(::isKana) && !hasHan(found[j].surface)
            if (j - i > 2 && found[i + 1].pos[0] != "接尾辞" && runEndsName) {
                val run = found.subList(i + 1, j)
                val surface = run.joinToString("") { it.surface }
                val reading = run.joinToString("") { it.reading }
                val givenName = listOf("名詞", "固有名詞", "人名", "名", "*", "*")
                out.add(Token(surface, reading, givenName, run.first().begin, run.last().end))
                i = j
            } else {
                i++
            }
        }
        return out
    }

    /** (surface, romaji with its leading space) per token. In [title] case every word but a particle is capitalized. */
    private fun japanese(s: String, title: Boolean, words: Map<String, String> = emptyMap()): List<Triple<String, String, String?>> {
        val result = ArrayList<Triple<String, String, String?>>()
        val tokens = names(tokens(s), title)
        var prevWord = false
        // The previous token was closing punctuation (」, ）, ”), so a word after it starts a new one.
        var prevClose = false
        var prev: Token? = null
        // The previous token ended in ッ, so this one's first consonant doubles: dat|ta.
        var sokuon = false
        var stray = false
        // A user's word reading replaces the longest run of whole tokens that spells the word.
        val wanted = words.filterKeys { it.isNotEmpty() && it in s }
        val ends = tokens.map { it.end }
        var skip = 0
        for ((i, t) in tokens.withIndex()) {
            if (skip > 0) {
                skip--
                continue
            }
            var word: String? = null
            if (wanted.isNotEmpty()) {
                val spelled = wanted.keys.filter { s.startsWith(it, t.begin) && t.begin + it.length in ends }
                word = spelled.maxByOrNull { it.length }
            }
            if (word != null) {
                val last = ends.indexOf(t.begin + word.length)
                val latin = wanted.getValue(word)
                val isWord = latin.firstOrNull()?.isLetterOrDigit() == true
                // A reading is a word like any other: title case applies to it, as the line's first letter does.
                val leadingSpace = if ((prevWord || prevClose) && isWord) " " else ""
                val cased = if (title && isWord) capitalize(latin, false) else latin
                result.add(Triple(word, leadingSpace + cased, null))
                prevWord = latin.lastOrNull()?.isLetterOrDigit() == true
                prevClose = false
                prev = tokens[last]
                sokuon = false
                stray = false
                skip = last - i
                continue
            }
            val surface = t.surface
            val pos = t.pos[0]
            val prevPos = prev?.pos?.get(0)
            val japaneseText = surface.any(::isKana) || hasHan(surface)
            // A kanji the dictionary doesn't know (a simplified Chinese 词 in a credit line) has no kana reading: it goes
            // through the Chinese path below instead of being left in the output. Joined numbers (三) are OOV too, but
            // keep their reading.
            val unknownHan = hasHan(surface) && t.reading.none(::isKana)
            val reading = if (japaneseText && !unknownHan) {
                val written = if (t.reading.none(::isKana)) toKatakana(surface) else t.reading
                spoken(written, t, tokens.getOrNull(i + 1))
            } else {
                null
            }
            var romaji = when {
                pos == NO_DICTIONARY -> surface
                reading != null -> kana(reading)
                hasNonLatin(surface) -> other(surface, title, cyrillicLanguage(surface))
                else -> surface
            }
            if (sokuon && japaneseText) {
                val c = romaji.firstOrNull()
                if (romaji.startsWith("ch")) {
                    romaji = "t$romaji"
                } else if (c != null && c.isLetter() && c !in "aiueonāīūēō") {
                    romaji = c + romaji
                }
            }
            sokuon = japaneseText && toKatakana(surface).endsWith('ッ')
            // Auxiliaries, suffixes, a prefix, the te-form (and ちゃ じゃ for ては では) and ば attach to their word: "aruita", "aruite",
            // "tabemasu", "okāsan", "sannin", "ikeba", "datte". A subsidiary verb straight after a verb stem glues
            // ("hashiridasu", "ureshikunai" for ない); after the te-form or an adjective it is its own word:
            // "tokete yuku", "tabete iru", "tsuyoku nareru". Contractions such as てる and ちゃう are
            // auxiliaries, so they glue. Auxiliaries that read as words of their own (です, らしい) stay apart, and
            // so do よう and みたい, which Sudachi tags as 形状詞.
            val afterInflected = prevPos == "動詞" || prevPos == "形容詞" || prevPos == "助動詞"
            val glue = pos == "助動詞" && afterInflected && t.pos[4] !in LOOSE_AUX ||
                pos == "接尾辞" || prevPos == "接頭辞" ||
                pos == "動詞" && t.pos[1] == "非自立可能" && prevPos == "動詞" ||
                pos == "形容詞" && t.pos[1] == "非自立可能" && afterInflected ||
                t.pos[1] == "接続助詞" && (surface in setOf("て", "で", "ちゃ", "じゃ") || surface == "ば" && afterInflected) ||
                surface == "って" && prev?.surface == "だ" ||
                prev?.pos?.get(1) == "数詞" && pos == "名詞" || stray ||
                t.pos[1] == "準体助詞" && surface == "ん" || // こんなんじゃ "konnan ja", なんだ "nan da"
                // A small vowel kana only stretches the sound before it (うっせぇ, だりぃ): it can't start a word.
                prev != null && surface.all { it in "ぁぃぅぇぉァィゥェォ" }
            // A sentence-final particle can't start a phrase: there it is a misread first syllable. Sudachi reads
            // よふかし (夜更かし) as よ|ふかし, so it joins the next word: "Yofukashi no Uta".
            stray = t.pos[1] == "終助詞" && !prevWord && tokens.getOrNull(i + 1)?.surface?.any(::isKana) == true
            // Particles stay lowercase in titles, and so does だ as に, な or で after a na-adjective ("Shizumu Yō ni").
            val particle = pos == "助詞" && !stray || pos == "助動詞" && t.pos[4] == "助動詞-ダ" && surface in setOf("に", "な", "で")
            val isWord = romaji.firstOrNull()?.isLetterOrDigit() == true
            // Paired punctuation sits against what it encloses, with a space on its outer side: "Itai" to "itai".
            val opens = surface.isNotEmpty() && surface.all(::opening)
            val space = prevWord && isWord && !glue || prevClose && (isWord || opens) || prevWord && opens
            if (title && japaneseText && isWord && !(prevWord && glue) && !particle) romaji = capitalize(romaji, false)
            val leadingSpace = if (space) " " else ""
            result.add(Triple(surface, leadingSpace + romaji, reading))
            prevWord = romaji.lastOrNull()?.isLetterOrDigit() == true
            prevClose = surface.isNotEmpty() && surface.all(::closing)
            prev = t
        }
        return result
    }

    /**
     * Sudachi's reading is the kana spelling (東京 トウキョウ, particle は ハ). Hepburn writes what is said: particles は
     * へ as wa e, and a long o or u as ō ū where the spelling has オウ, オオ or ウウ inside a word (tōkyō, ōkii, kūki).
     * エイ and イイ stay (eiga, ōkii). A verb's final ウ is its ending, not a long vowel (思う omou), except in the
     * volitional (行こう ikō). 何 is nan before t, d and n sounds and as a number (nande, nando), else nani.
     */
    private fun spoken(reading: String, t: Token, next: Token?): String {
        if (t.pos[0] == "助詞") {
            when (t.surface) {
                "は" -> return "ワ"
                "へ" -> return "エ"
            }
        }
        if (t.surface == "何" && t.pos[0] == "代名詞") {
            val nextSound = next?.reading?.firstOrNull()
            val nan = nextSound != null && nextSound in "タチツテトダヂヅデドナニヌネノ"
            return if (nan) "ナン" else "ナニ"
        }
        val verbEnding = t.pos[0] == "動詞" && t.pos[5] != "意志推量形"
        val sb = StringBuilder()
        for ((i, c) in reading.withIndex()) {
            val before = reading.getOrNull(i - 1)
            val long = before != null && (c == 'ウ' && (before in O_ROW || before in U_ROW) || c == 'オ' && before in O_ROW) &&
                !(verbEnding && i == reading.lastIndex)
            sb.append(if (long) 'ー' else c)
        }
        return sb.toString()
    }

    /** Katakana (or hiragana) to Modified Hepburn. */
    fun kana(input: String): String {
        val s = toKatakana(input)
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == 'ッ' -> {
                    val following = syllable(s, i + 1).first
                    val first = following.firstOrNull()
                    if (following.startsWith("ch")) {
                        out.append('t')
                    } else if (first != null && first !in "aiueon") {
                        out.append(following[0])
                    }
                    i++
                }
                c == 'ン' -> {
                    val following = syllable(s, i + 1).first
                    val first = following.firstOrNull()
                    out.append(if (first != null && first in "aiueoy") "n'" else "n")
                    i++
                }
                c == 'ー' -> {
                    val last = out.lastOrNull()
                    if (last != null && last in "aiueo") {
                        if (settings.japaneseMacrons) out.setCharAt(out.length - 1, macron(last))
                    } else if (last == null || !isMacron(last)) out.append('-')
                    i++
                }
                else -> {
                    val (romaji, used) = syllable(s, i)
                    out.append(romaji)
                    i += used
                }
            }
        }
        return out.toString()
    }

    private fun syllable(s: String, i: Int): Pair<String, Int> {
        val c = s.getOrNull(i) ?: return "" to 1
        val base = KANA[c] ?: return c.toString() to 1
        val n = s.getOrNull(i + 1)
        if (n != null && n in "ャュョ") {
            val v = KANA[n]!!.drop(1) // a, u, o
            val stem = base.dropLast(1)
            val combined = if (stem.endsWith("sh") || stem.endsWith("ch") || stem.endsWith("j")) stem + v else stem + "y" + v
            return combined to 2
        }
        if (n != null && n in "ァィゥェォ" && c !in "ァィゥェォャュョッン") {
            val stem = if (c == 'ウ') "w" else base.dropLast(1)
            return stem + KANA[n]!! to 2
        }
        return base to 1
    }

    /**
     * A line split into the words its romanization shows: in Japanese a word with its glued endings and the
     * particles apart (食べます|君|に、), opening punctuation with the word it opens (と|「居たい」), elsewhere at spaces.
     * The pieces join back into the line, and each is made of whole dictionary tokens, so a correction of one matches it
     * everywhere.
     */
    fun pieces(line: String, siblings: List<String> = emptyList()): List<String> {
        if (!isJapanese(normalize(line), siblings)) {
            val words = Regex("""^\s*\S+\s*|\S+\s*""").findAll(line).map { it.value }.toList()
            return words.ifEmpty { listOf(line) }
        }
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var hasWord = false
        // The piece so far ends in punctuation or a space.
        var endsLoose = false
        for ((surface, romaji, _) in japanese(line, false)) {
            val isWord = romaji.trimStart().firstOrNull()?.isLetterOrDigit() == true || hasNonLatin(surface)
            val opens = surface.isNotEmpty() && surface.all(::opening)
            val startsWord = isWord && (romaji.startsWith(' ') || endsLoose)
            if (cur.isNotEmpty() && hasWord && (startsWord || opens)) {
                out.add(cur.toString())
                cur.clear()
                hasWord = false
            }
            cur.append(surface)
            hasWord = hasWord || isWord
            endsLoose = !isWord && !opens
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    /** One dictionary token of a word, for the word card: its kana reading, romaji, dictionary form and part of speech. */
    class WordInfo(val surface: String, val kana: String, val romaji: String, val dictionaryForm: String, val dictionaryRomaji: String, val partOfSpeech: String)

    /** The tokens of a Japanese word with what the dictionary says about them; empty for other text. */
    fun lookUp(word: String): List<WordInfo> {
        val n = normalize(word).trim()
        if (n.none(::isKana) && !hasHan(n)) return emptyList()
        val tokenizer = tokenizer() ?: return emptyList()
        val ms = synchronized(tokenizer) { tokenizer.tokenize(Tokenizer.SplitMode.C, n).toList() }
        return ms.filter { it.partOfSpeech()[0] != "空白" }.map { m ->
            val readingForm = m.readingForm()
            val reading = if (readingForm.any(::isKana)) readingForm else toKatakana(m.surface())
            val dict = m.dictionaryForm()
            val dictReading = if (dict == m.surface()) {
                reading
            } else {
                synchronized(tokenizer) { tokenizer.tokenize(Tokenizer.SplitMode.C, dict).joinToString("") { it.readingForm() } }
            }
            val pos = m.partOfSpeech()[0]
            WordInfo(m.surface(), toHiragana(reading), kana(reading), dict, kana(dictReading), POS_NAMES[pos] ?: pos)
        }
    }

    /**
     * The scripts a string needs reading in: "ja", "zh", "ko", "cyrillic", "greek" or "other". Han counts as Japanese
     * or Chinese by the same rule as romanization. Empty for Latin text.
     */
    fun scripts(s: String, siblings: List<String> = emptyList()): Set<String> {
        val out = HashSet<String>()
        val japanese by lazy { isJapanese(s, siblings) }
        s.codePoints().forEach { cp ->
            when (Character.UnicodeScript.of(cp)) {
                Character.UnicodeScript.LATIN, Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED -> {}
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> out.add("ja")
                Character.UnicodeScript.HAN -> out.add(if (japanese) "ja" else "zh")
                Character.UnicodeScript.HANGUL -> out.add("ko")
                Character.UnicodeScript.CYRILLIC -> out.add("cyrillic")
                Character.UnicodeScript.GREEK -> out.add("greek")
                else -> out.add("other")
            }
        }
        return out
    }

    /** Korean in the Revised Romanization. */
    fun hangul(s: String): String {
        val out = StringBuilder()
        var pendingInitial: String? = null
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c !in '가'..'힣') {
                out.append(c)
                i++
                pendingInitial = null
                continue
            }
            val code = c - '가'
            var initial = INITIAL[code / SYLLABLES_PER_INITIAL]
            val vowel = VOWEL[(code % SYLLABLES_PER_INITIAL) / CODA_COUNT]
            val t = code % CODA_COUNT
            if (pendingInitial != null) initial = pendingInitial
            pendingInitial = null
            var coda = CODA[t]
            val nextC = s.getOrNull(i + 1)
            if (nextC != null && nextC in '가'..'힣' && t != 0) {
                val nextL = (nextC - '가') / SYLLABLES_PER_INITIAL
                when {
                    nextL == 11 && t != 21 -> { // ㅇ: the final consonant moves to the next syllable
                        coda = LIAISON[t].first
                        pendingInitial = LIAISON[t].second
                    }
                    nextL == 2 || nextL == 6 -> { // ㄴ ㅁ: nasalization
                        coda = when (coda) {
                            "k" -> "ng"
                            "t" -> "n"
                            "p" -> "m"
                            else -> coda
                        }
                        if (coda == "l" && nextL == 2) pendingInitial = "l"
                    }
                    nextL == 5 -> { // ㄹ
                        if (coda == "n" || coda == "l") {
                            coda = "l"
                            pendingInitial = "l"
                        } else {
                            coda = when (coda) {
                                "k" -> "ng"
                                "t" -> "n"
                                "p" -> "m"
                                else -> coda
                            }
                            pendingInitial = "n"
                        }
                    }
                    (t == 27 || t == 6 || t == 15) && nextL in listOf(0, 3, 12) -> { // ㅎ before ㄱ ㄷ ㅈ: aspiration
                        coda = if (t == 27) "" else CODA[t]
                        pendingInitial = when (nextL) {
                            0 -> "k"
                            3 -> "t"
                            else -> "ch"
                        }
                    }
                }
            }
            out.append(initial).append(vowel).append(coda)
            i++
        }
        return out.toString()
    }

    /**
     * Russian uses the chosen scheme (English-style, Polish-style, ISO 9). Other languages use their national Latin
     * form, unless ISO 9 is chosen. Whatever a table leaves goes through ICU's Cyrillic-Latin, so no Cyrillic remains.
     */
    fun cyrillic(s: String, lang: String = cyrillicLanguage(s)): String {
        val out = when {
            settings.cyrillic == "iso9" -> table(s, ISO9)
            lang == "ru" && settings.cyrillic == "polish" -> polish(s)
            lang == "ru" -> table(s, ENGLISH)
            lang == "mn" -> runCatching { icu.transliterate("mn-mn_Latn/MNS", s) }.getOrDefault(s)
            else -> national(s, lang)
        }
        if (!hasCyrillic(out)) return out
        val icuOut = icu.transliterate("Cyrillic-Latin", out)
        if (!hasCyrillic(icuOut)) return icuOut
        // ICU skips letters such as Tatar җ and Tajik ҷ: take the letter they are built on (ж, ч), or drop them.
        val based = icuOut.map { c ->
            if (hasCyrillic(c.toString())) baseLetter(c)?.toString() ?: "" else c.toString()
        }.joinToString("")
        return icu.transliterate("Cyrillic-Latin", based).filterNot { hasCyrillic(it.toString()) }
    }

    private fun table(s: String, table: Map<Char, String>): String {
        val out = StringBuilder()
        for ((i, c) in s.withIndex()) {
            val lower = c.lowercaseChar()
            var r = table[lower] ?: c.toString()
            // English style writes е as "ye" at the start of a word and after a vowel.
            if (table === ENGLISH && lower == 'е' && (i == 0 || s[i - 1].lowercaseChar() in VOWELS_CYR || !s[i - 1].isLetter())) r = "ye"
            out.append(if (c.isUpperCase()) capitalize(r, allCaps(s, i)) else r)
        }
        return out.toString()
    }

    // Kazakh: the 2021 Latin alphabet. Ukrainian: the 2010 national transliteration. Belarusian: the 2007 national
    // system. Serbian: Gaj's alphabet. Macedonian: the official romanization. Bulgarian: the 2009 Streamlined System.
    private fun national(s: String, lang: String): String {
        val table = NATIONAL.getValue(lang)
        val out = StringBuilder()
        for ((i, c) in s.withIndex()) {
            val lower = c.lowercaseChar()
            val prev = s.getOrNull(i - 1)?.lowercaseChar()
            val next = s.getOrNull(i + 1)?.lowercaseChar()
            val initial = prev == null || !prev.isLetter() && prev !in APOSTROPHES
            if (lower in APOSTROPHES && (lang == "uk" || lang == "be")) continue
            var r = when {
                lang == "uk" && lower in "єїйюя" -> if (initial) UK_INITIAL.getValue(lower) else table.getValue(lower)
                lang == "uk" && lower == 'г' && prev == 'з' -> "gh" // зг: zgh
                lang == "be" && lower in "еёюя" -> {
                    val soft = initial || prev in VOWELS_CYR || prev in APOSTROPHES || prev == 'ь' || prev == 'ў'
                    (if (soft) "j" else "i") + table.getValue(lower)
                }
                lang == "be" && lower == 'ь' -> {
                    // зь ль нь сь ць (and дзь) take an acute: ź ĺ ń ś ć. ь elsewhere is silent.
                    if (prev != null && prev in "злнсц") out.setCharAt(out.length - 1, Normalizer.normalize(out.last() + "\u0301", Normalizer.Form.NFC).single())
                    continue
                }
                lang == "bg" && lower == 'я' && prev == 'и' && (next == null || !next.isLetter()) -> "a" // final ия: ia
                else -> table[lower] ?: c.toString()
            }
            if (c.isUpperCase()) {
                r = capitalize(r, allCaps(s, i))
                if (lang == "kk" && r.startsWith('I') && lower != 'і') r = "İ" + r.drop(1) // и й: İ, і: I
            }
            out.append(r)
        }
        return out.toString()
    }

    private fun polish(s: String): String {
        val out = StringBuilder()
        for ((i, c) in s.withIndex()) {
            val lower = c.lowercaseChar()
            val prev = s.getOrNull(i - 1)?.lowercaseChar()
            val next = s.getOrNull(i + 1)?.lowercaseChar()
            val afterConsonant = prev != null && prev.isLetter() && prev !in VOWELS_CYR && prev != 'ь' && prev != 'ъ'
            val hard = prev != null && prev in "жшцчщ"
            var r = when (lower) {
                'е' -> if (!afterConsonant) "je" else if (hard) "e" else "ie"
                'ё' -> if (!afterConsonant) "jo" else if (hard) "o" else "io"
                'ю' -> if (!afterConsonant) "ju" else "iu"
                'я' -> if (!afterConsonant) "ja" else "ia"
                'л' -> if (next != null && next in "ьеёюяи") "l" else "ł"
                'ь' -> when (prev) { 'н' -> "́"; 'с' -> "́"; 'з' -> "́"; else -> "" }
                else -> POLISH[lower] ?: c.toString()
            }
            if (r == "́") { // нь сь зь: ń ś ź
                val last = out.last()
                out.setLength(out.length - 1)
                out.append(Normalizer.normalize(last.toString() + "́", Normalizer.Form.NFC))
                continue
            }
            if (c.isUpperCase()) r = capitalize(r, allCaps(s, i))
            out.append(r)
        }
        return out.toString()
    }

    companion object {
        private const val NO_DICTIONARY = "辞書なし"

        // A Hangul syllable's code is (initial × 21 + vowel) × 28 + final consonant, from 가.
        private const val CODA_COUNT = 28
        private const val SYLLABLES_PER_INITIAL = 21 * CODA_COUNT

        /** Names Sudachi reads wrong, by their tokens: the reading their MusicBrainz sort name gives (Yonezu, Kenshi). */
        private val NAME_READINGS = mapOf(listOf("米津", "玄師") to listOf("ヨネヅ", "ケンシ"))
        private val NAME_FRAGMENTS = setOf("助詞", "助動詞", "感動詞", "接尾辞")
        fun isKana(c: Char) = (c in 'ぁ'..'ヺ') || c in 'ㇰ'..'ㇿ' || c in 'ｦ'..'ﾝ'
        private val SHIFT_JIS = charset("Shift_JIS")

        fun hasHan(s: String) = s.codePoints().anyMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN }
        /** Opening and closing punctuation by Unicode's classes (「 （ “ and 」 ）”); ’ is the apostrophe too, so it doesn't close. */
        private fun opening(c: Char): Boolean {
            val type = Character.getType(c)
            return type == Character.START_PUNCTUATION.toInt() || type == Character.INITIAL_QUOTE_PUNCTUATION.toInt()
        }
        private fun closing(c: Char): Boolean {
            if (c == '’') return false
            val type = Character.getType(c)
            return type == Character.END_PUNCTUATION.toInt() || type == Character.FINAL_QUOTE_PUNCTUATION.toInt()
        }

        fun hasNonLatin(s: String) = s.codePoints().anyMatch {
            val sc = Character.UnicodeScript.of(it)
            sc != Character.UnicodeScript.LATIN && sc != Character.UnicodeScript.COMMON && sc != Character.UnicodeScript.INHERITED
        }

        /**
         * The language of a Cyrillic string, from its distinctive letters: "kk", "ky", "uk", "be", "sr", "mk", "bg", "mn" or "ru".
         * Bulgarian has no letter of its own, only ъ before a consonant (път, България), which Russian never writes
         * now, and no ы э ё. Anything else is Russian, the safe default.
         */
        fun cyrillicLanguage(s: String): String {
            val t = s.lowercase()
            fun has(letters: String) = t.any { it in letters }
            return when {
                // Kazakh, Kyrgyz and Mongolian share ө ү; ң is Kazakh and Kyrgyz; ә ғ қ ұ һ are Kazakh only, and so is і
                // next to ң ө ү (alone it is Ukrainian or Belarusian).
                has("әғқұһ") || has("і") && has("ңөү") -> "kk"
                has("ң") -> "ky"
                has("өү") -> "mn"
                has("ў") || has("і") && has("ыэё") && !has("иєїґ") -> "be"
                has("єїґі") -> "uk"
                has("ѓќѕ") -> "mk"
                has("ђјљњћџ") -> "sr"
                Regex("ъ[бвгджзйклмнпрстфхцчшщ]").containsMatchIn(t) && !has("ыэё") -> "bg"
                else -> "ru"
            }
        }

        private const val RU = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя"
        private val ALPHABETS = mapOf(
            "ru" to RU,
            "kk" to RU + "әғқңөұүһі",
            "ky" to RU + "ңөү",
            "mn" to RU + "өү",
            "uk" to "абвгґдеєжзиіїйклмнопрстуфхцчшщьюя",
            "be" to "абвгдеёжзійклмнопрстуўфхцчшыьэюя",
            "bg" to "абвгдежзийклмнопрстуфхцчшщъьюя",
            "sr" to "абвгдђежзијклљмнњопрстћуфхцчџш",
            "mk" to "абвгдѓежзѕијклљмнњопрстќуфхцчџш",
        )

        /** True when every Cyrillic letter of s belongs to the language's alphabet. */
        fun cyrillicFits(language: String, s: String): Boolean {
            val alphabet = ALPHABETS[language] ?: return true
            return s.lowercase().none { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.CYRILLIC && it !in alphabet }
        }

        /**
         * A lyric line's language: the song's, unless the line has a letter that language lacks (a Russian line with ы
         * in a Ukrainian song); then the line's own. Kazakh contains every Russian letter, so Russian lines in a Kazakh
         * song stay Kazakh: the outputs differ little and a correction covers the rest.
         */
        fun cyrillicLanguageOfLine(line: String, song: String) = if (cyrillicFits(song, line)) song else cyrillicLanguage(line)

        private fun hasCyrillic(s: String) = s.codePoints().anyMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.CYRILLIC }

        private val CYRILLIC_NAMES by lazy { (0x400..0x45F).associateBy { Character.getName(it) } }

        /** "CYRILLIC SMALL LETTER ZHE WITH DESCENDER" → ж; "… BASHKIR KA" → к. Null if no basic letter fits. */
        private fun baseLetter(c: Char): Char? {
            val name = Character.getName(c.code) ?: return null
            val case = if (c.isUpperCase()) "CAPITAL" else "SMALL"
            val letter = name.substringAfter("LETTER ").substringBefore(" WITH ")
            val code = CYRILLIC_NAMES["CYRILLIC $case LETTER $letter"] ?: CYRILLIC_NAMES["CYRILLIC $case LETTER ${letter.substringAfterLast(' ')}"]
            return code?.toChar()
        }

        fun stripMarks(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

        /**
         * NFKC, but only on the half-width and full-width forms block: ＬＯＶＥ → LOVE, ｱｲ → アイ, ～ → ~.
         * Full NFKC would also change Latin text (² → 2, ﬁ → fi) and kaomoji (´ → space + accent).
         * A full-width parenthesis carries its own spacing, so it gets a space next to a word: テトリス（feat. X）.
         */
        fun normalize(s: String): String {
            val spaced = s.replace(Regex("(?<=\\S)（"), " （").replace(Regex("）(?=[^\\s)）])"), "） ")
            val folded = Regex("[\\uFF00-\\uFFEF]+").replace(spaced) { Normalizer.normalize(it.value, Normalizer.Form.NFKC) }
            return longVowelMarks(folded)
        }

        /**
         * Lyrics typed on Chinese keyboards often write the katakana long-vowel mark ー as the kanji 一 or a dash:
         * ブル一ライト. After katakana, and not before a kanji (カード一枚 is "one card"), those mean ー.
         */
        fun longVowelMarks(s: String) = s
            .replace(Regex("(?<=[ァ-ヺ])[一—―－‐](?=[ァ-ヺ]|[^\\p{IsHan}]|$)"), "ー")
            .replace(Regex("(?<=[ァ-ヺ])-(?=[ァ-ヺ])"), "ー")

        /** "スピカ - Spica" → "Spica": the Latin form a title carries after " - ", " / " or in parentheses. */
        fun ownLatin(s: String): String? {
            val n = normalize(s)
            val m = Regex("""^(.+?) (?:-|/) (.+)$""").find(n) ?: Regex("""^(.+?)\s*\(([^()]+)\)$""").find(n) ?: return null
            val (left, right) = m.destructured
            // The tag check skips the Latin part's own brackets: "すずめ - Suzume (feat. Toaka)" is still "Suzume (feat. Toaka)".
            val tagged = VERSION_TAG.containsMatchIn(right.replace(Regex("""\(.*?\)"""), ""))
            if (!hasNonLatin(left) || hasNonLatin(right) || right.none { it.isLetter() } || tagged) return null
            return right.trim()
        }

        // ponytail: a word list for version tags, so "(Ballade Ver.)" and "(feat. X)" aren't taken for a translation.
        private val VERSION_TAG = Regex("""(?i)\b(ver|version|size|mix|remix|live|edit|acoustic|instrumental|inst|cover|feat|ft|prod|from|op|ed|opening|ending|theme|remaster|remastered|mv|short|full|tv|demo|karaoke|off vocal)\b""")

        /** A word mixing Latin with Greek or Cyrillic letters is stylized (ΛRCTIC, ЯOCK, μ's): keep it. */
        private fun stylized(word: String): Boolean {
            val scripts = word.codePoints().filter(Character::isLetter).toArray().map { Character.UnicodeScript.of(it) }.toSet()
            return Character.UnicodeScript.LATIN in scripts &&
                (Character.UnicodeScript.GREEK in scripts || Character.UnicodeScript.CYRILLIC in scripts)
        }

        // Turns 、。「」『』・～ into Latin punctuation. ・ becomes a space only between words, so (´・ω・`) keeps it.
        private fun punctuation(s: String): String = s
            .replace(Regex("、(?=\\p{L})"), ", ")
            .replace('、', ',')
            .replace(Regex("。(?=\\p{L})"), ". ")
            .replace('。', '.')
            .replace(Regex("(?<=[\\p{L}\\p{N}])[「『]"), " \"")
            .replace(Regex("[」』](?=[\\p{L}\\p{N}])"), "\" ")
            .replace(Regex("[「」『』]"), "\"")
            .replace(Regex("(?<=\\p{L})・(?=\\p{L})"), " ")
            .replace('〜', '~')
            .replace('～', '~')

        /** Capitalizes each space-separated word: Korean, Chinese (one word per syllable) and other caseless scripts. */
        private fun words(s: String, title: Boolean) = if (title) s.split(' ').joinToString(" ") { capitalize(it, false) } else s

        // The first word is always capitalized (βίος → "Bios"), unless it was Latin already or stayed as it was (ω in a kaomoji).
        private fun capFirst(original: String, latin: String): String {
            val cp = original.codePoints().filter(Character::isLetter).findFirst().orElse(-1)
            val out = latin.firstOrNull { it.isLetter() } ?: return latin
            if (cp < 0) return latin
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN) return latin
            if (Character.UnicodeScript.of(out.code) != Character.UnicodeScript.LATIN) return latin
            return capitalize(latin, false)
        }

        // The capital at [i] is part of an all-caps word: the next letter is a capital too, or it ends one ("ПЛАЩ").
        private fun allCaps(s: String, i: Int): Boolean {
            val next = s.getOrNull(i + 1)
            if (next?.isUpperCase() == true) return true
            val endsWord = next == null || !next.isLetter()
            return endsWord && s.getOrNull(i - 1)?.isUpperCase() == true
        }

        // Uppercases the first letter, wherever it is: "~anata" → "~Anata".
        private fun capitalize(r: String, allCaps: Boolean): String {
            if (allCaps) return r.uppercase()
            val i = r.indexOfFirst { it.isLetter() }
            return if (i < 0) r else r.substring(0, i) + r[i].uppercase() + r.substring(i + 1)
        }

        private fun appendWord(out: StringBuilder, word: String) {
            if (out.isNotEmpty() && word.isNotEmpty() && out.last().isLetterOrDigit() && word.first().isLetterOrDigit()) out.append(' ')
            out.append(word)
        }

        // Auxiliaries (by conjugation type) that stay separate words after a verb: "iku deshō", "kuru rashii".
        private val LOOSE_AUX = setOf("助動詞-デス", "助動詞-ラシイ", "助動詞-ダ")
        private const val O_ROW = "オコソトノホモヨロゴゾドボポョォ"
        private const val U_ROW = "ウクスツヌフムユルグズヅブプュゥ"

        // The hiragana and katakana blocks hold the same kana in the same order, this far apart.
        private const val KATAKANA_OFFSET = 0x60

        private fun toKatakana(s: String) = buildString {
            for (c in s) append(if (c in 'ぁ'..'ゖ') c + KATAKANA_OFFSET else c)
        }

        private fun toHiragana(s: String) = buildString {
            for (c in s) append(if (c in 'ァ'..'ヶ') c - KATAKANA_OFFSET else c)
        }

        // Sudachi's part-of-speech names in plain English, for the word card.
        private val POS_NAMES = mapOf(
            "名詞" to "noun", "代名詞" to "pronoun", "動詞" to "verb", "形容詞" to "adjective", "形状詞" to "na-adjective",
            "副詞" to "adverb", "連体詞" to "pre-noun adjectival", "接続詞" to "conjunction", "感動詞" to "interjection",
            "助詞" to "particle", "助動詞" to "auxiliary verb", "接頭辞" to "prefix", "接尾辞" to "suffix",
            "補助記号" to "punctuation", "記号" to "symbol",
        )
        private fun macron(c: Char) = "āīūēō"["aiueo".indexOf(c)]
        private fun isMacron(c: Char) = c in "āīūēō"

        private val KANA: Map<Char, String> = run {
            val m = HashMap<Char, String>()
            fun row(chars: String, vararg romaji: String) = chars.forEachIndexed { i, c -> m[c] = romaji[i] }
            row("アイウエオ", "a", "i", "u", "e", "o")
            row("カキクケコ", "ka", "ki", "ku", "ke", "ko")
            row("サシスセソ", "sa", "shi", "su", "se", "so")
            row("タチツテト", "ta", "chi", "tsu", "te", "to")
            row("ナニヌネノ", "na", "ni", "nu", "ne", "no")
            row("ハヒフヘホ", "ha", "hi", "fu", "he", "ho")
            row("マミムメモ", "ma", "mi", "mu", "me", "mo")
            row("ヤユヨ", "ya", "yu", "yo")
            row("ラリルレロ", "ra", "ri", "ru", "re", "ro")
            row("ワヰヱヲ", "wa", "i", "e", "o")
            row("ガギグゲゴ", "ga", "gi", "gu", "ge", "go")
            row("ザジズゼゾ", "za", "ji", "zu", "ze", "zo")
            row("ダヂヅデド", "da", "ji", "zu", "de", "do")
            row("バビブベボ", "ba", "bi", "bu", "be", "bo")
            row("パピプペポ", "pa", "pi", "pu", "pe", "po")
            row("ァィゥェォ", "a", "i", "u", "e", "o")
            row("ャュョヮヵヶヴ", "ya", "yu", "yo", "wa", "ka", "ke", "vu")
            m
        }

        private val INITIAL = listOf("g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h")
        private val VOWEL = listOf("a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i")
        private val CODA = listOf("", "k", "k", "k", "n", "n", "n", "t", "l", "k", "m", "l", "l", "l", "p", "l", "m", "p", "p", "t", "t", "ng", "t", "t", "k", "t", "p", "t")
        // Final consonant before a silent ㅇ: what stays, what becomes the next syllable's initial.
        private val LIAISON = listOf(
            "" to "", "" to "g", "" to "kk", "k" to "s", "" to "n", "n" to "j", "n" to "", "" to "d", "" to "r", "l" to "g",
            "l" to "m", "l" to "b", "l" to "s", "l" to "t", "l" to "p", "l" to "", "" to "m", "" to "b", "p" to "s", "" to "s",
            "" to "ss", "ng" to "", "" to "j", "" to "ch", "" to "k", "" to "t", "" to "p", "" to "",
        )

        private const val VOWELS_CYR = "аеёиоуыэюяіїєaeiou"
        private const val APOSTROPHES = "'’ʼ"
        private fun cyr(letters: String, vararg latin: String) = letters.withIndex().associate { (i, c) -> c to latin[i] }
        private const val CYR = "абвгдеёжзийклмнопрстуфхцчшщъыьэюяіїєґў"
        private val ENGLISH = cyr(CYR, "a", "b", "v", "g", "d", "e", "yo", "zh", "z", "i", "y", "k", "l", "m", "n", "o", "p", "r", "s", "t", "u", "f", "kh", "ts", "ch", "sh", "shch", "", "y", "", "e", "yu", "ya", "i", "yi", "ye", "g", "w")
        private val ISO9 = cyr(CYR, "a", "b", "v", "g", "d", "e", "ë", "ž", "z", "i", "j", "k", "l", "m", "n", "o", "p", "r", "s", "t", "u", "f", "h", "c", "č", "š", "ŝ", "ʺ", "y", "ʹ", "è", "û", "â", "ì", "ï", "ê", "g̀", "ŭ")
        private val NATIONAL = mapOf(
            // Kyrgyz has no official Latin alphabet; this is BGN/PCGN 1979.
            "ky" to cyr("абвгдеёжзийклмнңоөпрстуүфхцчшщъыьэюя", "a", "b", "v", "g", "d", "e", "yo", "j", "z", "i", "y", "k", "l", "m", "n", "ng", "o", "ö", "p", "r", "s", "t", "u", "ü", "f", "kh", "ts", "ch", "sh", "shch", "", "y", "", "e", "yu", "ya"),
            "kk" to cyr("абвгдеёжзийклмнопрстуфхцчшщъыьэюяәғқңөұүһі", "a", "b", "v", "g", "d", "e", "io", "j", "z", "i", "i", "k", "l", "m", "n", "o", "p", "r", "s", "t", "u", "f", "h", "ts", "tş", "ş", "ştş", "", "y", "", "e", "iu", "ia", "ä", "ğ", "q", "ñ", "ö", "ū", "ü", "h", "ı"),
            "uk" to cyr("абвгґдеєжзиіїйклмнопрстуфхцчшщьюя", "a", "b", "v", "h", "g", "d", "e", "ie", "zh", "z", "y", "i", "i", "i", "k", "l", "m", "n", "o", "p", "r", "s", "t", "u", "f", "kh", "ts", "ch", "sh", "shch", "", "iu", "ia"),
            // е ё ю я are j or i plus this vowel.
            "be" to cyr("абвгґдеёжзійклмнопрстуўфхцчшыэюя", "a", "b", "v", "h", "g", "d", "e", "o", "ž", "z", "i", "j", "k", "l", "m", "n", "o", "p", "r", "s", "t", "u", "ŭ", "f", "ch", "c", "č", "š", "y", "e", "u", "a"),
            "sr" to cyr("абвгдђежзијклљмнњопрстћуфхцчџш", "a", "b", "v", "g", "d", "đ", "e", "ž", "z", "i", "j", "k", "l", "lj", "m", "n", "nj", "o", "p", "r", "s", "t", "ć", "u", "f", "h", "c", "č", "dž", "š"),
            "mk" to cyr("абвгдѓежзѕијклљмнњопрстќуфхцчџш", "a", "b", "v", "g", "d", "gj", "e", "ž", "z", "dz", "i", "j", "k", "l", "lj", "m", "n", "nj", "o", "p", "r", "s", "t", "kj", "u", "f", "h", "c", "č", "dž", "š"),
            "bg" to cyr("абвгдежзийклмнопрстуфхцчшщъьюя", "a", "b", "v", "g", "d", "e", "zh", "z", "i", "y", "k", "l", "m", "n", "o", "p", "r", "s", "t", "u", "f", "h", "ts", "ch", "sh", "sht", "a", "y", "yu", "ya"),
        )
        private val UK_INITIAL = mapOf('є' to "ye", 'ї' to "yi", 'й' to "y", 'ю' to "yu", 'я' to "ya")
        private val POLISH = cyr(CYR, "a", "b", "w", "g", "d", "e", "io", "ż", "z", "i", "j", "k", "ł", "m", "n", "o", "p", "r", "s", "t", "u", "f", "ch", "c", "cz", "sz", "szcz", "", "y", "", "e", "iu", "ia", "i", "ji", "je", "g", "u")
    }
}
