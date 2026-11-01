package roma.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The client and the index sync against a small fake server on localhost, which answers like an OpenSubsonic server. */
class SubsonicTest {
    private val calls = ArrayList<Pair<String, List<Pair<String, String>>>>()
    private var ignoreOffset = false
    private val songs = (1..7).map { """{"id":"tr-$it","title":"夜に駆ける $it","artist":"YOASOBI","artistId":"ar-1","album":"THE BOOK","albumId":"al-1","track":$it,"duration":${200 + it},"coverArt":"al-1"${if (it == 3) ""","starred":"2026-10-30T10:00:00Z"""" else ""}}""" }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/rest/") { ex ->
            val method = ex.requestURI.path.removePrefix("/rest/")
            val query = ex.requestURI.rawQuery
            val params = query.split("&").map { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), "UTF-8") }
            fun p(k: String) = params.firstOrNull { it.first == k }?.second
            calls += method to params
            val authed = p("u") == "admin" && SubsonicClient.md5("secret" + p("s")) == p("t")
            val body = if (!authed) """"status":"failed","error":{"code":40,"message":"Wrong username or password"}""" else """"status":"ok",""" + when (method) {
                "ping" -> """"x":1"""
                "getOpenSubsonicExtensions" -> """"openSubsonicExtensions":[{"name":"formPost","versions":[1]},{"name":"songLyrics","versions":[1]}]"""
                "search3" -> {
                    val offset = if (ignoreOffset) 0 else p("songOffset")!!.toInt()
                    """"searchResult3":{"song":[${songs.drop(offset).take(p("songCount")!!.toInt()).joinToString(",")}]}"""
                }
                "getAlbumList2" -> """"albumList2":{"album":[{"id":"al-1","name":"THE BOOK","artist":"YOASOBI","artistId":"ar-1","songCount":7,"coverArt":"al-1"}]}"""
                "getArtists" -> """"artists":{"index":[{"name":"Y","artist":[{"id":"ar-1","name":"YOASOBI","albumCount":1}]}]}"""
                "getLyricsBySongId" -> """"lyricsList":{"structuredLyrics":[{"synced":false,"line":[{"value":"plain"}]},{"synced":true,"offset":-100,"line":[{"start":1000,"value":"沈むように"},{"start":4000,"value":"溶けてゆくように"}]}]}"""
                "getSong" -> """"song":{"id":"tr-8","title":"Lyra","artist":"Breaking Benjamin","suffix":"flac","bitRate":1411,"path":"Breaking Benjamin/Lyra.flac"}"""
                "getAlbum" -> """"album":{"id":"al-9","name":"STRAY SHEEP","starred":"2026-10-31T01:00:00Z","oasisWanted":"wanted","oasisWantedError":"searching","artist":"米津玄師","artistId":"ar-9","song":[${songs[0]},${songs[1]}]}"""
                "getArtist" -> """"artist":{"id":"ar-9","name":"米津玄師","album":[{"id":"al-9","name":"STRAY SHEEP"},{"id":"al-10","name":"Lemon","year":2018,"releaseTypes":["Single"]}]}"""
                "getUser" -> """"user":{"username":"${p("username")}","scrobblingEnabled":true,"adminRole":true}"""
                "getTopSongs" -> """"topSongs":{"song":[${songs[2]}]}"""
                "getSimilarSongs" -> """"similarSongs":{"song":[${songs[4]}]}"""
                "getPlaylists" -> """"playlists":{"playlist":[{"id":"1","name":"Radio","owner":"admin"},{"id":"2","name":"Favourites","owner":"admin"},""" +
                    """{"id":"3","name":"Replay Mix","owner":"YouTube Music","oasisMix":true},{"id":"4","name":"Archive Mix","owner":"YouTube Music","oasisMix":true}]}"""
                "scrobble", "star" -> """"x":1"""
                else -> """"x":1"""
            }
            val json = """{"subsonic-response":{$body,"version":"1.16.1","type":"navidrome","openSubsonic":true}}""".toByteArray()
            ex.sendResponseHeaders(200, json.size.toLong())
            ex.responseBody.use { it.write(json) }
        }
        start()
    }
    private val url = "http://127.0.0.1:${server.address.port}/"

    @AfterTest fun stop() = server.stop(0)

    @Test fun idsRoundTrip() {
        for (raw in listOf("tr-1", "a1b2C3_x", "folder/song 1.mp3", "曲", "s.x")) {
            val id = SubsonicIds.encode(raw)
            assertTrue(SubsonicIds.of(id))
            assertTrue(id.removePrefix("s.").all { it.isLetterOrDigit() && it.code < 128 || it in "_-." }, id)
            assertEquals(raw, SubsonicIds.decode(id))
        }
        assertEquals("s.tr-1", SubsonicIds.encode("tr-1"))
        assertEquals("s.a.2fb", SubsonicIds.encode("a/b"))
    }

    @Test fun tokenAuth() {
        val c = SubsonicClient(url, "admin", "secret")
        c.ping()
        val ps = calls.last().second.toMap()
        assertEquals("roma", ps["c"])
        assertEquals("json", ps["f"])
        assertTrue("p" !in ps) // the password itself never goes out
        assertEquals(listOf("formPost", "songLyrics"), c.extensions())
    }

    @Test fun wrongPassword() {
        val e = assertFailsWith<SubsonicException> { SubsonicClient(url, "admin", "nope").ping() }
        assertEquals(40, e.code)
        assertEquals("Wrong username or password", e.message)
    }

    @Test fun notSubsonic() {
        server.createContext("/plain/rest/") { ex -> ex.sendResponseHeaders(404, -1); ex.close() }
        val e = assertFailsWith<SubsonicException> { SubsonicClient(url + "plain", "admin", "secret").ping() }
        assertEquals("The server answered HTTP 404", e.message)
    }

    @Test fun syncPagesThroughEverything() {
        val index = SubsonicClient(url.removePrefix("http://"), "admin", "secret").sync(page = 3) // no scheme: http
        assertEquals((1..7).map { "tr-$it" }, index.songs.map { it.id })
        // 3 + 3 + 1: the short page ends it.
        assertEquals(listOf("0", "3", "6"), calls.filter { it.first == "search3" }.map { it.second.toMap()["songOffset"] })
        assertEquals("\"\"", calls.first { it.first == "search3" }.second.toMap()["query"])
        assertEquals(listOf(3), index.songs.filter { it.starred }.map { it.track })
        assertEquals("THE BOOK", index.albums.single().name)
        assertEquals("YOASOBI", index.artists.single().name)
        assertEquals(203, index.songs[2].duration)
        assertEquals(true, index.serverScrobbles)
    }

    @Test fun syncStopsWhenOffsetIsIgnored() {
        ignoreOffset = true
        val index = SubsonicClient(url, "admin", "secret").sync(page = 3)
        assertEquals(3, index.songs.size)
        assertEquals(2, calls.count { it.first == "search3" })
    }

    @Test fun syncedLyricsWin() {
        val l = SubsonicClient(url, "admin", "secret").lyrics("tr-1", "Your server")!!
        assertTrue(l.synced)
        assertEquals(listOf(900L, 3900L), l.lines.map { it.startMs })
        assertEquals("沈むように", l.lines[0].text)
        assertEquals(l.lines.size, SubsonicLyrics { SubsonicClient(url, "admin", "secret") }.fetch(LyricsQuery("s.tr-1", "", "", null, 0))!!.lines.size)
        assertEquals("tr-1", calls.last().second.toMap()["id"])
    }

    @Test fun scrobbleSendsIdsAndTimes() {
        SubsonicClient(url, "admin", "secret").scrobble(listOf("tr-1", "tr-2"), listOf(1_000L, 2_000L), submission = true)
        val ps = calls.last().second
        assertEquals(listOf("tr-1", "tr-2"), ps.filter { it.first == "id" }.map { it.second })
        assertEquals(listOf("1000", "2000"), ps.filter { it.first == "time" }.map { it.second })
        assertEquals("true", ps.toMap()["submission"])
    }

    @Test fun songsRomanizedWithEncodedIds() {
        val sub = SubsonicClient.song(kotlinx.serialization.json.Json.parseToJsonElement(songs[0]))!!
        val s = Romanizer(jvmIcu).song(sub)
        assertEquals("s.tr-1", s.id)
        assertEquals("s.ar-1", s.firstArtistId)
        assertEquals("s.al-1", s.album?.id)
        assertEquals("subsonic:al-1", s.thumb)
        assertEquals(201, s.durationSec)
        assertEquals("夜に駆ける 1", s.title.original)
        assertTrue(s.title.latin.startsWith("Yoru ni Kakeru"), s.title.latin)
    }

    @Test fun pagesOnDemand() {
        val c = SubsonicClient(url, "admin", "secret")
        val (album, tracks) = c.album("al-9")
        assertEquals("STRAY SHEEP", album.name)
        assertTrue(album.starred)
        assertEquals("wanted", album.wanted)
        assertEquals("searching", album.wantedError)
        assertEquals(listOf("tr-1", "tr-2"), tracks.map { it.id })
        val (artist, albums) = c.artist("ar-9")
        assertEquals("米津玄師", artist.name)
        assertEquals(listOf("al-9", "al-10"), albums.map { it.id })
        assertEquals(listOf(null, "Single"), albums.map { it.releaseType })
        assertEquals(listOf("tr-3"), c.topSongs("米津玄師").map { it.id })
        assertEquals("米津玄師", calls.last().second.toMap()["artist"])
        assertEquals(listOf("tr-5"), c.similarSongs("tr-1").map { it.id })
        assertEquals("tr-1", calls.last().second.toMap()["id"])
    }

    @Test fun songFile() {
        val lyra = SubsonicClient(url, "admin", "secret").song("tr-8")
        assertEquals("Breaking Benjamin/Lyra.flac", lyra.path)
        assertEquals(1411, lyra.bitRate)
        assertNull(SubsonicClient.song(kotlinx.serialization.json.Json.parseToJsonElement(songs[0]))!!.path)
    }

    @Test fun mixesMarked() {
        val all = SubsonicClient(url, "admin", "secret").playlists()
        assertEquals(listOf("Radio", "Favourites"), all.filter { !it.mix }.map { it.name })
        assertEquals(listOf("Replay Mix", "Archive Mix"), all.filter { it.mix }.map { it.name }) // in the server's order
    }

    @Test fun mediaUrlsCarryAuth() {
        val c = SubsonicClient(url, "admin", "secret")
        val q = URI(c.coverUrl("al-1", 96)).rawQuery.split("&").associate { it.substringBefore("=") to it.substringAfter("=") }
        assertEquals("al-1", q["id"])
        assertEquals("96", q["size"])
        assertEquals(SubsonicClient.md5("secret" + q["s"]), q["t"])
        assertNull(q["p"])
    }
}
