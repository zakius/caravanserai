package com.zakius.roma

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

// The desktop's audio below DesktopAudio. A song's stream is kept on disk as it downloads (StreamFile) and read packet
// by packet. YouTube serves audio as Opus in WebM or AAC in MP4: Opus is decoded to 16-bit PCM here in pure Java, and
// AAC by roma-aac ([AacPcm]). Any other file (a Subsonic server's MP3, FLAC, Ogg, WAV) goes whole to roma-aac
// ([FilePcm]).

/** The most samples per channel one Opus packet decodes to: 120 ms at 48 kHz. */
const val MAX_PACKET_SAMPLES = 5760

/** Frames of 16-bit PCM read from roma-aac at a time. */
private const val PCM_READ_FRAMES = 4096

/** A position in a [StreamFile], read forward. */
class Cursor(private val f: StreamFile, var pos: Long) {
    private val one = ByteArray(8)

    fun bytes(n: Int): ByteArray {
        val b = ByteArray(n)
        var got = 0
        while (got < n) {
            val m = f.read(pos + got, b, got, n - got)
            if (m <= 0) throw java.io.EOFException()
            got += m
        }
        pos += n
        return b
    }

    fun u8(): Int {
        if (f.read(pos, one, 0, 1) < 1) throw java.io.EOFException()
        pos++
        return one[0].toInt() and 0xff
    }

    /** An unsigned big-endian 32-bit number. */
    fun u32(): Long {
        val b = bytes(4)
        val b0 = b[0].toLong() and 0xff
        val b1 = b[1].toLong() and 0xff
        val b2 = b[2].toLong() and 0xff
        val b3 = b[3].toLong() and 0xff
        return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    /** A big-endian 64-bit number. */
    fun u64(): Long {
        val high = u32()
        val low = u32()
        return (high shl 32) or low
    }

    val atEnd get() = f.total in 0..pos
}

/**
 * One coded frame and when it plays, in microseconds from the song's start (negative for the codec's lead-in).
 * [dropEnd] is the count of samples at its end that don't play, the last frame's padding.
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
    /** Moves to the packet at or before [ms]. The decoder drops the samples before [ms]. */
    fun seek(ms: Long)
}

/** Packets to interleaved 16-bit PCM. */
interface Decoder {
    /** Decodes [p] into [out] and returns the samples per channel written. */
    fun decode(p: Packet, out: ShortArray): Int
}

/** A song as 16-bit PCM from where it was opened. The samples before that, and the codec's lead-in, are dropped. */
interface Pcm : AutoCloseable {
    val sampleRate: Int
    val channels: Int
    /** The song's length in ms, -1 when the container doesn't say. */
    val durationMs: Long
    /** Blocking. Reads interleaved samples into [out] and returns the samples per channel, or -1 at the end. */
    fun read(out: ShortArray): Int
    override fun close() {}
}

/**
 * [f] as PCM from [atMs]. Opus in WebM is decoded here, AAC in MP4 by roma-aac ([AacPcm]), and any other file by
 * roma-aac whole ([FilePcm]).
 */
fun pcm(f: StreamFile, atMs: Long): Pcm = when (val d = demuxer(f)) {
    is WebmDemuxer -> Decoded(d, d.decoder(), atMs)
    is Mp4Demuxer -> AacPcm(d, atMs)
    else -> FilePcm(f, atMs)
}

/** Packets decoded in this process, from [atMs]. */
class Decoded(private val d: Demuxer, private val decoder: Decoder, private val atMs: Long) : Pcm {
    override val sampleRate get() = d.sampleRate
    override val channels get() = d.channels
    override val durationMs get() = d.durationMs
    private val buf = ShortArray(MAX_PACKET_SAMPLES * d.channels)

    init { if (atMs > 0) d.seek(atMs) }

    override fun read(out: ShortArray): Int {
        while (true) {
            val p = d.next() ?: return -1
            val n = decoder.decode(p, buf) - p.dropEnd
            // The samples before the start (the lead-in) or the seek target don't play.
            val startUs = maxOf(atMs * 1000, 0)
            val before = samples(startUs - p.timeUs, sampleRate)
            val from = before.coerceIn(0, maxOf(n, 0).toLong()).toInt()
            if (from >= n) continue
            System.arraycopy(buf, from * channels, out, 0, (n - from) * channels)
            return n - from
        }
    }
}

