package roma.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest

/**
 * One finished listen, in original (not romanized) metadata so it matches Last.fm's database. [id]: the server's song
 * id, for a Subsonic server's own scrobble.
 */
@Serializable
class Scrobble(val artist: String, val track: String, val album: String?, val timestamp: Long, val durationSec: Int, val id: String? = null)

/** Last.fm's rule: the track is longer than 30 s and was played for half its length or 4 minutes. */
fun shouldScrobble(durationSec: Int, playedSec: Int) = durationSec > 30 && playedSec >= minOf(durationSec / 2, 240)

/** Last.fm API calls. The user registers the key and secret; the session key comes from the browser auth flow. */
class LastFm(private val apiKey: String, private val secret: String) {
    fun authUrl(callback: String) = "https://www.last.fm/api/auth/?api_key=$apiKey&cb=$callback"

    /** Trades the token from the auth callback for a session key. */
    fun session(token: String): String {
        val json = call(mapOf("method" to "auth.getSession", "token" to token))
        return json.jsonObject["session"]!!.jsonObject["key"]!!.jsonPrimitive.content
    }

    fun nowPlaying(sk: String, s: Scrobble) {
        val p = mutableMapOf("method" to "track.updateNowPlaying", "sk" to sk, "artist" to s.artist, "track" to s.track, "duration" to s.durationSec.toString())
        s.album?.let { p["album"] = it }
        call(p)
    }

    /** Sends up to 50 scrobbles in one request. */
    fun scrobble(sk: String, batch: List<Scrobble>) {
        require(batch.size <= 50)
        call(batchParams(sk, batch))
    }

    fun batchParams(sk: String, batch: List<Scrobble>): Map<String, String> {
        val p = mutableMapOf("method" to "track.scrobble", "sk" to sk)
        batch.forEachIndexed { i, s ->
            p["artist[$i]"] = s.artist
            p["track[$i]"] = s.track
            p["timestamp[$i]"] = s.timestamp.toString()
            p["duration[$i]"] = s.durationSec.toString()
            s.album?.let { p["album[$i]"] = it }
        }
        return p
    }

    fun sign(params: Map<String, String>): String {
        val base = params.toSortedMap().entries.joinToString("") { it.key + it.value } + secret
        return MessageDigest.getInstance("MD5").digest(base.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun call(params: Map<String, String>): kotlinx.serialization.json.JsonElement {
        val all = params + ("api_key" to apiKey)
        val form = (all + ("api_sig" to sign(all)) + ("format" to "json")).entries
            .joinToString("&") { (k, v) -> java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8") }
        val (code, body) = httpCall("https://ws.audioscrobbler.com/2.0/", form.toByteArray(), mapOf("Content-Type" to "application/x-www-form-urlencoded"))
        val json = Json.parseToJsonElement(body)
        if (code !in 200..299 || json.jsonObject["error"] != null) error("Last.fm: ${json.jsonObject["message"]?.jsonPrimitive?.content ?: code}")
        return json
    }
}

/** Scrobbles waiting to be sent, kept in a file so they survive being offline and app restarts. */
class ScrobbleQueue(private val file: File) {
    private val flushing = java.util.concurrent.locks.ReentrantLock() // not `this`: add() mustn't wait for the network
    @Synchronized fun add(s: Scrobble) = save(load() + s)

    @Synchronized fun load(): List<Scrobble> = if (file.exists()) runCatching { Json.decodeFromString<List<Scrobble>>(file.readText()) }.getOrDefault(emptyList()) else emptyList()

    private fun save(list: List<Scrobble>) = file.writeText(Json.encodeToString(list))

    /**
     * Sends everything in batches of 50. [send] returns how many of the batch's first plays are done with (accepted, or
     * dropped for good), which leave the queue; fewer than the batch, or a throw, stops the flush.
     */
    fun flush(send: (List<Scrobble>) -> Int) {
        // Two flushes at once would send a batch twice; the running one also sends what was added meanwhile.
        if (!flushing.tryLock()) return
        try { flushOnce(send) } finally { flushing.unlock() }
    }

    private fun flushOnce(send: (List<Scrobble>) -> Int) {
        while (true) {
            val batch = synchronized(this) { load().take(50) }
            if (batch.isEmpty()) return
            val done = send(batch).coerceIn(0, batch.size)
            synchronized(this) { save(load().drop(done)) }
            if (done < batch.size) return
        }
    }
}
