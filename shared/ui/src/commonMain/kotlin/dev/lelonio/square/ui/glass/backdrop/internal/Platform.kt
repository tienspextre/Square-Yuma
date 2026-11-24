package dev.lelonio.square.ui.glass.backdrop.internal

import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RenderEffect
import dev.lelonio.square.ui.glass.backdrop.RuntimeShader

internal expect fun RenderEffect?.chain(other: RenderEffect): RenderEffect

internal expect fun RuntimeShaderEffect(runtimeShader: RuntimeShader, uniformShaderName: String): RenderEffect

internal expect fun ColorFilterEffect(renderEffect: RenderEffect?, colorFilter: ColorFilter): RenderEffect

internal expect fun Paint.blur(radius: Float)

internal expect fun Paint.setRuntimeShader(runtimeShader: RuntimeShader?)