/**
 * A child process fed by a thread of its own. [feed] writes its input, and its output is [process]'s input stream.
 * [failure] says why feeding stopped, unless it was closed. [close] never blocks (FedProcessTest).
 */
class FedProcess(command: List<String>, name: String, feed: (java.io.OutputStream) -> Unit) : AutoCloseable {
    val process: Process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    @Volatile var failure: Throwable? = null
        private set
    @Volatile var closed = false
        private set
    private val writer = Thread({
        try {
            process.outputStream.buffered().use(feed)
        } catch (t: Throwable) {
            if (!closed) failure = t
            process.toHandle().destroyForcibly()
        }
    }, name).apply {
        isDaemon = true
        start()
    }

    /** The feeding thread is still at it. */
    val feeding get() = writer.isAlive

    /**
     * Kills the process and lets go of its pipes without waiting for anything. Process.destroy would close the input
     * first, which waits for the feeder's write. When the process is stuck writing output that nobody reads any more,
     * that write never ends, and roma-aac then ignores SIGTERM too, so a track change would freeze the player and the
     * UI. Killed outright, the process can't hold out and the feeder's write fails. The pipes close on a thread of
     * their own, because closing the output waits for any read in progress.
     */
    override fun close() {
        closed = true
        writer.interrupt()
        process.toHandle().destroyForcibly()
        Thread({
            runCatching { process.inputStream.close() }
            runCatching { process.outputStream.close() }
            process.waitFor()
        }, "close ${writer.name}").apply {
            isDaemon = true
            start()
        }
    }
}

/**
 * AAC through roma-aac, a build of ffmpeg with only its AAC decoder (tools/build-ffmpeg.sh). This reader's frames go to
 * its stdin as ADTS, and its PCM comes back on stdout. The frames' times say how many samples to drop at the start
 * (the edit list's lead-in, or up to the seek target), so it behaves as [Decoded].
 */
class AacPcm(private val d: Mp4Demuxer, atMs: Long) : Pcm {
    override val sampleRate get() = d.sampleRate
    override val channels get() = d.channels
    override val durationMs get() = d.durationMs
    // Samples per channel still to drop, or -1 until the first frame's time is known.
    @Volatile private var drop = -1L
    private val bytes = ByteArray(PCM_READ_FRAMES * 2 * d.channels)
    private val decoder: FedProcess

    init {
        if (atMs > 0) d.seek(atMs)
        val (profile, rate, ch) = d.adts
        val command = listOf(
            decoderPath(), "-hide_banner", "-loglevel", "error", "-f", "aac", "-i", "pipe:0",
            "-f", "s16le", "-ac", "${d.channels}", "-ar", "${d.sampleRate}", "pipe:1",
        )
        decoder = FedProcess(command, "aac in") { o ->
            var first = true
            while (true) {
                val p = d.next() ?: break
                if (first) {
                    first = false
                    val startUs = maxOf(atMs * 1000, 0)
                    drop = samples(startUs - p.timeUs, d.sampleRate).coerceAtLeast(0)
                }
                // A 7-byte ADTS header without CRC. Its 13-bit frame length counts the header too, and its 11-bit
                // buffer fullness is all ones, which means variable bitrate.
                val len = p.data.size + 7
                o.write(0xFF) // sync word, high 8 bits
                o.write(0xF1) // sync word low 4 bits, MPEG-4, layer 0, no CRC
                o.write((profile shl 6) or (rate shl 2) or (ch shr 2)) // profile 2 bits, rate index 4, private 1, channels high 1
                o.write(((ch and 3) shl 6) or (len shr 11)) // channels low 2 bits, 4 unused, frame length high 2
                o.write((len shr 3) and 0xff) // frame length middle 8 bits
                o.write(((len and 7) shl 5) or 0x1F) // frame length low 3 bits, buffer fullness high 5
                o.write(0xFC) // buffer fullness low 6 bits, one raw data block
                o.write(p.data)
            }
            if (first) drop = 0
        }
    }
    private val process get() = decoder.process
    private val pcm get() = decoder.process.inputStream

