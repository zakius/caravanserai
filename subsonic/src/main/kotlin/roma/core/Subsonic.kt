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
)

@Serializable
class SubAlbum(val id: String, val name: String, val artist: String = "", val artistId: String? = null, val year: Int = 0, val coverArt: String? = null, val songCount: Int = 0, val created: String? = null, val starred: Boolean = false, val releaseType: String? = null, val wanted: String? = null, val wantedError: String? = null, val nameLatin: String? = null, val artistLatin: String? = null)

/** [wanted]: how a starred album or artist the server doesn't hold yet is coming (oasis's "oasisWanted": wanted, downloading, failed), [wantedError] why it isn't there yet; null on other servers. */
@Serializable
class SubArtist(val id: String, val name: String, val coverArt: String? = null, val albumCount: Int = 0, val starred: Boolean = false, val wanted: String? = null, val wantedError: String? = null, val nameLatin: String? = null)

/** [mix]: one of the account's personal mixes (oasis's "oasisMix"; false on other servers). */
@Serializable
class SubPlaylist(val id: String, val name: String, val owner: String = "", val songCount: Int = 0, val coverArt: String? = null, val mix: Boolean = false, val nameLatin: String? = null)

/** The whole library as of [syncedAt], with what the server said about itself. */
@Serializable
class SubsonicIndex(
    val songs: List<SubSong>,
    val albums: List<SubAlbum>,
    val artists: List<SubArtist>,
    val syncedAt: Long,
    val extensions: List<String> = emptyList(),
    /** getUser's "scrobblingEnabled": the server sends this user's plays to Last.fm itself; null when it doesn't say. */
    val serverScrobbles: Boolean? = null,
)

/** The server's answer to a call that failed: [code] as Subsonic gives it (40 wrong user or password, 70 not found). */
class SubsonicException(val code: Int, message: String) : Exception(message)

