package dev.lelonio.square.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Bold
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.bold.MagnifyingGlass
import com.adamglin.phosphoricons.fill.Gear
import com.adamglin.phosphoricons.fill.House
import com.adamglin.phosphoricons.fill.MusicNotes
import com.adamglin.phosphoricons.fill.Pause
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.fill.SkipBack
import com.adamglin.phosphoricons.fill.SkipForward
import com.adamglin.phosphoricons.regular.ChartBar
import com.adamglin.phosphoricons.regular.Queue
import com.adamglin.phosphoricons.regular.Quotes
import com.adamglin.phosphoricons.regular.Repeat
import com.adamglin.phosphoricons.regular.RepeatOnce
import com.adamglin.phosphoricons.regular.Shuffle
import com.adamglin.phosphoricons.regular.SpeakerHigh
import com.adamglin.phosphoricons.regular.SpeakerX
import com.adamglin.phosphoricons.regular.VinylRecord
import dev.lelonio.square.ui.glass.GlassEffectConfig
import dev.lelonio.square.ui.glass.InteractiveHighlight
import dev.lelonio.square.ui.glass.LiquidSlider
import dev.lelonio.square.ui.glass.LocalAppBackdrop
import dev.lelonio.square.ui.glass.LocalGlassEffectConfig
import dev.lelonio.square.ui.glass.backdrop.Backdrop
import dev.lelonio.square.ui.glass.backdrop.backdrops.layerBackdrop
import dev.lelonio.square.ui.glass.backdrop.backdrops.rememberLayerBackdrop
import dev.lelonio.square.ui.glass.liquidGlass
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import dev.lelonio.square.ui.theme.LocalLightTheme
import dev.lelonio.square.ui.theme.SquareTheme
import dev.lelonio.square.ui.theme.pageColorFor
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** How the player settles when it is not being dragged — the phone's expandSpec. */
val ExpandSpec = spring<Float>(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)

val SidebarWidth = 236.dp
private val BarHeight = 68.dp
/** Room the pages leave at their foot for the floating bar to sit over. */
val BarClearance = 112.dp

