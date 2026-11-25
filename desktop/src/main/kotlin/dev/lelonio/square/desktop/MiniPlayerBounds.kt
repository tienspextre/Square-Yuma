package dev.lelonio.square.desktop

import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.util.prefs.Preferences
import kotlin.math.abs

data class MonitorBounds(
    val id: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    val centerX: Int get() = x + width / 2
    val centerY: Int get() = y + height / 2

    fun contains(px: Int, py: Int): Boolean =
        px >= x && px < x + width && py >= y && py < y + height
}

data class WindowBoundsSnapshot(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val maximized: Boolean = false,
)

interface BoundsKeyValue {
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
    fun getString(key: String, default: String): String
    fun putString(key: String, value: String)
    fun contains(key: String): Boolean
}

class PreferencesBoundsKeyValue(private val prefs: Preferences) : BoundsKeyValue {
    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
    override fun putInt(key: String, value: Int) = prefs.putInt(key, value)
    override fun getString(key: String, default: String): String = prefs.get(key, default)
    override fun putString(key: String, value: String) = prefs.put(key, value)
    override fun contains(key: String): Boolean = prefs.get(key, null) != null
}

object MiniPlayerBounds {
    const val WIDTH_DP = 360
    const val HEIGHT_DP = 88
    private const val MARGIN_PX = 24

    fun sanitizeMonitorId(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "default" }

    fun currentMonitors(): List<MonitorBounds> {
        if (GraphicsEnvironment.isHeadless()) return emptyList()
        return runCatching {
            val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
            val toolkit = Toolkit.getDefaultToolkit()
            ge.screenDevices.mapIndexed { idx, dev ->
                val gc = dev.defaultConfiguration
                val b = gc.bounds
                val insets = runCatching { toolkit.getScreenInsets(gc) }.getOrNull()
                val left = insets?.left ?: 0
                val top = insets?.top ?: 0
                val right = insets?.right ?: 0
                val bottom = insets?.bottom ?: 0
                val w = (b.width - left - right).coerceAtLeast(160)
                val h = (b.height - top - bottom).coerceAtLeast(120)
                MonitorBounds(
                    id = sanitizeMonitorId(dev.iDstring ?: "screen-$idx"),
                    x = b.x + left,
                    y = b.y + top,
                    width = w,
                    height = h,
                )
            }
        }.getOrDefault(emptyList())
    }

    fun findMonitorForPoint(px: Int, py: Int, monitors: List<MonitorBounds>): MonitorBounds? {
        if (monitors.isEmpty()) return null
        monitors.firstOrNull { it.contains(px, py) }?.let { return it }
        return monitors.minByOrNull { m ->
            val dx = (px - m.centerX).toLong()
            val dy = (py - m.centerY).toLong()
            dx * dx + dy * dy
        }
    }

    fun clampToMonitor(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        monitor: MonitorBounds,
    ): Pair<Int, Int> {
        val maxX = (monitor.x + monitor.width - width).coerceAtLeast(monitor.x)
        val maxY = (monitor.y + monitor.height - height).coerceAtLeast(monitor.y)
        return x.coerceIn(monitor.x, maxX) to y.coerceIn(monitor.y, maxY)
    }

    fun clampMiniPlayerPosition(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        monitors: List<MonitorBounds>,
    ): Pair<Int, Int> {
        if (monitors.isEmpty()) return x to y
        val cx = x + width / 2
        val cy = y + height / 2
        val target = findMonitorForPoint(cx, cy, monitors) ?: monitors.first()
        return clampToMonitor(x, y, width, height, target)
    }

    fun defaultMiniPlayerPosition(
        monitor: MonitorBounds,
        width: Int,
        height: Int,
        margin: Int = MARGIN_PX,
    ): Pair<Int, Int> {
        val rawX = monitor.x + monitor.width - width - margin
        val rawY = monitor.y + monitor.height - height - margin
        return clampToMonitor(rawX, rawY, width, height, monitor)
    }

    /** Picks the closest monitor for [preferredCenter], loads saved position for it if any, and clamps on-screen. */
    fun resolveInitialPosition(
        store: BoundsKeyValue,
        monitors: List<MonitorBounds>,
        width: Int,
        height: Int,
        preferredCenterX: Int? = null,
        preferredCenterY: Int? = null,
    ): Pair<Int, Int> {
        if (monitors.isEmpty()) return 40 to 40
        val monitor = when {
            preferredCenterX != null && preferredCenterY != null ->
                findMonitorForPoint(preferredCenterX, preferredCenterY, monitors)
            else -> {
                val savedMon = store.getString("miniMonitorId", "")
                monitors.firstOrNull { it.id == savedMon }
            }
        } ?: monitors.first()

        val kx = "miniPos.${monitor.id}.x"
        val ky = "miniPos.${monitor.id}.y"
        return if (store.contains(kx) && store.contains(ky)) {
            val sx = store.getInt(kx, monitor.x)
            val sy = store.getInt(ky, monitor.y)
            clampToMonitor(sx, sy, width, height, monitor)
        } else {
            defaultMiniPlayerPosition(monitor, width, height)
        }
    }

    fun savePosition(
        store: BoundsKeyValue,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        monitors: List<MonitorBounds>,
    ): Pair<Int, Int> {
        if (monitors.isEmpty()) return x to y
        val cx = x + width / 2
        val cy = y + height / 2
        val mon = findMonitorForPoint(cx, cy, monitors) ?: monitors.first()
        val (clampedX, clampedY) = clampToMonitor(x, y, width, height, mon)
        // Avoid accidental 1px drift jitter on tiny drags
        if (abs(clampedX - store.getInt("miniPos.${mon.id}.x", Int.MIN_VALUE)) >= 1 ||
            abs(clampedY - store.getInt("miniPos.${mon.id}.y", Int.MIN_VALUE)) >= 1
        ) {
            store.putString("miniMonitorId", mon.id)
            store.putInt("miniPos.${mon.id}.x", clampedX)
            store.putInt("miniPos.${mon.id}.y", clampedY)
        }
        return clampedX to clampedY
    }
}
