package roma.core

import roma.core.StreamResolveException.Reason
import kotlin.test.Test
import kotlin.test.assertEquals

class StreamsTest {
    private val premium = StreamException("This video is a YouTube Music Premium video")

    @Test
    fun lastSpecificReasonWins() {
        val generic = StreamResolveException(Reason.NO_PLAYABLE_STREAM, "No playable stream found for this track.")
        assertEquals("This video is a YouTube Music Premium video", Streams.reason(listOf(premium, premium, generic)))
        val age = StreamResolveException(Reason.AGE_RESTRICTED, "This track is age-restricted.")
        assertEquals("This track is age-restricted.", Streams.reason(listOf(premium, premium, age)))
    }

    @Test
    fun networkAndEmpty() {
        val offline = StreamResolveException(Reason.NETWORK, "x", java.net.UnknownHostException("youtubei.googleapis.com"))
        assertEquals("No connection to YouTube.", Streams.reason(listOf(offline)))
        assertEquals("YouTube gave no playable stream for this song.", Streams.reason(listOf(IllegalStateException("No audio stream for x"))))
    }

    @Test
    fun keptAcrossStarts() {
        val f = java.io.File.createTempFile("streams", ".txt")
        val old = System.currentTimeMillis() - 8 * 86_400_000L
        f.writeText("a L 1.5\nb L -2.0\na L 3.0\nc X ${System.currentTimeMillis()}\nd X $old\ne X 1\ne N\n")
        Streams.keepIn(f)
        assertEquals(3.0f, Streams.loudness("a"))
        assertEquals(-2.0f, Streams.loudness("b"))
        assertEquals("c", Streams.recentFallback()) // d is too old, e worked with the direct path again
        Streams.putLoudness("b", -2.0f) // unchanged: no new line
        Streams.putLoudness("z", 4.0f)
        assertEquals("z L 4.0", f.readLines().last())
        f.delete()
    }
}
