package dev.lelonio.square.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Where a page ends up once the artwork's colour has faded out of it.
 *
 * Not pure black: a page that ends on #000 against a phone's own black bezel
 * has no bottom edge, and the last shelf on it looks like it is falling off.
 */
val PageFloor = Color(0xFF0A0A0C)

/**
 * And where it ends up on the other side, when the system is set to light.
 *
 * Not pure white, for the same reason the floor is not pure black: a page that
 * ends on #FFF has no edge against a white status bar or a white sheet over it.
 */
val PageCeiling = Color(0xFFF1F1F5)

/**
 * The same colour, taken up towards paper instead of down towards the floor.
 *
 * Mixing towards a neutral ceiling is what this did first, and it bleaches:
 * a page that measured 45% saturated on the reference came out at 14%, which is
 * a grey with a rumour of pink in it rather than the record's colour. Holding
 * the hue, easing the saturation down by as much as the mix asks for and
 * setting the brightness where paper belongs keeps a light page recognisably
 * the colour of what is playing.
 */
internal fun liftedTone(colour: Color, mix: Float): Color {
    val (hue, saturation) = colour.hueAndSaturation()
    return Color.hsv(
        hue,
        (saturation * (1f - mix * 0.55f)).coerceIn(0.08f, 0.46f),
        (0.97f - mix * 0.10f).coerceIn(0.82f, 0.97f),
    )
}

/** Hue in degrees and HSV saturation; what android.graphics.Color.colorToHSV gave, without Android. */
private fun Color.hueAndSaturation(): Pair<Float, Float> {
    val max = maxOf(red, green, blue)
    val delta = max - minOf(red, green, blue)
    if (delta == 0f) return 0f to 0f
    val hue = when (max) {
        red -> 60f * (((green - blue) / delta) % 6f)
        green -> 60f * ((blue - red) / delta + 2f)
        else -> 60f * ((red - green) / delta + 4f)
    }
    return (if (hue < 0f) hue + 360f else hue) to delta / max
}

/**
 * The ink a surface needs, given the colour it is actually sitting on.
 *
 * Two different things decide ink in this app, and conflating them is what put
 * white letters on a pale pink page. The app's own chrome — bars, sheets, the
 * pages made of shelves — follows the system's setting. A record's page and the
 * player do not: those are the colour of the artwork, chosen by the catalogue
 * or read off the picture, and that colour is as dark as it is whichever way
 * the phone is set. What can be read on it is decided by it, not by the phone.
 */
fun inkOn(ground: Color): Color =
    if (ground.luminance() > 0.42f) Color(0xFF15161A) else Color(0xFFF7F8FA)

/**
 * An ink that overrides the theme's, for the surfaces that own their colour.
 *
 * Provided by a record's page and by the player, read by [Ink] — so every
 * caller that already asks for the app's ink gets the right one on those
 * screens without knowing anything about them.
 */
val LocalInkOverride = androidx.compose.runtime.compositionLocalOf<Color?> { null }

/** Which way this app's colours are being taken; see SquareTheme. */
@Composable
fun lightPage(): Boolean = LocalLightTheme.current

/**
 * What a scrim is made of on this side of the app.
 *
 * A scrim exists to put distance between a picture and the text over it, and
 * which way that distance goes depends on the ink: black under light ink, white
 * under dark. The same gradient with this colour in it works both ways round.
 */
@Composable
fun scrimColor(): Color {
    // The ink a page has set for itself before the theme's. The player's is
    // light whatever the phone's theme, and read from the theme its scrim was
    // white on a light phone: a black cover ended in a grey wash under the
    // controls, a third of the way to white.
    val ink = LocalInkOverride.current
    val lightPaper = if (ink != null) ink.luminance() < 0.5f else lightPage()
    return if (lightPaper) Color.White else Color.Black
}

/** The colour every page's own colour is mixed towards. */
@Composable
fun pageGround(): Color = if (lightPage()) PageCeiling else PageFloor

/**
 * The page tone for a cover.
 *
 * The dominant colour arrives at full saturation — it has to, it is picked to
 * identify the artwork — and using it as a background would put text on a
 * fluorescent field. Most of the way to the floor keeps the hue recognisable
 * and nothing else. A null cover falls back to the floor rather than to grey.
 */
@Composable
fun pageColorFor(accent: Color?): Color {
    val ground = pageGround()
    // Less of the ground on the light side. Taken as far towards paper as it is
    // taken towards the floor, a colour arrives as a pastel with no record left
    // in it — and the whole point of these pages is that they are the colour of
    // what is playing.
    if (accent == null) return ground
    return if (lightPage()) {
        liftedTone(accent, 0.62f)
    } else {
        androidx.compose.ui.graphics.lerp(accent, ground, 0.7f)
    }
}

/**
 * The ground colour for a picture that is meant to carry on into it.
 *
 * Mixed down far less than [pageColorFor] does. That one is taming a colour
 * picked for being loud; this one is already the colour of a piece of the
 * picture, and taking it most of the way to the floor would undo the only
 * thing it was read for.
 */
@Composable
fun footToneFor(foot: Color?, mix: Float = 0.34f, lift: Boolean = true): Color {
    val ground = if (lift) pageGround() else PageFloor
    if (foot == null) return ground
    return if (lift && lightPage()) {
        liftedTone(foot, mix)
    } else {
        androidx.compose.ui.graphics.lerp(foot, ground, mix)
    }
}

