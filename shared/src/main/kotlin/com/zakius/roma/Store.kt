package com.zakius.roma

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import roma.core.Bans
import roma.core.Icu
import roma.core.Item
import roma.core.LyricsProvider
import roma.core.Override
import roma.core.RadioSettings
import roma.core.RomanizeSettings
import roma.core.Romanizer
import roma.core.Song
import roma.core.again
import roma.core.matchDistance
import java.io.File

/** Work the first frame doesn't need waits until it is drawn, so it doesn't compete with it for the CPU or the main thread. */
object Startup {
    private var started = false
    private val later = ArrayList<() -> Unit>()

    /** Runs [f] once roma's first frame is drawn (or now, if it was). Main thread only. */
    fun later(f: () -> Unit) {
        if (started) f() else later += f
    }

    /** The first frame is drawn, or the media service started without a screen: the deferred work runs now. */
    fun go() {
        if (started) return
        started = true
        later.forEach { it() }
        later.clear()
    }
}

@Serializable
class Banned(val kind: String, val id: String, val name: String)

/**
 * Settings and everything the app keeps on disk. Compose reads [version] to redraw after a change, and [dataVersion]
 * to load pages again: only signing in or out and romanization changes alter what a page holds. A ban or a display
 * setting must not send every open page back to the network.
 */
object Store {
    lateinit var prefs: Prefs
    /** Data the user would lose: settings, sign-ins, corrections, the queue, unsent scrobbles. */
    lateinit var files: File
    /** Everything that can be fetched or rebuilt again, so Android may clear it: dictionary, index, likes, pages, streams. */
    lateinit var cache: File
    private lateinit var pages: File
    val json = Json { ignoreUnknownKeys = true }
    var version by mutableIntStateOf(0)
        private set
    var dataVersion by mutableIntStateOf(0)
        private set

    val icu: Icu = platformIcu

    // The dictionary is memory-mapped in place, in the app's own storage where Dictionary keeps it, so it stays out
    // of the heap. While it is missing, or while the user reads Japanese as written (nothing would show the romaji
    // then), Japanese gets kana only and the dictionary isn't loaded.
    val romanizer = Romanizer(icu) {
        try {
            check("ja" !in readScripts) { "Japanese is read as written" }
            val dictionary = Dictionary.map()
            // Names romanized without the dictionary, in this run or an earlier one, are redone.
            if (prefs.getBoolean("dict.failed", false)) {
                prefs.edit().remove("dict.failed").apply()
                postMain {
                    Playback.romanizationChanged()
                    dataChanged()
                }
            }
            dictionary
        } catch (e: Exception) {
            prefs.edit().putBoolean("dict.failed", true).apply()
            throw e
        }
    }

    fun init(prefs: Prefs, files: File, cache: File) {
        this.prefs = prefs
        this.files = files
        this.cache = cache
        pages = File(cache, "pages")
        // Cleans up after older versions, off the main thread and before the index and the liked songs are read from
        // the cache folder. Up to 0.1.26 these files sat in filesDir and the mixes in prefs. They are copied, not
        // renamed, because a renamed file keeps its group and Android counts a file as cache by its group. The
        // dictionary unpacked by 0.1.27 and earlier is deleted.
        indexThread.execute {
            for (dir in listOf(files, cache)) {
                for (f in dir.listFiles().orEmpty()) {
                    if (f.name.startsWith("sudachi")) f.delete()
                }
            }
            for (n in listOf("index.json", "liked.json", "streams.txt")) {
                val old = File(files, n)
                if (old.exists()) {
                    runCatching { old.copyTo(File(cache, n), true) }
                    old.delete()
                }
            }
            File(cache, "http_cache").deleteRecursively() // OkHttp's cache, up to 0.1.39
            File(cache, "ktor_cache").deleteRecursively() // Ktor's cache, up to 0.1.50
            prefs.edit().remove("home.mixes").apply()
        }
        romanizer.settings = romanizeSettings()
        romanizer.loadOverrides(indexThread.submit<List<Override>> { loadOverrides() })
        romanizer.cache = Names
        // Loaded after the first frame: new names arrive with network results well after it, and start-up already
        // has enough disk reads.
        Startup.later { Names.load(File(cache, "names.json")) }
        backend.init()
        Startup.later {
            indexThread.execute {
                val saved = runCatching { json.decodeFromString<List<Song>>(File(cache, "index.json").readText()) }
                index.addAll(saved.getOrDefault(emptyList()))
                runCatching { File(files, "played.txt").forEachLine { played.add(it) } }
            }
        }
    }

