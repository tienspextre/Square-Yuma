package timber.log

/** The slice of Timber the shared automix analysis uses, printed with -Dsquare.debug (errors always). */
object Timber {
    class Tree(private val tag: String) {
        private fun out(level: String, t: Throwable?, msg: String, always: Boolean = false) {
            if (always || System.getProperty("square.debug") != null) System.err.println("$level/$tag: $msg" + (t?.let { " — $it" } ?: ""))
        }
        fun d(msg: String) = out("D", null, msg)
        fun d(t: Throwable?, msg: String) = out("D", t, msg)
        fun w(msg: String) = out("W", null, msg)
        fun w(t: Throwable?, msg: String) = out("W", t, msg)
        fun e(msg: String) = out("E", null, msg, true)
        fun e(t: Throwable?, msg: String) = out("E", t, msg, true)
    }
    fun tag(tag: String) = Tree(tag)
}
