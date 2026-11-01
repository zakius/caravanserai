package com.zakius.roma

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

// The desktop's audio, below DesktopAudio: YouTube's stream of a song kept on disk as it downloads (StreamFile),
// read packet by packet (WebM with Opus, MP4 with AAC: the two containers and codecs YouTube serves audio in) and
// decoded to 16-bit PCM in pure Java.

/** A position in a [StreamFile], read forward. */
class Cursor(private val f: StreamFile, var pos: Long) {
    private val one = ByteArray(8)
    fun bytes(n: Int): ByteArray {
        val b = ByteArray(n)
        var got = 0
        while (got < n) { val m = f.read(pos + got, b, got, n - got); if (m <= 0) throw java.io.EOFException(); got += m }
        pos += n
        return b
    }
    fun u8(): Int { if (f.read(pos, one, 0, 1) < 1) throw java.io.EOFException(); pos++; return one[0].toInt() and 0xff }
    fun u32(): Long { val b = bytes(4); return ((b[0].toLong() and 0xff) shl 24) or ((b[1].toLong() and 0xff) shl 16) or ((b[2].toLong() and 0xff) shl 8) or (b[3].toLong() and 0xff) }
    fun u64(): Long = (u32() shl 32) or u32()
    val atEnd get() = f.total in 0..pos
}

/**
 * One coded frame and when it plays, in microseconds from the song's start (negative for the codec's lead-in);
 * [dropEnd]: samples at its end that don't play (the last frame's padding).
 */
class Packet(val data: ByteArray, val timeUs: Long, val dropEnd: Int = 0)

/** A container read packet by packet. */
interface Demuxer {
    val sampleRate: Int
    val channels: Int
    /** The song's length in ms, -1 when the container doesn't say. */
    val durationMs: Long
    /** The next packet, null at the end. */
    fun next(): Packet?
    /** To the packet at or before [ms]; the decoder drops the samples before [ms]. */
    fun seek(ms: Long)
}

/** Packets to interleaved 16-bit PCM. */
interface Decoder {
    /** Decodes [p] into [out]; returns the samples per channel written. */
    fun decode(p: Packet, out: ShortArray): Int
}

/** A song as 16-bit PCM from where it was opened: the samples before that (and the codec's lead-in) are dropped. */
interface Pcm : AutoCloseable {
    val sampleRate: Int
    val channels: Int
    /** The song's length in ms, -1 when the container doesn't say. */
    val durationMs: Long
    /** Blocking: interleaved samples into [out]; returns the samples per channel, -1 at the end. */
    fun read(out: ShortArray): Int
    override fun close() {}
}

/** [f] as PCM from [atMs]: Opus decoded here, AAC by roma-aac ([AacPcm]). */
fun pcm(f: StreamFile, atMs: Long): Pcm = when (val d = demuxer(f)) {
    is WebmDemuxer -> Decoded(d, d.decoder(), atMs)
    is Mp4Demuxer -> AacPcm(d, atMs)
    else -> error("No decoder")
}

/** Packets decoded in this process, from [atMs]. */
class Decoded(private val d: Demuxer, private val decoder: Decoder, private val atMs: Long) : Pcm {
    override val sampleRate get() = d.sampleRate
    override val channels get() = d.channels
    override val durationMs get() = d.durationMs
    private val buf = ShortArray(5760 * d.channels)

    init { if (atMs > 0) d.seek(atMs) }

    override fun read(out: ShortArray): Int {
        while (true) {
            val p = d.next() ?: return -1
            val n = decoder.decode(p, buf) - p.dropEnd
            // The samples before the start (the lead-in) or the seek target don't play.
            val from = samples(maxOf(atMs * 1000, 0) - p.timeUs, sampleRate).coerceIn(0, maxOf(n, 0).toLong()).toInt()
            if (from >= n) continue
            System.arraycopy(buf, from * channels, out, 0, (n - from) * channels)
            return n - from
        }
    }
}

/**
 * AAC through roma-aac, a build of ffmpeg's AAC decoder alone (tools/build-ffmpeg.sh): this reader's frames go to its
 * stdin as ADTS, its PCM comes back on stdout. The frames' times say how many samples to drop at the start (the
 * edit list's lead-in, or up to the seek target), so it behaves as [Decoded].
 */
