package roma.core

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.innertubex.models.response.PlayerResponse
import com.metrolist.innertubex.models.response.PlayerResponse.StreamingData.Format
import com.metrolist.innertubex.utils.decodeQueryComponent
import com.metrolist.innertubex.utils.encodeQueryComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.net.URI
import meow.nyx.quackjs.PlayerProgram
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Mints PoTokens for the web clients (a WebView BotGuard generator in the app). Never log the values. */
interface TokenProvider {
    suspend fun getPoToken(videoId: String, visitorData: String, cookie: String?): PoTokenResult?
}

class PoTokenResult(val playerRequestToken: String, val streamingDataToken: String, val visitorData: String)

class StreamResolveException(val reason: Reason, message: String, cause: Throwable? = null) : IllegalStateException(message, cause) {
    enum class Reason { NO_PLAYABLE_STREAM, UNAVAILABLE, AGE_RESTRICTED, NETWORK }
}

/**
 * The fallback lookup, InnerTubeX 0.11.2's extractor cut to what roma asks of it: audio only, best quality, no hints,
 * no client overrides. Its order of attempts, per lookup:
 * 1. VISIONOS 0.1 with the session's visitor data, kept only if it gives a URL that needs no signature or n work.
 * 2. With the watch page's player config (cached 3 h; refetched once if the cached one may be the reason it failed):
 *    the clients in [automatic] order that this session can use, each one's response taken through the player's
 *    signature and n functions (QuackJS). Web clients need a PoToken ([tokens]); their streams stop after about 1 MB
 *    without one.
 * 3. Signed in: the same with the account's cookies on the watch page.
 * 4. The embedded player with the embed page's config, then, unless the song is age-restricted, the kids web client.
 * A confirmed age gate in any response drops the clients that can't play age-restricted songs. At most 50 player
 * requests and 30 s per lookup; an identical request is never sent twice in one lookup.
 * A client that failed recently scores lower, one that failed twice in a row goes last for 2 minutes.
 * Left out of InnerTubeX's: the 60 s cache of refusals (roma keeps its own, [Streams]) and cancelling a lookup when
 * the session changes.
 */
internal class Fallback(private val innerTube: InnerTube, private val tokens: TokenProvider?) {
    /** One of InnerTubeX's playback client manifests, the fields roma's lookups read. */
    class Manifest internal constructor(
        val id: String,
        val client: YouTubeClient,
        val score: Int,
        /** Score for age-restricted songs, signed in and out; null: can't play them. */
        val ageScore: ((Boolean) -> Int)?,
        val normal: Boolean = true,
        val authRequired: Boolean = false,
        val playerToken: Boolean = false,
        val gvsToken: Boolean = false,
        val tokenCookies: Boolean = true,
        val fastPath: Boolean = false,
    ) {
        val tokenRequired get() = playerToken || gvsToken
    }

    private val visionOs = Manifest("VISIONOS_0_1", YouTubeClient.VISIONOS_0_1, 105, null, fastPath = true)
    private val embedded = Manifest("WEB_EMBEDDED_PLAYER", YouTubeClient.WEB_EMBEDDED_PLAYER, 83, null)
    private val kids = Manifest("WEB_KIDS", YouTubeClient.WEB_KIDS, 0, null, normal = false, tokenCookies = false)

    // InnerTubeX's scores: priority, content support, lifecycle and the token, cipher and WebView costs.
    private val automatic = listOf(
        embedded,
        Manifest("WEB_REMIX", YouTubeClient.WEB_REMIX, 74, { if (it) 92 else 56 }, gvsToken = true),
        Manifest("WEB_CREATOR", YouTubeClient.WEB_CREATOR, 67, { 67 }, authRequired = true, gvsToken = true),
        Manifest("TVHTML5_SIMPLY", YouTubeClient.TVHTML5_SIMPLY, 18, null, playerToken = true, gvsToken = true, tokenCookies = false),
    )

    private class Config(val playerUrl: String, val sts: Int?, val visitorData: String?, val clientVersion: String?, val hostFlags: String? = null)

    private class Cached(val config: Config, val at: Long, val identity: Pair<Any, String?>, val login: Boolean)

    private val configs = HashMap<Boolean, Cached>()
    private val configMutex = Mutex()

    class Result internal constructor(val manifest: Manifest, val client: YouTubeClient, val response: PlayerResponse, val usedToken: Boolean, val gvsToken: String?) {
        val profile get() = "${manifest.id}__${if (usedToken) "po" else "nopo"}"
    }

    class Stream(val url: String, format: Format, result: Result) {
        val mimeType = format.mimeType.substringBefore(";").trim()
        val bitrate = format.bitrate
        val loudnessDb = result.response.playerConfig?.audioConfig?.loudnessDb ?: format.loudnessDb
        val profile = result.profile
    }

    private class Failure(val status: String?, val reason: String?, val ageGate: Int?)

    private class Attempt(
        val result: Result? = null,
        val failure: Failure? = null,
        val requestFailure: Throwable? = null,
        val budgetExhausted: Boolean = false,
        val tokenUnavailable: Boolean = false,
    )

    private class Batch(
        val playable: List<Result>,
        val failures: List<Failure>,
        val requestFailures: List<Throwable>,
        val onlyUnavailable: Boolean = false,
        val onlyBootstrapIndependent: Boolean = false,
    )

    private class Exhausted : Exception()

    private data class TokenKey(val videoId: String, val visitorData: String, val cookie: String?)

    private data class RequestKey(val client: YouTubeClient, val sts: Int?, val poToken: String?, val session: InnerTube.SessionSnapshot, val hostFlags: String?)

