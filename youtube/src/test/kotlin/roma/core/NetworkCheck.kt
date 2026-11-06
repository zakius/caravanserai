package roma.core

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.URI

// A check against the live services: ./gradlew :youtube:networkCheck
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

    // The fallback alone, as when YouTube refuses the direct path's client. The same song goes through InnerTubeX and
    // is read at the start and past 1 MB, where a stream that needed a PoToken would be cut off. The JVM has no
    // PoToken minter (it needs a WebView), so this exercises InnerTubeX's token-free clients only.
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
    // An age-restricted song is refused signed out. With ROMA_COOKIE set (a signed-in music.youtube.com cookie; never
    // commit or log it), InnerTubeX retries with the account. Without a PoToken only token-free clients can answer.
    val cookie = System.getenv("ROMA_COOKIE")?.takeIf { it.isNotBlank() }
    if (cookie != null) YouTube.cookie = cookie
    val age = runCatching { InnerTubeXStreams.audio("x8VYWazR5mE") }
    val signedIn = if (cookie != null) "signed in: " else "signed out: "
    val ageResult = age.fold({ "${it.mimeType} ${it.bitrate} bps" }, { "refused: ${it.message}" })
    println("fallback, age-restricted x8VYWazR5mE, $signedIn$ageResult")
    YouTube.cookie = null

    // Comments, two pages, and a thread of replies.
    val comments = Comments.of(song.id)
    val secondPageSize = comments.next?.invoke()?.comments?.size
    val firstComment = comments.comments.firstOrNull()?.text?.take(60)
    println("comments: ${comments.comments.size}, next page $secondPageSize, first: $firstComment")
    check(comments.comments.isNotEmpty())
    val replies = comments.comments.first { it.thread != null }.thread!!()
    val firstReply = replies.comments.firstOrNull()?.text?.take(60)
    println("replies: ${replies.comments.size}, more ${replies.next != null}, first: $firstReply")
    check(replies.comments.isNotEmpty() && replies.comments.all { it.thread == null })

    // Lyrics providers, each on its own, with the song's original metadata, then the rule that picks one.
    for (query in listOf("YOASOBI 夜に駆ける", "米津玄師 Lemon", "The Weeknd Blinding Lights", "周杰倫 晴天", "BTS Spring Day")) {
        val s = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrThrow().items.first() as SongItem
        val q = LyricsQuery(s.id, s.title, s.artists.joinToString { it.name }, s.album?.name, s.duration ?: 0)
        println("\n${s.id} ${s.title} / ${q.artist}")
        val ytm = runCatching { YouTubeLyrics.fetch(q) }.getOrNull()
        for (p in lyricsProviders) {
            val l = runCatching { p.fetch(q) }.onFailure { println("  ${p.name}: failed: ${it.message}") }.getOrNull()
            var sim = ""
            if (l != null && ytm != null && p != YouTubeLyrics) {
                val providerText = l.lines.joinToString("\n") { it.text }
                val ytmText = ytm.lines.joinToString("\n") { it.text }
                sim = " sim=%.2f".format(similarity(providerText, ytmText, jvmIcu))
            }
            if (l == null) {
                println("  ${p.name}: none")
                continue
            }
            val tr = translationLanguage(l.lines.mapNotNull { it.translation })
            val first = l.lines.firstOrNull { it.text.isNotBlank() }?.text
            println("  ${p.name}: ${l.source}, ${l.lines.size} lines, synced=${l.synced}, word=${l.wordLevel}$sim, translation=$tr, first=$first")
        }
        val r = LyricsProviders.find(q, lyricsProviders, jvmIcu, reference = YouTubeLyrics)
        val translations = r.result?.translations?.map { "${it.source}:${it.language}" }
        println("  -> rule ${r.result?.rule}: ${r.result?.lyrics?.source}, complete=${r.complete}, translations=$translations")
        // The opt-in machine translation, into Polish, which no provider has.
        val chosen = r.result?.lyrics ?: continue
        val pl = GoogleTranslate.layer(chosen, "pl")
        if (pl == null) {
            println("  Google pl: same language")
        } else {
            val translated = pl.lines.count { it.translation != null }
            val firstTranslation = pl.lines.firstOrNull { it.translation != null }?.translation
            println("  Google pl: $translated/${pl.lines.size} lines, first=$firstTranslation")
        }
        check(pl != null && pl.lines.count { it.translation != null } > pl.lines.size / 2)
    }
    val english = Lyrics("t", listOf(Line(0, "I've been on my own for long enough")), true)
    check(GoogleTranslate.layer(english, "en") == null) // already English
}
