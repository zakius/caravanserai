package roma.core

import kotlin.test.Test
import kotlin.test.assertEquals

class CommentsTest {
    @Test
    fun replyCounts() {
        assertEquals(629, Comments.count("629"))
        assertEquals(1200, Comments.count("1.2K"))
        assertEquals(0, Comments.count(""))
        assertEquals(0, Comments.count(null))
    }
}
