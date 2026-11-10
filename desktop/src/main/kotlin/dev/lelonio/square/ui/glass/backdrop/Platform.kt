// Desktop (Skiko) actuals for the Android app's vendored Kyant0/backdrop
// (Apache License 2.0), whose shared sources this module compiles as they are.
package dev.lelonio.square.ui.glass.backdrop

// Skia always has image filters and runtime effects.
fun isRenderEffectSupported(): Boolean = true

fun isRuntimeShaderSupported(): Boolean = true
