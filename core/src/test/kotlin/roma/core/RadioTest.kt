package roma.core

import kotlin.test.Test
import kotlin.test.assertEquals

class RadioTest {
    private fun song(id: String, title: String, artist: String, video: Boolean = false) =
        Song(id, Text(title, title), listOf(Named(artist, Text(artist, artist))), video = video)

    private val seed = song("s", "Seed", "A")

    @Test fun filters() {
        val c = listOf(
            song("1", "Song One", "A"),
            song("1v", "Song One (Official Video)", "A", video = true), // its audio track is already there
            song("2", "Song Two (Live)", "A"),
            song("3", "Song Three - sped up", "B"),
            song("4", "Banned", "A"),
            song("5", "By banned artist", "X"),
            song("6", "Seed", "A"), // the same song as the seed
            song("7", "Other", "R"),
        )
        val bans = Bans(songs = setOf("4"), artists = setOf("X"))
        val got = filterRadio(c, seed, setOf("R"), listOf(seed), bans, RadioSettings(leash = "free"))
        assertEquals(listOf("1", "7"), got.map { it.id })
        assertEquals(listOf("same artist", "related artist"), got.map { it.reason })
    }

    @Test fun offWhenDisabled() {
        val c = listOf(song("2", "Song Two (Live)", "A"), song("5", "x", "X"))
        val got = filterRadio(c, seed, emptySet(), emptyList(), Bans(artists = setOf("X")), RadioSettings("free", false, false, false))
        assertEquals(listOf("2", "5"), got.map { it.id })
    }

    @Test fun leash() {
        val c = listOf(song("1", "a", "Z"), song("2", "b", "A"), song("3", "c", "Y"), song("4", "d", "W"), song("5", "e", "R"))
        assertEquals(listOf("2", "5"), filterRadio(c, seed, setOf("R"), emptyList(), Bans(), RadioSettings("close")).map { it.id })
        // Medium: an off-leash song only while there are fewer of them than on-leash ones.
        assertEquals(listOf("2", "3", "5"), filterRadio(c, seed, setOf("R"), emptyList(), Bans(), RadioSettings("medium")).map { it.id })
        assertEquals(5, filterRadio(c, seed, setOf("R"), emptyList(), Bans(), RadioSettings("free")).size)
    }

    @Test fun scrobbleRule() {
        assertEquals(false, shouldScrobble(30, 30))
        assertEquals(true, shouldScrobble(200, 100))
        assertEquals(false, shouldScrobble(200, 99))
        assertEquals(true, shouldScrobble(600, 240))
    }

    @Test fun lastFmSignature() {
        // Sorted name+value pairs, then the secret, MD5.
        val fm = LastFm("key", "secret")
        assertEquals(md5("api_keykeymethodauth.getSessiontokentsecret"), fm.sign(mapOf("method" to "auth.getSession", "token" to "t", "api_key" to "key")))
        assertEquals("b", fm.batchParams("sk", listOf(Scrobble("a", "b", null, 1, 100)))["track[0]"])
    }

    private fun md5(s: String) = java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
