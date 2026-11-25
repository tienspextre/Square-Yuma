package dev.lelonio.square.desktop.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowScope
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Bold
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.bold.MagnifyingGlass
import com.adamglin.phosphoricons.regular.CaretLeft
import com.adamglin.phosphoricons.regular.CaretRight
import com.adamglin.phosphoricons.regular.CornersIn
import com.adamglin.phosphoricons.regular.CornersOut
import com.adamglin.phosphoricons.regular.Minus
import com.adamglin.phosphoricons.regular.PictureInPicture
import com.adamglin.phosphoricons.regular.SidebarSimple
import com.adamglin.phosphoricons.regular.Square
import com.adamglin.phosphoricons.regular.X
import dev.lelonio.square.desktop.AppState
import dev.lelonio.square.desktop.Screen
import dev.lelonio.square.desktop.str
import dev.lelonio.square.ui.glass.GlassEffectConfig
import dev.lelonio.square.ui.glass.liquidGlass
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import kotlin.math.roundToInt

val TitleBarHeight = 44.dp

@Composable
fun WindowScope.GlassTitleBar(
    state: AppState,
    config: GlassEffectConfig,
    window: ComposeWindow,
    frameController: NativeWindowFrameController,
    sidebarCollapsed: Boolean,
    onCloseWindow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(state.quickSearchFocusTick) {
        if (state.quickSearchFocusTick > 0) searchFocus.requestFocus()
    }

    var minRect by remember { mutableStateOf(PixelRect()) }
    var maxRect by remember { mutableStateOf(PixelRect()) }
    var closeRect by remember { mutableStateOf(PixelRect()) }
    LaunchedEffect(minRect, maxRect, closeRect) {
        frameController.updateButtons(minRect, maxRect, closeRect)
    }

    val isWindows = remember { System.getProperty("os.name").startsWith("Windows") }
    val barContent: @Composable () -> Unit = {
        Row(
            modifier
                .fillMaxWidth()
                .height(TitleBarHeight)
                .onGloballyPositioned { frameController.updateCaptionHeight(it.size.height) }
                .padding(start = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left: Sidebar toggle + Back/Forward navigation history
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.trackInteractive(frameController, "nav"),
            ) {
                TitleBarIconButton(
                    icon = PhosphorIcons.Regular.SidebarSimple,
                    label = str(if (sidebarCollapsed) "desktop_expand_sidebar" else "desktop_collapse_sidebar"),
                    enabled = true,
                    active = !sidebarCollapsed,
                    onClick = { state.toggleSidebar(sidebarCollapsed) },
                )
                TitleBarIconButton(
                    icon = PhosphorIcons.Regular.CaretLeft,
                    label = str("back"),
                    enabled = state.canGoBack,
                    onClick = { state.back() },
                )
                TitleBarIconButton(
                    icon = PhosphorIcons.Regular.CaretRight,
                    label = str("desktop_forward"),
                    enabled = state.canGoForward,
                    onClick = { state.forward() },
                )
            }

            Spacer(Modifier.width(12.dp))

            // Center-left: Quick Search box (Ctrl+K)
            Box(
                Modifier
                    .weight(1f)
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { if (!isWindows) toggleMaximizeWindow(window) })
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(
                    Modifier
                        .widthIn(min = 220.dp, max = 380.dp)
                        .height(30.dp)
                        .trackInteractive(frameController, "quickSearch")
                        .clip(RoundedCornerShape(15.dp))
                        .background(Ink.copy(alpha = 0.09f))
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(PhosphorIcons.Bold.MagnifyingGlass, null, tint = InkDim, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (state.searchQuery.isEmpty()) {
                            Text(str("desktop_quick_search_hint"), style = MaterialTheme.typography.bodySmall, color = InkDim, maxLines = 1)
                        }
                        BasicTextField(
                            value = state.searchQuery,
                            onValueChange = { next ->
                                state.searchQuery = next
                                if (next.isNotBlank() && state.currentScreen != Screen.Search) {
                                    state.root(Screen.Search)
                                }
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(color = Ink),
                            cursorBrush = SolidColor(Ink),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(searchFocus)
                                .onFocusChanged { state.typing = it.isFocused },
                        )
                    }
                    if (state.searchQuery.isNotEmpty()) {
                        Icon(
                            PhosphorIcons.Regular.X,
                            str("clear"),
                            tint = InkDim,
                            modifier = Modifier
                                .size(14.dp)
                                .clickable { state.searchQuery = "" }
                                .pointerHoverIcon(PointerIcon.Hand),
                        )
                    }
                }
            }

            // Right utility controls: Mini-player toggle stub + Inspector toggle
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.trackInteractive(frameController, "tools").padding(end = 6.dp),
            ) {
                TitleBarIconButton(
                    icon = PhosphorIcons.Regular.PictureInPicture,
                    label = str("desktop_mini_player"),
                    enabled = true,
                    onClick = { state.requestMiniPlayerToggle() },
                )
                TitleBarIconButton(
                    icon = PhosphorIcons.Regular.SidebarSimple,
                    label = str("desktop_inspector"),
                    enabled = true,
                    active = state.inspectorOpen,
                    onClick = { state.toggleInspector() },
                )
            }

            // Windows-style Min / Max / Close buttons
            val maximized = isWindowMaximized(window)
            Row(Modifier.fillMaxHeight()) {
                WindowCaptionButton(
                    icon = PhosphorIcons.Regular.Minus,
                    label = str("desktop_minimize"),
                    isClose = false,
                    onPositioned = { minRect = it },
                    onClick = { window.extendedState = window.extendedState or java.awt.Frame.ICONIFIED },
                )
                WindowCaptionButton(
                    icon = if (maximized) PhosphorIcons.Regular.CornersIn else PhosphorIcons.Regular.Square,
                    label = str(if (maximized) "desktop_restore" else "desktop_maximize"),
                    isClose = false,
                    onPositioned = { maxRect = it },
                    onClick = { toggleMaximizeWindow(window) },
                )
                WindowCaptionButton(
                    icon = PhosphorIcons.Regular.X,
                    label = str("close"),
                    isClose = true,
                    onPositioned = { closeRect = it },
                    onClick = onCloseWindow,
                )
            }
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .liquidGlass(config, RoundedCornerShape(0.dp), applyEdgeEffects = false, blurRadiusDp = config.blurRadius * 3f),
    ) {
        if (isWindows) {
            barContent()
        } else {
            WindowDraggableArea {
                barContent()
            }
        }
    }
}

