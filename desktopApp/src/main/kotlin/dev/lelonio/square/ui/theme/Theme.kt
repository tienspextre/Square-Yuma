package dev.lelonio.square.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val LocalLightTheme = staticCompositionLocalOf { false }
val LocalInkOverride = compositionLocalOf<Color?> { null }

val PageFloor = Color(0xFF0A0A0C)
val PageCeiling = Color(0xFFF1F1F5)

private val DarkBase = Color(0xFF0D0E11)
private val LightBase = Color(0xFFF1F2F6)

private val DarkFilm = Color.White.copy(alpha = 0.10f)
private val DarkFilmStrong = Color.White.copy(alpha = 0.16f)
private val LightFilm = Color.White.copy(alpha = 0.55f)
private val LightFilmStrong = Color.White.copy(alpha = 0.72f)

private val DarkInk = Color(0xFFF7F8FA)
private val LightInk = Color(0xFF15161A)

val Ink: Color
    @Composable get() = LocalInkOverride.current ?: MaterialTheme.colorScheme.onSurface

val AppInk: Color
    @Composable get() = if (lightPage()) LightInk else DarkInk

val InkDim: Color
    @Composable get() = Ink.copy(alpha = 0.66f)

private val DefaultAccent = Color(0xFF7C5CE6)

@Composable
fun lightPage(): Boolean = LocalLightTheme.current

fun inkOn(ground: Color): Color =
    if (ground.luminance() > 0.42f) Color(0xFF15161A) else Color(0xFFF7F8FA)

@Composable
fun scrimColor(): Color {
    val ink = LocalInkOverride.current
    val lightPaper = if (ink != null) ink.luminance() < 0.5f else lightPage()
    return if (lightPaper) Color.White else Color.Black
}

@Composable
fun pageGround(): Color = if (lightPage()) PageCeiling else PageFloor

@Composable
fun SquareTheme(
    seed: Color? = null,
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val base = seed ?: DefaultAccent
    val accent = if (darkTheme) base.liftFor(DarkBase) else base.deepenFor(LightBase)
    val animatedAccent by animateColorAsState(accent, tween(600), label = "accent")

    val scheme = remember(animatedAccent, darkTheme) {
        val ink = if (darkTheme) DarkInk else LightInk
        val film = if (darkTheme) DarkFilm else LightFilm
        val filmStrong = if (darkTheme) DarkFilmStrong else LightFilmStrong
        if (darkTheme) {
            darkColorScheme(
                primary = animatedAccent,
                onPrimary = Color(0xFF0B0D10),
                primaryContainer = film,
                onPrimaryContainer = ink,
                secondary = ink.copy(alpha = 0.66f),
                background = Color.Transparent,
                onBackground = ink,
                surface = film,
                onSurface = ink,
                surfaceVariant = filmStrong,
                onSurfaceVariant = ink.copy(alpha = 0.66f),
                outlineVariant = Color.White.copy(alpha = 0.16f),
            )
        } else {
            lightColorScheme(
                primary = animatedAccent,
                onPrimary = Color(0xFFFFFFFF),
                primaryContainer = film,
                onPrimaryContainer = ink,
                secondary = ink.copy(alpha = 0.66f),
                background = Color.Transparent,
                onBackground = ink,
                surface = film,
                onSurface = ink,
                surfaceVariant = filmStrong,
                onSurfaceVariant = ink.copy(alpha = 0.66f),
                outlineVariant = Color.Black.copy(alpha = 0.14f),
            )
        }
    }

    CompositionLocalProvider(LocalLightTheme provides !darkTheme) {
        MaterialTheme(
            colorScheme = scheme,
            typography = DesktopTypography,
            content = content,
        )
    }
}

private fun Color.liftFor(background: Color): Color {
    val floor = background.luminance() + 0.16f
    if (luminance() >= floor) return this
    val amount = ((floor - luminance()) * 2f).coerceIn(0f, 1f)
    return Color(
        red = red + (1f - red) * amount,
        green = green + (1f - green) * amount,
        blue = blue + (1f - blue) * amount,
    )
}

private fun Color.deepenFor(background: Color): Color {
    val ceiling = background.luminance() - 0.62f
    if (luminance() <= ceiling) return this
    val amount = ((luminance() - ceiling) * 1.6f).coerceIn(0f, 0.86f)
    return Color(
        red = red * (1f - amount),
        green = green * (1f - amount),
        blue = blue * (1f - amount),
    )
}

fun Modifier.softShadow(
    shape: Shape,
    elevation: Dp = 18.dp,
    ambient: Float = 0.10f,
    spot: Float = 0.13f,
): Modifier = shadow(
    elevation = elevation,
    shape = shape,
    clip = false,
    ambientColor = Color.Black.copy(alpha = ambient),
    spotColor = Color.Black.copy(alpha = spot),
)

private val DesktopTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 34.sp,
        lineHeight = 38.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.6).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 26.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.4).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 13.5.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
    ),
)
