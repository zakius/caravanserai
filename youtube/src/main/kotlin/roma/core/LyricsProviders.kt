package roma.core

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.WatchEndpoint
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.util.Base64
import java.util.zip.Inflater
import kotlin.math.abs

// The lyrics providers besides LRCLIB, which lives in core: YouTube Music's own, which also serves as the reference,
// and the unofficial ones.

/** Every provider, in the default order. */
val lyricsProviders: List<LyricsProvider> = listOf(NetEase, KuGou, LrcLib, LyricsPlus, YouTubeLyrics)

/** A translation line may start up to this far from its lyric line and still belong to it. */
private const val TRANSLATION_TOLERANCE_MS = 300L

/**
 * Parses KuGou KRC. Each line is `[lineStart,lineDur]` followed by `<offset,dur,0>word` entries, with word offsets
 * relative to the line start.
 */
fun parseKrc(text: String): List<Line> {
    val head = Regex("""^\[(\d+),(\d+)]""")
    val word = Regex("""<(\d+),(\d+),\d+>([^<]*)""")
    val translations = krcTranslations(text)
    val lines = ArrayList<Line>()
    for (raw in text.lines()) {
        val h = head.find(raw) ?: continue
        val start = h.groupValues[1].toLong()
        val matches = word.findAll(raw.substring(h.range.last + 1))
        val words = matches.map { m ->
            val wordStart = start + m.groupValues[1].toLong()
            Word(wordStart, wordStart + m.groupValues[2].toLong(), m.groupValues[3])
        }.filter { it.text.isNotEmpty() }.toList()
        val lineText = words.joinToString("") { it.text }.trim()
        lines.add(Line(start, lineText, words, translations.getOrNull(lines.size)))
    }
    return lines
}

/** The translations in a KRC's base64 `[language:…]` tag, one per lyric line, null for a blank one. */
private fun krcTranslations(text: String): List<String?> {
    val tag = Regex("""\[language:([^\]]*)]""").find(text) ?: return emptyList()
    return runCatching {
        val decoded = String(Base64.getDecoder().decode(tag.groupValues[1]))
        val json = Json.parseToJsonElement(decoded).jsonObject
        val contents = json["content"]!!.jsonArray.map { it.jsonObject }
        val content = contents.firstOrNull { it["type"]?.jsonPrimitive?.content == "1" }
        val rows = content!!["lyricContent"]!!.jsonArray
        rows.map { row ->
            val rowText = row.jsonArray.joinToString("") { it.jsonPrimitive.content }
            rowText.ifBlank { null }
        }
    }.getOrDefault(emptyList())
}

/**
 * Parses NetEase YRC. Each line is `[lineStart,lineDur]` followed by `(wordStart,dur,0)word` entries, with absolute
 * word starts.
 */
fun parseYrc(text: String): List<Line> {
    val head = Regex("""^\[(\d+),(\d+)]""")
    // A word runs to the next stamp, so "(oh)" in the lyrics stays part of its word.
    val word = Regex("""\((\d+),(\d+),\d+\)(.*?)(?=\(\d+,\d+,\d+\)|$)""")
    val lines = ArrayList<Line>()
    for (raw in text.lines()) {
        val h = head.find(raw) ?: continue
        val matches = word.findAll(raw.substring(h.range.last + 1))
        val words = matches.map { m ->
            val wordStart = m.groupValues[1].toLong()
            Word(wordStart, wordStart + m.groupValues[2].toLong(), m.groupValues[3])
        }.filter { it.text.isNotEmpty() }.toList()
        val lineText = words.joinToString("") { it.text }.trim()
        lines.add(Line(h.groupValues[1].toLong(), lineText, words))
    }
    return lines
}

/** Adds translations from a second LRC with the same timestamps. */
fun withTranslation(lines: List<Line>, translationLrc: String?): List<Line> {
    if (translationLrc.isNullOrBlank()) return lines
    val byTime = parseLrc(translationLrc).filter { it.text.isNotBlank() }.associate { it.startMs to it.text }
    return lines.map { l ->
        val t = byTime[l.startMs]
            ?: byTime.entries.firstOrNull { entry -> abs(entry.key - l.startMs) < TRANSLATION_TOLERANCE_MS }?.value
        Line(l.startMs, l.text, l.words, t ?: l.translation)
    }
}

/** NetEase Cloud Music: word timing (YRC) for much C/J-pop, line LRC and Chinese translations otherwise. Unofficial API. */
object NetEase : LyricsProvider {
    override val id = "netease"
    override val name = "NetEase"
    private val referer = "Referer" to "https://music.163.com/"

