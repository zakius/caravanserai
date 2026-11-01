package com.metrolist.innertubex

import com.metrolist.innertubex.models.Context
import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.innertubex.models.YouTubeLocale
import com.metrolist.innertubex.models.body.BrowseBody
import com.metrolist.innertubex.models.body.GetSearchSuggestionsBody
import com.metrolist.innertubex.models.body.LikeBody
import com.metrolist.innertubex.models.body.SubscribeBody
import com.metrolist.innertubex.models.body.NextBody
import com.metrolist.innertubex.models.body.PlayerBody
import com.metrolist.innertubex.models.body.SearchBody
import com.metrolist.innertubex.utils.parseCookieString
import com.metrolist.innertubex.utils.sanitizeCookieString
import com.metrolist.innertubex.utils.sanitizeVisitorData
import com.metrolist.innertubex.utils.sha1
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.net.URLEncoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import roma.core.HttpResponse
import roma.core.http
import roma.core.httpsUrl
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@OptIn(ExperimentalAtomicApi::class)
class InnerTube(
    private val retryDelay: suspend (kotlin.time.Duration) -> Unit = { delay(it) },
) {
    private companion object {
        private const val ORIGIN_WWW = "https://www.youtube.com"
        private const val REFERER_WWW = "$ORIGIN_WWW/"
        private const val API_BASE_WWW = "$ORIGIN_WWW/youtubei/v1"

        private const val ORIGIN_MWEB = "https://m.youtube.com"
        private const val REFERER_MWEB = "$ORIGIN_MWEB/"
        private const val API_BASE_MWEB = "$ORIGIN_MWEB/youtubei/v1"

        private const val ORIGIN_MUSIC = "https://music.youtube.com"
        private const val REFERER_MUSIC = "$ORIGIN_MUSIC/"
        private const val API_BASE_MUSIC = "$ORIGIN_MUSIC/youtubei/v1"
        private const val VISITOR_DATA_URL = "$ORIGIN_WWW/sw.js_data"

        private const val ORIGIN_STUDIO = "https://studio.youtube.com"
        private const val REFERER_STUDIO = "$ORIGIN_STUDIO/"
        private const val API_BASE_STUDIO = "$ORIGIN_STUDIO/youtubei/v1"
        private const val MAX_API_RESPONSE_BYTES = 4 * 1024 * 1024

        private val TRANSIENT_STATUS_CODES = setOf(408, 425, 429, 500, 502, 503, 504)
        private const val MAX_RETRY_AFTER_MS = 5_000L
        private val MEDIA_REQUEST_HEADERS = setOf("accept", "accept-language", "range", "user-agent")
        private val VISITOR_DATA_REGEX = Regex(""""(?:VISITOR_DATA|visitorData)"\s*:\s*"([^"]+)"""")
        private const val VISITOR_FETCH_TIMEOUT_MS = 15_000L
        private const val SERVICE_WORKER_VISITOR_TIMEOUT_MS = 4_000L
        private const val FIELD_MASK_HEADER = "X-Goog-FieldMask"
        private val VISITOR_FAILURE_COOLDOWN = 30.seconds
    }

    /** Immutable request identity. Contains credentials and must not be serialized or logged directly. */
    data class SessionSnapshot(
        val locale: YouTubeLocale =
            com.metrolist.innertubex.models
                .defaultYouTubeLocale(),
        val visitorData: String? = null,
        val dataSyncId: String? = null,
        val authUser: String = "0",
        val cookie: String? = null,
        val sapisid: String? = null,
        val useLoginForBrowse: Boolean = false,
        val regionOverrideActive: Boolean = false,
        val generation: Long = 0,
    ) {
        override fun toString(): String =
            "SessionSnapshot(" +
                "locale=$locale, " +
                "visitorData=${visitorData.presence()}, " +
                "dataSyncId=${dataSyncId.presence()}, " +
                "authUser=${authUser.presence()}, " +
                "cookie=${cookie.presence()}, " +
                "sapisid=${sapisid.presence()}, " +
                "useLoginForBrowse=$useLoginForBrowse, " +
                "regionOverrideActive=$regionOverrideActive, " +
                "generation=$generation)"

        private fun String?.presence(): String = if (isNullOrBlank()) "missing" else "present"
    }

    private data class VisitorDataFlight(
        val sessionIdentity: SessionSnapshot,
        val result: CompletableDeferred<String?>,
    )

    private data class SessionBoundRequest(
        val session: SessionSnapshot,
        val job: Job,
    )

    private val session = AtomicReference(SessionSnapshot())
    private val mutableSessionFlow = MutableStateFlow(session.load())
    val sessionFlow: StateFlow<SessionSnapshot> = mutableSessionFlow.asStateFlow()

    private val visitorDataFetchMutex = Mutex()
    private val visitorDataScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var visitorDataFlight: VisitorDataFlight? = null

    @Volatile
    private var playerFieldMaskEnabled = true
    private var visitorDataFailure: Pair<SessionSnapshot, TimeSource.Monotonic.ValueTimeMark>? = null
    private val sessionBoundRequests = AtomicReference<List<SessionBoundRequest>>(emptyList())

    var locale: YouTubeLocale
        get() = session.load().locale
        set(value) = updateSession { it.copy(locale = value) }

    var visitorData: String?
        get() = session.load().visitorData
        set(value) = updateSession { it.copy(visitorData = sanitizeVisitorData(value)) }

    var dataSyncId: String?
        get() = session.load().dataSyncId
        set(value) = updateSession { it.copy(dataSyncId = value) }

    var authUser: String
        get() = session.load().authUser
        set(value) = updateSession { it.copy(authUser = sanitizeAuthUser(value)) }

    var cookie: String?
        get() = session.load().cookie
        set(value) {
            val sanitized = value?.let(::sanitizeCookieString)
            updateSession { it.copy(cookie = sanitized, sapisid = resolveSapisidValue(sanitized)) }
        }

    var useLoginForBrowse: Boolean
        get() = session.load().useLoginForBrowse
        set(value) = updateSession { it.copy(useLoginForBrowse = value) }

    var regionOverrideActive: Boolean
        get() = session.load().regionOverrideActive
        set(value) = updateSession { it.copy(regionOverrideActive = value) }

    private inline fun updateSession(transform: (SessionSnapshot) -> SessionSnapshot) {
        while (true) {
            val current = session.load()
            val updated = transform(current)
            if (updated == current) return
            if (publishSession(current, updated)) return
        }
    }

    fun sessionSnapshot(): SessionSnapshot = session.load()

    fun sessionSnapshotWithVisitorData(
        expected: SessionSnapshot,
        visitorData: String,
    ): SessionSnapshot? {
        val current = session.load()
        return current.takeIf { it.visitorData == visitorData && sessionIsCompatible(expected, it) }
    }

    private fun publishSession(
        expected: SessionSnapshot,
        updated: SessionSnapshot,
    ): Boolean {
        val current = session.load()
        if (updated == expected) return current == expected
        // Callers pass value copies, so match by equality; the CAS below still guards against races.
        if (current != expected) return false
        val versioned = updated.copy(generation = expected.generation + 1)
        if (!session.compareAndSet(current, versioned)) return false
        while (true) {
            val published = mutableSessionFlow.value
            if (published.generation >= versioned.generation) break
            if (mutableSessionFlow.compareAndSet(published, versioned)) break
        }
        cancelStaleSessionRequests(versioned)
        return true
    }

    private fun sessionIsCompatible(
        expected: SessionSnapshot,
        current: SessionSnapshot,
    ): Boolean =
        expected.generation == current.generation ||
            (
                expected.visitorData == null && current.visitorData != null &&
                    current.generation == expected.generation + 1 &&
                    expected.copy(visitorData = current.visitorData, generation = current.generation) == current
            )

    private fun cancelStaleSessionRequests(current: SessionSnapshot) {
        while (true) {
            val requests = sessionBoundRequests.load()
            val (active, stale) =
                requests.partition {
                    it.session.generation > current.generation ||
                        sessionIsCompatible(it.session, current)
                }
            if (!sessionBoundRequests.compareAndSet(requests, active)) continue
            stale.forEach { it.job.cancel(CancellationException("InnerTube session changed")) }
            return
        }
    }

    private suspend fun <T> withSessionBoundRequest(
        requestSession: SessionSnapshot,
        block: suspend () -> T,
    ): T =
        coroutineScope {
            val job = currentCoroutineContext()[Job]!!
            val request = SessionBoundRequest(requestSession, job)
            while (true) {
                if (!sessionIsCompatible(requestSession, session.load())) throw CancellationException("InnerTube session changed")
                val requests = sessionBoundRequests.load()
                if (sessionBoundRequests.compareAndSet(requests, requests + request)) break
            }
            try {
                if (!sessionIsCompatible(requestSession, session.load())) throw CancellationException("InnerTube session changed")
                val result = block()
                currentCoroutineContext().ensureActive()
                if (!sessionIsCompatible(requestSession, session.load())) throw CancellationException("InnerTube session changed")
                result
            } finally {
                while (true) {
                    val requests = sessionBoundRequests.load()
                    if (request !in requests || sessionBoundRequests.compareAndSet(requests, requests - request)) break
                }
            }
        }

    private fun sanitizeAuthUser(value: String): String = value.filter { it.isDigit() }.ifBlank { "0" }

    private fun resolveSapisidValue(cookie: String?): String? {
        val cookieMap = cookie?.let(::parseCookieString).orEmpty()
        return cookieMap["SAPISID"]?.takeIf { it.isNotBlank() }
            ?: cookieMap["__Secure-3PAPISID"]?.takeIf { it.isNotBlank() }
            ?: cookieMap["__Secure-1PAPISID"]?.takeIf { it.isNotBlank() }
    }

    private fun sapisidAuthHeader(
        origin: String,
        sapisid: String?,
    ): String? {
        sapisid ?: return null
        val currentTime = Clock.System.now().toEpochMilliseconds() / 1000
        val sapisidHash = sha1("$currentTime $sapisid $origin")
        return "SAPISIDHASH ${currentTime}_$sapisidHash"
    }

    /** SAPISID present so SAPISIDHASH auth can be sent for [YouTubeClient.loginSupported] clients. */
    fun hasSapCookieAuth(): Boolean = hasSapCookieAuth(session.load())

    fun hasSapCookieAuth(session: SessionSnapshot): Boolean = !session.cookie.isNullOrBlank() && !session.sapisid.isNullOrBlank()

    private fun Request.ytClient(
        client: YouTubeClient,
        session: SessionSnapshot,
        setLogin: Boolean = false,
        visitorData: String? = session.visitorData,
        includeAccountCookies: Boolean = true,
    ) {
        val endpoint = url
        val (apiBase, origin, referer) =
            when {
                endpoint == "player" && client.useMusicPlayerEndpoint -> {
                    Triple(API_BASE_MUSIC, ORIGIN_MUSIC, REFERER_MUSIC)
                }

                client.clientName == "WEB_REMIX" -> {
                    Triple(API_BASE_MUSIC, ORIGIN_MUSIC, REFERER_MUSIC)
                }

                client.clientName == "WEB_CREATOR" -> {
                    Triple(API_BASE_STUDIO, ORIGIN_STUDIO, REFERER_STUDIO)
                }

                client.clientName == "MWEB" -> {
                    Triple(API_BASE_MWEB, ORIGIN_MWEB, REFERER_MWEB)
                }

                else -> {
                    Triple(API_BASE_WWW, ORIGIN_WWW, REFERER_WWW)
                }
            }
        val requestReferer = if (client.isEmbedded) "https://www.reddit.com/" else referer

        header("Content-Type", "application/json")
        header("X-Goog-Api-Format-Version", "1")
        header("X-YouTube-Client-Name", client.clientId)
        header("X-YouTube-Client-Version", client.clientVersion)
        header("Origin", origin)
        header("X-Origin", origin)
        header("Referer", requestReferer)
        header(
            "Accept-Language",
            session.locale.acceptLanguageHeader(),
        )
        visitorData?.let { header("X-Goog-Visitor-Id", it) }
        if (client.loginSupported && includeAccountCookies) {
            val effectiveCookie = injectPrefCookie(session.cookie, session.locale.hl, session.locale.gl)
            header("Cookie", effectiveCookie)
        }
        if (setLogin && client.loginSupported && !session.cookie.isNullOrBlank()) {
            header("X-Goog-AuthUser", session.authUser)
            val sapisid = session.sapisid
            if (!sapisid.isNullOrBlank()) {
                sapisidAuthHeader(origin, sapisid)?.let { header("Authorization", it) }
            }
        }

        url = "$apiBase/$endpoint"
        parameter("prettyPrint", false)
        header("User-Agent", client.userAgent)
    }

    private suspend fun post(
        url: String,
        maxBytes: Int = MAX_API_RESPONSE_BYTES,
        keepErrorBody: Boolean = true,
        block: Request.() -> Unit,
    ): HttpResponse = Request(url).apply(block).send(maxBytes, keepErrorBody, ByteArray(0))

    private suspend fun get(
        url: String,
        maxBytes: Int = MAX_API_RESPONSE_BYTES,
        block: Request.() -> Unit,
    ): HttpResponse = Request(url).apply(block).send(maxBytes, true, null)

    /**
     * [keepErrorBody] = false skips downloading non-2xx bodies; use it only where callers never read them. A POST's
     * redirect is an error, not a GET elsewhere carrying the account's cookie.
     */
    private suspend fun Request.send(maxBytes: Int, keepErrorBody: Boolean, emptyBody: ByteArray?) =
        http(url, headers, body ?: emptyBody, maxBytes = maxBytes, errorBody = keepErrorBody, redirects = emptyBody == null)

    private suspend fun executeRequest(
        operation: String = "request",
        requestSession: SessionSnapshot? = null,
        retryTransientFailures: Boolean = true,
        requireSuccess: Boolean = true,
        maxAttempts: Int = 3,
        initialDelay: Long = 500L,
        factor: Double = 2.0,
        block: suspend () -> HttpResponse,
    ): HttpResponse {
        if (requestSession != null) {
            return withSessionBoundRequest(requestSession) {
                executeRequest(operation, null, retryTransientFailures, requireSuccess, maxAttempts, initialDelay, factor, block)
            }
        }
        var currentDelay = initialDelay
        var attempt = 0
        while (true) {
            try {
                currentCoroutineContext().ensureActive()
                val response = block()
                // A 429 is only retried when the server says how long to wait, and not for long.
                val retryAfterMs =
                    if (response.status == 429) {
                        response.header("Retry-After")
                            ?.toLongOrNull()
                            ?.times(1_000L)
                            ?.takeIf { it in 0..MAX_RETRY_AFTER_MS }
                    } else {
                        null
                    }
                val retryableStatus =
                    response.status in TRANSIENT_STATUS_CODES && (response.status != 429 || retryAfterMs != null)
                if (!retryTransientFailures || !retryableStatus || attempt + 1 >= maxAttempts) {
                    if (requireSuccess && response.status !in 200..299) {
                        throw InnerTubeHttpException(operation, response.status)
                    }
                    return response
                }
                attempt++
                retryDelay((retryAfterMs ?: currentDelay).milliseconds)
                currentDelay = (currentDelay * factor).toLong()
            } catch (e: CancellationException) {
                throw e
            } catch (e: InnerTubeHttpException) {
                throw e
            } catch (e: Exception) {
                attempt++
                if (!retryTransientFailures || (e !is IOException) || attempt >= maxAttempts) {
                    throw e
                }
                retryDelay(currentDelay.milliseconds)
                currentDelay = (currentDelay * factor).toLong()
            }
        }
    }

    private suspend fun executeMutation(
        operation: String,
        requestSession: SessionSnapshot = sessionSnapshot(),
        block: suspend (SessionSnapshot) -> HttpResponse,
    ): HttpResponse =
        withSessionBoundRequest(requestSession) {
            executeRequest(operation = operation, retryTransientFailures = false, requireSuccess = false) {
                block(requestSession)
            }
        }

    /** [setLogin] explicitly opts this call into account auth, independently of the default browse preference. */
    suspend fun browse(
        client: YouTubeClient,
        browseId: String? = null,
        params: String? = null,
        continuation: String? = null,
        setLogin: Boolean = false,
    ): HttpResponse {
        val requestSession = sessionSnapshot()
        val setLoginForRequest = setLogin || requestSession.useLoginForBrowse
        val visitorDataForRequest = requestSession.visitorData.takeUnless { requestSession.regionOverrideActive }
        return executeRequest(
            operation = "browse",
            requestSession = requestSession.takeIf { setLoginForRequest && client.loginSupported },
        ) {
            post("browse", keepErrorBody = false) {
                ytClient(
                    client,
                    requestSession,
                    setLogin = setLoginForRequest,
                    visitorData = visitorDataForRequest,
                    includeAccountCookies = setLoginForRequest,
                )
                setBody(
                    BrowseBody(
                        context =
                            client.toContext(
                                requestSession.locale,
                                visitorDataForRequest,
                                if (setLoginForRequest) requestSession.dataSyncId else null,
                            ),
                        browseId = browseId,
                        params = params,
                        continuation = continuation,
                    ),
                )
            }
        }
    }

    suspend fun player(
        client: YouTubeClient,
        videoId: String,
        playlistId: String? = null,
        signatureTimestamp: Int? = null,
        poToken: String? = null,
        requestVisitorData: String? = null,
        encryptedHostFlags: String? = null,
    ): HttpResponse =
        playerWithSessionBound(
            client,
            videoId,
            playlistId,
            signatureTimestamp,
            poToken,
            requestVisitorData,
            sessionSnapshot(),
            encryptedHostFlags,
        )

    private fun YouTubeClient.withLanguage(language: String): YouTubeClient =
        if ("{language}" in userAgent) copy(userAgent = userAgent.replace("{language}", language)) else this

    private fun buildPlayerBody(
        client: YouTubeClient,
        context: Context,
        videoId: String,
        signatureTimestamp: Int?,
        playlistId: String? = null,
        poToken: String? = null,
        encryptedHostFlags: String? = null,
        thirdParty: PlayerBody.ThirdParty? = null,
    ): PlayerBody =
        PlayerBody(
            context = context,
            videoId = videoId,
            playlistId = playlistId,
            playbackContext =
                if (client.useSignatureTimestamp || client.isEmbedded) {
                    PlayerBody.PlaybackContext(
                        PlayerBody.PlaybackContext.ContentPlaybackContext(
                            html5Preference = "HTML5_PREF_WANTS".takeUnless { client.useMusicPlayerEndpoint },
                            signatureTimestamp = signatureTimestamp.takeIf { client.useSignatureTimestamp },
                            encryptedHostFlags = encryptedHostFlags.takeIf { client.isEmbedded },
                        ),
                    )
                } else {
                    null
                },
            thirdParty = thirdParty,
            serviceIntegrityDimensions = poToken?.let { PlayerBody.ServiceIntegrityDimensions(it) },
            contentCheckOk = true,
            racyCheckOk = true,
            videoCheckOk = true.takeUnless { client.useMusicPlayerEndpoint },
        )

    internal suspend fun playerWithSession(
        client: YouTubeClient,
        videoId: String,
        playlistId: String?,
        signatureTimestamp: Int?,
        poToken: String?,
        requestVisitorData: String?,
        requestSession: SessionSnapshot,
        encryptedHostFlags: String? = null,
        retryTransientFailures: Boolean = true,
    ): HttpResponse =
        executeRequest(operation = "player:${client.clientName}", retryTransientFailures = retryTransientFailures) {
            post("player", keepErrorBody = false) {
                playerRequest(
                    client,
                    videoId,
                    playlistId,
                    signatureTimestamp,
                    poToken,
                    requestVisitorData,
                    requestSession,
                    encryptedHostFlags,
                )
            }
        }

    /**
     * Extraction-only player request: no transient retries, status checked before the body is read, and the bounded
     * body is decoded once instead of being saved into a replayable response first.
     */
    internal suspend fun playerTextWithSessionBound(
        client: YouTubeClient,
        videoId: String,
        signatureTimestamp: Int?,
        poToken: String?,
        requestSession: SessionSnapshot,
        encryptedHostFlags: String?,
        maxBytes: Int,
    ): String =
        withSessionBoundRequest(requestSession) {
            val masked = playerFieldMaskEnabled
            try {
                extractionPlayerText(client, videoId, signatureTimestamp, poToken, requestSession, encryptedHostFlags, maxBytes, masked)
            } catch (e: InnerTubeHttpException) {
                if (!masked || e.status != 400) throw e
                // A schema change could invalidate the mask; never let that break playback.
                extractionPlayerText(
                    client,
                    videoId,
                    signatureTimestamp,
                    poToken,
                    requestSession,
                    encryptedHostFlags,
                    maxBytes,
                    false,
                ).also {
                    playerFieldMaskEnabled = false
                }
            }
        }

    private suspend fun extractionPlayerText(
        client: YouTubeClient,
        videoId: String,
        signatureTimestamp: Int?,
        poToken: String?,
        requestSession: SessionSnapshot,
        encryptedHostFlags: String?,
        maxBytes: Int,
        fieldMask: Boolean,
    ): String =
        post("player", maxBytes, keepErrorBody = false) {
            playerRequest(client, videoId, null, signatureTimestamp, poToken, null, requestSession, encryptedHostFlags)
            // Asks the server for only what PlayerResponse decodes: about half the bytes to transfer and parse.
            if (fieldMask) header(FIELD_MASK_HEADER, PLAYER_RESPONSE_FIELD_MASK)
        }.let { response ->
            if (!response.ok) throw InnerTubeHttpException("player:${client.clientName}", response.status)
            response.text()
        }

    private fun Request.playerRequest(
        client: YouTubeClient,
        videoId: String,
        playlistId: String?,
        signatureTimestamp: Int?,
        poToken: String?,
        requestVisitorData: String?,
        requestSession: SessionSnapshot,
        encryptedHostFlags: String?,
    ) {
        val visitorDataForRequest = requestVisitorData ?: requestSession.visitorData
        val requestClient = client.withLanguage(requestSession.locale.hl)
        ytClient(requestClient, requestSession, setLogin = true, visitorData = visitorDataForRequest)
        setBody(
            buildPlayerBody(
                client = requestClient,
                context =
                    requestClient.toContext(
                        requestSession.locale,
                        visitorDataForRequest,
                        requestSession.dataSyncId.takeIf { client.loginSupported },
                    ),
                videoId = videoId,
                signatureTimestamp = signatureTimestamp,
                playlistId = playlistId,
                poToken = poToken,
                encryptedHostFlags = encryptedHostFlags,
                thirdParty = if (client.isEmbedded) PlayerBody.ThirdParty("https://www.youtube.com/embed/$videoId") else null,
            ),
        )
        header("User-Agent", requestClient.userAgent)
    }

    suspend fun mediaContentLength(
        url: String,
        requestHeaders: Map<String, String>,
    ): Long? =
        try {
            val headers = requestHeaders.filterKeys { it.lowercase() in MEDIA_REQUEST_HEADERS }
            http(validatedMediaUrl(url), headers, method = "HEAD", redirects = false)
                .let { r -> r.header("Content-Length")?.toLongOrNull()?.takeIf { r.ok && it > 0L } }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    suspend fun playerWithSessionBound(
        client: YouTubeClient,
        videoId: String,
        playlistId: String?,
        signatureTimestamp: Int?,
        poToken: String?,
        requestVisitorData: String?,
        requestSession: SessionSnapshot,
        encryptedHostFlags: String? = null,
    ): HttpResponse =
        playerWithSessionBound(
            client,
            videoId,
            playlistId,
            signatureTimestamp,
            poToken,
            requestVisitorData,
            requestSession,
            encryptedHostFlags,
            retryTransientFailures = true,
        )

    /** The extraction director falls back to another client instead of retrying the same one. */
    internal suspend fun playerWithSessionBound(
        client: YouTubeClient,
        videoId: String,
        playlistId: String?,
        signatureTimestamp: Int?,
        poToken: String?,
        requestVisitorData: String?,
        requestSession: SessionSnapshot,
        encryptedHostFlags: String?,
        retryTransientFailures: Boolean,
    ): HttpResponse =
        withSessionBoundRequest(requestSession) {
            playerWithSession(
                client,
                videoId,
                playlistId,
                signatureTimestamp,
                poToken,
                requestVisitorData,
                requestSession,
                encryptedHostFlags,
                retryTransientFailures,
            )
        }

    suspend fun next(
        client: YouTubeClient,
        videoId: String? = null,
        playlistId: String? = null,
        playlistSetVideoId: String? = null,
        index: Int? = null,
        params: String? = null,
        continuation: String? = null,
    ): HttpResponse {
        val requestSession = sessionSnapshot()
        val setLoginForRequest = requestSession.useLoginForBrowse
        return executeRequest(operation = "next", requestSession = requestSession.takeIf { setLoginForRequest && client.loginSupported }) {
            post("next", keepErrorBody = false) {
                ytClient(
                    client,
                    requestSession,
                    setLogin = setLoginForRequest,
                    includeAccountCookies = setLoginForRequest,
                )
                setBody(
                    NextBody(
                        context =
                            client.toContext(
                                requestSession.locale,
                                requestSession.visitorData,
                                requestSession.dataSyncId.takeIf { setLoginForRequest },
                            ),
                        videoId = videoId,
                        playlistId = playlistId,
                        playlistSetVideoId = playlistSetVideoId,
                        index = index,
                        params = params,
                        continuation = continuation,
                        contentCheckOk = true,
                        racyCheckOk = true,
                    ),
                )
            }
        }
    }

    private fun validatedMediaUrl(value: String): String {
        requireNotNull(httpsUrl(value, { it == "googlevideo.com" || it.endsWith(".googlevideo.com") }, { it == "/videoplayback" })) {
            "Media URL must use an approved Google Video HTTPS endpoint"
        }
        return value
    }

    suspend fun search(
        client: YouTubeClient,
        query: String? = null,
        params: String? = null,
        continuation: String? = null,
        setLogin: Boolean? = null,
    ): HttpResponse {
        val requestSession = sessionSnapshot()
        val setLoginForRequest = setLogin ?: true
        val visitorDataForRequest = requestSession.visitorData.takeUnless { requestSession.regionOverrideActive }
        return executeRequest(
            operation = "search",
            requestSession = requestSession.takeIf { setLoginForRequest && client.loginSupported },
        ) {
            post("search", keepErrorBody = false) {
                ytClient(
                    client,
                    requestSession,
                    setLogin = setLoginForRequest,
                    visitorData = visitorDataForRequest,
                    includeAccountCookies = setLoginForRequest,
                )
                setBody(
                    SearchBody(
                        context =
                            client.toContext(
                                requestSession.locale,
                                visitorDataForRequest,
                                if (setLoginForRequest) requestSession.dataSyncId else null,
                            ),
                        query = query,
                        params = params,
                    ),
                )
                if (continuation != null) {
                    parameter("continuation", continuation)
                    parameter("ctoken", continuation)
                }
            }
        }
    }

    suspend fun getSearchSuggestions(
        client: YouTubeClient,
        input: String,
        setLogin: Boolean = false,
    ): HttpResponse {
        val requestSession = sessionSnapshot()
        return executeRequest(
            operation = "getSearchSuggestions",
            requestSession = requestSession.takeIf { setLogin && client.loginSupported },
        ) {
            post("music/get_search_suggestions", keepErrorBody = false) {
                ytClient(client, requestSession, setLogin = setLogin, includeAccountCookies = setLogin)
                setBody(
                    GetSearchSuggestionsBody(
                        context =
                            client.toContext(
                                requestSession.locale,
                                requestSession.visitorData,
                                if (setLogin) requestSession.dataSyncId else null,
                            ),
                        input = input,
                    ),
                )
            }
        }
    }

    suspend fun likeVideo(
        client: YouTubeClient,
        videoId: String,
    ) = likeVideo(client, videoId, sessionSnapshot())

    suspend fun likeVideo(
        client: YouTubeClient,
        videoId: String,
        requestSession: SessionSnapshot,
    ) = executeMutation("likeVideo", requestSession) { requestSession ->
        post("like/like") {
            ytClient(client, requestSession, setLogin = true)
            setBody(
                LikeBody(
                    client.toContext(requestSession.locale, requestSession.visitorData, requestSession.dataSyncId),
                    LikeBody.Target.video(videoId),
                ),
            )
        }
    }

    suspend fun unlikeVideo(
        client: YouTubeClient,
        videoId: String,
    ) = unlikeVideo(client, videoId, sessionSnapshot())

    suspend fun unlikeVideo(
        client: YouTubeClient,
        videoId: String,
        requestSession: SessionSnapshot,
    ) = executeMutation("unlikeVideo", requestSession) { requestSession ->
        post("like/removelike") {
            ytClient(client, requestSession, setLogin = true)
            setBody(
                LikeBody(
                    client.toContext(requestSession.locale, requestSession.visitorData, requestSession.dataSyncId),
                    LikeBody.Target.video(videoId),
                ),
            )
        }
    }

    /** Saves a playlist or album (its audio playlist) to the library, or removes it. */
    suspend fun likePlaylist(client: YouTubeClient, playlistId: String, like: Boolean) =
        executeMutation("likePlaylist", sessionSnapshot()) { requestSession ->
            post(if (like) "like/like" else "like/removelike") {
                ytClient(client, requestSession, setLogin = true)
                setBody(LikeBody(client.toContext(requestSession.locale, requestSession.visitorData, requestSession.dataSyncId), LikeBody.Target.playlist(playlistId)))
            }
        }

    /** Subscribes to an artist's channel, or unsubscribes. */
    suspend fun subscribe(client: YouTubeClient, channelId: String, subscribe: Boolean) =
        executeMutation("subscribe", sessionSnapshot()) { requestSession ->
            post(if (subscribe) "subscription/subscribe" else "subscription/unsubscribe") {
                ytClient(client, requestSession, setLogin = true)
                setBody(SubscribeBody(client.toContext(requestSession.locale, requestSession.visitorData, requestSession.dataSyncId), listOf(channelId)))
            }
        }

    suspend fun fetchFreshVisitorData(): String? = fetchFreshVisitorData(sessionSnapshot(), forceRefresh = true)

    suspend fun fetchFreshVisitorData(
        requestSession: SessionSnapshot,
        forceRefresh: Boolean = false,
    ): String? {
        val deferred =
            visitorDataFetchMutex.withLock {
                visitorDataScope.coroutineContext.ensureActive()
                val currentSession = session.load()
                if (!forceRefresh || requestSession != currentSession) {
                    // Compatibility (not equality) so a caller holding the pre-publication snapshot still hits the
                    // visitor a concurrent flight just published, which bumped the generation.
                    currentSession.visitorData
                        ?.takeIf { visitorData -> visitorData.isNotBlank() && sessionIsCompatible(requestSession, currentSession) }
                        ?.let { visitorData ->
                            return@withLock CompletableDeferred<String?>().also { it.complete(visitorData) }
                        }
                }

                val sessionIdentity = requestSession.copy(visitorData = null)
                // A just-failed bootstrap is not repeated for every tokenized client; an explicit refresh bypasses this.
                visitorDataFailure
                    ?.takeIf { !forceRefresh && it.first == sessionIdentity && it.second.elapsedNow() < VISITOR_FAILURE_COOLDOWN }
                    ?.let { return@withLock CompletableDeferred<String?>().also { it.complete(null) } }
                visitorDataFlight
                    ?.takeIf { it.sessionIdentity == sessionIdentity && !it.result.isCompleted }
                    ?.result
                    ?: CompletableDeferred<String?>().also { result ->
                        visitorDataFlight = VisitorDataFlight(sessionIdentity, result)
                        visitorDataScope
                            .launch {
                                val outcome =
                                    runCatching { withTimeout(VISITOR_FETCH_TIMEOUT_MS) { fetchFreshVisitorDataNetwork(requestSession) } }
                                // Retire the flight and publish its cooldown before waking waiters, so a resumed caller
                                // sees the cooldown instead of starting another bootstrap. Only the owning flight may
                                // touch the cooldown; an obsolete session's flight must not overwrite the current one.
                                withContext(NonCancellable) {
                                    visitorDataFetchMutex.withLock {
                                        if (visitorDataFlight?.result === result) {
                                            visitorDataFlight = null
                                            outcome.onSuccess { visitorData ->
                                                visitorDataFailure =
                                                    sessionIdentity
                                                        .takeIf { visitorData.isNullOrBlank() }
                                                        ?.let { it to TimeSource.Monotonic.markNow() }
                                            }
                                        }
                                    }
                                }
                                outcome.fold(result::complete, result::completeExceptionally)
                            }.invokeOnCompletion { cause ->
                                if (cause != null) result.completeExceptionally(cause)
                            }
                    }
            }
        return deferred.await()
    }

    private suspend fun fetchFreshVisitorDataNetwork(requestSession: SessionSnapshot): String? =
        try {
            // The service worker gets its own deadline so a stall still leaves time for the homepage fallback.
            val serviceWorkerData =
                withTimeoutOrNull(SERVICE_WORKER_VISITOR_TIMEOUT_MS) {
                    try {
                        val response =
                            executeRequest(operation = "fetchFreshVisitorData", retryTransientFailures = false, requireSuccess = false) {
                                get(VISITOR_DATA_URL) {
                                    header("User-Agent", YouTubeClient.USER_AGENT_WEB)
                                    header("Accept", "application/json,text/plain,*/*")
                                    header(
                                        "Accept-Language",
                                        requestSession.locale.acceptLanguageHeader(),
                                    )
                                }
                            }
                        if (response.status in 200..299) {
                            parseServiceWorkerVisitorData(response.text())
                        } else {
                            null
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        null
                    }
                }
            val data =
                serviceWorkerData
                    ?: fetchHomepageVisitorData(requestSession)
            if (!data.isNullOrBlank()) {
                publishSession(requestSession, requestSession.copy(visitorData = data))
            }
            data
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    private suspend fun fetchHomepageVisitorData(requestSession: SessionSnapshot): String? {
        val response =
            executeRequest(operation = "fetchFreshVisitorData", requireSuccess = false, maxAttempts = 2) {
                get(ORIGIN_MUSIC) {
                    header("User-Agent", YouTubeClient.USER_AGENT_WEB)
                    header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    header(
                        "Accept-Language",
                        requestSession.locale.acceptLanguageHeader(),
                    )
                }
            }
        if (response.status !in 200..299) {
            return null
        }
        return VISITOR_DATA_REGEX
            .find(response.text())
            ?.groupValues
            ?.get(1)
    }

    private fun parseServiceWorkerVisitorData(responseText: String): String? =
        runCatching {
            Json
                .parseToJsonElement(responseText.substringAfter('\n').trimStart())
                .jsonArray[0]
                .jsonArray[2]
                .jsonArray[0]
                .jsonArray[0]
                .jsonArray[13]
                .jsonPrimitive
                .contentOrNull
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
}

class InnerTubeHttpException(
    val operation: String,
    val status: Int,
) : IllegalStateException("$operation failed with HTTP $status")

/** A request as it's built: the URL (an endpoint name until [InnerTube] resolves it), headers and JSON body. */
private class Request(var url: String) {
    val headers = linkedMapOf("Accept" to "application/json", "Cache-Control" to "no-cache")
    var body: ByteArray? = null

    fun header(name: String, value: String) {
        headers[name] = value
    }

    fun parameter(name: String, value: Any) {
        url += (if ('?' in url) "&" else "?") + name + "=" + URLEncoder.encode(value.toString(), "UTF-8")
    }

    inline fun <reified T> setBody(value: T) {
        body = innerTubeJson.encodeToString(value).encodeToByteArray()
    }
}

private fun injectPrefCookie(
    cookie: String?,
    hl: String,
    gl: String,
): String {
    if (cookie.isNullOrBlank()) return "PREF=hl=$hl&gl=$gl"
    val parts = cookie.split(";").map { it.trim() }.filter { it.isNotEmpty() }
    val prefParts = mutableListOf<String>()
    val otherParts = mutableListOf<String>()
    var foundPref = false
    for (part in parts) {
        if (part.startsWith("PREF=", ignoreCase = true)) {
            foundPref = true
            val prefValue = part.substringAfter("=")
            prefValue.split("&").filterTo(prefParts) { !it.startsWith("hl=") && !it.startsWith("gl=") }
        } else {
            otherParts.add(part)
        }
    }
    prefParts.add(0, "hl=$hl")
    prefParts.add(1, "gl=$gl")
    val newPref = "PREF=${prefParts.joinToString("&")}"
    return (otherParts + newPref).joinToString("; ")
}

private const val PLAYER_FORMAT_FIELDS =
    "itag,url,mimeType,bitrate,averageBitrate,width,height,contentLength,quality,audioQuality," +
        "approxDurationMs,audioSampleRate,audioChannels,loudnessDb,lastModified,xtags,audioTrack,isDrc,signatureCipher"

/** Every field [PlayerResponse] decodes, and nothing else. Keep in sync with that model. */
internal const val PLAYER_RESPONSE_FIELD_MASK =
    "playabilityStatus(status,reason,desktopLegacyAgeGateReason)," +
        "streamingData(formats($PLAYER_FORMAT_FIELDS),adaptiveFormats($PLAYER_FORMAT_FIELDS)," +
        "expiresInSeconds,hlsManifestUrl,dashManifestUrl,serverAbrStreamingUrl)," +
        "videoDetails(videoId,title,author,channelId,lengthSeconds,musicVideoType,viewCount,thumbnail,isLiveContent)," +
        "playbackTracking(videostatsPlaybackUrl,videostatsWatchtimeUrl,atrUrl," +
        "videostatsScheduledFlushWalltimeSeconds,videostatsDefaultFlushIntervalSeconds)," +
        "playerConfig(audioConfig(loudnessDb,perceptualLoudnessDb)," +
        "mediaCommonConfig(mediaUstreamerRequestConfig(videoPlaybackUstreamerConfig)))"
