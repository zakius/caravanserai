package com.zakius.caravanserai

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FedProcessTest {
    /**
     * A decoder that stops reading (here, one that never reads) leaves its feeder stuck in a write to a full pipe. The
     * player closes a song's decoder to start another, so closing must neither wait for that feeder nor leave it stuck.
     * This decoder ignores SIGTERM, as roma-aac does while it is blocked writing its own output.
     */
    @Test fun closeNeverWaitsForAStuckFeeder() {
        val fedEnded = CountDownLatch(1)
        val fed = FedProcess(listOf("sh", "-c", "trap '' TERM; exec sleep 60"), "test in") { o ->
            try {
                val b = ByteArray(64 * 1024)
                while (true) o.write(b)
            } finally {
                fedEnded.countDown()
            }
        }
        try {
            // By now the 64 KB pipe is full and the feeder is blocked in write.
            Thread.sleep(500)
            assertEquals(1L, fedEnded.count)
            val closer = Thread(fed::close)
            closer.start()
            closer.join(2_000)
            assertFalse(closer.isAlive, "close blocked on the stuck feeder")
            assertTrue(fedEnded.await(5, TimeUnit.SECONDS), "the feeder is still stuck after close")
        } finally {
            fed.process.toHandle().destroyForcibly()
        }
    }
}