    override fun read(out: ShortArray): Int {
        while (true) {
            val n = readFully(minOf(bytes.size, out.size * 2))
            if (n <= 0) {
                val failure = decoder.failure
                if (failure != null) throw failure
                // roma-aac ended on its own, either at the end of the input or in a crash mid-song, which isn't the
                // song's end.
                if (!decoder.closed && process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() != 0) {
                    throw IOException("roma-aac stopped (exit ${process.exitValue()})")
                }
                return -1
            }
            var frames = n / (2 * channels)
            var from = 0
            if (drop > 0) {
                val k = minOf(drop, frames.toLong()).toInt()
                from = k
                drop -= k
            }
            if (from >= frames) continue
            for (i in from * channels until frames * channels) {
                // 16-bit little-endian samples.
                val low = bytes[2 * i].toInt() and 0xff
                val high = bytes[2 * i + 1].toInt() shl 8
                out[i - from * channels] = (high or low).toShort()
            }
            return frames - from
        }
    }

    // Reads whole frames only, and only once the first input frame has said how many samples to drop.
    private fun readFully(max: Int): Int {
        val frame = 2 * channels
        var got = 0
        while (got < frame || got % frame != 0) {
            val m = pcm.read(bytes, got, max - got)
            if (m < 0) break
            got += m
        }
        while (drop < 0 && decoder.failure == null && process.isAlive) Thread.sleep(1)
        return got - got % frame
    }

    override fun close() = decoder.close()

    companion object {
        /**
         * Finds roma-aac: in the package's resources, or where -Droma.aac points when run from Gradle. The packaging
         * drops its executable bit, and an installed copy belongs to the system, so a copy that can't run is copied
         * once into the cache and run from there.
         */
        fun decoderPath(): String {
            val exe = if (System.getProperty("os.name").startsWith("Windows")) "roma-aac.exe" else "roma-aac"
            val fromGradle = System.getProperty("roma.aac")
            if (fromGradle != null) return fromGradle
            val resources = System.getProperty("compose.application.resources.dir") ?: error("roma-aac is missing")
            val bundled = File(resources, exe)
            check(bundled.exists()) { "roma-aac is missing from $bundled" }
            if (bundled.canExecute()) return bundled.path
            val copy = File(Dirs.cache, exe)
            if (copy.length() != bundled.length()) bundled.copyTo(copy, overwrite = true)
            copy.setExecutable(true)
            return copy.path
        }
    }
}

/**
 * A file roma-aac reads itself: MP3, FLAC, Ogg Vorbis or Opus, WAV or ADTS AAC, which is what a Subsonic server sends
 * without transcoding. Its bytes go to stdin from the start, and a 16-bit WAV comes back at the file's own rate and
 * channels. A seek decodes from the start and drops the samples before [atMs], which takes milliseconds for a song.
 * The length comes from the song, not from here.
 */
class FilePcm(private val f: StreamFile, atMs: Long) : Pcm {
    @Volatile private var decoderClosed = false
    private val decoder = FedProcess(listOf(AacPcm.decoderPath(), "-hide_banner", "-loglevel", "error", "-i", "pipe:0",
        "-map", "0:a:0", "-map_metadata", "-1", "-bitexact", "-f", "wav", "-c:a", "pcm_s16le", "pipe:1"), "file in") { o ->
        val buf = ByteArray(64 * 1024)
        var pos = 0L
        while (true) {
            val n = f.read(pos, buf, 0, buf.size) { decoderClosed }
            if (n <= 0) break
            o.write(buf, 0, n)
            pos += n
        }
    }
    private val pcm = java.io.DataInputStream(decoder.process.inputStream.buffered(64 * 1024))
    override val sampleRate: Int
    override val channels: Int
    override val durationMs = -1L
    private var drop: Long
    private val bytes: ByteArray

