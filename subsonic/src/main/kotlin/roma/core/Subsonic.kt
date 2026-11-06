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
 * A Subsonic id inside roma is "s." plus the server's id, with every character other than A–Z, a–z, 0–9, _ and -
 * written as "." and two hex digits per UTF-8 byte. No YouTube id holds a dot, and the result is a safe file name and
 * URI host everywhere.
 */
object SubsonicIds {
    const val PREFIX = "s."

    fun of(id: String) = id.startsWith(PREFIX)

    fun encode(raw: String) = PREFIX + buildString {
        for (b in raw.toByteArray()) {
            val c = b.toInt().toChar()
            if (b >= 0 && (c.isLetterOrDigit() || c == '_' || c == '-')) {
                append(c)
            } else {
                append('.')
                append("%02x".format(b))
            }
        }
    }

    fun decode(id: String): String {
        val s = id.removePrefix(PREFIX)
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            if (s[i] == '.') {
                out.write(s.substring(i + 1, i + 3).toInt(16))
                i += 3
            } else {
                out.write(s[i].code)
                i += 1
            }
        }
        return out.toString(Charsets.UTF_8)
    }
}

/** A song as the server lists it, with the original names and the server's own ids. */
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
    /**
     * The server's Latin forms (extension romanization), or the original where it sent none. Null when the server
     * lacks the extension.
     */
    val titleLatin: String? = null,
    val artistLatin: String? = null,
    val albumLatin: String? = null,
    /**
     * The track's gain in dB from OpenSubsonic replayGain, or the album's gain when the track has none. Null when the
     * server sends neither.
     */
    val gain: Float? = null,
    /** A public link to the song, shared as is (OpenSubsonic extension shareUrl). Null when the server has none. */
    val shareUrl: String? = null,
)

@Serializable
class SubAlbum(
    val id: String,
    val name: String,
    val artist: String = "",
    val artistId: String? = null,
    val year: Int = 0,
    val coverArt: String? = null,
    val songCount: Int = 0,
    val created: String? = null,
    val starred: Boolean = false,
    val releaseType: String? = null,
    val wanted: String? = null,
    val wantedError: String? = null,
    val nameLatin: String? = null,
    val artistLatin: String? = null,
)

/**
 * [wanted] says how a starred album or artist that the server doesn't hold yet is coming: "wanted", "downloading" or
 * "failed" (extension oasis's "oasisWanted"). [wantedError] says why it isn't there yet. Both are null on other servers.
 */
@Serializable
class SubArtist(
    val id: String,
    val name: String,
    val coverArt: String? = null,
    val albumCount: Int = 0,
    val starred: Boolean = false,
    val wanted: String? = null,
    val wantedError: String? = null,
    val nameLatin: String? = null,
)

/** [mix] is true for one of the account's personal mixes (extension oasis's "oasisMix"). It is false on other servers. */
@Serializable
class SubPlaylist(
    val id: String,
    val name: String,
    val owner: String = "",
    val songCount: Int = 0,
    val coverArt: String? = null,
    val mix: Boolean = false,
    val nameLatin: String? = null,
)

/** The whole library as of [syncedAt], with what the server said about itself. */
@Serializable
class SubsonicIndex(
    val songs: List<SubSong>,
    val albums: List<SubAlbum>,
    val artists: List<SubArtist>,
    val syncedAt: Long,
    /**
     * Each extension's name and versions from getOpenSubsonicExtensions, such as "songLyrics" to [1, 2]. Empty for a
     * plain Subsonic server.
     */
    val extensionVersions: Map<String, List<Int>> = emptyMap(),
    /**
     * getUser's "scrobblingEnabled". True means the server sends this user's plays to Last.fm itself. Null when the
     * server doesn't say.
     */
    val serverScrobbles: Boolean? = null,
) {
    fun supports(extension: String, version: Int = 1) = version in extensionVersions[extension].orEmpty()
}

/**
 * The server's answer to a call that failed. [code] is the Subsonic error code, such as 40 for a wrong user or password
 * and 70 for not found. [http] is the HTTP status of an answer that wasn't Subsonic's, and null when it was.
 */
class SubsonicException(val code: Int, message: String, val http: Int? = null) : Exception(message)

