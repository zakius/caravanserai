package roma.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.math.abs

/**
 * Subsonic ids inside roma: "s." and the server's id, every character but A–Z a–z 0–9 _ - written as "." and two hex
 * digits per UTF-8 byte. No YouTube id holds a dot, and the result is a safe file name and URI host everywhere.
 */
object SubsonicIds {
    const val PREFIX = "s."

    fun of(id: String) = id.startsWith(PREFIX)

    fun encode(raw: String) = PREFIX + buildString {
        for (b in raw.toByteArray()) {
            val c = b.toInt().toChar()
            if (b >= 0 && (c.isLetterOrDigit() || c == '_' || c == '-')) append(c) else append('.').append("%02x".format(b))
        }
    }

    fun decode(id: String): String {
        val s = id.removePrefix(PREFIX)
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            if (s[i] == '.') { out.write(s.substring(i + 1, i + 3).toInt(16)); i += 3 } else out.write(s[i++].code)
        }
        return out.toString(Charsets.UTF_8)
    }
}

/** A song as the server lists it: original names, the server's own ids. */
@Serializable
class SubSong(
    val id: String,
    val title: String,
    val artist: String = "",
    val artistId: String? = null,
    val album: String? = null,
    val albumId: String? = null,
    val track: Int = 0,
    val disc: Int = 0,
    val year: Int = 0,
    val duration: Int = 0,
    val coverArt: String? = null,
    val starred: Boolean = false,
    val created: String? = null,
    val suffix: String? = null,
    val bitRate: Int = 0,
    /** The file under the server's music folder, when the server gives one. */
    val path: String? = null,
    /** The server's Latin forms (extension romanization); the original where it sent none. Null without the extension. */
    val titleLatin: String? = null,
    val artistLatin: String? = null,
    val albumLatin: String? = null,
    /** OpenSubsonic replayGain: the track's gain in dB (the album's when the track has none), null when the server sends none. */
    val gain: Float? = null,
    /** A public link to the song, shared as is (OpenSubsonic extension shareUrl); null when the server has none. */
    val shareUrl: String? = null,
)

@Serializable
class SubAlbum(val id: String, val name: String, val artist: String = "", val artistId: String? = null, val year: Int = 0, val coverArt: String? = null, val songCount: Int = 0, val created: String? = null, val starred: Boolean = false, val releaseType: String? = null, val wanted: String? = null, val wantedError: String? = null, val nameLatin: String? = null, val artistLatin: String? = null)

/** [wanted]: how a starred album or artist the server doesn't hold yet is coming (extension oasis's "oasisWanted": wanted, downloading, failed), [wantedError] why it isn't there yet; null on other servers. */
@Serializable
class SubArtist(val id: String, val name: String, val coverArt: String? = null, val albumCount: Int = 0, val starred: Boolean = false, val wanted: String? = null, val wantedError: String? = null, val nameLatin: String? = null)

/** [mix]: one of the account's personal mixes (extension oasis's "oasisMix"; false on other servers). */
@Serializable
class SubPlaylist(val id: String, val name: String, val owner: String = "", val songCount: Int = 0, val coverArt: String? = null, val mix: Boolean = false, val nameLatin: String? = null)

/** The whole library as of [syncedAt], with what the server said about itself. */
@Serializable
class SubsonicIndex(
    val songs: List<SubSong>,
    val albums: List<SubAlbum>,
    val artists: List<SubArtist>,
    val syncedAt: Long,
    /** getOpenSubsonicExtensions: each extension's name and versions ("songLyrics" to [1, 2]); empty for a plain Subsonic server. */
    val extensionVersions: Map<String, List<Int>> = emptyMap(),
    /** getUser's "scrobblingEnabled": the server sends this user's plays to Last.fm itself; null when it doesn't say. */
    val serverScrobbles: Boolean? = null,
) {
    fun supports(extension: String, version: Int = 1) = version in extensionVersions[extension].orEmpty()
}

/**
 * The server's answer to a call that failed: [code] as Subsonic gives it (40 wrong user or password, 70 not found).
 * [http]: the HTTP status of an answer that wasn't Subsonic's; null when it was.
 */
class SubsonicException(val code: Int, message: String, val http: Int? = null) : Exception(message)

