// Desktop (Skiko) actuals for the Android app's vendored Kyant0/backdrop
// (Apache License 2.0), whose shared sources this module compiles as they are.
package dev.lelonio.square.ui.glass.backdrop.internal

import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asSkiaColorFilter
import androidx.compose.ui.graphics.skiaImageFilter
import dev.lelonio.square.ui.glass.backdrop.RuntimeShader
import dev.lelonio.square.ui.glass.backdrop.SkikoRuntimeShader
import org.jetbrains.skia.ImageFilter

internal fun RenderEffect?.chain(other: RenderEffect): RenderEffect =
    if (this != null) {
        ImageFilter.makeCompose(other.skiaImageFilter, this.skiaImageFilter).asComposeRenderEffect()
    } else {
        other
    }

internal fun RuntimeShaderEffect(runtimeShader: RuntimeShader, uniformShaderName: String): RenderEffect =
    ImageFilter.makeRuntimeShader((runtimeShader as SkikoRuntimeShader).builder, uniformShaderName, null)
        .asComposeRenderEffect()

internal fun ColorFilterEffect(renderEffect: RenderEffect?, colorFilter: ColorFilter): RenderEffect =
    ImageFilter.makeColorFilter(colorFilter.asSkiaColorFilter(), renderEffect?.skiaImageFilter, null)
        .asComposeRenderEffect()
