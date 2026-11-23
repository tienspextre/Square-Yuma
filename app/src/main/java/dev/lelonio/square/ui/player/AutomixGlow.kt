package dev.lelonio.square.ui.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lelonio.square.playback.AutomixStateHolder

private val NeonColors = listOf(
    Color(0xFF2E4BFF), Color(0xFF6A7BFF), Color(0xFF9D5CFF), Color(0xFFFF4D7A), Color(0xFF2E4BFF),
)

/**
 * A neon edge round [shape] while Automix is mixing: blue, violet and red light running along it,
 * breathing. Lights up as the mix starts and fades slowly after it; nothing drawn otherwise.
 */
fun Modifier.automixNeonBorder(shape: Shape): Modifier = composed {
    val status = AutomixStateHolder.state.collectAsStateWithLifecycle()
    // Through derivedStateOf: the state ticks every frame of a mix with its progress.
    val mixing by remember { derivedStateOf { status.value.isCrossfading } }
    val on by animateFloatAsState(if (mixing) 1f else 0f, tween(if (mixing) 500 else 1400), label = "neonOn")
    if (!mixing && on <= 0.001f) return@composed this
    val t = rememberInfiniteTransition(label = "neon")
    val sweep by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "sweep")
    val pulse by t.animateFloat(0.6f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
    drawWithContent {
        drawContent()
        // Read here, in the draw phase: the animation redraws, it does not recompose.
        val a = on
        if (a <= 0.001f) return@drawWithContent
        val span = size.width.coerceAtLeast(1f)
        val x = sweep * span
        val brush = Brush.linearGradient(NeonColors, Offset(x, 0f), Offset(x + span, size.height), TileMode.Repeated)
        val outline = shape.createOutline(size, layoutDirection, this)
        val glow = a * pulse
        // Wide and faint under narrow and bright: the halo of a neon tube without a blur pass.
        drawOutline(outline, brush, alpha = 0.18f * glow, style = Stroke(9.dp.toPx()))
        drawOutline(outline, brush, alpha = 0.35f * glow, style = Stroke(4.5.dp.toPx()))
        drawOutline(outline, brush, alpha = a, style = Stroke(1.6.dp.toPx()))
    }
}
