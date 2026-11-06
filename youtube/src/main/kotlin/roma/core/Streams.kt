package roma.core

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.response.PlayerResponse
import com.metrolist.innertubex.models.YouTubeClient.Companion.VISIONOS
import kotlinx.coroutines.runBlocking
import java.io.File

/** A playable audio URL for one video. */
class AudioSource(val url: String, val mimeType: String, val bitrate: Int)

object Streams {
    /**
     * For testing only, and off in releases. When set, lookups act as if YouTube refused the VISIONOS client, as it
     * does for some songs on some networks: the direct path is skipped and InnerTubeX doesn't ask that client either.
     */
    @Volatile var refuseVisionOs = false

    /** The path that last found a stream for each song, tried first next time. InnerTubeX ones are kept, see [keepIn]. */
    private val worked = java.util.concurrent.ConcurrentHashMap<String, Path>()

    private enum class Path { DIRECT, INNERTUBEX }

    /**
     * Songs every path refused just now, with the reason and when. The precache and the player both look a song up;
     * within [REFUSED_MS] the second one gets the same answer without asking YouTube again.
     */
    private val refused = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
    private const val REFUSED_MS = 30_000L
    const val NO_CONNECTION = "No connection to YouTube."

    /**
     * Finds a playable audio URL. Blocking, so call it off the main thread.
     *
     * The direct path goes first: one VISIONOS player request whose audio URLs need no signature or n-parameter work,
     * so it is fast. Then InnerTubeX's extractor ([InnerTubeXStreams]) asks other clients with their own context. The
     * path that worked for this song goes first next time. [full] means the last URL was refused, and skips the
     * direct path.
     */
    fun audio(videoId: String, full: Boolean = false): AudioSource {
        val refusal = refused[videoId]
        if (refusal != null) {
            val (why, at) = refusal
            if (System.currentTimeMillis() - at < REFUSED_MS) throw StreamException(why)
        }
        val order = listOfNotNull(worked[videoId]) + Path.entries
        val failures = mutableListOf<Exception>()
        for (path in order.distinct()) {
            if (path == Path.DIRECT && (full || refuseVisionOs)) continue
            try {
                val a = when (path) {
                    Path.DIRECT -> direct(videoId)
                    Path.INNERTUBEX -> {
                        val excluded = if (refuseVisionOs) setOf("VISIONOS") else emptySet()
                        InnerTubeXStreams.audio(videoId, excluded)
                    }
                }
                val before = worked.put(videoId, path)
                if (before != path && (path == Path.INNERTUBEX || before == Path.INNERTUBEX)) {
                    val what = if (path == Path.INNERTUBEX) "X ${System.currentTimeMillis()}" else "N"
                    note(videoId, what)
                }
                return a
            } catch (e: Exception) {
                failures += e
            }
        }
        worked.remove(videoId)
        val why = reason(failures)
        // A network failure isn't remembered, so Retry asks YouTube again.
        if (why != NO_CONNECTION) refused[videoId] = why to System.currentTimeMillis()
        throw StreamException(why, offline = why == NO_CONNECTION)
    }

    // YouTube.player sends YouTube.visitorData with the request. Without it, YouTube answers "Sign in to confirm
    // you're not a bot".
    private fun direct(videoId: String): AudioSource = runBlocking {
        val r = YouTube.player(videoId, client = VISIONOS).getOrThrow()
        if (r.playabilityStatus.status != "OK") throw StreamException(r.playabilityStatus.reason ?: r.playabilityStatus.status)
        val loudness = r.playerConfig?.audioConfig?.loudnessDb
        if (loudness != null) putLoudness(videoId, loudness.toFloat())
        best(videoId, r.streamingData?.adaptiveFormats.orEmpty())
    }

    // Prefers the original track over dubbed ones (an original track's id ends in ".4"), then the highest bitrate.
    internal fun best(videoId: String, formats: List<PlayerResponse.StreamingData.Format>): AudioSource {
        val audio = formats.filter { it.mimeType.startsWith("audio/") && it.url != null }
        val sorted = audio.sortedWith(
            compareBy(
                { format -> format.audioTrack?.id?.endsWith(".4") == false },
                { format -> -(format.averageBitrate ?: format.bitrate) },
            ),
        )
        val best = sorted.firstOrNull() ?: error("No audio stream for $videoId")
        return AudioSource(best.url!!, best.mimeType.substringBefore(';'), best.averageBitrate ?: best.bitrate)
    }

    /**
     * The reason to show when every path failed. The last failure that says why (YouTube's playability text, an age
     * check) wins over generic ones ("no playable stream"), and a network failure is put in plain words.
     */
    internal fun reason(failures: List<Exception>): String {
        fun plain(e: Throwable): String? = when {
            e is java.net.UnknownHostException || e is java.net.SocketTimeoutException || e is java.net.ConnectException -> NO_CONNECTION
            e is StreamResolveException && e.reason == StreamResolveException.Reason.NO_PLAYABLE_STREAM -> null
            e is StreamResolveException && e.reason == StreamResolveException.Reason.NETWORK -> {
                val cause = e.cause
                val causeReason = if (cause == null) null else plain(cause)
                causeReason ?: NO_CONNECTION
            }
            e is IllegalStateException && e.message?.startsWith("No audio stream") == true -> null
            else -> {
                val message = e.message?.trim()
                if (message.isNullOrEmpty()) null else message
            }
        }
        return failures.asReversed().firstNotNullOfOrNull(::plain) ?: "YouTube gave no playable stream for this song."
    }