    init {
        // The WAV header: "RIFF", the file's size and "WAVE" (12 bytes), then chunks up to "data", whose size is
        // unknown on a pipe, so it runs to the end.
        try {
            pcm.readFully(ByteArray(12))
            var rate = 0
            var ch = 0
            while (true) {
                val idBytes = ByteArray(4)
                pcm.readFully(idBytes)
                val id = idBytes.toString(Charsets.US_ASCII)
                // The chunk's size is an unsigned little-endian 32-bit number. An odd size is padded to even.
                val size = Integer.reverseBytes(pcm.readInt()).toLong() and 0xffffffffL
                if (id == "data") break
                val body = ByteArray(size.toInt() + (size and 1).toInt())
                pcm.readFully(body)
                if (id == "fmt ") {
                    // Little-endian: the format tag (2 bytes), the channels (2) and the sample rate (4).
                    ch = (body[2].toInt() and 0xff) or ((body[3].toInt() and 0xff) shl 8)
                    rate = java.nio.ByteBuffer.wrap(body, 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                }
            }
            check(rate > 0 && ch > 0) { "No audio format" }
            sampleRate = rate
            channels = ch
        } catch (e: java.io.EOFException) {
            close()
            throw decoder.failure ?: IOException("Not an audio stream ${BuildConfig.APP_NAME} can read")
        }
        drop = samples(atMs * 1000, sampleRate)
        bytes = ByteArray(PCM_READ_FRAMES * 2 * channels)
    }

    override fun read(out: ShortArray): Int {
        val frame = 2 * channels
        while (true) {
            var got = 0
            val max = minOf(bytes.size, out.size * 2) / frame * frame
            while (got < frame || got % frame != 0) {
                val m = pcm.read(bytes, got, max - got)
                if (m < 0) break
                got += m
            }
            val frames = got / frame
            if (frames == 0) {
                val failure = decoder.failure
                if (failure != null) throw failure
                val p = decoder.process
                if (!decoder.closed && p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() != 0) {
                    throw IOException("roma-aac stopped (exit ${p.exitValue()})")
                }
                return -1
            }
            val from = minOf(drop, frames.toLong()).toInt()
            drop -= from
            if (from >= frames) continue
            for (i in from * channels until frames * channels) {
                // 16-bit little-endian samples.
                val low = bytes[2 * i].toInt() and 0xff
                val high = bytes[2 * i + 1].toInt() shl 8
                out[i - from * channels] = (high or low).toShort()
            }
            return frames - from
        }
    }

    override fun close() {
        decoderClosed = true
        decoder.close()
    }
}

/** [us] microseconds at [rate], to the nearest sample. */
fun samples(us: Long, rate: Int) = Math.round(us * rate / 1e6)

/** The container's reader for WebM and MP4, or null for any other file. */
fun demuxer(f: StreamFile): Demuxer? {
    val head = Cursor(f, 0).bytes(8)
    // WebM starts with the EBML header's id, 1A 45 DF A3. MP4 starts with an ftyp box, its type after a 4-byte size.
    val webm = head[0] == 0x1A.toByte() && head[1] == 0x45.toByte()
    val mp4 = String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp"
    return when {
        webm -> WebmDemuxer(f)
        mp4 -> Mp4Demuxer(f)
        else -> null
    }
}

/**
 * WebM (Matroska) with one Opus track, as YouTube serves it, with no lacing and the Cues (the seek index) before the
 * clusters. An unknown size, as on a live stream's segment, runs to the end of the file.
 */
class WebmDemuxer(private val f: StreamFile) : Demuxer {
    private var c = Cursor(f, 0)
    private var segmentStart = 0L
    private var scale = 1_000_000L // nanoseconds per timecode unit
    override var durationMs = -1L
        private set
    override var sampleRate = 48_000
        private set
    override var channels = 2
        private set
    private var codecPrivate: ByteArray? = null
    private var codecDelayNs = 0L
    private val cues = ArrayList<Pair<Long, Long>>() // timecode to the cluster's position in the segment
    private var clusterTime = 0L
    private var clusterEnd = Long.MAX_VALUE
    private var firstCluster = 0L
    private var delayUs = 0L

    init {
        val (ebml, ebmlSize) = header()
        check(ebml == EBML) { "Not WebM" }
        c.pos += ebmlSize
        val (segment, segmentSize) = header()
        check(segment == SEGMENT) { "No segment" }
        segmentStart = c.pos
        val end = if (segmentSize < 0) Long.MAX_VALUE else c.pos + segmentSize
        while (c.pos < end) {
            val at = c.pos
            val (id, size) = header()
            when (id) {
                INFO -> info(c.pos + size)
                TRACKS -> tracks(c.pos + size)
                CUES -> cues(c.pos + size)
                CLUSTER -> {
                    firstCluster = at
                    c.pos = at
                    break
                }
                else -> c.pos += size
            }
        }
        checkNotNull(codecPrivate) { "No Opus track" }
        // Block times include the codec's delay: Matroska's CodecDelay, or else OpusHead's pre-skip at 48 kHz.
        val head = codecPrivate!!
        delayUs = if (codecDelayNs > 0) {
            codecDelayNs / 1000
        } else if (head.size >= 12) {
            // The pre-skip is a little-endian 16-bit sample count at byte 10 of OpusHead.
            val preSkip = (head[10].toInt() and 0xff) or ((head[11].toInt() and 0xff) shl 8)
            preSkip * 1_000_000L / 48_000
        } else {
            0
        }
    }

