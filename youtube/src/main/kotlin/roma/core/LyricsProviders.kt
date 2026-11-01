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

// roma's lyrics providers besides LRCLIB (core): YouTube Music's own, the reference, and the unofficial ones.

/** Every provider, in the default order. */
val lyricsProviders: List<LyricsProvider> = listOf(NetEase, KuGou, LrcLib, LyricsPlus, YouTubeLyrics)

/** KuGou KRC: [lineStart,lineDur]<offset,dur,0>word, offsets relative to the line. */
fun parseKrc(text: String): List<Line> {
    val head = Regex("""^\[(\d+),(\d+)]""")
    val word = Regex("""<(\d+),(\d+),\d+>([^<]*)""")
    val translations = krcTranslations(text)
    val lines = ArrayList<Line>()
    for (raw in text.lines()) {
        val h = head.find(raw) ?: continue
        val start = h.groupValues[1].toLong()
        val words = word.findAll(raw.substring(h.range.last + 1)).map {
            val off = start + it.groupValues[1].toLong()
            Word(off, off + it.groupValues[2].toLong(), it.groupValues[3])
        }.filter { it.text.isNotEmpty() }.toList()
        lines.add(Line(start, words.joinToString("") { it.text }.trim(), words, translations.getOrNull(lines.size)))
    }
    return lines
}

private fun krcTranslations(text: String): List<String?> {
    val tag = Regex("""\[language:([^\]]*)]""").find(text) ?: return emptyList()
    return runCatching {
        val json = Json.parseToJsonElement(String(Base64.getDecoder().decode(tag.groupValues[1]))).jsonObject
        val content = json["content"]!!.jsonArray.map { it.jsonObject }.firstOrNull { it["type"]?.jsonPrimitive?.content == "1" }
        content!!["lyricContent"]!!.jsonArray.map { row -> row.jsonArray.joinToString("") { it.jsonPrimitive.content }.ifBlank { null } }
    }.getOrDefault(emptyList())
}

/** NetEase YRC: [lineStart,lineDur](wordStart,dur,0)word, word starts absolute. */
fun parseYrc(text: String): List<Line> {
    val head = Regex("""^\[(\d+),(\d+)]""")
    // A word runs to the next stamp, so "(oh)" in the lyrics stays part of its word.
    val word = Regex("""\((\d+),(\d+),\d+\)(.*?)(?=\(\d+,\d+,\d+\)|$)""")
    val lines = ArrayList<Line>()
    for (raw in text.lines()) {
        val h = head.find(raw) ?: continue
        val words = word.findAll(raw.substring(h.range.last + 1)).map {
            val s = it.groupValues[1].toLong()
            Word(s, s + it.groupValues[2].toLong(), it.groupValues[3])
        }.filter { it.text.isNotEmpty() }.toList()
        lines.add(Line(h.groupValues[1].toLong(), words.joinToString("") { it.text }.trim(), words))
    }
    return lines
}

/** Adds translations from a second LRC with the same timestamps. */
fun withTranslation(lines: List<Line>, translationLrc: String?): List<Line> {
    if (translationLrc.isNullOrBlank()) return lines
    val byTime = parseLrc(translationLrc).filter { it.text.isNotBlank() }.associate { it.startMs to it.text }
    return lines.map { l ->
        val t = byTime[l.startMs] ?: byTime.entries.firstOrNull { abs(it.key - l.startMs) < 300 }?.value
        Line(l.startMs, l.text, l.words, t ?: l.translation)
    }
}

/** NetEase Cloud Music: word timing (YRC) for much C/J-pop, line LRC and Chinese translations otherwise. Unofficial API. */
object NetEase : LyricsProvider {
    override val id = "netease"
    override val name = "NetEase"
    private val referer = "Referer" to "https://music.163.com/"

