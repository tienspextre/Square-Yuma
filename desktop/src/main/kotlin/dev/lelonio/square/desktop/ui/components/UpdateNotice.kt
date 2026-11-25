package dev.lelonio.square.desktop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.lelonio.square.desktop.Update
import dev.lelonio.square.desktop.str
import dev.lelonio.square.ui.glass.LocalAppBackdrop
import dev.lelonio.square.ui.glass.LocalGlassEffectConfig
import dev.lelonio.square.ui.glass.liquidGlass
import dev.lelonio.square.ui.glass.shapes.ContinuousRoundedRectangle
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import dev.lelonio.square.ui.theme.LocalLightTheme

private val UpdatePanelShape = ContinuousRoundedRectangle(28.dp)

@Composable
fun UpdateNoticeOverlay(
    update: Update?,
    visible: Boolean,
    downloading: Boolean,
    progress: Float?,
    errorMessage: String?,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
) {
    val light = LocalLightTheme.current
    val dim = if (light) Color.Black.copy(alpha = 0.35f) else Color(0xFF0A0A0D).copy(alpha = 0.62f)
    val lift = if (light) Color.White.copy(alpha = 0.72f) else Color.White.copy(alpha = 0.08f)

    AnimatedVisibility(
        visible = visible && update != null,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(160)),
    ) {
        val u = update ?: return@AnimatedVisibility
        Box(
            Modifier
                .fillMaxSize()
                .background(dim)
                .clickable(enabled = !downloading, onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = scaleIn(tween(220), initialScale = 0.94f) + fadeIn(tween(220)),
                exit = scaleOut(tween(150), targetScale = 0.96f) + fadeOut(tween(150)),
            ) {
                Column(
                    Modifier
                        .padding(horizontal = 28.dp)
                        .widthIn(max = 460.dp)
                        .fillMaxWidth()
                        .liquidGlass(
                            config = LocalGlassEffectConfig.current,
                            shape = UpdatePanelShape,
                            ownBackdrop = LocalAppBackdrop.current,
                            onDrawTint = { drawRect(lift) },
                        )
                        .clickable(enabled = false) {}
                        .padding(24.dp),
                ) {
                    val title = if (u.prerelease) {
                        "${str("update_available", u.version)} (${str("desktop_beta_tag")})"
                    } else {
                        str("update_available", u.version)
                    }
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = Ink,
                    )
                    if (u.expectedBytes > 0L) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "%.1f MB".format(u.expectedBytes / 1_048_576.0),
                            style = MaterialTheme.typography.bodySmall,
                            color = InkDim,
                        )
                    }
                    if (u.notes.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            u.notes,
                            style = MaterialTheme.typography.bodyMedium,
                            color = InkDim,
                            modifier = Modifier
                                .heightIn(max = 200.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                    if (!errorMessage.isNullOrBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFF6B6B),
                        )
                    }

                    Spacer(Modifier.height(20.dp))
                    if (downloading) {
                        if (progress != null) {
                            LinearProgressIndicator(
                                progress = { progress.coerceIn(0f, 1f) },
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = Ink.copy(alpha = 0.16f),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "${str("update_downloading")} ${(progress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = InkDim,
                            )
                        } else {
                            LinearProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = Ink.copy(alpha = 0.16f),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                str("update_downloading"),
                                style = MaterialTheme.typography.bodySmall,
                                color = InkDim,
                            )
                        }
                    } else {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            NoticeAction(
                                label = str("later"),
                                container = Ink.copy(alpha = 0.12f),
                                content = Ink,
                                modifier = Modifier.weight(1f),
                                onClick = onDismiss,
                            )
                            NoticeAction(
                                label = if (u.canInstallInApp) str("update_now") else str("desktop_open_release_page"),
                                container = MaterialTheme.colorScheme.primary,
                                content = Color.White,
                                modifier = Modifier.weight(1f),
                                onClick = onAction,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoticeAction(
    label: String,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .height(42.dp)
            .clip(CircleShape)
            .background(container)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = content,
        )
    }
}
