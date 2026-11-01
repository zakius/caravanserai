package roma.core

import kotlinx.serialization.Serializable

@Serializable
class Named(val id: String?, val name: Text)

/** A song with every name already romanized. Built once, when the song enters the app. */
@Serializable
class Song(
    val id: String,
    val title: Text,
    val artists: List<Named>,
    val album: Named? = null,
    val durationSec: Int = 0,
    val thumb: String? = null,
    /** A music video rather than an audio track. */
    val video: Boolean = false,
    /** Why radio picked it, if known ("same artist"). */
    val reason: String? = null,
) {
    val artistLatin get() = artists.joinToString(", ") { it.name.latin }
    val artistOriginal get() = artists.joinToString(", ") { it.name.original }
    val artistText get() = Text.join(artists.map { it.name }, ", ")
    val firstArtistId get() = artists.firstOrNull { it.id != null }?.id

    fun withReason(r: String?) = Song(id, title, artists, album, durationSec, thumb, video, r)
}

/** Redo the Latin forms, e.g. after an override or a scheme change. */
fun Romanizer.again(s: Song): Song {
    val artistNames = s.artists.map { it.name.original }
    val albumName = s.album?.name?.original
    val title = text(s.title.original, artistNames + listOfNotNull(albumName), s.id, s.firstArtistId, titleOf = true)
    val artists = s.artists.map { a -> Named(a.id, text(a.name.original, listOf(s.title.original) + listOfNotNull(albumName), s.id, a.id)) }
    val album = s.album?.let { Named(it.id, text(it.name.original, listOf(s.title.original) + artistNames, s.id, s.firstArtistId, titleOf = true)) }
    return Song(s.id, title, artists, album, s.durationSec, s.thumb, s.video, s.reason)
}

/**
 * Local search: the query matches the original or the Latin form, ignoring case, accents (ō = o) and spaces.
 * Small typos pass: one in a query of 5 or more letters, two from 9. Returns how many typos it took, or null.
 */
fun matchDistance(song: Song, query: String): Int? =
    matchDistance(listOf(song.title.original, song.title.latin, song.artistOriginal, song.artistLatin, song.artistLatin + song.title.latin), query)

/** [matchDistance] against any names: the query matches one of [texts]. */
fun matchDistance(texts: List<String>, query: String): Int? {
    val q = fold(query)
    if (q.isEmpty()) return null
    val allowed = when { q.length >= 9 -> 2; q.length >= 5 -> 1; else -> 0 }
    return texts.minOf { substringDistance(q, fold(it)) }.takeIf { it <= allowed }
}

fun matches(song: Song, query: String) = matchDistance(song, query) != null

// Fewest edits that turn q into some substring of text (Sellers' algorithm).
private fun substringDistance(q: String, text: String): Int {
    var prev = IntArray(text.length + 1) // row 0: q is empty, so any start in text costs nothing
    for (i in 1..q.length) {
        val row = IntArray(text.length + 1)
        row[0] = i
        for (j in 1..text.length) row[j] = minOf(prev[j - 1] + if (q[i - 1] == text[j - 1]) 0 else 1, prev[j] + 1, row[j - 1] + 1)
        prev = row
    }
    return prev.min()
}

private fun fold(s: String) = Romanizer.stripMarks(s).lowercase().replace(Regex("[\\s'’-]+"), "")

/** A card on home, search, library or artist pages: a song, album, playlist or artist. */
@Serializable
class Item(val kind: String, val id: String, val title: Text, val subtitle: Text, val thumb: String?, val song: Song? = null)

/** Dotted versions compared number by number: 0.1.10 is newer than 0.1.9. */
fun newerVersion(a: String, b: String): Boolean {
    val x = a.split('.').map { it.toIntOrNull() ?: 0 }
    val y = b.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(x.size, y.size)) {
        val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
        if (d != 0) return d > 0
    }
    return false
}

/**
 * The latest release's version at a GitHub [releases] page ("0.1.55"), from where its "latest" link redirects: one
 * HEAD request, no API token. Null when the check fails. Blocking.
 */
fun latestRelease(releases: String): String? = runCatching {
    val c = java.net.URI("$releases/latest").toURL().openConnection() as java.net.HttpURLConnection
    c.instanceFollowRedirects = false
    c.requestMethod = "HEAD"
    c.connectTimeout = 15_000
    c.readTimeout = 15_000
    try { c.getHeaderField("Location") } finally { c.disconnect() }
}.getOrNull()?.substringAfter("/tag/v", "")?.takeIf { it.isNotEmpty() }