class AacPcm(private val d: Mp4Demuxer, atMs: Long) : Pcm {
    override val sampleRate get() = d.sampleRate
    override val channels get() = d.channels
    override val durationMs get() = d.durationMs
    private val process: Process
    private val pcm: java.io.InputStream
    private val writer: Thread
    @Volatile private var failure: Throwable? = null
    @Volatile private var closed = false
    @Volatile private var drop = -1L // samples per channel still to drop; -1 until the first frame's time is known
    private val bytes = ByteArray(4096 * 2 * d.channels)

    init {
        if (atMs > 0) d.seek(atMs)
        process = ProcessBuilder(decoderPath(), "-hide_banner", "-loglevel", "error", "-f", "aac", "-i", "pipe:0",
            "-f", "s16le", "-ac", "${d.channels}", "-ar", "${d.sampleRate}", "pipe:1").redirectError(ProcessBuilder.Redirect.DISCARD).start()
        pcm = process.inputStream
        val (profile, rate, ch) = d.adts
        writer = Thread({
            try {
                process.outputStream.buffered().use { o ->
                    var first = true
                    while (true) {
                        val p = d.next() ?: break
                        if (first) { first = false; drop = samples(maxOf(atMs * 1000, 0) - p.timeUs, d.sampleRate).coerceAtLeast(0) }
                        val len = p.data.size + 7
                        o.write(0xFF); o.write(0xF1)
                        o.write((profile shl 6) or (rate shl 2) or (ch shr 2))
                        o.write(((ch and 3) shl 6) or (len shr 11))
                        o.write((len shr 3) and 0xff)
                        o.write(((len and 7) shl 5) or 0x1F)
                        o.write(0xFC)
                        o.write(p.data)
                    }
                    if (first) drop = 0
                }
            } catch (t: Throwable) {
                if (!closed) failure = t
                process.destroy()
            }
        }, "aac in").apply { isDaemon = true; start() }
    }

    override fun read(out: ShortArray): Int {
        while (true) {
            val n = readFully(minOf(bytes.size, out.size * 2))
            if (n <= 0) {
                failure?.let { throw it }
                // roma-aac ended on its own: at the end of the input, or a crash mid-song, which isn't the song's end.
                if (!closed && process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() != 0) {
                    throw IOException("roma-aac stopped (exit ${process.exitValue()})")
                }
                return -1
            }
            var frames = n / (2 * channels)
            var from = 0
            if (drop > 0) { val k = minOf(drop, frames.toLong()).toInt(); from = k; drop -= k }
            if (from >= frames) continue
            for (i in from * channels until frames * channels) out[i - from * channels] = ((bytes[2 * i + 1].toInt() shl 8) or (bytes[2 * i].toInt() and 0xff)).toShort()
            return frames - from
        }
    }

    // Whole frames only, and only once the first input frame said how many samples to drop.
    private fun readFully(max: Int): Int {
        val frame = 2 * channels
        var got = 0
        while (got < frame || got % frame != 0) {
            val m = pcm.read(bytes, got, max - got)
            if (m < 0) break
            got += m
        }
        while (drop < 0 && failure == null && process.isAlive) Thread.sleep(1)
        return got - got % frame
    }

    // Through its handle: Process.destroy first closes its input, which waits for the writer, stuck in a full pipe while
    // the player holds its lock, and the reader of the output waits for that lock: a skip froze the player.
    override fun close() {
        closed = true
        writer.interrupt()
        process.toHandle().destroy()
    }

    companion object {
        /**
         * roma-aac: in the package's resources, or where -Droma.aac points (a run from Gradle). The packaging drops
         * its executable bit and an installed copy is the system's, so a copy that can't run is copied once into the
         * cache and run from there.
         */
        fun decoderPath(): String {
            val exe = if (System.getProperty("os.name").startsWith("Windows")) "roma-aac.exe" else "roma-aac"
            System.getProperty("roma.aac")?.let { return it }
            val bundled = File(System.getProperty("compose.application.resources.dir") ?: error("roma-aac is missing"), exe)
            check(bundled.exists()) { "roma-aac is missing from $bundled" }
            if (bundled.canExecute()) return bundled.path
            val copy = File(Dirs.cache, exe)
            if (copy.length() != bundled.length()) bundled.copyTo(copy, overwrite = true)
            copy.setExecutable(true)
            return copy.path
        }
    }
}