    // Reading [version] subscribes the calling composable, so it redraws when set() changes a setting.
    // SharedPreferences alone isn't observable, and skipped composables such as Switch1 and Options would miss the change.
    fun str(key: String, def: String): String {
        version
        return prefs.getString(key, def) ?: def
    }

    fun bool(key: String, def: Boolean): Boolean {
        version
        return prefs.getBoolean(key, def)
    }

    fun set(key: String, value: Any?) {
        prefs.edit().apply {
            when (value) {
                null -> remove(key)
                is Boolean -> putBoolean(key, value)
                else -> putString(key, value.toString())
            }
        }.apply()
        if (key.startsWith("rom.")) {
            romanizer.settings = romanizeSettings()
            Playback.romanizationChanged()
        }
        if (key == READ_KEY) {
            // The notification's title follows the scripts the user reads.
            Playback.romanizationChanged()
            // Japanese read as written doesn't load the dictionary, so Japanese romanized again must load it.
            romanizer.retryDictionary()
        }
        if (backend.changes(key) or key.startsWith("rom.")) dataChanged()
        // A source that asks for lyrics by what they show (backend.lyricsVariant) loads the song's again.
        if (key.startsWith("lyrics.layer.")) Playback.current()?.let { Playback.loadLyrics(it.song) }
        version++
        if (key == "play.ahead" && value == true && Playback.player.isPlaying) Playback.downloadAhead()
    }

    /** The pinned row's last known mixes, so it isn't empty while Home loads. Null before the first lookup. */
    var mixes: List<Item>?
        get() = runCatching { json.decodeFromString<List<Item>>(File(cache, "mixes.json").readText()) }.getOrNull()
        set(v) { runCatching { File(cache, "mixes.json").writeText(json.encodeToString(v)) } }

