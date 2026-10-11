package com.zima26.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.zima26.app.R

/** Fuente de máquina de escribir para todos los menús ("Special Elite", Apache 2.0). */
val Typewriter = FontFamily(Font(R.font.special_elite))

/** Fuente manuscrita ("Homemade Apple", Apache 2.0), la misma que se escribe en la foto. */
val Handwriting = FontFamily(Font(R.font.homemade_apple))

private val base = Typography()

/** Toda la tipografía de Material 3 con la fuente de máquina de escribir. */
val VintageTypography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = Typewriter),
    displayMedium = base.displayMedium.copy(fontFamily = Typewriter),
    displaySmall = base.displaySmall.copy(fontFamily = Typewriter),
    headlineLarge = base.headlineLarge.copy(fontFamily = Typewriter),
    headlineMedium = base.headlineMedium.copy(fontFamily = Typewriter),
    headlineSmall = base.headlineSmall.copy(fontFamily = Typewriter),
    titleLarge = base.titleLarge.copy(fontFamily = Typewriter, letterSpacing = 1.5.sp),
    titleMedium = base.titleMedium.copy(fontFamily = Typewriter, letterSpacing = 1.sp),
    titleSmall = base.titleSmall.copy(fontFamily = Typewriter),
    bodyLarge = base.bodyLarge.copy(fontFamily = Typewriter),
    bodyMedium = base.bodyMedium.copy(fontFamily = Typewriter),
    bodySmall = base.bodySmall.copy(fontFamily = Typewriter),
    labelLarge = base.labelLarge.copy(fontFamily = Typewriter, letterSpacing = 1.sp),
    labelMedium = base.labelMedium.copy(fontFamily = Typewriter),
    labelSmall = base.labelSmall.copy(fontFamily = Typewriter),
)

val HandwritingStyle = TextStyle(fontFamily = Handwriting, fontSize = 22.sp)
