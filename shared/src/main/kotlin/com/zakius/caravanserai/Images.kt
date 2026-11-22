package com.zakius.caravanserai

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Covers: downloaded once into cacheDir/img, decoded at the size they're shown (a video's 480×360 still in a 48 dp row
 * takes a 48 dp bitmap, not a full one), and kept in memory while there's room. Hardware bitmaps unless the pixels are
 * read. One download and decode per picture and size, however many rows ask for it at once.
 */
object Images {
    private lateinit var dir: File
    private val memory = Lru()
    private val loading = ConcurrentHashMap<String, Deferred<Pic?>>()
    // Deduplicates concurrent network fetches of the same URL (e.g., 544 px player art + 16 px backdrop + 24 px colour).
    private val downloading = ConcurrentHashMap<String, Deferred<ByteArray?>>()
    // Local disk decodes run on their own fast pool so they never wait behind slow network downloads.
    // On-screen downloads take up to 6 connections (HTTP/1.1 per-host concurrency); background prefetching
    // is capped at 2 so it never starves visible covers.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val decodeIo by lazy { Dispatchers.IO.limitedParallelism(4) }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val netIo by lazy { Dispatchers.IO.limitedParallelism(6) }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val prefetchIo by lazy { Dispatchers.IO.limitedParallelism(2) }
    private val scope by lazy { CoroutineScope(SupervisorJob() + decodeIo) }
    private val netScope by lazy { CoroutineScope(SupervisorJob() + netIo) }
    private val prefetchScope by lazy { CoroutineScope(SupervisorJob() + prefetchIo) }
    private val sha1 = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-1") }
    private val HEX = "0123456789abcdef".toCharArray()
    private val tmpSeq = java.util.concurrent.atomic.AtomicLong()
    private const val TOUCH_INTERVAL_MS = 24L * 60 * 60 * 1000

    /** [memoryBytes]: how much the decoded covers may take (see [picMemory]). */
    fun init(cache: File, memoryBytes: Long) {
        dir = File(cache, "img")
        memory.max = memoryBytes
        Startup.later {
            scope.launch {
                // Coil's cache, left behind by versions up to 0.1.39.
                File(cache, "image_cache").deleteRecursively()
                trim()
            }
        }
    }

    /** Updates the cover cache ceiling once [picMemory] is read after the first frame. */
    fun setMemory(memoryBytes: Long) {
        memory.max = memoryBytes
    }

    /** Drops every cover, in memory and on disk. SubsonicSource.forget needs this because another server's covers can have the same names. */
    fun clear() {
        memory.clear()
        scope.launch { dir.deleteRecursively() }
    }

    /** The decoded covers, least recently used out first past [max] bytes. */
    private class Lru {
        var max = Runtime.getRuntime().maxMemory() / 8
        private val map = LinkedHashMap<String, Pic>(64, 0.75f, true)
        private var size = 0L
        @Synchronized fun get(k: String) = map[k]
        @Synchronized fun clear() {
            map.clear()
            size = 0
        }
        @Synchronized fun put(k: String, v: Pic) {
            val replaced = map.put(k, v)
            if (replaced != null) size -= picBytes(replaced)
            size += picBytes(v)
            val i = map.values.iterator()
            while (size > max && i.hasNext()) {
                size -= picBytes(i.next())
                i.remove()
            }
        }
    }

    private const val DISK_MAX_BYTES = 100L * 1024 * 1024

    // ponytail: deletes the oldest files past 100 MB, checked once per start. Coil kept 2% of free space instead.
    private fun trim() {
        val files = dir.listFiles().orEmpty().sortedByDescending { it.lastModified() }
        var kept = 0L
        for (f in files) {
            kept += f.length()
            if (kept > DISK_MAX_BYTES) f.delete()
        }
    }

    private fun key(url: String, px: Int, exact: Boolean, software: Boolean): String {
        val exactMark = if (exact) "e" else ""
        val softwareMark = if (software) "s" else ""
        return "$url@$px$exactMark$softwareMark"
    }

    /** The cover if it is in memory already, so it is drawn in the first frame without a flash of an empty box. */
    fun peek(url: String, px: Int, exact: Boolean = false) = memory.get(key(url, px, exact, false))

    /**
     * [px] is the side it's shown at. The bitmap is at least that big, so it stays sharp. [exact] scales it to exactly
     * [px] across, for the blurred backdrop and the colour sample. [software] makes the pixels readable with getPixel.
     */
    suspend fun load(url: String, px: Int, exact: Boolean = false, software: Boolean = false, background: Boolean = false): Pic? {
        val k = key(url, px, exact, software)
        val cached = memory.get(k)
        if (cached != null) return cached
        val targetScope = if (background) prefetchScope else scope
        val job = loading.computeIfAbsent(k) {
            targetScope.async(start = CoroutineStart.LAZY) {
                try {
                    val data = bytes(url, background) ?: return@async null
                    val pic = runCatching { decodePic(data, px, exact, software) }.getOrNull()
                    if (pic != null) memory.put(k, pic)
                    pic
                } finally {
                    loading.remove(k)
                }
            }
        }
        return job.await()
    }