/** Calls to a Subsonic or OpenSubsonic server, with token auth: md5(password + salt), a fresh salt per request. Blocking. */
class SubsonicClient(url: String, val user: String, private val password: String) {
    /**
     * The server has the OpenSubsonic extension romanization: every call asks for the Latin forms beside the originals
     * (romanization=both), and the names parsed carry them. [sync] sets it from the server's extensions.
     */
    @Volatile var romanization = false
    /**
     * The server has the extension oasis v1: every request opts in (oasis=1), so its playlists say which are mixes and
     * its albums and artists what is on its way. [sync] sets it.
     */
    @Volatile var oasis = false
    /** An address without a scheme is https. */
    val base = url.trim().trimEnd('/').let { if ("://" in it) it else "https://$it" }
    /** The URL of [method] with auth and [params], for calls and for what the player and image loader fetch. */
    fun url(method: String, params: List<Pair<String, String>> = emptyList()) = "$base/rest/$method?" + query(params)

    private fun query(params: List<Pair<String, String>>): String {
        val salt = ByteArray(8).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val token = md5(password + salt)
        val all = listOf("u" to user, "t" to token, "s" to salt, "v" to API, "c" to "caravan", "f" to "json") + params + if (oasis) listOf("oasis" to "1") else emptyList()
        return all.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
    }

    /** The answer's "subsonic-response", after checking its status. */
    fun call(method: String, params: List<Pair<String, String>> = emptyList()): JsonObject {
        // A big library's getPlaylist, getStarred2 or getArtists runs to tens of MB.
        val (code, body) = httpCall(url(method, params + if (romanization) listOf("romanization" to "both") else emptyList()), timeoutMs = 30_000, maxBytes = 64 shl 20)
        val root = runCatching { Json.parseToJsonElement(body).obj()?.get("subsonic-response").obj() }.getOrNull()
            ?: throw SubsonicException(0, if (code in 200..299) "Not a Subsonic server: no subsonic-response in the answer" else "The server answered HTTP $code", code)
        if (root["status"].str() != "ok") {
            val e = root["error"].obj()
            throw SubsonicException(e?.get("code").str()?.toIntOrNull() ?: 0, e?.get("message").str() ?: "The server refused $method")
        }
        return root
    }

    fun ping() { call("ping") }

    /**
     * OpenSubsonic extensions and their versions ("songLyrics" to [1, 2], …). None from a server that doesn't know the
     * call: a Subsonic error, HTTP 404 or 501, or an answer that isn't Subsonic's. A timeout, a 503 or another failure
     * throws: the server didn't say, and taking that as "none" would drop what the extensions bring.
     */
    fun extensions(): Map<String, List<Int>> = try {
        call("getOpenSubsonicExtensions")["openSubsonicExtensions"].arr().orEmpty().mapNotNull { it.obj() }
            .mapNotNull { o -> o["name"].str()?.let { it to o["versions"].arr().orEmpty().mapNotNull { v -> v.str()?.toIntOrNull() } } }.toMap()
    } catch (e: SubsonicException) {
        when (e.http) { null, 404, 501, in 200..299 -> emptyMap(); else -> throw e }
    }

    /** getUser's "scrobblingEnabled" for this user; null when the server doesn't say. */
    fun scrobblingEnabled(): Boolean? = runCatching { call("getUser", listOf("username" to user))["user"].obj()?.get("scrobblingEnabled").str()?.toBooleanStrictOrNull() }.getOrNull()

    /**
     * The whole library: songs from an empty search3 (`query=""`, which Navidrome and others read as
     * everything), albums from getAlbumList2, artists from getArtists, [page] at a time. A page shorter than asked
     * ends a list; so does one with nothing new, in case a server ignores the offset. A server whose empty search
     * finds nothing (Airsonic and its kind) while it lists artists gives the songs album by album (getAlbum).
     */
    fun sync(page: Int = 500): SubsonicIndex {
        val extensions = extensions()
        romanization = ROMANIZATION in extensions
        oasis = 1 in extensions[OASIS].orEmpty()
        val searched = paged(page, SubSong::id) { offset ->
            call("search3", listOf("query" to "\"\"", "songCount" to "$page", "songOffset" to "$offset", "albumCount" to "0", "artistCount" to "0"))["searchResult3"].obj()?.get("song").arr().orEmpty().mapNotNull { song(it, romanization) }
        }
        val albums = paged(page, SubAlbum::id) { offset ->
            call("getAlbumList2", listOf("type" to "alphabeticalByName", "size" to "$page", "offset" to "$offset"))["albumList2"].obj()?.get("album").arr().orEmpty().mapNotNull { album(it, romanization, oasis) }
        }
        val artists = call("getArtists")["artists"].obj()?.get("index").arr().orEmpty()
            .flatMap { it.obj()?.get("artist").arr().orEmpty() }.mapNotNull { artist(it, romanization, oasis) }.distinctBy { it.id }
        val songs = if (searched.isEmpty() && artists.isNotEmpty()) albums.flatMap { album(it.id).second }.distinctBy { it.id } else searched
        return SubsonicIndex(songs, albums, artists, System.currentTimeMillis(), extensions, scrobblingEnabled())
    }