    override fun fetch(q: LyricsQuery): Lyrics? {
        val searchUrl = "https://music.163.com/api/cloudsearch/pc?s=${enc(q.title + " " + q.artist)}&type=1&limit=10"
        val body = lyricsGet(searchUrl, referer)
        val result = Json.parseToJsonElement(body).obj()?.get("result")
        val songs = result.obj()?.get("songs").arr()?.mapNotNull { it.obj() } ?: return null
        val sameTitleSongs = songs.filter { sameTitle(it["name"].str() ?: "", q.title) }
        val song = closest(sameTitleSongs, q.durationSec) { (it["dt"].str()?.toDoubleOrNull() ?: 0.0) / 1000 } ?: return null
        val id = song["id"].str() ?: return null
        val lyricsBody = lyricsGet("https://music.163.com/api/song/lyric?id=$id&lv=-1&tv=-1&yv=-1", referer)
        val lyr = Json.parseToJsonElement(lyricsBody).obj() ?: return null
        val translation = lyr["tlyric"].obj()?.get("lyric").str()
        val yrc = lyr["yrc"].obj()?.get("lyric").str()
        if (!yrc.isNullOrBlank()) {
            val lines = parseYrc(yrc)
            if (lines.isNotEmpty()) return Lyrics(name, withTranslation(lines, translation), true)
        }
        val lrc = lyr["lrc"].obj()?.get("lyric").str() ?: return null
        // Drops credit lines such as "作词 : X".
        val lines = parseLrc(lrc).filter { !it.text.contains(" : ") && !it.text.contains("：") }
        return if (lines.isEmpty()) null else Lyrics(name, withTranslation(lines, translation), true)
    }
}

/** KuGou: KRC word timing, encrypted with a well-known key. Unofficial API. */
object KuGou : LyricsProvider {
    override val id = "kugou"
    override val name = "KuGou"
    private val key = intArrayOf(64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 206, 210, 110, 105)

    /** A KRC file starts with the 4-byte magic "krc1"; the encrypted data follows it. */
    private const val HEADER_BYTES = 4

    override fun fetch(q: LyricsQuery): Lyrics? {
        val keyword = enc("${q.artist} - ${q.title}")
        val search = lyricsGet("https://mobileservice.kugou.com/api/v3/search/song?version=9108&plat=0&pagesize=8&showtype=0&keyword=$keyword")
        val data = Json.parseToJsonElement(search).obj()?.get("data")
        val songs = data.obj()?.get("info").arr()?.mapNotNull { it.obj() } ?: return null
        val sameTitleSongs = songs.filter { sameTitle(it["songname"].str() ?: "", q.title) }
        val song = closest(sameTitleSongs, q.durationSec) { it["duration"].str()?.toDoubleOrNull() ?: 0.0 } ?: return null
        val hash = song["hash"].str() ?: return null
        val candidatesUrl = "https://krcs.kugou.com/search?ver=1&man=yes&client=mobi&keyword=$keyword" +
            "&duration=${q.durationSec * 1000}&hash=$hash"
        val cands = lyricsGet(candidatesUrl)
        val cand = Json.parseToJsonElement(cands).obj()?.get("candidates").arr()?.firstOrNull().obj() ?: return null
        val downloadUrl = "https://krcs.kugou.com/download?ver=1&man=yes&client=pc&fmt=krc&charset=utf8" +
            "&id=${cand["id"].str()}&accesskey=${cand["accesskey"].str()}"
        val dl = lyricsGet(downloadUrl)
        val content = Json.parseToJsonElement(dl).obj()?.get("content").str() ?: return null
        val lines = parseKrc(decrypt(content))
        return if (lines.isEmpty()) null else Lyrics(name, lines, true)
    }

    fun decrypt(base64: String): String {
        val raw = Base64.getDecoder().decode(base64)
        val data = ByteArray(raw.size - HEADER_BYTES) { i ->
            (raw[i + HEADER_BYTES].toInt() xor key[i % key.size]).toByte()
        }
        val inflater = Inflater()
        inflater.setInput(data)
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buf, 0, n)
            }
        } finally {
            inflater.end()
        }
        // decodeToString, because toString(Charset) needs Android API 33 and core isn't lint-checked for Android.
        // The text may start with a byte order mark.
        return out.toByteArray().decodeToString().removePrefix("﻿")
    }
}

/**
 * LyricsPlus, the YouLy+ backend: free, no key. Its server searches Apple Music, QQ Music, Musixmatch and others by
 * title, artist and duration and sends the best as JSON: word timing as syllables, sometimes an English translation
 * per line. The source names the server's own source ("LyricsPlus · Apple"). 404 means nothing found.
 */
object LyricsPlus : LyricsProvider {
    override val id = "lyricsplus"
    override val name = "LyricsPlus"

    // ponytail: only the main server is asked. YouLy+ lists mirrors (lyricsplus.atomix.one, …) to try if it goes down.
    override fun fetch(q: LyricsQuery): Lyrics? {
        val url = "https://lyricsplus.prjktla.my.id/v2/lyrics/get?title=${enc(q.title)}&artist=${enc(q.artist)}&duration=${q.durationSec}"
        val (code, body) = httpCall(url, headers = mapOf("User-Agent" to "Mozilla/5.0 roma"), timeoutMs = 12_000)
        if (code == 404) return null
        if (code != 200) throw IOException("HTTP $code")
        return parse(body, q)
    }

