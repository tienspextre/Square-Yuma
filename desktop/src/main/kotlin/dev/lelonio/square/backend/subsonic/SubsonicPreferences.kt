package dev.lelonio.square.backend.subsonic

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest
import java.util.UUID
import java.util.prefs.Preferences

/**
 * The desktop's SubsonicPreferences: the same API the shared SubsonicClient
 * reads, kept in java.util.prefs (~/.java/.userPrefs on Linux) instead of
 * SharedPreferences.
 */
class SubsonicPreferences(private val prefs: Preferences = Preferences.userRoot().node("square-yuma/subsonic")) {

    private fun string(key: String, default: String) = MutableStateFlow(prefs.get(key, default))

    private val _serverUrl = string("server_url", "")
    val serverUrl: StateFlow<String> = _serverUrl
    private val _username = string("username", "")
    val username: StateFlow<String> = _username
    private val _password = string("password", "")
    val password: StateFlow<String> = _password

    // Read by SubsonicClient.buildStreamUrl; a desktop is never on mobile data.
    val transcodeCellular: StateFlow<Boolean> = MutableStateFlow(false)
    val transcodeBitrate: StateFlow<Int> = MutableStateFlow(160)

    fun isConfigured() = _serverUrl.value.isNotBlank() && _username.value.isNotBlank()

    fun save(url: String, user: String, pass: String) {
        _serverUrl.value = url.trim().removeSuffix("/")
        _username.value = user.trim()
        _password.value = pass
        prefs.put("server_url", _serverUrl.value)
        prefs.put("username", _username.value)
        // ponytail: stored like the Android app stores it (plain prefs); use the OS keyring if this ever leaves a personal machine.
        prefs.put("password", pass)
        prefs.flush()
    }

    fun buildAuthParams(): Map<String, String> {
        val salt = UUID.randomUUID().toString().replace("-", "").take(12)
        val token = MessageDigest.getInstance("MD5").digest((_password.value + salt).toByteArray())
            .joinToString("") { "%02x".format(it) }
        return mapOf("u" to _username.value, "t" to token, "s" to salt, "v" to "1.16.1", "c" to "SquareYumaDesktop", "f" to "json")
    }
}
