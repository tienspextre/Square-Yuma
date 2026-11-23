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
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lelonio.square.playback.AutomixStateHolder

// Around the clock: a dim base, then the lit stretch (blue, violet, red) that runs round the edge.
private val NeonArgb = intArrayOf(
    0x002E4BFF, 0x002E4BFF, 0xFF2E4BFF.toInt(), 0xFF7A8CFF.toInt(), 0xFF9D5CFF.toInt(), 0xFFFF4D7A.toInt(), 0x00FF4D7A,
)
private val NeonStops = floatArrayOf(0f, 0.45f, 0.62f, 0.72f, 0.82f, 0.92f, 1f)

/**
 * A neon edge round [shape] while Automix is mixing: a stretch of blue, violet and red light
 * running round it over a faint base line, breathing. Lights up as the mix starts and fades slowly
 * after it; nothing drawn otherwise.
 */
fun Modifier.automixNeonBorder(shape: Shape): Modifier = composed {
    val status = AutomixStateHolder.state.collectAsStateWithLifecycle()
    // Through derivedStateOf: the state ticks every frame of a mix with its progress.
    val mixing by remember { derivedStateOf { status.value.isCrossfading } }
    val on by animateFloatAsState(if (mixing) 1f else 0f, tween(if (mixing) 500 else 1400), label = "neonOn")
    if (!mixing && on <= 0.001f) return@composed this
    val t = rememberInfiniteTransition(label = "neon")
    val turn by t.animateFloat(0f, 360f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "turn")
    val pulse by t.animateFloat(0.6f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
    val matrix = remember { android.graphics.Matrix() }
    drawWithContent {
        drawContent()
        // Read here, in the draw phase: the animation redraws, it does not recompose.
        val a = on
        if (a <= 0.001f) return@drawWithContent
        val cx = size.width / 2
        val cy = size.height / 2
        // Squashed to the pill's proportions so the light keeps an even pace along the long sides
        // instead of racing across them and dawdling round the ends.
        matrix.setRotate(turn, cx, cy)
        matrix.postScale(1f, size.height / size.width.coerceAtLeast(1f), cx, cy)
        val shader = android.graphics.SweepGradient(cx, cy, NeonArgb, NeonStops).apply { setLocalMatrix(matrix) }
        val brush = ShaderBrush(shader)
        val outline = shape.createOutline(size, layoutDirection, this)
        val glow = a * pulse
        drawOutline(outline, Color(0xFF6A7BFF), alpha = 0.22f * a, style = Stroke(1.dp.toPx()))
        // Wide and faint under narrow and bright: the halo of a neon tube without a blur pass.
        drawOutline(outline, brush, alpha = 0.22f * glow, style = Stroke(10.dp.toPx()))
        drawOutline(outline, brush, alpha = 0.4f * glow, style = Stroke(5.dp.toPx()))
        drawOutline(outline, brush, alpha = a, style = Stroke(1.8.dp.toPx()))
    }
}