@Composable
fun App(state: AppState) {
    val song by state.player.current.collectAsState()
    val seed by rememberArtworkColor(state.cover(song?.coverArt, 300))
    SquareTheme(seed = seed, darkTheme = state.themeMode == ThemeMode.DARK) {
        val config = state.glassProfile.config()
        val pageBackdrop = rememberLayerBackdrop()
        val scope = rememberCoroutineScope()
        LaunchedEffect(state.nowPlaying) { state.expand.animateTo(if (state.nowPlaying) 1f else 0f, ExpandSpec) }

        CompositionLocalProvider(
            LocalContentColor provides Ink,
            LocalGlassEffectConfig provides config,
            LocalAppBackdrop provides pageBackdrop,
        ) {
            Box(Modifier.fillMaxSize()) {
                // Everything the chrome is glass over is recorded into this layer.
                Box(Modifier.fillMaxSize().layerBackdrop(pageBackdrop)) {
                    AmbientBackground(state.cover(song?.coverArt, 600), pageColorFor(seed), settleFrom = 0.3f)
                    Content(state, Modifier.fillMaxSize().padding(start = SidebarWidth + 14.dp))
                }
                val chrome = chromeConfig(config)
                Sidebar(state, chrome, Modifier.padding(10.dp).width(SidebarWidth).fillMaxHeight())
                TransportBar(
                    state, chrome,
                    Modifier.align(Alignment.BottomCenter)
                        .padding(start = SidebarWidth + 34.dp, end = 24.dp, bottom = 16.dp)
                        .fillMaxWidth().height(BarHeight),
                )
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
                androidx.compose.animation.AnimatedVisibility(
                    state.toast != null,
                    Modifier.align(Alignment.BottomCenter).padding(bottom = BarHeight + 30.dp, start = SidebarWidth),
                    enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut(),
                ) {
                    Text(
                        state.toast.orEmpty(), color = Ink, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.liquidGlass(chrome, RoundedCornerShape(50), highlightAlpha = 0.3f).padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

/**
 * The bar and the mini player's tuning on the phone: a deeper bend at the rim and
 * a lighter film, so what is behind shows through instead of a milky sheet.
 */
@Composable
private fun chromeConfig(config: GlassEffectConfig): GlassEffectConfig {
    val light = LocalLightTheme.current
    val defaults = GlassEffectConfig()
    fun scaled(tuned: Float, set: Float, default: Float) = (tuned * set / default).coerceIn(0f, 1f)
    return config.copy(
        lensHeight = scaled(0.60f, config.lensHeight, defaults.lensHeight),
        lensAmount = scaled(0.69f, config.lensAmount, defaults.lensAmount),
        surfaceTintColor = if (config.surfaceTintColor.isSpecified) config.surfaceTintColor else if (light) Color(0xFFF5F5F7) else Color.White,
        surfaceOpacity = scaled(if (light) 0.40f else 0.15f, config.surfaceOpacity, defaults.surfaceOpacity),
    )
}

private data class NavItem(val screen: Screen, val icon: ImageVector, val label: String)

@Composable
private fun Sidebar(state: AppState, config: GlassEffectConfig, modifier: Modifier) {
    val items = listOf(
        NavItem(Screen.Search, PhosphorIcons.Bold.MagnifyingGlass, "Search"),
        NavItem(Screen.Home, PhosphorIcons.Fill.House, "Home"),
        NavItem(Screen.Library, PhosphorIcons.Regular.VinylRecord, "Library"),
        NavItem(Screen.Playlists, PhosphorIcons.Fill.MusicNotes, "Playlists"),
        NavItem(Screen.Month, PhosphorIcons.Regular.ChartBar, "Your month"),
        NavItem(Screen.Settings, PhosphorIcons.Fill.Gear, "Settings"),
    )
    val selected = items.indexOfFirst { it.screen == state.stack.first() }
    // The puck slides between rows the way the phone's tab indicator slides between tabs.
    val puck by animateFloatAsState(selected.toFloat(), spring(dampingRatio = 0.72f, stiffness = 420f))
    val scope = rememberCoroutineScope()
    val glow = remember(scope) { InteractiveHighlight(animationScope = scope) }
    Column(
        modifier.liquidGlass(config, RoundedCornerShape(28.dp), highlightAlpha = 0.3f, blurRadiusDp = config.blurRadius * 6f)
            .then(glow.modifier).then(glow.gestureModifier)
            .padding(horizontal = 12.dp, vertical = 22.dp),
    ) {
        Text("Square-Yuma", style = MaterialTheme.typography.titleLarge, color = Ink, modifier = Modifier.padding(start = 12.dp, bottom = 22.dp))
        Box {
            if (selected >= 0) {
                Box(
                    Modifier.offset { IntOffset(0, (puck * RowHeight.toPx()).roundToInt()) }
                        .fillMaxWidth().height(RowHeight).clip(RoundedCornerShape(14.dp))
                        .background(Ink.copy(alpha = 0.13f)),
                )
            }
            Column {
                items.forEachIndexed { i, item -> NavRow(item, i == selected) { state.root(item.screen) } }
            }
        }
        // Pinned playlists, as Apple Music lists playlists under the sidebar's sections.
        if (state.pins.isNotEmpty()) {
            SectionLabel("Pinned", Modifier.padding(start = 12.dp, top = 22.dp, bottom = 8.dp))
            Column(Modifier.verticalScroll(rememberScrollState())) {
                state.pins.forEach { pin ->
                    val open = state.stack.lastOrNull().let { it is Screen.Playlist && it.id == pin.id }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (open) Ink.copy(alpha = 0.1f) else Color.Transparent)
                            .clickable { state.root(Screen.Playlists); state.open(Screen.Playlist(pin.id, pin.name)) }
                            .pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(state.cover(pin.cover, 100), Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)))
                        Spacer(Modifier.width(12.dp))
                        Text(pin.name, style = MaterialTheme.typography.bodyMedium, color = Ink, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

private val RowHeight = 42.dp

@Composable
private fun NavRow(item: NavItem, selected: Boolean, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val pressed by hover.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(stiffness = Spring.StiffnessMediumLow))
    val tint by animateColorAsState(if (selected) Ink else if (hovered) Ink else InkDim)
    Row(
        Modifier.fillMaxWidth().height(RowHeight).graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(14.dp))
            .background(if (hovered && !selected) Ink.copy(alpha = 0.06f) else Color.Transparent)
            .hoverable(hover).clickable(hover, indication = null, onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(item.icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(item.label, style = MaterialTheme.typography.titleMedium, color = Ink)
    }
}

@Composable
private fun Content(state: AppState, modifier: Modifier) {
    AnimatedContent(
        targetState = state.stack.last(),
        modifier = modifier,
        // The phone's navigation: tabs crossfade; a page pushed slides a fifth of
        // the way in, and slides back out on the way back.
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
            Screen.Playlists -> PlaylistsScreen(state)
            Screen.Search -> SearchScreen(state)
            Screen.Settings -> SettingsScreen(state)
            is Screen.Album -> AlbumScreen(state, screen)
            is Screen.Artist -> ArtistScreen(state, screen)
            is Screen.Playlist -> PlaylistScreen(state, screen)
            is Screen.Mix -> MixScreen(state, screen)
            Screen.Month -> MonthScreen(state)
            Screen.Downloads -> DownloadsScreen(state)
        }
    }
}

/**
 * The phone's floating mini player, widened into a Mac transport: the same
 * capsule of glass, the same 1.04 press swell and finger-following highlight,
 * the same swipe for next/previous and the same hairline of progress at its foot.
 */
@Composable
private fun TransportBar(state: AppState, config: GlassEffectConfig, modifier: Modifier) {
    val p = state.player
    val song by p.current.collectAsState()
    val playing by p.isPlaying.collectAsState()
    val shuffle by p.shuffle.collectAsState()
    val repeat by p.repeat.collectAsState()
    val scope = rememberCoroutineScope()
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 1.04f else 1f, spring(stiffness = Spring.StiffnessMediumLow))
    val glow = remember(scope) { InteractiveHighlight(animationScope = scope) }
    val offsetX = remember { Animatable(0f) }
    val settle = remember { spring<Float>(stiffness = Spring.StiffnessMediumLow) }
    val gone = state.expand.value

    Box(
        modifier.graphicsLayer { scaleX = pressScale; scaleY = pressScale; alpha = 1f - gone * 0.6f }
            .liquidGlass(config, RoundedCornerShape(percent = 50), highlightAlpha = 0.3f)
            .then(glow.modifier).clipToBounds().then(glow.gestureModifier)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragCancel = { scope.launch { offsetX.animateTo(0f, settle) } },
                    onHorizontalDrag = { _, d -> scope.launch { offsetX.snapTo(offsetX.value + d) } },
                    onDragEnd = {
                        if (abs(offsetX.value) > size.width * 0.12f) { if (offsetX.value > 0) p.previous() else p.next() }
                        scope.launch { offsetX.animateTo(0f, settle) }
                    },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier.fillMaxSize().offset { IntOffset(offsetX.value.roundToInt(), 0) }.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).clickable(press, indication = null, enabled = song != null) { state.nowPlaying = true }
                    .pointerHoverIcon(PointerIcon.Hand),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(48.dp).onGloballyPositioned { state.miniArt = it.boundsInRoot() }
                        .graphicsLayer { alpha = if (gone > 0f) 0f else 1f },
                ) {
                    Artwork(state.cover(song?.coverArt, 120), Modifier.fillMaxSize().clip(RoundedCornerShape(9.dp)))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(song?.title ?: "Not playing", style = MaterialTheme.typography.bodyMedium, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song?.artist.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Ink.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Glyph(PhosphorIcons.Regular.Shuffle, "Shuffle", active = shuffle) { p.toggleShuffle() }
                Glyph(PhosphorIcons.Fill.SkipBack, "Previous", size = 22.dp) { p.previous() }
                PlayGlyph(playing, 30.dp) { p.toggle() }
                Glyph(PhosphorIcons.Fill.SkipForward, "Next", size = 22.dp) { p.next() }
                Glyph(if (repeat == RepeatMode.ONE) PhosphorIcons.Regular.RepeatOnce else PhosphorIcons.Regular.Repeat, "Repeat", active = repeat != RepeatMode.OFF) { p.cycleRepeat() }
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Glyph(PhosphorIcons.Regular.Quotes, "Lyrics (L)", active = state.nowPlaying && !state.showQueue) { state.showQueue = false; state.nowPlaying = true }
                Glyph(PhosphorIcons.Regular.Queue, "Queue", active = state.nowPlaying && state.showQueue) { state.showQueue = true; state.nowPlaying = true }
                Spacer(Modifier.width(6.dp))
                Volume(state, Modifier.width(140.dp))
            }
        }
        BarProgress(state, Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 26.dp))
    }
}

/** The 2 dp line at the pill's foot, as on the phone — and on a desktop, something to click. */
@Composable
private fun BarProgress(state: AppState, modifier: Modifier) {
    val pos by state.player.positionMs.collectAsState()
    val song by state.player.current.collectAsState()
    val dur = (song?.duration ?: 0L) * 1000
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val thick by animateDpAsState(if (hovered) 4.dp else 2.dp)
    Box(
        modifier.height(10.dp).hoverable(hover).pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(dur) { detectTapGestures { if (dur > 0) state.player.seek((it.x / size.width * dur).toLong()) } },
        contentAlignment = Alignment.BottomStart,
    ) {
        Box(Modifier.fillMaxWidth().height(thick).clip(CircleShape).background(Ink.copy(alpha = 0.18f)))
        Box(
            Modifier.fillMaxWidth(if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f).height(thick)
                .clip(CircleShape).background(Ink),
        )
    }
}

/** Volume on the phone's LiquidSlider, its thumb a lens that swells when held. */
@Composable
fun Volume(state: AppState, modifier: Modifier, backdrop: Backdrop = LocalAppBackdrop.current) {
    val v by state.player.volume.collectAsState()
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(if (v == 0f) PhosphorIcons.Regular.SpeakerX else PhosphorIcons.Regular.SpeakerHigh, "Volume", tint = InkDim, modifier = Modifier.size(18.dp))
        LiquidSlider(
            value = { state.player.volume.value }, onValueChange = state::setVolume, valueRange = 0f..1f,
            visibilityThreshold = 0.001f, backdrop = backdrop, modifier = Modifier.weight(1f).padding(start = 6.dp),
            accentColor = Ink, trackColor = Ink.copy(alpha = 0.28f),
            thumbSize = DpSize(24.dp, 24.dp), thumbRestScale = 0.6f, thumbPressedScale = 1f,
        )
    }
}

@Composable
fun Glyph(icon: ImageVector, label: String, active: Boolean = false, size: Dp = 18.dp, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val pressed by hover.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.86f else if (hovered) 1.08f else 1f, spring(dampingRatio = 0.5f, stiffness = 600f))
    val tint by animateColorAsState(if (active) Ink else Ink.copy(alpha = 0.78f))
    Box(
        Modifier.size(size + 18.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape)
            .background(if (active) Ink.copy(alpha = 0.16f) else if (hovered) Ink.copy(alpha = 0.08f) else Color.Transparent)
            .hoverable(hover).clickable(hover, indication = null, onClick = onClick).pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = tint, modifier = Modifier.size(size)) }
}

@Composable
fun PlayGlyph(playing: Boolean, size: Dp, onClick: () -> Unit) =
    Glyph(if (playing) PhosphorIcons.Fill.Pause else PhosphorIcons.Fill.Play, if (playing) "Pause" else "Play", size = size, onClick = onClick)

/**
 * The cover in flight between the bar and the player: drawn above both while the
 * player opens or closes, at a rectangle eased from the bar's thumbnail to the
 * player's cover, its corner growing from the thumbnail's 9 dp to 18 dp.
 */
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
            Modifier.offset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }.size(r.width.toDp(), r.height.toDp())
                .clip(RoundedCornerShape(androidx.compose.ui.unit.lerp(9.dp, 18.dp, e.coerceIn(0f, 1f)))),
        ) { Artwork(state.cover(song?.coverArt, 900), Modifier.fillMaxSize()) }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelLarge, color = InkDim, modifier = modifier)
}

@Composable
fun Title(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.displayLarge, color = Ink, modifier = modifier)
}
