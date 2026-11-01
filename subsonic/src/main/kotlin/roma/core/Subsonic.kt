package roma.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

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
    /** The file under the server's music folder; oasis gives none for the songs it streams. */
    val path: String? = null,
)

@Serializable
class SubAlbum(val id: String, val name: String, val artist: String = "", val artistId: String? = null, val year: Int = 0, val coverArt: String? = null, val songCount: Int = 0, val created: String? = null, val starred: Boolean = false, val wanted: String? = null, val releaseType: String? = null, val wantedError: String? = null)

/** [wanted]: oasis's "oasisWanted", how Lidarr is getting a starred album or artist: wanted, downloading or failed; [wantedError]: why it isn't there yet. */
@Serializable
class SubArtist(val id: String, val name: String, val coverArt: String? = null, val albumCount: Int = 0, val starred: Boolean = false, val wanted: String? = null, val wantedError: String? = null)

/** [mix]: one of the account's personal mixes (oasis's "oasisMix"). */
@Serializable
class SubPlaylist(val id: String, val name: String, val owner: String = "", val songCount: Int = 0, val coverArt: String? = null, val mix: Boolean = false)

/** The whole library as of [syncedAt], with what the server said about itself. */
@Serializable
class SubsonicIndex(
    val songs: List<SubSong>,
    val albums: List<SubAlbum>,
    val artists: List<SubArtist>,
    val syncedAt: Long,
    /** subsonic-response's "type": "oasis", "navidrome", …; null for servers that don't say. */
    val type: String? = null,
    val extensions: List<String> = emptyList(),
)

/** The server's answer to a call that failed: [code] as Subsonic gives it (40 wrong user or password, 70 not found). */
class SubsonicException(val code: Int, message: String) : Exception(message)

/** Calls to a Subsonic or OpenSubsonic server, with token auth: md5(password + salt), a fresh salt per request. Blocking. */
class SubsonicClient(url: String, val user: String, private val password: String) {
    val base = url.trim().trimEnd('/').let { if ("://" in it) it else "http://$it" }
    /** subsonic-response's "type" from the last answer: "oasis" means oasis, which forwards plays itself. */
    @Volatile var type: String? = null
        private set

    /** The URL of [method] with auth and [params], for calls and for what the player and image loader fetch. */
    fun url(method: String, params: List<Pair<String, String>> = emptyList()) = "$base/rest/$method?" + query(params)

    private fun query(params: List<Pair<String, String>>): String {
        val salt = ByteArray(8).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val token = md5(password + salt)
        val all = listOf("u" to user, "t" to token, "s" to salt, "v" to API, "c" to "roma", "f" to "json") + params
        return all.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
    }

    /** The answer's "subsonic-response", after checking its status. */
    fun call(method: String, params: List<Pair<String, String>> = emptyList()): JsonObject {
        val (code, body) = httpCall(url(method, params), timeoutMs = 30_000)
        val root = runCatching { Json.parseToJsonElement(body).obj()?.get("subsonic-response").obj() }.getOrNull()
            ?: throw SubsonicException(0, if (code in 200..299) "Not a Subsonic server: no subsonic-response in the answer" else "The server answered HTTP $code")
        type = root["type"].str() ?: type
        if (root["status"].str() != "ok") {
            val e = root["error"].obj()
            throw SubsonicException(e?.get("code").str()?.toIntOrNull() ?: 0, e?.get("message").str() ?: "The server refused $method")
        }
        return root
    }

    fun ping() { call("ping") }

    /** OpenSubsonic extensions ("songLyrics", "formPost", …); none from a plain Subsonic server. */
    fun extensions(): List<String> = runCatching { call("getOpenSubsonicExtensions")["openSubsonicExtensions"].arr().orEmpty().mapNotNull { it.obj()?.get("name").str() } }.getOrDefault(emptyList())