/** [us] microseconds at [rate], to the nearest sample. */
fun samples(us: Long, rate: Int) = Math.round(us * rate / 1e6)

fun demuxer(f: StreamFile): Demuxer {
    val head = Cursor(f, 0).bytes(8)
    return when {
        head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() -> WebmDemuxer(f)
        String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> Mp4Demuxer(f)
        else -> throw IOException("Not an audio stream roma can read")
    }
}

/**
 * WebM (Matroska) with one Opus track, as YouTube serves it: no lacing, Cues (the seek index) before the clusters.
 * Unknown sizes (a live stream's segment) run to the end of the file.
 */
class WebmDemuxer(private val f: StreamFile) : Demuxer {
    private var c = Cursor(f, 0)
    private var segmentStart = 0L
    private var scale = 1_000_000L // ns per timecode
    override var durationMs = -1L
        private set
    override var sampleRate = 48_000
        private set
    override var channels = 2
        private set
    private var codecPrivate: ByteArray? = null
    private var codecDelayNs = 0L
    private val cues = ArrayList<Pair<Long, Long>>() // (timecode, cluster position in the segment)
    private var clusterTime = 0L
    private var clusterEnd = Long.MAX_VALUE
    private var firstCluster = 0L
    private var delayUs = 0L

    init {
        val (ebml, ebmlSize) = header()
        check(ebml == 0x1A45DFA3L) { "Not WebM" }
        c.pos += ebmlSize
        val (segment, segmentSize) = header()
        check(segment == 0x18538067L) { "No segment" }
        segmentStart = c.pos
        val end = if (segmentSize < 0) Long.MAX_VALUE else c.pos + segmentSize
        while (c.pos < end) {
            val at = c.pos
            val (id, size) = header()
            when (id) {
                0x1549A966L -> info(c.pos + size)
                0x1654AE6BL -> tracks(c.pos + size)
                0x1C53BB6BL -> cues(c.pos + size)
                0x1F43B675L -> { firstCluster = at; c.pos = at; break }
                else -> c.pos += size
            }
        }
        checkNotNull(codecPrivate) { "No Opus track" }
        // Block times include the codec's delay (Matroska's CodecDelay, else OpusHead's pre-skip at 48 kHz).
        val head = codecPrivate!!
        delayUs = if (codecDelayNs > 0) codecDelayNs / 1000 else if (head.size >= 12) ((head[10].toInt() and 0xff) or ((head[11].toInt() and 0xff) shl 8)) * 1_000_000L / 48_000 else 0
    }


    // An element's id (with its length marker) and size; -1 for an unknown size.
    private fun header(): Pair<Long, Long> {
        val id = vint(keepMarker = true)
        val size = vint(keepMarker = false)
        return id to size
    }

    private fun vint(keepMarker: Boolean): Long {
        val first = c.u8()
        var len = 1
        var mask = 0x80
        while (first and mask == 0) { mask = mask shr 1; len++; if (len > 8) throw IOException("Bad WebM") }
        var v = (if (keepMarker) first else first and (mask - 1)).toLong()
        var allOnes = v == (mask - 1).toLong()
        repeat(len - 1) { val b = c.u8(); v = (v shl 8) or b.toLong(); if (b != 0xff) allOnes = false }
        return if (!keepMarker && allOnes) -1 else v
    }

    private fun uint(size: Long): Long { var v = 0L; repeat(size.toInt()) { v = (v shl 8) or c.u8().toLong() }; return v }
    private fun float(size: Long): Double { val b = c.bytes(size.toInt()); val bb = java.nio.ByteBuffer.wrap(b); return if (size == 4L) bb.float.toDouble() else bb.double }

