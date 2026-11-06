package roma.core

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The client and the index sync against a small fake server on localhost, which answers like an OpenSubsonic server. */
class SubsonicTest {
    private val calls = ArrayList<Pair<String, List<Pair<String, String>>>>()
    private var ignoreOffset = false
    // The extension romanization is listed, and with romanization=both the server sends Latin forms where they
    // differ from the original (none for song 2).
    private var romanizing = false
    // The extension oasis is listed, so playlists' oasisMix and albums' oasisWanted count.
    private var oasisListed = false
    private var shareListed = false
    // getOpenSubsonicExtensions answers with this HTTP status and no Subsonic body, like a proxy's 503 or a plain
    // server's 404.
    private var extensionsHttp: Int? = null
    // An empty search3 finds nothing, as on Airsonic.
    private var emptySearch = false
    private val songs = (1..7).map { n ->
        val starred = if (n == 3) ""","starred":"2026-10-30T10:00:00Z"""" else ""
        """{"id":"tr-$n","title":"夜に駆ける $n","artist":"YOASOBI","artistId":"ar-1","album":"THE BOOK","albumId":"al-1","track":$n,"duration":${200 + n},"coverArt":"al-1"$starred}"""
    }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/rest/") { ex ->
            val method = ex.requestURI.path.removePrefix("/rest/")
            val query = ex.requestURI.rawQuery
            val params = query.split("&").map { pair ->
                pair.substringBefore("=") to URLDecoder.decode(pair.substringAfter("="), "UTF-8")
            }
            fun p(k: String) = params.firstOrNull { it.first == k }?.second
            calls += method to params
            val authed = p("u") == "admin" && SubsonicClient.md5("secret" + p("s")) == p("t")
            if (method == "getOpenSubsonicExtensions" && extensionsHttp != null) {
                ex.sendResponseHeaders(extensionsHttp!!, -1)
                ex.close()
                return@createContext
            }
            val ids = params.filter { it.first == "id" }.map { it.second }
            fun failed(code: Int, message: String) = """"status":"failed","error":{"code":$code,"message":"$message"}"""
            // Scrobbling: a song the server no longer has (tr-gone) fails with 70, a busy database (tr-boom) with 0.
            val body = if (!authed) {
                failed(40, "Wrong username or password")
            } else if (method == "scrobble" && "tr-gone" in ids) {
                failed(70, "Song not found")
            } else if (method == "scrobble" && "tr-boom" in ids) {
                failed(0, "Database busy")
            } else {
                """"status":"ok",""" + when (method) {
                    "ping" -> """"x":1"""
                    "getOpenSubsonicExtensions" -> {
                        var list = """"openSubsonicExtensions":[{"name":"formPost","versions":[1]},{"name":"songLyrics","versions":[1,2]}"""
                        if (oasisListed) list += """,{"name":"oasis","versions":[1]}"""
                        if (shareListed) list += """,{"name":"shareUrl","versions":[1]}"""
                        if (romanizing) list += """,{"name":"romanization","versions":[1]}"""
                        list + "]"
                    }
                    "search3" -> {
                        val offset = if (emptySearch) songs.size else if (ignoreOffset) 0 else p("songOffset")!!.toInt()
                        val latin = p("romanization") == "both"
                        val page = songs.drop(offset).take(p("songCount")!!.toInt())
                        val listed = page.map { song ->
                            if (latin && "駆ける 2" !in song) {
                                song.replace("}", ""","titleLatin":"Yoru ni Kakeru","albumLatin":"za bukku"}""")
                            } else {
                                song
                            }
                        }
                        """"searchResult3":{"song":[${listed.joinToString(",")}]}"""
                    }
                    "getAlbumList2" -> """"albumList2":{"album":[{"id":"al-1","name":"THE BOOK","artist":"YOASOBI","artistId":"ar-1","songCount":7,"coverArt":"al-1"}]}"""
                    "getArtists" -> """"artists":{"index":[{"name":"Y","artist":[{"id":"ar-1","name":"YOASOBI","albumCount":1}]}]}"""
                    // songLyrics v2 for tr-1 sends every layer whatever kinds says, like a server that ignores it.
                    // tr-2 has no pronunciation.
                    "getLyricsBySongId" -> if (p("enhanced") == "true") {
                        enhancedLyrics(p("id")!!)
                    } else {
                        """"lyricsList":{"structuredLyrics":[{"synced":false,"line":[{"value":"plain"}]},{"synced":true,"offset":-100,"line":[{"start":1000,"value":"沈むように"},{"start":4000,"value":"溶けてゆくように"}]}]}"""
                    }
                    "getSong" -> """"song":{"id":"tr-8","title":"Lyra","artist":"Breaking Benjamin","suffix":"flac","bitRate":1411,"path":"Breaking Benjamin/Lyra.flac","shareUrl":"https://example.org/s/tr-8"}"""
                    "getAlbum" -> if (p("id") == "al-1") {
                        """"album":{"id":"al-1","name":"THE BOOK","song":[${songs.joinToString(",")}]}"""
                    } else {
                        """"album":{"id":"al-9","name":"STRAY SHEEP","starred":"2026-10-31T01:00:00Z","oasisWanted":"wanted","oasisWantedError":"searching","artist":"米津玄師","artistId":"ar-9","song":[${songs[0]},${songs[1]}]}"""
                    }
                    "getArtist" -> """"artist":{"id":"ar-9","name":"米津玄師","album":[{"id":"al-9","name":"STRAY SHEEP"},{"id":"al-10","name":"Lemon","year":2018,"releaseTypes":["Single"]}]}"""
                    // Over 5 MB of JSON, as a big library's playlist.
                    "getPlaylist" -> {
                        val entries = (1..20_000).joinToString(",") { n -> songs[0].replace("tr-1", "tr-$n") }
                        """"playlist":{"id":"big","name":"Everything","entry":[$entries]}"""
                    }
                    "getUser" -> """"user":{"username":"${p("username")}","scrobblingEnabled":true,"adminRole":true}"""
                    "getTopSongs" -> """"topSongs":{"song":[${songs[2]}]}"""
                    "getSimilarSongs" -> """"similarSongs":{"song":[${songs[4]}]}"""
                    "getPlaylists" -> """"playlists":{"playlist":[{"id":"1","name":"Radio","owner":"admin"},{"id":"2","name":"Favourites","owner":"admin"},""" +
                        """{"id":"3","name":"Replay Mix","owner":"YouTube Music","oasisMix":true},{"id":"4","name":"Archive Mix","owner":"YouTube Music","oasisMix":true}]}"""
                    "scrobble", "star" -> """"x":1"""
                    else -> """"x":1"""
                }
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
            // Each of 君, の and 声 is three bytes in UTF-8.
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
        assertEquals("caravan", ps["c"])
        assertEquals("json", ps["f"])
        assertTrue("p" !in ps) // the password itself never goes out
        assertEquals(mapOf("formPost" to listOf(1), "songLyrics" to listOf(1, 2)), c.extensions())
    }

