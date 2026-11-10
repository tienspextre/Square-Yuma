package android.content

import java.io.File
import java.io.InputStream

/** What the shared ModelPass asks of a Context: somewhere to unpack the models, and the models. */
open class Context {
    open val applicationContext: Context get() = this
    val filesDir: File = File(System.getProperty("user.home"), ".square-yuma/models").apply { mkdirs() }
    val assets = Assets()

    class Assets {
        fun open(name: String): InputStream =
            Context::class.java.getResourceAsStream("/models/$name") ?: throw java.io.FileNotFoundException(name)
    }
}
