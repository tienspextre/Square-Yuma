package dev.lelonio.square.desktop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Pause
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.regular.CaretLeft
import com.adamglin.phosphoricons.regular.ListBullets
import com.adamglin.phosphoricons.regular.SquaresFour
import dev.lelonio.square.backend.subsonic.SubsonicAlbumEntry
import dev.lelonio.square.backend.subsonic.SubsonicArtistItem
import dev.lelonio.square.desktop.AppState
import dev.lelonio.square.desktop.Screen
import dev.lelonio.square.desktop.str
import dev.lelonio.square.desktop.tr
import dev.lelonio.square.ui.glass.LiquidButton
import dev.lelonio.square.ui.glass.LocalAppBackdrop
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import dev.lelonio.square.ui.theme.LocalLightTheme
import dev.lelonio.square.ui.theme.softShadow
import kotlinx.coroutines.launch

val MaxContent = 1480.dp
val Gutter = 28.dp
val BarClearance = 120.dp

@Composable
fun <T> load(state: AppState, vararg keys: Any?, block: suspend () -> Result<T>): Result<T>? =
    produceState<Result<T>?>(null, state.generation, *keys) { value = block() }.value

@Composable
fun <T> Loaded(result: Result<T>?, content: @Composable (T) -> Unit) {
    when {
        result == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        result.isFailure -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("${str("desktop_server_unreachable")}: ${result.exceptionOrNull()?.message}", color = InkDim)
        }
        else -> content(result.getOrThrow())
    }
}

@Composable
fun Grid(header: @Composable () -> Unit, items: LazyGridScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyVerticalGrid(
            GridCells.Adaptive(176.dp),
            Modifier.widthIn(max = MaxContent).fillMaxSize(),
            contentPadding = PaddingValues(Gutter, 20.dp, Gutter, BarClearance),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { header() }
            items()
        }
    }
}

