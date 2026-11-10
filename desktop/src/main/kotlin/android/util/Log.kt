package android.util

/** The shared lyrics providers log through android.util; on the desktop that is stderr, behind -Dsquare.debug. */
object Log {
    private val on = System.getProperty("square.debug") != null
    fun i(tag: String, msg: String): Int { if (on) System.err.println("I/$tag: $msg"); return 0 }
    fun w(tag: String, msg: String, t: Throwable? = null): Int { if (on) System.err.println("W/$tag: $msg ${t ?: ""}"); return 0 }
    fun d(tag: String, msg: String): Int = i(tag, msg)
    fun e(tag: String, msg: String, t: Throwable? = null): Int { System.err.println("E/$tag: $msg ${t ?: ""}"); return 0 }
}

/** android.util.Base64.decode(…, DEFAULT): MIME-tolerant base64. */
object Base64 {
    const val DEFAULT = 0
    fun decode(s: String, flags: Int): ByteArray = java.util.Base64.getMimeDecoder().decode(s)
}
