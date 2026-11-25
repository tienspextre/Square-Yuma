package dev.lelonio.square.desktop.ui.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState
import dev.lelonio.square.desktop.AppState
import dev.lelonio.square.desktop.DropTargetHandler
import dev.lelonio.square.desktop.DropZone
import dev.lelonio.square.desktop.NavMove
import dev.lelonio.square.desktop.Screen
import dev.lelonio.square.desktop.ThemeMode
import dev.lelonio.square.desktop.str
import dev.lelonio.square.desktop.ui.browse.AlbumScreen
import dev.lelonio.square.desktop.ui.browse.ArtistScreen
import dev.lelonio.square.desktop.ui.browse.DownloadsScreen
import dev.lelonio.square.desktop.ui.browse.LocalAlbumScreen
import dev.lelonio.square.desktop.ui.browse.LocalArtistScreen
import dev.lelonio.square.desktop.ui.browse.MixScreen
import dev.lelonio.square.desktop.ui.browse.MonthScreen
import dev.lelonio.square.desktop.ui.browse.PlaylistScreen
import dev.lelonio.square.desktop.ui.components.AmbientBackground
import dev.lelonio.square.desktop.ui.components.Artwork
import dev.lelonio.square.desktop.ui.components.UpdateNoticeOverlay
import dev.lelonio.square.desktop.ui.components.rememberArtworkColor
import dev.lelonio.square.desktop.ui.home.HomeScreen
import dev.lelonio.square.desktop.ui.library.LibraryScreen
import dev.lelonio.square.desktop.ui.library.LocalFoldersScreen
import dev.lelonio.square.desktop.ui.library.PlaylistsScreen
import dev.lelonio.square.desktop.ui.player.ExpandSpec
import dev.lelonio.square.desktop.ui.player.NowPlaying
import dev.lelonio.square.desktop.ui.player.TransportBar
import dev.lelonio.square.desktop.ui.search.SearchScreen
import dev.lelonio.square.desktop.ui.settings.SettingsScreen
import dev.lelonio.square.ui.glass.GlassEffectConfig
import dev.lelonio.square.ui.glass.GlassStyle
import dev.lelonio.square.ui.glass.LocalAppBackdrop
import dev.lelonio.square.ui.glass.LocalGlassEffectConfig
import dev.lelonio.square.ui.glass.backdrop.backdrops.layerBackdrop
import dev.lelonio.square.ui.glass.backdrop.backdrops.rememberLayerBackdrop
import dev.lelonio.square.ui.glass.liquidGlass
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.Inter
import dev.lelonio.square.ui.theme.LocalLightTheme
import dev.lelonio.square.ui.theme.SquareTheme
import dev.lelonio.square.ui.theme.pageColorFor
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private val TransportHeight = 82.dp

