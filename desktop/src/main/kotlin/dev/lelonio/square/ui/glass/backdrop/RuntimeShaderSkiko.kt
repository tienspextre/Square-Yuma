// Desktop (Skiko) actuals for the Android app's vendored Kyant0/backdrop
// (Apache License 2.0), whose shared sources this module compiles as they are.
package dev.lelonio.square.ui.glass.backdrop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.asComposeShader
import org.intellij.lang.annotations.Language
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

/** The same AGSL sources compile as SkSL: AGSL is Android's name for Skia's shading language. */
fun RuntimeShader(@Language("AGSL") shaderString: String): RuntimeShader =
    SkikoRuntimeShader(RuntimeShaderBuilder(RuntimeEffect.makeForShader(shaderString)))

fun RuntimeShader.asComposeShader(): Shader = (this as SkikoRuntimeShader).builder.makeShader().asComposeShader()

internal class SkikoRuntimeShader(val builder: RuntimeShaderBuilder) : RuntimeShader {
    override fun setFloatUniform(name: String, value: Float) = builder.uniform(name, value)
    override fun setFloatUniform(name: String, value1: Float, value2: Float) = builder.uniform(name, value1, value2)
    override fun setFloatUniform(name: String, value1: Float, value2: Float, value3: Float) =
        builder.uniform(name, value1, value2, value3)
    override fun setFloatUniform(name: String, value1: Float, value2: Float, value3: Float, value4: Float) =
        builder.uniform(name, value1, value2, value3, value4)
    override fun setFloatUniform(name: String, values: FloatArray) = builder.uniform(name, values)

    override fun setIntUniform(name: String, value: Int) = builder.uniform(name, value)
    override fun setIntUniform(name: String, value1: Int, value2: Int) = builder.uniform(name, value1, value2)
    override fun setIntUniform(name: String, value1: Int, value2: Int, value3: Int) =
        builder.uniform(name, value1, value2, value3)
    override fun setIntUniform(name: String, value1: Int, value2: Int, value3: Int, value4: Int) =
        builder.uniform(name, value1, value2, value3, value4)
    override fun setIntUniform(name: String, values: IntArray) = when (values.size) {
        1 -> setIntUniform(name, values[0])
        2 -> setIntUniform(name, values[0], values[1])
        3 -> setIntUniform(name, values[0], values[1], values[2])
        else -> setIntUniform(name, values[0], values[1], values[2], values[3])
    }

    /**
     * Premultiplied, as Android's setColorUniform hands a layout(color) uniform
     * to the shader. Passed straight, a press highlight's white at 15 % came out
     * as full white under BlendMode.Plus and swallowed the button it lit.
     */
    override fun setColorUniform(name: String, color: Color) =
        builder.uniform(name, color.red * color.alpha, color.green * color.alpha, color.blue * color.alpha, color.alpha)
}
