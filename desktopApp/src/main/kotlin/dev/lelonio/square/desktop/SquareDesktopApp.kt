package dev.lelonio.square.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Bold
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.bold.ArrowsOutSimple
import com.adamglin.phosphoricons.bold.CornersOut
import com.adamglin.phosphoricons.bold.Folder
import com.adamglin.phosphoricons.bold.Gear
import com.adamglin.phosphoricons.bold.HardDrives
import com.adamglin.phosphoricons.bold.House
import com.adamglin.phosphoricons.bold.MagnifyingGlass
import com.adamglin.phosphoricons.bold.Playlist
import com.adamglin.phosphoricons.bold.SidebarSimple
import com.adamglin.phosphoricons.bold.Sliders
import com.adamglin.phosphoricons.bold.Sparkle
import com.adamglin.phosphoricons.bold.SquaresFour
import com.adamglin.phosphoricons.bold.Waveform
import com.adamglin.phosphoricons.fill.MicrophoneStage
import com.adamglin.phosphoricons.fill.MusicNotes
import com.adamglin.phosphoricons.fill.Pause
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.fill.Shuffle
import com.adamglin.phosphoricons.fill.SkipBack
import com.adamglin.phosphoricons.fill.SkipForward
import com.adamglin.phosphoricons.fill.SpeakerHigh
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.ui.components.DesktopArtworkImage
import dev.lelonio.square.ui.glass.GlassEffectConfig
import dev.lelonio.square.ui.glass.LiquidButton
import dev.lelonio.square.ui.glass.LiquidSlider
import dev.lelonio.square.ui.glass.LiquidToggle
import dev.lelonio.square.ui.glass.LocalAppBackdrop
import dev.lelonio.square.ui.glass.LocalGlassEffectConfig
import dev.lelonio.square.ui.glass.backdrop.Backdrop
import dev.lelonio.square.ui.glass.backdrop.backdrops.layerBackdrop
import dev.lelonio.square.ui.glass.backdrop.backdrops.rememberLayerBackdrop
import dev.lelonio.square.ui.glass.liquidGlass
import dev.lelonio.square.ui.glass.shapes.ContinuousCapsule
import dev.lelonio.square.ui.glass.shapes.ContinuousRoundedRectangle
import dev.lelonio.square.ui.player.LyricsView
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import dev.lelonio.square.ui.theme.PageFloor
import dev.lelonio.square.ui.theme.SquareTheme
import dev.lelonio.square.ui.theme.softShadow
import javax.swing.JFileChooser
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun SquareDesktopApp(
    controller: DesktopMusicController,
    modifier: Modifier = Modifier,
) {
    val glassConfig by controller.glassConfig.collectAsState()
    val layoutMode by controller.layoutMode.collectAsState()
    val bassPulse by controller.player.bassPulse.collectAsState()

    var dominantSeed by remember { mutableStateOf(Color(0xFF7C5CE6)) }
    var secondarySeed by remember { mutableStateOf(Color(0xFF2563EB)) }

    val animatedPrimary by animateColorAsState(dominantSeed, tween(900), label = "primarySeed")
    val animatedSecondary by animateColorAsState(secondarySeed, tween(900), label = "secondarySeed")

    SquareTheme(seed = animatedPrimary, darkTheme = true) {
        val appBackdrop = rememberLayerBackdrop {
            drawRect(PageFloor)
            drawContent()
        }

        CompositionLocalProvider(
            LocalGlassEffectConfig provides glassConfig,
            LocalAppBackdrop provides appBackdrop,
        ) {
            Box(modifier = modifier.fillMaxSize().background(PageFloor)) {
                // Background Layer captured into appBackdrop for Liquid Glass refraction!
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .layerBackdrop(appBackdrop),
                ) {
                    AmbientMeshBackground(
                        primaryColor = animatedPrimary,
                        secondaryColor = animatedSecondary,
                        bassPulse = bassPulse,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // Foreground Spatial UI Panels (refract appBackdrop cleanly without recursion)
                Box(modifier = Modifier.fillMaxSize()) {
                    when (layoutMode) {
                        DesktopLayoutMode.STUDIO_3PANE -> {
                            StudioThreePaneContent(
                                controller = controller,
                                backdrop = appBackdrop,
                                glassConfig = glassConfig,
                                onPaletteLoaded = { c1, c2 ->
                                    dominantSeed = c1
                                    secondarySeed = c2
                                },
                            )
                        }
                        DesktopLayoutMode.IMMERSION_STAGE -> {
                            ImmersionStageView(
                                controller = controller,
                                backdrop = appBackdrop,
                                glassConfig = glassConfig,
                                onPaletteLoaded = { c1, c2 ->
                                    dominantSeed = c1
                                    secondarySeed = c2
                                },
                            )
                        }
                        DesktopLayoutMode.MINI_CUBE -> {
                            MiniCubePlayerView(
                                controller = controller,
                                backdrop = appBackdrop,
                                glassConfig = glassConfig,
                                onPaletteLoaded = { c1, c2 ->
                                    dominantSeed = c1
                                    secondarySeed = c2
                                },
                            )
                        }
                    }
                }

                // Floating Liquid Glass Bottom Transport Dock (only in 3-Pane Studio Mode)
                if (layoutMode == DesktopLayoutMode.STUDIO_3PANE) {
                    FloatingBottomTransportDock(
                        controller = controller,
                        backdrop = appBackdrop,
                        glassConfig = glassConfig,
                        onPaletteLoaded = { c1, c2 ->
                            dominantSeed = c1
                            secondarySeed = c2
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AmbientMeshBackground(
    primaryColor: Color,
    secondaryColor: Color,
    bassPulse: Float,
    modifier: Modifier = Modifier,
) {
    val infinite = rememberInfiniteTransition(label = "ambientMesh")
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 22_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "meshPhase",
    )

    Canvas(modifier = modifier) {
        drawRect(PageFloor)
        val w = size.width
        val h = size.height
        val radius1 = (maxOf(w, h) * (0.55f + bassPulse * 0.12f)).coerceAtLeast(100f)
        val radius2 = (maxOf(w, h) * (0.48f + bassPulse * 0.10f)).coerceAtLeast(100f)

        val c1 = Offset(
            x = w * (0.28f + 0.16f * cos(phase)),
            y = h * (0.30f + 0.14f * sin(phase)),
        )
        val c2 = Offset(
            x = w * (0.74f + 0.15f * sin(phase * 0.8f)),
            y = h * (0.68f + 0.15f * cos(phase * 0.8f)),
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    primaryColor.copy(alpha = (0.36f + bassPulse * 0.16f).coerceAtMost(0.65f)),
                    Color.Transparent,
                ),
                center = c1,
                radius = radius1,
            ),
            radius = radius1,
            center = c1,
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    secondaryColor.copy(alpha = (0.28f + bassPulse * 0.12f).coerceAtMost(0.55f)),
                    Color.Transparent,
                ),
                center = c2,
                radius = radius2,
            ),
            radius = radius2,
            center = c2,
        )
    }
}

@Composable
private fun StudioThreePaneContent(
    controller: DesktopMusicController,
    backdrop: Backdrop,
    glassConfig: GlassEffectConfig,
    onPaletteLoaded: (Color, Color) -> Unit,
) {
    val showSidecar by controller.showRightSidecar.collectAsState()

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 114.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // PANE 1: Left Floating Liquid Glass Navigation & Sources Sidebar
        LeftLiquidSidebar(
            controller = controller,
            backdrop = backdrop,
            glassConfig = glassConfig,
            modifier = Modifier.width(256.dp).fillMaxHeight(),
        )

        // PANE 2: Center Main Stage (Search Bar + Hero + Shelves + Audiophile Track Table)
        CenterMainStage(
            controller = controller,
            backdrop = backdrop,
            glassConfig = glassConfig,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )

        // PANE 3: Right Now Playing & Karaoke Sidecar
        AnimatedVisibility(visible = showSidecar) {
            RightNowPlayingSidecar(
                controller = controller,
                backdrop = backdrop,
                glassConfig = glassConfig,
                onPaletteLoaded = onPaletteLoaded,
                modifier = Modifier.width(332.dp).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun LeftLiquidSidebar(
    controller: DesktopMusicController,
    backdrop: Backdrop,
    glassConfig: GlassEffectConfig,
    modifier: Modifier = Modifier,
) {
    val activeSource by controller.activeSource.collectAsState()
    val activeTab by controller.activeNavTab.collectAsState()
    val playlists by controller.playlists.collectAsState()
    val albums by controller.albums.collectAsState()
    val selectedPlaylist by controller.selectedPlaylist.collectAsState()
    val serverStatus by controller.serverStatus.collectAsState()
    val connectionMsg by controller.connectionMessage.collectAsState()

    val sidebarShape = ContinuousRoundedRectangle(24.dp)
    val accent = MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier
            .liquidGlass(glassConfig, sidebarShape, ownBackdrop = backdrop)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Brand Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(ContinuousRoundedRectangle(10.dp))
                    .background(accent.copy(alpha = 0.28f))
                    .border(1.dp, accent.copy(alpha = 0.7f), ContinuousRoundedRectangle(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = PhosphorIcons.Bold.Waveform,
                    contentDescription = null,
                    tint = Ink,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column {
                Text(
                    text = "SQUARE × YUMA",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
                    color = Ink,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (serverStatus != null || activeSource == DesktopMusicSource.LOCAL_FILES) Color(0xFF22C55E) else Color(0xFFF59E0B)),
                    )
                    Text(
                        text = connectionMsg,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp),
                        color = InkDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Source Switcher Pill (NAS Subsonic vs Local Lossless)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ContinuousCapsule())
                .background(Color.Black.copy(alpha = 0.25f))
                .border(1.dp, Color.White.copy(alpha = 0.10f), ContinuousCapsule())
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            DesktopMusicSource.entries.forEach { src ->
                val selected = src == activeSource
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(ContinuousCapsule())
                        .background(if (selected) accent.copy(alpha = 0.32f) else Color.Transparent)
                        .clickable { controller.setSource(src) }
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = src.title,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        ),
                        color = if (selected) Ink else InkDim,
                    )
                }
            }
        }

        // Main Navigation Tabs
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DesktopNavTab.entries.forEach { tab ->
                val selected = tab == activeTab
                val icon = when (tab) {
                    DesktopNavTab.HOME -> PhosphorIcons.Bold.House
                    DesktopNavTab.EXPLORE -> PhosphorIcons.Bold.SquaresFour
                    DesktopNavTab.LIBRARY -> PhosphorIcons.Bold.Playlist
                    DesktopNavTab.AUTOMIX_LAB -> PhosphorIcons.Bold.Sliders
                    DesktopNavTab.SETTINGS -> PhosphorIcons.Bold.Gear
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) Color.White.copy(alpha = 0.14f) else Color.Transparent)
                        .border(
                            width = 1.dp,
                            color = if (selected) Color.White.copy(alpha = 0.24f) else Color.Transparent,
                            shape = RoundedCornerShape(12.dp),
                        )
                        .clickable { controller.setNavTab(tab) }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = tab.label,
                        tint = if (selected) accent else InkDim,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        color = if (selected) Ink else InkDim,
                    )
                }
            }
        }

        // Pinned Playlists & Albums List
        Text(
            text = if (playlists.isNotEmpty()) "PLAYLISTS & COLLECTIONS" else "RECENT ALBUMS",
            style = MaterialTheme.typography.labelLarge,
            color = InkDim,
            modifier = Modifier.padding(top = 4.dp),
        )

        val quickItems = (playlists + albums).distinctBy { it.uri }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(quickItems, key = { it.uri }) { item ->
                val isSelected = selectedPlaylist?.uri == item.uri
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) accent.copy(alpha = 0.20f) else Color.Transparent)
                        .clickable {
                            controller.selectPlaylistOrAlbum(item)
                            if (activeTab != DesktopNavTab.HOME && activeTab != DesktopNavTab.LIBRARY) {
                                controller.setNavTab(DesktopNavTab.HOME)
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    DesktopArtworkImage(
                        artworkUrl = item.artworkUrl,
                        titleFallback = item.name,
                        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            ),
                            color = Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = item.subtitle ?: "${item.songCount} tracks",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = InkDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CenterMainStage(
    controller: DesktopMusicController,
    backdrop: Backdrop,
    glassConfig: GlassEffectConfig,
    modifier: Modifier = Modifier,
) {
    val activeTab by controller.activeNavTab.collectAsState()
    val searchQuery by controller.searchQuery.collectAsState()
    val searchResults by controller.searchResults.collectAsState()
    val showSidecar by controller.showRightSidecar.collectAsState()

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Top Floating Command & Search Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val searchShape = ContinuousCapsule()
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .liquidGlass(glassConfig, searchShape, ownBackdrop = backdrop)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = PhosphorIcons.Bold.MagnifyingGlass,
                    contentDescription = "Search",
                    tint = InkDim,
                    modifier = Modifier.size(16.dp),
                )
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (searchQuery.isEmpty()) {
                        Text(
                            text = "Search NAS Subsonic & Local Lossless tracks, albums, artists… (Ctrl+K)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = InkDim,
                            maxLines = 1,
                        )
                    }
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { controller.updateSearchQuery(it) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = Ink),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (searchQuery.isNotEmpty()) {
                    Text(
                        text = "Clear",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { controller.updateSearchQuery("") },
                    )
                }
            }

            // Quick Action Liquid Buttons
            LiquidButton(
                onClick = { controller.toggleImmersionMode() },
                backdrop = backdrop,
            ) {
                Icon(PhosphorIcons.Bold.CornersOut, contentDescription = null, tint = Ink, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text("Immersion Stage", style = MaterialTheme.typography.bodySmall, color = Ink)
            }

            LiquidButton(
                onClick = { controller.toggleMiniCubeMode() },
                backdrop = backdrop,
            ) {
                Icon(PhosphorIcons.Bold.ArrowsOutSimple, contentDescription = null, tint = Ink, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text("Glass Cube", style = MaterialTheme.typography.bodySmall, color = Ink)
            }

            LiquidButton(
                onClick = { controller.toggleRightSidecar() },
                backdrop = backdrop,
                tint = if (showSidecar) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else Color.Unspecified,
            ) {
                Icon(PhosphorIcons.Bold.SidebarSimple, contentDescription = "Toggle Sidecar", tint = Ink, modifier = Modifier.size(16.dp))
            }
        }

        // Main Content Pane
        val stageShape = ContinuousRoundedRectangle(24.dp)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .liquidGlass(glassConfig, stageShape, ownBackdrop = backdrop)
                .clip(stageShape),
        ) {
            if (searchQuery.isNotBlank()) {
                SearchResultsStage(
                    query = searchQuery,
                    results = searchResults,
                    controller = controller,
                )
            } else {
                AnimatedContent(targetState = activeTab, label = "centerMainStage") { tab ->
                    when (tab) {
                        DesktopNavTab.HOME -> HomeStageContent(controller = controller, backdrop = backdrop)
                        DesktopNavTab.EXPLORE -> ExploreAlbumsGridStage(controller = controller)
                        DesktopNavTab.LIBRARY -> LibraryStageContent(controller = controller, backdrop = backdrop)
                        DesktopNavTab.AUTOMIX_LAB -> AutomixAndDspFullStage(controller = controller, backdrop = backdrop)
                        DesktopNavTab.SETTINGS -> SettingsAndGlassStage(controller = controller, backdrop = backdrop)
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeStageContent(
    controller: DesktopMusicController,
    backdrop: Backdrop,
) {
    val selectedPlaylist by controller.selectedPlaylist.collectAsState()
    val playlistTracks by controller.selectedPlaylistTracks.collectAsState()
    val featuredTracks by controller.featuredTracks.collectAsState()
    val albums by controller.albums.collectAsState()
    val currentTrack by controller.player.currentTrack.collectAsState()

    val shownTracks = playlistTracks.ifEmpty { featuredTracks }
    val heroTitle = selectedPlaylist?.name ?: "Lossless Master Collection"
    val heroSubtitle = selectedPlaylist?.subtitle ?: "${shownTracks.size} Hi-Res FLAC tracks ready for Smart Automix"
    val heroCover = selectedPlaylist?.artworkUrl ?: shownTracks.firstOrNull()?.artworkUrl

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        // Editorial Hero Splash Card (Apple Music iPadOS / macOS style)
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                                Color.White.copy(alpha = 0.04f),
                            )
                        )
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(22.dp))
                    .padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DesktopArtworkImage(
                    artworkUrl = heroCover,
                    titleFallback = heroTitle,
                    modifier = Modifier
                        .size(148.dp)
                        .softShadow(RoundedCornerShape(18.dp), elevation = 20.dp)
                        .clip(RoundedCornerShape(18.dp)),
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "BIT-PERFECT LOSSLESS • DUAL-DECK AUTOMIX",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        text = heroTitle,
                        style = MaterialTheme.typography.displayLarge.copy(fontSize = 28.sp),
                        color = Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = heroSubtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = InkDim,
                        maxLines = 2,
                    )

                    Spacer(Modifier.height(4.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LiquidButton(
                            onClick = {
                                if (shownTracks.isNotEmpty()) {
                                    controller.player.playTrackList(shownTracks, 0)
                                }
                            },
                            backdrop = backdrop,
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                        ) {
                            Icon(PhosphorIcons.Fill.Play, contentDescription = null, tint = Ink, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Play Collection", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = Ink)
                        }

                        LiquidButton(
                            onClick = {
                                if (shownTracks.isNotEmpty()) {
                                    controller.player.playTrackList(shownTracks.shuffled(), 0)
                                }
                            },
                            backdrop = backdrop,
                        ) {
                            Icon(PhosphorIcons.Fill.Shuffle, contentDescription = null, tint = Ink, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Smart Automix Shuffle", style = MaterialTheme.typography.bodyMedium, color = Ink)
                        }
                    }
                }
            }
        }

        // Horizontal Album Shelf
        if (albums.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "RECENT LOSSLESS ALBUMS",
                            style = MaterialTheme.typography.labelLarge,
                            color = InkDim,
                        )
                        Text(
                            text = "See All (${albums.size})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { controller.setNavTab(DesktopNavTab.EXPLORE) },
                        )
                    }

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(albums.take(14), key = { it.uri }) { album ->
                            Column(
                                modifier = Modifier
                                    .width(136.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { controller.selectPlaylistOrAlbum(album) }
                                    .padding(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                DesktopArtworkImage(
                                    artworkUrl = album.artworkUrl,
                                    titleFallback = album.name,
                                    modifier = Modifier
                                        .size(124.dp)
                                        .clip(RoundedCornerShape(12.dp)),
                                )
                                Text(
                                    text = album.name,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = Ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = album.subtitle ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = InkDim,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }

        // Audiophile Multi-Column Track Table Header
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("#", style = MaterialTheme.typography.labelLarge, color = InkDim, modifier = Modifier.width(36.dp))
                Text("TITLE & ARTIST", style = MaterialTheme.typography.labelLarge, color = InkDim, modifier = Modifier.weight(0.42f))
                Text("ALBUM", style = MaterialTheme.typography.labelLarge, color = InkDim, modifier = Modifier.weight(0.28f))
                Text("AUDIO STREAM QUALITY", style = MaterialTheme.typography.labelLarge, color = InkDim, modifier = Modifier.weight(0.22f))
                Text("TIME", style = MaterialTheme.typography.labelLarge, color = InkDim, modifier = Modifier.width(56.dp), textAlign = TextAlign.End)
            }
        }

        itemsIndexed(shownTracks, key = { idx, t -> "${t.uri}_$idx" }) { index, track ->
            AudiophileTrackRow(
                index = index + 1,
                track = track,
                isCurrent = currentTrack?.uri == track.uri,
                onClick = { controller.player.playTrackList(shownTracks, index) },
            )
        }
    }
}

@Composable
private fun AudiophileTrackRow(
    index: Int,
    track: CatalogTrack,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isCurrent) accent.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.02f))
            .border(
                width = 1.dp,
                color = if (isCurrent) accent.copy(alpha = 0.55f) else Color.Transparent,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (isCurrent) "▶" else "$index",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = if (isCurrent) accent else InkDim,
            modifier = Modifier.width(36.dp),
        )

        Row(
            modifier = Modifier.weight(0.42f).padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DesktopArtworkImage(
                artworkUrl = track.artworkUrl,
                titleFallback = track.name,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)),
            )
            Column {
                Text(
                    text = track.name,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                    ),
                    color = if (isCurrent) accent else Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = InkDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Text(
            text = track.album.ifBlank { "—" },
            style = MaterialTheme.typography.bodySmall,
            color = InkDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.28f).padding(end = 12.dp),
        )

        Box(modifier = Modifier.weight(0.22f)) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (track.isHiRes) accent.copy(alpha = 0.22f)
                        else Color.White.copy(alpha = 0.08f)
                    )
                    .border(
                        width = 1.dp,
                        color = if (track.isHiRes) accent.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text = track.qualityBadge,
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 10.sp),
                    color = if (track.isHiRes) accent else Ink,
                    maxLines = 1,
                )
            }
        }

        Text(
            text = formatClockMs(track.durationMs),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = InkDim,
            modifier = Modifier.width(56.dp),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun ExploreAlbumsGridStage(controller: DesktopMusicController) {
    val albums by controller.albums.collectAsState()
    val playlists by controller.playlists.collectAsState()
    val allItems = (playlists + albums).distinctBy { it.uri }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "ALBUMS & PLAYLISTS BENTO GALLERY (${allItems.size})",
            style = MaterialTheme.typography.headlineLarge,
            color = Ink,
        )

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 168.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(allItems, key = { it.uri }) { item ->
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.White.copy(alpha = 0.04f))
                        .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(18.dp))
                        .clickable {
                            controller.selectPlaylistOrAlbum(item)
                            controller.setNavTab(DesktopNavTab.HOME)
                        }
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DesktopArtworkImage(
                        artworkUrl = item.artworkUrl,
                        titleFallback = item.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = item.subtitle ?: "${item.songCount} tracks",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryStageContent(
    controller: DesktopMusicController,
    backdrop: Backdrop,
) {
    HomeStageContent(controller = controller, backdrop = backdrop)
}

@Composable
private fun AutomixAndDspFullStage(
    controller: DesktopMusicController,
    backdrop: Backdrop,
) {
    val automix by controller.player.automixState.collectAsState()
    val dspState by controller.player.dspState.collectAsState()
    val spectrum by controller.player.liveSpectrum.collectAsState()
    val queue by controller.player.queue.collectAsState()
    val currentIndex by controller.player.currentIndex.collectAsState()

    Row(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Box(modifier = Modifier.weight(0.52f).fillMaxHeight()) {
            AutomixQueueAndCurvePanel(
                automix = automix,
                queue = queue,
                currentIndex = currentIndex,
                backdrop = backdrop,
                onToggleAutomix = { controller.player.setAutomixEnabled(it) },
                onToggleBassSwap = { controller.player.setDjBassSwapEnabled(it) },
                onCrossfadeChange = { controller.player.setCrossfadeDurationSec(it) },
                onSelectQueueIndex = { idx -> controller.player.playTrackList(queue, idx) },
            )
        }
        Box(modifier = Modifier.weight(0.48f).fillMaxHeight()) {
            AudioDspPanel(
                dspState = dspState,
                spectrum = spectrum,
                backdrop = backdrop,
                onSelectPreset = { controller.player.applyDspPreset(it) },
                onUpdateDsp = { state, restart -> controller.player.updateDspState(state, restart) },
            )
        }
    }
}

@Composable
private fun SettingsAndGlassStage(
    controller: DesktopMusicController,
    backdrop: Backdrop,
) {
    val serverUrl by controller.subsonicPrefs.serverUrl.collectAsState()
    val username by controller.subsonicPrefs.username.collectAsState()
    val password by controller.subsonicPrefs.password.collectAsState()
    val localFolder by controller.subsonicPrefs.localMusicFolder.collectAsState()
    val glassConfig by controller.glassConfig.collectAsState()
    val connectionMsg by controller.connectionMessage.collectAsState()

    var urlDraft by remember(serverUrl) { mutableStateOf(serverUrl) }
    var userDraft by remember(username) { mutableStateOf(username) }
    var passDraft by remember(password) { mutableStateOf(password) }
    var folderDraft by remember(localFolder) { mutableStateOf(localFolder) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Text(
            text = "SOURCES & LIQUID GLASS SHADER STUDIO",
            style = MaterialTheme.typography.headlineLarge,
            color = Ink,
        )

        // Subsonic NAS Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White.copy(alpha = 0.05f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(18.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "NAS SUBSONIC / NAVIDROME SERVER",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Status: $connectionMsg",
                style = MaterialTheme.typography.bodySmall,
                color = InkDim,
            )

            SettingsInputField("Server URL", urlDraft) { urlDraft = it }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) {
                    SettingsInputField("Username", userDraft) { userDraft = it }
                }
                Box(Modifier.weight(1f)) {
                    SettingsInputField("Password", passDraft) { passDraft = it }
                }
            }

            LiquidButton(
                onClick = {
                    controller.subsonicPrefs.setServerUrl(urlDraft)
                    controller.subsonicPrefs.setUsername(userDraft)
                    controller.subsonicPrefs.setPassword(passDraft)
                    controller.refreshLibrary()
                },
                backdrop = backdrop,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
            ) {
                Icon(PhosphorIcons.Bold.HardDrives, contentDescription = null, tint = Ink, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Save & Reconnect NAS", style = MaterialTheme.typography.bodyMedium, color = Ink)
            }
        }

        // Local Lossless Folder Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White.copy(alpha = 0.05f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(18.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "LOCAL LOSSLESS DIRECTORY (FLAC / WAV / ALAC / M3U8)",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            SettingsInputField("Music Folder Path", folderDraft) { folderDraft = it }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LiquidButton(
                    onClick = {
                        val chooser = JFileChooser(folderDraft).apply {
                            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                            dialogTitle = "Select Lossless Music Directory"
                        }
                        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                            folderDraft = chooser.selectedFile.absolutePath
                            controller.subsonicPrefs.setLocalMusicFolder(folderDraft)
                            controller.refreshLibrary()
                        }
                    },
                    backdrop = backdrop,
                ) {
                    Icon(PhosphorIcons.Bold.Folder, contentDescription = null, tint = Ink, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Browse Folder…", style = MaterialTheme.typography.bodyMedium, color = Ink)
                }

                LiquidButton(
                    onClick = {
                        controller.subsonicPrefs.setLocalMusicFolder(folderDraft)
                        controller.refreshLibrary()
                    },
                    backdrop = backdrop,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
                ) {
                    Text("Scan Folder Now", style = MaterialTheme.typography.bodyMedium, color = Ink)
                }
            }
        }

        // Liquid Glass SkSL Shader Tuning Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White.copy(alpha = 0.05f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(18.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "SKIA / SKIKO LIQUID GLASS SHADER ENGINE",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Chromatic Dispersion (Rainbow Rim Refraction)", style = MaterialTheme.typography.bodyMedium, color = Ink)
                LiquidToggle(
                    selected = { glassConfig.chromaticAberration },
                    onSelect = { enabled -> controller.updateGlassConfig { it.copy(chromaticAberration = enabled) } },
                    backdrop = backdrop,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("3DRadial Lens Depth Curvature", style = MaterialTheme.typography.bodyMedium, color = Ink)
                LiquidToggle(
                    selected = { glassConfig.depthEffect },
                    onSelect = { enabled -> controller.updateGlassConfig { it.copy(depthEffect = enabled) } },
                    backdrop = backdrop,
                )
            }

            GlassSliderSetting(
                label = "Lens Refraction Strength",
                valueText = "${(glassConfig.lensAmount * 100).toInt()}%",
                value = glassConfig.lensAmount,
                range = 0f..1f,
                backdrop = backdrop,
            ) { v -> controller.updateGlassConfig { it.copy(lensAmount = v) } }

            GlassSliderSetting(
                label = "Lens Rim Depth",
                valueText = "${(glassConfig.lensHeight * 100).toInt()}%",
                value = glassConfig.lensHeight,
                range = 0.1f..1f,
                backdrop = backdrop,
            ) { v -> controller.updateGlassConfig { it.copy(lensHeight = v) } }

            GlassSliderSetting(
                label = "Frosted Blur Radius",
                valueText = "${glassConfig.blurRadius.toInt()} dp",
                value = glassConfig.blurRadius,
                range = 0f..32f,
                backdrop = backdrop,
            ) { v -> controller.updateGlassConfig { it.copy(blurRadius = v) } }

            GlassSliderSetting(
                label = "Glass Film Opacity",
                valueText = "${(glassConfig.surfaceOpacity * 100).toInt()}%",
                value = glassConfig.surfaceOpacity,
                range = 0.10f..0.85f,
                backdrop = backdrop,
            ) { v -> controller.updateGlassConfig { it.copy(surfaceOpacity = v) } }
        }
    }
}