@Composable
private fun TitleBarIconButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Box(
        Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(
                when {
                    active -> Ink.copy(alpha = 0.14f)
                    hovered && enabled -> Ink.copy(alpha = 0.08f)
                    else -> Color.Transparent
                },
            )
            .alpha(if (enabled) 1f else 0.34f)
            .hoverable(hover)
            .clickable(enabled = enabled, onClick = onClick)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (active) Ink else InkDim, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun WindowCaptionButton(
    icon: ImageVector,
    label: String,
    isClose: Boolean,
    onPositioned: (PixelRect) -> Unit,
    onClick: () -> Unit,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val bg = when {
        isClose && hovered -> Color(0xFFE81123)
        hovered -> Ink.copy(alpha = 0.10f)
        else -> Color.Transparent
    }
    val tint = if (isClose && hovered) Color.White else Ink
    Box(
        Modifier
            .width(46.dp)
            .fillMaxHeight()
            .onGloballyPositioned { coords ->
                val b = coords.boundsInWindow()
                onPositioned(PixelRect(b.left.roundToInt(), b.top.roundToInt(), b.right.roundToInt(), b.bottom.roundToInt()))
            }
            .background(bg)
            .hoverable(hover)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(15.dp))
    }
}

private fun Modifier.trackInteractive(controller: NativeWindowFrameController, key: String): Modifier =
    this.onGloballyPositioned { coords ->
        val b = coords.boundsInWindow()
        controller.setInteractiveRect(
            key,
            PixelRect(b.left.roundToInt(), b.top.roundToInt(), b.right.roundToInt(), b.bottom.roundToInt()),
        )
    }