    @Test fun extensionsOnlyEmptyWhenTheServerSaysSo() {
        val c = SubsonicClient(url, "admin", "secret")
        // A plain Subsonic server: a 404 or a Subsonic error is "no extensions".
        extensionsHttp = 404
        assertEquals(emptyMap(), c.extensions())
        extensionsHttp = null
        assertEquals(emptyMap(), SubsonicClient(url, "admin", "nope").extensions())
        // A proxy's 503 is no answer: the sync fails rather than drop the extensions.
        extensionsHttp = 503
        assertEquals(503, assertFailsWith<SubsonicException> { c.extensions() }.http)
        assertFailsWith<SubsonicException> { c.sync() }
        assertFailsWith<java.io.IOException> { SubsonicClient("http://127.0.0.1:1", "admin", "secret").extensions() }
    }

    @Test fun addressWithoutSchemeIsHttps() {
        assertEquals("https://music.example.com", SubsonicClient("music.example.com/", "a", "b").base)
        assertEquals("http://192.168.1.10:4533", SubsonicClient("http://192.168.1.10:4533", "a", "b").base)
    }

    @Test fun plainHttpOnlyOnTheOwnNetwork() {
        for (h in listOf("localhost", "127.0.0.1", "192.168.1.10", "10.0.0.2", "172.20.1.1", "100.101.102.103", "[::1]", "fd12::1", "fe80::1", "nas", "nas.local", "music.lan", "box.home.arpa"))
            assertTrue(SubsonicClient.localHost(h), h)
        for (h in listOf("8.8.8.8", "172.32.0.1", "100.128.0.1", "2001:4860:4860::8888", ""))
            assertTrue(!SubsonicClient.localHost(h), h)
    }