    override fun fetch(q: LyricsQuery): Lyrics? {
        val body = lyricsGet("https://music.163.com/api/cloudsearch/pc?s=${enc(q.title + " " + q.artist)}&type=1&limit=10", referer)
        val songs = Json.parseToJsonElement(body).obj()?.get("result").obj()?.get("songs").arr()?.mapNotNull { it.obj() } ?: return null
        val song = closest(songs.filter { sameTitle(it["name"].str() ?: "", q.title) }, q.durationSec) { (it["dt"].str()?.toDoubleOrNull() ?: 0.0) / 1000 } ?: return null
        val id = song["id"].str() ?: return null
        val lyr = Json.parseToJsonElement(lyricsGet("https://music.163.com/api/song/lyric?id=$id&lv=-1&tv=-1&yv=-1", referer)).obj() ?: return null
        val translation = lyr["tlyric"].obj()?.get("lyric").str()
        val yrc = lyr["yrc"].obj()?.get("lyric").str()
        if (!yrc.isNullOrBlank()) {
            val lines = parseYrc(yrc)
            if (lines.isNotEmpty()) return Lyrics(name, withTranslation(lines, translation), true)
        }
        val lrc = lyr["lrc"].obj()?.get("lyric").str() ?: return null
        val lines = parseLrc(lrc).filter { !it.text.contains(" : ") && !it.text.contains("：") } // credits like "作词 : X"
        return if (lines.isEmpty()) null else Lyrics(name, withTranslation(lines, translation), true)
    }
}

/** KuGou: KRC word timing, encrypted with a well-known key. Unofficial API. */
object KuGou : LyricsProvider {
    override val id = "kugou"
    override val name = "KuGou"
    private val key = intArrayOf(64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 206, 210, 110, 105)

    override fun fetch(q: LyricsQuery): Lyrics? {
        val keyword = enc("${q.artist} - ${q.title}")
        val search = lyricsGet("https://mobileservice.kugou.com/api/v3/search/song?version=9108&plat=0&pagesize=8&showtype=0&keyword=$keyword")
        val songs = Json.parseToJsonElement(search).obj()?.get("data").obj()?.get("info").arr()?.mapNotNull { it.obj() } ?: return null
        val song = closest(songs.filter { sameTitle(it["songname"].str() ?: "", q.title) }, q.durationSec) { it["duration"].str()?.toDoubleOrNull() ?: 0.0 } ?: return null
        val hash = song["hash"].str() ?: return null
        val cands = lyricsGet("https://krcs.kugou.com/search?ver=1&man=yes&client=mobi&keyword=$keyword&duration=${q.durationSec * 1000}&hash=$hash")
        val cand = Json.parseToJsonElement(cands).obj()?.get("candidates").arr()?.firstOrNull().obj() ?: return null
        val dl = lyricsGet("https://krcs.kugou.com/download?ver=1&man=yes&client=pc&fmt=krc&charset=utf8&id=${cand["id"].str()}&accesskey=${cand["accesskey"].str()}")
        val content = Json.parseToJsonElement(dl).obj()?.get("content").str() ?: return null
        val lines = parseKrc(decrypt(content))
        return if (lines.isEmpty()) null else Lyrics(name, lines, true)
    }