    /**
     * YouTube's loudness of the track against its reference level, in dB; positive means louder. Read from the player
     * response of the last [audio] lookup of this video, so it costs no request of its own; null if it had none.
     */
    fun loudness(videoId: String): Float? = loudnessDb[videoId]

    internal fun putLoudness(videoId: String, db: Float) {
        if (loudnessDb.put(videoId, db) != db) note(videoId, "L $db")
    }

    private var file: File? = null

    /**
     * Keeps two facts per song across starts, one line per change in [f]: its loudness, and that only InnerTubeX found
     * its stream. A song played to the end replays from the cache without a lookup, so without the first it would play
     * at full loudness after a restart. Without the second, the next start would first wait for the direct path that
     * YouTube refused. An InnerTubeX mark lasts [FALLBACK_DAYS] days, so a song YouTube serves again gets the fast path
     * back. Blocking: it reads the file (at most about 150 KB) and compacts it.
     */
    fun keepIn(f: File) {
        val loud = LinkedHashMap<String, Float>()
        val fallback = HashMap<String, Long>()
        val lines = runCatching { f.readLines() }.getOrDefault(emptyList())
        for (line in lines) {
            val p = line.split(' ')
            when (p.getOrNull(1)) {
                "L" -> {
                    val db = p.getOrNull(2)?.toFloatOrNull()
                    if (db != null) {
                        // Removing first moves the song to the end, so the most recently changed ones survive the cap.
                        loud.remove(p[0])
                        loud[p[0]] = db
                    }
                }
                "X" -> {
                    val at = p.getOrNull(2)?.toLongOrNull()
                    if (at != null) fallback[p[0]] = at
                }
                "N" -> fallback.remove(p[0])
            }
        }
        val since = System.currentTimeMillis() - FALLBACK_DAYS * DAY_MS
        fallback.values.removeAll { it < since }
        val keep = loud.entries.toList().takeLast(MAX_LOUDNESS_SONGS)
        for ((id, db) in keep) loudnessDb.putIfAbsent(id, db)
        for ((id, at) in fallback) {
            worked.putIfAbsent(id, Path.INNERTUBEX)
            fallbackAt[id] = at
        }
        if (lines.size > keep.size + fallback.size + COMPACT_AFTER_EXTRA_LINES) {
            val loudLines = keep.joinToString("") { (id, db) -> "$id L $db\n" }
            val fallbackLines = fallback.entries.joinToString("") { (id, at) -> "$id X $at\n" }
            val tmp = File(f.path + ".tmp")
            tmp.writeText(loudLines + fallbackLines)
            tmp.renameTo(f)
        }
        file = f
    }

    private val fallbackAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val FALLBACK_DAYS = 7
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val MAX_LOUDNESS_SONGS = 5000
    private const val COMPACT_AFTER_EXTRA_LINES = 500

    /** A song that only InnerTubeX could play in the last [FALLBACK_DAYS] days, the latest; null if none. */
    fun recentFallback(): String? = fallbackAt.entries.maxByOrNull { it.value }?.key

    private fun note(videoId: String, what: String) {
        if (what.startsWith("X")) fallbackAt[videoId] = System.currentTimeMillis()
        if (what == "N") fallbackAt.remove(videoId)
        val f = file ?: return
        synchronized(this) {
            runCatching { f.appendText("$videoId $what\n") }
        }
    }
}

private val loudnessDb = java.util.concurrent.ConcurrentHashMap<String, Float>()

/**
 * The fallback: [Fallback], InnerTubeX's extractor cut to what roma asks of it. It rolls over its own client list
 * (VISIONOS 0.1 with the session's visitor data, the web clients, the embedded player) and solves signatures with
 * QuackJS. Account cookies ([com.metrolist.innertube.YouTube.cookie]) go with the clients that accept them, and when
 * signed in it retries with an authenticated watch page. Clients that need a PoToken (the music web client WEB_REMIX,
 * the only one that plays age-restricted songs for an account without Premium) are used only with a [tokenProvider],
 * because without a token their streams stop after about 1 MB.
 */
object InnerTubeXStreams {
    /** Hands PoTokens to the clients that need one (a WebView BotGuard generator in the app); none by default. */
    @Volatile var tokenProvider: TokenProvider? = null
        set(value) {
            field = value
            built = null
        }

    @Volatile private var built: Fallback? = null

    @Synchronized
    private fun fallback(): Fallback {
        val existing = built
        if (existing != null) return existing
        val created = Fallback(com.metrolist.innertube.YouTube.extractionTransport(), tokenProvider)
        built = created
        return created
    }

    /** Blocking. Throws [StreamResolveException] with a plain reason ("This track is age-restricted.") on refusal. */
    fun audio(videoId: String, excludedClients: Set<String> = emptySet()): AudioSource = runBlocking {
        val s = fallback().extract(videoId, excludedClients, playbackNonce())
        // Logs which client worked, for logcat. The profile name carries no tokens.
        println("roma streams: $videoId via InnerTubeX ${s.profile}")
        val loudness = s.loudnessDb
        if (loudness != null) Streams.putLoudness(videoId, loudness.toFloat())
        AudioSource(s.url, s.mimeType.ifEmpty { "audio/*" }, s.bitrate)
    }
}
