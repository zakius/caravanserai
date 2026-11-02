package com.zakius.roma

import java.io.File

// ./gradlew :desktop:decodeCheck -Pfiles=a.webm,b.m4a,c.mp3,d.flac: decodes each file the way the player does (Media.kt's pcm),
// from the start and from 60 s, and compares the PCM with ffmpeg's (seeking after decoding, which is exact for every
// format, where -ss before -i isn't for MP3): the sample count and the signal-to-noise ratio.
// Needs ffmpeg on the PATH.
fun main(args: Array<String>) {
    var ok = true
    Store.cache = kotlin.io.path.createTempDirectory("decode-check").toFile() // the audio cache's folder, nothing else
    for (path in args) {
        val src = File(path)
        val id = "check-" + src.name.replace('.', '-') // a.mp3 and a.flac apart
        StreamFile.dir.mkdirs()
        src.copyTo(File(StreamFile.dir, id), true)
        File(StreamFile.dir, "$id.len").writeText("${src.length()}")
        for (at in listOf(0L, 60_000L)) {
            val ours = java.io.ByteArrayOutputStream()
            val (rate, channels) = pcm(StreamFile.of(id), at).use { p ->
                val out = ShortArray(5760 * p.channels)
                while (true) {
                    val n = p.read(out)
                    if (n < 0) break
                    for (i in 0 until n * p.channels) { ours.write(out[i].toInt() and 0xff); ours.write(out[i].toInt() shr 8 and 0xff) }
                }
                p.sampleRate to p.channels
            }
            val ref = ProcessBuilder(listOf("ffmpeg", "-v", "error", "-i", path) + (if (at > 0) listOf("-ss", "${at / 1000.0}") else emptyList()) + listOf("-f", "s16le", "-ac", "$channels", "-ar", "$rate", "-")).start().inputStream.readBytes()
            val a = ours.toByteArray()
            if (at == 0L) System.getProperty("dump")?.let { File(it, src.name + ".roma.raw").writeBytes(a) } // for a closer look
            fun s(b: ByteArray, i: Int) = ((b[2 * i + 1].toInt() shl 8) or (b[2 * i].toInt() and 0xff)).toDouble()
            val n = minOf(a.size, ref.size) / 2
            var sig = 0.0; var noise = 0.0
            for (i in 0 until n) { val r = s(ref, i); val d = s(a, i) - r; sig += r * r; noise += d * d }
            val snr = 10 * Math.log10(sig / maxOf(noise, 1e-9))
            val diff = (a.size - ref.size) / 2 / channels
            println("${src.name} from ${at / 1000} s: $rate Hz $channels ch, roma ${a.size / 2 / channels} samples, ffmpeg ${ref.size / 2 / channels} (${"%+d".format(diff)}), SNR %.1f dB".format(snr))
            if (snr < 40 || kotlin.math.abs(diff) > rate / 50) ok = false // 20 ms
        }
        File(StreamFile.dir, id).delete(); File(StreamFile.dir, "$id.len").delete()
    }
    check(ok) { "roma's decoding differs from ffmpeg's" }
}