    // An element's id (with its length marker) and its size, which is -1 when unknown.
    private fun header(): Pair<Long, Long> {
        val id = vint(keepMarker = true)
        val size = vint(keepMarker = false)
        return id to size
    }

    // EBML's variable-length integer. The position of the first set bit in the first byte gives the length in bytes
    // (the length marker). A size whose value bits are all ones means "unknown".
    private fun vint(keepMarker: Boolean): Long {
        val first = c.u8()
        var len = 1
        var mask = 0x80
        while (first and mask == 0) {
            mask = mask shr 1
            len++
            if (len > 8) throw IOException("Bad WebM")
        }
        var v = (if (keepMarker) first else first and (mask - 1)).toLong()
        var allOnes = v == (mask - 1).toLong()
        repeat(len - 1) {
            val b = c.u8()
            v = (v shl 8) or b.toLong()
            if (b != 0xff) allOnes = false
        }
        return if (!keepMarker && allOnes) -1 else v
    }

    // A big-endian unsigned integer of [size] bytes.
    private fun uint(size: Long): Long {
        var v = 0L
        repeat(size.toInt()) { v = (v shl 8) or c.u8().toLong() }
        return v
    }

    private fun float(size: Long): Double {
        val b = c.bytes(size.toInt())
        val bb = java.nio.ByteBuffer.wrap(b)
        return if (size == 4L) bb.float.toDouble() else bb.double
    }

    private fun info(end: Long) {
        var duration = -1.0
        while (c.pos < end) {
            val (id, size) = header()
            when (id) {
                TIMECODE_SCALE -> scale = uint(size)
                DURATION -> duration = float(size)
                else -> c.pos += size
            }
        }
        if (duration > 0) durationMs = (duration * scale / 1_000_000).toLong()
    }

