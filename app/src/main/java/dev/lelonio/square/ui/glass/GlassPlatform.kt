package dev.lelonio.square.ui.glass

import android.app.ActivityManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

// The Android half of GlassEffect.kt; the desktop app has its own file of the same name.

/**
 * The backdrop blur pipeline requires [android.graphics.RenderEffect] on a
 * [android.graphics.RenderNode], which is available from Android 12 (API 31).
 */
fun isGlassSupported(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= Build.VERSION_CODES.S

/**
 * Devices Android flags as low-RAM ([ActivityManager.isLowRamDevice]) can't push the
 * RenderEffect/RuntimeShader chain (blur + lens + vibrancy, several offscreen layers
 * per glass surface) without dropped frames, so glass is skipped there in favor of the
 * translucent film and rim [liquidGlass] draws where the blur is not available.
 */
@Composable
fun isLowRamDevice(): Boolean {
    val context = LocalContext.current
    return remember {
        context.getSystemService(ActivityManager::class.java)?.isLowRamDevice ?: false
    }
}

/** Combines the API-level check with the low-RAM device check; use this to gate glass. */
@Composable
fun isGlassAllowed(): Boolean = isGlassSupported() && !isLowRamDevice()

/** The phone records the backdrop at the scale asked for; its pixels are small enough to hide it. */
internal fun platformBackdropScale(scale: Float, density: Float): Float = scale
