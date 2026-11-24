package dev.lelonio.square.common

actual object Log {
    private val debug = System.getProperty("square.debug") != null

    private fun out(level: String, tag: String, msg: String, t: Throwable?) {
        System.err.println("$level/$tag: $msg")
        t?.printStackTrace()
    }

    actual fun i(tag: String, msg: String) { if (debug) out("I", tag, msg, null) }
    actual fun d(tag: String, msg: String) { if (debug) out("D", tag, msg, null) }
    actual fun w(tag: String, msg: String) { if (debug) out("W", tag, msg, null) }
    actual fun w(tag: String, t: Throwable, msg: String) { if (debug) out("W", tag, msg, t) }
    actual fun e(tag: String, msg: String) = out("E", tag, msg, null)
    actual fun e(tag: String, t: Throwable, msg: String) = out("E", tag, msg, t)
}
