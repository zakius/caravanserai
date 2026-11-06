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
}
