package meow.nyx.quackjs

import kotlin.test.Test
import kotlin.test.assertIs

class PlayerProgramTest {
    // The fallback catches Exception only; an Error escaping QuackJS would crash ExoPlayer's loader thread.
    @Test fun stackOverflowIsUnsupported() {
        val nested = "(".repeat(120) + "1" + ")".repeat(120)
        val player = "var g={};g.N=function(a){return $nested};function f(a,b,c){return $nested}"
        val hints = PlayerHints("f(1,2,INPUT)", "N", 0)
        // Load QuackJS's classes first, so the small stack overflows in the parser and not in the class loader.
        runCatching { PlayerProgram.compile("var g={};", hints) }
        var thrown: Throwable? = null
        val thread = Thread(null, {
            thrown = runCatching { PlayerProgram.compile(player, hints) }.exceptionOrNull()
        }, "small-stack", 16 * 1024)
        thread.start()
        thread.join()
        assertIs<UnsupportedPlayerException>(thrown)
    }
}
