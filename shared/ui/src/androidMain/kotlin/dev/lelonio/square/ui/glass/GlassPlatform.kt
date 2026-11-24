package dev.lelonio.square.ui.glass

import android.app.ActivityManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext


/**
 * The backdrop blur pipeline requires [android.graphics.RenderEffect] on a
 * [android.graphics.RenderNode], which is available from Android 12 (API 31).
 */
actual fun isGlassSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Devices Android flags as low-RAM ([ActivityManager.isLowRamDevice]) can't push the
 * RenderEffect/RuntimeShader chain (blur + lens + vibrancy, several offscreen layers
 * per glass surface) without dropped frames, so glass is skipped there in favor of the
 * translucent film and rim [liquidGlass] draws where the blur is not available.
 */
@Composable
actual fun isLowRamDevice(): Boolean {
    val context = LocalContext.current
    return remember {
        context.getSystemService(ActivityManager::class.java)?.isLowRamDevice ?: false
    }
}

/** Combines the API-level check with the low-RAM device check; use this to gate glass. */
@Composable
actual fun isGlassAllowed(): Boolean = isGlassSupported() && !isLowRamDevice()

/** The phone records the backdrop at the scale asked for; its pixels are small enough to hide it. */
internal actual fun platformBackdropScale(scale: Float, density: Float): Float = scale