@Composable
private fun SettingsInputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = InkDim)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Ink, fontFamily = FontFamily.Monospace),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.32f))
                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun GlassSliderSetting(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    backdrop: Backdrop,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Ink)
            Text(valueText, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.primary)
        }
        LiquidSlider(
            value = { value },
            onValueChange = onValueChange,
            valueRange = range,
            visibilityThreshold = 0.01f,
            backdrop = backdrop,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SearchResultsStage(
    query: String,
    results: List<CatalogTrack>,
    controller: DesktopMusicController,
) {
    val currentTrack by controller.player.currentTrack.collectAsState()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                text = "SEARCH RESULTS FOR \"$query\" (${results.size})",
                style = MaterialTheme.typography.titleLarge,
                color = Ink,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        itemsIndexed(results, key = { idx, t -> "${t.uri}_$idx" }) { idx, track ->
            AudiophileTrackRow(
                index = idx + 1,
                track = track,
                isCurrent = currentTrack?.uri == track.uri,
                onClick = { controller.player.playTrackList(results, idx) },
            )
        }
    }
}

@Composable
private fun RightNowPlayingSidecar(
    controller: DesktopMusicController,
    backdrop: Backdrop,
    glassConfig: GlassEffectConfig,
    onPaletteLoaded: (Color, Color) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentTrack by controller.player.currentTrack.collectAsState()
    val positionMs by controller.player.positionMs.collectAsState()
    val lyrics by controller.currentLyrics.collectAsState()
    val spectrum by controller.player.liveSpectrum.collectAsState()
    val automix by controller.player.automixState.collectAsState()

    val positionState = rememberUpdatedState(positionMs)
    val sidecarShape = ContinuousRoundedRectangle(24.dp)

    Column(
        modifier = modifier
            .liquidGlass(glassConfig, sidecarShape, ownBackdrop = backdrop)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "NOW PLAYING STAGE",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Expand ↗",
                style = MaterialTheme.typography.bodySmall,
                color = InkDim,
                modifier = Modifier.clickable { controller.toggleImmersionMode() },
            )
        }

        // Cover Art + Live Spectrum Overlay
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.18f)
                .clip(RoundedCornerShape(18.dp)),
        ) {
            DesktopArtworkImage(
                artworkUrl = currentTrack?.artworkUrl,
                titleFallback = currentTrack?.name ?: "Square-Yuma",
                modifier = Modifier.fillMaxSize(),
                onPaletteLoaded = onPaletteLoaded,
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(54.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))
                        )
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                LiveSpectrumBars(
                    bands = spectrum,
                    accent = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Column {
            Text(
                text = currentTrack?.name ?: "Ready to Play",
                style = MaterialTheme.typography.titleLarge,
                color = Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = currentTrack?.artist ?: "Select any track from NAS or Local",
                style = MaterialTheme.typography.bodyMedium,
                color = InkDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (automix.isMixing) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f))
                    .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                    .padding(10.dp),
            ) {
                Text(
                    text = "MIXING → ${automix.incomingTrack?.name ?: "Next Track"} (${(automix.progress * 100).toInt()}%)",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Live Karaoke Lyrics Preview inside Sidecar
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.22f)),
        ) {
            val currentLyrics = lyrics
            if (currentLyrics != null && currentLyrics.lines.isNotEmpty()) {
                LyricsView(
                    lyrics = currentLyrics,
                    positionMs = positionState,
                    modifier = Modifier.fillMaxSize(),
                    showTranslation = true,
                    onSeek = { controller.player.seekTo(it) },
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (currentTrack == null) "Word-by-word Karaoke lyrics appear here" else "Instrumental / Searching lyrics…",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkDim,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FloatingBottomTransportDock(
    controller: DesktopMusicController,
    backdrop: Backdrop,
    glassConfig: GlassEffectConfig,
    onPaletteLoaded: (Color, Color) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentTrack by controller.player.currentTrack.collectAsState()
    val isPlaying by controller.player.isPlaying.collectAsState()
    val positionMs by controller.player.positionMs.collectAsState()
    val durationMs by controller.player.durationMs.collectAsState()
    val volume by controller.player.masterVolume.collectAsState()
    val automix by controller.player.automixState.collectAsState()

    val dockShape = ContinuousRoundedRectangle(26.dp)
    val safeDur = durationMs.coerceAtLeast(1L).toFloat()
    val curFrac = (positionMs.toFloat() / safeDur).coerceIn(0f, 1f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(88.dp)
            .softShadow(dockShape, elevation = 24.dp, ambient = 0.22f, spot = 0.30f)
            .liquidGlass(
                config = glassConfig,
                shape = dockShape,
                ownBackdrop = backdrop,
            )
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // Left: Artwork + Title + Quality Badge
        Row(
            modifier = Modifier
                .width(290.dp)
                .clickable { controller.toggleImmersionMode() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DesktopArtworkImage(
                artworkUrl = currentTrack?.artworkUrl,
                titleFallback = currentTrack?.name ?: "Square-Yuma",
                modifier = Modifier
                    .size(58.dp)
                    .clip(RoundedCornerShape(14.dp)),
                onPaletteLoaded = onPaletteLoaded,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = currentTrack?.name ?: "Not Playing",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = currentTrack?.artist ?: "Square × Yuma Desktop",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = currentTrack?.qualityBadge ?: "FLAC LOSSLESS",
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 9.5.sp),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }

        // Center: Transport Buttons + LiquidSlider Scrubber
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                LiquidButton(
                    onClick = { controller.player.skipPrevious() },
                    backdrop = backdrop,
                    modifier = Modifier.size(38.dp),
                ) {
                    Icon(PhosphorIcons.Fill.SkipBack, contentDescription = "Previous", tint = Ink, modifier = Modifier.size(16.dp))
                }

                LiquidButton(
                    onClick = { controller.player.togglePlayPause() },
                    backdrop = backdrop,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        imageVector = if (isPlaying) PhosphorIcons.Fill.Pause else PhosphorIcons.Fill.Play,
                        contentDescription = "Play/Pause",
                        tint = Ink,
                        modifier = Modifier.size(20.dp),
                    )
                }

                LiquidButton(
                    onClick = { controller.player.skipNext() },
                    backdrop = backdrop,
                    modifier = Modifier.size(38.dp),
                ) {
                    Icon(PhosphorIcons.Fill.SkipForward, contentDescription = "Next", tint = Ink, modifier = Modifier.size(16.dp))
                }

                AutomixStatusPill(
                    automix = automix,
                    backdrop = backdrop,
                    glassConfig = glassConfig,
                    onClick = { controller.setNavTab(DesktopNavTab.AUTOMIX_LAB) },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = formatClockMs(positionMs),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    color = InkDim,
                    modifier = Modifier.width(38.dp),
                )
                LiquidSlider(
                    value = { curFrac },
                    onValueChange = { frac ->
                        controller.player.seekTo((frac * safeDur).toLong())
                    },
                    valueRange = 0f..1f,
                    visibilityThreshold = 0.001f,
                    backdrop = backdrop,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatClockMs(durationMs),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    color = InkDim,
                    modifier = Modifier.width(38.dp),
                    textAlign = TextAlign.End,
                )
            }
        }

        // Right: Volume Slider + Stage Buttons
        Row(
            modifier = Modifier.width(260.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = PhosphorIcons.Fill.SpeakerHigh,
                contentDescription = "Volume",
                tint = InkDim,
                modifier = Modifier.size(16.dp),
            )
            LiquidSlider(
                value = { volume },
                onValueChange = { controller.player.setMasterVolume(it) },
                valueRange = 0f..1f,
                visibilityThreshold = 0.005f,
                backdrop = backdrop,
                modifier = Modifier.weight(1f),
            )
            LiquidButton(
                onClick = {
                    controller.setImmersionTab(ImmersionRightTab.LYRICS)
                    controller.toggleImmersionMode()
                },
                backdrop = backdrop,
                modifier = Modifier.size(38.dp),
            ) {
                Icon(PhosphorIcons.Fill.MicrophoneStage, contentDescription = "Karaoke Stage", tint = Ink, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun MiniCubePlayerView(
    controller: DesktopMusicController,
    backdrop: Backdrop,
    glassConfig: GlassEffectConfig,
    onPaletteLoaded: (Color, Color) -> Unit,
) {
    val currentTrack by controller.player.currentTrack.collectAsState()
    val isPlaying by controller.player.isPlaying.collectAsState()
    val positionMs by controller.player.positionMs.collectAsState()
    val durationMs by controller.player.durationMs.collectAsState()
    val lyrics by controller.currentLyrics.collectAsState()

    val currentLine = remember(lyrics, positionMs) {
        lyrics?.lines?.lastOrNull { (it.startTimeMs ?: 0L) <= positionMs }?.text
    }

    Box(modifier = Modifier.fillMaxSize()) {
        DesktopArtworkImage(
            artworkUrl = currentTrack?.artworkUrl,
            titleFallback = currentTrack?.name ?: "Square-Yuma",
            modifier = Modifier.fillMaxSize(),
            onPaletteLoaded = onPaletteLoaded,
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.35f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.82f),
                        )
                    )
                )
                .padding(18.dp),
        ) {
            // Top bar: Exit Mini-Cube
            Row(
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = currentTrack?.qualityBadge ?: "FLAC",
                    style = MaterialTheme.typography.labelLarge,
                    color = Ink,
                )
                LiquidButton(
                    onClick = { controller.toggleMiniCubeMode() },
                    backdrop = backdrop,
                ) {
                    Text("Expand Studio", style = MaterialTheme.typography.bodySmall, color = Ink)
                }
            }

            // Bottom Floating Glass Card inside Mini-Cube
            val cardShape = ContinuousRoundedRectangle(20.dp)
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .liquidGlass(glassConfig, cardShape, ownBackdrop = backdrop)
                    .padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!currentLine.isNullOrBlank()) {
                    Text(
                        text = currentLine,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                    )
                }
                Text(
                    text = currentTrack?.name ?: "Square × Yuma",
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = currentTrack?.artist ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkDim,
                    maxLines = 1,
                )
                val safeDur = durationMs.coerceAtLeast(1L).toFloat()
                LiquidSlider(
                    value = { (positionMs.toFloat() / safeDur).coerceIn(0f, 1f) },
                    onValueChange = { controller.player.seekTo((it * safeDur).toLong()) },
                    valueRange = 0f..1f,
                    visibilityThreshold = 0.001f,
                    backdrop = backdrop,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LiquidButton(onClick = { controller.player.skipPrevious() }, backdrop = backdrop, modifier = Modifier.size(38.dp)) {
                        Icon(PhosphorIcons.Fill.SkipBack, contentDescription = null, tint = Ink, modifier = Modifier.size(16.dp))
                    }
                    LiquidButton(
                        onClick = { controller.player.togglePlayPause() },
                        backdrop = backdrop,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                        modifier = Modifier.size(46.dp),
                    ) {
                        Icon(
                            imageVector = if (isPlaying) PhosphorIcons.Fill.Pause else PhosphorIcons.Fill.Play,
                            contentDescription = null,
                            tint = Ink,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    LiquidButton(onClick = { controller.player.skipNext() }, backdrop = backdrop, modifier = Modifier.size(38.dp)) {
                        Icon(PhosphorIcons.Fill.SkipForward, contentDescription = null, tint = Ink, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}
