package dev.lelonio.square.common

/** Leveled logging; Android writes through Timber, the desktop to stderr (debug and warnings only with -Dsquare.debug). */
expect object Log {
    fun i(tag: String, msg: String)
    fun d(tag: String, msg: String)
    fun w(tag: String, msg: String)
    fun w(tag: String, t: Throwable, msg: String)
    fun e(tag: String, msg: String)
    fun e(tag: String, t: Throwable, msg: String)
}
