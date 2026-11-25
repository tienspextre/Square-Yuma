package moe.rukamori.archivetune.playback.smart

import java.io.File

private object NativeLoader {
    @Volatile
    var loaded = false

    fun load() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val name = System.mapLibraryName("square_win_native")
            val candidates = buildList {
                System.getProperty("compose.application.resources.dir")?.let { add(File(it, name)) }
                val os = System.getProperty("os.name").orEmpty()
                val osDir = if (os.startsWith("Windows")) "windows" else "linux"
                add(File("desktop/appResources/$osDir/$name"))
                add(File("appResources/$osDir/$name"))
                add(File("native/windows/build/$name"))
                add(File("../native/windows/build/$name"))
            }
            val file = candidates.firstOrNull { it.isFile }
            if (file != null) {
                System.load(file.absolutePath)
            } else {
                System.loadLibrary("square_win_native")
            }
            loaded = true
        }
    }
}

/** Loads `square_win_native` (which embeds the analysis JNI symbols); throws UnsatisfiedLinkError if absent. */
actual fun loadAnalysisLibrary() {
    NativeLoader.load()
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
