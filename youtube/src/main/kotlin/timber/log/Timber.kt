// The vendored innertube code logs through Timber, an Android library. This no-op stand-in keeps that code unchanged.
package timber.log

object Timber {
    fun d(message: String, vararg args: Any?) {}
    fun w(message: String, vararg args: Any?) {}
    fun w(t: Throwable?, message: String, vararg args: Any?) {}
    fun e(t: Throwable?, message: String, vararg args: Any?) {}
}
