package dev.lelonio.square.backend.subsonic

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class SubsonicPreferences {

    private val configFile: File by lazy {
        val os = System.getProperty("os.name").lowercase()
        val baseDir = if (os.contains("win")) {
            val appData = System.getenv("APPDATA") ?: System.getProperty("user.home")
            File(appData, "Square-Yuma")
        } else {
            val xdg = System.getenv("XDG_CONFIG_HOME")
                ?: "${System.getProperty("user.home")}/.config"
            File(xdg, "square-yuma")
        }
        baseDir.mkdirs()
        File(baseDir, "preferences.json")
    }

    private val storedJson: JSONObject by lazy {
        if (configFile.exists()) {
            runCatching { JSONObject(configFile.readText()) }.getOrDefault(JSONObject())
        } else {
            JSONObject()
        }
    }

    companion object {
        const val KEY_SERVER_URL = "subsonic_server_url"
        const val KEY_USERNAME = "subsonic_username"
        const val KEY_PASSWORD = "subsonic_password"
        const val KEY_TRANSCODE_CELLULAR = "subsonic_transcode_cellular"
        const val KEY_TRANSCODE_BITRATE = "subsonic_transcode_bitrate"
        const val KEY_LOCAL_FOLDER = "local_music_folder"
        const val DEFAULT_SERVER_URL = ""
        const val DEFAULT_USERNAME = ""
        const val DEFAULT_PASSWORD = ""
        const val CLIENT_NAME = "SquareYumaDesktop"
        const val API_VERSION = "1.16.1"
    }

    private val _serverUrl = MutableStateFlow(
        storedJson.optString(KEY_SERVER_URL, DEFAULT_SERVER_URL).ifBlank { DEFAULT_SERVER_URL }
    )
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _username = MutableStateFlow(
        storedJson.optString(KEY_USERNAME, DEFAULT_USERNAME).ifBlank { DEFAULT_USERNAME }
    )
    val username: StateFlow<String> = _username.asStateFlow()

    private val _password = MutableStateFlow(
        storedJson.optString(KEY_PASSWORD, DEFAULT_PASSWORD).ifBlank { DEFAULT_PASSWORD }
    )
    val password: StateFlow<String> = _password.asStateFlow()

    private val _transcodeCellular = MutableStateFlow(
        storedJson.optBoolean(KEY_TRANSCODE_CELLULAR, false)
    )
    val transcodeCellular: StateFlow<Boolean> = _transcodeCellular.asStateFlow()

    private val _transcodeBitrate = MutableStateFlow(
        storedJson.optInt(KEY_TRANSCODE_BITRATE, 160)
    )
    val transcodeBitrate: StateFlow<Int> = _transcodeBitrate.asStateFlow()

    private val defaultLocalFolder: String by lazy {
        val nasPool = File("/nas-pool/media/music")
        if (nasPool.exists() && nasPool.isDirectory) {
            nasPool.absolutePath
        } else {
            File(System.getProperty("user.home"), "Music").absolutePath
        }
    }

    private val _localMusicFolder = MutableStateFlow(
        storedJson.optString(KEY_LOCAL_FOLDER, defaultLocalFolder).ifBlank { defaultLocalFolder }
    )
    val localMusicFolder: StateFlow<String> = _localMusicFolder.asStateFlow()

    private fun save() {
        runCatching {
            storedJson.put(KEY_SERVER_URL, _serverUrl.value)
            storedJson.put(KEY_USERNAME, _username.value)
            storedJson.put(KEY_PASSWORD, _password.value)
            storedJson.put(KEY_TRANSCODE_CELLULAR, _transcodeCellular.value)
            storedJson.put(KEY_TRANSCODE_BITRATE, _transcodeBitrate.value)
            storedJson.put(KEY_LOCAL_FOLDER, _localMusicFolder.value)
            configFile.writeText(storedJson.toString(2))
        }
    }

    fun isConfigured(): Boolean {
        val url = _serverUrl.value.trim()
        val user = _username.value.trim()
        return url.isNotEmpty() && user.isNotEmpty()
    }

    fun setServerUrl(value: String) {
        _serverUrl.value = value.trim().removeSuffix("/")
        save()
    }

    fun setUsername(value: String) {
        _username.value = value.trim()
        save()
    }

    fun setPassword(value: String) {
        _password.value = value
        save()
    }

    fun setTranscodeCellular(value: Boolean) {
        _transcodeCellular.value = value
        save()
    }

    fun setTranscodeBitrate(value: Int) {
        _transcodeBitrate.value = value
        save()
    }

    fun setLocalMusicFolder(value: String) {
        _localMusicFolder.value = value.trim()
        save()
    }

    private val sessionSalt: String = UUID.randomUUID().toString().replace("-", "").take(12)

    fun buildAuthParams(): Map<String, String> {
        val user = _username.value.trim()
        val pass = _password.value
        val token = md5(pass + sessionSalt)
        return mapOf(
            "u" to user,
            "t" to token,
            "s" to sessionSalt,
            "v" to API_VERSION,
            "c" to CLIENT_NAME,
            "f" to "json",
        )
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
