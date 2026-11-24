package dev.lelonio.square.backend.subsonic

import dev.lelonio.square.common.asKeyValueStore
import java.util.prefs.Preferences

/** The desktop's settings, in java.util.prefs (~/.java/.userPrefs on Linux) instead of SharedPreferences. */
fun SubsonicPreferences(prefs: Preferences = Preferences.userRoot().node("square-yuma/subsonic")): SubsonicPreferences {
    // Before the shared class, the keys here were un-prefixed; carry a saved server over once.
    for ((old, new) in listOf(
        "server_url" to SubsonicPreferences.KEY_SERVER_URL,
        "username" to SubsonicPreferences.KEY_USERNAME,
        "password" to SubsonicPreferences.KEY_PASSWORD,
    )) {
        val value = prefs.get(old, null)
        if (value != null && prefs.get(new, null) == null) prefs.put(new, value)
    }
    return SubsonicPreferences(prefs.asKeyValueStore(), clientName = "SquareYumaDesktop")
}
