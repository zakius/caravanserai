import com.worksap.nlp.sudachi.dictionary.Block
import com.worksap.nlp.sudachi.dictionary.CompactedStrings
import com.worksap.nlp.sudachi.dictionary.Description
import com.worksap.nlp.sudachi.dictionary.DictionaryPrinter
import com.worksap.nlp.sudachi.dictionary.WordInfo
import com.worksap.nlp.sudachi.dictionary.WordParameters
import com.worksap.nlp.sudachi.dictionary.build.DicBuilder
import java.io.File
import java.io.PrintStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.time.Instant

// Sudachi's word entries are 32 bytes; roma's are 12 (layout in :core's RomaDictionary.kt).
private const val SUDACHI_ENTRY_BYTES = 32
private const val ROMA_ENTRY_BYTES = 12

// A roma entry's long: the params index in the low 14 bits, then the cost, then the headword's string reference.
private const val PARAMS_INDEX_BITS = 14
private const val COST_SHIFT = 14
private const val HEADWORD_SHIFT = 30

// A string reference: bit 31 set for the kana pool, the length from bit 23, the offset below it.
private const val KANA_FLAG = 1 shl 31
private const val REF_LENGTH_SHIFT = 23

// Kana-pool strings store each character as its distance from this one.
private const val KATAKANA_BASE = '\u30a0'

/*
 * SudachiDict core is a dictionary for search engines; roma only needs a word's reading, part of speech and
 * dictionary form, in split mode C. This rebuilds it with Sudachi's own builder, minus what roma never reads, and
 * checks nothing else changes (docs/design.md, "Slim dictionary"):
 *
 * - Postal addresses go: compound place names (they have split data) of 5+ characters that contain an
 *   administrative unit (都道府県市区町村郡), such as 京都府京都市上京区菊屋町. Short or famous ones (富士山, 東京都) stay.
 * - Split data (A, B, word structure), synonym groups and normalized forms are cleared: they serve split modes A and
 *   B and search. Dictionary forms stay (the word card).
 * - Words that never win go: another word of the same text costs less in every context.
 * - The connection cost matrix keeps the ids in use, one copy of each distinct row and column. Ids are renumbered,
 *   so the unknown-word definitions and the config's ids are too; the class of id 0 stays 0 (sentence start and end).
 * - Word entries and strings are written in roma's format (compact, read by :core's RomaDictionary.kt).
 *
 * Usage: Slim <system_core.dic> <out dir> <version>. Writes system_core.dic, roma-sudachi.json and roma-unk.def.
 */
