package com.zakius.roma

import java.io.File

/** The SNR below which roma's decoding counts as different from ffmpeg's. */
private const val MIN_SNR_DB = 40

/**
 * Run with `./gradlew :desktop:decodeCheck -Pfiles=a.webm,b.m4a,c.mp3,d.flac`. It decodes each file the way the player
 * does (Media.kt's pcm), once from the start and once from 60 s, and compares the PCM with ffmpeg's output by sample
 * count and signal-to-noise ratio. ffmpeg seeks after decoding (-ss after -i), because that is exact for every format,
 * and -ss before -i is not exact for MP3. Needs ffmpeg on the PATH.
 */
fun main(args: Array<String>) {
    var ok = true
    // Store.cache is only used here for the audio cache's folder.
    Store.cache = kotlin.io.path.createTempDirectory("decode-check").toFile()
    for (path in args) {
        val src = File(path)
        // The extension goes into the id so that a.mp3 and a.flac don't share a cache file.
        val id = "check-" + src.name.replace('.', '-')
        StreamFile.dir.mkdirs()
        src.copyTo(File(StreamFile.dir, id), true)
        File(StreamFile.dir, "$id.len").writeText("${src.length()}")
        for (at in listOf(0L, 60_000L)) {
            val ours = java.io.ByteArrayOutputStream()
            val (rate, channels) = pcm(StreamFile.of(id), at).use { p ->
                val out = ShortArray(MAX_PACKET_SAMPLES * p.channels)
                while (true) {
                    val n = p.read(out)
                    if (n < 0) break
                    for (i in 0 until n * p.channels) {
                        // 16-bit little-endian, as ffmpeg's s16le.
                        val sample = out[i].toInt()
                        ours.write(sample and 0xff)
                        ours.write(sample shr 8 and 0xff)
                    }
                }
                p.sampleRate to p.channels
            }
            val command = ArrayList<String>()
            command += listOf("ffmpeg", "-v", "error", "-i", path)
            if (at > 0) command += listOf("-ss", "${at / 1000.0}")
            command += listOf("-f", "s16le", "-ac", "$channels", "-ar", "$rate", "-")
            val ref = ProcessBuilder(command).start().inputStream.readBytes()
            val a = ours.toByteArray()
            // -Ddump=<dir> keeps roma's PCM for a closer look.
            val dumpDir = System.getProperty("dump")
            if (at == 0L && dumpDir != null) File(dumpDir, src.name + ".roma.raw").writeBytes(a)

            // Sample i of a 16-bit little-endian PCM buffer.
            fun s(b: ByteArray, i: Int): Double {
                val low = b[2 * i].toInt() and 0xff
                val high = b[2 * i + 1].toInt() shl 8
                return (high or low).toDouble()
            }
            val n = minOf(a.size, ref.size) / 2
            var sig = 0.0
            var noise = 0.0
            for (i in 0 until n) {
                val r = s(ref, i)
                val d = s(a, i) - r
                sig += r * r
                noise += d * d
            }
            val snr = 10 * Math.log10(sig / maxOf(noise, 1e-9))
            val diff = (a.size - ref.size) / 2 / channels
            val ourSamples = a.size / 2 / channels
            val ffmpegSamples = ref.size / 2 / channels
            println("${src.name} from ${at / 1000} s: $rate Hz $channels ch, roma $ourSamples samples, ffmpeg $ffmpegSamples (${"%+d".format(diff)}), SNR %.1f dB".format(snr))
            val maxDiff = rate / 50 // 20 ms of samples
            if (snr < MIN_SNR_DB || kotlin.math.abs(diff) > maxDiff) ok = false
        }
        File(StreamFile.dir, id).delete()
        File(StreamFile.dir, "$id.len").delete()
    }
    check(ok) { "roma's decoding differs from ffmpeg's" }
}
