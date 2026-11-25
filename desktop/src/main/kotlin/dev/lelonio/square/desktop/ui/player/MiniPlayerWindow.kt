package dev.lelonio.square.desktop.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalFontFamilyResolver
import dev.lelonio.square.ui.theme.SharedFontFamilyResolver
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.SkipBack
import com.adamglin.phosphoricons.fill.SkipForward
import com.adamglin.phosphoricons.regular.CornersOut
import com.adamglin.phosphoricons.regular.X
import dev.lelonio.square.desktop.AppState
import dev.lelonio.square.desktop.MiniPlayerBounds
import dev.lelonio.square.desktop.PreferencesBoundsKeyValue
import dev.lelonio.square.desktop.ThemeMode
import dev.lelonio.square.desktop.WindowBoundsSnapshot
import dev.lelonio.square.desktop.str
import dev.lelonio.square.desktop.ui.components.Artwork
import dev.lelonio.square.desktop.ui.components.Glyph
import dev.lelonio.square.desktop.ui.components.PlayGlyph
import dev.lelonio.square.desktop.ui.components.rememberArtworkColor
import dev.lelonio.square.desktop.uiPrefs
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import dev.lelonio.square.ui.theme.SquareTheme
import dev.lelonio.square.ui.theme.pageColorFor
import kotlin.math.roundToInt

/**
 * Separate small always-on-top undecorated Compose window for the Floating Mini-Player.
 * Draggable anywhere, remembers per-monitor position, and clamps on-screen.
 */
@Composable
fun MiniPlayerWindow(
    state: AppState,
    savedMainBounds: WindowBoundsSnapshot?,
    onRestoreMain: () -> Unit,
    onCloseMini: () -> Unit,
) {
    val store = remember { PreferencesBoundsKeyValue(uiPrefs) }
    val monitors = remember { MiniPlayerBounds.currentMonitors() }
    val initialPos = remember(savedMainBounds) {
        val cx = savedMainBounds?.let { it.x + it.width / 2 }
        val cy = savedMainBounds?.let { it.y + it.height / 2 }
        MiniPlayerBounds.resolveInitialPosition(
            store = store,
            monitors = monitors,
            width = MiniPlayerBounds.WIDTH_DP,
            height = MiniPlayerBounds.HEIGHT_DP,
            preferredCenterX = cx,
            preferredCenterY = cy,
        )
    }

    val miniState = rememberWindowState(
        position = WindowPosition(initialPos.first.dp, initialPos.second.dp),
        size = DpSize(MiniPlayerBounds.WIDTH_DP.dp, MiniPlayerBounds.HEIGHT_DP.dp),
    )

    Window(
        onCloseRequest = onCloseMini,
        title = "Square-Yuma Mini",
        icon = painterResource("square.png"),
        state = miniState,
        undecorated = true,
        resizable = false,
        alwaysOnTop = true,
        transparent = false,
        onPreviewKeyEvent = state::onKey,
        onKeyEvent = state::onPostKey,
    ) {
        LaunchedEffect(Unit) {
            val activeMonitors = MiniPlayerBounds.currentMonitors()
            val (cx, cy) = MiniPlayerBounds.clampMiniPlayerPosition(
                window.x,
                window.y,
                window.width.coerceAtLeast(MiniPlayerBounds.WIDTH_DP),
                window.height.coerceAtLeast(MiniPlayerBounds.HEIGHT_DP),
                activeMonitors,
            )
            if (cx != window.x || cy != window.y) {
                window.setLocation(cx, cy)
            }
        }

        val song by state.player.current.collectAsState()
        val playing by state.player.isPlaying.collectAsState()
        val posMs by state.player.positionMs.collectAsState()
        val chain by state.player.signalChain.collectAsState()
        val seed by rememberArtworkColor(state.cover(song?.coverArt, 300))
        val systemDark = isSystemInDarkTheme()
        val darkTheme = when (state.themeMode) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            ThemeMode.SYSTEM -> systemDark
        }

        SquareTheme(seed = seed, darkTheme = darkTheme) {
            CompositionLocalProvider(
                LocalFontFamilyResolver provides SharedFontFamilyResolver,
                androidx.compose.material3.LocalContentColor provides Ink,
            ) {
                val baseColor = pageColorFor(seed)
                val bgTop = if (darkTheme) Color(0xFF1A1B22) else Color(0xFFF3F4F8)
                val dur = song?.let(state.local::durationMsOf) ?: 0L
                val progress = if (dur > 0L) (posMs.toFloat() / dur.toFloat()).coerceIn(0f, 1f) else 0f

                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    baseColor.copy(alpha = if (darkTheme) 0.45f else 0.25f),
                                    bgTop,
                                ),
                            ),
                        )
                        .background(bgTop.copy(alpha = 0.65f))
                        .border(1.dp, Ink.copy(alpha = 0.14f))
                        .pointerInput(window) {
                            detectDragGestures(
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val nx = window.x + dragAmount.x.roundToInt()
                                    val ny = window.y + dragAmount.y.roundToInt()
                                    window.setLocation(nx, ny)
                                },
                                onDragEnd = {
                                    val liveMonitors = MiniPlayerBounds.currentMonitors()
                                    val (cx, cy) = MiniPlayerBounds.savePosition(
                                        store = store,
                                        x = window.x,
                                        y = window.y,
                                        width = window.width,
                                        height = window.height,
                                        monitors = liveMonitors,
                                    )
                                    window.setLocation(cx, cy)
                                },
                            )
                        },
                ) {
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Artwork(
                                state.cover(song?.coverArt, 120),
                                Modifier
                                    .size(54.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onRestoreMain() }
                                    .pointerHoverIcon(PointerIcon.Hand),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    song?.title ?: str("desktop_not_playing"),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    song?.artist.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = InkDim,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Glyph(PhosphorIcons.Fill.SkipBack, "previous", size = 16.dp) { state.player.previous() }
                                Box(Modifier.mixRing(chain.mixing)) {
                                    PlayGlyph(playing, 20.dp) { state.player.toggle() }
                                }
                                Glyph(PhosphorIcons.Fill.SkipForward, "next", size = 16.dp) { state.player.next() }
                                Glyph(PhosphorIcons.Regular.CornersOut, "desktop_expand_main", size = 14.dp) { onRestoreMain() }
                                Glyph(PhosphorIcons.Regular.X, "close", size = 14.dp) { onCloseMini() }
                            }
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .background(Ink.copy(alpha = 0.14f)),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(progress)
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                        }
                    }
                }
            }
        }
    }
}
