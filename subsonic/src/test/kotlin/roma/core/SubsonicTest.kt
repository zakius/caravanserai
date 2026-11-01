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
    // The extension romanization: listed, and with romanization=both the Latin forms where they differ (none for song 2).
    private var romanizing = false
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
                "getOpenSubsonicExtensions" -> """"openSubsonicExtensions":[{"name":"formPost","versions":[1]},{"name":"songLyrics","versions":[1]}${if (romanizing) """,{"name":"romanization","versions":[1]}""" else ""}]"""
                "search3" -> {
                    val offset = if (ignoreOffset) 0 else p("songOffset")!!.toInt()
                    val latin = p("romanization") == "both"
                    """"searchResult3":{"song":[${songs.drop(offset).take(p("songCount")!!.toInt()).map { if (latin && "駆ける 2" !in it) it.replace("}", ""","titleLatin":"Yoru ni Kakeru","albumLatin":"za bukku"}""") else it }.joinToString(",")}]}"""
                }
                "getAlbumList2" -> """"albumList2":{"album":[{"id":"al-1","name":"THE BOOK","artist":"YOASOBI","artistId":"ar-1","songCount":7,"coverArt":"al-1"}]}"""
                "getArtists" -> """"artists":{"index":[{"name":"Y","artist":[{"id":"ar-1","name":"YOASOBI","albumCount":1}]}]}"""
                // songLyrics v2 for tr-1, every layer whatever kinds says (a server that ignores it); tr-2 has no pronunciation.
                "getLyricsBySongId" -> if (p("enhanced") == "true") enhancedLyrics(p("id")!!) else """"lyricsList":{"structuredLyrics":[{"synced":false,"line":[{"value":"plain"}]},{"synced":true,"offset":-100,"line":[{"start":1000,"value":"沈むように"},{"start":4000,"value":"溶けてゆくように"}]}]}"""
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

    private fun enhancedLyrics(id: String): String {
        val main = """{"kind":"main","lang":"ja","synced":true,"line":[{"start":1000,"value":"君の声"},{"start":4000,"value":"花火"}],""" +
            // 君 の 声: three bytes a character
            """"cueLine":[{"index":0,"start":1000,"end":3000,"value":"君の声","cue":[{"start":1000,"end":1500,"value":"君","byteStart":0,"byteEnd":2},{"start":1500,"end":2000,"value":"の","byteStart":3,"byteEnd":5},{"start":2000,"end":3000,"value":"声","byteStart":6,"byteEnd":8}]}]}"""
        val translation = """{"kind":"translation","lang":"en","synced":true,"line":[{"start":1000,"value":"Your voice"},{"start":4000,"value":"Fireworks"}]}"""
        val pron = """{"kind":"pronunciation","lang":"ja-Latn","synced":true,"line":[{"start":1000,"value":"kimi no koe"},{"start":4010,"value":"hanabi"}],""" +
            """"cueLine":[{"index":0,"start":1000,"end":3000,"value":"kimi no koe","cue":[{"start":1000,"end":1500,"value":"kimi","byteStart":0,"byteEnd":3},{"start":1500,"end":2000,"value":"no","byteStart":5,"byteEnd":6},{"start":2000,"end":3000,"value":"koe","byteStart":8,"byteEnd":10}]}]}"""
        return """"lyricsList":{"structuredLyrics":[$translation,$main${if (id == "tr-1") ",$pron" else ""}]}"""
    }

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
        assertEquals(l.lines.size, SubsonicLyrics({ SubsonicClient(url, "admin", "secret") }, { null }).fetch(LyricsQuery("s.tr-1", "", "", null, 0))!!.lines.size)
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

    @Test fun romanizationOnlyWithTheExtension() {
        val plain = SubsonicClient(url, "admin", "secret").sync(page = 10)
        assertTrue(SubsonicClient.ROMANIZATION !in plain.extensions)
        assertTrue(calls.none { it.second.toMap()["romanization"] != null })
        assertNull(plain.songs[0].titleLatin)
        romanizing = true
        calls.clear()
        val c = SubsonicClient(url, "admin", "secret")
        val index = c.sync(page = 10)
        assertTrue(c.romanization)
        assertTrue(SubsonicClient.ROMANIZATION in index.extensions) // what keeps the Japanese dictionary away (SubsonicSource.romanizes)
        assertTrue(calls.filter { it.first != "getOpenSubsonicExtensions" }.all { it.second.toMap()["romanization"] == "both" })
        val r = Romanizer(jvmIcu, dictionary = { error("no dictionary") })
        // The server's Latin forms, not the device's
        val s = r.song(index.songs[0])
        assertEquals("Yoru ni Kakeru", s.title.latin)
        assertEquals("za bukku", s.album!!.name.latin)
        assertEquals("YOASOBI", s.artistLatin)
        // No …Latin field: the original as it is
        val two = r.song(index.songs[1])
        assertEquals("夜に駆ける 2", two.title.latin)
        // A correction still wins, and romanizing again keeps the server's form
        r.overrides = listOf(Override("song", "s.tr-1", "夜に駆ける 1", "Racing into the Night"))
        assertEquals("Racing into the Night", r.again(s).title.latin)
        r.overrides = emptyList()
        assertEquals("Yoru ni Kakeru", r.again(s).title.latin)
    }

    @Test fun lyricsLayers() {
        val c = SubsonicClient(url, "admin", "secret")
        val l = c.lyrics("tr-1", "Your server", listOf("main", "pronunciation"))!!
        val ps = calls.last().second.toMap()
        assertEquals("true", ps["enhanced"])
        assertEquals("main,pronunciation", ps["kinds"])
        // The translation sent anyway is left out; the pronunciation pairs by start time (4010 with 4000).
        assertEquals(listOf("君の声", "花火"), l.lines.map { it.text })
        assertEquals(listOf("kimi no koe", "hanabi"), l.lines.map { it.latin })
        assertEquals(listOf("君", "の", "声"), l.lines[0].words.map { it.text })
        assertEquals(listOf(1000L, 1500L, 2000L), l.lines[0].words.map { it.startMs })
        assertEquals(listOf("kimi ", "no ", "koe"), l.lines[0].wordLatin)
        // Romanized only: the pronunciation is the lyrics, its cues the words.
        val romaji = c.lyrics("tr-1", "Your server", listOf("pronunciation"))!!
        assertEquals("pronunciation", calls.last().second.toMap()["kinds"])
        assertEquals("君の声", romaji.lines[0].text) // this server ignored kinds and sent main too
        // No pronunciation for the song: its main lyrics, romanized on the device as without the extension.
        val none = c.lyrics("tr-2", "Your server", listOf("main", "pronunciation"))!!
        assertEquals("", none.lines[0].latin)
        assertTrue(none.lines[0].words.isNotEmpty())
    }

    @Test fun pronunciationAlone() {
        server.createContext("/pron/rest/") { ex ->
            val kinds = ex.requestURI.rawQuery.split("&").firstOrNull { it.startsWith("kinds=") }?.let { URLDecoder.decode(it.substringAfter("="), "UTF-8") }
            calls += "kinds" to listOf("kinds" to (kinds ?: ""))
            val body = when (kinds) {
                "pronunciation" -> if ("id=tr-2" in ex.requestURI.rawQuery) "" else
                    """{"kind":"pronunciation","synced":true,"line":[{"start":1000,"value":"kimi no koe"}],"cueLine":[{"index":0,"value":"kimi no koe","cue":[{"start":1000,"value":"kimi","byteStart":0,"byteEnd":3},{"start":1500,"value":"no","byteStart":5,"byteEnd":6},{"start":2000,"end":3000,"value":"koe","byteStart":8,"byteEnd":10}]}]}"""
                else -> """{"kind":"main","synced":false,"line":[{"value":"Hello"}]}"""
            }
            val json = """{"subsonic-response":{"status":"ok","lyricsList":{"structuredLyrics":[$body]}}}""".toByteArray()
            ex.sendResponseHeaders(200, json.size.toLong())
            ex.responseBody.use { it.write(json) }
        }
        val c = SubsonicClient(url + "pron", "admin", "secret")
        val l = c.lyrics("tr-1", "Your server", listOf("pronunciation"))!!
        assertEquals("kimi no koe", l.lines[0].text)
        assertEquals(listOf("kimi ", "no ", "koe"), l.lines[0].words.map { it.text })
        assertEquals(listOf(1500L, 2000L, 3000L), l.lines[0].words.map { it.endMs })
        // A song in Latin script has no pronunciation: asked again for its main lyrics.
        calls.clear()
        assertEquals("Hello", c.lyrics("tr-2", "Your server", listOf("pronunciation"))!!.lines[0].text)
        assertEquals(listOf("pronunciation", "main"), calls.map { it.second[0].second })
    }
}
