package com.worksap.nlp.sudachi.dictionary

import com.worksap.nlp.dartsclone.DoubleArray
import com.worksap.nlp.sudachi.Config
import com.worksap.nlp.sudachi.Dictionary
import com.worksap.nlp.sudachi.RomaLattice
import com.worksap.nlp.sudachi.Tokenizer
import com.worksap.nlp.sudachi.WordId
import java.nio.ByteBuffer
import java.nio.CharBuffer

/*
 * roma's dictionary (:dict-tool, docs/design.md "Slim dictionary"): Sudachi's format, with each word's 32-byte entry
 * cut to the 12 bytes that split mode C reads and roma's own strings. It lives in Sudachi's package for
 * WordInfoList's and WordIdTable's package-private constructors. Word ids are Sudachi's (4 × the entry's index), so
 * the trie and word id table are Sudachi's own blocks.
 *
 * RomaEntries, 12 bytes per word id / 4: a long of
 *   bits 0-13   index into RomaParams
 *   bits 14-29  cost
 *   bits 30-61  headword (a string reference)
 * then an int, the reading (a string reference).
 * RomaParams: (left id, right id, part of speech) as three shorts, shared by the words that have them.
 * RomaForms: the words whose dictionary form is another word. An int, the number of groups of 64 entries, an int of
 *   padding, a long per group with a bit per such word, an int per group counting them in the groups before, then
 *   the dictionary form's word id / 4 of each, in order. The other words are their own dictionary form.
 * Strings: katakana-only ones in RomaKana, a byte per character (the character - U+30A0); the others in RomaText as
 *   UTF-16. A reference is the pool (bit 31, 1 for RomaKana), the length (bits 23-30) and the offset (bits 0-22).
 * RomaMatrix: the connection matrix, read by RomaLattice.
 * The normalized form is the word itself; splits, synonyms and user data are empty; the index form's length is 0
 * (only split modes A and B read it). Sudachi's Entries and Strings blocks are empty and its matrix is 0 x 0: an
 * older roma fails on this dictionary instead of misreading it.
 */
// RomaEntries: per word, a long (params index, cost, headword) and then an int (the reading).
private const val ENTRY_BYTES = 12
private const val READING_OFFSET = 8
private const val PARAMS_INDEX_MASK = 0x3fffL // bits 0-13
private const val COST_SHIFT = 14 // bits 14-29
private const val HEADWORD_SHIFT = 30 // bits 30-61

// RomaParams: left id, right id and part of speech, a short each.
private const val PARAMS_BYTES = 6
private const val RIGHT_ID_OFFSET = 2
private const val POS_OFFSET = 4
private const val SHORT_MASK = 0xffffL

// RomaForms: two ints (the group count and padding) before the per-group bitmaps.
private const val FORMS_HEADER_BYTES = 8

// String reference: bit 31 picks the pool (set for RomaKana), bits 23-30 the length, bits 0-22 the offset.
private const val LENGTH_SHIFT = 23
private const val LENGTH_MASK = 0xff
private const val OFFSET_MASK = 0x7fffff
private const val KATAKANA_BASE = 0x30a0

class RomaDictionary(bytes: ByteBuffer) : BinaryDictionary(bytes) {
    private val lexicon = RomaLexicon(bytes, dictionaryHeader)
    private val matrix = dictionaryHeader.slice(bytes, "RomaMatrix")

    init {
        check(dictionaryHeader.isSystemDictionary) { "invalid system dictionary" }
    }

    override fun getLexicon(): DoubleArrayLexicon = lexicon

    /** A tokenizer with [config] on this dictionary, its lattice reading roma's matrix (RomaLattice). */
    fun tokenizer(config: Config): Tokenizer {
        val sudachiTokenizer = Dictionary.load(config.systemDictionary(this)).tokenizer()
        return RomaLattice.install(sudachiTokenizer, matrix)
    }
}

