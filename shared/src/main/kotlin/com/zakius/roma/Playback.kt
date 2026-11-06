package com.zakius.roma

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import roma.core.GoogleTranslate
import roma.core.LastFm
import roma.core.Lyrics
import roma.core.LyricsProviders
import roma.core.LyricsQuery
import roma.core.LyricsResult
import roma.core.Translation
import roma.core.Scrobble
import roma.core.ScrobbleQueue
import roma.core.Song
import roma.core.StreamException
import roma.core.again
import roma.core.filterMix
import roma.core.filterRadio
import roma.core.romanizeLyrics
import roma.core.shouldScrobble
import kotlinx.serialization.Serializable
import java.io.File
import kotlin.math.pow
import kotlin.math.roundToInt

/** A queue entry. Section is "queued" (pinned: radio never moves or removes it) or "radio". */
class Entry(val song: Song, val section: String)

/**
 * Lyrics for the current song: null while loading, [Lyrics] with no lines when no provider had any. The translation
 * layers are attached at display time, so changing the translation language needs no new search.
 */
class LyricsState(val songId: String, val lyrics: Lyrics?, val translations: List<Translation> = emptyList())

/** The one player. The service wraps it in a media session; the UI calls it directly (same process, main thread). */
object Playback {
    lateinit var player: AudioPlayer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Bumped on every player change; the UI reads it to redraw. */
    var tick by mutableIntStateOf(0)
        private set
    /** Bumped twice a second while playing. Only the seek bar reads it, so the clock doesn't redraw the whole player. */
    var clock by mutableIntStateOf(0)
        private set
    var lyrics by mutableStateOf<LyricsState?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** The "Banned · Undo" bar: the banned song and where it was, until the bar times out. */
    var lastBan by mutableStateOf<Pair<Song, Int>?>(null)
        private set
    var liked by mutableStateOf(setOf<String>())
        private set

    private val urls = HashMap<String, Pair<String, Long>>() // videoId to (url, resolved at)
    private var lastRetry: Pair<String, Long>? = null
    private val fullLookup: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** [p] is the platform's player. The app sets it up together with the stream tokens' provider. */
    fun init(p: AudioPlayer) {
        player = p
        Startup.later {
            scope.launch {
                liked = withContext(Dispatchers.IO) {
                    val saved = runCatching { Store.json.decodeFromString<List<String>>(likedFile.readText()).toSet() }
                    saved.getOrDefault(emptySet())
                }
                likeChanged()
                // The restored song's like is checked once Home's first request has had the connection to itself.
                delay(5_000)
                checkLike(current()?.song)
            }
        }
        // The saved queue and the current song's lyrics are read now, in parallel with the first frame, and restore()
        // hands them to the player after it. Reading them only then would put them on screen two disk reads late.
        val read = java.util.concurrent.FutureTask { readSaved() }
        restoreRead = read
        kotlin.concurrent.thread(name = "restore") { read.run() }
        Startup.later { restore() }
        // Watching the network makes two system-service registrations, binder calls into a system_server that is busy
        // with the launch. Nothing before the first frame plays or needs them, and [online] starts true.
        Startup.later {
            watchNetwork { up ->
                // Radio that couldn't load while offline loads now, and lyrics a failed search left out.
                if (up && !online && player.isPlaying) topUpRadio()
                if (up && !online) {
                    val song = shown()
                    if (song != null && song.id == lyricsUnfinished) postMain { loadLyrics(song) }
                }
                online = up
            }
        }
        Startup.later { backend.start() }
        player.listener = object : AudioPlayer.Listener {
            override fun onEvents(listChanged: Boolean, transition: Boolean, repeatChanged: Boolean, playingChanged: Boolean, discontinuity: Boolean) {
                tick++
                playerChanged?.invoke()
                if (listChanged || transition || repeatChanged) saveQueue()
                if (playingChanged || discontinuity) savePosition()
                if (player.isPlaying) startSideWork()
                // Radio songs arrive after the song started, and queue edits can change what comes next.
                if (player.isPlaying && sideWorkFor == null && listChanged) {
                    prefetchNext()
                    downloadAhead()
                }
            }

            override fun onMediaItemTransition() {
                error = null
                // After an error the player is idle, and a seek alone (Next, a queue tap) doesn't start it again.
                if (player.playerError != null) player.prepare()
                val e = current() ?: return
                if (Store.radioSettings().skipBanned && Store.bans().banned(e.song)) {
                    postMain { if (player.hasNextMediaItem()) player.seekToNextMediaItem() }
                    return
                }
                applyLoudness(e.song.id)
                if (restoringNow) {
                    // Back from a restart and paused, the cached lyrics show at once. Nothing is announced to Last.fm,
                    // and the radio top-up and next-song prefetch wait until play is pressed.
                    resetPlayCount()
                    sideWorkFor = e.song.id
                    loadLyrics(e.song, network = false)
                    return
                }
                startedTrack()
                // Cached lyrics show at once; the providers wait for the audio.
                loadLyrics(e.song, network = false)
                sideWorkFor = e.song.id
                // Everything else waits until the song is audible (startSideWork), so the stream has the network to
                // itself. If the song never starts (an error, or paused right away), it runs after 4 s anyway.
                val id = e.song.id
                postMain(4_000) { if (sideWorkFor == id) startSideWork() }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (reason == AudioPlayer.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) sleepOff()
            }

            override fun onPlayerError(e: Throwable, httpStatus: Int?, message: String) {
                val id = current()?.song?.id
                // The URL may have expired, so a retry resolves it again.
                synchronized(urls) { urls.remove(id) }
                // A refused stream URL (403, seen once after a seek) gets one silent retry through the full lookup,
                // from the same position. A second failure within a minute shows the error.
                val refused = httpStatus in setOf(403, 410)
                val now = System.currentTimeMillis()
                val last = lastRetry
                val retryAllowed = last == null || last.first != id || now - last.second > 60_000
                if (refused && id != null && retryAllowed) {
                    lastRetry = id to now
                    fullLookup.add(id)
                    val at = player.currentPosition
                    player.prepare()
                    player.seekTo(at)
                    player.play()
                    return
                }
                // A failed lookup carries its reason in plain words; Media3 may wrap it in its own exceptions.
                val causes = generateSequence<Throwable>(e) { t -> t.cause }
                val streamError = causes.firstNotNullOfOrNull { t -> t as? StreamException }
                error = streamError?.message ?: e.cause?.message ?: message
            }
        }
        scope.launch {
            while (true) {
                delay(500)
                if (player.isPlaying) {
                    clock++
                    countPlayed()
                    // Every 10 s, so a killed app loses at most that much.
                    if (clock % 20 == 0) savePosition()
                } else if (sleepAt != 0L) {
                    // The seek bar's "Sleep in" count keeps going while paused.
                    clock++
                }
                checkSleep()
            }
        }
    }