@Composable
fun Card(
    title: String,
    subtitle: String?,
    cover: String?,
    round: Boolean = false,
    modifier: Modifier = Modifier,
    onPlay: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val scale by animateFloatAsState(if (hovered) 1.03f else 1f, spring(stiffness = 500f))
    val shape = if (round) CircleShape else RoundedCornerShape(12.dp)
    Column(modifier.hoverable(hover).clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).scale(scale).softShadow(shape, if (hovered) 22.dp else 14.dp)) {
            Artwork(cover, Modifier.fillMaxSize().clip(shape))
            if (onPlay != null) {
                androidx.compose.animation.AnimatedVisibility(
                    hovered,
                    Modifier.align(Alignment.BottomEnd).padding(10.dp),
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut(),
                ) {
                    LiquidButton(onPlay, LocalAppBackdrop.current, Modifier.size(42.dp), contentHeight = 42.dp, contentPadding = 0.dp, flat = true) {
                        Icon(PhosphorIcons.Fill.Play, str("play"), tint = Ink, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = InkDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun AlbumCard(state: AppState, a: SubsonicAlbumEntry) {
    val scope = rememberCoroutineScope()
    AlbumContextWrapper(state, a) { menuMod ->
        Card(
            a.name,
            listOfNotNull(a.artist, a.year?.toString()).joinToString(" · "),
            state.cover(a.coverArt),
            modifier = menuMod,
            onPlay = {
                if (state.offlineMode) {
                    val cached = state.downloads.cachedSongs().filter { (it.albumId ?: it.album.orEmpty()) == a.id }
                    if (cached.isNotEmpty()) state.player.playQueue(cached)
                } else {
                    scope.launch { state.client.getAlbum(a.id).onSuccess { state.player.playQueue(it) } }
                }
            },
        ) { state.open(Screen.Album(a.id, a.name)) }
    }
}

@Composable
fun ArtistCard(state: AppState, a: SubsonicArtistItem) =
    ArtistContextWrapper(state, a) { menuMod ->
        Card(a.name, str("album_count", a.albumCount), state.cover(a.coverArt), round = true, modifier = menuMod) {
            state.open(Screen.Artist(a.id, a.name))
        }
    }

@Composable
fun Shelf(titleKey: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(bottom = 28.dp)) {
        Text(tr(titleKey), style = MaterialTheme.typography.headlineLarge, color = Ink, modifier = Modifier.padding(horizontal = Gutter, vertical = 10.dp))
        content()
    }
}

@Composable
fun Glyph(icon: ImageVector, labelKey: String, active: Boolean = false, size: Dp = 18.dp, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val pressed by hover.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.86f else if (hovered) 1.08f else 1f, spring(dampingRatio = 0.5f, stiffness = 600f))
    val tint by animateColorAsState(if (active) Ink else Ink.copy(alpha = 0.78f))
    Box(
        Modifier.size(size + 16.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape)
            .background(if (active) Ink.copy(alpha = 0.16f) else if (hovered) Ink.copy(alpha = 0.08f) else Color.Transparent)
            .hoverable(hover).clickable(hover, indication = null, onClick = onClick).pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, tr(labelKey), tint = tint, modifier = Modifier.size(size)) }
}

@Composable
fun PlayGlyph(playing: Boolean, size: Dp, onClick: () -> Unit) =
    Glyph(if (playing) PhosphorIcons.Fill.Pause else PhosphorIcons.Fill.Play, if (playing) "pause" else "play", size = size, onClick = onClick)

@Composable
fun SectionLabel(textKey: String, modifier: Modifier = Modifier) {
    Text(tr(textKey).uppercase(), style = MaterialTheme.typography.labelLarge, color = InkDim, modifier = modifier)
}

@Composable
fun Title(textKey: String, modifier: Modifier = Modifier) {
    Text(tr(textKey), style = MaterialTheme.typography.displayLarge, color = Ink, modifier = modifier)
}

@Composable
fun LayoutToggle(state: AppState) =
    Glyph(
        if (state.listMode) PhosphorIcons.Regular.SquaresFour else PhosphorIcons.Regular.ListBullets,
        if (state.listMode) "desktop_show_as_grid" else "desktop_show_as_list",
        size = 20.dp,
    ) { state.toggleListMode() }

@Composable
fun ListEntry(
    cover: String?,
    title: String,
    subtitle: String,
    round: Boolean = false,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
    onClick: () -> Unit,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (hovered) Ink.copy(alpha = 0.07f) else Color.Transparent)
            .hoverable(hover).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(cover, Modifier.size(48.dp).clip(if (round) CircleShape else RoundedCornerShape(6.dp)))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = InkDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

@Composable
fun Toggle(labelKey: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
        Text(tr(labelKey), color = Ink, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Segmented(listOf("off", "desktop_on"), if (on) 1 else 0) { onChange(it == 1) }
    }
}

@Composable
fun FxSlider(
    labelKey: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    show: (Float) -> String,
    onChange: (Float, Boolean) -> Unit,
) {
    var v by remember(value) { mutableStateOf(value) }
    Column(Modifier.padding(top = 8.dp)) {
        Text(tr(labelKey), color = InkDim, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                v,
                { v = it },
                Modifier.weight(1f),
                valueRange = range,
                onValueChangeFinished = { onChange(v, true) },
                colors = SliderDefaults.colors(thumbColor = Ink, activeTrackColor = Ink, inactiveTrackColor = Ink.copy(alpha = 0.28f)),
            )
            Text(show(v), color = Ink, modifier = Modifier.width(70.dp).padding(start = 12.dp))
        }
    }
}

@Composable
fun Segmented(optionKeys: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.clip(CircleShape).background(Ink.copy(alpha = 0.08f)).padding(3.dp)) {
        optionKeys.forEachIndexed { i, key ->
            val bg by animateColorAsState(if (i == selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            Text(
                tr(key),
                style = MaterialTheme.typography.titleMedium,
                color = if (i == selected) Ink else InkDim,
                modifier = Modifier.clip(CircleShape).background(bg).clickable { onSelect(i) }
                    .pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
fun Pill(icon: ImageVector, labelKey: String, accent: Boolean = false, onClick: () -> Unit) {
    val ink = if (accent) (if (LocalLightTheme.current) Color.White else Color.Black) else Ink
    LiquidButton(
        onClick,
        LocalAppBackdrop.current,
        flat = true,
        contentHeight = 40.dp,
        contentPadding = 18.dp,
        surfaceColor = if (accent) Ink else Color.Unspecified,
    ) {
        Icon(icon, null, tint = ink, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(tr(labelKey), style = MaterialTheme.typography.titleMedium, color = ink)
    }
}

@Composable
fun BackButton(state: AppState) {
    if (state.canGoBack) {
        Box(Modifier.padding(bottom = 10.dp)) {
            Glyph(PhosphorIcons.Regular.CaretLeft, "back", size = 22.dp) { state.back() }
        }
    }
}