    private fun <T> paged(page: Int, id: (T) -> String, fetch: (Int) -> List<T>): List<T> {
        val all = LinkedHashMap<String, T>()
        while (true) {
            val got = fetch(all.size)
            val before = all.size
            for (x in got) all.putIfAbsent(id(x), x)
            if (got.size < page || all.size == before) return all.values.toList()
        }
    }

    /** "newest", "recent", "frequent", "random", …: the server's album lists. */
    fun albumList(type: String, size: Int = 20) = call("getAlbumList2", listOf("type" to type, "size" to "$size"))["albumList2"].obj()?.get("album").arr().orEmpty().mapNotNull { album(it, romanization, oasis) }

    fun playlists() = call("getPlaylists")["playlists"].obj()?.get("playlist").arr().orEmpty().mapNotNull { p ->
        val o = p.obj() ?: return@mapNotNull null
        SubPlaylist(o["id"].str() ?: return@mapNotNull null, o["name"].str() ?: "", o["owner"].str() ?: "", o["songCount"].str()?.toIntOrNull() ?: 0, o["coverArt"].str(), oasis && o["oasisMix"].str() == "true", latin(o, "name", romanization))
    }

    fun playlist(id: String): Pair<SubPlaylist, List<SubSong>> {
        val o = call("getPlaylist", listOf("id" to id))["playlist"].obj() ?: throw SubsonicException(70, "No such playlist")
        val songs = o["entry"].arr().orEmpty().mapNotNull { song(it, romanization) }
        return SubPlaylist(id, o["name"].str() ?: "", o["owner"].str() ?: "", songs.size, o["coverArt"].str(), nameLatin = latin(o, "name", romanization)) to songs
    }

    fun starredSongs() = call("getStarred2")["starred2"].obj()?.get("song").arr().orEmpty().mapNotNull { song(it, romanization) }

    /** Songs like the song, album or artist [id] (getSimilarSongs takes any of the three). */
    fun similarSongs(id: String, count: Int = 50) = call("getSimilarSongs", listOf("id" to id, "count" to "$count"))["similarSongs"].obj()?.get("song").arr().orEmpty().mapNotNull { song(it, romanization) }

    /** An album and its songs, for one the synced lists don't hold (seen in search or radio). */
    fun album(id: String): Pair<SubAlbum, List<SubSong>> {
        val a = call("getAlbum", listOf("id" to id))["album"] ?: throw SubsonicException(70, "No such album")
        return (album(a, romanization, oasis) ?: throw SubsonicException(70, "No such album")) to a.obj()?.get("song").arr().orEmpty().mapNotNull { song(it, romanization) }
    }

    fun artist(id: String): Pair<SubArtist, List<SubAlbum>> {
        val a = call("getArtist", listOf("id" to id))["artist"] ?: throw SubsonicException(70, "No such artist")
        return (artist(a, romanization, oasis) ?: throw SubsonicException(70, "No such artist")) to a.obj()?.get("album").arr().orEmpty().mapNotNull { album(it, romanization, oasis) }
    }

    /** The server's own search. */
    /** The artist's top songs. */
    fun topSongs(artist: String, count: Int = 50) = call("getTopSongs", listOf("artist" to artist, "count" to "$count"))["topSongs"].obj()?.get("song").arr().orEmpty().mapNotNull { song(it, romanization) }

    fun search(query: String, count: Int = 30): Triple<List<SubSong>, List<SubAlbum>, List<SubArtist>> {
        val r = call("search3", listOf("query" to query, "songCount" to "$count", "albumCount" to "$count", "artistCount" to "$count"))["searchResult3"].obj()
        return Triple(r?.get("song").arr().orEmpty().mapNotNull { song(it, romanization) }, r?.get("album").arr().orEmpty().mapNotNull { album(it, romanization, oasis) }, r?.get("artist").arr().orEmpty().mapNotNull { artist(it, romanization, oasis) })
    }