    // The next song's stream URL and first bytes are fetched while this one plays, so Next (from the app or the
    // notification) and the automatic transition start it from the cache. Media3's own preloading isn't used because
    // it keeps the next song's download open, and the cache can't serve or store those bytes meanwhile.
    // The next song's lyrics and large art are fetched too, so they are there the moment it starts. This is done for
    // the one next song only, once per song, and both are kept (lyrics cache, Coil's disk cache) for when it plays.
    // The work is wasted only if the song is skipped.
    private fun prefetchNext() {
        prefetchQueueCovers()
        val i = player.nextMediaItemIndex
        if (i < 0) return
        val next = player.entryAt(i)?.song ?: return
        if (next.id == prefetched) return
        prefetched = next.id
        next.thumb?.let { Images.prefetch(bigThumb(it), BIG_PX) }
        scope.launch {
            precacheAsync(next.id).await()
            withContext(Dispatchers.IO) {
                runCatching {
                    val alreadyRomanized = romanizedLyrics(next) != null || romanizedOnDisk(next) != null
                    if (!alreadyRomanized) romanize(next, lyricsResult(next))
                }
            }
        }
    }

    private var prefetched: String? = null

    /**
     * The queue rows' covers for the next [QUEUE_COVERS] songs, decoded, so the queue opens with them drawn (about
     * 80 KB of memory each). A cover already in memory costs nothing; see [PrefetchCovers] for when it runs.
     */
    private fun prefetchQueueCovers() {
        if (thrifty()) return
        // 48 dp is SongRow's cover size.
        val px = (48 * screenDensity()).roundToInt()
        val from = player.currentMediaItemIndex + 1
        val end = minOf(from + QUEUE_COVERS, player.mediaItemCount)
        val urls = (from until end).mapNotNull { i ->
            val thumb = player.entryAt(i)?.song?.thumb
            if (thumb == null) null else thumbAt(thumb, px)
        }
        scope.launch { for (u in urls) Images.load(u, px) }
    }

    /** Blocking. The song Play would resume, unless it is on disk already; null when there's none. */
    fun resumeId(): String? {
        val id = runCatching { positionFile.readText().substringBefore(' ') }.getOrNull() ?: return null
        if (player.fullyCached(id)) return null
        return id
    }

    /** Blocking, on the player's loader thread. URLs expire after about 6 hours; reuse them for 5. */
    fun streamUrl(id: String): String {
        synchronized(urls) {
            val cached = urls[id]
            if (cached != null) {
                val (url, at) = cached
                if (System.currentTimeMillis() - at < 5 * 60 * 60 * 1000) return url
            }
        }
        val url = backend.streamUrl(id, full = fullLookup.remove(id))
        synchronized(urls) { urls[id] = url to System.currentTimeMillis() }
        postMain { applyLoudness(id) }
        return url
    }

    // Normalization only turns loud tracks down, because the player's volume can't go above 1.
    private fun applyLoudness(id: String) {
        if (current()?.song?.id != id) return
        val db = backend.loudness(id)
        baseVolume = if (db == null || db <= 0f || !Store.bool("play.normalize", true)) 1f else 10f.pow(-db / 20f)
        player.volume = baseVolume
    }

    private var baseVolume = 1f

    fun current(): Entry? = player.currentEntry

    fun songAt(i: Int): Song? = if (i in 0 until player.mediaItemCount) player.entryAt(i)?.song else null

    fun entries(): List<Entry> = (0 until player.mediaItemCount).mapNotNull { player.entryAt(it) }

    /** Plays [songs] from [start]; the rest of the list is queued and radio from the started song follows. */
    fun play(songs: List<Song>, start: Int = 0) = play(songs, start, null, null)

    /**
     * The song being prepared after a tap: shown in the player at once, while whatever plays keeps playing until the
     * new song's stream URL is known and its first 32 KB are in the cache. Then the switch starts it without a gap.
     */
    var pending by mutableStateOf<Song?>(null)
        private set
    private var pendingJob: Job? = null
    private var pendingIndex = -1

    /** A mix picked whose songs are still loading: its name and cover, shown instead of the song it replaces. */
    var opening by mutableStateOf<Pair<String, String?>?>(null)
        private set

    // While a pick is prepared, what still plays drops to 30%, so the ear hears a switch coming, without a gap.
    private fun duck(on: Boolean) {
        player.volume = if (on) baseVolume * 0.3f else baseVolume
    }

    /** What the player shows: the song being prepared, else the one playing. */
    fun shown(): Song? = pending ?: current()?.song

    /** The queue position of [shown]: a skip's target while it is prepared, -1 while a new list is. */
    fun shownIndex(): Int {
        if (opening != null) return -1
        if (pending == null) return player.currentMediaItemIndex
        return pendingIndex
    }