    private fun tracks(end: Long) {
        while (c.pos < end) {
            val (id, size) = header()
            if (id != TRACK_ENTRY) {
                c.pos += size
                continue
            }
            val entryEnd = c.pos + size
            var codec = ""
            while (c.pos < entryEnd) {
                val (eid, esize) = header()
                when (eid) {
                    CODEC_ID -> codec = String(c.bytes(esize.toInt()), Charsets.US_ASCII)
                    CODEC_PRIVATE -> codecPrivate = c.bytes(esize.toInt())
                    CODEC_DELAY -> codecDelayNs = uint(esize)
                    AUDIO -> {
                        val audioEnd = c.pos + esize
                        while (c.pos < audioEnd) {
                            val (aid, asize) = header()
                            when (aid) {
                                SAMPLING_FREQUENCY -> sampleRate = float(asize).toInt()
                                CHANNELS -> channels = uint(asize).toInt()
                                else -> c.pos += asize
                            }
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
            if (id != CUE_POINT) {
                c.pos += size
                continue
            }
            val pointEnd = c.pos + size
            var time = 0L
            var cluster = -1L
            while (c.pos < pointEnd) {
                val (pid, psize) = header()
                when (pid) {
                    CUE_TIME -> time = uint(psize)
                    CUE_TRACK_POSITIONS -> {
                        val posEnd = c.pos + psize
                        while (c.pos < posEnd) {
                            val (qid, qsize) = header()
                            if (qid == CUE_CLUSTER_POSITION) cluster = uint(qsize) else c.pos += qsize
                        }
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
            val (id, size) = try {
                header()
            } catch (_: java.io.EOFException) {
                return null
            }
            when (id) {
                // The cursor stays inside the cluster, so its children are read next.
                CLUSTER -> clusterEnd = if (size < 0) Long.MAX_VALUE else c.pos + size
                CLUSTER_TIMECODE -> clusterTime = uint(size)
                SIMPLE_BLOCK -> return block(size, 0)
                BLOCK_GROUP -> return group(c.pos + size)
                else -> c.pos += size
            }
        }
    }

    // A BlockGroup holds a Block, and the last one also holds the DiscardPadding in nanoseconds.
    private fun group(end: Long): Packet? {
        var at = -1L
        var size = 0L
        var padNs = 0L
        while (c.pos < end) {
            val (id, s) = header()
            when (id) {
                BLOCK -> {
                    at = c.pos
                    size = s
                    c.pos += s
                }
                DISCARD_PADDING -> padNs = uint(s)
                else -> c.pos += s
            }
        }
        if (at < 0) return next()
        c.pos = at
        val padSamples = (padNs * sampleRate / 1_000_000_000).toInt()
        val packet = block(size, padSamples)
        c.pos = end
        return packet
    }

    private fun block(size: Long, dropEnd: Int): Packet {
        val start = c.pos
        // The track number, skipped because there is only one track.
        vint(keepMarker = false)
        // The time relative to the cluster's, a signed big-endian 16-bit number.
        val high = c.u8()
        val low = c.u8()
        val rel = ((high shl 8) or low).toShort().toLong()
        val flags = c.u8()
        if (flags and LACING_FLAGS != 0) throw IOException("Laced WebM blocks aren't supported")
        val data = c.bytes((size - (c.pos - start)).toInt())
        val timeUs = (clusterTime + rel) * scale / 1000 - delayUs
        return Packet(data, timeUs, dropEnd)
    }

    override fun seek(ms: Long) {
        val tc = ms * 1_000_000 / scale
        val cue = cues.lastOrNull { it.first <= tc }
        c.pos = if (cue != null) segmentStart + cue.second else firstCluster
    }

    // Matroska element ids, with their length markers.
    private companion object {
        const val EBML = 0x1A45DFA3L
        const val SEGMENT = 0x18538067L
        const val INFO = 0x1549A966L
        const val TIMECODE_SCALE = 0x2AD7B1L
        const val DURATION = 0x4489L
        const val TRACKS = 0x1654AE6BL
        const val TRACK_ENTRY = 0xAEL
        const val CODEC_ID = 0x86L
        const val CODEC_PRIVATE = 0x63A2L
        const val CODEC_DELAY = 0x56AAL
        const val AUDIO = 0xE1L
        const val SAMPLING_FREQUENCY = 0xB5L
        const val CHANNELS = 0x9FL
        const val CUES = 0x1C53BB6BL
        const val CUE_POINT = 0xBBL
        const val CUE_TIME = 0xB3L
        const val CUE_TRACK_POSITIONS = 0xB7L
        const val CUE_CLUSTER_POSITION = 0xF1L
        const val CLUSTER = 0x1F43B675L
        const val CLUSTER_TIMECODE = 0xE7L
        const val SIMPLE_BLOCK = 0xA3L
        const val BLOCK_GROUP = 0xA0L
        const val BLOCK = 0xA1L
        const val DISCARD_PADDING = 0x75A2L

        /** A block header's lacing bits. */
        const val LACING_FLAGS = 0x06
    }
}

/**
 * Fragmented MP4 with one AAC track, as YouTube serves it. First comes moov, with the codec's config and the edit
 * list's lead-in, then sidx, with each fragment's size and length for seeking, then moof and mdat pairs of about 10 s.
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
    private var leadIn = 0L // the edit list's start, in the timescale
    private var defaultDuration = 1024L
    private var defaultSize = 0L
    private val fragments = ArrayList<Pair<Long, Long>>() // start time in the timescale to file position
    private val queue = ArrayDeque<Packet>()

    init {
        while (config == null || fragments.isEmpty() && !c.atEnd) {
            val at = c.pos
            val (type, size) = box()
            when (type) {
                "moov" -> moov(at + size)
                "sidx" -> sidx(at, at + size)
                "moof" -> {
                    c.pos = at
                    break
                }
                else -> c.pos = at + size
            }
        }
        checkNotNull(config) { "No AAC track" }
    }

    // A box's type and whole size, header included. The cursor is left after the header.
    private fun box(): Pair<String, Long> {
        var size = c.u32()
        val type = String(c.bytes(4), Charsets.ISO_8859_1)
        // Size 1 means a 64-bit size follows, and size 0 means the box runs to the end of the file.
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
                    val version = c.u8()
                    c.pos += 3 // flags
                    // The creation and modification times come first, then the timescale, then the duration.
                    if (version == 1) {
                        c.pos += 16
                        timescale = c.u32()
                        c.u64()
                    } else {
                        c.pos += 8
                        timescale = c.u32()
                        c.u32()
                    }
                }
                "elst" -> {
                    val version = c.u8()
                    c.pos += 3 // flags
                    val entries = c.u32()
                    // The first entry's segment duration, then its media time, which is where playback starts.
                    if (entries > 0) {
                        if (version == 1) {
                            c.u64()
                            leadIn = c.u64()
                        } else {
                            c.u32()
                            leadIn = c.u32().toInt().toLong() // a signed 32-bit number
                        }
                    }
                    if (leadIn < 0) leadIn = 0
                }
                "trex" -> {
                    c.pos += 4 + 4 + 4 // version and flags, track id, default sample description index
                    defaultDuration = c.u32()
                    defaultSize = c.u32()
                }
                "stsd" -> {
                    c.pos += 8 // version and flags, entry count
                    sampleEntry()
                }
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
        sampleRate = (c.u32() / 65536).toInt() // a 16.16 fixed-point number
        while (c.pos < at + size) {
            val bAt = c.pos
            val (t, s) = box()
            if (t == "esds") {
                c.pos += 4 // version and flags
                config = decoderSpecificInfo(bAt + s)
            }
            c.pos = bAt + s
        }
    }

    // The AudioSpecificConfig, found in ES_Descriptor > DecoderConfigDescriptor > DecoderSpecificInfo.
    private fun decoderSpecificInfo(end: Long): ByteArray? {
        while (c.pos < end) {
            val tag = c.u8()
            // The length takes up to 4 bytes of 7 bits each. Every byte but the last has its high bit set.
            var len = 0
            for (i in 0 until 4) {
                val b = c.u8()
                len = (len shl 7) or (b and 0x7f)
                if (b and 0x80 == 0) break
            }
            when (tag) {
                ES_DESCRIPTOR -> {
                    c.pos += 2 // ES_ID
                    val flags = c.u8()
                    if (flags and ES_DEPENDS_ON != 0) c.pos += 2
                    if (flags and ES_URL != 0) c.pos += c.u8()
                    if (flags and ES_OCR_STREAM != 0) c.pos += 2
                }
                // Its fixed fields: object type, stream type, buffer size, maximum and average bitrate.
                DECODER_CONFIG_DESCRIPTOR -> c.pos += 13
                DECODER_SPECIFIC_INFO -> return c.bytes(len)
                else -> c.pos += len
            }
        }
        return null
    }

    private fun sidx(at: Long, end: Long) {
        val version = c.u8()
        c.pos += 3 // flags
        c.pos += 4 // reference id
        val scale = c.u32()
        var time = if (version == 0) c.u32() else c.u64()
        val first = if (version == 0) c.u32() else c.u64()
        c.pos += 2 // reserved
        val countHigh = c.u8()
        val countLow = c.u8()
        val count = (countHigh shl 8) or countLow
        var pos = end + first
        var total = 0L
        repeat(count) {
            // The top bit is the reference type; the low 31 bits are the fragment's size.
            val size = c.u32() and 0x7fffffff
            val duration = c.u32()
            c.pos += 4 // stream access point fields
            fragments += time to pos
            time += duration
            total += duration
            pos += size
        }
        durationMs = (total - leadIn) * 1000 / scale
    }

    /** The AudioSpecificConfig's fields as an ADTS header wants them: profile, sampling frequency index, channels. */
    val adts: Triple<Int, Int, Int> get() {
        val asc = config!!
        // The first 16 bits: object type (5 bits), sampling frequency index (4), channel configuration (4), 3 more.
        val bits = ((asc[0].toInt() and 0xff) shl 8) or (asc.getOrElse(1) { 0 }.toInt() and 0xff)
        val type = bits shr 11
        val rate = (bits shr 7) and 0xf
        val ch = (bits shr 3) and 0xf
        // HE-AAC (types 5 and 29) carries an AAC-LC core. ADTS says LC, and the decoder finds SBR by itself. ADTS's
        // profile is the object type minus one.
        val profile = if (type == 5 || type == 29) 1 else type - 1
        val adtsChannels = if (ch == 0) channels else ch
        return Triple(profile, rate, adtsChannels)
    }

    override fun next(): Packet? {
        while (queue.isEmpty()) if (!fragment()) return null
        return queue.removeFirst()
    }

    // Reads the next moof and its samples, from the mdat after it, into the queue. Returns false at the end.
    private fun fragment(): Boolean {
        while (true) {
            if (c.atEnd) return false
            val at = c.pos
            val (type, size) = try {
                box()
            } catch (_: java.io.EOFException) {
                return false
            }
            if (type != "moof") {
                c.pos = at + size
                continue
            }
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
                            val flags = c.u32() and FLAGS_MASK
                            c.pos += 4 // track id
                            if (flags and TFHD_BASE_DATA_OFFSET != 0L) c.pos += 8
                            if (flags and TFHD_SAMPLE_DESCRIPTION_INDEX != 0L) c.pos += 4
                            if (flags and TFHD_DEFAULT_SAMPLE_DURATION != 0L) sampleDuration = c.u32()
                            if (flags and TFHD_DEFAULT_SAMPLE_SIZE != 0L) sampleSize = c.u32()
                        }
                        "tfdt" -> {
                            val version = c.u8()
                            c.pos += 3 // flags
                            base = if (version == 1) c.u64() else c.u32()
                        }
                        "trun" -> {
                            val flags = c.u32() and FLAGS_MASK
                            val n = c.u32().toInt()
                            if (flags and TRUN_DATA_OFFSET != 0L) dataOffset = c.u32().toInt().toLong() // signed
                            if (flags and TRUN_FIRST_SAMPLE_FLAGS != 0L) c.pos += 4
                            sizes = LongArray(n)
                            durations = LongArray(n)
                            for (i in 0 until n) {
                                durations[i] = if (flags and TRUN_SAMPLE_DURATION != 0L) c.u32() else sampleDuration
                                sizes[i] = if (flags and TRUN_SAMPLE_SIZE != 0L) c.u32() else sampleSize
                                if (flags and TRUN_SAMPLE_FLAGS != 0L) c.pos += 4
                                if (flags and TRUN_SAMPLE_COMPOSITION_OFFSET != 0L) c.pos += 4
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
                val data = c.bytes(sizes[i].toInt())
                val timeUs = (t - leadIn) * 1_000_000 / timescale
                queue.addLast(Packet(data, timeUs))
                t += durations[i]
            }
            return true
        }
    }

    override fun seek(ms: Long) {
        queue.clear()
        val t = ms * timescale / 1000 + leadIn
        val fragment = fragments.lastOrNull { it.first <= t } ?: fragments.firstOrNull()
        if (fragment != null) c.pos = fragment.second
    }

    private companion object {
        // A full box's first 32 bits are its version (8 bits) and then its flags (24 bits).
        const val FLAGS_MASK = 0xffffffL

        const val TFHD_BASE_DATA_OFFSET = 0x1L
        const val TFHD_SAMPLE_DESCRIPTION_INDEX = 0x2L
        const val TFHD_DEFAULT_SAMPLE_DURATION = 0x8L
        const val TFHD_DEFAULT_SAMPLE_SIZE = 0x10L

        const val TRUN_DATA_OFFSET = 0x1L
        const val TRUN_FIRST_SAMPLE_FLAGS = 0x4L
        const val TRUN_SAMPLE_DURATION = 0x100L
        const val TRUN_SAMPLE_SIZE = 0x200L
        const val TRUN_SAMPLE_FLAGS = 0x400L
        const val TRUN_SAMPLE_COMPOSITION_OFFSET = 0x800L

        // MPEG-4 descriptor tags, and the ES_Descriptor's flags for optional fields.
        const val ES_DESCRIPTOR = 0x03
        const val DECODER_CONFIG_DESCRIPTOR = 0x04
        const val DECODER_SPECIFIC_INFO = 0x05
        const val ES_DEPENDS_ON = 0x80
        const val ES_URL = 0x40
        const val ES_OCR_STREAM = 0x20
    }
}

/** Opus through Concentus. */
class OpusDecoderOf(private val channels: Int) : Decoder {
    private val d = io.github.jaredmdobson.concentus.OpusDecoder(48_000, channels)
    override fun decode(p: Packet, out: ShortArray) = d.decode(p.data, 0, p.data.size, out, 0, out.size / channels, false)
}