    fun decrypt(base64: String): String {
        val raw = Base64.getDecoder().decode(base64)
        val data = ByteArray(raw.size - 4) { i -> (raw[i + 4].toInt() xor key[i % key.size]).toByte() }
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
        // decodeToString, not toString(Charset): that one is API 33, and core isn't lint-checked for Android.
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

    // ponytail: the main server only; YouLy+ lists mirrors (lyricsplus.atomix.one, …) to try if it goes down
    override fun fetch(q: LyricsQuery): Lyrics? {
        val (code, body) = httpCall("https://lyricsplus.prjktla.my.id/v2/lyrics/get?title=${enc(q.title)}&artist=${enc(q.artist)}&duration=${q.durationSec}", headers = mapOf("User-Agent" to "Mozilla/5.0 roma"), timeoutMs = 12_000)
        if (code == 404) return null
        if (code != 200) throw IOException("HTTP $code")
        return parse(body, q)
    }

    fun parse(body: String, q: LyricsQuery): Lyrics? {
        val root = Json.parseToJsonElement(body).obj() ?: return null
        val meta = root["metadata"].obj()
        // The server names the song only for some sources; when it does, it must be this one.
        meta?.get("title").str()?.let { if (!sameTitle(it, q.title)) return null }
        fun ms(e: JsonElement?) = e.str()?.toDoubleOrNull()?.toLong() ?: 0
        val lines = root["lyrics"].arr()?.mapNotNull { it.obj() }?.map { r ->
            val words = r["syllabus"].arr().orEmpty().mapNotNull { it.obj() }
                .map { s -> ms(s["time"]).let { t -> Word(t, t + ms(s["duration"]), s["text"].str() ?: "") } }
                .filter { it.text.isNotEmpty() }
            val text = if (words.isEmpty()) r["text"].str().orEmpty().trim() else words.joinToString("") { it.text }.trim()
            Line(ms(r["time"]), text, words, r["translation"].obj()?.get("text").str()?.takeIf { it.isNotBlank() })
        } ?: return null
        // The server matched the song on its own: its timing must fit this recording, ending at most 45 s after it and
        // not before its middle with over 90 s left (LastWave's bounds, LyricsRepository.plausibleDuration).
        val end = lines.maxOfOrNull { l -> l.words.lastOrNull()?.endMs ?: l.startMs } ?: return null
        val song = q.durationSec * 1000L
        if (song >= 60_000 && end > 0 && end !in minOf(song / 2, song - 90_000)..song + 45_000) return null
        val source = meta?.get("source").str()?.let { "$name · $it" } ?: name
        return Lyrics(source, lines, lines.any { it.startMs > 0 })
    }
}

/**
 * YouTube Music's own lyrics, as the Android app gets them: the lyrics browse ID through the ANDROID_MUSIC client
 * gives line timing (timedLyricsData with cueRange) when there is any, otherwise the plain text from the web client.
 * The source names YTM's own source ("Source: LyricFind").
 */
object YouTubeLyrics : LyricsProvider {
    override val id = "youtube"
    override val name = "YouTube Music"
    private val mobile = arrayOf("User-Agent" to "com.google.android.apps.youtube.music/7.21.50 (Linux; U; Android 14) gzip")

    override fun fetch(q: LyricsQuery): Lyrics? = runBlocking {
        val next = YouTube.next(WatchEndpoint(videoId = q.videoId)).getOrThrow()
        val endpoint = next.lyricsEndpoint ?: return@runBlocking null
        timed(endpoint.browseId)?.let { return@runBlocking it }
        val text = YouTube.lyrics(endpoint).getOrThrow() ?: return@runBlocking null
        Lyrics(name, text.lines().map { Line(0, it) }, false)
    }

    /** Null when the mobile response has no timedLyricsModel; plain lines when it has text without cueRange. */
    fun timed(browseId: String): Lyrics? {
        val body = """{"context":{"client":{"clientName":"ANDROID_MUSIC","clientVersion":"7.21.50","androidSdkVersion":34,"hl":"en"}},"browseId":"$browseId"}"""
        val root = Json.parseToJsonElement(lyricsGet("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false", *mobile, "Content-Type" to "application/json", body = body)).obj()
        val data = listOf("contents", "elementRenderer", "newElement", "type", "componentType", "model", "timedLyricsModel", "lyricsData")
            .fold(root as JsonElement?) { e, k -> e.obj()?.get(k) }.obj() ?: return null
        val rows = data["timedLyricsData"].arr()?.mapNotNull { it.obj() } ?: return null
        val source = data["sourceMessage"].str()?.let { "$name · $it" } ?: name
        val synced = rows.all { it["cueRange"] != null }
        val lines = rows.map { Line(if (synced) it["cueRange"].obj()?.get("startTimeMilliseconds").str()?.toLongOrNull() ?: 0 else 0, it["lyricLine"].str() ?: "") }
        return if (lines.isEmpty()) null else Lyrics(source, lines, synced)
    }
}

