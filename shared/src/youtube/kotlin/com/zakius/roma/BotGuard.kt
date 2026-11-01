package com.zakius.roma

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import roma.core.PoTokenResult
import roma.core.TokenProvider
import java.util.Base64

/** One BotGuard session in a browser page: [mint] per identifier until [expired]. */
interface BotGuardSession {
    suspend fun mint(identifier: String): String
    val expired: Boolean
    val dead: Boolean
    /** Any thread. */
    fun close()
}

/** The platform can't run BotGuard at all (Android: a system WebView that fails on the page): no more tries this run. */
class BotGuardBroken(message: String) : Exception(message)

/**
 * PoTokens for InnerTubeX's web clients (WEB_REMIX and others), minted by YouTube's BotGuard in a hidden browser page
 * ([start]: Android's WebView, PoToken.kt; on the desktop a Chromium, Browser.kt).
 * Without them those clients' streams stop after about 1 MB. Ported from Metrolist (GPL-3.0,
 * app/.../utils/potoken at 0dd3d747), which took it from NewPipe; app/src/main/assets/po_token.html is Metrolist's
 * page.
 *
 * The streaming token is minted once per visitor data, a player token per video. Tokens and cookies are never
 * logged or stored.
 */
class PoTokens(private val timeoutMs: Long, private val start: suspend () -> BotGuardSession) : TokenProvider {
    private val lock = Mutex()
    private var view: BotGuardSession? = null
    private var visitor: String? = null
    private var streamingToken: String? = null
    @Volatile private var broken = false // the platform can't run the page; don't try again this run

    override suspend fun getPoToken(videoId: String, visitorData: String, cookie: String?): PoTokenResult? {
        if (broken) return null
        return try {
            withTimeout(timeoutMs) { mint(videoId, visitorData, retried = false) }
        } catch (_: TimeoutCancellationException) {
            reset() // the page's process may have been killed; the next song starts over
            null
        } catch (_: BotGuardBroken) {
            broken = true
            null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            reset() // BotGuard failed or the page is gone: no token for this song (null, not a thrown lookup)
            null
        }
    }

    private suspend fun mint(videoId: String, visitorData: String, retried: Boolean): PoTokenResult {
        val (v, streaming, fresh) = lock.withLock {
            val current = view
            val fresh = retried || current == null || current.expired || current.dead || visitor != visitorData
            if (fresh) {
                current?.close()
                view = null; streamingToken = null; visitor = null
                val created = start()
                // The streaming token must be minted before any player token.
                val token = try { created.mint(visitorData) } catch (t: Throwable) { created.close(); throw t }
                view = created; streamingToken = token; visitor = visitorData
            }
            Triple(view!!, streamingToken!!, fresh)
        }
        val player = try {
            v.mint(videoId)
        } catch (t: Throwable) {
            if (fresh) throw t
            return mint(videoId, visitorData, retried = true) // the page may be gone after the app was in the background
        }
        return PoTokenResult(playerRequestToken = streaming, streamingDataToken = player, visitorData = visitorData)
    }

    private suspend fun reset() = lock.withLock {
        view?.close()
        view = null; streamingToken = null; visitor = null
    }
}

/** BotGuard's side of the PoToken flow, the same for every browser page: its requests and the page's inputs. */
object BotGuard {
    const val API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
    const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3"

    /** Blocking: a BotGuard request ("Create", then "GenerateIT"), as the page would send it. */
    fun request(url: String, body: String): String {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json",
            "Content-Type" to "application/json+protobuf",
            "x-goog-api-key" to API_KEY,
            "x-user-agent" to "grpc-web-javascript/0.1",
        )
        val (code, text) = roma.core.httpCall(url, body.toByteArray(), headers)
        if (code !in 200..299 || text.isEmpty()) throw java.io.IOException("BotGuard request failed: $url")
        return text
    }

    /** GenerateIT's answer: the integrity token as a Uint8Array literal, and when it expires (10 minutes early). */
    fun integrity(body: String): Pair<String, Long> {
        val data = Json.parseToJsonElement(body).jsonArray
        return u8Array(base64(data[0].jsonPrimitive.content)) to System.currentTimeMillis() + (data[1].jsonPrimitive.long - 600) * 1000
    }

    /** A minted token, as the page hands it over (its bytes joined by commas), in YouTube's URL-safe base64. */
    fun token(u8: String): String =
        Base64.getEncoder().encodeToString(u8.split(",").map { it.toInt().toByte() }.toByteArray()).replace('+', '-').replace('/', '_')

    /** The Create answer, maybe scrambled (base64, each byte minus 97), as the object runBotGuard takes. */
    fun parseChallenge(raw: String): String {
        val outer = Json.parseToJsonElement(raw).jsonArray
        val c = if (outer.size > 1 && (outer[1] as? JsonPrimitive)?.isString == true) {
            Json.parseToJsonElement(base64(outer[1].jsonPrimitive.content).map { (it + 97).toByte() }.toByteArray().decodeToString()).jsonArray
        } else {
            outer[0].jsonArray
        }
        fun firstString(i: Int) = (c[i] as? JsonArray)?.firstOrNull { (it as? JsonPrimitive)?.isString == true } ?: JsonNull
        return JsonObject(
            mapOf(
                "messageId" to JsonPrimitive(c[0].jsonPrimitive.content),
                "interpreterJavascript" to JsonObject(
                    mapOf(
                        "privateDoNotAccessOrElseSafeScriptWrappedValue" to firstString(1),
                        "privateDoNotAccessOrElseTrustedResourceUrlWrappedValue" to firstString(2),
                    ),
                ),
                "interpreterHash" to JsonPrimitive(c[3].jsonPrimitive.content),
                "program" to JsonPrimitive(c[4].jsonPrimitive.content),
                "globalName" to JsonPrimitive(c[5].jsonPrimitive.content),
                "clientExperimentsStateBlob" to JsonPrimitive(c[7].jsonPrimitive.content),
            ),
        ).toString()
    }

    // YouTube's base64 variant: URL-safe, '.' for padding.
    fun base64(s: String): ByteArray = Base64.getDecoder().decode(s.replace('-', '+').replace('_', '/').replace('.', '='))

    fun u8Array(bytes: ByteArray) = "new Uint8Array([" + bytes.joinToString(",") { (it.toInt() and 0xff).toString() } + "])"
}