    /**
     * The whole library: songs from an empty search3 (`query=""`, which oasis, Navidrome and others read as
     * everything), albums from getAlbumList2, artists from getArtists, [page] at a time. A page shorter than asked
     * ends a list; so does one with nothing new, in case a server ignores the offset.
     */
    fun sync(page: Int = 500): SubsonicIndex {
        val songs = paged(page, SubSong::id) { offset ->
            call("search3", listOf("query" to "\"\"", "songCount" to "$page", "songOffset" to "$offset", "albumCount" to "0", "artistCount" to "0"))["searchResult3"].obj()?.get("song").arr().orEmpty().mapNotNull { song(it) }
        }
        val albums = paged(page, SubAlbum::id) { offset ->
            call("getAlbumList2", listOf("type" to "alphabeticalByName", "size" to "$page", "offset" to "$offset"))["albumList2"].obj()?.get("album").arr().orEmpty().mapNotNull { album(it) }
        }
        val artists = call("getArtists")["artists"].obj()?.get("index").arr().orEmpty()
            .flatMap { it.obj()?.get("artist").arr().orEmpty() }.mapNotNull { artist(it) }.distinctBy { it.id }
        return SubsonicIndex(songs, albums, artists, System.currentTimeMillis(), type, extensions())
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
    fun albumList(type: String, size: Int = 20) = call("getAlbumList2", listOf("type" to type, "size" to "$size"))["albumList2"].obj()?.get("album").arr().orEmpty().mapNotNull { album(it) }

    fun playlists() = call("getPlaylists")["playlists"].obj()?.get("playlist").arr().orEmpty().mapNotNull { p ->
        val o = p.obj() ?: return@mapNotNull null
        SubPlaylist(o["id"].str() ?: return@mapNotNull null, o["name"].str() ?: "", o["owner"].str() ?: "", o["songCount"].str()?.toIntOrNull() ?: 0, o["coverArt"].str(), o["oasisMix"].str() == "true")
    }

    fun playlist(id: String): Pair<SubPlaylist, List<SubSong>> {
        val o = call("getPlaylist", listOf("id" to id))["playlist"].obj() ?: throw SubsonicException(70, "No such playlist")
        val songs = o["entry"].arr().orEmpty().mapNotNull { song(it) }
        return SubPlaylist(id, o["name"].str() ?: "", o["owner"].str() ?: "", songs.size, o["coverArt"].str()) to songs
    }

    fun starredSongs() = call("getStarred2")["starred2"].obj()?.get("song").arr().orEmpty().mapNotNull { song(it) }

    /** Songs like the song, album or artist [id] (getSimilarSongs takes any of the three). */
    fun similarSongs(id: String, count: Int = 50) = call("getSimilarSongs", listOf("id" to id, "count" to "$count"))["similarSongs"].obj()?.get("song").arr().orEmpty().mapNotNull { song(it) }

    /** An album and its songs, for one the synced lists don't hold (oasis: a streamed album seen in search or radio). */
    fun album(id: String): Pair<SubAlbum, List<SubSong>> {
        val a = call("getAlbum", listOf("id" to id))["album"] ?: throw SubsonicException(70, "No such album")
        return (album(a) ?: throw SubsonicException(70, "No such album")) to a.obj()?.get("song").arr().orEmpty().mapNotNull { song(it) }
    }

    fun artist(id: String): Pair<SubArtist, List<SubAlbum>> {
        val a = call("getArtist", listOf("id" to id))["artist"] ?: throw SubsonicException(70, "No such artist")
        return (artist(a) ?: throw SubsonicException(70, "No such artist")) to a.obj()?.get("album").arr().orEmpty().mapNotNull { album(it) }
    }

    /** The server's own search, which on oasis reaches its streaming services too. */
    /** The artist's top songs (oasis: the most played library songs, then its streaming services' list). */
    fun topSongs(artist: String, count: Int = 50) = call("getTopSongs", listOf("artist" to artist, "count" to "$count"))["topSongs"].obj()?.get("song").arr().orEmpty().mapNotNull { song(it) }

    fun search(query: String, count: Int = 30): Triple<List<SubSong>, List<SubAlbum>, List<SubArtist>> {
        val r = call("search3", listOf("query" to query, "songCount" to "$count", "albumCount" to "$count", "artistCount" to "$count"))["searchResult3"].obj()
        return Triple(r?.get("song").arr().orEmpty().mapNotNull { song(it) }, r?.get("album").arr().orEmpty().mapNotNull { album(it) }, r?.get("artist").arr().orEmpty().mapNotNull { artist(it) })
    }

    fun song(id: String) = call("getSong", listOf("id" to id))["song"]?.let { song(it) } ?: throw SubsonicException(70, "No such song")

    /** [param]: "id" for a song, "albumId" or "artistId". */
    fun star(id: String, on: Boolean, param: String = "id") { call(if (on) "star" else "unstar", listOf(param to id)) }

    /** OpenSubsonic getLyricsBySongId: the synced lyrics if the server has any, else its plain ones; null for none. */
    fun lyrics(id: String, source: String): Lyrics? {
        val list = call("getLyricsBySongId", listOf("id" to id))["lyricsList"].obj()?.get("structuredLyrics").arr().orEmpty().mapNotNull { it.obj() }
        val l = list.firstOrNull { it["synced"].str() == "true" } ?: list.firstOrNull() ?: return null
        val synced = l["synced"].str() == "true"
        val offset = l["offset"].str()?.toLongOrNull() ?: 0
        val lines = l["line"].arr().orEmpty().mapNotNull { it.obj() }.map { Line(if (synced) (it["start"].str()?.toLongOrNull() ?: 0) + offset else 0, it["value"].str() ?: "") }
        return Lyrics(source, lines, synced).takeIf { lines.any { it.text.isNotBlank() } }
    }

    /**
     * Reports plays: now playing with [submission] false (one id, no time), finished ones with true and each play's
     * start in ms since the epoch, which the server stores.
     */
    fun scrobble(ids: List<String>, timesMs: List<Long>, submission: Boolean) {
        call("scrobble", ids.map { "id" to it } + timesMs.map { "time" to "$it" } + listOf("submission" to "$submission"))
    }

    fun streamUrl(id: String) = url("stream", listOf("id" to id))
    fun coverUrl(id: String, size: Int?) = url("getCoverArt", listOf("id" to id) + listOfNotNull(size?.let { "size" to "$it" }))

    companion object {
        const val API = "1.16.1"
        private val random = SecureRandom()
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        fun song(e: JsonElement): SubSong? {
            val o = e.obj() ?: return null
            if (o["isVideo"].str() == "true") return null
            return SubSong(
                o["id"].str() ?: return null, o["title"].str() ?: "", o["artist"].str() ?: "", o["artistId"].str(), o["album"].str(), o["albumId"].str(),
                o["track"].int(), o["discNumber"].int(), o["year"].int(), o["duration"].int(), o["coverArt"].str(), o["starred"] != null, o["created"].str(),
                o["suffix"].str(), o["bitRate"].int(), o["path"].str(),
            )
        }

        fun album(e: JsonElement): SubAlbum? {
            val o = e.obj() ?: return null
            return SubAlbum(o["id"].str() ?: return null, o["name"].str() ?: o["title"].str() ?: "", o["artist"].str() ?: "", o["artistId"].str(), o["year"].int(), o["coverArt"].str(), o["songCount"].int(), o["created"].str(), o["starred"] != null, o["oasisWanted"].str(), o["releaseTypes"].arr()?.firstOrNull().str(), o["oasisWantedError"].str())
        }

        fun artist(e: JsonElement): SubArtist? {
            val o = e.obj() ?: return null
            return SubArtist(o["id"].str() ?: return null, o["name"].str() ?: "", o["coverArt"].str(), o["albumCount"].int(), o["starred"] != null, o["oasisWanted"].str(), o["oasisWantedError"].str())
        }

        private fun JsonElement?.int() = str()?.toIntOrNull() ?: 0
    }
}

/** A Subsonic cover in a song or card: "subsonic:" and its encoded id. The app turns it into a getCoverArt URL. */
fun subsonicCover(id: String?) = id?.let { "subsonic:" + SubsonicIds.encode(it).removePrefix(SubsonicIds.PREFIX) }

fun Romanizer.song(s: SubSong): Song {
    val id = SubsonicIds.encode(s.id)
    val artistId = s.artistId?.let(SubsonicIds::encode)
    val title = text(s.title, listOf(s.artist) + listOfNotNull(s.album), id, artistId, titleOf = true)
    val artist = Named(artistId, text(s.artist, listOf(s.title) + listOfNotNull(s.album), id, artistId))
    val album = s.album?.let { Named(s.albumId?.let(SubsonicIds::encode), text(it, listOf(s.title, s.artist), id, artistId, titleOf = true)) }
    return Song(id, title, listOf(artist), album, s.duration, subsonicCover(s.coverArt))
}

fun Romanizer.item(a: SubAlbum): Item =
    Item("album", SubsonicIds.encode(a.id), text(a.name, listOf(a.artist), titleOf = true), text(a.artist, listOf(a.name), artistId = a.artistId?.let(SubsonicIds::encode)), subsonicCover(a.coverArt))

fun Romanizer.item(a: SubArtist): Item {
    val id = SubsonicIds.encode(a.id)
    return Item("artist", id, text(a.name, artistId = id), Text("", ""), subsonicCover(a.coverArt))
}

fun Romanizer.item(p: SubPlaylist): Item = Item("playlist", SubsonicIds.encode(p.id), text(p.name), text(p.owner), subsonicCover(p.coverArt))

/** The server's own lyrics (OpenSubsonic songLyrics), the reference the other providers' text is matched against. */
class SubsonicLyrics(private val client: () -> SubsonicClient) : LyricsProvider {
    override val id = "subsonic"
    override val name = "Your server"
    override fun fetch(q: LyricsQuery): Lyrics? = client().lyrics(SubsonicIds.decode(q.videoId), name)
}

/**
 * Whether the app scrobbles a server's song to Last.fm itself. Each play is reported once, by the side that knows its
 * real source: the server (which the app always tells), and the app as well only when the user turned that on for a
 * server other than oasis, since oasis forwards every play itself.
 */
fun scrobblesToLastFm(serverType: String?, lastFmForServer: Boolean) = serverType != "oasis" && lastFmForServer
