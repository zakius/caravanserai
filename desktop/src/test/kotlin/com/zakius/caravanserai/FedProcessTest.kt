package com.zakius.caravanserai

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FedProcessTest {
    /**
     * A decoder that stops reading (here, one that never reads) leaves its feeder stuck in a write to a full pipe. The
     * player closes a song's decoder to start another, so closing must neither wait for that feeder nor leave it stuck.
     * This decoder ignores SIGTERM, as roma-aac does while it is blocked writing its own output.
     */
    @Test fun closeNeverWaitsForAStuckFeeder() {
        val fed = FedProcess(listOf("sh", "-c", "trap '' TERM; exec sleep 60"), "test in") { o ->
            val b = ByteArray(64 * 1024)
            while (true) o.write(b)
        }
        try {
            // By now the 64 KB pipe is full and the feeder is blocked in write.
            Thread.sleep(500)
            assertTrue(fed.feeding)
            val closer = Thread(fed::close)
            closer.start()
            closer.join(2_000)
            assertFalse(closer.isAlive, "close blocked on the stuck feeder")
            val until = System.currentTimeMillis() + 5_000
            while (fed.feeding && System.currentTimeMillis() < until) Thread.sleep(20)
            assertFalse(fed.feeding, "the feeder is still stuck after close")
        } finally {
            fed.process.toHandle().destroyForcibly()
        }
    }
}
