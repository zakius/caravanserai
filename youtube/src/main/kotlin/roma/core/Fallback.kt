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
 * The fallback lookup: InnerTubeX 0.11.2's extractor, cut to what roma asks of it (audio only, best quality, no hints,
 * no client overrides). Each lookup tries, in order:
 * 1. VISIONOS 0.1 with the session's visitor data. Its result is kept only if it gives a URL that needs no signature
 *    or n-parameter work.
 * 2. The clients in [automatic] order that this session can use, with the watch page's player config. Each response
 *    goes through the player's signature and n functions (QuackJS). The config is cached for 3 hours, and fetched again
 *    once if the cached one may be why a pass failed. Web clients need a PoToken ([tokens]); without one their streams
 *    stop after about 1 MB.
 * 3. When signed in, the same with the account's cookies on the watch page.
 * 4. The embedded player with the embed page's config, then the kids web client unless the song is age-restricted.
 *
 * A confirmed age gate in any response drops the clients that can't play age-restricted songs. A lookup sends at most
 * [MAX_REQUESTS] player requests within [LOOKUP_TIME], and never sends an identical request twice. A client that
 * failed recently scores lower, and one that failed twice in a row goes last for 2 minutes.
 *
 * Two parts of InnerTubeX's extractor are left out: its 60 s cache of refusals (roma keeps its own in [Streams]) and
 * cancelling a lookup when the session changes.
 */
internal class Fallback(private val innerTube: InnerTube, private val tokens: TokenProvider?) {
    /** One of InnerTubeX's playback client manifests, the fields roma's lookups read. */
    class Manifest internal constructor(
        val id: String,
        val client: YouTubeClient,
        val score: Int,
        /** The score for age-restricted songs, given whether the session is signed in; null when it can't play them. */
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

    // The scores are InnerTubeX's. They weigh priority, content support and lifecycle against the token, cipher and
    // WebView costs.
    private val automatic = listOf(
        embedded,
        Manifest("WEB_REMIX", YouTubeClient.WEB_REMIX, 74, { signedIn -> if (signedIn) 92 else 56 }, gvsToken = true),
        Manifest("WEB_CREATOR", YouTubeClient.WEB_CREATOR, 67, { 67 }, authRequired = true, gvsToken = true),
        Manifest("TVHTML5_SIMPLY", YouTubeClient.TVHTML5_SIMPLY, 18, null, playerToken = true, gvsToken = true, tokenCookies = false),
    )

    private class Config(val playerUrl: String, val sts: Int?, val visitorData: String?, val clientVersion: String?, val hostFlags: String? = null)

    private class Cached(val config: Config, val at: Long, val identity: Pair<Any, String?>, val login: Boolean)

    private val configs = HashMap<Boolean, Cached>()
    private val configMutex = Mutex()

    class Result internal constructor(val manifest: Manifest, val client: YouTubeClient, val response: PlayerResponse, val usedToken: Boolean, val gvsToken: String?) {
        val profile: String
            get() {
                val token = if (usedToken) "po" else "nopo"
                return "${manifest.id}__$token"
            }
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
        private val deadline = TimeSource.Monotonic.markNow() + LOOKUP_TIME
        private var requestsLeft = MAX_REQUESTS
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

        fun timeLeftOrNull(limit: Duration): Duration? {
            val left = -deadline.elapsedNow()
            if (!left.isPositive()) return null
            return minOf(limit, left)
        }

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
                    withTimeoutOrNull(timeLeft(TOKEN_TIMEOUT)) { tokens?.getPoToken(videoId, key.visitorData, key.cookie) }
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
            val stream = withTimeoutOrNull(l.timeLeft(Duration.INFINITE)) { lookup(l, excluded, cpn) } ?: throw Exhausted()
            // A stream found after the deadline still counts as running out of time.
            l.timeLeft(Duration.INFINITE)
            stream
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
        // A pass without a page config asks only the fast-path clients, whose URLs need no deciphering.
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
            if (!config?.hostFlags.isNullOrBlank() && config.sts != null) {
                withConfig(l, config, embedded, excluded, cpn)?.let { return it }
            }
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
            val lastRequestFailure = l.requestFailures.lastOrNull()
            if (lastRequestFailure != null) {
                throw StreamResolveException(StreamResolveException.Reason.NETWORK, "Unable to fetch stream data from YouTube.", lastRequestFailure)
            }
        }
        throw StreamResolveException(StreamResolveException.Reason.NO_PLAYABLE_STREAM, "No playable stream found for this track.")
    }

    /** What a cached config depends on: the locale and, for a signed-in config, a hash of the account's cookie. */
    private fun identity(login: Boolean): Pair<Any, String?> {
        val s = innerTube.sessionSnapshot()
        val cookie = s.cookie
        var cookieHash: String? = null
        if (login && cookie != null) {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(cookie.encodeToByteArray())
            cookieHash = digest.toHexString()
        }
        return Pair(s.locale, cookieHash)
    }

