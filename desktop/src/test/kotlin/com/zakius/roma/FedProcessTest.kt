package com.zakius.roma

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FedProcessTest {
    /**
     * A decoder that stops reading (here one that never does) leaves its feeder stuck in a write to a full pipe. Closing
     * it must neither wait for that feeder nor leave it stuck: the player closes a song's decoder to start another. Like
     * roma-aac blocked writing its own output, this one doesn't end on SIGTERM.
     */
    @Test fun closeNeverWaitsForAStuckFeeder() {
        val fed = FedProcess(listOf("sh", "-c", "trap '' TERM; exec sleep 60"), "test in") { o -> val b = ByteArray(1 shl 16); while (true) o.write(b) }
        try {
            Thread.sleep(500) // the pipe (64 KB) is full by now: the feeder is blocked in write
            assertTrue(fed.feeding)
            val closer = Thread(fed::close).apply { start(); join(2_000) }
            assertFalse(closer.isAlive, "close blocked on the stuck feeder")
            val until = System.currentTimeMillis() + 5_000
            while (fed.feeding && System.currentTimeMillis() < until) Thread.sleep(20)
            assertFalse(fed.feeding, "the feeder is still stuck after close")
        } finally {
            fed.process.toHandle().destroyForcibly()
        }
    }
}