    private fun play(songs: List<Song>, start: Int, mix: String?, mixNext: String?) {
        if (songs.isEmpty()) return
        Store.remember(songs)
        val song = songs[start]
        pendingJob?.cancel()
        opening = null
        pending = song
        pendingIndex = -1
        duck(true)
        loadLyrics(song, network = false)
        pendingJob = scope.launch {
            // On failure the player's own error handling takes over.
            withTimeoutOrNull(8_000) { precacheAsync(song.id).await() }
            pending = null
            duck(false)
            switchTo(songs, start, mix, mixNext)
        }
    }

    /** Next, Previous and queue taps: the current song keeps playing until the target's first bytes are cached. */
    fun skipTo(index: Int) {
        val song = player.entryAt(index)?.song ?: return
        pendingJob?.cancel()
        opening = null
        pending = song
        pendingIndex = index
        duck(true)
        loadLyrics(song, network = false)
        pendingJob = scope.launch {
            withTimeoutOrNull(8_000) { precacheAsync(song.id).await() }
            pending = null
            duck(false)
            if (index < player.mediaItemCount) player.seekTo(index, 0)
        }
    }

    /** Steps from the shown song, so taps while a skip is still prepared go further instead of re-picking its target. */
    fun next() {
        val target = neighbour(shownIndex(), 1)
        if (target >= 0) skipTo(target)
    }

    /** Like the system's: past 3 s, back to the start of this song; earlier, the song before. */
    fun previous() {
        if (pending == null && (player.currentPosition > 3_000 || !player.hasPreviousMediaItem())) {
            player.seekTo(0)
        } else {
            val target = neighbour(shownIndex(), -1)
            if (target >= 0) skipTo(target)
        }
    }

    /** The queue position [dir] steps from [i], wrapping only with Repeat all; -1 when there is none. */
    fun neighbour(i: Int, dir: Int): Int {
        val n = player.mediaItemCount
        if (i < 0 || i >= n) return -1
        val j = i + dir
        return when {
            j in 0 until n -> j
            player.repeatMode == AudioPlayer.REPEAT_MODE_ALL -> if (dir > 0) 0 else n - 1
            else -> -1
        }
    }

    // The song whose lyrics, radio top-up and next-song prefetch still wait for playback to start.
    private var sideWorkFor: String? = null

    private fun startSideWork() {
        val e = current() ?: return
        if (sideWorkFor != e.song.id) return
        sideWorkFor = null
        loadLyrics(e.song)
        checkLike(e.song)
        topUpRadio()
        prefetchNext()
        downloadAhead()
    }

    private var aheadJob: Job? = null

    /**
     * Opt-in ("Download songs ahead"): the next 3 songs go into the audio cache in full, one after another, after the
     * next song's first bytes. With the current song buffered in full by the player's load control, a network drop
     * costs nothing for the next quarter of an hour or so. Songs already cached are skipped.
     */
    fun downloadAhead() {
        if (!Store.bool("play.ahead", false) || aheadJob?.isActive == true) return
        val ids = entries().drop(player.currentMediaItemIndex + 1).take(3).map { it.song.id }
        if (ids.isEmpty()) return
        aheadJob = scope.launch {
            precacheAsync(ids[0]).await()
            withContext(Dispatchers.IO) {
                for (id in ids) {
                    if (player.fullyCached(id)) continue
                    runCatching { player.precache(id, -1) }
                }
            }
        }
    }

    // Resolves the stream URL and caches the first 32 KB: the container header plus about 1.5 s of audio at YouTube's
    // bitrates, more than the 0.5 s the player needs to start. Waiting for more only delays the switch on slow networks.
    private fun precache(id: String) = player.precache(id, 32 * 1024L)

    // Running precaches by video ID, touched on the main thread only. A tap joins the lookup its touch-down started,
    // and Next joins the next-song prefetch, instead of resolving and downloading the same song twice at once.
    private val precaching = HashMap<String, Deferred<Unit>>()
    private var speculative: Deferred<Unit>? = null

    private fun precacheAsync(id: String): Deferred<Unit> {
        precaching.values.removeAll { !it.isActive }
        return precaching.getOrPut(id) {
            scope.async(Dispatchers.IO) {
                runCatching { precache(id) }
                Unit
            }
        }
    }

    /**
     * A finger landed on a song in a list: start its lookup now, before the tap is recognized; the tap joins it.
     * One at a time, so a scroll that starts on a song row costs one lookup (about 12 KB) and a fling costs no more.
     */
    fun touched(s: Song) {
        if (speculative?.isActive != true) speculative = precacheAsync(s.id)
    }

    private var guessJob: Job? = null
    private val guessTimes = ArrayDeque<Long>()

    /**
     * Prepares the first [GUESS_SONGS] of [songs], one after another: an opened page's first song, Search's top
     * result, the queue's next songs. A new call replaces what is left of the last one. Each costs about 45 KB
     * (nothing for a song already cached), at most [GUESSES_PER_HOUR] an hour (under 3 MB). Never while the current
     * song is still loading, and only on an unmetered network outside Battery Saver unless the user allows it.
     */
    fun prepare(songs: List<Song>) {
        guessJob?.cancel()
        guessJob = scope.launch {
            for (s in songs.take(GUESS_SONGS)) {
                val now = System.currentTimeMillis()
                while (guessTimes.isNotEmpty() && now - guessTimes.first() > 60 * 60 * 1000) {
                    guessTimes.removeFirst()
                }
                if (guessTimes.size >= GUESSES_PER_HOUR ||
                    pending != null ||
                    player.playbackState == AudioPlayer.STATE_BUFFERING ||
                    thrifty()
                ) {
                    return@launch
                }
                guessTimes.addLast(now)
                precacheAsync(s.id).await()
            }
        }
    }

