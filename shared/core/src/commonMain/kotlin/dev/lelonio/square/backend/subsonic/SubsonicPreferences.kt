package dev.lelonio.square.backend.subsonic

import dev.lelonio.square.common.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.util.UUID

class SubsonicPreferences(
    private val prefs: KeyValueStore,
    private val clientName: String = CLIENT_NAME,
) {
    companion object {
        const val KEY_SERVER_URL = "subsonic_server_url"
        const val KEY_USERNAME = "subsonic_username"
        const val KEY_PASSWORD = "subsonic_password"
        const val KEY_TRANSCODE_CELLULAR = "subsonic_transcode_cellular"
        const val KEY_TRANSCODE_BITRATE = "subsonic_transcode_bitrate"
        const val KEY_DOWNLOAD_TREE = "subsonic_download_tree_uri"
        const val DEFAULT_SERVER_URL = ""
        const val CLIENT_NAME = "SquarePlayer"
        const val API_VERSION = "1.16.1"
    }

    private val _serverUrl = MutableStateFlow(prefs.getString(KEY_SERVER_URL) ?: DEFAULT_SERVER_URL)
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _username = MutableStateFlow(prefs.getString(KEY_USERNAME) ?: "")
    val username: StateFlow<String> = _username.asStateFlow()

    private val _password = MutableStateFlow(prefs.getString(KEY_PASSWORD) ?: "")
    val password: StateFlow<String> = _password.asStateFlow()

    private val _transcodeCellular = MutableStateFlow(prefs.getBoolean(KEY_TRANSCODE_CELLULAR, false))
    val transcodeCellular: StateFlow<Boolean> = _transcodeCellular.asStateFlow()

    private val _transcodeBitrate = MutableStateFlow(prefs.getInt(KEY_TRANSCODE_BITRATE, 160))
    val transcodeBitrate: StateFlow<Int> = _transcodeBitrate.asStateFlow()

    /** A folder the user picked (SAF tree URI); null means the device's Music/Square. */
    private val _downloadTreeUri = MutableStateFlow(prefs.getString(KEY_DOWNLOAD_TREE))
    val downloadTreeUri: StateFlow<String?> = _downloadTreeUri.asStateFlow()

    fun setDownloadTreeUri(value: String?) {
        _downloadTreeUri.value = value
        prefs.putString(KEY_DOWNLOAD_TREE, value)
    }

    fun isConfigured(): Boolean {
        val url = _serverUrl.value.trim()
        val user = _username.value.trim()
        return url.isNotEmpty() && user.isNotEmpty()
    }

    fun setServerUrl(value: String) {
        val trimmed = value.trim().removeSuffix("/")
        _serverUrl.value = trimmed
        prefs.putString(KEY_SERVER_URL, trimmed)
    }

    fun setUsername(value: String) {
        val trimmed = value.trim()
        _username.value = trimmed
        prefs.putString(KEY_USERNAME, trimmed)
    }

    fun setPassword(value: String) {
        _password.value = value
        prefs.putString(KEY_PASSWORD, value)
    }

    fun setTranscodeCellular(value: Boolean) {
        _transcodeCellular.value = value
        prefs.putBoolean(KEY_TRANSCODE_CELLULAR, value)
    }

    fun setTranscodeBitrate(value: Int) {
        _transcodeBitrate.value = value
        prefs.putInt(KEY_TRANSCODE_BITRATE, value)
    }

    fun save(url: String, user: String, pass: String) {
        setServerUrl(url)
        setUsername(user)
        setPassword(pass)
    }

    fun buildAuthParams(): Map<String, String> {
        val user = _username.value.trim()
        val pass = _password.value
        val salt = UUID.randomUUID().toString().replace("-", "").take(12)
        val token = md5(pass + salt)
        return mapOf(
            "u" to user,
            "t" to token,
            "s" to salt,
            "v" to API_VERSION,
            "c" to clientName,
            "f" to "json",
        )
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
