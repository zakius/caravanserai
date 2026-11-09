package com.zakius.caravanserai

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamFileTest {
    /** Another server's song with the same id isn't played from the cache after [StreamFile.clear]. */
    @Test fun clearForgetsCompleteSongs() {
        Store.cache = kotlin.io.path.createTempDirectory("stream-file").toFile()
        StreamFile.dir.mkdirs()
        File(StreamFile.dir, "tr-1").writeBytes(ByteArray(10))
        File(StreamFile.dir, "tr-1.len").writeText("10")
        assertTrue(StreamFile.of("tr-1").complete)
        StreamFile.clear()
        assertFalse(StreamFile.of("tr-1").complete)
        assertEquals(0L, StreamFile.of("tr-1").have)
        assertFalse(File(StreamFile.dir, "tr-1").exists())
    }
}