    @Test fun bigAnswers() {
        assertEquals(20_000, SubsonicClient(url, "admin", "secret").playlist("big").second.size)
    }

    @Test fun replayGain() {
        fun gain(json: String): Float? {
            val song = songs[0].replace("}", ",$json}")
            return SubsonicClient.song(Json.parseToJsonElement(song))!!.gain
        }
        assertEquals(-7.5f, gain(""""replayGain":{"trackGain":-7.5,"albumGain":-6}"""))
        assertEquals(-6f, gain(""""replayGain":{"albumGain":-6}"""))
        assertNull(SubsonicClient.song(Json.parseToJsonElement(songs[0]))!!.gain)
    }

    @Test fun wrongPassword() {
        val e = assertFailsWith<SubsonicException> { SubsonicClient(url, "admin", "nope").ping() }
        assertEquals(40, e.code)
        assertEquals("Wrong username or password", e.message)
    }

    @Test fun notSubsonic() {
        server.createContext("/plain/rest/") { ex ->
            ex.sendResponseHeaders(404, -1)
            ex.close()
        }
        val e = assertFailsWith<SubsonicException> { SubsonicClient(url + "plain", "admin", "secret").ping() }
        assertEquals("The server answered HTTP 404", e.message)
    }

    @Test fun syncPagesThroughEverything() {
        val index = SubsonicClient(url, "admin", "secret").sync(page = 3)
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

    @Test fun syncAlbumByAlbumWhenTheEmptySearchFindsNothing() {
        emptySearch = true
        val index = SubsonicClient(url, "admin", "secret").sync(page = 3)
        assertEquals((1..7).map { "tr-$it" }, index.songs.map { it.id })
        assertEquals("al-1", calls.single { it.first == "getAlbum" }.second.toMap()["id"])
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
        // The offset of -100 shows the lines later.
        assertEquals(listOf(1100L, 4100L), l.lines.map { it.startMs })
        assertEquals("沈むように", l.lines[0].text)
        val provider = SubsonicLyrics({ SubsonicClient(url, "admin", "secret") }, { null })
        val fetched = provider.fetch(LyricsQuery("s.tr-1", "", "", null, 0))!!
        assertEquals(l.lines.size, fetched.lines.size)
        assertEquals("tr-1", calls.last().second.toMap()["id"])
    }

    @Test fun submitDropsMissingSongsAndStopsAtOtherFailures() {
        val c = SubsonicClient(url, "admin", "secret")
        assertEquals(2, c.submit(listOf("tr-1", "tr-2"), listOf(1L, 2L)))
        // The batch fails for tr-gone: one by one, tr-gone is dropped and every play is done with.
        assertEquals(3, c.submit(listOf("tr-1", "tr-gone", "tr-2"), listOf(1L, 2L, 3L)))
        // tr-boom fails otherwise: the two before it are done with, it and the rest stay queued.
        assertEquals(2, c.submit(listOf("tr-1", "tr-gone", "tr-boom", "tr-2"), listOf(1L, 2L, 3L, 4L)))
        assertEquals(0, assertFailsWith<SubsonicException> { c.submit(listOf("tr-boom"), listOf(1L)) }.code)
    }

    @Test fun scrobbleSendsIdsAndTimes() {
        SubsonicClient(url, "admin", "secret").scrobble(listOf("tr-1", "tr-2"), listOf(1_000L, 2_000L), submission = true)
        val ps = calls.last().second
        assertEquals(listOf("tr-1", "tr-2"), ps.filter { it.first == "id" }.map { it.second })
        assertEquals(listOf("1000", "2000"), ps.filter { it.first == "time" }.map { it.second })
        assertEquals("true", ps.toMap()["submission"])
    }

    @Test fun songsRomanizedWithEncodedIds() {
        val sub = SubsonicClient.song(Json.parseToJsonElement(songs[0]))!!
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
        // oasisWanted counts only with the extension oasis.
        assertNull(c.album("al-9").first.wanted)
        c.oasis = true
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
        assertNull(SubsonicClient.song(Json.parseToJsonElement(songs[0]))!!.path)
    }

    @Test fun mixesMarked() {
        val c = SubsonicClient(url, "admin", "secret")
        // oasisMix without the extension oasis is just a field: no mixes.
        c.sync()
        assertTrue(c.playlists().none { it.mix })
        oasisListed = true
        assertTrue(c.sync().supports(SubsonicClient.OASIS))
        val all = c.playlists()
        assertEquals(listOf("Radio", "Favourites"), all.filter { !it.mix }.map { it.name })
        // Mixes keep the server's order.
        assertEquals(listOf("Replay Mix", "Archive Mix"), all.filter { it.mix }.map { it.name })
    }

    @Test fun mediaUrlsCarryAuth() {
        val c = SubsonicClient(url, "admin", "secret")
        val rawQuery = URI(c.coverUrl("al-1", 96)).rawQuery
        val q = rawQuery.split("&").associate { pair -> pair.substringBefore("=") to pair.substringAfter("=") }
        assertEquals("al-1", q["id"])
        assertEquals("96", q["size"])
        assertEquals(SubsonicClient.md5("secret" + q["s"]), q["t"])
        assertNull(q["p"])
    }

    @Test fun shareUrl() {
        assertEquals("https://example.org/s/tr-8", SubsonicClient(url, "admin", "secret").song("tr-8").shareUrl)
        assertNull(SubsonicClient.song(Json.parseToJsonElement(songs[0]))!!.shareUrl)
        assertFalse(SubsonicClient(url, "admin", "secret").sync(page = 10).supports(SubsonicClient.SHARE_URL))
        shareListed = true
        assertTrue(SubsonicClient(url, "admin", "secret").sync(page = 10).supports(SubsonicClient.SHARE_URL))
    }

    @Test fun oasisOnlyWithTheExtension() {
        val plain = SubsonicClient(url, "admin", "secret")
        plain.sync(page = 10)
        val stream = plain.streamUrl("tr-1")
        assertTrue("oasis=" !in stream, stream)
        assertTrue(calls.none { it.second.toMap()["oasis"] != null })
        oasisListed = true
        calls.clear()
        val c = SubsonicClient(url, "admin", "secret")
        c.sync(page = 10)
        c.playlists()
        assertTrue(calls.filter { it.first != "getOpenSubsonicExtensions" }.all { it.second.toMap()["oasis"] == "1" })
        assertTrue("oasis=1" in c.coverUrl("al-1", 96))
    }

    @Test fun romanizationOnlyWithTheExtension() {
        val plain = SubsonicClient(url, "admin", "secret").sync(page = 10)
        assertTrue(SubsonicClient.ROMANIZATION !in plain.extensionVersions)
        assertTrue(calls.none { it.second.toMap()["romanization"] != null })
        assertNull(plain.songs[0].titleLatin)
        romanizing = true
        calls.clear()
        val c = SubsonicClient(url, "admin", "secret")
        val index = c.sync(page = 10)
        assertTrue(c.romanization)
        // This is what keeps the Japanese dictionary away (SubsonicSource.romanizes).
        assertTrue(SubsonicClient.ROMANIZATION in index.extensionVersions)
        assertTrue(calls.filter { it.first != "getOpenSubsonicExtensions" }.all { it.second.toMap()["romanization"] == "both" })
        val r = Romanizer(jvmIcu, dictionary = { error("no dictionary") })
        // The server's Latin forms win over the device's.
        val s = r.song(index.songs[0])
        assertEquals("Yoru ni Kakeru", s.title.latin)
        assertEquals("za bukku", s.album!!.name.latin)
        assertEquals("YOASOBI", s.artistLatin)
        // Without a …Latin field, the original stays as it is.
        val two = r.song(index.songs[1])
        assertEquals("夜に駆ける 2", two.title.latin)
        // A correction still wins, and romanizing again keeps the server's form.
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
        // The translation sent anyway is left out; the pronunciation pairs line by line (4010 with 4000).
        assertEquals(listOf("君の声", "花火"), l.lines.map { it.text })
        assertEquals(listOf("kimi no koe", "hanabi"), l.lines.map { it.latin })
        assertEquals(listOf("君", "の", "声"), l.lines[0].words.map { it.text })
        assertEquals(listOf(1000L, 1500L, 2000L), l.lines[0].words.map { it.startMs })
        assertEquals(listOf("kimi ", "no ", "koe"), l.lines[0].wordLatin)
        // Romanized only: the pronunciation is the lyrics, its cues the words.
        val romaji = c.lyrics("tr-1", "Your server", listOf("pronunciation"))!!
        assertEquals("pronunciation", calls.last().second.toMap()["kinds"])
        // This server ignored kinds and sent main too.
        assertEquals("君の声", romaji.lines[0].text)
        // No pronunciation for the song: its main lyrics, romanized on the device as without the extension.
        val none = c.lyrics("tr-2", "Your server", listOf("main", "pronunciation"))!!
        assertEquals("", none.lines[0].latin)
        assertTrue(none.lines[0].words.isNotEmpty())
    }

    @Test fun pronunciationAlone() {
        server.createContext("/pron/rest/") { ex ->
            val kindsParam = ex.requestURI.rawQuery.split("&").firstOrNull { it.startsWith("kinds=") }
            val kinds = if (kindsParam == null) null else URLDecoder.decode(kindsParam.substringAfter("="), "UTF-8")
            calls += "kinds" to listOf("kinds" to (kinds ?: ""))
            val body = when (kinds) {
                "pronunciation" -> if ("id=tr-2" in ex.requestURI.rawQuery) {
                    ""
                } else {
                    """{"kind":"pronunciation","synced":true,"line":[{"start":1000,"value":"kimi no koe"}],"cueLine":[{"index":0,"value":"kimi no koe","cue":[{"start":1000,"value":"kimi","byteStart":0,"byteEnd":3},{"start":1500,"value":"no","byteStart":5,"byteEnd":6},{"start":2000,"end":3000,"value":"koe","byteStart":8,"byteEnd":10}]}]}"""
                }
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

    @Test fun layersPairLineByLine() {
        server.createContext("/pairs/rest/") { ex ->
            // Same count, start times far apart (a server that timed the romaji itself) and two lines starting together.
            val main = """{"kind":"main","synced":true,"line":[{"start":1000,"value":"一"},{"start":1000,"value":"二"},{"start":5000,"value":"三"}]}"""
            val pron = """{"kind":"pronunciation","synced":true,"line":[{"start":1000,"value":"ichi"},{"start":1000,"value":"ni"},{"start":9000,"value":"san"}]}"""
            val json = """{"subsonic-response":{"status":"ok","lyricsList":{"structuredLyrics":[$main,$pron]}}}""".toByteArray()
            ex.sendResponseHeaders(200, json.size.toLong())
            ex.responseBody.use { it.write(json) }
        }
        val l = SubsonicClient(url + "pairs", "admin", "secret").lyrics("tr-1", "Your server", listOf("main", "pronunciation"))!!
        assertEquals(listOf("ichi", "ni", "san"), l.lines.map { it.latin })
    }
}