fun main(args: Array<String>) {
    val (source, outDir) = args.take(2).map(::File)
    val version = args[2]
    outDir.mkdirs()
    val work = File(outDir, "work").apply { mkdirs() }

    // The matrix is m[a][b], with a the slow index (left ids) and b the fast one (right ids).
    val (n, matrix) = FileChannel.open(source.toPath(), READ).use { ch ->
        val all = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()).order(ByteOrder.LITTLE_ENDIAN)
        // duplicate() forgets the byte order.
        val le = { all.duplicate().order(ByteOrder.LITTLE_ENDIAN) }
        val block = Description.load(le()).slice(le(), "ConnMatrix").order(ByteOrder.LITTLE_ENDIAN)
        val rows = block.getShort(0).toInt()
        val cols = block.getShort(2).toInt()
        check(rows == cols) { "matrix $rows x $cols" }
        val costs = Array(rows) { a ->
            ShortArray(cols) { b -> block.getShort(4 + (a * cols + b) * 2) }
        }
        rows to costs
    }

    // The lexicon comes from Sudachi's own export, minus postal addresses.
    val export = File(work, "lexicon-full.csv")
    val stdout = System.out
    PrintStream(export.outputStream().buffered(), false, "UTF-8").use { exportStream ->
        System.setOut(exportStream)
        DictionaryPrinter.main(arrayOf(source.path))
    }
    System.setOut(stdout)
    val admin = Regex("[都道府県市区町村郡]")
    val (header, rows) = export.bufferedReader().use { input ->
        val headerLine = parseCsv(input.readLine())
        val dataLines = input.lineSequence().map(::parseCsv).toList()
        headerLine to dataLines
    }
    export.delete()
    val ix = header.withIndex().associate { it.value to it.index }
    fun col(name: String) = ix[name] ?: error("no $name column")
    val leftId = col("LEFT_ID")
    val rightId = col("RIGHT_ID")
    val costCol = col("COST")
    val pos3 = col("POS3")
    val pos4 = col("POS4")
    val splitA = col("SPLIT_A")
    val structure = col("WORD_STRUCTURE")
    val headword = col("HEADWORD")
    val dictionaryForm = col("DICTIONARY_FORM")
    val keyCols = listOf("POS1", "POS2", "POS3", "POS4", "POS5", "POS6", "READING_FORM").map(::col)
    val cleared = listOf("NORMALIZED_FORM", "SPLIT_A", "SPLIT_B", "SPLIT_C", "WORD_STRUCTURE", "SYNONYM_GROUPS").map(::col)
    val words = rows.filterNot { row ->
        val compound = row[splitA].isNotEmpty() || row[structure].isNotEmpty()
        compound && row[pos3] == "地名" && row[pos4] == "一般" && row[0].length >= 5 && admin.containsMatchIn(row[0])
    }
    val addresses = rows.size - words.size

    // The ids that the unknown-word definitions and the config use.
    val loader = DicBuilder::class.java.classLoader
    val unk = loader.getResourceAsStream("unk.def")!!.bufferedReader().readLines()
    val oovPlugin = "\"class\" : \"com.worksap.nlp.sudachi.MeCabOovProviderPlugin\" }"
    val oovPluginWithUnkDef = "\"class\" : \"com.worksap.nlp.sudachi.MeCabOovProviderPlugin\", \"unkDef\" : \"roma-unk.def\" }"
    var config = loader.getResourceAsStream("sudachi.json")!!.bufferedReader().readText()
    config = config.replace(oovPlugin, oovPluginWithUnkDef)
    check("roma-unk.def" in config) { "sudachi.json changed: MeCabOovProviderPlugin entry not found" }
    val configIds = Regex("\"(leftId|rightId)\"\\s*:\\s*(\\d+)")
    // Id 0 is the sentence start and end.
    val oovLeft = sortedSetOf(0)
    val oovRight = sortedSetOf(0)
    for (line in unk) {
        val fields = line.split(',')
        if (fields.size > 3) {
            oovLeft += fields[1].toInt()
            oovRight += fields[2].toInt()
        }
    }
    for (m in configIds.findAll(config)) {
        val id = m.groupValues[2].toInt()
        if (m.groupValues[1] == "leftId") {
            oovLeft += id
        } else {
            oovRight += id
        }
    }
    fun used(words: List<List<String>>, ids: Int, oov: Set<Int>): IntArray {
        val wordIds = words.map { it[ids].toInt() }.filter { it >= 0 }
        return (wordIds + oov).toSortedSet().toIntArray()
    }

    // A word is never on the best path when another word of the same text costs less in every context: its cost plus
    // the most its connections can cost more on either side (over the ids in use). Both start and end at the same
    // place, so the unknown-word rules, which only ask whether a word starts there, see no change.
    val wordLeft = used(words, leftId, oovLeft)
    val wordRight = used(words, rightId, oovRight)
    // One cache for both gaps. The key packs a and b into 16 bits each, with bit 40 set for right gaps.
    val gaps = HashMap<Long, Int>()
    fun leftGap(a: Int, b: Int): Int {
        if (a == b) return 0
        val cacheKey = (a.toLong() shl 16) or b.toLong()
        return gaps.getOrPut(cacheKey) { wordRight.maxOf { p -> matrix[a][p] - matrix[b][p] } }
    }
    fun rightGap(a: Int, b: Int): Int {
        if (a == b) return 0
        val cacheKey = (1L shl 40) or (a.toLong() shl 16) or b.toLong()
        return gaps.getOrPut(cacheKey) { wordLeft.maxOf { s -> matrix[s][a] - matrix[s][b] } }
    }
    // Dictionary forms refer to words by headword, part of speech and reading, so the words they refer to stay.
    fun key(row: List<String>): String {
        val name = row[headword].ifEmpty { row[0] }
        return (listOf(name) + keyCols.map { row[it] }).joinToString(",")
    }
    val referenced = HashSet<String>()
    for (row in words) {
        val form = row[dictionaryForm]
        // The first 8 fields are the ones key() joins: headword, part of speech and reading.
        if (form.isNotEmpty()) referenced += parseCsv(form).take(8).joinToString(",")
    }
    val beaten = HashSet<List<String>>()
    val byText = words.filter { it[leftId].toInt() >= 0 }.groupBy { it[0] }
    for (same in byText.values) {
        for (w in same) {
            val (l, r, c) = listOf(leftId, rightId, costCol).map { w[it].toInt() }
            val cheaperEverywhere = same.any { o ->
                if (o === w) {
                    false
                } else {
                    val worstCost = o[costCol].toInt() + leftGap(o[leftId].toInt(), l) + rightGap(o[rightId].toInt(), r)
                    worstCost < c
                }
            }
            if (cheaperEverywhere && key(w) !in referenced) beaten += w
        }
    }
    val kept = words.filter { it !in beaten }

    // The matrix over the ids still in use keeps one copy of each distinct row and column.
    val usedLeft = used(kept, leftId, oovLeft)
    val usedRight = used(kept, rightId, oovRight)
    val rowClass = classes(n, usedLeft) { a -> ShortArray(usedRight.size) { i -> matrix[a][usedRight[i]] } }
    val colClass = classes(n, usedRight) { b -> ShortArray(usedLeft.size) { i -> matrix[usedLeft[i]][b] } }
    val rowOf = IntArray(rowClass.count)
    for (a in usedLeft) rowOf[rowClass.of[a]] = a
    val colOf = IntArray(colClass.count)
    for (b in usedRight) colOf[colClass.of[b]] = b
    val matrixFile = File(work, "matrix.def")
    // Square, because Sudachi's builder checks a word's left id against the size its lookups use for right ids.
    val size = maxOf(rowClass.count, colClass.count)
    matrixFile.bufferedWriter().use { w ->
        w.write("$size $size\n")
        for (a in 0 until size) {
            for (b in 0 until size) {
                val cost = if (a < rowClass.count && b < colClass.count) matrix[rowOf[a]][colOf[b]] else 0
                w.write("$b $a $cost\n")
            }
        }
    }
    fun left(id: Int): Int {
        if (id < 0) return id
        val row = rowClass.of[id]
        check(row >= 0) { "left id $id has no row" }
        return row
    }
    fun right(id: Int): Int {
        if (id < 0) return id
        val column = colClass.of[id]
        check(column >= 0) { "right id $id has no column" }
        return column
    }

    val lexicon = File(work, "lexicon.csv")
    lexicon.bufferedWriter().use { out ->
        out.write(header.joinToString(",") + "\n")
        for (word in kept) {
            val row = word.toMutableList()
            cleared.forEach { row[it] = "" }
            row[leftId] = left(row[leftId].toInt()).toString()
            row[rightId] = right(row[rightId].toInt()).toString()
            out.write(row.joinToString(",", transform = ::csvField) + "\n")
        }
    }
    println("Slim: ${kept.size} entries, $addresses addresses and ${beaten.size} words that never win dropped, matrix ${n}x$n -> ${size}x$size")

    // The unknown-word definitions and the config get the new ids.
    File(outDir, "roma-unk.def").bufferedWriter().use { w ->
        for (line in unk) {
            val c = line.split(',').toMutableList()
            if (c.size > 3) {
                c[1] = left(c[1].toInt()).toString()
                c[2] = right(c[2].toInt()).toString()
            }
            w.write(c.joinToString(",") + "\n")
        }
    }
    config = configIds.replace(config) { m ->
        val name = m.groupValues[1]
        val id = m.groupValues[2].toInt()
        val newId = if (name == "leftId") left(id) else right(id)
        "\"$name\" : $newId"
    }
    File(outDir, "roma-sudachi.json").writeText(config)

    // Build with Sudachi's builder, then compact the entries.
    val built = File(work, "system_core.dic")
    FileChannel.open(built.toPath(), CREATE, TRUNCATE_EXISTING, WRITE, READ).use { ch ->
        DicBuilder.system()
            .matrix(matrixFile.toPath())
            .signature("roma-slim-$version")
            .lexicon(lexicon.toPath())
            .comment("SudachiDict core $version, slimmed by roma")
            .creationTime(Instant.EPOCH)
            .build(ch)
    }
    val out = File(outDir, "system_core.dic")
    compact(built, out)
    work.deleteRecursively()
    println("Slim: ${source.length()} -> ${out.length()} bytes")
}