    /** Mobile data or Battery Saver, and the user hasn't allowed guesses there. */
    fun thrifty(): Boolean {
        if (Store.bool("prepare.always", false)) return false
        return meteredOrSaving()
    }

    private const val GUESS_SONGS = 3
    private const val QUEUE_COVERS = 30
    private const val GUESSES_PER_HOUR = 60

    private fun switchTo(songs: List<Song>, start: Int, mix: String?, mixNext: String?) {
        mixId = mix
        mixContinuation = mixNext
        // Otherwise the old queue's top-up would add its songs to this one.
        radioJob?.cancel()
        radioSeed = songs[start]
        radioContinuation = null
        related = null
        shuffleOrder = null
        player.setMediaItems(songs.map { player.item(Entry(it, "queued")) }, start, 0)
        player.prepare()
        player.play()
    }

    fun playNext(s: Song) = insertQueued(s, player.currentMediaItemIndex + 1)

    fun addToQueue(s: Song) {
        val list = entries()
        var i = player.currentMediaItemIndex + 1
        while (i < list.size && list[i].section == "queued") i++
        insertQueued(s, i)
    }

    private fun insertQueued(s: Song, at: Int) {
        Store.remember(listOf(s))
        if (player.mediaItemCount == 0) return play(listOf(s))
        // A queued song leaves the radio section: radio entries aren't pinned, queued ones are.
        val list = entries()
        var insertAt = at.coerceAtMost(player.mediaItemCount)
        for (i in list.indices.reversed()) {
            if (i > player.currentMediaItemIndex && list[i].section == "radio" && list[i].song.id == s.id) {
                player.removeMediaItem(i)
                if (i < insertAt) insertAt--
            }
        }
        player.addMediaItem(insertAt, player.item(Entry(s, "queued")))
    }

    fun remove(index: Int) {
        if (index != player.currentMediaItemIndex) player.removeMediaItem(index)
    }

    /** Keeps the queued songs and replaces the radio with one seeded from [s]. */
    fun startRadio(s: Song) {
        if (current()?.song?.id != s.id) return play(listOf(s))
        mixId = null
        radioJob?.cancel()
        radioSeed = s
        radioContinuation = null
        related = null
        val list = entries()
        for (i in list.indices.reversed()) {
            if (i > player.currentMediaItemIndex && list[i].section == "radio") player.removeMediaItem(i)
        }
        topUpRadio(force = true)
    }

    private var radioSeed: Song? = null
    private var radioContinuation: String? = null
    private var related: Set<String>? = null
    private var radioJob: Job? = null

    private fun topUpRadio(force: Boolean = false) {
        val left = player.mediaItemCount - player.currentMediaItemIndex - 1
        if (left >= 3 || radioJob?.isActive == true) return
        if (mixId != null) return topUpMix()
        if (Store.stopAtEnd && !force) return
        val seed = radioSeed ?: return
        radioJob = scope.launch {
            val settings = Store.radioSettings()
            // Read on the main thread, because ExoPlayer is single-threaded.
            val already = entries().map { it.song }
            var rel = related
            var continuation = radioContinuation
            val added = withContext(Dispatchers.IO) {
                val knownRelated = rel
                val r: Set<String>
                if (knownRelated != null) {
                    r = knownRelated
                } else {
                    r = if (settings.leash == "free") emptySet() else relatedArtists(seed)
                    rel = r
                }
                var fresh = emptyList<Song>()
                // A page can be all filtered out, so a few continuations are tried before giving up.
                repeat(4) {
                    if (fresh.isNotEmpty()) return@repeat
                    // A continuation saved before a restart may have expired. Then the radio's pages start over, and
                    // the songs already queued are filtered out.
                    val page = backend.radio(seed, continuation)
                    if (page == null) {
                        continuation = null
                        return@repeat
                    }
                    continuation = page.next
                    fresh = filterRadio(page.songs, seed, r, already, Store.bans(), settings)
                    if (continuation == null) return@repeat
                }
                fresh
            }
            // Another radio or queue started meanwhile.
            if (radioSeed !== seed) return@launch
            related = rel
            radioContinuation = continuation
            Store.remember(added)
            player.addMediaItems(added.map { player.item(Entry(it, "radio")) })
        }
    }

    private var mixId: String? = null
    private var mixContinuation: String? = null

    /**
     * Plays a personal mix or another generated playlist (RD…) as a playlist: its songs go into "You queued", not radio,
     * and its next page loads when the queue runs low. The radio filters apply; the leash doesn't.
     */
    fun playMix(id: String, loaded: List<Song>? = null, title: String = "", thumb: String? = null) {
        // Opened from its page, the songs are there already (every distinct one, see loadDetail), so it starts at once.
        if (loaded != null) {
            val songs = filterMix(loaded, emptyList(), Store.bans(), Store.radioSettings())
            return play(songs, 0, id.removePrefix("VL"), null)
        }
        // Loaded as a pendingJob, so a song picked meanwhile wins over the mix arriving later.
        pendingJob?.cancel()
        pending = null
        opening = title to thumb
        duck(true)
        pendingJob = scope.launch {
            val source = backend
            val page = withContext(Dispatchers.IO) { source.playlist(id) }
            // Liked music plays as liked. The mix filters (versions, bans) are for what YouTube picks.
            val songs = withContext(Dispatchers.IO) {
                val all = page?.songs.orEmpty()
                if (id == source.likedId) all else filterMix(all, emptyList(), Store.bans(), Store.radioSettings())
            }
            if (page == null || songs.isEmpty()) {
                opening = null
                duck(false)
                error = "Couldn't load this playlist."
                return@launch
            }
            play(songs, 0, id.removePrefix("VL"), page.next)
        }
    }