    private fun info(end: Long) {
        var duration = -1.0
        while (c.pos < end) {
            val (id, size) = header()
            when (id) { 0x2AD7B1L -> scale = uint(size); 0x4489L -> duration = float(size); else -> c.pos += size }
        }
        if (duration > 0) durationMs = (duration * scale / 1_000_000).toLong()
    }

    private fun tracks(end: Long) {
        while (c.pos < end) {
            val (id, size) = header()
            if (id != 0xAEL) { c.pos += size; continue }
            val entryEnd = c.pos + size
            var codec = ""
            while (c.pos < entryEnd) {
                val (eid, esize) = header()
                when (eid) {
                    0x86L -> codec = String(c.bytes(esize.toInt()), Charsets.US_ASCII)
                    0x63A2L -> codecPrivate = c.bytes(esize.toInt())
                    0x56AAL -> codecDelayNs = uint(esize)
                    0xE1L -> {
                        val audioEnd = c.pos + esize
                        while (c.pos < audioEnd) {
                            val (aid, asize) = header()
                            when (aid) { 0xB5L -> sampleRate = float(asize).toInt(); 0x9FL -> channels = uint(asize).toInt(); else -> c.pos += asize }
                        }
                    }
                    else -> c.pos += esize
                }
            }
            check(codec == "A_OPUS") { "Unsupported codec $codec" }
        }
    }

    private fun cues(end: Long) {
        while (c.pos < end) {
            val (id, size) = header()
            if (id != 0xBBL) { c.pos += size; continue }
            val pointEnd = c.pos + size
            var time = 0L
            var cluster = -1L
            while (c.pos < pointEnd) {
                val (pid, psize) = header()
                when (pid) {
                    0xB3L -> time = uint(psize)
                    0xB7L -> {
                        val posEnd = c.pos + psize
                        while (c.pos < posEnd) { val (qid, qsize) = header(); if (qid == 0xF1L) cluster = uint(qsize) else c.pos += qsize }
                    }
                    else -> c.pos += psize
                }
            }
            if (cluster >= 0) cues += time to cluster
        }
    }

    fun decoder(): Decoder = OpusDecoderOf(channels)

    override fun next(): Packet? {
        while (true) {
            if (c.atEnd) return null
            val (id, size) = try { header() } catch (_: java.io.EOFException) { return null }
            when (id) {
                0x1F43B675L -> clusterEnd = if (size < 0) Long.MAX_VALUE else c.pos + size // into the cluster
                0xE7L -> clusterTime = uint(size)
                0xA3L -> return block(size, 0)
                0xA0L -> return group(c.pos + size)
                else -> c.pos += size
            }
        }
    }

    // A BlockGroup: its Block, and the DiscardPadding (in ns) of the last one.
    private fun group(end: Long): Packet? {
        var at = -1L; var size = 0L; var padNs = 0L
        while (c.pos < end) {
            val (id, s) = header()
            when (id) { 0xA1L -> { at = c.pos; size = s; c.pos += s }; 0x75A2L -> padNs = uint(s); else -> c.pos += s }
        }
        if (at < 0) return next()
        c.pos = at
        return block(size, (padNs * sampleRate / 1_000_000_000).toInt()).also { c.pos = end }
    }

    private fun block(size: Long, dropEnd: Int): Packet {
        val start = c.pos
        vint(keepMarker = false) // track number: one track
        val rel = (c.u8() shl 8 or c.u8()).toShort().toLong()
        val flags = c.u8()
        if (flags and 0x06 != 0) throw IOException("Laced WebM blocks aren't supported")
        val data = c.bytes((size - (c.pos - start)).toInt())
        return Packet(data, (clusterTime + rel) * scale / 1000 - delayUs, dropEnd)
    }

    override fun seek(ms: Long) {
        val tc = ms * 1_000_000 / scale
        val cue = cues.lastOrNull { it.first <= tc }
        c.pos = if (cue != null) segmentStart + cue.second else firstCluster
    }
}

/**
 * Fragmented MP4 with one AAC track, as YouTube serves it: moov (the codec's config, the edit list's lead-in), sidx
 * (each fragment's size and length, for seeking), then moof + mdat pairs of about 10 s.
 */
