package dev.lelonio.square.common

import java.util.prefs.Preferences

fun Preferences.asKeyValueStore(): KeyValueStore = object : KeyValueStore {
    override fun getString(key: String): String? = get(key, null)
    override fun getBoolean(key: String, default: Boolean) = this@asKeyValueStore.getBoolean(key, default)
    override fun getInt(key: String, default: Int) = this@asKeyValueStore.getInt(key, default)
    override fun putString(key: String, value: String?) { if (value == null) remove(key) else put(key, value); flush() }
    override fun putBoolean(key: String, value: Boolean) { this@asKeyValueStore.putBoolean(key, value); flush() }
    override fun putInt(key: String, value: Int) { this@asKeyValueStore.putInt(key, value); flush() }
}