    fun song(id: String) = call("getSong", listOf("id" to id))["song"]?.let { song(it, romanization) } ?: throw SubsonicException(70, "No such song")

    /** [param]: "id" for a song, "albumId" or "artistId". */
    fun star(id: String, on: Boolean, param: String = "id") { call(if (on) "star" else "unstar", listOf(param to id)) }

    /**
     * OpenSubsonic getLyricsBySongId: the synced lyrics if the server has any, else its plain ones; null for none.
     * With [kinds] (extension romanization: "main", "pronunciation" or both) it asks songLyrics v2's layers, and the
     * pronunciation entry gives each main line its Latin form, word-timed when both have cueLine. Pronunciation alone
     * is the lyrics; a song with none (already in Latin script) is asked again for its main lyrics. A server that
     * ignores kinds sends every layer: main and pronunciation are used, the others left out.
     */
    fun lyrics(id: String, source: String, kinds: List<String>? = null): Lyrics? {
        val params = listOf("id" to id) + if (kinds == null) emptyList() else listOf("enhanced" to "true", "kinds" to kinds.joinToString(","))
        val list = call("getLyricsBySongId", params)["lyricsList"].obj()?.get("structuredLyrics").arr().orEmpty().mapNotNull { it.obj() }
        fun pick(kind: String) = list.filter { (it["kind"].str() ?: "main") == kind }.let { l -> l.firstOrNull { it["synced"].str() == "true" } ?: l.firstOrNull() }
        val main = pick("main")
        val pron = if (kinds == null) null else pick("pronunciation")
        val base = main ?: pron ?: return if (kinds == listOf("pronunciation")) lyrics(id, source, listOf("main")) else null
        val synced = base["synced"].str() == "true"
        val latin = if (main != null && pron != null) lines(pron) else emptyList()
        val pronSynced = pron?.get("synced").str() == "true"
        val own = lines(base)
        val lines = own.mapIndexed { i, line ->
            // The layers pair line by line (the extension's rule); only a server whose counts differ pairs by start time.
            val p = (if (latin.size == own.size) latin[i] else if (synced && pronSynced) latin.minByOrNull { abs(it.startMs - line.startMs) }?.takeIf { abs(it.startMs - line.startMs) <= 1000 } else null)
                ?: return@mapIndexed line
            // Each timed word gets the romaji timed from its start to the next word's; romaji sung before the first word goes on it.
            val wordLatin = if (line.words.isEmpty() || p.words.isEmpty()) emptyList() else line.words.mapIndexed { k, w ->
                val end = line.words.getOrNull(k + 1)?.startMs ?: Long.MAX_VALUE
                p.words.filter { it.startMs < end && (k == 0 || it.startMs >= w.startMs) }.joinToString("") { it.text }
            }
            Line(line.startMs, line.text, line.words, latin = p.text.ifBlank { line.text }, wordLatin = wordLatin)
        }
        return Lyrics(source, lines, synced, lang = base["lang"].str()).takeIf { lines.any { it.text.isNotBlank() } }
    }

    // One structuredLyrics entry's lines; with cueLine, their timed words.
    private fun lines(l: JsonObject): List<Line> {
        val synced = l["synced"].str() == "true"
        // OpenSubsonic: a positive offset shows the lyrics sooner.
        val offset = l["offset"].str()?.toLongOrNull() ?: 0
        // Several cueLines on one line are its voices, the main one first.
        val cues = l["cueLine"].arr().orEmpty().mapNotNull { it.obj() }.groupBy { it["index"].int() }.mapValues { it.value.first() }
        return l["line"].arr().orEmpty().mapNotNull { it.obj() }.mapIndexed { i, o ->
            Line(if (synced) (o["start"].str()?.toLongOrNull() ?: 0) - offset else 0, o["value"].str() ?: "", if (synced) cues[i]?.let { words(it, offset) }.orEmpty() else emptyList())
        }
    }

