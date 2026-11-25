package dev.lelonio.square.desktop.ui.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.X
import dev.lelonio.square.desktop.AppState
import dev.lelonio.square.desktop.InspectorTab
import dev.lelonio.square.desktop.str
import dev.lelonio.square.desktop.ui.components.Glyph
import dev.lelonio.square.desktop.ui.lyrics.LyricsInspectorContent
import dev.lelonio.square.ui.glass.GlassEffectConfig
import dev.lelonio.square.ui.glass.liquidGlass
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim

val InspectorWidth = 340.dp

@Composable
fun InspectorPane(
    state: AppState,
    config: GlassEffectConfig,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .width(InspectorWidth)
            .fillMaxHeight()
            .liquidGlass(config, RoundedCornerShape(24.dp), highlightAlpha = 0.3f, blurRadiusDp = config.blurRadius * 6f)
            .padding(14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                Modifier.weight(1f).clip(CircleShape).background(Ink.copy(alpha = 0.08f)).padding(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val tabs = listOf("lyrics", "queue", "desktop_audio_tab")
                tabs.forEachIndexed { idx, key ->
                    val selected = state.inspectorTab.ordinal == idx
                    val bg by animateColorAsState(if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(CircleShape)
                            .background(bg)
                            .clickable { state.openInspector(InspectorTab.entries[idx]) }
                            .pointerHoverIcon(PointerIcon.Hand)
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            str(key),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) Ink else InkDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Glyph(PhosphorIcons.Regular.X, "close", size = 14.dp) {
                state.toggleInspector()
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (state.inspectorTab) {
                InspectorTab.LYRICS -> LyricsInspectorContent(state)
                InspectorTab.QUEUE -> QueueInspectorContent(state)
                InspectorTab.AUDIO -> AudioInspectorContent(state)
            }
        }
    }
}
