package dev.lelonio.square.ui.glass.backdrop

// Per-platform halves of the vendored Kyant0/backdrop (Apache License 2.0), see ../LICENSE-backdrop.txt.

expect fun isRenderEffectSupported(): Boolean

expect fun isRuntimeShaderSupported(): Boolean

expect fun RuntimeShader(shaderString: String): RuntimeShader

expect fun RuntimeShader.asComposeShader(): androidx.compose.ui.graphics.Shader
