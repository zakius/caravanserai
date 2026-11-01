package roma.core

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.URI

// Step 0 check against the live services: ./gradlew :core:networkCheck
// Searches YouTube Music through innertube, resolves the first song through the direct path and reads 64 KB of audio,
// then resolves it again through the InnerTubeX fallback alone.
fun main() = runBlocking {
    YouTube.visitorData = YouTube.visitorData().getOrThrow() // as the app does before a stream lookup
    val result = YouTube.search("YOASOBI 夜に駆ける", YouTube.SearchFilter.FILTER_SONG).getOrThrow()
    val song = result.items.first() as SongItem
    println("innertube: ${song.id} ${song.title} / ${song.artists.joinToString { it.name }}")
    val t = System.currentTimeMillis()
    val audio = Streams.audio(song.id)
    check(Streams.audio("CXNv4_wQjKQ").url.isNotEmpty()) // Youngblood, refused for VISIONOS on some networks
    println("direct: ${audio.mimeType} ${audio.bitrate} bps in ${System.currentTimeMillis() - t} ms")
    val c = URI(audio.url).toURL().openConnection() as HttpURLConnection
    c.setRequestProperty("Range", "bytes=0-65535")
    val n = c.inputStream.use { it.readAllBytes().size }
    println("stream: HTTP ${c.responseCode}, read $n bytes")
    check(n > 1000)

    // The fallback alone (as when YouTube refuses the direct path's client): the same song through InnerTubeX, read at the
    // start and past 1 MB, where a stream that needed a PoToken would be cut off. The JVM has no PoToken minter (it
    // needs a WebView), so this exercises InnerTubeX's token-free clients only.
    val t0 = System.currentTimeMillis()
    val fb = InnerTubeXStreams.audio(song.id)
    println("fallback: ${fb.mimeType} ${fb.bitrate} bps in ${System.currentTimeMillis() - t0} ms")
    for (range in listOf("0-65535", "1500000-1565535")) {
        val f = URI(fb.url).toURL().openConnection() as HttpURLConnection
        f.setRequestProperty("Range", "bytes=$range")
        val got = f.inputStream.use { it.readAllBytes().size }
        println("fallback stream $range: HTTP ${f.responseCode}, read $got bytes")
        check(got > 1000)
    }
    // Age-restricted: refused signed out. With ROMA_COOKIE set (a signed-in music.youtube.com cookie, never commit
    // or log it) InnerTubeX retries with the account; without a PoToken only token-free clients can answer.
    val cookie = System.getenv("ROMA_COOKIE")?.takeIf { it.isNotBlank() }
    if (cookie != null) YouTube.cookie = cookie
    val age = runCatching { InnerTubeXStreams.audio("x8VYWazR5mE") }
    println("fallback, age-restricted x8VYWazR5mE, " + (if (cookie != null) "signed in: " else "signed out: ") +
        age.fold({ "${it.mimeType} ${it.bitrate} bps" }, { "refused: ${it.message}" }))
    YouTube.cookie = null

    // Comments, two pages, and a thread of replies.
    val comments = Comments.of(song.id)
    println("comments: ${comments.comments.size}, next page ${comments.next?.invoke()?.comments?.size}, first: ${comments.comments.firstOrNull()?.text?.take(60)}")
    check(comments.comments.isNotEmpty())
    val replies = comments.comments.first { it.thread != null }.thread!!()
    println("replies: ${replies.comments.size}, more ${replies.next != null}, first: ${replies.comments.firstOrNull()?.text?.take(60)}")
    check(replies.comments.isNotEmpty() && replies.comments.all { it.thread == null })

    // Lyrics providers, each on its own, with the song's original metadata, then the rule that picks one.
    for (query in listOf("YOASOBI 夜に駆ける", "米津玄師 Lemon", "The Weeknd Blinding Lights", "周杰倫 晴天", "BTS Spring Day")) {
        val s = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrThrow().items.first() as SongItem
        val q = LyricsQuery(s.id, s.title, s.artists.joinToString { it.name }, s.album?.name, s.duration ?: 0)
        println("\n${s.id} ${s.title} / ${q.artist}")
        val ytm = runCatching { YouTubeLyrics.fetch(q) }.getOrNull()
        for (p in lyricsProviders) {
            val l = runCatching { p.fetch(q) }.onFailure { println("  ${p.name}: failed: ${it.message}") }.getOrNull()
            val sim = if (l != null && ytm != null && p != YouTubeLyrics) " sim=%.2f".format(similarity(l.lines.joinToString("\n") { it.text }, ytm.lines.joinToString("\n") { it.text }, jvmIcu)) else ""
            val tr = l?.lines?.mapNotNull { it.translation }?.let { translationLanguage(it) }
            println("  ${p.name}: " + if (l == null) "none" else "${l.source}, ${l.lines.size} lines, synced=${l.synced}, word=${l.wordLevel}$sim, translation=$tr, first=${l.lines.firstOrNull { it.text.isNotBlank() }?.text}")
        }
        val r = LyricsProviders.find(q, lyricsProviders, jvmIcu, reference = YouTubeLyrics)
        println("  -> rule ${r.result?.rule}: ${r.result?.lyrics?.source}, complete=${r.complete}, translations=${r.result?.translations?.map { "${it.source}:${it.language}" }}")
        // The opt-in machine translation, into Polish: no provider has that language.
        val chosen = r.result?.lyrics ?: continue
        val pl = GoogleTranslate.layer(chosen, "pl")
        println("  Google pl: " + (pl?.let { t -> "${t.lines.count { it.translation != null }}/${t.lines.size} lines, first=${t.lines.firstOrNull { it.translation != null }?.translation}" } ?: "same language"))
        check(pl != null && pl.lines.count { it.translation != null } > pl.lines.size / 2)
    }
    check(GoogleTranslate.layer(Lyrics("t", listOf(Line(0, "I've been on my own for long enough")), true), "en") == null) // already English
}
