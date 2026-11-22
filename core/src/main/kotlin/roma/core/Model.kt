package roma.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

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
    @Transient private var cachedArtistLatin: String? = null
    @Transient private var cachedArtistOriginal: String? = null
    @Transient private var cachedArtistText: Text? = null
    @Transient private var cachedFirstArtistId: String? = null
    @Transient private var firstArtistIdChecked: Boolean = false

    val artistLatin: String
        get() = cachedArtistLatin ?: when (artists.size) {
            0 -> ""
            1 -> artists[0].name.latin
            else -> artists.joinToString(", ") { it.name.latin }
        }.also { cachedArtistLatin = it }

    val artistOriginal: String
        get() = cachedArtistOriginal ?: when (artists.size) {
            0 -> ""
            1 -> artists[0].name.original
            else -> artists.joinToString(", ") { it.name.original }
        }.also { cachedArtistOriginal = it }

    val artistText: Text
        get() = cachedArtistText ?: when (artists.size) {
            1 -> artists[0].name
            else -> Text.join(artists.map { it.name }, ", ")
        }.also { cachedArtistText = it }

    val firstArtistId: String?
        get() {
            if (!firstArtistIdChecked) {
                cachedFirstArtistId = artists.firstOrNull { it.id != null }?.id
                firstArtistIdChecked = true
            }
            return cachedFirstArtistId
        }

    fun withReason(r: String?) = Song(id, title, artists, album, durationSec, thumb, video, r)
}

/** Redo the Latin forms, e.g. after an override or a scheme change. */
fun Romanizer.again(s: Song): Song {
    val artistNames = s.artists.map { it.name.original }
    val albumName = s.album?.name?.original
    val titleContext = artistNames + listOfNotNull(albumName)
    val title = text(s.title.original, titleContext, s.id, s.firstArtistId, titleOf = true, served = s.title.served)
    val artists = s.artists.map { a ->
        val artistContext = listOf(s.title.original) + listOfNotNull(albumName)
        Named(a.id, text(a.name.original, artistContext, s.id, a.id, served = a.name.served))
    }
    val oldAlbum = s.album
    var album: Named? = null
    if (oldAlbum != null) {
        val albumContext = listOf(s.title.original) + artistNames
        val name = text(oldAlbum.name.original, albumContext, s.id, s.firstArtistId, titleOf = true, served = oldAlbum.name.served)
        album = Named(oldAlbum.id, name)
    }
    return Song(s.id, title, artists, album, s.durationSec, s.thumb, s.video, s.reason)
}

/**
 * A folded search query and its typo budget, built once per search instead of once per song.
 * Small typos pass: one in a query of 5 or more letters, two from 9.
 */
class PreparedQuery(val q: String, val allowed: Int)

fun prepareQuery(query: String): PreparedQuery? {
    val q = foldSearch(query)
    if (q.isEmpty()) return null
    val allowed = when {
        q.length >= 9 -> 2
        q.length >= 5 -> 1
        else -> 0
    }
    return PreparedQuery(q, allowed)
}

/**
 * Local search: the query matches the original or the Latin form, ignoring case, accents (ō = o) and spaces.
 * Small typos pass: one in a query of 5 or more letters, two from 9. Returns how many typos it took, or null.
 */
fun matchDistance(song: Song, query: String): Int? =
    matchDistance(listOf(song.title.original, song.title.latin, song.artistOriginal, song.artistLatin, song.artistLatin + song.title.latin), query)

/** [matchDistance] against any names: the query matches one of [texts]. */
fun matchDistance(texts: List<String>, query: String): Int? {
    val p = prepareQuery(query) ?: return null
    var best = p.allowed + 1
    for (t in texts) {
        val d = substringDistance(p.q, foldSearch(t), best - 1)
        if (d < best) {
            best = d
            if (best == 0) return 0
        }
    }
    return if (best <= p.allowed) best else null
}

/** [matchDistance] against already-folded strings ([foldSearch]), for searching an index repeatedly. */
fun matchFolded(foldedTexts: Array<String>, query: PreparedQuery): Int? {
    var best = query.allowed + 1
    for (t in foldedTexts) {
        val d = substringDistance(query.q, t, best - 1)
        if (d < best) {
            best = d
            if (best == 0) return 0
        }
    }
    return if (best <= query.allowed) best else null
}

fun matches(song: Song, query: String) = matchDistance(song, query) != null

// Fewest edits that turn q into some substring of text (Sellers' algorithm), bounded by [limit].
// Returns a value > [limit] as soon as no substring can match within [limit].
private fun substringDistance(q: String, text: String, limit: Int): Int {
    if (limit < 0) return 1
    if (text.contains(q)) return 0
    if (limit == 0) return 1
    val m = q.length
    val n = text.length
    if (n == 0) return m
    // Row 0 is all zeros: with q empty, any start in text costs nothing.
    var prev = IntArray(n + 1)
    var curr = IntArray(n + 1)
    for (i in 1..m) {
        val qc = q[i - 1]
        curr[0] = i
        var rowMin = i
        for (j in 1..n) {
            val sub = prev[j - 1] + if (qc == text[j - 1]) 0 else 1
            val del = prev[j] + 1
            val ins = curr[j - 1] + 1
            val v = if (sub <= del) (if (sub <= ins) sub else ins) else (if (del <= ins) del else ins)
            curr[j] = v
            if (v < rowMin) rowMin = v
        }
        if (rowMin > limit) return limit + 1
        val tmp = prev
        prev = curr
        curr = tmp
    }
    var min = prev[0]
    for (j in 1..n) if (prev[j] < min) min = prev[j]
    return min
}

fun foldSearch(s: String): String {
    if (s.isEmpty()) return ""
    val stripped = Romanizer.stripMarks(s)
    val sb = StringBuilder(stripped.length)
    for (i in 0 until stripped.length) {
        val c = stripped[i]
        if (c == '\'' || c == '\u2019' || c == '-' || c.isWhitespace()) continue
        sb.append(c.lowercaseChar())
    }
    return sb.toString()
}

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
fun latestRelease(releases: String): String? {
    val location = runCatching {
        val c = java.net.URI("$releases/latest").toURL().openConnection() as java.net.HttpURLConnection
        c.instanceFollowRedirects = false
        c.requestMethod = "HEAD"
        c.connectTimeout = 15_000
        c.readTimeout = 15_000
        try {
            c.getHeaderField("Location")
        } finally {
            c.disconnect()
        }
    }.getOrNull() ?: return null
    val version = location.substringAfter("/tag/v", "")
    return if (version.isEmpty()) null else version
}
