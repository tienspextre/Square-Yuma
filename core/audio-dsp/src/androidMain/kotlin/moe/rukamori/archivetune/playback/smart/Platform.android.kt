package moe.rukamori.archivetune.playback.smart

import android.content.Context
import java.io.File

actual fun loadAnalysisLibrary() = System.loadLibrary("yuma_analysis")

actual object ModelSource {
    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    actual val ready: Boolean get() = appContext != null

    /**
     * ONNX Runtime wants a file it can map; an APK asset is not one. Unpacked once by name, through
     * a temporary file so a copy cut short by a kill is never taken for the real one.
     */
    actual fun path(name: String): String {
        val context = appContext ?: error("ModelSource.init not called")
        val file = File(context.filesDir, name)
        if (!file.exists() || file.length() == 0L) {
            val tmp = File(context.filesDir, "$name.tmp")
            context.assets.open(name).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(file)) error("could not move $tmp into place")
        }
        return file.absolutePath
    }
}