    // A cueLine's cues as words. Each runs from its first byte to the next one's, so the words join into the line,
    // the untimed spaces between them included.
    private fun words(c: JsonObject, offset: Long): List<Word> {
        val bytes = (c["value"].str() ?: "").toByteArray()
        val cues = c["cue"].arr().orEmpty().mapNotNull { it.obj() }
        return cues.mapIndexed { i, q ->
            val from = if (i == 0) 0 else q["byteStart"].int()
            val to = cues.getOrNull(i + 1)?.get("byteStart")?.int() ?: bytes.size
            val text = if (from in 0..to && to <= bytes.size) String(bytes, from, to - from, Charsets.UTF_8) else q["value"].str() ?: ""
            val start = q["start"].str()?.toLongOrNull() ?: 0
            val end = q["end"].str()?.toLongOrNull() ?: cues.getOrNull(i + 1)?.get("start").str()?.toLongOrNull() ?: c["end"].str()?.toLongOrNull() ?: start
            Word(start - offset, end - offset, text)
        }
    }

    /**
     * Reports plays: now playing with [submission] false (one id, no time), finished ones with true and each play's
     * start in ms since the epoch, which the server stores.
     */
    fun scrobble(ids: List<String>, timesMs: List<Long>, submission: Boolean) {
        call("scrobble", ids.map { "id" to it } + timesMs.map { "time" to "$it" } + listOf("submission" to "$submission"))
    }

    /**
     * Finished plays ([scrobble] with submission). A song the server no longer has (70) fails the whole call, which
     * would stay queued for good: then they go one by one, and the missing songs' are dropped. Returns how many of the
     * first plays are done with; another failure one by one stops there. Throws when the first call fails otherwise.
     */
    fun submit(ids: List<String>, timesMs: List<Long>): Int {
        try { scrobble(ids, timesMs, submission = true); return ids.size } catch (e: SubsonicException) { if (e.code != 70) throw e }
        return ids.indices.firstOrNull { i ->
            runCatching { scrobble(listOf(ids[i]), listOf(timesMs[i]), submission = true) }.exceptionOrNull().let { it != null && (it !is SubsonicException || it.code != 70) }
        } ?: ids.size
    }

    fun streamUrl(id: String) = url("stream", listOf("id" to id))
    fun coverUrl(id: String, size: Int?) = url("getCoverArt", listOf("id" to id) + listOfNotNull(size?.let { "size" to "$it" }))

