// Desktop (Skiko) actuals for the Android app's vendored Kyant0/backdrop
// (Apache License 2.0), whose shared sources this module compiles as they are.
package dev.lelonio.square.ui.glass.backdrop.internal

import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.skiaPaint
import androidx.compose.ui.graphics.skiaShader
import dev.lelonio.square.ui.glass.backdrop.RuntimeShader
import dev.lelonio.square.ui.glass.backdrop.asComposeShader
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.MaskFilter

internal fun Paint.blur(radius: Float) {
    // Android's BlurMaskFilter takes a radius and converts it to this sigma.
    skiaPaint.maskFilter =
        if (radius > 0f) MaskFilter.makeBlur(FilterBlurMode.NORMAL, radius * 0.57735f + 0.5f) else null
}

internal fun Paint.setRuntimeShader(runtimeShader: RuntimeShader?) {
    skiaPaint.shader = runtimeShader?.asComposeShader()?.skiaShader
}