private class RomaLexicon(bytes: ByteBuffer, header: Description, private val entries: RomaEntries = RomaEntries(bytes, header)) :
    DoubleArrayLexicon(
        header,
        WordIdTable(header.slice(bytes, Block.WORD_POINTERS)),
        null,
        entries,
        DoubleArray().apply {
            val trie = header.slice(bytes, Block.TRIE_INDEX).asIntBuffer()
            setArray(trie, trie.limit())
        },
        CompactedStrings(CharBuffer.allocate(0)),
    ) {
    override fun parameters(wordId: Int): Long = entries.parameters(wordId)

    override fun string(dic: Int, stringPtr: Int): String = entries.string(stringPtr)
}

private class RomaEntries(bytes: ByteBuffer, header: Description) : WordInfoList(null) {
    private val entries = header.slice(bytes, "RomaEntries")
    private val params = header.slice(bytes, "RomaParams")
    private val forms = header.slice(bytes, "RomaForms")
    private val kana = header.slice(bytes, "RomaKana")
    private val text = header.slice(bytes, "RomaText").asCharBuffer()
    private val groups = forms.getInt(0)

    private fun at(wordId: Int) = (WordId.word(wordId) shr 2) * ENTRY_BYTES

    /** Packed as Sudachi's WordParameters reads them: left, right, cost, part of speech, 16 bits each. */
    fun parameters(wordId: Int): Long {
        val word = entries.getLong(at(wordId))
        val p = (word and PARAMS_INDEX_MASK).toInt() * PARAMS_BYTES
        val left = params.getShort(p).toLong() and SHORT_MASK
        val right = params.getShort(p + RIGHT_ID_OFFSET).toLong() and SHORT_MASK
        val cost = (word ushr COST_SHIFT) and SHORT_MASK
        val partOfSpeech = params.getShort(p + POS_OFFSET).toLong() and SHORT_MASK
        return left or (right shl 16) or (cost shl 32) or (partOfSpeech shl 48)
    }

    private fun dictionaryForm(wordId: Int): Int {
        val k = WordId.word(wordId) shr 2
        val group = k ushr 6
        val bits = forms.getLong(FORMS_HEADER_BYTES + group * 8)
        val bit = 1L shl (k and 63)
        if ((bits and bit) == 0L) return WordId.word(wordId)
        val countBefore = forms.getInt(FORMS_HEADER_BYTES + groups * 8 + group * 4)
        val rank = countBefore + java.lang.Long.bitCount(bits and (bit - 1))
        return forms.getInt(FORMS_HEADER_BYTES + groups * 12 + rank * 4) shl 2
    }

    override fun getWordInfo(wordId: Int): WordInfo {
        val e = at(wordId)
        val word = entries.getLong(e)
        val partOfSpeech = params.getShort((word and PARAMS_INDEX_MASK).toInt() * PARAMS_BYTES + POS_OFFSET)
        val headword = (word ushr HEADWORD_SHIFT).toInt()
        val reading = entries.getInt(e + READING_OFFSET)
        val none = Ints.EMPTY_ARRAY
        return WordInfo(
            0, partOfSpeech, headword, reading,
            WordId.word(wordId), dictionaryForm(wordId), none, none, none, none, none, "",
        )
    }

    override fun headwordPtr(wordId: Int): Int = (entries.getLong(at(wordId)) ushr HEADWORD_SHIFT).toInt()

    override fun readingPtr(wordId: Int): Int = entries.getInt(at(wordId) + READING_OFFSET)

    fun string(ref: Int): String {
        val length = (ref ushr LENGTH_SHIFT) and LENGTH_MASK
        val offset = ref and OFFSET_MASK
        // A clear bit 31 (a non-negative ref) means RomaText.
        if (ref >= 0) return text.subSequence(offset, offset + length).toString()
        val chars = CharArray(length) { i ->
            val code = kana.get(offset + i).toInt() and 0xff
            (KATAKANA_BASE + code).toChar()
        }
        return String(chars)
    }
}
