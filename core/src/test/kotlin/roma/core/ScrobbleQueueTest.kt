package roma.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ScrobbleQueueTest {
    private fun play(n: Int) = Scrobble("t$n", "a", null, n.toLong(), 200, "s.$n")

    @Test fun keepsWhatWasntDoneWith() {
        val q = ScrobbleQueue(kotlin.io.path.createTempFile().toFile().also { it.delete() })
        (1..60).forEach { q.add(play(it)) }
        val sent = ArrayList<Int>()
        // The first batch goes whole, the second stops after two.
        q.flush { batch -> sent += batch.size; if (sent.size == 1) batch.size else 2 }
        assertEquals(listOf(50, 10), sent)
        assertEquals((53..60).map { "s.$it" }, q.load().map { it.id })
    }

    @Test fun clearedPlaysDontComeBack() {
        val q = ScrobbleQueue(kotlin.io.path.createTempFile().toFile().also { it.delete() })
        (1..60).forEach { q.add(play(it)) }
        // Another server meanwhile: the batch being sent mustn't put the rest back.
        q.flush { q.clear(); 0 }
        assertEquals(emptyList(), q.load())
        q.add(play(61))
        assertEquals(listOf("s.61"), q.load().map { it.id })
    }
}