    private fun topUpMix() {
        val id = mixId ?: return
        radioJob = scope.launch {
            val already = entries().map { it.song }
            val added = withContext(Dispatchers.IO) {
                var fresh = emptyList<Song>()
                // Generated playlists' continuations loop back to songs already queued, and filtering can empty a
                // page; a few pages with nothing new end the mix.
                repeat(3) {
                    val c = mixContinuation
                    if (fresh.isNotEmpty() || c == null) return@repeat
                    val page = backend.playlist(id, c) ?: return@repeat
                    mixContinuation = page.next
                    fresh = filterMix(page.songs, already, Store.bans(), Store.radioSettings())
                }
                fresh
            }
            // Another queue started meanwhile.
            if (mixId != id) return@launch
            if (added.isEmpty()) {
                // The mix is done (or the network failed): radio from here on, unless the queue should stop.
                mixId = null
                postMain { topUpRadio() }
                return@launch
            }
            Store.remember(added)
            player.addMediaItems(added.map { player.item(Entry(it, "queued")) })
        }
    }

    private suspend fun relatedArtists(seed: Song) = backend.relatedArtists(seed)

    /** When the sleep timer pauses playback, in [elapsedRealtime] time; 0 when it's off. */
    var sleepAt by mutableLongStateOf(0L)
        private set
    /** The sleep timer pauses when the playing song ends. */
    var sleepAfterSong by mutableStateOf(false)
        private set

    fun sleepIn(minutes: Int) {
        sleepOff()
        sleepAt = elapsedRealtime() + minutes * 60_000L
    }

    fun sleepAtSongEnd() {
        sleepOff()
        sleepAfterSong = true
        player.pauseAtEndOfMediaItems = true
    }

    fun sleepOff() {
        // The volume may be partway through the fade-out.
        if (sleepAt != 0L) player.volume = baseVolume
        sleepAt = 0L
        sleepAfterSong = false
        player.pauseAtEndOfMediaItems = false
    }

    // Runs on the half-second clock, so the timer costs no wakeups of its own. The last 10 s fade out instead of
    // cutting a phrase off. A timer that runs out while paused just turns off.
    private fun checkSleep() {
        if (sleepAt == 0L) return
        val left = sleepAt - elapsedRealtime()
        if (left > 10_000) return
        if (left > 0 && player.isPlaying) {
            player.volume = baseVolume * left / 10_000f
            return
        }
        if (left > 0) return
        player.pause()
        sleepOff()
    }

    /**
     * What queue.json holds, so the queue and the current song survive a restart. The position is kept apart in the
     * position file, because it changes every few seconds.
     */
    @Serializable
    private class Saved(
        val songs: List<Song>,
        val sections: List<String>,
        val index: Int,
        val repeat: Int,
        val shuffleOrder: List<String>?,
        val radioSeed: Song?,
        val radioContinuation: String?,
        val mixId: String?,
        val mixContinuation: String?,
    )

    /** True until the saved queue is back (or found missing). The player shows nothing rather than "Nothing playing". */
    var restoring by mutableStateOf(true)
        private set
    private var restoringNow = false
    private val restoreWaiters = ArrayList<() -> Unit>()

    private val queueFile get() = File(Store.files, "queue.json")
    private val positionFile get() = File(Store.files, "position")
    // One writer thread runs the saves in order, so a later save never lands before an earlier one.
    private val saver = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var saveQueued = false

    // A burst of queue changes (radio adding 20 songs) is written once, after it.
    private fun saveQueue() {
        if (restoring || saveQueued) return
        saveQueued = true
        postMain {
            saveQueued = false
            val list = entries()
            val s = Saved(
                songs = list.map { it.song },
                sections = list.map { it.section },
                index = player.currentMediaItemIndex,
                repeat = player.repeatMode,
                shuffleOrder = shuffleOrder,
                radioSeed = radioSeed,
                radioContinuation = radioContinuation,
                mixId = mixId,
                mixContinuation = mixContinuation,
            )
            saver.execute { writeAtomically(queueFile, Store.json.encodeToString(s)) }
        }
    }

    private fun savePosition() {
        if (restoring) return
        val id = current()?.song?.id ?: return
        val line = "$id ${player.currentPosition}"
        saver.execute { writeAtomically(positionFile, line) }
    }