class Mp4Demuxer(private val f: StreamFile) : Demuxer {
    private val c = Cursor(f, 0)
    private var timescale = 44_100L
    override var sampleRate = 44_100
        private set
    override var channels = 2
        private set
    override var durationMs = -1L
        private set
    private var config: ByteArray? = null
    private var leadIn = 0L // in the timescale: the edit list's start
    private var defaultDuration = 1024L
    private var defaultSize = 0L
    private val fragments = ArrayList<Pair<Long, Long>>() // (start time in the timescale, file position)
    private val queue = ArrayDeque<Packet>()

    init {
        while (config == null || fragments.isEmpty() && !c.atEnd) {
            val at = c.pos
            val (type, size) = box()
            when (type) {
                "moov" -> moov(at + size)
                "sidx" -> sidx(at, at + size)
                "moof" -> { c.pos = at; break }
                else -> c.pos = at + size
            }
        }
        checkNotNull(config) { "No AAC track" }
    }

    // A box's type and whole size (header included); the cursor is left after the header.
    private fun box(): Pair<String, Long> {
        var size = c.u32()
        val type = String(c.bytes(4), Charsets.ISO_8859_1)
        if (size == 1L) size = c.u64()
        if (size == 0L) size = Long.MAX_VALUE / 2
        return type to size
    }

    private fun moov(end: Long) {
        while (c.pos < end) {
            val at = c.pos
            val (type, size) = box()
            when (type) {
                "trak", "mdia", "minf", "stbl", "edts", "mvex" -> moov(at + size)
                "mdhd" -> {
                    val v = c.u8(); c.pos += 3
                    if (v == 1) { c.pos += 16; timescale = c.u32(); c.u64() } else { c.pos += 8; timescale = c.u32(); c.u32() }
                }
                "elst" -> {
                    val v = c.u8(); c.pos += 3
                    if (c.u32() > 0) leadIn = if (v == 1) { c.u64(); c.u64() } else { c.u32(); c.u32().toInt().toLong() }
                    if (leadIn < 0) leadIn = 0
                }
                "trex" -> { c.pos += 4 + 4 + 4; defaultDuration = c.u32(); defaultSize = c.u32() }
                "stsd" -> { c.pos += 8; sampleEntry() }
            }
            c.pos = at + size
        }
    }

    private fun sampleEntry() {
        val at = c.pos
        val (type, size) = box()
        check(type == "mp4a") { "Unsupported codec $type" }
        c.pos += 6 + 2 + 8 // reserved, data reference index, reserved
        channels = (c.u8() shl 8) or c.u8()
        c.pos += 2 + 4 // sample size, pre-defined and reserved
        sampleRate = (c.u32() shr 16).toInt()
        while (c.pos < at + size) {
            val bAt = c.pos
            val (t, s) = box()
            if (t == "esds") { c.pos += 4; config = decoderSpecificInfo(bAt + s) }
            c.pos = bAt + s
        }
    }

    // ES_Descriptor > DecoderConfigDescriptor > DecoderSpecificInfo: the AudioSpecificConfig.
    private fun decoderSpecificInfo(end: Long): ByteArray? {
        while (c.pos < end) {
            val tag = c.u8()
            var len = 0
            for (i in 0 until 4) { val b = c.u8(); len = (len shl 7) or (b and 0x7f); if (b and 0x80 == 0) break }
            when (tag) {
                0x03 -> { c.pos += 2; val flags = c.u8(); if (flags and 0x80 != 0) c.pos += 2; if (flags and 0x40 != 0) c.pos += c.u8(); if (flags and 0x20 != 0) c.pos += 2 }
                0x04 -> c.pos += 13
                0x05 -> return c.bytes(len)
                else -> c.pos += len
            }
        }
        return null
    }

