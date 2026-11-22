package roma.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RomanizeCacheTest {
    private class MapCache : RomanizeCache {
        val map = HashMap<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) { map[key] = value }
    }

    @Test fun keptAndReused() {
        val cache = MapCache()
        val r = Romanizer(jvmIcu)
        r.cache = cache
        assertEquals("Moskva", r.machine("Москва"))
        assertEquals(1, cache.map.size)
        // A hit is returned as kept, without romanizing again.
        cache.map[cache.map.keys.single()] = "kept"
        assertEquals("kept", r.machine("Москва"))
        // Title and sentence case are kept apart.
        r.machine("Москва", sentence = true)
        assertEquals(2, cache.map.size)
        // Latin text is never romanized, so never kept.
        r.machine("Moscow")
        assertEquals(2, cache.map.size)
    }

    @Test fun songReadingsAndMissingDictionaryAreNotKept() {
        val cache = MapCache()
        val r = Romanizer(jvmIcu, dictionary = { error("no dictionary") })
        r.cache = cache
        r.overrides = listOf(Override("word", "song1", "Москва", "Moscow"))
        r.machine("Москва", songId = "song1")
        assertTrue(cache.map.isEmpty(), "a song's own readings bypass the cache")
        r.machine("さよなら")
        assertTrue(cache.map.isEmpty(), "kana-only Japanese without the dictionary is redone later, not kept")
    }

    @Test fun computePiecesOffSkipsDictionaryOnCacheHit() {
        val cache = MapCache()
        val warm = Romanizer(jvmIcu).apply { this.cache = cache }
        val expected = warm.text("夜に駆ける")
        var dictCalls = 0
        val cold = Romanizer(jvmIcu, dictionary = {
            dictCalls++
            error("dictionary should not be loaded on a cached non-ruby lookup")
        }).apply {
            this.cache = cache
            computePieces = false
        }
        val got = cold.text("夜に駆ける")
        assertEquals(expected.latin, got.latin)
        assertTrue(got.pieces.isEmpty())
        assertEquals(0, dictCalls)
    }

    @Test fun preparedSearchMatchesMatchDistance() {
        val texts = listOf("夜に駆ける", "Yoru ni Kakeru", "YOASOBI", "Gunjō")
        val folded = texts.map(::foldSearch).toTypedArray()
        for (q in listOf("yoru", "yoru ni", "yoru ni kakru", "yoasbi", "gunjo", "zzzzz", "  ")) {
            val expected = matchDistance(texts, q)
            val actual = prepareQuery(q)?.let { matchFolded(folded, it) }
            assertEquals(expected, actual, "query=$q")
        }
    }
}
