package dev.lelonio.square.desktop.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeShader
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.fill.Waveform
import dev.lelonio.square.desktop.AudioSignalChain
import dev.lelonio.square.desktop.str
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import org.jetbrains.skia.Color4f
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Gradient
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.Shader

private val NeonColors = arrayOf(
    Color4f(0.18f, 0.29f, 1f, 0f),
    Color4f(0.18f, 0.29f, 1f, 0f),
    Color4f(0.18f, 0.29f, 1f, 1f),
    Color4f(0.48f, 0.55f, 1f, 1f),
    Color4f(0.62f, 0.36f, 1f, 1f),
    Color4f(1f, 0.30f, 0.48f, 1f),
    Color4f(1f, 0.30f, 0.48f, 0f),
)
private val NeonStops = floatArrayOf(0f, 0.45f, 0.62f, 0.72f, 0.82f, 0.92f, 1f)
private val NeonGradient = Gradient(Gradient.Colors(NeonColors, NeonStops, FilterTileMode.CLAMP, null))

/**
 * Skiko port of Android `AutomixGlow.kt`: animated neon border around [shape] while Automix is mixing.
 */
fun Modifier.automixNeonBorder(shape: Shape, mixing: Boolean): Modifier = composed {
    val on by animateFloatAsState(if (mixing) 1f else 0f, tween(if (mixing) 500 else 1400), label = "neonOn")
    if (!mixing && on <= 0.001f) return@composed this
    val t = rememberInfiniteTransition(label = "neon")
    val turn by t.animateFloat(0f, 360f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "turn")
    val pulse by t.animateFloat(0.6f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")

    drawWithContent {
        drawContent()
        val a = on
        if (a <= 0.001f) return@drawWithContent
        val cx = size.width / 2f
        val cy = size.height / 2f
        val aspectY = size.height / size.width.coerceAtLeast(1f)
        val matrix = Matrix33.makeTranslate(cx, cy)
            .makeConcat(Matrix33.makeScale(1f, aspectY))
            .makeConcat(Matrix33.makeRotate(turn))
            .makeConcat(Matrix33.makeTranslate(-cx, -cy))
        val shader = Shader.makeSweepGradient(cx, cy, NeonGradient, matrix).asComposeShader()
        val brush = ShaderBrush(shader)
        val outline = shape.createOutline(size, layoutDirection, this)
        val glow = a * pulse
        drawOutline(outline, Color(0xFF6A7BFF), alpha = 0.22f * a, style = Stroke(1.dp.toPx()))
        drawOutline(outline, brush, alpha = 0.22f * glow, style = Stroke(10.dp.toPx()))
        drawOutline(outline, brush, alpha = 0.4f * glow, style = Stroke(5.dp.toPx()))
        drawOutline(outline, brush, alpha = a, style = Stroke(1.8.dp.toPx()))
    }
}

/**
 * Skiko port of Android `MixRing.kt`: rotating accent halo around the play button while mixing.
 */
@Composable
fun Modifier.mixRing(
    on: Boolean,
    color: Color = MaterialTheme.colorScheme.primary,
): Modifier {
    if (!on) return this
    val turn = rememberInfiniteTransition(label = "mixRing")
    val angle by turn.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(4_000, easing = LinearEasing)),
        label = "mixRingAngle",
    )
    return this
        .drawBehind {
            val stroke = 3.dp.toPx()
            val inset = -stroke * 1.4f
            val cx = size.width / 2f
            val cy = size.height / 2f
            val dim = Color4f(color.red, color.green, color.blue, 0.15f)
            val lit = Color4f(color.red, color.green, color.blue, color.alpha)
            val grad = Gradient(Gradient.Colors(arrayOf(dim, lit, dim), floatArrayOf(0f, 0.5f, 1f), FilterTileMode.CLAMP, null))
            val matrix = Matrix33.makeRotate(angle, cx, cy)
            val shader = Shader.makeSweepGradient(cx, cy, grad, matrix).asComposeShader()
            drawRoundRect(
                brush = ShaderBrush(shader),
                topLeft = Offset(inset, inset),
                size = Size(size.width - 2 * inset, size.height - 2 * inset),
                cornerRadius = CornerRadius(size.height),
                style = Stroke(stroke),
            )
        }
        .drawBehind {
            drawRoundRect(
                color = color.copy(alpha = 0.22f),
                topLeft = Offset(-6.dp.toPx(), -6.dp.toPx()),
                size = Size(size.width + 12.dp.toPx(), size.height + 12.dp.toPx()),
                cornerRadius = CornerRadius(size.height),
            )
        }
}

/**
 * Interactive Automix / format badge: shows audio quality at rest, animated MixingBars during Automix,
 * and opens the Audio & Automix inspector tab when clicked.
 */
@Composable
fun AutomixBadge(
    chain: AudioSignalChain,
    smartAutomix: Boolean,
    color: Color = Ink,
    dimColor: Color = InkDim,
    onClick: (() -> Unit)? = null,
) {
    val label = chain.qualityLabel ?: if (smartAutomix) str("desktop_automix_label") else return
    AnimatedContent(
        targetState = chain.mixing,
        transitionSpec = {
            (fadeIn(tween(350)) + scaleIn(tween(350), initialScale = 0.92f)) togetherWith
                (fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 0.92f)) using
                SizeTransform(clip = false)
        },
        label = "automixBadge",
    ) { mixing ->
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(color.copy(alpha = if (mixing) 0.18f else 0.10f))
                .then(
                    if (onClick != null) Modifier.clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
                    else Modifier,
                )
                .padding(horizontal = 9.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (mixing) {
                MixingBars(color)
                Text(
                    str("desktop_automix_label"),
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    fontWeight = FontWeight.SemiBold,
                )
            } else {
                Icon(
                    imageVector = PhosphorIcons.Fill.Waveform,
                    contentDescription = null,
                    tint = dimColor,
                    modifier = Modifier.size(11.dp),
                )
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = dimColor,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun MixingBars(color: Color) {
    val t = rememberInfiniteTransition(label = "mixBars")
    val phases = listOf(0, 180, 90)
    val heights = phases.map { delayMs ->
        t.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(420, delayMillis = delayMs, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "bar$delayMs",
        )
    }
    Row(
        Modifier.size(width = 11.dp, height = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(1.5.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        heights.forEach { h ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(h.value)
                    .clip(RoundedCornerShape(1.dp))
                    .background(color),
            )
        }
    }
}