/**
 * Blocking calls to a Subsonic or OpenSubsonic server. Auth uses a token, md5(password + salt), with a fresh salt per
 * request.
 */
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
    val base = url.trim().trimEnd('/').let { address -> if ("://" in address) address else "https://$address" }
    /** The URL of [method] with auth and [params], for calls and for what the player and image loader fetch. */
    fun url(method: String, params: List<Pair<String, String>> = emptyList()) = "$base/rest/$method?" + query(params)

    private fun query(params: List<Pair<String, String>>): String {
        val saltBytes = ByteArray(8)
        random.nextBytes(saltBytes)
        val salt = saltBytes.joinToString("") { byte -> "%02x".format(byte) }
        val token = md5(password + salt)
        val auth = listOf("u" to user, "t" to token, "s" to salt, "v" to API, "c" to "caravan", "f" to "json")
        val optIn: List<Pair<String, String>> = if (oasis) listOf("oasis" to "1") else emptyList()
        val all = auth + params + optIn
        return all.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
    }

    /** The answer's "subsonic-response", after checking its status. */
    fun call(method: String, params: List<Pair<String, String>> = emptyList()): JsonObject {
        val romanizationParams: List<Pair<String, String>> =
            if (romanization) listOf("romanization" to "both") else emptyList()
        // A big library's getPlaylist, getStarred2 or getArtists runs to tens of MB.
        val (code, body) = httpCall(url(method, params + romanizationParams), timeoutMs = 30_000, maxBytes = 64 * 1024 * 1024)
        val root = runCatching { Json.parseToJsonElement(body).obj()?.get("subsonic-response").obj() }.getOrNull()
        if (root == null) {
            val message = if (code in 200..299) {
                "Not a Subsonic server: no subsonic-response in the answer"
            } else {
                "The server answered HTTP $code"
            }
            throw SubsonicException(0, message, code)
        }
        if (root["status"].str() != "ok") {
            val error = root["error"].obj()
            val errorCode = error?.get("code").str()?.toIntOrNull() ?: 0
            val message = error?.get("message").str() ?: "The server refused $method"
            throw SubsonicException(errorCode, message)
        }
        return root
    }

    fun ping() {
        call("ping")
    }

    /**
     * OpenSubsonic extensions and their versions ("songLyrics" to [1, 2], …). A server that doesn't know the call has
     * none: it answers with a Subsonic error, HTTP 404 or 501, or something that isn't Subsonic's. A timeout, a 503 or
     * another failure throws, because the server didn't say, and taking that as "none" would drop what the extensions
     * bring.
     */
    fun extensions(): Map<String, List<Int>> = try {
        val list = call("getOpenSubsonicExtensions")["openSubsonicExtensions"].arr().orEmpty()
        val entries = list.mapNotNull { element -> element.obj() }
        val pairs = entries.mapNotNull { o ->
            val name = o["name"].str() ?: return@mapNotNull null
            val versions = o["versions"].arr().orEmpty().mapNotNull { v -> v.str()?.toIntOrNull() }
            name to versions
        }
        pairs.toMap()
    } catch (e: SubsonicException) {
        when (e.http) {
            null, 404, 501, in 200..299 -> emptyMap()
            else -> throw e
        }
    }

    /** getUser's "scrobblingEnabled" for this user; null when the server doesn't say. */
    fun scrobblingEnabled(): Boolean? = runCatching {
        val account = call("getUser", listOf("username" to user))["user"].obj()
        account?.get("scrobblingEnabled").str()?.toBooleanStrictOrNull()
    }.getOrNull()

    /**
     * The whole library, [page] items at a time: songs from an empty search3 (`query=""`, which Navidrome and others
     * read as everything), albums from getAlbumList2, and artists from getArtists. A page shorter than asked ends a
     * list. So does a page with nothing new, in case a server ignores the offset. Some servers (Airsonic and its kind)
     * find nothing with an empty search while they do list artists. For them the songs come album by album (getAlbum).
     */
    fun sync(page: Int = 500): SubsonicIndex {
        val extensions = extensions()
        romanization = ROMANIZATION in extensions
        oasis = 1 in extensions[OASIS].orEmpty()
        val searched = paged(page, SubSong::id) { offset ->
            val params = listOf(
                "query" to "\"\"",
                "songCount" to "$page",
                "songOffset" to "$offset",
                "albumCount" to "0",
                "artistCount" to "0",
            )
            val result = call("search3", params)["searchResult3"].obj()
            result?.get("song").arr().orEmpty().mapNotNull { song(it, romanization) }
        }
        val albums = paged(page, SubAlbum::id) { offset ->
            val params = listOf("type" to "alphabeticalByName", "size" to "$page", "offset" to "$offset")
            val result = call("getAlbumList2", params)["albumList2"].obj()
            result?.get("album").arr().orEmpty().mapNotNull { album(it, romanization, oasis) }
        }
        val indexes = call("getArtists")["artists"].obj()?.get("index").arr().orEmpty()
        val artists = indexes
            .flatMap { index -> index.obj()?.get("artist").arr().orEmpty() }
            .mapNotNull { artist(it, romanization, oasis) }
            .distinctBy { it.id }
        val songs = if (searched.isEmpty() && artists.isNotEmpty()) {
            albums.flatMap { album(it.id).second }.distinctBy { it.id }
        } else {
            searched
        }
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

    /** One of the server's album lists, by [type]: "newest", "recent", "frequent", "random", and so on. */
    fun albumList(type: String, size: Int = 20): List<SubAlbum> {
        val params = listOf("type" to type, "size" to "$size")
        val result = call("getAlbumList2", params)["albumList2"].obj()
        return result?.get("album").arr().orEmpty().mapNotNull { album(it, romanization, oasis) }
    }

    fun playlists(): List<SubPlaylist> {
        val list = call("getPlaylists")["playlists"].obj()?.get("playlist").arr().orEmpty()
        return list.mapNotNull { p ->
            val o = p.obj() ?: return@mapNotNull null
            val id = o["id"].str() ?: return@mapNotNull null
            SubPlaylist(
                id = id,
                name = o["name"].str() ?: "",
                owner = o["owner"].str() ?: "",
                songCount = o["songCount"].str()?.toIntOrNull() ?: 0,
                coverArt = o["coverArt"].str(),
                mix = oasis && o["oasisMix"].str() == "true",
                nameLatin = latin(o, "name", romanization),
            )
        }
    }

    fun playlist(id: String): Pair<SubPlaylist, List<SubSong>> {
        val o = call("getPlaylist", listOf("id" to id))["playlist"].obj() ?: throw SubsonicException(NOT_FOUND, "No such playlist")
        val songs = o["entry"].arr().orEmpty().mapNotNull { song(it, romanization) }
        val playlist = SubPlaylist(
            id,
            o["name"].str() ?: "",
            o["owner"].str() ?: "",
            songs.size,
            o["coverArt"].str(),
            nameLatin = latin(o, "name", romanization),
        )
        return playlist to songs
    }

    fun starredSongs(): List<SubSong> = starred().first

    /** Everything starred (getStarred2): songs, albums and artists. */
    fun starred(): Triple<List<SubSong>, List<SubAlbum>, List<SubArtist>> {
        val r = call("getStarred2")["starred2"].obj()
        val songs = r?.get("song").arr().orEmpty().mapNotNull { song(it, romanization) }
        val albums = r?.get("album").arr().orEmpty().mapNotNull { album(it, romanization, oasis) }
        val artists = r?.get("artist").arr().orEmpty().mapNotNull { artist(it, romanization, oasis) }
        return Triple(songs, albums, artists)
    }

    /** Songs like the song, album or artist [id] (getSimilarSongs takes any of the three). */
    fun similarSongs(id: String, count: Int = 50): List<SubSong> {
        val result = call("getSimilarSongs", listOf("id" to id, "count" to "$count"))["similarSongs"].obj()
        return result?.get("song").arr().orEmpty().mapNotNull { song(it, romanization) }
    }

    /** An album and its songs, for an album the synced lists don't hold (seen in search or radio). */
    fun album(id: String): Pair<SubAlbum, List<SubSong>> {
        val a = call("getAlbum", listOf("id" to id))["album"] ?: throw SubsonicException(NOT_FOUND, "No such album")
        val info = album(a, romanization, oasis) ?: throw SubsonicException(NOT_FOUND, "No such album")
        val songs = a.obj()?.get("song").arr().orEmpty().mapNotNull { song(it, romanization) }
        return info to songs
    }

    fun artist(id: String): Pair<SubArtist, List<SubAlbum>> {
        val a = call("getArtist", listOf("id" to id))["artist"] ?: throw SubsonicException(NOT_FOUND, "No such artist")
        val info = artist(a, romanization, oasis) ?: throw SubsonicException(NOT_FOUND, "No such artist")
        val albums = a.obj()?.get("album").arr().orEmpty().mapNotNull { album(it, romanization, oasis) }
        return info to albums
    }

    /** The artist's top songs. */
    fun topSongs(artist: String, count: Int = 50): List<SubSong> {
        val result = call("getTopSongs", listOf("artist" to artist, "count" to "$count"))["topSongs"].obj()
        return result?.get("song").arr().orEmpty().mapNotNull { song(it, romanization) }
    }

    /** The server's own search. */
    fun search(query: String, count: Int = 30): Triple<List<SubSong>, List<SubAlbum>, List<SubArtist>> {
        val params = listOf("query" to query, "songCount" to "$count", "albumCount" to "$count", "artistCount" to "$count")
        val r = call("search3", params)["searchResult3"].obj()
        val songs = r?.get("song").arr().orEmpty().mapNotNull { song(it, romanization) }
        val albums = r?.get("album").arr().orEmpty().mapNotNull { album(it, romanization, oasis) }
        val artists = r?.get("artist").arr().orEmpty().mapNotNull { artist(it, romanization, oasis) }
        return Triple(songs, albums, artists)
    }

    fun song(id: String): SubSong {
        val element = call("getSong", listOf("id" to id))["song"]
        val parsed = if (element == null) null else song(element, romanization)
        return parsed ?: throw SubsonicException(NOT_FOUND, "No such song")
    }

    /** [param] is "id" for a song, "albumId" for an album or "artistId" for an artist. */
    fun star(id: String, on: Boolean, param: String = "id") {
        val method = if (on) "star" else "unstar"
        call(method, listOf(param to id))
    }

    /**
     * OpenSubsonic getLyricsBySongId: the synced lyrics if the server has any, else its plain ones; null for none.
     * With [kinds] (extension romanization: "main", "pronunciation" or both) it asks for songLyrics v2's layers, and
     * the pronunciation entry gives each main line its Latin form, word-timed when both have cueLine. Pronunciation
     * alone is the lyrics. A song with no pronunciation (already in Latin script) is asked again for its main lyrics. A
     * server that ignores kinds sends every layer; roma uses main and pronunciation and leaves the others out.
     */
    fun lyrics(id: String, source: String, kinds: List<String>? = null): Lyrics? {
        val enhanced: List<Pair<String, String>> =
            if (kinds == null) emptyList() else listOf("enhanced" to "true", "kinds" to kinds.joinToString(","))
        val params = listOf("id" to id) + enhanced
        val lyricsList = call("getLyricsBySongId", params)["lyricsList"].obj()
        val list = lyricsList?.get("structuredLyrics").arr().orEmpty().mapNotNull { it.obj() }

        fun pick(kind: String): JsonObject? {
            val ofKind = list.filter { entry -> (entry["kind"].str() ?: "main") == kind }
            return ofKind.firstOrNull { entry -> entry["synced"].str() == "true" } ?: ofKind.firstOrNull()
        }

        val main = pick("main")
        val pron = if (kinds == null) null else pick("pronunciation")
        val base = main ?: pron
        if (base == null) {
            return if (kinds == listOf("pronunciation")) lyrics(id, source, listOf("main")) else null
        }
        val synced = base["synced"].str() == "true"
        val latin = if (main != null && pron != null) lines(pron) else emptyList()
        val pronSynced = pron?.get("synced").str() == "true"
        val own = lines(base)
        val lines = own.mapIndexed { i, line ->
            // The layers pair line by line (the extension's rule). Only when a server's line counts differ do they
            // pair by start time.
            val p = if (latin.size == own.size) {
                latin[i]
            } else if (synced && pronSynced) {
                val nearest = latin.minByOrNull { candidate -> abs(candidate.startMs - line.startMs) }
                if (nearest != null && abs(nearest.startMs - line.startMs) <= PAIRING_WINDOW_MS) nearest else null
            } else {
                null
            }
            if (p == null) return@mapIndexed line
            // Each timed word gets the romaji timed from its start to the next word's start. Romaji sung before the
            // first word goes on the first word.
            val wordLatin = if (line.words.isEmpty() || p.words.isEmpty()) {
                emptyList()
            } else {
                line.words.mapIndexed { k, w ->
                    val end = line.words.getOrNull(k + 1)?.startMs ?: Long.MAX_VALUE
                    val sung = p.words.filter { pw -> pw.startMs < end && (k == 0 || pw.startMs >= w.startMs) }
                    sung.joinToString("") { pw -> pw.text }
                }
            }
            Line(line.startMs, line.text, line.words, latin = p.text.ifBlank { line.text }, wordLatin = wordLatin)
        }
        val lyrics = Lyrics(source, lines, synced, lang = base["lang"].str())
        return if (lines.any { it.text.isNotBlank() }) lyrics else null
    }

    // One structuredLyrics entry's lines, and with cueLine their timed words.
    private fun lines(l: JsonObject): List<Line> {
        val synced = l["synced"].str() == "true"
        // In OpenSubsonic, a positive offset shows the lyrics sooner.
        val offset = l["offset"].str()?.toLongOrNull() ?: 0
        // Several cueLines on one line are its voices, with the main one first.
        val cues = l["cueLine"].arr().orEmpty()
            .mapNotNull { it.obj() }
            .groupBy { cue -> cue["index"].int() }
            .mapValues { entry -> entry.value.first() }
        val entries = l["line"].arr().orEmpty().mapNotNull { it.obj() }
        return entries.mapIndexed { i, o ->
            val start: Long = if (synced) (o["start"].str()?.toLongOrNull() ?: 0) - offset else 0
            val text = o["value"].str() ?: ""
            val cue = cues[i]
            val timedWords = if (synced && cue != null) words(cue, offset) else emptyList()
            Line(start, text, timedWords)
        }
    }

    // A cueLine's cues as words. Each word runs from its cue's first byte to the next cue's, so the words join into
    // the whole line, including the untimed spaces between them.
    private fun words(c: JsonObject, offset: Long): List<Word> {
        val bytes = (c["value"].str() ?: "").toByteArray()
        val cues = c["cue"].arr().orEmpty().mapNotNull { it.obj() }
        return cues.mapIndexed { i, q ->
            val from = if (i == 0) 0 else q["byteStart"].int()
            val to = cues.getOrNull(i + 1)?.get("byteStart")?.int() ?: bytes.size
            val text = if (from in 0..to && to <= bytes.size) {
                String(bytes, from, to - from, Charsets.UTF_8)
            } else {
                q["value"].str() ?: ""
            }
            val start = q["start"].str()?.toLongOrNull() ?: 0
            val end = q["end"].str()?.toLongOrNull()
                ?: cues.getOrNull(i + 1)?.get("start").str()?.toLongOrNull()
                ?: c["end"].str()?.toLongOrNull()
                ?: start
            Word(start - offset, end - offset, text)
        }
    }

    /**
     * Reports plays. Now playing uses [submission] false with one id and no time. Finished plays use true, with each
     * play's start in ms since the epoch, which the server stores.
     */
    fun scrobble(ids: List<String>, timesMs: List<Long>, submission: Boolean) {
        val idParams = ids.map { id -> "id" to id }
        val timeParams = timesMs.map { time -> "time" to "$time" }
        call("scrobble", idParams + timeParams + listOf("submission" to "$submission"))
    }

    /**
     * Submits finished plays ([scrobble] with submission). A song the server no longer has (70) fails the whole call,
     * which would then stay queued for good. In that case the plays go one by one, and the missing songs' plays are
     * dropped. Returns how many of the first plays are done with: another failure while going one by one stops there.
     * Throws when the first call fails for any other reason.
     */
    fun submit(ids: List<String>, timesMs: List<Long>): Int {
        try {
            scrobble(ids, timesMs, submission = true)
            return ids.size
        } catch (e: SubsonicException) {
            if (e.code != NOT_FOUND) throw e
        }
        val firstFailure = ids.indices.firstOrNull { i ->
            val error = runCatching { scrobble(listOf(ids[i]), listOf(timesMs[i]), submission = true) }.exceptionOrNull()
            error != null && (error !is SubsonicException || error.code != NOT_FOUND)
        }
        return firstFailure ?: ids.size
    }

    fun streamUrl(id: String) = url("stream", listOf("id" to id))

    fun coverUrl(id: String, size: Int?): String {
        val sizeParams: List<Pair<String, String>> = if (size == null) emptyList() else listOf("size" to "$size")
        return url("getCoverArt", listOf("id" to id) + sizeParams)
    }

    companion object {
        const val API = "1.16.1"
        private const val NOT_FOUND = 70
        // How far apart in time a main line and a pronunciation line may start and still pair.
        private const val PAIRING_WINDOW_MS = 1000L
        private val random = SecureRandom()
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { byte -> "%02x".format(byte) }

        const val ROMANIZATION = "romanization"
        const val OASIS = "oasis"
        /** OpenSubsonic songLyrics v2: enhanced lyrics, with their kinds (main, pronunciation, translation) and cueLine. */
        const val SONG_LYRICS = "songLyrics"
        /** Songs carry shareUrl, a public link to share as is. */
        const val SHARE_URL = "shareUrl"

        /**
         * Plain http is allowed only to a server on the user's own network: localhost, a private, link-local or CGNAT
         * (Tailscale) address, a name without a dot or under .local, .lan, .home.arpa or .internal, or a name that
         * resolves to such addresses only. Blocking, because it may resolve [host].
         */
        fun localHost(host: String): Boolean {
            val h = host.lowercase().trim('[', ']').trimEnd('.')
            if (h.isEmpty()) return false
            if (h == "localhost") return true
            if ('.' !in h && ':' !in h) return true
            val localSuffixes = listOf(".localhost", ".local", ".lan", ".home.arpa", ".internal")
            if (localSuffixes.any { suffix -> h.endsWith(suffix) }) return true
            val addresses = runCatching { java.net.InetAddress.getAllByName(h) }.getOrNull() ?: return false
            return addresses.isNotEmpty() && addresses.all { a ->
                val b = a.address
                // 100.64.0.0/10: the first byte is 100 and the top two bits of the second are 01.
                val carrierNat = b.size == 4 && (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 64
                // fc00::/7: the top seven bits of the first byte are 1111110.
                val uniqueLocal = b.size == 16 && (b[0].toInt() and 0xfe) == 0xfc
                a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || carrierNat || uniqueLocal
            }
        }

        /**
         * [o]'s "<field>Latin" when [latin] is set (asked with romanization=both), or the original where the server
         * sent none. Null when [latin] is not set.
         */
        fun latin(o: JsonObject, field: String, latin: Boolean): String? {
            if (!latin) return null
            return o[field + "Latin"].str() ?: o[field].str() ?: ""
        }

        fun song(e: JsonElement, latin: Boolean = false): SubSong? {
            val o = e.obj() ?: return null
            if (o["isVideo"].str() == "true") return null
            val id = o["id"].str() ?: return null
            val albumLatin = if (o["album"] != null) latin(o, "album", latin) else null
            val replayGain = o["replayGain"].obj()
            val gain = if (replayGain == null) null else (replayGain["trackGain"] ?: replayGain["albumGain"]).str()?.toFloatOrNull()
            return SubSong(
                id = id,
                title = o["title"].str() ?: "",
                artist = o["artist"].str() ?: "",
                artistId = o["artistId"].str(),
                album = o["album"].str(),
                albumId = o["albumId"].str(),
                track = o["track"].int(),
                disc = o["discNumber"].int(),
                year = o["year"].int(),
                duration = o["duration"].int(),
                coverArt = o["coverArt"].str(),
                starred = o["starred"] != null,
                created = o["created"].str(),
                suffix = o["suffix"].str(),
                bitRate = o["bitRate"].int(),
                path = o["path"].str(),
                titleLatin = latin(o, "title", latin),
                artistLatin = latin(o, "artist", latin),
                albumLatin = albumLatin,
                gain = gain,
                shareUrl = o["shareUrl"].str(),
            )
        }

        /** [oasis] says the server has the extension oasis, so its oasisWanted fields count. */
        fun album(e: JsonElement, latin: Boolean = false, oasis: Boolean = false): SubAlbum? {
            val o = e.obj() ?: return null
            val name = o["name"].str() ?: o["title"].str() ?: ""
            val id = o["id"].str() ?: return null
            val nameLatin = if (latin) o["nameLatin"].str() ?: o["titleLatin"].str() ?: name else null
            return SubAlbum(
                id = id,
                name = name,
                artist = o["artist"].str() ?: "",
                artistId = o["artistId"].str(),
                year = o["year"].int(),
                coverArt = o["coverArt"].str(),
                songCount = o["songCount"].int(),
                created = o["created"].str(),
                starred = o["starred"] != null,
                releaseType = o["releaseTypes"].arr()?.firstOrNull().str(),
                wanted = if (oasis) o["oasisWanted"].str() else null,
                wantedError = if (oasis) o["oasisWantedError"].str() else null,
                nameLatin = nameLatin,
                artistLatin = latin(o, "artist", latin),
            )
        }

        fun artist(e: JsonElement, latin: Boolean = false, oasis: Boolean = false): SubArtist? {
            val o = e.obj() ?: return null
            val id = o["id"].str() ?: return null
            return SubArtist(
                id = id,
                name = o["name"].str() ?: "",
                coverArt = o["coverArt"].str(),
                albumCount = o["albumCount"].int(),
                starred = o["starred"] != null,
                wanted = if (oasis) o["oasisWanted"].str() else null,
                wantedError = if (oasis) o["oasisWantedError"].str() else null,
                nameLatin = latin(o, "name", latin),
            )
        }

        private fun JsonElement?.int() = str()?.toIntOrNull() ?: 0
    }
}

/** A Subsonic cover in a song or card: "subsonic:" and its encoded id. The app turns it into a getCoverArt URL. */
fun subsonicCover(id: String?): String? {
    if (id == null) return null
    return "subsonic:" + SubsonicIds.encode(id).removePrefix(SubsonicIds.PREFIX)
}

// Names the server already romanized (extension romanization) use its Latin forms, which Romanizer.text takes as
// served. Other names are romanized here.
fun Romanizer.song(s: SubSong): Song {
    val id = SubsonicIds.encode(s.id)
    val artistId = s.artistId?.let(SubsonicIds::encode)
    val title = text(s.title, listOf(s.artist) + listOfNotNull(s.album), id, artistId, titleOf = true, served = s.titleLatin)
    val artist = Named(artistId, text(s.artist, listOf(s.title) + listOfNotNull(s.album), id, artistId, served = s.artistLatin))
    val albumName = s.album
    val album = if (albumName == null) {
        null
    } else {
        val albumId = s.albumId?.let(SubsonicIds::encode)
        val albumText = text(albumName, listOf(s.title, s.artist), id, artistId, titleOf = true, served = s.albumLatin)
        Named(albumId, albumText)
    }
    return Song(id, title, listOf(artist), album, s.duration, subsonicCover(s.coverArt))
}

fun Romanizer.item(a: SubAlbum): Item {
    val id = SubsonicIds.encode(a.id)
    val title = text(a.name, listOf(a.artist), titleOf = true, served = a.nameLatin)
    val artistId = a.artistId?.let(SubsonicIds::encode)
    val artist = text(a.artist, listOf(a.name), artistId = artistId, served = a.artistLatin)
    return Item("album", id, title, artist, subsonicCover(a.coverArt))
}

fun Romanizer.item(a: SubArtist): Item {
    val id = SubsonicIds.encode(a.id)
    return Item("artist", id, text(a.name, artistId = id, served = a.nameLatin), Text("", ""), subsonicCover(a.coverArt))
}

fun Romanizer.item(p: SubPlaylist): Item {
    val id = SubsonicIds.encode(p.id)
    val name = text(p.name, served = p.nameLatin)
    // The owner is a user name, which the server doesn't romanize. When the server romanizes, the owner is passed as
    // already served so roma leaves it as written.
    val ownerServed = if (p.nameLatin != null) p.owner else null
    val owner = text(p.owner, served = ownerServed)
    return Item("playlist", id, name, owner, subsonicCover(p.coverArt))
}

/**
 * The server's own lyrics (OpenSubsonic songLyrics), the reference the other providers' text is matched against.
 * [kinds] gives the layers to ask for (extension romanization), and is null without that extension.
 */
class SubsonicLyrics(private val client: () -> SubsonicClient, private val kinds: () -> List<String>?) : LyricsProvider {
    override val id = "subsonic"
    override val name = "Your server"
    override fun fetch(q: LyricsQuery): Lyrics? = client().lyrics(SubsonicIds.decode(q.videoId), name, kinds())
}