/**
 * Sudachi's 32-byte word entries, its strings and its matrix in roma's format: 12-byte entries, katakana a byte per
 * character, the matrix in shared pieces. The layout is in :core's RomaDictionary.kt and RomaLattice.java, which
 * read it. Entries, Strings and ConnMatrix are left empty; every other block is copied as it is.
 */
private fun compact(source: File, out: File) = FileChannel.open(source.toPath(), READ).use { ch ->
    val all = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()).order(ByteOrder.LITTLE_ENDIAN)
    val description = Description.load(all.duplicate().order(ByteOrder.LITTLE_ENDIAN))
    val old = description.slice(all, Block.ENTRIES)
    val oldStrings = description.slice(all, Block.STRINGS)
    val strings = CompactedStrings(oldStrings.asCharBuffer())
    val n = old.limit() / SUDACHI_ENTRY_BYTES
    check(old.limit() == n * SUDACHI_ENTRY_BYTES) { "Entries: ${old.limit()} bytes, not 32 per word" }
    val packed = LongArray(n) { k -> old.getLong(SUDACHI_ENTRY_BYTES * k) }
    val words = Array(n) { k -> WordInfo.read(old, SUDACHI_ENTRY_BYTES * k) }
    val headwords = Array(n) { k -> strings.string(words[k].headword) }
    val readings = Array(n) { k -> strings.string(words[k].readingForm) }
    for ((k, w) in words.withIndex()) {
        val splits = listOf(w.aunitSplit, w.bunitSplit, w.cunitSplit, w.wordStructure, w.synonymGroupIds)
        val empty = splits.all { it.isEmpty() } && w.userData.isEmpty()
        check(empty && w.normalizedForm == 4 * k) { "entry $k: split, synonym, user data or normalized form left" }
        check(WordParameters.cost(packed[k]) != Short.MIN_VALUE) { "entry $k: runtime cost" }
        check(w.dictionaryForm % 4 == 0) { "entry $k: dictionary form ${w.dictionaryForm}" }
    }

    // Katakana-only strings go in the kana pool, a byte per character; the others in the UTF-16 text pool.
    val kana = Pool()
    val text = Pool()
    fun isKana(s: String) = s.isNotEmpty() && s.all { it in '\u30a0'..'\u30ff' }
    val distinct = (headwords + readings).toSet()
    kana.layout(distinct.filter(::isKana))
    text.layout(distinct.filterNot(::isKana))
    fun ref(s: String): Int {
        val (pool, flag) = if (isKana(s)) kana to KANA_FLAG else text to 0
        val offset = pool.offset(s)
        check(s.length < 256 && offset < (1 shl REF_LENGTH_SHIFT)) { "string too long or pool too big: $s" }
        return flag or (s.length shl REF_LENGTH_SHIFT) or offset
    }

    // The entries, and the dictionary forms of the words whose form is another word.
    val entries = ByteBuffer.allocate(n * ROMA_ENTRY_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    // Maps (left, right, part of speech) to its index in RomaParams.
    val params = LinkedHashMap<Long, Int>()
    val groups = (n + 63) / 64
    val bits = LongArray(groups)
    val targets = ArrayList<Int>()
    val before = IntArray(groups)
    for ((k, w) in words.withIndex()) {
        if (k % 64 == 0) before[k / 64] = targets.size
        if (w.dictionaryForm != 4 * k) {
            bits[k / 64] = bits[k / 64] or (1L shl (k % 64))
            targets += w.dictionaryForm / 4
        }
        val leftAndRight = packed[k] and 0xffffffffL
        val partOfSpeech = (w.posId.toLong() and 0xffff) shl 32
        val param = params.getOrPut(leftAndRight or partOfSpeech) { params.size }
        check(param < (1 shl PARAMS_INDEX_BITS)) { "more than 16384 (left, right, part of speech)" }
        val cost = WordParameters.cost(packed[k]).toLong() and 0xffff
        val headwordRef = ref(headwords[k]).toLong() and 0xffffffffL
        val entry = param.toLong() or (cost shl COST_SHIFT) or (headwordRef shl HEADWORD_SHIFT)
        entries.putLong(entry)
        entries.putInt(ref(readings[k]))
    }
    val table = ByteBuffer.allocate(params.size * 6).order(ByteOrder.LITTLE_ENDIAN)
    for (p in params.keys) {
        table.putShort(p.toShort()) // left id
        table.putShort((p shr 16).toShort()) // right id
        table.putShort((p shr 32).toShort()) // part of speech
    }
    val forms = ByteBuffer.allocate(8 + groups * 12 + targets.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    forms.putInt(groups)
    forms.putInt(0)
    bits.forEach { forms.putLong(it) }
    before.forEach { forms.putInt(it) }
    targets.forEach { forms.putInt(it) }
    val kanaBytes = ByteBuffer.wrap(ByteArray(kana.text.length) { i -> (kana.text[i] - KATAKANA_BASE).toByte() })
    val textChars = ByteBuffer.allocate(text.text.length * 2).order(ByteOrder.LITTLE_ENDIAN)
    for (c in text.text) textChars.putChar(c)

    val matrix = matrix(description.slice(all, Block.CONNECTION_MATRIX))

    FileChannel.open(out.toPath(), CREATE, TRUNCATE_EXISTING, WRITE).use { w ->
        val blocks = ArrayList<Description.BlockInfo>()
        // The description goes first, in the space left here.
        w.position(4096)
        fun put(name: String, data: ByteBuffer) {
            // Each block starts on a 64-byte boundary.
            w.position((w.position() + 63) / 64 * 64)
            val start = w.position()
            blocks.add(Description.BlockInfo(name, start, data.remaining().toLong()))
            while (data.hasRemaining()) w.write(data)
        }
        // Sudachi's grammar needs a matrix block to load, so it gets an empty one (0 x 0).
        val replaced = mapOf(
            Block.ENTRIES to ByteBuffer.allocate(0),
            Block.STRINGS to ByteBuffer.allocate(0),
            Block.CONNECTION_MATRIX to ByteBuffer.allocate(4),
        )
        for (b in description.blocks) put(b.name, replaced[b.name] ?: description.slice(all, b.name))
        put("RomaMatrix", matrix)
        put("RomaEntries", entries.flip())
        put("RomaParams", table.flip())
        put("RomaForms", forms.flip())
        put("RomaKana", kanaBytes)
        put("RomaText", textChars.flip())
        description.blocks = blocks
        description.save(w)
    }
    val oldBytes = old.limit() + oldStrings.limit()
    val entryBytes = n * ROMA_ENTRY_BYTES + table.limit() + forms.limit()
    val stringBytes = kanaBytes.limit() + textChars.limit()
    println("Slim: entries and strings $oldBytes -> $entryBytes + $stringBytes bytes, ${params.size} (left, right, part of speech), ${targets.size} other dictionary forms")
}

/**
 * Sudachi's connection matrix as roma's (RomaLattice.java in :core reads it): each row cut into pieces of 16 costs,
 * each piece its lowest cost plus a pattern of 16 unsigned shorts, and equal patterns stored once. Three ints come
 * first: the number of left ids (a row's length), of right ids (rows) and of patterns. Then, per row and piece, the
 * pattern's index (an int) and the lowest cost (a short), and then the patterns. A row is the left id of the word
 * after, a column the right id of the word before, as in Sudachi.
 */
private fun matrix(block: ByteBuffer): ByteBuffer {
    val left = block.getShort(0).toInt()
    val right = block.getShort(2).toInt()
    val pieces = (left + 15) / 16
    val patterns = LinkedHashMap<Line, Int>()
    val index = IntArray(right * pieces)
    val lowest = ShortArray(right * pieces)
    for (r in 0 until right) {
        for (p in 0 until pieces) {
            val costs = IntArray(16) { i ->
                val b = p * 16 + i
                if (b < left) block.getShort(4 + (r * left + b) * 2).toInt() else 0
            }
            val low = costs.min()
            val pattern = Line(ShortArray(16) { i -> (costs[i] - low).toShort() })
            index[r * pieces + p] = patterns.getOrPut(pattern) { patterns.size }
            lowest[r * pieces + p] = low.toShort()
        }
    }
    val out = ByteBuffer.allocate(12 + index.size * 6 + patterns.size * 32).order(ByteOrder.LITTLE_ENDIAN)
    out.putInt(left).putInt(right).putInt(patterns.size)
    index.forEach { out.putInt(it) }
    lowest.forEach { out.putShort(it) }
    for (p in patterns.keys) p.v.forEach { out.putShort(it) }
    println("Slim: matrix ${block.limit()} -> ${out.limit()} bytes, ${patterns.size} patterns")
    return out.flip()
}

/** Strings laid out like Sudachi's StringStorage: longest first; one inside a string laid out before points into it. */
private class Pool {
    val text = StringBuilder()
    private val at = HashMap<String, Int>()

    fun layout(strings: Collection<String>) {
        val needed = strings.toHashSet()
        for (s in strings.sortedWith(compareByDescending<String> { it.length }.thenBy { it })) {
            if (s in at) continue
            val start = text.length
            text.append(s)
            for (i in s.indices) {
                for (j in i + 1..s.length) {
                    val sub = s.substring(i, j)
                    if (sub in needed) at.putIfAbsent(sub, start + i)
                }
            }
        }
        at.putIfAbsent("", 0)
    }

    fun offset(s: String) = at.getValue(s)
}

/** Equal rows (or columns) of the ids in use share a class; the class of id 0 is 0. Ids not in use have none (-1). */
private class Classes(val of: IntArray, val count: Int)

private class Line(val v: ShortArray) {
    override fun equals(other: Any?) = other is Line && v.contentEquals(other.v)
    override fun hashCode() = v.contentHashCode()
}

private fun classes(n: Int, ids: IntArray, line: (Int) -> ShortArray): Classes {
    check(ids.first() == 0)
    val seen = HashMap<Line, Int>()
    val of = IntArray(n) { -1 }
    for (i in ids) of[i] = seen.getOrPut(Line(line(i))) { seen.size }
    return Classes(of, seen.size)
}

private fun csvField(s: String): String {
    val needsQuotes = s.any { it == ',' || it == '"' || it == '\n' }
    if (!needsQuotes) return s
    return "\"" + s.replace("\"", "\"\"") + "\""
}

private fun parseCsv(line: String): List<String> {
    val out = ArrayList<String>()
    val cur = StringBuilder()
    var quoted = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                cur.append('"')
                i++
            }
            c == '"' -> quoted = !quoted
            c == ',' && !quoted -> {
                out.add(cur.toString())
                cur.setLength(0)
            }
            else -> cur.append(c)
        }
        i++
    }
    out.add(cur.toString())
    return out
}