    companion object {
        const val API = "1.16.1"
        private val random = SecureRandom()
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        const val ROMANIZATION = "romanization"
        const val OASIS = "oasis"
        /** OpenSubsonic songLyrics v2: enhanced lyrics, with their kinds (main, pronunciation, translation) and cueLine. */
        const val SONG_LYRICS = "songLyrics"
        /** Songs carry shareUrl, a public link to share as is. */
        const val SHARE_URL = "shareUrl"

        /**
         * Plain http only to a server on the user's own network: localhost, a private, link-local or CGNAT (Tailscale)
         * address, a name without a dot or under .local, .lan, .home.arpa or .internal, or a name that resolves to
         * such addresses only. Blocking: it may resolve [host].
         */
        fun localHost(host: String): Boolean {
            val h = host.lowercase().trim('[', ']').trimEnd('.')
            if (h.isEmpty()) return false
            if (h == "localhost" || '.' !in h && ':' !in h || listOf(".localhost", ".local", ".lan", ".home.arpa", ".internal").any { h.endsWith(it) }) return true
            val addresses = runCatching { java.net.InetAddress.getAllByName(h) }.getOrNull() ?: return false
            return addresses.isNotEmpty() && addresses.all { a ->
                val b = a.address
                a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress ||
                    (b.size == 4 && (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 64) || // 100.64.0.0/10
                    (b.size == 16 && (b[0].toInt() and 0xfe) == 0xfc) // fc00::/7
            }
        }

        /** [o]'s "<field>Latin" with [latin] (asked with romanization=both), the original where the server sent none; else null. */
        fun latin(o: JsonObject, field: String, latin: Boolean) = if (latin) o[field + "Latin"].str() ?: o[field].str() ?: "" else null

        fun song(e: JsonElement, latin: Boolean = false): SubSong? {
            val o = e.obj() ?: return null
            if (o["isVideo"].str() == "true") return null
            return SubSong(
                o["id"].str() ?: return null, o["title"].str() ?: "", o["artist"].str() ?: "", o["artistId"].str(), o["album"].str(), o["albumId"].str(),
                o["track"].int(), o["discNumber"].int(), o["year"].int(), o["duration"].int(), o["coverArt"].str(), o["starred"] != null, o["created"].str(),
                o["suffix"].str(), o["bitRate"].int(), o["path"].str(), latin(o, "title", latin), latin(o, "artist", latin), latin(o, "album", latin)?.takeIf { o["album"] != null },
                o["replayGain"].obj()?.let { g -> (g["trackGain"] ?: g["albumGain"]).str()?.toFloatOrNull() }, o["shareUrl"].str(),
            )
        }

        /** [oasis]: the server has the extension oasis, so its oasisWanted fields count. */
        fun album(e: JsonElement, latin: Boolean = false, oasis: Boolean = false): SubAlbum? {
            val o = e.obj() ?: return null
            val name = o["name"].str() ?: o["title"].str() ?: ""
            return SubAlbum(o["id"].str() ?: return null, name, o["artist"].str() ?: "", o["artistId"].str(), o["year"].int(), o["coverArt"].str(), o["songCount"].int(), o["created"].str(), o["starred"] != null, o["releaseTypes"].arr()?.firstOrNull().str(), o["oasisWanted"].str()?.takeIf { oasis }, o["oasisWantedError"].str()?.takeIf { oasis }, if (latin) o["nameLatin"].str() ?: o["titleLatin"].str() ?: name else null, latin(o, "artist", latin))
        }

        fun artist(e: JsonElement, latin: Boolean = false, oasis: Boolean = false): SubArtist? {
            val o = e.obj() ?: return null
            return SubArtist(o["id"].str() ?: return null, o["name"].str() ?: "", o["coverArt"].str(), o["albumCount"].int(), o["starred"] != null, o["oasisWanted"].str()?.takeIf { oasis }, o["oasisWantedError"].str()?.takeIf { oasis }, latin(o, "name", latin))
        }

        private fun JsonElement?.int() = str()?.toIntOrNull() ?: 0
    }
}

/** A Subsonic cover in a song or card: "subsonic:" and its encoded id. The app turns it into a getCoverArt URL. */
fun subsonicCover(id: String?) = id?.let { "subsonic:" + SubsonicIds.encode(it).removePrefix(SubsonicIds.PREFIX) }

// Names the server romanized (extension romanization) take its Latin forms (Romanizer.text's served); the others are romanized here.
fun Romanizer.song(s: SubSong): Song {
    val id = SubsonicIds.encode(s.id)
    val artistId = s.artistId?.let(SubsonicIds::encode)
    val title = text(s.title, listOf(s.artist) + listOfNotNull(s.album), id, artistId, titleOf = true, served = s.titleLatin)
    val artist = Named(artistId, text(s.artist, listOf(s.title) + listOfNotNull(s.album), id, artistId, served = s.artistLatin))
    val album = s.album?.let { Named(s.albumId?.let(SubsonicIds::encode), text(it, listOf(s.title, s.artist), id, artistId, titleOf = true, served = s.albumLatin)) }
    return Song(id, title, listOf(artist), album, s.duration, subsonicCover(s.coverArt))
}

fun Romanizer.item(a: SubAlbum): Item =
    Item("album", SubsonicIds.encode(a.id), text(a.name, listOf(a.artist), titleOf = true, served = a.nameLatin), text(a.artist, listOf(a.name), artistId = a.artistId?.let(SubsonicIds::encode), served = a.artistLatin), subsonicCover(a.coverArt))

fun Romanizer.item(a: SubArtist): Item {
    val id = SubsonicIds.encode(a.id)
    return Item("artist", id, text(a.name, artistId = id, served = a.nameLatin), Text("", ""), subsonicCover(a.coverArt))
}

// The owner is a user name, which the server doesn't romanize.
fun Romanizer.item(p: SubPlaylist): Item = Item("playlist", SubsonicIds.encode(p.id), text(p.name, served = p.nameLatin), text(p.owner, served = p.owner.takeIf { p.nameLatin != null }), subsonicCover(p.coverArt))

/**
 * The server's own lyrics (OpenSubsonic songLyrics), the reference the other providers' text is matched against.
 * [kinds]: the layers to ask for (extension romanization), null without it.
 */
class SubsonicLyrics(private val client: () -> SubsonicClient, private val kinds: () -> List<String>?) : LyricsProvider {
    override val id = "subsonic"
    override val name = "Your server"
    override fun fetch(q: LyricsQuery): Lyrics? = client().lyrics(SubsonicIds.decode(q.videoId), name, kinds())
}
