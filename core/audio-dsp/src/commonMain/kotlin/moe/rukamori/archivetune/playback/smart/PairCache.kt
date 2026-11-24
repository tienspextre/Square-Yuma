package moe.rukamori.archivetune.playback.smart

import java.io.File
import java.security.MessageDigest

/** Planned transitions per track pair, at `filesDir/automix/pairs/<id>.json`. */
class PairCache(private val dir: File) {
    fun get(id: String): String? = runCatching { File(dir, "$id.json").takeIf { it.isFile }?.readText() }.getOrNull()

    fun put(id: String, json: String) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "$id.tmp")
            tmp.writeText(json)
            tmp.renameTo(File(dir, "$id.json"))
        }
    }

    companion object {
        fun id(trackIdA: String, trackIdB: String, analysisVersion: Int, pairVersion: Int): String =
            MessageDigest.getInstance("SHA-256")
                .digest("$trackIdA|$trackIdB|$analysisVersion|$pairVersion".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(16)

        fun at(filesDir: File) = PairCache(File(filesDir, "automix/pairs"))
    }
}

/** Automix reports at `filesDir/automix/reports/`, newest [keep] kept. */
class ReportStore(private val dir: File, private val keep: Int = 50) {
    fun write(name: String, json: String) {
        runCatching {
            dir.mkdirs()
            File(dir, name).writeText(json)
            files().drop(keep).forEach { it.delete() }
        }
    }

    /** Newest first. */
    fun files(): List<File> = dir.listFiles()?.filter { it.isFile }
        ?.sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name }).orEmpty()

    companion object {
        fun at(filesDir: File) = ReportStore(File(filesDir, "automix/reports"))
    }
}
