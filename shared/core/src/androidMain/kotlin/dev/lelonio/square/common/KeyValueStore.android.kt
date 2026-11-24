package dev.lelonio.square.common

import android.content.SharedPreferences

fun SharedPreferences.asKeyValueStore(): KeyValueStore = object : KeyValueStore {
    override fun getString(key: String): String? = this@asKeyValueStore.getString(key, null)
    override fun getBoolean(key: String, default: Boolean) = this@asKeyValueStore.getBoolean(key, default)
    override fun getInt(key: String, default: Int) = this@asKeyValueStore.getInt(key, default)
    override fun putString(key: String, value: String?) = edit().putString(key, value).apply()
    override fun putBoolean(key: String, value: Boolean) = edit().putBoolean(key, value).apply()
    override fun putInt(key: String, value: Int) = edit().putInt(key, value).apply()
}
