package dev.lelonio.square.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

/** The Android app's Inter, from the same variable file (copied in by the build). */
val Inter = FontFamily(
    listOf(300, 400, 500, 600, 700, 800, 900).map { weight ->
        Font(
            resource = "font/inter.ttf",
            weight = FontWeight(weight),
            variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
        )
    },
)