@Composable
fun WindowScope.App(
    state: AppState,
    window: ComposeWindow,
    @Suppress("UNUSED_PARAMETER") windowState: WindowState,
    frameController: NativeWindowFrameController,
    onCloseWindow: () -> Unit,
) {
    val song by state.player.current.collectAsState()
    val seed by rememberArtworkColor(state.cover(song?.coverArt, 300))
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (state.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> systemDark
    }

    SquareTheme(seed = seed, darkTheme = darkTheme) {
        val base = MaterialTheme.typography
        val family = if (state.systemFont) FontFamily.Default else Inter
        fun TextStyle.withAppFont() = if (fontFamily == null) copy(fontFamily = family) else this
        MaterialTheme(
            typography = remember(base, family) {
                base.copy(
                    displayLarge = base.displayLarge.withAppFont(),
                    displayMedium = base.displayMedium.withAppFont(),
                    displaySmall = base.displaySmall.withAppFont(),
                    headlineLarge = base.headlineLarge.withAppFont(),
                    headlineMedium = base.headlineMedium.withAppFont(),
                    headlineSmall = base.headlineSmall.withAppFont(),
                    titleLarge = base.titleLarge.withAppFont(),
                    titleMedium = base.titleMedium.withAppFont(),
                    titleSmall = base.titleSmall.withAppFont(),
                    bodyLarge = base.bodyLarge.withAppFont(),
                    bodyMedium = base.bodyMedium.withAppFont(),
                    bodySmall = base.bodySmall.withAppFont(),
                    labelLarge = base.labelLarge.withAppFont(),
                    labelMedium = base.labelMedium.withAppFont(),
                    labelSmall = base.labelSmall.withAppFont(),
                )
            },
        ) {
            val config = state.glassProfile.config()
            val pageBackdrop = rememberLayerBackdrop()
            val scope = rememberCoroutineScope()
            val density = LocalDensity.current.density
            LaunchedEffect(state.nowPlaying) { state.expand.animateTo(if (state.nowPlaying) 1f else 0f, ExpandSpec) }
            LaunchedEffect(state.fullscreen) { frameController.setFullscreen(state.fullscreen) }
            DisposableEffect(window) {
                val target = DropTargetHandler.attachWindowDropTarget(window, state) { density }
                onDispose {
                    runCatching { target.isActive = false; window.dropTarget = null }
                }
            }

            CompositionLocalProvider(
                LocalContentColor provides Ink,
                LocalGlassEffectConfig provides config,
                LocalAppBackdrop provides pageBackdrop,
            ) {
                val chrome = chromeConfig(config)
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val sidebarCollapsed = shouldCollapseSidebar(maxWidth.value, state.sidebarCollapsedOverride)
                    val showInspector = state.inspectorOpen && maxWidth >= 820.dp

                    // Ambient + main content layer captured for glass chrome
                    Box(Modifier.fillMaxSize().layerBackdrop(pageBackdrop)) {
                        AmbientBackground(state.cover(song?.coverArt, 600), pageColorFor(seed), settleFrom = 0.3f)
                        val startPad = if (sidebarCollapsed) SidebarRailWidth + 20.dp else SidebarExpandedWidth + 20.dp
                        val endPad = if (showInspector) InspectorWidth + 20.dp else 10.dp
                        Content(
                            state,
                            Modifier
                                .fillMaxSize()
                                .padding(top = TitleBarHeight, start = startPad, end = endPad),
                        )
                    }

                    // Foreground shell: glass titlebar + left sidebar + right inspector + floating transport bar
                    Column(Modifier.fillMaxSize()) {
                        GlassTitleBar(
                            state = state,
                            config = chrome,
                            window = window,
                            frameController = frameController,
                            sidebarCollapsed = sidebarCollapsed,
                            onCloseWindow = onCloseWindow,
                        )
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            Row(Modifier.fillMaxSize().padding(10.dp)) {
                                Sidebar(state, chrome, collapsed = sidebarCollapsed)
                                Spacer(Modifier.weight(1f))
                                if (showInspector) {
                                    Spacer(Modifier.width(10.dp))
                                    InspectorPane(state, chrome)
                                }
                            }

                            val barStart = (if (sidebarCollapsed) SidebarRailWidth else SidebarExpandedWidth) + 24.dp
                            val barEnd = if (showInspector) InspectorWidth + 24.dp else 20.dp
                            TransportBar(
                                state = state,
                                config = chrome,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(start = barStart, end = barEnd, bottom = 14.dp)
                                    .fillMaxWidth()
                                    .height(TransportHeight),
                            )
                        }
                    }

                    if (state.expand.value > 0f) {
                        NowPlaying(
                            state,
                            onDrag = { d -> scope.launch { state.expand.snapTo((state.expand.value - d).coerceIn(0f, 1f)) } },
                        ) { velocity ->
                            val open = if (abs(velocity) > 0.8f) velocity < 0 else state.expand.value > 0.5f
                            if (open == state.nowPlaying) scope.launch { state.expand.animateTo(if (open) 1f else 0f, ExpandSpec) }
                            state.nowPlaying = open
                        }
                    }
                    SharedCover(state)
                    if (state.dropZoneHighlight == DropZone.MAIN) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .padding(8.dp)
                                .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), RoundedCornerShape(18.dp)),
                        )
                    }
                    AnimatedVisibility(
                        state.pendingDropFolders.isNotEmpty(),
                        Modifier.align(Alignment.BottomCenter).padding(bottom = TransportHeight + 24.dp),
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        val folders = state.pendingDropFolders
                        val prompt = if (folders.size == 1) {
                            str("desktop_drop_folder_prompt", folders.first().fileName?.toString() ?: folders.first().toString())
                        } else {
                            str("desktop_drop_folders_prompt", folders.size)
                        }
                        Row(
                            Modifier
                                .liquidGlass(chrome, RoundedCornerShape(50), highlightAlpha = 0.3f)
                                .padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(prompt, color = Ink, style = MaterialTheme.typography.bodyMedium)
                            TextButton({ DropTargetHandler.confirmAddDroppedFolders(state) }) {
                                Text(str("desktop_add"), color = MaterialTheme.colorScheme.primary)
                            }
                            TextButton({ state.pendingDropFolders = emptyList() }) {
                                Text(str("desktop_dismiss"), color = Ink)
                            }
                        }
                    }
                    AnimatedVisibility(
                        state.toast != null && state.pendingDropFolders.isEmpty(),
                        Modifier.align(Alignment.BottomCenter).padding(bottom = TransportHeight + 24.dp),
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        Text(
                            state.toast.orEmpty(),
                            color = Ink,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier
                                .liquidGlass(chrome, RoundedCornerShape(50), highlightAlpha = 0.3f)
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                        )
                    }
                    UpdateNoticeOverlay(
                        update = state.update,
                        visible = state.updateNoticeVisible,
                        downloading = state.updateDownloading,
                        progress = state.updateProgress,
                        errorMessage = state.updateError,
                        onAction = { state.applyUpdate() },
                        onDismiss = { state.dismissUpdateNotice() },
                    )
                }
            }
        }
    }
}

