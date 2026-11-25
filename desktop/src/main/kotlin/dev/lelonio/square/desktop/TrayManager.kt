package dev.lelonio.square.desktop

import dev.lelonio.square.common.SharedHttp
import dev.lelonio.square.desktop.nativeaudio.NativeEngine
import dev.lelonio.square.desktop.ui.components.Http
import dev.lelonio.square.desktop.ui.components.clearArtworkCaches
import dev.lelonio.square.desktop.ui.components.clearBlurCache
import java.awt.KeyboardFocusManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.awt.Container
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.Window
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/**
 * Manages the AWT SystemTray icon & menu and Skia/artwork cache cleanup for headless low-RAM tray mode.
 */
class TrayManager(
    private val state: AppState,
    private val onPlayPause: () -> Unit,
    private val onNext: () -> Unit,
    private val onPrevious: () -> Unit,
    private val onMiniPlayer: () -> Unit,
    private val onOpen: () -> Unit,
    private val onQuit: () -> Unit,
) {
    private var trayIcon: TrayIcon? = null
    private var playPauseItem: MenuItem? = null
    private var statusJob: Job? = null

    val isSupported: Boolean
        get() = !GraphicsEnvironment.isHeadless() && runCatching { SystemTray.isSupported() }.getOrDefault(false)

    fun install(): Boolean {
        if (!isSupported) return false
        if (trayIcon != null) return true
        return runCatching {
            val tray = SystemTray.getSystemTray()
            val iconSize = tray.trayIconSize
            val stream = TrayManager::class.java.classLoader?.getResourceAsStream("square.png")
            val rawImage = stream?.use { ImageIO.read(it) }
            val image: Image = rawImage?.getScaledInstance(iconSize.width, iconSize.height, Image.SCALE_SMOOTH)
                ?: java.awt.image.BufferedImage(iconSize.width.coerceAtLeast(16), iconSize.height.coerceAtLeast(16), java.awt.image.BufferedImage.TYPE_INT_ARGB)

            val playItem = MenuItem(playLabel()).apply {
                addActionListener { onPlayPause() }
            }
            playPauseItem = playItem

            val popup = PopupMenu().apply {
                add(playItem)
                add(MenuItem(str("next")).apply { addActionListener { onNext() } })
                add(MenuItem(str("previous")).apply { addActionListener { onPrevious() } })
                addSeparator()
                add(MenuItem(str("desktop_mini_player")).apply { addActionListener { onMiniPlayer() } })
                add(MenuItem(str("desktop_tray_open")).apply { addActionListener { onOpen() } })
                addSeparator()
                add(MenuItem(str("desktop_tray_quit")).apply { addActionListener { onQuit() } })
            }

            val icon = TrayIcon(image, "Square-Yuma", popup).apply {
                isImageAutoSize = true
                addActionListener { onOpen() }
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        if (SwingUtilities.isLeftMouseButton(e) && e.clickCount == 1) {
                            onOpen()
                        }
                    }
                })
            }
            tray.add(icon)
            trayIcon = icon

            statusJob?.cancel()
            statusJob = state.scope.launch {
                combine(state.player.isPlaying, state.player.current) { playing, _ -> playing }.collect {
                    EventQueue.invokeLater { playPauseItem?.label = playLabel() }
                }
            }
            true
        }.getOrDefault(false)
    }

    private fun playLabel(): String =
        if (state.player.isPlaying.value) str("pause") else str("play")

    fun showFirstNotice() {
        val icon = trayIcon ?: return
        runCatching {
            EventQueue.invokeLater {
                icon.displayMessage("Square-Yuma", str("desktop_tray_notice"), TrayIcon.MessageType.INFO)
            }
        }
    }

    fun remove() {
        statusJob?.cancel()
        statusJob = null
        val icon = trayIcon ?: return
        trayIcon = null
        runCatching { SystemTray.getSystemTray().remove(icon) }
    }
}

/**
 * Releases Skia GPU resources reachable from AWT/Compose windows, clears artwork/blur LRU caches,
 * and suggests GC when entering headless tray mode.
 */
object LowRamReleaser {
    fun releaseWindowGpuResources(window: Window?) {
        if (window == null) return
        runCatching {
            val layers = ArrayList<Any>()
            collectSkiaLayers(window, layers)
            for (layer in layers) {
                releaseSkiaLayerContext(layer)
            }
        }
    }

    private fun collectSkiaLayers(component: java.awt.Component, out: MutableList<Any>) {
        if (component.javaClass.name == "org.jetbrains.skiko.SkiaLayer") {
            out += component
        }
        if (component is Container) {
            for (child in component.components) {
                collectSkiaLayers(child, out)
            }
        }
    }

    private fun releaseSkiaLayerContext(layer: Any) {
        runCatching {
            val redrawer = layer.javaClass.methods.firstOrNull { it.name.startsWith("getRedrawer") }?.invoke(layer)
                ?: readField(layer, "redrawerManager")?.let { mgr ->
                    mgr.javaClass.methods.firstOrNull { it.name == "findRenderers" }?.invoke(mgr)
                }
            val candidates = when (redrawer) {
                is Iterable<*> -> redrawer.filterNotNull()
                else -> listOfNotNull(redrawer)
            }
            for (r in candidates) {
                val handler = readField(r, "contextHandler") ?: continue
                val ctx = handler.javaClass.methods.firstOrNull { it.name == "getContext" }?.invoke(handler)
                    ?: readField(handler, "context")
                if (ctx is org.jetbrains.skia.DirectContext && !ctx.isClosed) {
                    runCatching { ctx.flush() }
                    runCatching { ctx.resourceCacheLimit = 0L }
                }
            }
        }
    }

    private fun readField(target: Any, name: String): Any? {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            val field = runCatching { cls.getDeclaredField(name) }.getOrNull()
            if (field != null) {
                field.isAccessible = true
                return field.get(target)
            }
            cls = cls.superclass
        }
        return null
    }

    fun clearSkiaCaches() {
        runCatching { org.jetbrains.skia.Graphics.purgeAllCaches() }
    }

    fun releaseCachesAndGc(
        activeWindows: Collection<Window> = emptyList(),
        onReleaseModels: (() -> Unit)? = null,
    ) {
        activeWindows.forEach { releaseWindowGpuResources(it) }
        if (activeWindows.isEmpty()) {
            runCatching { KeyboardFocusManager.getCurrentKeyboardFocusManager().clearGlobalFocusOwner() }
        }
        clearArtworkCaches()
        clearBlurCache()
        runCatching { Http.client.connectionPool.evictAll() }
        SharedHttp.evictAll()
        onReleaseModels?.invoke()
        clearSkiaCaches()
        System.gc()
        NativeEngine.trimMemory()
    }
}
