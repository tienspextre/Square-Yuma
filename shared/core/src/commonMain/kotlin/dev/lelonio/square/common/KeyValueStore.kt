package dev.lelonio.square.common

/** The few typed settings the shared code keeps; SharedPreferences on Android, java.util.prefs on the desktop. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getInt(key: String, default: Int): Int
    fun putString(key: String, value: String?)
    fun putBoolean(key: String, value: Boolean)
    fun putInt(key: String, value: Int)
}