    // The last copy of Home, the library tabs and pages is kept on disk and shown at once on the next start.
    // Pages are read, written and dropped on their own thread, in order, so a read never sees a dropped page.
    private val pageThread = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun pageFile(key: String) = File(pages, key.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    /** Blocking: call it off the main thread. */
    fun <T> readPage(key: String, serializer: KSerializer<T>): T? {
        val task = pageThread.submit<T?> {
            runCatching { json.decodeFromString(serializer, pageFile(key).readText()) }.getOrNull()
        }
        return task.get()
    }

    fun <T> writePage(key: String, value: T, serializer: KSerializer<T>) = pageThread.execute {
        pages.mkdirs()
        // A failed write, such as on a full disk, only loses the copy.
        runCatching { pageFile(key).writeText(json.encodeToString(serializer, value)) }
    }

    /** What pages hold changed (account, romanization): the disk copies go, and open pages load again. */
    fun dataChanged() {
        pageThread.execute { pages.listFiles()?.forEach { it.delete() } }
        dataVersion++
    }

    /** Changes with every romanization setting or correction change; it keys the kept romanized lyrics. */
    val romanizationVersion get() = prefs.getInt("rom.cacheVersion", 0)
    fun bumpRomanizationVersion() = prefs.edit().putInt("rom.cacheVersion", romanizationVersion + 1).apply()

    private fun romanizeSettings() = RomanizeSettings(
        japaneseMacrons = bool("rom.macrons", true),
        chineseTones = bool("rom.tones", true),
        cyrillic = str("rom.cyrillic", "english"),
        greek = str("rom.greek", "classical"),
        hanDefault = str("rom.han", "ja"),
    )

    private val overridesFile get() = File(files, "overrides.json")

    private fun loadOverrides(): List<Override> =
        runCatching { json.decodeFromString<List<Override>>(overridesFile.readText()) }.getOrDefault(emptyList())

    /** "Don't romanize": a correction whose Latin form is the original. Saving it again removes it. */
    fun keptOriginal(scope: String, id: String?, original: String) =
        romanizer.overrides.any { it.scope == scope && it.id == id && it.original == original && it.latin == original }

    fun toggleKeepOriginal(scope: String, id: String, original: String) =
        saveOverride(Override(scope, id, original, if (keptOriginal(scope, id, original)) "" else original))

    fun saveOverride(o: Override) {
        val list = romanizer.overrides.filterNot { it.scope == o.scope && it.id == o.id && it.original == o.original }
        setOverrides(if (o.latin.isBlank()) list else list + o)
    }

    /** The reading the user gave this word: this song's own first, then the everywhere one. Null if none. */
    fun wordOverride(word: String, songId: String?): Override? =
        romanizer.overrides.firstOrNull { it.scope == "word" && it.id == songId && it.original == word }
            ?: romanizer.overrides.firstOrNull { it.scope == "word" && it.id == "" && it.original == word }

    /**
     * A word's reading, in one song or everywhere; a blank latin removes it. Saving it everywhere also
     * drops this song's own reading of the word, which would otherwise still win here.
     */
    fun saveWord(word: String, songId: String, everywhere: Boolean, latin: String) {
        val list = romanizer.overrides.filterNot { it.scope == "word" && it.original == word && (it.id == songId || everywhere && it.id == "") }
        setOverrides(if (latin.isBlank()) list else list + Override("word", if (everywhere) "" else songId, word, latin.trim()))
    }

    fun setOverrides(list: List<Override>) {
        // Written beside and renamed over: a full disk or a kill mid-write keeps the old corrections.
        val tmp = File(overridesFile.path + ".tmp")
        val written = runCatching { tmp.writeText(json.encodeToString(list)) }.isSuccess
        if (!written || !tmp.renameTo(overridesFile)) {
            toast("Couldn't save the correction: storage is full or not writable", long = true)
            return
        }
        romanizer.overrides = list
        Playback.romanizationChanged()
        dataChanged()
        version++
    }

    fun exportOverrides(): String {
        val file = overridesFile
        if (!file.exists()) return "[]"
        return file.readText()
    }

    /** Scripts the user reads ("ja", "zh", "ko", "cyrillic", "greek", "other"): text only in those shows as written. */
    val readScripts: Set<String> get() = str(READ_KEY, "").split(',').filter { it.isNotEmpty() }.toSet()

    fun reads(original: String, siblings: List<String> = emptyList()): Boolean {
        val read = readScripts
        if (read.isEmpty()) return false
        val scripts = romanizer.scripts(original, siblings)
        return scripts.isNotEmpty() && read.containsAll(scripts)
    }

    /** The form a single-line surface (notification, menu) shows: the original in a script the user reads, else Latin. */
    fun shown(t: roma.core.Text) = if (reads(t.original)) t.original else t.latin

    const val READ_KEY = "display.read"

    /** Japanese romanized again needs the dictionary: its download starts here if it is missing. */
    fun setReads(script: String, read: Boolean) {
        val r = readScripts
        set(READ_KEY, (if (read) r + script else r - script).joinToString(","))
        if (script == "ja" && !read) Dictionary.install()
    }

    const val PLACE_KEY = "display.place"

    /**
     * Where romanization goes on every surface: "below", "above" (ruby) or "custom" (the per-surface keys). The default
     * is custom for someone who set a per-surface key before this setting existed, so they keep their choice.
     */
    val place: String
        get() {
            val hasPerSurfaceKey = listOf("display.list", "display.player", "lyrics.layout").any(prefs::contains)
            return str(PLACE_KEY, if (hasPerSurfaceKey) "custom" else "below")
        }

    /** "romanized", "both", "ruby" or "original". */
    val listMode get() = mode("display.list")
    val playerMode get() = mode("display.player")
    val lyricsRuby get() = when (place) {
        "below" -> false
        "above" -> true
        else -> str("lyrics.layout", "lines") == "ruby"
    }

    private fun mode(key: String) = when (place) {
        "below" -> "both"
        "above" -> "ruby"
        else -> str(key, "both")
    }

    var banned: List<Banned>
        get() = runCatching { json.decodeFromString<List<Banned>>(str("bans", "[]")) }.getOrDefault(emptyList())
        set(v) = set("bans", json.encodeToString(v))

    fun bans(): Bans {
        val all = banned
        val songs = all.filter { it.kind == "song" }.map { it.id }.toSet()
        val artists = all.filter { it.kind == "artist" }.map { it.id }.toSet()
        return Bans(songs, artists)
    }

    fun ban(kind: String, id: String, name: String) {
        banned = banned.filterNot { it.kind == kind && it.id == id } + Banned(kind, id, name)
    }

    fun unban(kind: String, id: String) {
        banned = banned.filterNot { it.kind == kind && it.id == id }
    }

    fun radioSettings() = RadioSettings(
        leash = str("radio.leash", "free"),
        preferAudio = bool("radio.preferAudio", true),
        hideVersions = bool("radio.hideVersions", true),
        skipBanned = bool("radio.skipBanned", true),
    )

    val stopAtEnd get() = bool("queue.stopAtEnd", false)

    /** Providers in the user's order. */
    fun lyricsOrder(): List<LyricsProvider> {
        val all = backend.lyricsProviders
        val ids = str("lyrics.order", all.joinToString(",") { it.id }).split(",")
        val known = ids.mapNotNull { id -> all.firstOrNull { it.id == id } }
        return known + all.filter { it !in known }
    }

    fun lyricsEnabled(p: LyricsProvider) = bool("lyrics.on.${p.id}", true)

    /**
     * The translation languages shown, best first: the chosen language, or by default the device's, then English. The
     * first is the one Google translates into.
     */
    fun translationLanguages(): List<String> {
        val set = str("lyrics.translationLang", "device")
        return if (set == "device") listOf(java.util.Locale.getDefault().language, "en").distinct() else listOf(set)
    }

    // The local index holds every song that entered the app, newest first, and the played ones are searchable by their
    // Latin form. It is read, changed and saved only on its own thread, so a tap never waits for it: with a full index,
    // loading costs about 100 ms and each save about 15 ms. Saves that queue up behind each other are written once.
    private val index = ArrayList<Song>()
    private const val INDEX_SIZE = 5000
    // Ids of songs played past the scrobble point; "Seen before" shows only these. They are kept in [files], which
    // Android doesn't clear.
    private val played = HashSet<String>()
    private val indexThread = java.util.concurrent.Executors.newSingleThreadExecutor()

    /**
     * Romanized names kept across starts: Home and the lists show the same titles start after start, and a start whose
     * names are all known loads neither the Japanese dictionary nor ICU's tables for them (Han-Latin alone takes about
     * 220 ms the first time in a run). Tied to the romanization version and to this build, emptied when either changes.
     */
    object Names : roma.core.RomanizeCache {
        // ponytail: 6,000 names (about 400 KB on disk), least recently used dropped; enough for Home, the library and
        // the queue. A bigger library just romanizes its rarely seen names again.
        private const val MAX_NAMES = 6000
        private val map = object : LinkedHashMap<String, String>(1024, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > MAX_NAMES
        }
        @Volatile private var ready = false
        private var file: File? = null
        private var saveQueued = false
        private val stamp get() = "$romanizationVersion/${BuildConfig.VERSION_CODE}"

        fun load(f: File) {
            file = f
            indexThread.execute {
                val kept = runCatching { json.decodeFromString<Map<String, String>>(f.readText()) }.getOrNull()
                synchronized(map) {
                    if (kept?.get("") == stamp) map.putAll(kept - "")
                }
                ready = true
            }
        }

        // Until the file is read, every lookup misses and names are romanized as usual, so nothing waits for the cache.
        override fun get(key: String) = if (!ready) null else synchronized(map) { map[key] }

        override fun put(key: String, value: String) {
            if (!ready) return
            synchronized(map) {
                map[key] = value
                if (saveQueued) return
                saveQueued = true
            }
            // The delay lets a burst of new names, such as a page of results, be written once.
            postMain(2_000) { indexThread.execute(::save) }
        }

        private fun save() {
            val copy = synchronized(map) {
                saveQueued = false
                map.toMap()
            }
            runCatching { file?.writeText(json.encodeToString(copy + ("" to stamp))) }
        }

        /** Called after a romanization change, when the kept names no longer apply. */
        fun forget() {
            synchronized(map) { map.clear() }
            indexThread.execute { file?.delete() }
        }
    }
    private var saveQueued = false

    /** Blocking: call it off the main thread. Returns the newest song, because older ones may no longer exist. */
    fun anySong(): Song? = indexThread.submit<Song?> { index.firstOrNull() }.get()

    fun remember(songs: List<Song>) {
        if (songs.isEmpty()) return
        indexThread.execute {
            val ids = songs.map { it.id }.toSet()
            index.removeAll { it.id in ids }
            index.addAll(0, songs)
            while (index.size > INDEX_SIZE) index.removeAt(index.size - 1)
            if (!saveQueued) {
                saveQueued = true
                indexThread.execute {
                    saveQueued = false
                    runCatching { File(cache, "index.json").writeText(json.encodeToString(index.toList())) }
                }
            }
        }
    }

    /** [song] was really listened to: it stays in the index, at the front, and shows under "Seen before". */
    fun played(song: Song) {
        remember(listOf(song))
        indexThread.execute {
            if (played.add(song.id)) {
                runCatching { File(files, "played.txt").appendText(song.id + "\n") }
            }
        }
    }

    /**
     * Blocking: call it off the main thread. The stored Latin forms date from when each song was seen, so the hits are
     * romanized again to follow today's rules.
     */
    fun searchLocal(q: String): List<Song> {
        val task = indexThread.submit<List<Song>> {
            val hits = index.mapNotNull { s ->
                val distance = if (s.id in played) matchDistance(s, q) else null
                if (distance == null) null else s to distance
            }
            hits.sortedBy { it.second }.take(20).map { it.first }
        }
        return task.get().map { romanizer.again(it) }
    }
}
