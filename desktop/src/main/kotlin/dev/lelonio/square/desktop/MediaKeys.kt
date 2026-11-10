package dev.lelonio.square.desktop

import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant
import java.util.logging.Level
import java.util.logging.Logger

/**
 * The keyboard's and headset's media keys, and the system's media controls:
 * MPRIS on Linux (GNOME/KDE's media panel, playerctl, Bluetooth buttons), a
 * global key hook on Windows. Windows' own media overlay (SMTC) is a WinRT API
 * the JVM cannot reach, so the keys work there without the overlay.
 */
fun startMediaKeys(state: AppState) {
    val os = System.getProperty("os.name")
    runCatching {
        when {
            os.startsWith("Windows") -> windowsKeys(state)
            os.startsWith("Linux") -> Mpris(state).start()
        }
    }.onFailure { System.err.println("media keys unavailable: $it") }
}

private fun windowsKeys(state: AppState) {
    Logger.getLogger(GlobalScreen::class.java.packageName).apply { level = Level.OFF; useParentHandlers = false }
    GlobalScreen.registerNativeHook()
    GlobalScreen.addNativeKeyListener(object : NativeKeyListener {
        override fun nativeKeyPressed(e: NativeKeyEvent) {
            when (e.keyCode) {
                NativeKeyEvent.VC_MEDIA_PLAY -> state.player.toggle()
                NativeKeyEvent.VC_MEDIA_NEXT -> state.player.next()
                NativeKeyEvent.VC_MEDIA_PREVIOUS -> state.player.previous()
                NativeKeyEvent.VC_MEDIA_STOP -> state.player.pause()
            }
        }
    })
}

@DBusInterfaceName("org.mpris.MediaPlayer2")
interface MediaPlayer2 : DBusInterface {
    fun Raise()
    fun Quit()
}

@DBusInterfaceName("org.mpris.MediaPlayer2.Player")
interface MprisPlayer : DBusInterface {
    fun Next()
    fun Previous()
    fun Pause()
    fun PlayPause()
    fun Stop()
    fun Play()
    fun Seek(offset: Long)
    fun SetPosition(trackId: DBusPath, position: Long)
    fun OpenUri(uri: String)
}

private const val ROOT = "org.mpris.MediaPlayer2"
private const val PLAYER = "org.mpris.MediaPlayer2.Player"
private const val PATH = "/org/mpris/MediaPlayer2"

private class Mpris(private val state: AppState) : MediaPlayer2, MprisPlayer, Properties {
    private val p = state.player
    private val conn = DBusConnectionBuilder.forSessionBus().build()

    fun start() {
        conn.requestBusName("org.mpris.MediaPlayer2.square_yuma")
        conn.exportObject(PATH, this)
        state.scope.launch {
            combine(p.current, p.isPlaying) { s, on -> s?.id to on }.collect {
                runCatching {
                    conn.sendMessage(Properties.PropertiesChanged(PATH, PLAYER, mapOf("Metadata" to metadata(), "PlaybackStatus" to Variant(status())), emptyList()))
                }
            }
        }
    }

    private fun status() = when { p.current.value == null -> "Stopped"; p.isPlaying.value -> "Playing"; else -> "Paused" }

    private fun metadata(): Variant<*> {
        val s = p.current.value
        val m = linkedMapOf<String, Variant<*>>("mpris:trackid" to Variant(DBusPath("/dev/lelonio/square/track/" + (s?.id?.filter { it.isLetterOrDigit() }?.ifEmpty { null } ?: "none"))))
        if (s != null) {
            m["xesam:title"] = Variant(s.title)
            s.artist?.let { m["xesam:artist"] = Variant(listOf(it), "as") }
            s.album?.let { m["xesam:album"] = Variant(it) }
            m["mpris:length"] = Variant(s.duration * 1_000_000L)
            state.cover(s.coverArt, 300)?.let { m["mpris:artUrl"] = Variant(it) }
        }
        return Variant(m, "a{sv}")
    }

    private fun props(iface: String): Map<String, Variant<*>> = when (iface) {
        ROOT -> mapOf(
            "CanQuit" to Variant(false), "CanRaise" to Variant(false), "HasTrackList" to Variant(false),
            "Identity" to Variant("Square-Yuma"), "DesktopEntry" to Variant("square-yuma"),
            "SupportedUriSchemes" to Variant(emptyList<String>(), "as"), "SupportedMimeTypes" to Variant(emptyList<String>(), "as"),
        )
        PLAYER -> mapOf(
            "PlaybackStatus" to Variant(status()), "LoopStatus" to Variant("None"), "Rate" to Variant(1.0),
            "Shuffle" to Variant(p.shuffle.value), "Metadata" to metadata(), "Volume" to Variant(p.volume.value.toDouble()),
            "Position" to Variant(p.positionMs.value * 1000L), "MinimumRate" to Variant(1.0), "MaximumRate" to Variant(1.0),
            "CanGoNext" to Variant(true), "CanGoPrevious" to Variant(true), "CanPlay" to Variant(true),
            "CanPause" to Variant(true), "CanSeek" to Variant(true), "CanControl" to Variant(true),
        )
        else -> emptyMap()
    }

    @Suppress("UNCHECKED_CAST")
    override fun <A : Any?> Get(iface: String, name: String): A = props(iface)[name] as A
    override fun GetAll(iface: String): Map<String, Variant<*>> = props(iface)
    override fun <A : Any?> Set(iface: String, name: String, value: A) {
        if (iface == PLAYER && name == "Volume") (value as? Number)?.let { p.volume.value = it.toFloat().coerceIn(0f, 1f) }
    }

    override fun Raise() {}
    override fun Quit() {}
    override fun Next() = p.next()
    override fun Previous() = p.previous()
    override fun Pause() = p.pause()
    override fun PlayPause() = p.toggle()
    override fun Stop() = p.pause()
    override fun Play() { if (!p.isPlaying.value) p.toggle() }
    override fun Seek(offset: Long) = p.seek(p.positionMs.value + offset / 1000)
    override fun SetPosition(trackId: DBusPath, position: Long) = p.seek(position / 1000)
    override fun OpenUri(uri: String) {}
    override fun getObjectPath() = PATH
}
