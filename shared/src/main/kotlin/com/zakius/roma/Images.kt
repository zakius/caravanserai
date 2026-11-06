package com.zakius.roma

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
    // ponytail: 6 downloads at a time, matching the usual 6 connections per host over HTTP/1.1. If scrolling lags,
    // a priority queue for the visible rows is the next step.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(6)
    private val scope = CoroutineScope(SupervisorJob() + io)

    /** [memoryBytes]: how much the decoded covers may take (see [picMemory]). */
    fun init(cache: File, memoryBytes: Long) {
        dir = File(cache, "img")
        memory.max = memoryBytes
        scope.launch {
            // Coil's cache, left behind by versions up to 0.1.39.
            File(cache, "image_cache").deleteRecursively()
            trim()
        }
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
    suspend fun load(url: String, px: Int, exact: Boolean = false, software: Boolean = false): Pic? {
        val k = key(url, px, exact, software)
        val cached = memory.get(k)
        if (cached != null) return cached
        val job = loading.computeIfAbsent(k) {
            scope.async(start = CoroutineStart.LAZY) {
                try {
                    val pic = runCatching { decodePic(bytes(url), px, exact, software) }.getOrNull()
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
        scope.launch { load(url, px) }
    }

    /**
     * Downloads onto disk only, without decoding. A screen's worth of covers below the fold would crowd the visible
     * ones out of memory, and decoding a file takes a few ms where downloading it takes a round trip.
     */
    suspend fun download(url: String) {
        withContext(io) {
            if (!file(url).exists()) runCatching { bytes(url) }
        }
    }

    private fun file(url: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        val name = digest.joinToString("") { byte -> "%02x".format(byte) }
        return File(dir, name)
    }

    private fun bytes(url: String): ByteArray {
        val f = file(url)
        val name = f.name
        if (f.exists()) {
            f.setLastModified(System.currentTimeMillis())
            return f.readBytes()
        }
        val c = java.net.URI(backend.fetchUrl(url)).toURL().openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 15_000
        if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
        val data = c.inputStream.use { it.readBytes() }
        dir.mkdirs()
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(data)
        tmp.renameTo(f)
        return data
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
    val state = remember(url, px) {
        val cached = if (url != null) Images.peek(url, px, exact) else null
        mutableStateOf(cached ?: placeholder)
    }
    // Loads even when peek missed only by a moment: a decode started at launch can finish between composing this and
    // running the effect, and without the load the box would stay empty until something else recomposed it.
    LaunchedEffect(url, px) {
        if (url != null) {
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
 * The covers a screen holds below the fold or off to the side, fetched one at a time in the background: the [near]
 * ones from where [list] is scrolled to are decoded into memory, so they are drawn in their first frame; the rest
 * only go to disk, which would otherwise crowd the memory cache. Only when guesses are allowed (an unmetered network
 * outside Battery Saver, unless the user allows them anyway); stops when the screen goes.
 */
@Composable
fun PrefetchCovers(urls: List<String?>, size: Dp, list: LazyListState? = null, near: Int = 30) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    // ponytail: list row indices are used as indices into urls, so headers above the rows shift the window by a few.
    // The start moves in steps of 10 rows, so scrolling restarts the prefetch now and then rather than at every row.
    val from by remember(list) {
        derivedStateOf {
            val firstVisible = list?.firstVisibleItemIndex ?: 0
            firstVisible / 10 * 10
        }
    }
    LaunchedEffect(urls, px, from) {
        if (Playback.thrifty()) return@LaunchedEffect
        for (u in urls.drop(from).take(near)) {
            val thumb = thumbAt(u, px)
            if (thumb != null) Images.load(thumb, px)
        }
        for (u in urls) {
            val thumb = thumbAt(u, px)
            if (thumb != null) Images.download(thumb)
        }
    }
}