    /** One lookup's state: its request and time budget, the responses and tokens it already has, what failed. */
    private inner class Lookup(val videoId: String, val scope: CoroutineScope) {
        private val deadline = TimeSource.Monotonic.markNow() + 30.seconds
        private var requestsLeft = 50
        val responses = HashMap<RequestKey, PlayerResponse?>()
        val minted = HashMap<TokenKey, PoTokenResult>()
        val noToken = HashSet<TokenKey>()
        val inFlight = HashMap<TokenKey, Deferred<PoTokenResult?>>()
        var ageRestricted = false
        var visitorUnavailable = false
        val failures = mutableListOf<Failure>()
        val requestFailures = mutableListOf<Throwable>()
        var nonGatePasses = 0
        var unavailable = false
        var sawPlayable = false
        val reported = HashSet<String>()

        fun timeLeftOrNull(limit: Duration): Duration? = (-deadline.elapsedNow()).takeIf { it.isPositive() }?.let { minOf(limit, it) }

        fun timeLeft(limit: Duration) = timeLeftOrNull(limit) ?: throw Exhausted()

        val remaining get() = if ((-deadline.elapsedNow()).isPositive()) requestsLeft else 0

        fun consume() {
            timeLeft(Duration.INFINITE)
            if (requestsLeft <= 0) throw Exhausted()
            requestsLeft--
        }

        fun prefetch(key: TokenKey): Deferred<PoTokenResult?> = inFlight.getOrPut(key) {
            scope.async {
                try {
                    withTimeoutOrNull(timeLeft(18.seconds)) { tokens?.getPoToken(videoId, key.visitorData, key.cookie) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    suspend fun extract(videoId: String, excluded: Set<String>, cpn: String): Stream = coroutineScope {
        val l = Lookup(videoId, this)
        try {
            withTimeoutOrNull(l.timeLeft(Duration.INFINITE)) { Pair(lookup(l, excluded, cpn), Unit) }?.first?.also { l.timeLeft(Duration.INFINITE) }
                ?: throw Exhausted()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exhausted) {
            throw StreamResolveException(StreamResolveException.Reason.NO_PLAYABLE_STREAM, "Extraction time budget exhausted.", e)
        } catch (e: StreamResolveException) {
            throw e
        } catch (e: Exception) {
            throw StreamResolveException(StreamResolveException.Reason.NETWORK, "Unable to fetch stream data from YouTube.", e)
        } finally {
            l.inFlight.values.forEach { it.cancel() }
        }
    }

    private suspend fun lookup(l: Lookup, excluded: Set<String>, cpn: String): Stream {
        val visionOsUsable = !visionOs.excluded(excluded)
        val defer = innerTube.sessionSnapshot().visitorData.isNullOrBlank() && visionOsUsable
        suspend fun configFree() =
            withConfig(l, Config("", null, innerTube.sessionSnapshot().visitorData, null), null, excluded, cpn, allowCipher = false)
        if (!defer) configFree()?.let { return it }
        if (l.unavailable) fail(l)

        suspend fun watch(login: Boolean) = try {
            withCachedConfig(l, login, excluded, cpn)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            l.requestFailures += e
            null
        }
        watch(false)?.let { return it }
        if (l.unavailable) fail(l)
        if (defer) configFree()?.let { return it }
        if (l.unavailable) fail(l)
        if (innerTube.hasSapCookieAuth()) watch(true)?.let { return it }
        if (l.unavailable) fail(l)

        if (l.remaining > 0 && !l.ageRestricted) {
            val config = try {
                fetchConfig(l.videoId, false, embed = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                l.requestFailures += e
                null
            }
            if (!config?.hostFlags.isNullOrBlank() && config.sts != null) withConfig(l, config, embedded, excluded, cpn)?.let { return it }
        }
        if (l.unavailable) fail(l)
        if (!l.ageRestricted && classify(l.failures) != StreamResolveException.Reason.AGE_RESTRICTED) {
            val config = configMutex.withLock { cached(false) }?.config
            if (config != null) withConfig(l, config, kids, excluded, cpn)?.let { return it }
        }
        fail(l)
    }

    private fun fail(l: Lookup): Nothing {
        l.timeLeft(Duration.INFINITE)
        if (!l.sawPlayable) {
            when (classify(l.failures)) {
                StreamResolveException.Reason.AGE_RESTRICTED ->
                    throw StreamResolveException(StreamResolveException.Reason.AGE_RESTRICTED, "This track is age-restricted.")
                StreamResolveException.Reason.UNAVAILABLE ->
                    throw StreamResolveException(StreamResolveException.Reason.UNAVAILABLE, "This track is unavailable.")
                else -> {}
            }
            l.requestFailures.lastOrNull()?.let {
                throw StreamResolveException(StreamResolveException.Reason.NETWORK, "Unable to fetch stream data from YouTube.", it)
            }
        }
        throw StreamResolveException(StreamResolveException.Reason.NO_PLAYABLE_STREAM, "No playable stream found for this track.")
    }

    private fun identity(login: Boolean) = innerTube.sessionSnapshot().let { s ->
        Pair<Any, String?>(s.locale, s.cookie?.takeIf { login }?.let { java.security.MessageDigest.getInstance("SHA-256").digest(it.encodeToByteArray()).toHexString() })
    }

    // Under configMutex.
    private fun cached(login: Boolean): Cached? {
        val c = configs[login] ?: return null
        if (c.identity == identity(login) && System.currentTimeMillis() - c.at <= 3 * 3600_000L) return c
        configs.remove(login)
        return null
    }

    private suspend fun withCachedConfig(l: Lookup, login: Boolean, excluded: Set<String>, cpn: String): Stream? {
        val cached = configMutex.withLock {
            cached(login) ?: if (login && !innerTube.sessionSnapshot().visitorData.isNullOrBlank()) cached(false) else null
        }
        if (cached != null) {
            val passes = l.nonGatePasses
            val failures = l.failures.size
            val stream = try {
                withConfig(l, cached.config, null, excluded, cpn)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!login || cached.login || e is Exhausted) throw e
                l.requestFailures += e
                null
            }
            if (stream != null) return stream
            // Only gate refusals: a fresh config wouldn't change them.
            if (l.failures.size > failures && l.nonGatePasses == passes) return null
        }
        if (l.remaining <= 0) return null
        // Held over the fetch: concurrent lookups fetch the page once.
        val fresh = configMutex.withLock {
            if (cached != null && configs[login] === cached) configs.remove(login)
            cached(login) ?: Cached(fetchConfig(l.videoId, login, embed = false), System.currentTimeMillis(), identity(login), login)
                .also { configs[login] = it }
        }
        return withConfig(l, fresh.config, null, excluded, cpn)
    }

    /** One pass with [config]: the player responses, then the first one that yields a usable audio URL. */
    private suspend fun withConfig(
        l: Lookup, config: Config, override: Manifest?, excluded: Set<String>, cpn: String, allowCipher: Boolean = true,
    ): Stream? {
        if (l.remaining <= 0) return null
        val batch = players(l, config, override, excluded, allowCipher)
        if (!batch.onlyBootstrapIndependent) l.nonGatePasses++
        if (batch.onlyUnavailable) l.unavailable = true
        l.failures += batch.failures
        l.requestFailures += batch.requestFailures
        if (batch.playable.isEmpty()) return null
        l.sawPlayable = true

        for (result in batch.playable) {
            val all = result.response.streamingData?.let { it.formats.orEmpty() + it.adaptiveFormats } ?: continue
            val bounded = result.manifest.id == "TVHTML5_SIMPLY"
            val directItags = all.filter { it.isAudio && !it.url.isNullOrBlank() }.map { it.itag }.toSet()
            val direct = best(all.filter { it.isAudio && !it.url.isNullOrBlank() })
            val bestAny = best(all.filter { it.isAudio && (!it.url.isNullOrBlank() || !it.signatureCipher.isNullOrBlank() || !it.cipher.isNullOrBlank()) }, requireUrl = false)
            if (direct != null && direct === bestAny) {
                val url = withCpn(direct.url!!.withPoToken(result.gvsToken), cpn)
                if (!allowedMedia(url)) continue
                if (!N_ANY.containsMatchIn(url)) {
                    if (bounded && contentLength(l, direct.contentLength, url) == null) continue
                    return Stream(url, direct, result)
                }
            }
            if (!allowCipher) continue
            val raw = all.filter { it.isAudio }
            val preferred = best(raw, requireUrl = false)
            fun pick(formats: List<Format>): Format? {
                val usable = formats.filter { it.isAudio && !it.url.isNullOrBlank() }
                return best(usable.filter { it.itag in directItags }.ifEmpty { usable })
            }
            var format = pick(cipher.process(config.playerUrl, preferred?.let(::listOf) ?: raw))
            if (format == null && preferred != null && raw.size != 1) format = pick(cipher.process(config.playerUrl, raw))
            val url = format?.url?.withPoToken(result.gvsToken)?.let { withCpn(it, cpn) }
            if (url.isNullOrBlank() || !allowedMedia(url)) continue
            if (bounded && contentLength(l, format.contentLength, url) == null) continue
            return Stream(url, format, result)
        }
        val next = excluded + batch.playable.map { it.profile }
        if (next.size == excluded.size) return null
        return withConfig(l, config, override, next, cpn, allowCipher)
    }

    // TVHTML5_SIMPLY's streams need a known length: from the format, else a HEAD request.
    private suspend fun contentLength(l: Lookup, known: Long?, url: String): Long? {
        known?.takeIf { it > 0L }?.let { return it }
        val timeout = l.timeLeftOrNull(8.seconds) ?: return null
        return withTimeoutOrNull(timeout) { innerTube.mediaContentLength(url, emptyMap()) }
    }

    private class Health(val failures: Int, val at: Long, val cooldownUntil: Long)

    /** Per client and scope (content, signed in): consecutive failures, forgotten 10 minutes after the last one. */
    private val healths = java.util.concurrent.ConcurrentHashMap<String, Health>()

    private fun health(id: String, scope: String): Int {
        val now = System.currentTimeMillis()
        val h = healths["$id $scope"]?.takeIf { now - it.at < 600_000 } ?: return 0
        return -18 * minOf(h.failures, 5) - if (h.cooldownUntil > now) 1000 else 0
    }

    private fun failed(id: String, scope: String) {
        val now = System.currentTimeMillis()
        val n = (healths["$id $scope"]?.takeIf { now - it.at < 600_000 }?.failures ?: 0) + 1
        healths["$id $scope"] = Health(n, now, if (n >= 2) now + 120_000 else 0)
    }

    private fun Manifest.excluded(excluded: Set<String>): Boolean {
        if (client.clientName in excluded || id in excluded) return true
        val noPo = "${id}__nopo" in excluded
        val po = "${id}__po" in excluded
        return (noPo && po) || (!canMint() && noPo) || (tokenRequired && po)
    }

    private fun Manifest.canMint() = (playerToken || gvsToken) && tokens != null

    private suspend fun players(l: Lookup, config: Config, override: Manifest?, excluded: Set<String>, allowCipher: Boolean): Batch {
        val initial = innerTube.sessionSnapshot()
        val embedVisitor = config.visitorData?.takeIf { it.isNotBlank() && !config.hostFlags.isNullOrBlank() }
        val visitor = embedVisitor ?: initial.visitorData?.takeIf { it.isNotBlank() } ?: config.visitorData?.takeIf { it.isNotBlank() }
        var session = initial.copy(visitorData = visitor)
        val authenticated = !session.sapisid.isNullOrBlank()
        val age = l.ageRestricted
        val scope = "${if (age) "age" else if (override === kids) "kids" else "normal"} $authenticated"
        val clients = if (override != null && !override.excluded(excluded)) {
            listOf(override)
        } else {
            (automatic + listOfNotNull(visionOs.takeIf { visitor != null }))
                .filter { m ->
                    !m.excluded(excluded) && (!m.authRequired || authenticated) && (!m.tokenRequired || tokens != null) &&
                        (if (age) m.ageScore != null else m.normal) && (allowCipher || m.fastPath)
                }
                .map { m -> m to health(m.id, scope) }
                // Repeatedly failing clients go behind every healthy one instead of only losing points.
                .sortedWith(compareBy<Pair<Manifest, Int>> { it.second <= -50 }
                    .thenByDescending { (if (age) it.first.ageScore!!(authenticated) else it.first.score) + it.second }.thenBy { it.first.id })
                .map { it.first }
        }
        val playable = mutableListOf<Result>()
        val failures = mutableListOf<Failure>()
        val requestFailures = mutableListOf<Throwable>()
        var sent = 0
        var onlyUnavailable = allowCipher && config.playerUrl.isNotBlank() && (!authenticated || clients.any { it.client.loginSupported })
        var onlyBootstrapIndependent = true
        for (m in clients) {
            if (sent >= 24 || l.remaining <= 0) break
            if (override == null && l.ageRestricted && m.ageScore == null) continue
            // A tokenized attempt may have published fresh visitor data. Carry it forward.
            innerTube.sessionSnapshot().takeIf { it.generation != session.generation }?.visitorData?.takeIf(String::isNotBlank)
                ?.let { innerTube.sessionSnapshotWithVisitorData(session, it) }?.let { session = it }
            val client = config.clientVersion?.takeIf { it.isNotBlank() && m.client.clientName == "WEB_EMBEDDED_PLAYER" }
                ?.let { m.client.copy(clientVersion = it) } ?: m.client
            if (override == null && m === visionOs && visitor.isNullOrBlank()) continue
            val before = l.remaining
            val a = attempt(l, m, client, config, override != null, "${m.id}__nopo" in excluded && m.canMint(), session)
            sent += before - l.remaining
            val status = a.failure?.status
            // Success is left out: a playable response can still fail at the CDN.
            val healthScope = "${if (l.ageRestricted) "age" else if (override === kids) "kids" else "normal"} $authenticated"
            if (!(m === visionOs && visitor.isNullOrBlank())) when {
                a.requestFailure != null -> if (l.reported.add("${m.id} request")) failed(m.id, healthScope)
                a.result == null && !a.tokenUnavailable && !a.budgetExhausted && !status.isAccountGate() &&
                    status != "VIDEO_UNAVAILABLE" && status != "LIVE_STREAM_OFFLINE" -> if (l.reported.add("${m.id} playability")) failed(m.id, healthScope)
            }
            onlyUnavailable = onlyUnavailable && status == "ERROR" && a.requestFailure == null &&
                (!client.isEmbedded || !config.hostFlags.isNullOrBlank())
            onlyBootstrapIndependent = onlyBootstrapIndependent && (status.isAccountGate() || status == "ERROR") && a.requestFailure == null
            val r = a.result
            if (r != null) {
                if (r.profile in excluded) continue
                playable += r
                if (allowCipher || hasDirectAudio(r.response)) return Batch(listOf(r), failures, requestFailures)
            } else {
                a.failure?.let(failures::add)
                a.requestFailure?.let(requestFailures::add)
            }
        }
        if (playable.isNotEmpty()) return Batch(playable, failures, requestFailures)
        return Batch(emptyList(), failures, requestFailures, onlyUnavailable && failures.isNotEmpty(), onlyBootstrapIndependent && failures.isNotEmpty())
    }

    private suspend fun attempt(
        l: Lookup, m: Manifest, client: YouTubeClient, config: Config, manual: Boolean, untokenizedFailed: Boolean, session: InnerTube.SessionSnapshot,
    ): Attempt = try {
        val canMint = m.canMint()
        val allowUntokenized = (manual && !canMint) || !m.tokenRequired
        val known = session.visitorData?.takeIf(String::isNotBlank)?.let { TokenKey(l.videoId, it, session.cookie.takeIf { m.tokenCookies }) }
        when {
            m.tokenRequired && !allowUntokenized && known != null && known in l.noToken -> Attempt(tokenUnavailable = true)
            (untokenizedFailed || m.playerToken) && canMint -> tokenized(l, m, client, config, session, null, null, null)
            else -> {
                // A GVS-only token never enters the player request, so mint it while that request is in flight.
                val prefetch = known?.takeIf { canMint && m.gvsToken && !m.playerToken && !allowUntokenized && l.minted[it] == null }?.let(l::prefetch)
                untokenizedFirst(l, m, client, config, allowUntokenized, session, prefetch?.let { known to it })
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exhausted) {
        Attempt(budgetExhausted = true)
    } catch (e: Exception) {
        Attempt(requestFailure = e)
    }

    private suspend fun untokenizedFirst(
        l: Lookup, m: Manifest, client: YouTubeClient, config: Config, allowUntokenized: Boolean, session: InnerTube.SessionSnapshot,
        prefetch: Pair<TokenKey, Deferred<PoTokenResult?>>?,
    ): Attempt {
        val response = request(l, client, config, null, session) ?: return Attempt()
        val playable = playable(response, client)
        val failure = response.playabilityStatus.failure()
        if (playable && (!m.tokenRequired || allowUntokenized)) return Attempt(Result(m, client, response, false, null))
        if (!m.canMint()) return Attempt(failure = failure.takeUnless { playable }, tokenUnavailable = playable)
        // InnerTubeX retries a refusal with a token only for songs its caller marks age-restricted; roma marks none.
        if (!playable) return Attempt(failure = failure)
        return tokenized(l, m, client, config, session, failure.takeUnless { playable }, response.takeIf { playable }, prefetch)
    }

    private suspend fun tokenized(
        l: Lookup, m: Manifest, client: YouTubeClient, config: Config, session: InnerTube.SessionSnapshot,
        fallbackFailure: Failure?, existing: PlayerResponse?, prefetch: Pair<TokenKey, Deferred<PoTokenResult?>>?,
    ): Attempt {
        val tokenSession = if (session.visitorData.isNullOrBlank()) {
            if (l.visitorUnavailable) return Attempt(failure = fallbackFailure, tokenUnavailable = true)
            val v = withTimeoutOrNull(l.timeLeft(8.seconds)) { innerTube.fetchFreshVisitorData(session) }
            if (v.isNullOrBlank()) {
                l.visitorUnavailable = true
                return Attempt(failure = fallbackFailure, tokenUnavailable = true)
            }
            innerTube.sessionSnapshotWithVisitorData(session, v) ?: return Attempt(failure = fallbackFailure, tokenUnavailable = true)
        } else {
            session
        }
        val visitor = tokenSession.visitorData!!
        val key = TokenKey(l.videoId, visitor, tokenSession.cookie.takeIf { m.tokenCookies })
        if (key in l.noToken) return Attempt(failure = fallbackFailure, tokenUnavailable = true)
        val token = l.minted[key] ?: try {
            withTimeoutOrNull(l.timeLeft(18.seconds)) { (prefetch?.takeIf { it.first == key }?.second ?: l.prefetch(key)).await() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }?.also { l.minted[key] = it }
        if (token == null) {
            l.noToken += key
            return Attempt(failure = fallbackFailure, tokenUnavailable = true)
        }
        // The player token is bound to the visitor, the streaming token to the video.
        val playerToken = token.playerRequestToken.takeIf { m.playerToken }
        val gvsToken = token.streamingDataToken.takeIf { m.gvsToken }
        if (token.visitorData != visitor || (m.playerToken && playerToken.isNullOrBlank()) || (m.gvsToken && gvsToken.isNullOrBlank()) ||
            (playerToken.isNullOrBlank() && gvsToken.isNullOrBlank())
        ) {
            return Attempt(failure = fallbackFailure, tokenUnavailable = true)
        }
        if (existing != null && playerToken.isNullOrBlank()) return Attempt(Result(m, client, existing, true, gvsToken))
        val response = request(l, client, config, playerToken, tokenSession) ?: return Attempt(failure = fallbackFailure)
        return if (playable(response, client)) {
            Attempt(Result(m, client, response, !playerToken.isNullOrBlank() || !gvsToken.isNullOrBlank(), gvsToken))
        } else {
            Attempt(failure = response.playabilityStatus.failure())
        }
    }

    private suspend fun request(l: Lookup, client: YouTubeClient, config: Config, poToken: String?, session: InnerTube.SessionSnapshot): PlayerResponse? {
        val sts = config.sts.takeIf { client.useSignatureTimestamp }
        val hostFlags = config.hostFlags.takeIf { client.isEmbedded }
        val key = RequestKey(client, sts, poToken, session, hostFlags)
        if (key in l.responses) return l.responses[key]
        l.consume()
        val timeout = l.timeLeft(8.seconds)
        val response = try {
            withTimeout(timeout) {
                // Non-2xx responses throw InnerTubeHttpException before their bodies are read.
                val text = innerTube.playerTextWithSessionBound(client, l.videoId, sts, poToken, session, hostFlags, 4 * 1024 * 1024)
                withContext(Dispatchers.Default) { parse(text, l.videoId) }
            }
        } catch (e: TimeoutCancellationException) {
            // Running out of the lookup's own time is not this client's fault.
            if (timeout < 8.seconds) throw Exhausted()
            throw Exception("Player request for ${client.clientName} exceeded 8000ms", e)
        }
        response?.playabilityStatus?.let { if (ageGate(it.status, it.desktopLegacyAgeGateReason)) l.ageRestricted = true }
        l.responses[key] = response
        return response
    }

    private fun parse(text: String, videoId: String): PlayerResponse? {
        val r = runCatching { json.decodeFromString<PlayerResponse>(text) }.getOrNull() ?: return null
        if (r.videoDetails?.videoId?.takeIf(String::isNotBlank)?.let { it != videoId } == true) return null
        val n = (r.streamingData?.formats?.size ?: 0) + (r.streamingData?.adaptiveFormats?.size ?: 0)
        return r.takeIf { n <= 2048 }
    }

    private fun playable(r: PlayerResponse, client: YouTubeClient) =
        (r.playabilityStatus.status == "OK" || client.skipPlayerResponseValidation) &&
            (r.streamingData?.let { it.formats.orEmpty() + it.adaptiveFormats }.orEmpty()
                .any { it.isAudio && (!it.url.isNullOrBlank() || !it.signatureCipher.isNullOrBlank() || !it.cipher.isNullOrBlank()) } ||
                !r.streamingData?.hlsManifestUrl.isNullOrBlank())

    private fun hasDirectAudio(r: PlayerResponse) =
        r.streamingData?.let { it.formats.orEmpty() + it.adaptiveFormats }.orEmpty().any { it.isAudio && !it.url.isNullOrBlank() && !N_QUERY.containsMatchIn(it.url) }

    private fun PlayerResponse.PlayabilityStatus.failure() = Failure(status, reason, desktopLegacyAgeGateReason)

    private fun ageGate(status: String?, reason: Int?) =
        status == "AGE_CHECK_REQUIRED" || status == "AGE_VERIFICATION_REQUIRED" || (status == "LOGIN_REQUIRED" && (reason ?: 0) > 0)

    private fun String?.isAccountGate() =
        this == "LOGIN_REQUIRED" || this == "AGE_CHECK_REQUIRED" || this == "AGE_VERIFICATION_REQUIRED" || this == "CONTENT_CHECK_REQUIRED"

    private fun classify(failures: List<Failure>): StreamResolveException.Reason? {
        if (failures.any { f ->
                val s = f.status.orEmpty().uppercase()
                ageGate(s, f.ageGate) || s == "CONTENT_CHECK_REQUIRED" ||
                    listOf("confirm your age", "age-restricted", "age restricted", "age verification").any(f.reason.orEmpty().lowercase()::contains)
            }
        ) return StreamResolveException.Reason.AGE_RESTRICTED
        if (failures.any { f ->
                f.status.orEmpty().uppercase() in setOf("ERROR", "UNPLAYABLE", "VIDEO_UNAVAILABLE", "LIVE_STREAM_OFFLINE") ||
                    listOf("video unavailable", "not available", "private video", "has been removed").any(f.reason.orEmpty().lowercase()::contains)
            }
        ) return StreamResolveException.Reason.UNAVAILABLE
        return null
    }

    // ---- Player config from the watch or embed page ----

    private suspend fun fetchConfig(videoId: String, login: Boolean, embed: Boolean): Config {
        require(Regex("[A-Za-z0-9_-]{1,64}").matches(videoId)) { "Invalid video ID" }
        val pageUrl = if (embed) "https://www.youtube.com/embed/$videoId?html5=1" else "https://www.youtube.com/watch?v=$videoId&bpctr=9999999999&has_verified=1"
        val cookie = innerTube.cookie?.takeIf { login }?.split(';')?.map { it.trim() }
            ?.filter { it.isNotEmpty() && !it.startsWith("PREF=", ignoreCase = true) }?.joinToString("; ")?.takeIf { it.isNotEmpty() }
        // The fields sit in the first ~60 KB of a ~1.4 MB streamed page; STS often only near its middle.
        val done: (String) -> Boolean = { html ->
            playerUrl(html) != null && VISITOR.find(html) != null && CLIENT_VERSION.find(html) != null &&
                (!embed || HOST_FLAGS.find(html) != null) && sts(html) != null
        }
        suspend fun page(c: String?) = getText(pageUrl, 4 * 1024 * 1024, 10_000, done, buildMap {
            put("User-Agent", YouTubeClient.USER_AGENT_WEB)
            put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            if (embed) put("Referer", "https://www.reddit.com/")
            c?.let { put("Cookie", it) }
            put("Accept-Language", innerTube.locale.acceptLanguageHeader())
        })
        val html = try {
            page(cookie)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (cookie == null) throw e
            page(null)
        }
        val playerUrl = playerUrl(html) ?: iframePlayerUrl() ?: error("Unable to parse YouTube player JavaScript URL")
        val sts = sts(html) ?: try {
            sts(cipher.code(canonical(playerUrl)))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return Config(playerUrl, sts, VISITOR.find(html)?.groupValues?.get(1), CLIENT_VERSION.find(html)?.groupValues?.get(1),
            HOST_FLAGS.find(html)?.groupValues?.get(1).takeIf { embed })
    }

    private suspend fun iframePlayerUrl(): String? = try {
        val api = getText("https://www.youtube.com/iframe_api", 1024 * 1024, 8_000, null, mapOf("User-Agent" to YouTubeClient.USER_AGENT_WEB))
        Regex("/s/player/([a-zA-Z0-9_-]+)/").find(api.replace("\\/", "/"))?.groupValues?.get(1)
            ?.let { validPlayerUrl("https://www.youtube.com/s/player/$it/player_ias.vflset/en_GB/base.js") }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun playerUrl(html: String): String? {
        fun String.unescape() = replace("\\/", "/").replace("\\u0026", "&")
        val keyed = KEYED_PLAYER_URL.asSequence().mapNotNull { it.find(html)?.groupValues?.get(1)?.unescape() }
        val loose = sequence { LOOSE_PLAYER_URL.find(html.unescape())?.groupValues?.get(1)?.let { yield(it) } }
        return (keyed + loose).mapNotNull { p ->
            validPlayerUrl(if (p.startsWith("http")) p else "https://www.youtube.com${if (p.startsWith('/')) p else "/$p"}")
        }.firstOrNull()
    }

    private fun sts(text: String) = STS.asSequence().mapNotNull { it.find(text)?.groupValues?.get(1)?.toIntOrNull() }.firstOrNull()

    private fun validPlayerUrl(value: String): String? = httpsUrl(value, ::youTubeHost, PLAYER_PATH::matches)?.toString()

    /** GET that follows at most 3 redirects, each only to an approved YouTube page. */
    private suspend fun getText(url: String, maxBytes: Int, timeoutMs: Int, stopWhen: ((String) -> Boolean)?, headers: Map<String, String>): String {
        var current = url
        repeat(4) { redirects ->
            val r = http(current, headers, timeoutMs = timeoutMs, totalMs = timeoutMs.toLong(), maxBytes = maxBytes, errorBody = false, redirects = false, stopWhen = stopWhen)
            if (r.ok) return r.text()
            current = r.header("Location")?.takeIf { r.status in REDIRECTS && redirects < 3 }
                ?.let { runCatching { URI(current).resolve(it).toString() }.getOrNull() }
                ?.takeIf { u ->
                    httpsUrl(u, ::youTubeHost) { it == "/watch" || it == "/iframe_api" || EMBED_PATH.matches(it) || PLAYER_PATH.matches(it) } != null
                } ?: error("HTTP ${r.status}")
        }
        error("Too many redirects")
    }

    private val cipher = Cipher()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val N_ANY = Regex("(?:[?&]|%26)n(?:=|%3[dD])", RegexOption.IGNORE_CASE)
        private val N_QUERY = Regex("[?&]n=[^&]+", RegexOption.IGNORE_CASE)
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private val EMBED_PATH = Regex("/embed/[A-Za-z0-9_-]{1,64}")
        private val PLAYER_PATH = Regex("/s/player/[A-Za-z0-9_-]+/.+\\.js")
        private val KEYED_PLAYER_URL = listOf(Regex("\"PLAYER_JS_URL\":\"([^\"]+)\""), Regex("\"jsUrl\":\"([^\"]+)\""))
        private val LOOSE_PLAYER_URL = Regex("(?<![A-Za-z0-9:/])(/s/player/[^\"'\\\\]+/[^\"'\\\\]*\\.js[^\"'\\\\]*)")
        private val STS = listOf(Regex("(?:signatureTimestamp|sts)\"?\\s*:\\s*([0-9]{5})"), Regex("\"STS\":\\s*([0-9]{5})"))
        private val CLIENT_VERSION = Regex("\"INNERTUBE_CLIENT_VERSION\"\\s*:\\s*\"([^\"]+)\"")
        private val HOST_FLAGS = Regex("\"encryptedHostFlags\"\\s*:\\s*\"([^\"]+)\"")
        private val VISITOR = Regex("\"(?:VISITOR_DATA|visitorData)\"\\s*:\\s*\"([^\"]+)\"")
    }
}

private fun youTubeHost(host: String) =
    host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com")

private fun mediaHost(host: String) =
    host == "googlevideo.com" || host.endsWith(".googlevideo.com") || host == "youtube.com" || host.endsWith(".youtube.com")

private fun allowedMedia(value: String): Boolean = value.length in 1..16 * 1024 && httpsUrl(value, ::mediaHost) { it == "/videoplayback" } != null

private fun String.withPoToken(token: String?): String {
    if (token.isNullOrBlank() || contains("&pot=") || contains("?pot=")) return this
    val end = indexOf('#').takeIf { it >= 0 } ?: length
    val separator = if (indexOf('?').let { it in 0 until end }) "&" else "?"
    return substring(0, end) + separator + "pot=" + encodeQueryComponent(token) + substring(end)
}

private const val CPN_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"

internal fun playbackNonce() = buildString(16) { repeat(16) { append(CPN_ALPHABET[Random.nextInt(CPN_ALPHABET.length)]) } }

private fun withCpn(url: String, cpn: String): String {
    val u = httpsUrl(url, { it == "googlevideo.com" || it.endsWith(".googlevideo.com") }) { it == "/videoplayback" } ?: return url
    if (u.rawQuery?.split('&')?.any { it.substringBefore('=') == "cpn" } == true) return url
    val end = url.indexOf('#').takeIf { it >= 0 } ?: url.length
    val head = url.substring(0, end)
    return head + (if ('?' in head) "&cpn=$cpn" else "?cpn=$cpn") + url.substring(end)
}

/**
 * The best audio format: the original track of a dubbed upload (the xtags' acont=original, else the default track),
 * then bitrate, channels (stereo first), sample rate and container (WebM over MP4).
 */
private fun best(formats: List<Format>, requireUrl: Boolean = true): Format? {
    val valid = formats.filter { it.isAudio && it.mimeType.startsWith("audio/", ignoreCase = true) && (!requireUrl || !it.url.isNullOrBlank()) }
    val original = if (valid.none { it.audioTrack != null }) valid else valid.filter { it.acont() == "original" }
        .ifEmpty { valid.filter { it.audioTrack?.audioIsDefault == true } }.ifEmpty { valid }
    return original.maxWithOrNull(
        compareBy<Format> { it.bitrate }
            .thenBy { when (it.audioChannels) { 2 -> 2; null -> 1; else -> 0 } }
            .thenBy { (it.audioSampleRate ?: 0).coerceIn(0, 48_000) }
            .thenBy { if (it.mimeType.contains("audio/webm")) 2 else if (it.mimeType.contains("audio/mp4")) 1 else 0 },
    )
}

// xtags is a base64url protobuf map of repeated {1: key, 2: value} entries, e.g. acont=original.
private fun Format.acont(): String? {
    val bytes = xtags?.takeIf { it.length <= 1024 }?.let {
        runCatching { Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(it) }.getOrNull()
    } ?: return null
    return runCatching {
        for ((field, entry) in protoFields(bytes)) {
            if (field != 1 || entry == null) continue
            val kv = protoFields(entry).filter { it.second != null }.associate { it.first to it.second!!.decodeToString() }
            if (kv[1] == "acont") return@runCatching kv[2]
        }
        null
    }.getOrNull()
}

/** The fields of a protobuf message: number and, for length-delimited ones, the bytes. Throws on malformed input. */
private fun protoFields(b: ByteArray): List<Pair<Int, ByteArray?>> {
    var i = 0
    fun varint(): Long {
        var r = 0L
        var shift = 0
        while (true) {
            require(i < b.size && shift < 64)
            val x = b[i++].toInt()
            r = r or ((x and 0x7f).toLong() shl shift)
            if (x and 0x80 == 0) return r
            shift += 7
        }
    }
    val out = mutableListOf<Pair<Int, ByteArray?>>()
    while (i < b.size) {
        val tag = varint()
        val field = (tag ushr 3).toInt()
        require(field > 0)
        when ((tag and 7).toInt()) {
            0 -> { varint(); out += field to null }
            1 -> { i += 8; require(i <= b.size); out += field to null }
            2 -> {
                val n = varint()
                require(n >= 0 && n <= b.size - i)
                out += field to b.copyOfRange(i, i + n.toInt())
                i += n.toInt()
            }
            5 -> { i += 4; require(i <= b.size); out += field to null }
            else -> error("wire type")
        }
    }
    return out
}

private val LOCALIZED_IAS = Regex("^https://www\\.youtube\\.com/s/player/([A-Za-z0-9_-]+)/player_ias\\.vflset/[A-Za-z]{2,3}(?:_[A-Za-z]{2,4})?/base\\.js$")
private val PLAYER_HASH = listOf(Regex("/player/([a-f0-9]{8})/"), Regex("player_ias\\.vflset/[^/]+/([a-f0-9]{8})/"), Regex("/s/player/([a-f0-9]{8})/"))

/** Locale variants of one player_ias build are the same script; one download and one compiled program serve them. */
private fun canonical(url: String) =
    LOCALIZED_IAS.matchEntire(url)?.let { "https://www.youtube.com/s/player/${it.groupValues[1]}/player_ias.vflset/en_GB/base.js" } ?: url

/**
 * Signature and n-parameter solving with QuackJS. A player QuackJS can't compile gives formats without URLs, so
 * that client's response is skipped: no stream from it, not a crash. Compiled programs are kept for the 4 latest
 * players, a compile failure for 60 s, a download failure for 15 s.
 */
private class Cipher {
    private val mutex = Mutex()
    private val programs = LinkedHashMap<String, Any>() // a PlayerProgram, or the TimeMark of a compile failure
    private var code: Pair<String, String>? = null
    private val downloadFailed = HashMap<String, TimeSource.Monotonic.ValueTimeMark>()

    suspend fun code(url: String): String = mutex.withLock { download(url) }

    // Under mutex: one download at a time, shared by every lookup that needs the same player.
    private suspend fun download(playerUrl: String): String {
        code?.takeIf { it.first == playerUrl }?.let { return it.second }
        downloadFailed[playerUrl]?.takeIf { it.elapsedNow() < 15.seconds }?.let { error("Player script download recently failed") }
        require(httpsUrl(playerUrl, ::youTubeHost) { it.startsWith("/s/player/") && it.endsWith(".js") } != null) {
            "Player script URL must use an approved YouTube HTTPS endpoint"
        }
        try {
            var last: Exception? = null
            for (attempt in 0..2) {
                if (attempt > 0) delay(250L shl (attempt - 1))
                val r = try {
                    http(playerUrl, mapOf("User-Agent" to "okhttp/5.4.0", "Accept" to "*/*", "Referer" to "https://www.youtube.com/"),
                        timeoutMs = 10_000, totalMs = 30_000, maxBytes = 8 * 1024 * 1024, errorBody = false, redirects = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ResponseTooLargeException) {
                    throw e
                } catch (e: Exception) {
                    last = e
                    continue
                }
                if (r.ok) r.text().let { code = playerUrl to it; downloadFailed.remove(playerUrl); return it }
                last = IllegalStateException("Player script request failed with HTTP ${r.status}")
                if (r.status !in setOf(408, 425, 429, 500, 502, 503, 504)) break
            }
            throw last!!
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            downloadFailed[playerUrl] = TimeSource.Monotonic.markNow()
            throw e
        }
    }

    private suspend fun program(playerUrl: String): PlayerProgram = mutex.withLock {
        when (val p = programs[playerUrl]) {
            is PlayerProgram -> return@withLock p
            is TimeSource.Monotonic.ValueTimeMark -> if (p.elapsedNow() < 60.seconds) error("Player recently failed to compile")
        }
        val js = download(playerUrl)
        // UnsupportedPlayerException and any other Exception fail this player closed.
        val compiled = try {
            withContext(Dispatchers.Default) { PlayerProgram.compile(js) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            programs[playerUrl] = TimeSource.Monotonic.markNow()
            throw e
        }
        programs.remove(playerUrl)
        programs[playerUrl] = compiled
        while (programs.size > 4) programs.remove(programs.keys.first())
        compiled
    }

    /** The es6 player is solved from its player_ias build, else (or when that fails) from the exact URL. */
    private suspend fun programFor(playerUrl: String): PlayerProgram? {
        val preferred = (if ("/player_es6.vflset/" in playerUrl) PLAYER_HASH.firstNotNullOfOrNull { it.find(playerUrl)?.groupValues?.get(1) }
            ?.let { "https://www.youtube.com/s/player/$it/player_ias.vflset/en_GB/base.js" } else null) ?: canonical(playerUrl)
        return try {
            program(preferred)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (preferred == playerUrl) return null
            try {
                program(playerUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun process(playerUrl: String, formats: List<Format>): List<Format> {
        fun String.n() = if (allowedMedia(this)) N.find(this)?.takeIf { it.range.first < (indexOf('#').takeIf { i -> i >= 0 } ?: length) } else null
        if (formats.none { !it.signatureCipher.isNullOrBlank() || !it.cipher.isNullOrBlank() || it.url?.n() != null }) return formats
        val program = programFor(playerUrl)
        return withContext(Dispatchers.Default) {
            val signatures = HashMap<String, String?>()
            val nValues = HashMap<String, String?>()
            formats.map { f ->
                val cipher = f.signatureCipher?.takeIf { it.isNotBlank() } ?: f.cipher
                var resolved = f
                if (!cipher.isNullOrBlank()) {
                    val params = params(cipher)
                    val url = params?.get("url")
                    val s = params?.get("s")
                    val sp = params?.get("sp") ?: "signature"
                    if (url == null || !allowedMedia(url) || s == null || !PARAM_NAME.matches(sp)) return@map f.withUrl(null)
                    if (s !in signatures) signatures[s] = transform(program, s, n = false)
                    val signed = signatures[s]?.let { append(url, sp, it) } ?: return@map f.withUrl(null)
                    resolved = f.withUrl(signed)
                }
                val url = resolved.url ?: return@map resolved
                val match = url.n() ?: return@map resolved
                val challenge = runCatching { decodeQueryComponent(match.groupValues[1]) }.getOrNull() ?: return@map resolved.withUrl(null)
                if (challenge !in nValues) nValues[challenge] = transform(program, challenge, n = true)
                resolved.withUrl(nValues[challenge]?.let { replaceN(url, match, it) })
            }
        }
    }

    private fun transform(program: PlayerProgram?, input: String, n: Boolean): String? {
        if (program == null || input.length > 16 * 1024) return null
        return try {
            (if (n) program.transformN(input) else program.decipherSignature(input))
                .takeUnless { it.isBlank() || it.length > 65_536 || (n && (it == input || it.startsWith("enhanced_except_"))) }
        } catch (_: Exception) {
            null
        }
    }

    private fun params(cipher: String): Map<String, String>? {
        if (cipher.length > 64 * 1024) return null
        val out = HashMap<String, String>()
        for (pair in cipher.split("&")) {
            val parts = pair.split("=", limit = 2)
            if (parts.size == 2) out[parts[0]] = runCatching { decodeQueryComponent(parts[1]) }.getOrNull() ?: return null
        }
        return out
    }

    private fun append(url: String, name: String, value: String): String? {
        if (value.isBlank() || value.length > 65_536) return null
        val end = url.indexOf('#').takeIf { it >= 0 } ?: url.length
        val separator = when {
            url.indexOf('?').let { it < 0 || it >= end } -> "?"
            end > 0 && url[end - 1] in "?&" -> ""
            else -> "&"
        }
        return (url.substring(0, end) + separator + name + "=" + encodeQueryComponent(value) + url.substring(end)).takeIf { it.length <= 16 * 1024 }
    }

    private fun replaceN(url: String, match: MatchResult, value: String): String? {
        val end = url.indexOf('#').takeIf { it >= 0 } ?: url.length
        if (match.range.first >= end || value.isBlank() || value.length > 65_536) return null
        val start = match.range.last + 1 - match.groupValues[1].length
        return url.replaceRange(start, match.range.last + 1, encodeQueryComponent(value)).takeIf { it.length <= 16 * 1024 }
    }

    private companion object {
        val N = Regex("[&?]n=([^&#]+)")
        val PARAM_NAME = Regex("[A-Za-z][A-Za-z0-9._~-]*")
    }
}
