package moe.rukamori.archivetune.playback.smart

import java.io.File

/** The JNI library sits beside ffmpeg in the app's resources, not on java.library.path. */
actual fun loadAnalysisLibrary() {
    val name = System.mapLibraryName("yuma_analysis")
    val file = System.getProperty("compose.application.resources.dir")?.let { File(it, name) }?.takeIf { it.isFile }
        ?: throw UnsatisfiedLinkError("$name not in the app's resources")
    System.load(file.absolutePath)
}

actual object ModelSource {
    private val dir = File(System.getProperty("user.home"), ".square-yuma/models").apply { mkdirs() }

    actual val ready: Boolean get() = true

    /** The models ship on the classpath (/models); unpacked once into ~/.square-yuma/models. */
    actual fun path(name: String): String {
        val file = File(dir, name)
        if (!file.exists() || file.length() == 0L) {
            val tmp = File(dir, "$name.tmp")
            val input = ModelSource::class.java.getResourceAsStream("/models/$name") ?: throw java.io.FileNotFoundException(name)
            input.use { i -> tmp.outputStream().use { i.copyTo(it) } }
            if (!tmp.renameTo(file)) error("could not move $tmp into place")
        }
        return file.absolutePath
    }
}