@Composable
private fun chromeConfig(config: GlassEffectConfig): GlassEffectConfig {
    val light = LocalLightTheme.current
    val defaults = GlassEffectConfig()
    fun scaled(tuned: Float, set: Float, default: Float) = (tuned * set / default).coerceIn(0f, 1f)
    val opacity = if (config.style == GlassStyle.TRANSPARENT) {
        if (light) 0.86f else 0.82f
    } else {
        scaled(if (light) 0.45f else 0.18f, config.surfaceOpacity, defaults.surfaceOpacity)
    }
    return config.copy(
        lensHeight = scaled(0.60f, config.lensHeight, defaults.lensHeight),
        lensAmount = scaled(0.69f, config.lensAmount, defaults.lensAmount),
        surfaceTintColor = if (config.surfaceTintColor.isSpecified) config.surfaceTintColor else if (light) Color(0xFFF5F5F7) else Color.White,
        surfaceOpacity = opacity,
    )
}

@Composable
private fun Content(state: AppState, modifier: Modifier) {
    AnimatedContent(
        targetState = state.currentScreen,
        modifier = modifier,
        transitionSpec = {
            when (state.navMove) {
                NavMove.Push -> (slideInHorizontally(tween(280)) { it / 5 } + fadeIn(tween(220))) togetherWith fadeOut(tween(180))
                NavMove.Pop -> fadeIn(tween(200)) togetherWith (slideOutHorizontally(tween(260)) { it / 5 } + fadeOut(tween(200)))
                NavMove.Root -> fadeIn(tween(180)) togetherWith fadeOut(tween(180))
            }.apply { targetContentZIndex = if (state.navMove == NavMove.Pop) -1f else 1f }
        },
    ) { screen ->
        when (screen) {
            Screen.Home -> HomeScreen(state)
            Screen.Library -> LibraryScreen(state)
            Screen.LocalFolders -> LocalFoldersScreen(state)
            Screen.Playlists -> PlaylistsScreen(state)
            Screen.Search -> SearchScreen(state)
            Screen.Settings -> SettingsScreen(state)
            is Screen.Album -> AlbumScreen(state, screen)
            is Screen.Artist -> ArtistScreen(state, screen)
            is Screen.LocalAlbum -> LocalAlbumScreen(state, screen)
            is Screen.LocalArtist -> LocalArtistScreen(state, screen)
            is Screen.Playlist -> PlaylistScreen(state, screen)
            is Screen.Mix -> MixScreen(state, screen)
            Screen.Month -> MonthScreen(state)
            Screen.Downloads -> DownloadsScreen(state)
        }
    }
}

@Composable
private fun SharedCover(state: AppState) {
    val e = state.expand.value
    val from = state.miniArt
    val to = state.bigArt
    if (e <= 0f || e >= 1f || from == null || to == null) return
    val song by state.player.current.collectAsState()
    val r = Rect(lerp(from.left, to.left, e), lerp(from.top, to.top, e), lerp(from.right, to.right, e), lerp(from.bottom, to.bottom, e))
    with(LocalDensity.current) {
        Box(
            Modifier.offset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
                .size(r.width.toDp(), r.height.toDp())
                .clip(RoundedCornerShape(lerp(9.dp, 18.dp, e.coerceIn(0f, 1f)))),
        ) {
            Artwork(state.cover(song?.coverArt, 900), Modifier.fillMaxSize())
        }
    }
}

private val LocalContentColor = androidx.compose.material3.LocalContentColor
