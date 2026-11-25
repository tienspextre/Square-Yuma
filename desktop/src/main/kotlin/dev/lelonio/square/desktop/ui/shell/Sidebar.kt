package dev.lelonio.square.desktop.ui.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Gear
import com.adamglin.phosphoricons.fill.House
import com.adamglin.phosphoricons.regular.ChartBar
import com.adamglin.phosphoricons.regular.DownloadSimple
import com.adamglin.phosphoricons.regular.Folder
import com.adamglin.phosphoricons.regular.VinylRecord
import dev.lelonio.square.desktop.AppState
import dev.lelonio.square.desktop.Screen
import dev.lelonio.square.desktop.str
import dev.lelonio.square.desktop.ui.components.Artwork
import dev.lelonio.square.desktop.ui.components.SectionLabel
import dev.lelonio.square.ui.glass.GlassEffectConfig
import dev.lelonio.square.ui.glass.InteractiveHighlight
import dev.lelonio.square.ui.glass.liquidGlass
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import kotlin.math.roundToInt

val SidebarExpandedWidth = 228.dp
val SidebarRailWidth = 72.dp
private val RowHeight = 42.dp

private data class NavItem(val screen: Screen, val icon: ImageVector, val labelKey: String)

@Composable
fun Sidebar(
    state: AppState,
    config: GlassEffectConfig,
    collapsed: Boolean,
    modifier: Modifier = Modifier,
) {
    val items = remember {
        listOf(
            NavItem(Screen.Home, PhosphorIcons.Fill.House, "home"),
            NavItem(Screen.Library, PhosphorIcons.Regular.VinylRecord, "library"),
            NavItem(Screen.LocalFolders, PhosphorIcons.Regular.Folder, "desktop_local_folders"),
            NavItem(Screen.Downloads, PhosphorIcons.Regular.DownloadSimple, "downloads"),
            NavItem(Screen.Month, PhosphorIcons.Regular.ChartBar, "desktop_your_month"),
            NavItem(Screen.Settings, PhosphorIcons.Fill.Gear, "settings"),
        )
    }

    val activeRoot = state.activeRootScreen
    val selected = items.indexOfFirst {
        it.screen == activeRoot || (it.screen == Screen.Library && activeRoot == Screen.Playlists)
    }
    val width by animateDpAsState(
        if (collapsed) SidebarRailWidth else SidebarExpandedWidth,
        spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
    )
    val puck by animateFloatAsState(
        selected.coerceAtLeast(0).toFloat(),
        spring(dampingRatio = 0.72f, stiffness = 420f),
    )
    val scope = rememberCoroutineScope()
    val glow = remember(scope) { InteractiveHighlight(animationScope = scope) }

    Column(
        modifier
            .width(width)
            .fillMaxHeight()
            .liquidGlass(config, RoundedCornerShape(24.dp), highlightAlpha = 0.3f, blurRadiusDp = config.blurRadius * 6f)
            .then(glow.modifier)
            .then(glow.gestureModifier)
            .padding(horizontal = 10.dp, vertical = 14.dp),
        horizontalAlignment = if (collapsed) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        if (!collapsed) {
            Text(
                str("desktop_app_title"),
                style = MaterialTheme.typography.titleLarge,
                color = Ink,
                modifier = Modifier.padding(start = 10.dp, bottom = 14.dp),
                maxLines = 1,
            )
        }
        Box(Modifier.fillMaxWidth()) {
            if (selected >= 0) {
                Box(
                    Modifier
                        .offset { IntOffset(0, (puck * RowHeight.toPx()).roundToInt()) }
                        .fillMaxWidth()
                        .height(RowHeight)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Ink.copy(alpha = 0.13f)),
                )
            }
            Column {
                items.forEachIndexed { i, item ->
                    NavRow(item, i == selected, collapsed) { state.root(item.screen) }
                }
            }
        }

        if (!collapsed && state.pins.isNotEmpty()) {
            SectionLabel("pinned", Modifier.padding(start = 12.dp, top = 18.dp, bottom = 8.dp))
            Column(Modifier.verticalScroll(rememberScrollState())) {
                state.pins.forEach { pin ->
                    val open = state.currentScreen.let { it is Screen.Playlist && it.id == pin.id }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (open) Ink.copy(alpha = 0.1f) else Color.Transparent)
                            .clickable {
                                state.root(Screen.Playlists)
                                state.open(Screen.Playlist(pin.id, pin.name))
                            }
                            .pointerHoverIcon(PointerIcon.Hand)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(state.cover(pin.cover, 100), Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            pin.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Ink,
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
private fun NavRow(item: NavItem, selected: Boolean, collapsed: Boolean, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val pressed by hover.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(stiffness = Spring.StiffnessMediumLow))
    val tint by animateColorAsState(if (selected) Ink else if (hovered) Ink else InkDim)
    val label = str(item.labelKey)

    Row(
        Modifier
            .fillMaxWidth()
            .height(RowHeight)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(14.dp))
            .background(if (hovered && !selected) Ink.copy(alpha = 0.06f) else Color.Transparent)
            .hoverable(hover)
            .clickable(hover, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = if (collapsed) 0.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (collapsed) androidx.compose.foundation.layout.Arrangement.Center else androidx.compose.foundation.layout.Arrangement.Start,
    ) {
        Icon(item.icon, label, tint = tint, modifier = Modifier.size(20.dp))
        if (!collapsed) {
            Spacer(Modifier.width(12.dp))
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