    fun prefetch(url: String, px: Int) {
        if (peek(url, px) != null) return
        prefetchScope.launch { load(url, px, background = true) }
    }

    /**
     * Downloads onto disk only, without decoding. A screen's worth of covers below the fold would crowd the visible
     * ones out of memory, and decoding a file takes a few ms where downloading it takes a round trip.
     */
    suspend fun download(url: String) {
        withContext(prefetchIo) {
            if (!file(url).exists()) runCatching { bytes(url, background = true) }
        }
    }

    private fun file(url: String): File {
        val digest = sha1.get()!!.digest(url.toByteArray(Charsets.UTF_8))
        val chars = CharArray(digest.size * 2)
        for (i in digest.indices) {
            val v = digest[i].toInt() and 0xff
            chars[i * 2] = HEX[v ushr 4]
            chars[i * 2 + 1] = HEX[v and 0xf]
        }
        return File(dir, String(chars))
    }

    private suspend fun bytes(url: String, background: Boolean): ByteArray? {
        val f = file(url)
        if (f.exists()) {
            val now = System.currentTimeMillis()
            if (now - f.lastModified() > TOUCH_INTERVAL_MS) f.setLastModified(now)
            return runCatching { f.readBytes() }.getOrNull()
        }
        val fetchScope = if (background) prefetchScope else netScope
        val job = downloading.computeIfAbsent(url) {
            fetchScope.async(start = CoroutineStart.LAZY) {
                try {
                    if (f.exists()) return@async runCatching { f.readBytes() }.getOrNull()
                    runCatching {
                        val c = java.net.URI(backend.fetchUrl(url)).toURL().openConnection() as java.net.HttpURLConnection
                        c.connectTimeout = 15_000
                        c.readTimeout = 15_000
                        if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
                        val data = c.inputStream.use { it.readBytes() }
                        dir.mkdirs()
                        val tmp = File(dir, "${f.name}.${tmpSeq.incrementAndGet()}.tmp")
                        tmp.writeBytes(data)
                        if (!tmp.renameTo(f)) tmp.delete()
                        data
                    }.getOrNull()
                } finally {
                    downloading.remove(url)
                }
            }
        }
        return job.await()
    }
}

/**
 * A cover at [size], filling its box: a video's 16:9 frame is cropped to the square, not letterboxed. Until it's
 * loaded it shows [placeholder], or the box in a neutral tone, so a list never has holes where covers go.
 */
@Composable
fun Thumb(
    url: String?,
    size: Dp,
    modifier: Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alpha: Float = 1f,
    exact: Boolean = false,
    placeholder: Pic? = null,
) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    ThumbPx(url, px, modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentScale, alpha, exact, placeholder)
}

@Composable
fun ThumbPx(
    url: String?,
    px: Int,
    modifier: Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alpha: Float = 1f,
    exact: Boolean = false,
    placeholder: Pic? = null,
) {
    // Keyed on the picture: a row reused for another song never shows the previous one's cover.
    val initial = remember(url, px, exact) {
        if (url != null) Images.peek(url, px, exact) else null
    }
    val state = remember(url, px, exact) { mutableStateOf(initial ?: placeholder) }
    // Only launch a coroutine when memory missed; an already-cached bitmap needs zero effect overhead.
    if (url != null && initial == null) {
        LaunchedEffect(url, px, exact) {
            val pic = Images.load(url, px, exact)
            if (pic != null) state.value = pic
        }
    }
    val b = state.value
    if (b == null) {
        Box(modifier)
    } else {
        val image = remember(b) { b.toImage() }
        Image(image, null, modifier, contentScale = contentScale, alpha = alpha)
    }
}

/**
 * The covers a screen holds below the fold or off to the side, fetched in the background: the [near] ones from where
 * [list] is scrolled to are decoded into memory, so they are drawn in their first frame; the rest only go to disk,
 * which would otherwise crowd the memory cache. Only when the screen is shown and guesses are allowed.
 */
@Composable
fun PrefetchCovers(urls: List<String?>, size: Dp, list: LazyListState? = null, near: Int = 30) {
    val shown = LocalTabShown.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    // ponytail: list row indices are used as indices into urls, so headers above the rows shift the window by a few.
    // The start moves in steps of 10 rows, so scrolling restarts the prefetch now and then rather than at every row.
    val from by remember(list) {
        derivedStateOf {
            val firstVisible = list?.firstVisibleItemIndex ?: 0
            firstVisible / 10 * 10
        }
    }
    LaunchedEffect(urls, px, from, shown) {
        if (!shown || Playback.thrifty()) return@LaunchedEffect
        for (u in urls.drop(from).take(near)) {
            val thumb = thumbAt(u, px)
            if (thumb != null) Images.load(thumb, px, background = true)
        }
    }
    LaunchedEffect(urls, px, shown) {
        if (!shown || Playback.thrifty()) return@LaunchedEffect
        for (u in urls) {
            val thumb = thumbAt(u, px)
            if (thumb != null) Images.download(thumb)
        }
    }
}