    fun parse(body: String, q: LyricsQuery): Lyrics? {
        val root = Json.parseToJsonElement(body).obj() ?: return null
        val meta = root["metadata"].obj()
        // The server names the song only for some sources. When it does, it must be this one.
        val title = meta?.get("title").str()
        if (title != null && !sameTitle(title, q.title)) return null
        fun ms(e: JsonElement?) = e.str()?.toDoubleOrNull()?.toLong() ?: 0
        val rows = root["lyrics"].arr()?.mapNotNull { it.obj() } ?: return null
        val lines = rows.map { r ->
            val syllables = r["syllabus"].arr().orEmpty().mapNotNull { it.obj() }
            val words = syllables.map { s ->
                val start = ms(s["time"])
                Word(start, start + ms(s["duration"]), s["text"].str() ?: "")
            }.filter { it.text.isNotEmpty() }
            val text = if (words.isEmpty()) r["text"].str().orEmpty().trim() else words.joinToString("") { it.text }.trim()
            val translation = r["translation"].obj()?.get("text").str()
            Line(ms(r["time"]), text, words, if (translation.isNullOrBlank()) null else translation)
        }
        // The server matched the song on its own, so its timing must fit this recording. The lyrics must end at most
        // 45 s after the song, and no earlier than its middle or 90 s before its end, whichever comes first. These are
        // LastWave's bounds (LyricsRepository.plausibleDuration). Songs under a minute, or of unknown length, aren't
        // checked.
        val end = lines.maxOfOrNull { l -> l.words.lastOrNull()?.endMs ?: l.startMs } ?: return null
        val song = q.durationSec * 1000L
        val earliestEnd = minOf(song / 2, song - 90_000)
        val latestEnd = song + 45_000
        if (song >= 60_000 && end > 0 && end !in earliestEnd..latestEnd) return null
        val serverSource = meta?.get("source").str()
        val source = if (serverSource == null) name else "$name · $serverSource"
        return Lyrics(source, lines, lines.any { it.startMs > 0 })
    }
}

/**
 * YouTube Music's own lyrics, as the Android app gets them. The lyrics browse ID through the ANDROID_MUSIC client
 * gives line timing (timedLyricsData with cueRange) when there is any; otherwise the web client gives plain text.
 * The source names YTM's own source ("Source: LyricFind").
 */
object YouTubeLyrics : LyricsProvider {
    override val id = "youtube"
    override val name = "YouTube Music"
    private val mobile = arrayOf("User-Agent" to "com.google.android.apps.youtube.music/7.21.50 (Linux; U; Android 14) gzip")

    override fun fetch(q: LyricsQuery): Lyrics? = runBlocking {
        val next = YouTube.next(WatchEndpoint(videoId = q.videoId)).getOrThrow()
        val endpoint = next.lyricsEndpoint ?: return@runBlocking null
        val timedLyrics = timed(endpoint.browseId)
        if (timedLyrics != null) return@runBlocking timedLyrics
        val text = YouTube.lyrics(endpoint).getOrThrow() ?: return@runBlocking null
        Lyrics(name, text.lines().map { Line(0, it) }, false)
    }

    /** Null when the mobile response has no timedLyricsModel; plain lines when it has text without cueRange. */
    fun timed(browseId: String): Lyrics? {
        val body = """{"context":{"client":{"clientName":"ANDROID_MUSIC","clientVersion":"7.21.50","androidSdkVersion":34,"hl":"en"}},"browseId":"$browseId"}"""
        val url = "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false"
        val response = lyricsGet(url, *mobile, "Content-Type" to "application/json", body = body)
        val root = Json.parseToJsonElement(response).obj()
        val path = listOf("contents", "elementRenderer", "newElement", "type", "componentType", "model", "timedLyricsModel", "lyricsData")
        var node: JsonElement? = root
        for (key in path) node = node.obj()?.get(key)
        val data = node.obj() ?: return null
        val rows = data["timedLyricsData"].arr()?.mapNotNull { it.obj() } ?: return null
        val sourceMessage = data["sourceMessage"].str()
        val source = if (sourceMessage == null) name else "$name · $sourceMessage"
        val synced = rows.all { it["cueRange"] != null }
        val lines = rows.map { row ->
            var startMs = 0L
            if (synced) startMs = row["cueRange"].obj()?.get("startTimeMilliseconds").str()?.toLongOrNull() ?: 0L
            Line(startMs, row["lyricLine"].str() ?: "")
        }
        return if (lines.isEmpty()) null else Lyrics(source, lines, synced)
    }
}
