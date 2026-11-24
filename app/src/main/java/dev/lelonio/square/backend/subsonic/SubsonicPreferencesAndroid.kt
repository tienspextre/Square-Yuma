package dev.lelonio.square.backend.subsonic

import android.content.Context
import dev.lelonio.square.common.asKeyValueStore

/** The phone's settings, in the same SharedPreferences file as before. */
fun SubsonicPreferences(context: Context): SubsonicPreferences = SubsonicPreferences(
    context.applicationContext.getSharedPreferences("subsonic_preferences", Context.MODE_PRIVATE).asKeyValueStore(),
)