/** Calls to a Subsonic or OpenSubsonic server, with token auth: md5(password + salt), a fresh salt per request. Blocking. */
class SubsonicClient(url: String, val user: String, private val password: String) {
    /**
     * The server has the OpenSubsonic extension romanization: every call asks for the Latin forms beside the originals
     * (romanization=both), and the names parsed carry them. [sync] sets it from the server's extensions.
     */
    @Volatile var romanization = false
    val base = url.trim().trimEnd('/').let { if ("://" in it) it else "http://$it" }
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
        val (code, body) = httpCall(url(method, params + if (romanization) listOf("romanization" to "both") else emptyList()), timeoutMs = 30_000)
        val root = runCatching { Json.parseToJsonElement(body).obj()?.get("subsonic-response").obj() }.getOrNull()
            ?: throw SubsonicException(0, if (code in 200..299) "Not a Subsonic server: no subsonic-response in the answer" else "The server answered HTTP $code")
        if (root["status"].str() != "ok") {
            val e = root["error"].obj()
            throw SubsonicException(e?.get("code").str()?.toIntOrNull() ?: 0, e?.get("message").str() ?: "The server refused $method")
        }
        return root
    }

    fun ping() { call("ping") }

    /** OpenSubsonic extensions ("songLyrics", "formPost", …); none from a plain Subsonic server. */
    fun extensions(): List<String> = runCatching { call("getOpenSubsonicExtensions")["openSubsonicExtensions"].arr().orEmpty().mapNotNull { it.obj()?.get("name").str() } }.getOrDefault(emptyList())

    /** getUser's "scrobblingEnabled" for this user; null when the server doesn't say. */
    fun scrobblingEnabled(): Boolean? = runCatching { call("getUser", listOf("username" to user))["user"].obj()?.get("scrobblingEnabled").str()?.toBooleanStrictOrNull() }.getOrNull()

    /**
     * The whole library: songs from an empty search3 (`query=""`, which Navidrome and others read as
     * everything), albums from getAlbumList2, artists from getArtists, [page] at a time. A page shorter than asked
     * ends a list; so does one with nothing new, in case a server ignores the offset.
     */
    fun sync(page: Int = 500): SubsonicIndex {
        val extensions = extensions()
        romanization = ROMANIZATION in extensions
        val songs = paged(page, SubSong::id) { offset ->
            call("search3", listOf("query" to "\"\"", "songCount" to "$page", "songOffset" to "$offset", "albumCount" to "0", "artistCount" to "0"))["searchResult3"].obj()?.get("song").arr().orEmpty().mapNotNull { song(it, romanization) }
        }
        val albums = paged(page, SubAlbum::id) { offset ->
            call("getAlbumList2", listOf("type" to "alphabeticalByName", "size" to "$page", "offset" to "$offset"))["albumList2"].obj()?.get("album").arr().orEmpty().mapNotNull { album(it, romanization) }
        }
        val artists = call("getArtists")["artists"].obj()?.get("index").arr().orEmpty()
            .flatMap { it.obj()?.get("artist").arr().orEmpty() }.mapNotNull { artist(it, romanization) }.distinctBy { it.id }
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
    fun albumList(type: String, size: Int = 20) = call("getAlbumList2", listOf("type" to type, "size" to "$size"))["albumList2"].obj()?.get("album").arr().orEmpty().mapNotNull { album(it, romanization) }

    fun playlists() = call("getPlaylists")["playlists"].obj()?.get("playlist").arr().orEmpty().mapNotNull { p ->
        val o = p.obj() ?: return@mapNotNull null
        SubPlaylist(o["id"].str() ?: return@mapNotNull null, o["name"].str() ?: "", o["owner"].str() ?: "", o["songCount"].str()?.toIntOrNull() ?: 0, o["coverArt"].str(), o["oasisMix"].str() == "true", latin(o, "name", romanization))
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
        return (album(a, romanization) ?: throw SubsonicException(70, "No such album")) to a.obj()?.get("song").arr().orEmpty().mapNotNull { song(it, romanization) }
    }

    fun artist(id: String): Pair<SubArtist, List<SubAlbum>> {
        val a = call("getArtist", listOf("id" to id))["artist"] ?: throw SubsonicException(70, "No such artist")
        return (artist(a, romanization) ?: throw SubsonicException(70, "No such artist")) to a.obj()?.get("album").arr().orEmpty().mapNotNull { album(it, romanization) }
    }

    /** The server's own search. */
    /** The artist's top songs. */
    fun topSongs(artist: String, count: Int = 50) = call("getTopSongs", listOf("artist" to artist, "count" to "$count"))["topSongs"].obj()?.get("song").arr().orEmpty().mapNotNull { song(it, romanization) }

    fun search(query: String, count: Int = 30): Triple<List<SubSong>, List<SubAlbum>, List<SubArtist>> {
        val r = call("search3", listOf("query" to query, "songCount" to "$count", "albumCount" to "$count", "artistCount" to "$count"))["searchResult3"].obj()
        return Triple(r?.get("song").arr().orEmpty().mapNotNull { song(it, romanization) }, r?.get("album").arr().orEmpty().mapNotNull { album(it, romanization) }, r?.get("artist").arr().orEmpty().mapNotNull { artist(it, romanization) })
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
            // Layers needn't line up one to one: synced lines pair by start time, plain ones by position.
            val p = (if (synced && pronSynced) latin.minByOrNull { abs(it.startMs - line.startMs) }?.takeIf { abs(it.startMs - line.startMs) <= 1000 }
                else latin.getOrNull(i)?.takeIf { latin.size == own.size }) ?: return@mapIndexed line
            // Each timed word gets the romaji timed from its start to the next word's; romaji sung before the first word goes on it.
            val wordLatin = if (line.words.isEmpty() || p.words.isEmpty()) emptyList() else line.words.mapIndexed { k, w ->
                val end = line.words.getOrNull(k + 1)?.startMs ?: Long.MAX_VALUE
                p.words.filter { it.startMs < end && (k == 0 || it.startMs >= w.startMs) }.joinToString("") { it.text }
            }
            Line(line.startMs, line.text, line.words, latin = p.text.ifBlank { line.text }, wordLatin = wordLatin)
        }
        return Lyrics(source, lines, synced).takeIf { lines.any { it.text.isNotBlank() } }
    }

    // One structuredLyrics entry's lines; with cueLine, their timed words.
    private fun lines(l: JsonObject): List<Line> {
        val synced = l["synced"].str() == "true"
        val offset = l["offset"].str()?.toLongOrNull() ?: 0
        // Several cueLines on one line are its voices, the main one first.
        val cues = l["cueLine"].arr().orEmpty().mapNotNull { it.obj() }.groupBy { it["index"].int() }.mapValues { it.value.first() }
        return l["line"].arr().orEmpty().mapNotNull { it.obj() }.mapIndexed { i, o ->
            Line(if (synced) (o["start"].str()?.toLongOrNull() ?: 0) + offset else 0, o["value"].str() ?: "", if (synced) cues[i]?.let { words(it, offset) }.orEmpty() else emptyList())
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
            Word(start + offset, end + offset, text)
        }
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

        const val ROMANIZATION = "romanization"

        /** [o]'s "<field>Latin" with [latin] (asked with romanization=both), the original where the server sent none; else null. */
        fun latin(o: JsonObject, field: String, latin: Boolean) = if (latin) o[field + "Latin"].str() ?: o[field].str() ?: "" else null

        fun song(e: JsonElement, latin: Boolean = false): SubSong? {
            val o = e.obj() ?: return null
            if (o["isVideo"].str() == "true") return null
            return SubSong(
                o["id"].str() ?: return null, o["title"].str() ?: "", o["artist"].str() ?: "", o["artistId"].str(), o["album"].str(), o["albumId"].str(),
                o["track"].int(), o["discNumber"].int(), o["year"].int(), o["duration"].int(), o["coverArt"].str(), o["starred"] != null, o["created"].str(),
                o["suffix"].str(), o["bitRate"].int(), o["path"].str(), latin(o, "title", latin), latin(o, "artist", latin), latin(o, "album", latin)?.takeIf { o["album"] != null },
            )
        }

        fun album(e: JsonElement, latin: Boolean = false): SubAlbum? {
            val o = e.obj() ?: return null
            val name = o["name"].str() ?: o["title"].str() ?: ""
            return SubAlbum(o["id"].str() ?: return null, name, o["artist"].str() ?: "", o["artistId"].str(), o["year"].int(), o["coverArt"].str(), o["songCount"].int(), o["created"].str(), o["starred"] != null, o["releaseTypes"].arr()?.firstOrNull().str(), o["oasisWanted"].str(), o["oasisWantedError"].str(), if (latin) o["nameLatin"].str() ?: o["titleLatin"].str() ?: name else null, latin(o, "artist", latin))
        }

        fun artist(e: JsonElement, latin: Boolean = false): SubArtist? {
            val o = e.obj() ?: return null
            return SubArtist(o["id"].str() ?: return null, o["name"].str() ?: "", o["coverArt"].str(), o["albumCount"].int(), o["starred"] != null, o["oasisWanted"].str(), o["oasisWantedError"].str(), latin(o, "name", latin))
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