    private fun sidx(at: Long, end: Long) {
        val v = c.u8(); c.pos += 3
        c.pos += 4 // reference id
        val scale = c.u32()
        var time = if (v == 0) c.u32() else c.u64()
        val first = if (v == 0) c.u32() else c.u64()
        c.pos += 2
        val count = (c.u8() shl 8) or c.u8()
        var pos = end + first
        var total = 0L
        repeat(count) {
            val size = c.u32() and 0x7fffffff
            val duration = c.u32()
            c.pos += 4
            fragments += time to pos
            time += duration; total += duration; pos += size
        }
        durationMs = (total - leadIn) * 1000 / scale
    }

    /** The AudioSpecificConfig's fields as an ADTS header wants them: profile, sampling frequency index, channels. */
    val adts: Triple<Int, Int, Int> get() {
        val c = config!!
        val bits = ((c[0].toInt() and 0xff) shl 8) or (c.getOrElse(1) { 0 }.toInt() and 0xff)
        val type = bits shr 11
        val rate = (bits shr 7) and 0xf
        val ch = (bits shr 3) and 0xf
        // HE-AAC (5, 29) carries an AAC-LC core: ADTS says LC, and the decoder finds SBR by itself.
        return Triple(if (type == 5 || type == 29) 1 else type - 1, rate, if (ch == 0) channels else ch)
    }

    override fun next(): Packet? {
        while (queue.isEmpty()) if (!fragment()) return null
        return queue.removeFirst()
    }

    // Reads the next moof and its samples (from the mdat after it) into the queue; false at the end.
    private fun fragment(): Boolean {
        while (true) {
            if (c.atEnd) return false
            val at = c.pos
            val (type, size) = try { box() } catch (_: java.io.EOFException) { return false }
            if (type != "moof") { c.pos = at + size; continue }
            var base = 0L
            var dataOffset = 0L
            var sizes = LongArray(0)
            var durations = LongArray(0)
            var sampleSize = defaultSize
            var sampleDuration = defaultDuration
            val end = at + size
            fun walk(until: Long) {
                while (c.pos < until) {
                    val bAt = c.pos
                    val (t, s) = box()
                    when (t) {
                        "traf" -> walk(bAt + s)
                        "tfhd" -> {
                            val flags = c.u32() and 0xffffff
                            c.pos += 4
                            if (flags and 0x1 != 0L) c.pos += 8
                            if (flags and 0x2 != 0L) c.pos += 4
                            if (flags and 0x8 != 0L) sampleDuration = c.u32()
                            if (flags and 0x10 != 0L) sampleSize = c.u32()
                        }
                        "tfdt" -> { val v = c.u8(); c.pos += 3; base = if (v == 1) c.u64() else c.u32() }
                        "trun" -> {
                            val flags = c.u32() and 0xffffff
                            val n = c.u32().toInt()
                            if (flags and 0x1 != 0L) dataOffset = c.u32().toInt().toLong()
                            if (flags and 0x4 != 0L) c.pos += 4
                            sizes = LongArray(n); durations = LongArray(n)
                            for (i in 0 until n) {
                                durations[i] = if (flags and 0x100 != 0L) c.u32() else sampleDuration
                                sizes[i] = if (flags and 0x200 != 0L) c.u32() else sampleSize
                                if (flags and 0x400 != 0L) c.pos += 4
                                if (flags and 0x800 != 0L) c.pos += 4
                            }
                        }
                    }
                    c.pos = bAt + s
                }
            }
            walk(end)
            // The samples sit at the trun's data offset from the moof's start (YouTube's default-base-is-moof).
            c.pos = at + dataOffset
            var t = base
            for (i in sizes.indices) {
                queue.addLast(Packet(c.bytes(sizes[i].toInt()), (t - leadIn) * 1_000_000 / timescale))
                t += durations[i]
            }
            return true
        }
    }

    override fun seek(ms: Long) {
        queue.clear()
        val t = ms * timescale / 1000 + leadIn
        c.pos = (fragments.lastOrNull { it.first <= t } ?: fragments.firstOrNull())?.second ?: c.pos
    }
}

/** Opus through Concentus. */
class OpusDecoderOf(private val channels: Int) : Decoder {
    private val d = io.github.jaredmdobson.concentus.OpusDecoder(48_000, channels)
    override fun decode(p: Packet, out: ShortArray) = d.decode(p.data, 0, p.data.size, out, 0, out.size / channels, false)
}