    // A kill mid-write leaves the old file whole instead of half a new one.
    private fun writeAtomically(f: File, text: String) {
        val tmp = File(f.path + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(f)
    }

    private lateinit var restoreRead: java.util.concurrent.FutureTask<Restored?>

    // Everything but handing the items to the player happens off the main thread, because building a few hundred
    // items reads settings per song. The romanized lyrics land in their memory cache, so the restored song shows them
    // at once.
    private fun readSaved(): Restored? {
        val saved = runCatching { Store.json.decodeFromString<Saved>(queueFile.readText()) }.getOrNull() ?: return null
        if (saved.songs.isEmpty()) return null
        val position = runCatching {
            val parts = positionFile.readText().split(" ")
            parts[0] to parts[1].toLong()
        }.getOrNull()
        val index = saved.index.coerceIn(0, saved.songs.size - 1)
        val song = saved.songs[index]
        romanizedOnDisk(song)
        // When the player is the screen a start shows, its art is decoded into the memory cache now, so the first
        // frame has it. Requested only during the first frame, it would arrive 2 to 3 frames late.
        val thumb = song.thumb
        if (Store.prefs.getInt("ui.tab", TAB_HOME) == TAB_PLAYER && thumb != null) {
            Images.prefetch(bigThumb(thumb), BIG_PX)
        }
        val at = if (position?.first == song.id) position.second else 0L
        val items = saved.songs.mapIndexed { i, s ->
            val section = saved.sections.getOrElse(i) { "queued" }
            player.item(Entry(s, section))
        }
        return Restored(saved, index, at, items)
    }

    private fun restore() {
        scope.launch {
            // The cache check waits until the audio cache has scanned its files, so it stays off the main thread too.
            val (r, onDisk) = withContext(Dispatchers.IO) {
                val r = restoreRead.get()
                r to (r != null && player.fullyCached(r.saved.songs[r.index].id))
            }
            // A song picked while the file was read wins.
            if (r != null && player.mediaItemCount == 0 && pending == null) {
                val saved = r.saved
                radioSeed = saved.radioSeed
                radioContinuation = saved.radioContinuation
                mixId = saved.mixId
                mixContinuation = saved.mixContinuation
                shuffleOrder = saved.shuffleOrder
                restoringNow = true
                player.setMediaItems(r.items, r.index, r.at)
                player.repeatMode = saved.repeat
                restoringNow = false
                // A song fully on disk is prepared now; any other is prepared by Play, which reads what's cached
                // before asking the network, so opening the app to browse costs no data.
                if (onDisk) player.prepare()
            }
            restoring = false
            if (player.mediaItemCount == 0 && pending == null && Nav.tab == TAB_PLAYER) Nav.tab = TAB_HOME
            restoreWaiters.forEach { it() }
            restoreWaiters.clear()
        }
    }

    private class Restored(val saved: Saved, val index: Int, val at: Long, val items: List<QueueItem>)

    /** Runs [f] once the saved queue is back: at once if it already is. */
    fun afterRestore(f: () -> Unit) {
        if (restoring) restoreWaiters.add(f) else f()
    }

    // Pause works while buffering for the network too. Play prepares an idle player and restarts an ended one.
    fun togglePlay() = player.togglePlay()

    /** Told of every change the player reports: the platform's media session (Android's PlaybackService). */
    var playerChanged: (() -> Unit)? = null

    /** A validated network is up. Changes come from the system's callback, never from polling. */
    var online by mutableStateOf(true)
        private set

    /**
     * Asked to play, but stuck until the network is back: the player shows a quiet note instead of an error. That is
     * while the stream buffers, and also while a song not on the phone waits for its stream to be looked up.
     */
    val waitingForNetwork get() = !online && !player.isPlaying && (player.playWhenReady || pending != null)

    fun cycleRepeat() {
        player.repeatMode = when (player.repeatMode) {
            AudioPlayer.REPEAT_MODE_OFF -> AudioPlayer.REPEAT_MODE_ALL
            AudioPlayer.REPEAT_MODE_ALL -> AudioPlayer.REPEAT_MODE_ONE
            else -> AudioPlayer.REPEAT_MODE_OFF
        }
    }

    // Shuffle reorders only the queued songs after the current one, so radio stays after them; off restores the order.
    private var shuffleOrder: List<String>? = null
    val shuffled get() = shuffleOrder != null

    fun toggleShuffle() {
        val cur = player.currentMediaItemIndex
        val list = entries()
        var end = cur + 1
        while (end < list.size && list[end].section == "queued") end++
        val queued = list.subList(cur + 1, end)
        val order = shuffleOrder
        val newOrder = if (order == null) {
            shuffleOrder = queued.map { it.song.id }
            queued.shuffled()
        } else {
            shuffleOrder = null
            queued.sortedBy { e ->
                val i = order.indexOf(e.song.id)
                if (i < 0) Int.MAX_VALUE else i
            }
        }
        if (queued.isEmpty()) {
            tick++
            return
        }
        player.replaceMediaItems(cur + 1, end, newOrder.map { player.item(it) })
        tick++
    }

    /** Never: ban the current song and skip it. The bar offers Undo for 5 seconds. */
    fun never() {
        val e = current() ?: return
        val index = player.currentMediaItemIndex
        Store.ban("song", e.song.id, e.song.title.latin)
        lastBan = e.song to index
        if (player.hasNextMediaItem()) player.seekToNextMediaItem() else player.pause()
        scope.launch {
            delay(5000)
            if (lastBan?.first?.id == e.song.id) lastBan = null
        }
    }

    fun undoBan() {
        val (song, index) = lastBan ?: return
        lastBan = null
        Store.unban("song", song.id)
        if (index < player.mediaItemCount && entries()[index].song.id == song.id) player.seekTo(index, 0)
    }

    fun canLike(s: Song?) = s != null && backend.canLike

    fun toggleLike(s: Song? = current()?.song) {
        val song = s ?: return
        val source = backend
        if (!source.canLike) return
        val like = song.id !in liked
        liked = if (like) liked + song.id else liked - song.id
        toggled += song.id
        scope.launch(Dispatchers.IO) {
            val ok = source.like(song.id, like)
            withContext(Dispatchers.Main) {
                if (ok) {
                    saveLiked()
                } else {
                    liked = if (like) liked - song.id else liked + song.id
                    likeChanged()
                }
            }
        }
        likeChanged()
    }

    /** Callable from any thread: pages call it while they load on Dispatchers.IO, and the player is main-thread only. */
    fun markLiked(ids: Collection<String>) {
        postMain {
            liked = liked + ids
            saveLiked()
            likeChanged()
        }
    }

    // Each song is checked once per run. A song liked or unliked here meanwhile is left alone, so a late answer can't
    // undo the user's choice.
    private val likeChecked = HashSet<String>()
    private val toggled = HashSet<String>()

    /**
     * Asks YouTube whether the user likes [s] (one small request), so likes made elsewhere show up: when a song plays,
     * when its menu opens. The liked set kept on disk shows meanwhile, and offline. Main thread.
     */
    fun checkLike(s: Song?) {
        val id = s?.id ?: return
        val source = backend
        if (!source.canLike || id in toggled || !likeChecked.add(id)) return
        scope.launch {
            val like = withContext(Dispatchers.IO) { source.liked(id) }
            if (like == null) {
                likeChecked -= id
                return@launch
            }
            if (id in toggled || like == (id in liked)) return@launch
            liked = if (like) liked + id else liked - id
            saveLiked()
            likeChanged()
        }
    }

    private val likedFile get() = java.io.File(Store.cache, "liked.json")

    // What the Like controls show until YouTube answers for a song, on the next start too.
    private fun saveLiked() {
        val ids = liked.toList()
        saver.execute { writeAtomically(likedFile, Store.json.encodeToString(ids)) }
    }

    private var romanizeJob: Job? = null

    /**
     * After an override or a scheme change: rebuild every name in the queue, lyrics included. The names are romanized
     * off the main thread (a 1,000-song queue takes a while) and swapped in with one queue change, not one per song.
     */
    fun romanizationChanged() {
        Store.bumpRomanizationVersion()
        Store.Names.forget()
        forgetRomanizedLyrics()
        romanizeJob?.cancel()
        romanizeJob = scope.launch {
            val before = entries()
            val again = withContext(Dispatchers.Default) {
                before.map { e -> Entry(Store.romanizer.again(e.song), e.section) }
            }
            // Radio may have added songs meanwhile; any other change to the queue means starting over.
            val now = entries()
            val changed = now.size < before.size || before.indices.any { i -> now[i] !== before[i] }
            if (changed) return@launch romanizationChanged()
            player.replaceMediaItems(0, before.size, again.map { player.item(it) })
            tick++
        }
        current()?.let { loadLyrics(it.song) }
    }

    private var lyricsJob: Job? = null

    /** Blocking: the cached lyrics, else every provider asked and the answer cached. */
    private fun lyricsResult(s: Song): LyricsResult = lyricsResult(s, network = true)!!

    /** The song's lyrics from the disk cache, or (with [network]) from the providers. Null when not cached and [network] is false. */
    private fun lyricsResult(s: Song, network: Boolean): LyricsResult? {
        val file = rawFile(s.id)
        // A cache file from before 0.1.6 holds a bare Lyrics, fails to decode and is searched again.
        val cached = runCatching { Store.json.decodeFromString<LyricsResult>(file.readText()) }.getOrNull()
        if (cached != null) return cached
        if (!network) return null
        val q = LyricsQuery(s.id, s.title.original, s.artistOriginal, s.album?.name?.original, s.durationSec)
        // The source's own lyrics are the reference. When Settings lists them, as it does YouTube Music's, their switch
        // there turns them on or off; otherwise they are always asked.
        val own = backend.lyrics
        val enabled = Store.lyricsOrder().filter { Store.lyricsEnabled(it) }
        val providers = if (own in backend.lyricsProviders) enabled else enabled + listOfNotNull(own)
        val search = LyricsProviders.find(q, providers, Store.icu, reference = own)
        val r = search.result ?: LyricsResult(Lyrics("", emptyList(), false))
        // "No lyrics" is kept only when every provider answered; after a failure the next play asks again.
        if (search.result != null || search.complete) {
            file.parentFile!!.mkdirs()
            file.writeText(Store.json.encodeToString(r))
        }
        return r
    }

    /**
     * Shows the song's lyrics. Already romanized lyrics come from memory or disk at once; cached raw lyrics are
     * romanized and kept; only a song with nothing cached asks the providers, and only with [network] (callers pass
     * false until the song is audible, so the stream has the network to itself).
     */
    fun loadLyrics(s: Song, network: Boolean = true) {
        val kept = romanizedLyrics(s)
        if (kept != null) {
            lyricsJob?.cancel()
            lyrics = kept
            return
        }
        lyricsJob?.cancel()
        if (lyrics?.songId != s.id) lyrics = LyricsState(s.id, null)
        lyricsJob = scope.launch {
            val found = withContext(Dispatchers.IO) {
                val onDisk = romanizedOnDisk(s)
                if (onDisk != null) return@withContext onDisk
                val raw = lyricsResult(s, network) ?: return@withContext null
                // A search that failed (no network, a provider down) isn't kept, so the song is searched again: when
                // the network is back, or on its next play.
                if (!rawFile(s.id).exists()) {
                    lyricsUnfinished = s.id
                    return@withContext LyricsState(s.id, raw.lyrics, raw.translations)
                }
                romanize(s, raw)
            }
            if (found != null) lyrics = found
        }
    }

    /** The song whose lyrics search failed, to search again once the network is back. */
    @Volatile private var lyricsUnfinished: String? = null

    // Romanized lyrics are kept so that opening lyrics again, or after a restart, skips Sudachi: in memory for the
    // last 40 songs, and on disk next to the raw copy. The key carries the romanization version, which changes with
    // the settings and corrections, so stale romanizations are never shown (their files are deleted then).
    private val romanizedCache = object : LinkedHashMap<String, LyricsState>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LyricsState>) = size > 40
    }
    private fun romanizedKey(id: String) = "$id${backend.lyricsVariant}@${Store.romanizationVersion}"
    // The app's version too: an update may romanize differently, and its lyrics shouldn't show the old version's.
    private fun romanizedFile(id: String) = File(Store.cache, "lyrics/$id${backend.lyricsVariant}.r${Store.romanizationVersion}.${BuildConfig.VERSION_CODE}.json")
    private fun rawFile(id: String) = File(Store.cache, "lyrics/$id${backend.lyricsVariant}.json")

    private fun romanizedLyrics(s: Song): LyricsState? = synchronized(romanizedCache) { romanizedCache[romanizedKey(s.id)] }

    private fun romanizedOnDisk(s: Song): LyricsState? {
        val r = runCatching { Store.json.decodeFromString<LyricsResult>(romanizedFile(s.id).readText()) }.getOrNull() ?: return null
        val state = LyricsState(s.id, r.lyrics, r.translations)
        synchronized(romanizedCache) { romanizedCache[romanizedKey(s.id)] = state }
        return state
    }

    private fun romanize(s: Song, raw: LyricsResult): LyricsState {
        val siblings = listOf(s.artistOriginal) + listOfNotNull(s.album?.name?.original)
        val done = Store.romanizer.romanizeLyrics(raw.lyrics, siblings, s.id)
        runCatching {
            val result = LyricsResult(done, raw.translations, raw.rule)
            romanizedFile(s.id).writeText(Store.json.encodeToString(result))
        }
        val state = LyricsState(s.id, done, raw.translations)
        synchronized(romanizedCache) { romanizedCache[romanizedKey(s.id)] = state }
        return state
    }

    /** Called after a romanization change, when the kept romanizations no longer apply. */
    fun forgetRomanizedLyrics() {
        synchronized(romanizedCache) { romanizedCache.clear() }
        scope.launch(Dispatchers.IO) {
            val romanizedFiles = File(Store.cache, "lyrics").listFiles { f -> f.name.contains(".r") }
            romanizedFiles?.forEach { it.delete() }
        }
    }

    /** Drops the cached lyrics and asks the providers again, e.g. after changing their order. */
    fun refetchLyrics() {
        val s = current()?.song ?: return
        rawFile(s.id).delete()
        romanizedFile(s.id).delete()
        synchronized(romanizedCache) { romanizedCache.remove(romanizedKey(s.id)) }
        lyrics = null
        loadLyrics(s)
    }

    /**
     * Asks Google for the song's lyrics in the first translation language and keeps the layer with its lyrics, on
     * disk too, so the song shows it from then on. [done] gets what to tell the user, or null when it worked.
     */
    fun googleTranslate(id: String, done: (String?) -> Unit) {
        val state = lyrics
        if (state == null || state.songId != id) return done(null)
        val l = state.lyrics ?: return done(null)
        val lang = Store.translationLanguages().first()
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { GoogleTranslate.layer(l, lang) } }
            result.onSuccess { t ->
                if (t == null) {
                    done("The lyrics are already in ${java.util.Locale.forLanguageTag(lang).displayLanguage}")
                } else {
                    addTranslation(id, t)
                    done(null)
                }
            }
            result.onFailure { done("Couldn't translate. Check Wi-Fi or mobile data, then try again.") }
        }
    }

    /** Adds a translation layer wherever the song's lyrics are kept: the view, memory, the raw and romanized files. */
    private fun addTranslation(id: String, layer: Translation) {
        val state = lyrics
        if (state == null || state.songId != id) return
        val t = state.translations + layer
        val updated = LyricsState(id, state.lyrics, t)
        synchronized(romanizedCache) { romanizedCache[romanizedKey(id)] = updated }
        lyrics = updated
        scope.launch(Dispatchers.IO) {
            for (f in listOf(rawFile(id), romanizedFile(id))) {
                runCatching {
                    val r = Store.json.decodeFromString<LyricsResult>(f.readText())
                    f.writeText(Store.json.encodeToString(LyricsResult(r.lyrics, t, r.rule)))
                }
            }
        }
    }

    private var playedHalfSeconds = 0
    private var scrobbled = false
    private var startedAt = 0L

    private fun lastFm(): Pair<LastFm, String>? {
        val key = Store.str("lastfm.key", "")
        val secret = Store.str("lastfm.secret", "")
        val sk = Store.str("lastfm.session", "")
        if (key.isBlank() || secret.isBlank() || sk.isBlank()) return null
        return LastFm(key, secret) to sk
    }

    private fun scrobbleOf(s: Song): Scrobble {
        val artist = s.artists.firstOrNull()?.name?.original ?: ""
        return Scrobble(artist, s.title.original, s.album?.name?.original, startedAt, s.durationSec, s.id)
    }

    private fun resetPlayCount() {
        playedHalfSeconds = 0
        scrobbled = false
        startedAt = System.currentTimeMillis() / 1000
    }

    private fun startedTrack() {
        resetPlayCount()
        val s = current()?.song ?: return
        // Each play is reported once, by the side that knows its source (docs/subsonic.md).
        val source = backend
        source.nowPlaying(s)
        if (!source.lastFm) return
        val (fm, sk) = lastFm() ?: return
        val now = scrobbleOf(s)
        scope.launch(Dispatchers.IO) { runCatching { fm.nowPlaying(sk, now) } }
    }

    private fun countPlayed() {
        playedHalfSeconds++
        val s = current()?.song ?: return
        val duration = if (s.durationSec > 0) s.durationSec else (player.duration / 1000).toInt()
        if (scrobbled || !shouldScrobble(duration, playedHalfSeconds / 2)) return
        scrobbled = true
        Store.played(s)
        val source = backend
        source.played(scrobbleOf(s))
        if (!source.lastFm || Store.str("lastfm.session", "").isBlank()) return
        scrobbleQueue.add(scrobbleOf(s))
        flushScrobbles()
    }

    // One instance: its lock is what keeps an add and a flush from losing or repeating scrobbles.
    val scrobbleQueue by lazy { ScrobbleQueue(File(Store.files, "scrobbles.json")) }

    fun flushScrobbles() {
        val (fm, sk) = lastFm() ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                scrobbleQueue.flush { batch ->
                    fm.scrobble(sk, batch)
                    batch.size
                }
            }
        }
    }
}

/** Lists get small thumbnails (60–120 px); the player and the media notification need a large one. */
fun bigThumb(url: String) = backend.bigThumb(url)

/** The player's art: bigThumb's size. */
const val BIG_PX = 544