    // Call only while holding configMutex.
    private fun cached(login: Boolean): Cached? {
        val c = configs[login] ?: return null
        if (c.identity == identity(login) && System.currentTimeMillis() - c.at <= CONFIG_MAX_AGE_MS) return c
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
            // The pass failed only on account gates or errors, which a fresh config wouldn't change.
            if (l.failures.size > failures && l.nonGatePasses == passes) return null
        }
        if (l.remaining <= 0) return null
        // The lock is held over the fetch, so concurrent lookups fetch the page only once.
        val fresh = configMutex.withLock {
            if (cached != null && configs[login] === cached) configs.remove(login)
            val existing = cached(login)
            if (existing != null) return@withLock existing
            val fetched = Cached(fetchConfig(l.videoId, login, embed = false), System.currentTimeMillis(), identity(login), login)
            configs[login] = fetched
            fetched
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
            val streamingData = result.response.streamingData ?: continue
            val all = streamingData.formats.orEmpty() + streamingData.adaptiveFormats
            val bounded = result.manifest.id == "TVHTML5_SIMPLY"
            val directItags = all.filter { it.isAudio && !it.url.isNullOrBlank() }.map { it.itag }.toSet()
            val direct = best(all.filter { it.isAudio && !it.url.isNullOrBlank() })
            val anyAudio = all.filter {
                it.isAudio && (!it.url.isNullOrBlank() || !it.signatureCipher.isNullOrBlank() || !it.cipher.isNullOrBlank())
            }
            val bestAny = best(anyAudio, requireUrl = false)
            // A ready URL is used only when it is also the best format overall; otherwise deciphering may give a better one.
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
            val firstTry = if (preferred != null) listOf(preferred) else raw
            var format = pick(cipher.process(config.playerUrl, firstTry))
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

    // TVHTML5_SIMPLY's streams need a known length. It comes from the format, or else from a HEAD request.
    private suspend fun contentLength(l: Lookup, known: Long?, url: String): Long? {
        if (known != null && known > 0L) return known
        val timeout = l.timeLeftOrNull(REQUEST_TIMEOUT) ?: return null
        return withTimeoutOrNull(timeout) { innerTube.mediaContentLength(url, emptyMap()) }
    }

    private class Health(val failures: Int, val at: Long, val cooldownUntil: Long)

    /** Per client and scope (content, signed in): consecutive failures, forgotten 10 minutes after the last one. */
    private val healths = java.util.concurrent.ConcurrentHashMap<String, Health>()

    private fun health(id: String, scope: String): Int {
        val now = System.currentTimeMillis()
        val h = healths["$id $scope"] ?: return 0
        if (now - h.at >= HEALTH_MEMORY_MS) return 0
        val penalty = FAILURE_PENALTY * minOf(h.failures, MAX_COUNTED_FAILURES)
        val cooldown = if (h.cooldownUntil > now) COOLDOWN_PENALTY else 0
        return -penalty - cooldown
    }

    private fun failed(id: String, scope: String) {
        val now = System.currentTimeMillis()
        val previous = healths["$id $scope"]
        val earlier = if (previous != null && now - previous.at < HEALTH_MEMORY_MS) previous.failures else 0
        val n = earlier + 1
        val cooldownUntil = if (n >= 2) now + COOLDOWN_MS else 0L
        healths["$id $scope"] = Health(n, now, cooldownUntil)
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
        val configVisitor = config.visitorData?.takeIf { it.isNotBlank() }
        val sessionVisitor = initial.visitorData?.takeIf { it.isNotBlank() }
        val embedVisitor = if (config.hostFlags.isNullOrBlank()) null else configVisitor
        val visitor = embedVisitor ?: sessionVisitor ?: configVisitor
        var session = initial.copy(visitorData = visitor)
        val authenticated = !session.sapisid.isNullOrBlank()
        val age = l.ageRestricted
        val content = if (age) "age" else if (override === kids) "kids" else "normal"
        val scope = "$content $authenticated"
        val clients = if (override != null && !override.excluded(excluded)) {
            listOf(override)
        } else {
            val candidates = automatic + listOfNotNull(visionOs.takeIf { visitor != null })
            val usable = candidates.filter { m ->
                !m.excluded(excluded) &&
                    (!m.authRequired || authenticated) &&
                    (!m.tokenRequired || tokens != null) &&
                    (if (age) m.ageScore != null else m.normal) &&
                    (allowCipher || m.fastPath)
            }
            val withHealth = usable.map { m -> m to health(m.id, scope) }
            // Repeatedly failing clients go behind every healthy one instead of only losing points.
            val order = compareBy<Pair<Manifest, Int>> { (_, healthScore) -> healthScore <= UNHEALTHY_SCORE }
                .thenByDescending { (m, healthScore) ->
                    val base = if (age) m.ageScore!!(authenticated) else m.score
                    base + healthScore
                }
                .thenBy { (m, _) -> m.id }
            withHealth.sortedWith(order).map { (m, _) -> m }
        }
        val playable = mutableListOf<Result>()
        val failures = mutableListOf<Failure>()
        val requestFailures = mutableListOf<Throwable>()
        var sent = 0
        var onlyUnavailable = allowCipher && config.playerUrl.isNotBlank() && (!authenticated || clients.any { it.client.loginSupported })
        var onlyBootstrapIndependent = true
        for (m in clients) {
            if (sent >= MAX_REQUESTS_PER_PASS || l.remaining <= 0) break
            if (override == null && l.ageRestricted && m.ageScore == null) continue
            // A tokenized attempt may have published fresh visitor data. Carry it forward.
            val latest = innerTube.sessionSnapshot()
            if (latest.generation != session.generation) {
                val freshVisitor = latest.visitorData
                if (!freshVisitor.isNullOrBlank()) {
                    val updated = innerTube.sessionSnapshotWithVisitorData(session, freshVisitor)
                    if (updated != null) session = updated
                }
            }
            val version = config.clientVersion
            val client = if (!version.isNullOrBlank() && m.client.clientName == "WEB_EMBEDDED_PLAYER") {
                m.client.copy(clientVersion = version)
            } else {
                m.client
            }
            if (override == null && m === visionOs && visitor.isNullOrBlank()) continue
            val before = l.remaining
            val untokenizedFailed = "${m.id}__nopo" in excluded && m.canMint()
            val a = attempt(l, m, client, config, override != null, untokenizedFailed, session)
            sent += before - l.remaining
            val status = a.failure?.status
            // A success doesn't clear a client's failures, because a playable response can still fail at the CDN.
            val healthContent = if (l.ageRestricted) "age" else if (override === kids) "kids" else "normal"
            val healthScope = "$healthContent $authenticated"
            val playabilityFailure = a.result == null && !a.tokenUnavailable && !a.budgetExhausted &&
                !status.isAccountGate() && status != "VIDEO_UNAVAILABLE" && status != "LIVE_STREAM_OFFLINE"
            if (!(m === visionOs && visitor.isNullOrBlank())) {
                if (a.requestFailure != null) {
                    if (l.reported.add("${m.id} request")) failed(m.id, healthScope)
                } else if (playabilityFailure) {
                    if (l.reported.add("${m.id} playability")) failed(m.id, healthScope)
                }
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
                val failure = a.failure
                if (failure != null) failures += failure
                val requestFailure = a.requestFailure
                if (requestFailure != null) requestFailures += requestFailure
            }
        }
        if (playable.isNotEmpty()) return Batch(playable, failures, requestFailures)
        return Batch(
            emptyList(),
            failures,
            requestFailures,
            onlyUnavailable = onlyUnavailable && failures.isNotEmpty(),
            onlyBootstrapIndependent = onlyBootstrapIndependent && failures.isNotEmpty(),
        )
    }

    private suspend fun attempt(
        l: Lookup, m: Manifest, client: YouTubeClient, config: Config, manual: Boolean, untokenizedFailed: Boolean, session: InnerTube.SessionSnapshot,
    ): Attempt = try {
        val canMint = m.canMint()
        val allowUntokenized = (manual && !canMint) || !m.tokenRequired
        val visitorData = session.visitorData
        val tokenCookie = if (m.tokenCookies) session.cookie else null
        val known = if (visitorData.isNullOrBlank()) null else TokenKey(l.videoId, visitorData, tokenCookie)
        when {
            m.tokenRequired && !allowUntokenized && known != null && known in l.noToken -> Attempt(tokenUnavailable = true)
            (untokenizedFailed || m.playerToken) && canMint -> tokenized(l, m, client, config, session, null, null, null)
            else -> {
                // A GVS-only token never enters the player request, so mint it while that request is in flight.
                var prefetch: Pair<TokenKey, Deferred<PoTokenResult?>>? = null
                if (known != null && canMint && m.gvsToken && !m.playerToken && !allowUntokenized && l.minted[known] == null) {
                    prefetch = known to l.prefetch(known)
                }
                untokenizedFirst(l, m, client, config, allowUntokenized, session, prefetch)
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
        if (!m.canMint()) return Attempt(failure = if (playable) null else failure, tokenUnavailable = playable)
        // InnerTubeX retries a refusal with a token only for songs its caller marks age-restricted; roma marks none.
        if (!playable) return Attempt(failure = failure)
        return tokenized(l, m, client, config, session, null, response, prefetch)
    }

    private suspend fun tokenized(
        l: Lookup, m: Manifest, client: YouTubeClient, config: Config, session: InnerTube.SessionSnapshot,
        fallbackFailure: Failure?, existing: PlayerResponse?, prefetch: Pair<TokenKey, Deferred<PoTokenResult?>>?,
    ): Attempt {
        val tokenSession = if (session.visitorData.isNullOrBlank()) {
            if (l.visitorUnavailable) return Attempt(failure = fallbackFailure, tokenUnavailable = true)
            val v = withTimeoutOrNull(l.timeLeft(REQUEST_TIMEOUT)) { innerTube.fetchFreshVisitorData(session) }
            if (v.isNullOrBlank()) {
                l.visitorUnavailable = true
                return Attempt(failure = fallbackFailure, tokenUnavailable = true)
            }
            innerTube.sessionSnapshotWithVisitorData(session, v) ?: return Attempt(failure = fallbackFailure, tokenUnavailable = true)
        } else {
            session
        }
        val visitor = tokenSession.visitorData!!
        val tokenCookie = if (m.tokenCookies) tokenSession.cookie else null
        val key = TokenKey(l.videoId, visitor, tokenCookie)
        if (key in l.noToken) return Attempt(failure = fallbackFailure, tokenUnavailable = true)
        var token = l.minted[key]
        if (token == null) {
            token = try {
                withTimeoutOrNull(l.timeLeft(TOKEN_TIMEOUT)) {
                    val pending = if (prefetch != null && prefetch.first == key) prefetch.second else l.prefetch(key)
                    pending.await()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (token != null) l.minted[key] = token
        }
        if (token == null) {
            l.noToken += key
            return Attempt(failure = fallbackFailure, tokenUnavailable = true)
        }
        // The player token is bound to the visitor, the streaming token to the video.
        val playerToken = if (m.playerToken) token.playerRequestToken else null
        val gvsToken = if (m.gvsToken) token.streamingDataToken else null
        val wrongVisitor = token.visitorData != visitor
        val missingPlayerToken = m.playerToken && playerToken.isNullOrBlank()
        val missingGvsToken = m.gvsToken && gvsToken.isNullOrBlank()
        val noToken = playerToken.isNullOrBlank() && gvsToken.isNullOrBlank()
        if (wrongVisitor || missingPlayerToken || missingGvsToken || noToken) {
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
        val timeout = l.timeLeft(REQUEST_TIMEOUT)
        val response = try {
            withTimeout(timeout) {
                // Non-2xx responses throw InnerTubeHttpException before their bodies are read.
                val text = innerTube.playerTextWithSessionBound(client, l.videoId, sts, poToken, session, hostFlags, MAX_PLAYER_RESPONSE_BYTES)
                withContext(Dispatchers.Default) { parse(text, l.videoId) }
            }
        } catch (e: TimeoutCancellationException) {
            // Running out of the lookup's own time is not this client's fault.
            if (timeout < REQUEST_TIMEOUT) throw Exhausted()
            throw Exception("Player request for ${client.clientName} exceeded ${REQUEST_TIMEOUT.inWholeMilliseconds}ms", e)
        }
        val playability = response?.playabilityStatus
        if (playability != null && ageGate(playability.status, playability.desktopLegacyAgeGateReason)) l.ageRestricted = true
        l.responses[key] = response
        return response
    }

    private fun parse(text: String, videoId: String): PlayerResponse? {
        val r = runCatching { json.decodeFromString<PlayerResponse>(text) }.getOrNull() ?: return null
        val responseVideoId = r.videoDetails?.videoId
        if (!responseVideoId.isNullOrBlank() && responseVideoId != videoId) return null
        val n = (r.streamingData?.formats?.size ?: 0) + (r.streamingData?.adaptiveFormats?.size ?: 0)
        return if (n <= MAX_FORMATS) r else null
    }

    private fun playable(r: PlayerResponse, client: YouTubeClient): Boolean {
        if (r.playabilityStatus.status != "OK" && !client.skipPlayerResponseValidation) return false
        val data = r.streamingData
        val formats = if (data == null) emptyList<Format>() else data.formats.orEmpty() + data.adaptiveFormats
        val hasAudio = formats.any {
            it.isAudio && (!it.url.isNullOrBlank() || !it.signatureCipher.isNullOrBlank() || !it.cipher.isNullOrBlank())
        }
        return hasAudio || !data?.hlsManifestUrl.isNullOrBlank()
    }

    private fun hasDirectAudio(r: PlayerResponse): Boolean {
        val data = r.streamingData ?: return false
        val formats = data.formats.orEmpty() + data.adaptiveFormats
        return formats.any { it.isAudio && !it.url.isNullOrBlank() && !N_QUERY.containsMatchIn(it.url) }
    }

    private fun PlayerResponse.PlayabilityStatus.failure() = Failure(status, reason, desktopLegacyAgeGateReason)

    private fun ageGate(status: String?, reason: Int?) =
        status == "AGE_CHECK_REQUIRED" || status == "AGE_VERIFICATION_REQUIRED" || (status == "LOGIN_REQUIRED" && (reason ?: 0) > 0)

    private fun String?.isAccountGate() =
        this == "LOGIN_REQUIRED" || this == "AGE_CHECK_REQUIRED" || this == "AGE_VERIFICATION_REQUIRED" || this == "CONTENT_CHECK_REQUIRED"

    private fun classify(failures: List<Failure>): StreamResolveException.Reason? {
        val ageRestricted = failures.any { f ->
            val status = f.status.orEmpty().uppercase()
            val reason = f.reason.orEmpty().lowercase()
            ageGate(status, f.ageGate) ||
                status == "CONTENT_CHECK_REQUIRED" ||
                listOf("confirm your age", "age-restricted", "age restricted", "age verification").any { reason.contains(it) }
        }
        if (ageRestricted) return StreamResolveException.Reason.AGE_RESTRICTED
        val unavailable = failures.any { f ->
            val status = f.status.orEmpty().uppercase()
            val reason = f.reason.orEmpty().lowercase()
            status in setOf("ERROR", "UNPLAYABLE", "VIDEO_UNAVAILABLE", "LIVE_STREAM_OFFLINE") ||
                listOf("video unavailable", "not available", "private video", "has been removed").any { reason.contains(it) }
        }
        if (unavailable) return StreamResolveException.Reason.UNAVAILABLE
        return null
    }

    private suspend fun fetchConfig(videoId: String, login: Boolean, embed: Boolean): Config {
        require(Regex("[A-Za-z0-9_-]{1,64}").matches(videoId)) { "Invalid video ID" }
        val pageUrl = if (embed) "https://www.youtube.com/embed/$videoId?html5=1" else "https://www.youtube.com/watch?v=$videoId&bpctr=9999999999&has_verified=1"
        val sessionCookie = innerTube.cookie
        var cookieParts = emptyList<String>()
        if (login && sessionCookie != null) {
            cookieParts = sessionCookie.split(';').map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("PREF=", ignoreCase = true) }
        }
        val cookie = if (cookieParts.isEmpty()) null else cookieParts.joinToString("; ")
        // The page is streamed and runs to about 1.4 MB. Most fields sit in its first 60 KB or so, but STS is often
        // only near its middle, so reading stops as soon as all of them are found.
        val done: (String) -> Boolean = { html ->
            playerUrl(html) != null && VISITOR.find(html) != null && CLIENT_VERSION.find(html) != null &&
                (!embed || HOST_FLAGS.find(html) != null) && sts(html) != null
        }
        suspend fun page(c: String?): String {
            val headers = buildMap {
                put("User-Agent", YouTubeClient.USER_AGENT_WEB)
                put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                if (embed) put("Referer", "https://www.reddit.com/")
                if (c != null) put("Cookie", c)
                put("Accept-Language", innerTube.locale.acceptLanguageHeader())
            }
            return getText(pageUrl, maxBytes = 4 * 1024 * 1024, timeoutMs = 10_000, stopWhen = done, headers = headers)
        }
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
        val visitorData = VISITOR.find(html)?.groupValues?.get(1)
        val clientVersion = CLIENT_VERSION.find(html)?.groupValues?.get(1)
        val hostFlags = if (embed) HOST_FLAGS.find(html)?.groupValues?.get(1) else null
        return Config(playerUrl, sts, visitorData, clientVersion, hostFlags)
    }

    private suspend fun iframePlayerUrl(): String? = try {
        val headers = mapOf("User-Agent" to YouTubeClient.USER_AGENT_WEB)
        val api = getText("https://www.youtube.com/iframe_api", maxBytes = 1024 * 1024, timeoutMs = 8_000, stopWhen = null, headers = headers)
        val id = Regex("/s/player/([a-zA-Z0-9_-]+)/").find(api.replace("\\/", "/"))?.groupValues?.get(1)
        if (id == null) null else validPlayerUrl("https://www.youtube.com/s/player/$id/player_ias.vflset/en_GB/base.js")
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun playerUrl(html: String): String? {
        fun String.unescape() = replace("\\/", "/").replace("\\u0026", "&")
        val keyed = KEYED_PLAYER_URL.asSequence().mapNotNull { it.find(html)?.groupValues?.get(1)?.unescape() }
        // The loose search runs only when no keyed URL is valid.
        val loose = sequence {
            val match = LOOSE_PLAYER_URL.find(html.unescape())
            if (match != null) yield(match.groupValues[1])
        }
        return (keyed + loose).mapNotNull { path ->
            val absolute = when {
                path.startsWith("http") -> path
                path.startsWith('/') -> "https://www.youtube.com$path"
                else -> "https://www.youtube.com/$path"
            }
            validPlayerUrl(absolute)
        }.firstOrNull()
    }

    private fun sts(text: String) = STS.asSequence().mapNotNull { it.find(text)?.groupValues?.get(1)?.toIntOrNull() }.firstOrNull()

    private fun validPlayerUrl(value: String): String? = httpsUrl(value, ::youTubeHost, PLAYER_PATH::matches)?.toString()

    /** A GET that follows at most [MAX_REDIRECTS] redirects, each only to an approved YouTube page. */
    private suspend fun getText(url: String, maxBytes: Int, timeoutMs: Int, stopWhen: ((String) -> Boolean)?, headers: Map<String, String>): String {
        var current = url
        repeat(MAX_REDIRECTS + 1) { redirects ->
            val r = http(
                current, headers, timeoutMs = timeoutMs, totalMs = timeoutMs.toLong(), maxBytes = maxBytes,
                errorBody = false, redirects = false, stopWhen = stopWhen,
            )
            if (r.ok) return r.text()
            val location = r.header("Location")
            if (location == null || r.status !in REDIRECTS || redirects >= MAX_REDIRECTS) error("HTTP ${r.status}")
            val target = runCatching { URI(current).resolve(location).toString() }.getOrNull() ?: error("HTTP ${r.status}")
            val approved = httpsUrl(target, ::youTubeHost) { path ->
                path == "/watch" || path == "/iframe_api" || EMBED_PATH.matches(path) || PLAYER_PATH.matches(path)
            }
            if (approved == null) error("HTTP ${r.status}")
            current = target
        }
        error("Too many redirects")
    }

    private val cipher = Cipher()

    companion object {
        private val LOOKUP_TIME = 30.seconds
        private const val MAX_REQUESTS = 50
        private const val MAX_REQUESTS_PER_PASS = 24
        private val REQUEST_TIMEOUT = 8.seconds
        private val TOKEN_TIMEOUT = 18.seconds
        private const val MAX_PLAYER_RESPONSE_BYTES = 4 * 1024 * 1024
        private const val MAX_FORMATS = 2048
        private const val CONFIG_MAX_AGE_MS = 3 * 60 * 60 * 1000L
        private const val MAX_REDIRECTS = 3

        // Client health: each recent failure costs FAILURE_PENALTY points, counting at most MAX_COUNTED_FAILURES.
        // Two failures in a row add COOLDOWN_PENALTY for COOLDOWN_MS, which puts the client behind every healthy one.
        // Failures are forgotten HEALTH_MEMORY_MS after the last one.
        private const val FAILURE_PENALTY = 18
        private const val MAX_COUNTED_FAILURES = 5
        private const val COOLDOWN_PENALTY = 1000
        private const val COOLDOWN_MS = 2 * 60 * 1000L
        private const val HEALTH_MEMORY_MS = 10 * 60 * 1000L
        private const val UNHEALTHY_SCORE = -50

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

/** The longest media URL roma accepts. */
private const val MAX_URL_CHARS = 16 * 1024

/** The longest signature, n value or query value roma accepts from the player. */
private const val MAX_VALUE_CHARS = 64 * 1024

private fun allowedMedia(value: String): Boolean =
    value.length in 1..MAX_URL_CHARS && httpsUrl(value, ::mediaHost) { it == "/videoplayback" } != null

private fun String.withPoToken(token: String?): String {
    if (token.isNullOrBlank() || contains("&pot=") || contains("?pot=")) return this
    val hash = indexOf('#')
    val end = if (hash >= 0) hash else length
    val question = indexOf('?')
    val separator = if (question in 0 until end) "&" else "?"
    return substring(0, end) + separator + "pot=" + encodeQueryComponent(token) + substring(end)
}

private const val CPN_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"

internal fun playbackNonce() = buildString(16) { repeat(16) { append(CPN_ALPHABET[Random.nextInt(CPN_ALPHABET.length)]) } }

private fun withCpn(url: String, cpn: String): String {
    val u = httpsUrl(url, { it == "googlevideo.com" || it.endsWith(".googlevideo.com") }) { it == "/videoplayback" } ?: return url
    val query = u.rawQuery
    if (query != null && query.split('&').any { it.substringBefore('=') == "cpn" }) return url
    val hash = url.indexOf('#')
    val end = if (hash >= 0) hash else url.length
    val head = url.substring(0, end)
    val separator = if ('?' in head) "&" else "?"
    return head + separator + "cpn=" + cpn + url.substring(end)
}

/**
 * The best audio format: the original track of a dubbed upload (the xtags' acont=original, else the default track),
 * then bitrate, channels (stereo first), sample rate and container (WebM over MP4).
 */
private fun best(formats: List<Format>, requireUrl: Boolean = true): Format? {
    val valid = formats.filter {
        it.isAudio && it.mimeType.startsWith("audio/", ignoreCase = true) && (!requireUrl || !it.url.isNullOrBlank())
    }
    var original = valid
    if (valid.any { it.audioTrack != null }) {
        original = valid.filter { it.acont() == "original" }
        if (original.isEmpty()) original = valid.filter { it.audioTrack?.audioIsDefault == true }
        if (original.isEmpty()) original = valid
    }
    val order = compareBy<Format> { it.bitrate }
        .thenBy { format ->
            when (format.audioChannels) {
                2 -> 2
                null -> 1
                else -> 0
            }
        }
        // Sample rates above 48 kHz rank the same as 48 kHz.
        .thenBy { (it.audioSampleRate ?: 0).coerceIn(0, 48_000) }
        .thenBy { format ->
            when {
                format.mimeType.contains("audio/webm") -> 2
                format.mimeType.contains("audio/mp4") -> 1
                else -> 0
            }
        }
    return original.maxWithOrNull(order)
}

/** The acont xtag, such as "original". xtags is a base64url protobuf map of repeated {1: key, 2: value} entries. */
private fun Format.acont(): String? {
    val tags = xtags
    if (tags == null || tags.length > 1024) return null
    val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
    val bytes = runCatching { base64.decode(tags) }.getOrNull() ?: return null
    return runCatching {
        for ((field, entry) in protoFields(bytes)) {
            if (field != 1 || entry == null) continue
            val kv = protoFields(entry)
                .filter { (_, value) -> value != null }
                .associate { (number, value) -> number to value!!.decodeToString() }
            if (kv[1] == "acont") return@runCatching kv[2]
        }
        null
    }.getOrNull()
}

// Protobuf wire format: a varint carries 7 value bits per byte, and the high bit says another byte follows. A field's
// tag is its number shifted left past the 3-bit wire type.
private const val VARINT_VALUE_BITS = 0x7f
private const val VARINT_MORE = 0x80
private const val WIRE_TYPE_BITS = 3
private const val WIRE_TYPE_MASK = 0b111L
private const val WIRE_VARINT = 0
private const val WIRE_FIXED64 = 1
private const val WIRE_LENGTH_DELIMITED = 2
private const val WIRE_FIXED32 = 5

/** The fields of a protobuf message: number and, for length-delimited ones, the bytes. Throws on malformed input. */
private fun protoFields(b: ByteArray): List<Pair<Int, ByteArray?>> {
    var i = 0
    fun varint(): Long {
        var r = 0L
        var shift = 0
        while (true) {
            require(i < b.size && shift < 64)
            val x = b[i++].toInt()
            r = r or ((x and VARINT_VALUE_BITS).toLong() shl shift)
            if ((x and VARINT_MORE) == 0) return r
            shift += 7
        }
    }
    val out = mutableListOf<Pair<Int, ByteArray?>>()
    while (i < b.size) {
        val tag = varint()
        val field = (tag ushr WIRE_TYPE_BITS).toInt()
        require(field > 0)
        when ((tag and WIRE_TYPE_MASK).toInt()) {
            WIRE_VARINT -> {
                varint()
                out += field to null
            }
            WIRE_FIXED64 -> {
                i += 8
                require(i <= b.size)
                out += field to null
            }
            WIRE_LENGTH_DELIMITED -> {
                val n = varint()
                require(n >= 0 && n <= b.size - i)
                out += field to b.copyOfRange(i, i + n.toInt())
                i += n.toInt()
            }
            WIRE_FIXED32 -> {
                i += 4
                require(i <= b.size)
                out += field to null
            }
            else -> error("wire type")
        }
    }
    return out
}

private val LOCALIZED_IAS = Regex("^https://www\\.youtube\\.com/s/player/([A-Za-z0-9_-]+)/player_ias\\.vflset/[A-Za-z]{2,3}(?:_[A-Za-z]{2,4})?/base\\.js$")
private val PLAYER_HASH = listOf(Regex("/player/([a-f0-9]{8})/"), Regex("player_ias\\.vflset/[^/]+/([a-f0-9]{8})/"), Regex("/s/player/([a-f0-9]{8})/"))

/** Locale variants of one player_ias build are the same script; one download and one compiled program serve them. */
private fun canonical(url: String): String {
    val match = LOCALIZED_IAS.matchEntire(url) ?: return url
    return "https://www.youtube.com/s/player/${match.groupValues[1]}/player_ias.vflset/en_GB/base.js"
}

/**
 * Signature and n-parameter solving with QuackJS. A player QuackJS can't compile gives formats without URLs, so that
 * client's response is skipped: no stream from it, but no crash either. Compiled programs are kept for the
 * [KEPT_PROGRAMS] latest players, a compile failure for [COMPILE_FAILURE_WAIT], and a download failure for
 * [DOWNLOAD_FAILURE_WAIT].
 */
private class Cipher {
    private val mutex = Mutex()

    /** Per player URL, its PlayerProgram, or the TimeMark of its compile failure. */
    private val programs = LinkedHashMap<String, Any>()

    /** The last downloaded player script, as its URL and source. */
    private var code: Pair<String, String>? = null
    private val downloadFailed = HashMap<String, TimeSource.Monotonic.ValueTimeMark>()

    suspend fun code(url: String): String = mutex.withLock { download(url) }

    // Call only while holding mutex. One download runs at a time, and every lookup that needs the same player shares it.
    private suspend fun download(playerUrl: String): String {
        val cached = code
        if (cached != null && cached.first == playerUrl) return cached.second
        val failedAt = downloadFailed[playerUrl]
        if (failedAt != null && failedAt.elapsedNow() < DOWNLOAD_FAILURE_WAIT) error("Player script download recently failed")
        require(httpsUrl(playerUrl, ::youTubeHost) { it.startsWith("/s/player/") && it.endsWith(".js") } != null) {
            "Player script URL must use an approved YouTube HTTPS endpoint"
        }
        try {
            var last: Exception? = null
            for (attempt in 0..2) {
                // Waits 250 ms before the second attempt and 500 ms before the third.
                if (attempt > 0) delay(250L * attempt)
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
                if (r.ok) {
                    val text = r.text()
                    code = playerUrl to text
                    downloadFailed.remove(playerUrl)
                    return text
                }
                last = IllegalStateException("Player script request failed with HTTP ${r.status}")
                if (r.status !in RETRY_STATUSES) break
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
            is TimeSource.Monotonic.ValueTimeMark -> if (p.elapsedNow() < COMPILE_FAILURE_WAIT) error("Player recently failed to compile")
        }
        val js = download(playerUrl)
        // UnsupportedPlayerException, like any other Exception, marks this player as failed, so its formats get no URLs.
        val compiled = try {
            withContext(Dispatchers.Default) { PlayerProgram.compile(js) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            programs[playerUrl] = TimeSource.Monotonic.markNow()
            throw e
        }
        // Removing first moves the player to the end of the order, so the oldest one is evicted first.
        programs.remove(playerUrl)
        programs[playerUrl] = compiled
        while (programs.size > KEPT_PROGRAMS) programs.remove(programs.keys.first())
        compiled
    }

    /** The es6 player is solved from its player_ias build, else (or when that fails) from the exact URL. */
    private suspend fun programFor(playerUrl: String): PlayerProgram? {
        val es6Hash = if ("/player_es6.vflset/" in playerUrl) {
            PLAYER_HASH.firstNotNullOfOrNull { pattern -> pattern.find(playerUrl)?.groupValues?.get(1) }
        } else {
            null
        }
        val preferred = if (es6Hash != null) {
            "https://www.youtube.com/s/player/$es6Hash/player_ias.vflset/en_GB/base.js"
        } else {
            canonical(playerUrl)
        }
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
        // The n parameter of a media URL, when it sits before the fragment.
        fun nParameter(url: String): MatchResult? {
            if (!allowedMedia(url)) return null
            val match = N.find(url) ?: return null
            val hash = url.indexOf('#')
            val end = if (hash >= 0) hash else url.length
            return if (match.range.first < end) match else null
        }
        val needsWork = formats.any { f ->
            val url = f.url
            !f.signatureCipher.isNullOrBlank() || !f.cipher.isNullOrBlank() || (url != null && nParameter(url) != null)
        }
        if (!needsWork) return formats
        val program = programFor(playerUrl)
        return withContext(Dispatchers.Default) {
            val signatures = HashMap<String, String?>()
            val nValues = HashMap<String, String?>()
            formats.map { f ->
                val signatureCipher = f.signatureCipher
                val cipher = if (!signatureCipher.isNullOrBlank()) signatureCipher else f.cipher
                var resolved = f
                if (!cipher.isNullOrBlank()) {
                    val params = params(cipher)
                    val url = params?.get("url")
                    val s = params?.get("s")
                    val sp = params?.get("sp") ?: "signature"
                    if (url == null || !allowedMedia(url) || s == null || !PARAM_NAME.matches(sp)) return@map f.withUrl(null)
                    if (s !in signatures) signatures[s] = transform(program, s, n = false)
                    val signature = signatures[s]
                    val signed = if (signature == null) null else append(url, sp, signature)
                    if (signed == null) return@map f.withUrl(null)
                    resolved = f.withUrl(signed)
                }
                val url = resolved.url ?: return@map resolved
                val match = nParameter(url) ?: return@map resolved
                val challenge = runCatching { decodeQueryComponent(match.groupValues[1]) }.getOrNull() ?: return@map resolved.withUrl(null)
                if (challenge !in nValues) nValues[challenge] = transform(program, challenge, n = true)
                val solved = nValues[challenge]
                val solvedUrl = if (solved == null) null else replaceN(url, match, solved)
                resolved.withUrl(solvedUrl)
            }
        }
    }

    private fun transform(program: PlayerProgram?, input: String, n: Boolean): String? {
        if (program == null || input.length > 16 * 1024) return null
        return try {
            val output = if (n) program.transformN(input) else program.decipherSignature(input)
            // An n result equal to its input, or one starting with the player's "enhanced_except_" error marker, means
            // the challenge wasn't solved.
            val unsolved = n && (output == input || output.startsWith("enhanced_except_"))
            if (output.isBlank() || output.length > MAX_VALUE_CHARS || unsolved) null else output
        } catch (_: Exception) {
            null
        }
    }

    private fun params(cipher: String): Map<String, String>? {
        if (cipher.length > 64 * 1024) return null
        val out = HashMap<String, String>()
        for (pair in cipher.split("&")) {
            val parts = pair.split("=", limit = 2)
            if (parts.size != 2) continue
            val value = runCatching { decodeQueryComponent(parts[1]) }.getOrNull() ?: return null
            out[parts[0]] = value
        }
        return out
    }

    private fun append(url: String, name: String, value: String): String? {
        if (value.isBlank() || value.length > MAX_VALUE_CHARS) return null
        val hash = url.indexOf('#')
        val end = if (hash >= 0) hash else url.length
        val question = url.indexOf('?')
        val separator = when {
            question < 0 || question >= end -> "?"
            end > 0 && url[end - 1] in "?&" -> ""
            else -> "&"
        }
        val result = url.substring(0, end) + separator + name + "=" + encodeQueryComponent(value) + url.substring(end)
        return if (result.length <= MAX_URL_CHARS) result else null
    }

    private fun replaceN(url: String, match: MatchResult, value: String): String? {
        val hash = url.indexOf('#')
        val end = if (hash >= 0) hash else url.length
        if (match.range.first >= end || value.isBlank() || value.length > MAX_VALUE_CHARS) return null
        val start = match.range.last + 1 - match.groupValues[1].length
        val result = url.replaceRange(start, match.range.last + 1, encodeQueryComponent(value))
        return if (result.length <= MAX_URL_CHARS) result else null
    }

    private companion object {
        const val KEPT_PROGRAMS = 4
        val COMPILE_FAILURE_WAIT = 60.seconds
        val DOWNLOAD_FAILURE_WAIT = 15.seconds
        val RETRY_STATUSES = setOf(408, 425, 429, 500, 502, 503, 504)
        val N = Regex("[&?]n=([^&#]+)")
        val PARAM_NAME = Regex("[A-Za-z][A-Za-z0-9._~-]*")
    }
}
