package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.R

/*
 * "Cuarto oscuro": a photographer's darkroom. Black with warm grays, lit by a single safelight red-orange;
 * photos bring all the other color.
 */
object Darkroom {
    val Canvas = Color(0xFF000000)
    val Sheet = Color(0xFF0C0B0A)
    val Raised = Color(0xFF171513)
    val Nav = Color(0xFF1F1C1A)
    val Chip = Color(0xFF1D1A18)
    val Hairline = Color(0xFF2E2A27)
    val Accent = Color(0xFFFF5B3A)
    val AccentSoft = Color(0xFF2E120A)
    val OnAccent = Color(0xFF1F0700)
    val Danger = Color(0xFFFF4D8D)
    val DangerSoft = Color(0xFF2E0F1D)
    val Ink = Color(0xFFF6F1EC)
    val OnInk = Color(0xFF0C0B0A)
    val Muted = Color(0xFF9E958E)
    val Shadow = Color(0x99000000)
}

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: FontWeight) = Font(
    resId = res,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight))
)

private val allWeights = listOf(
    FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold, FontWeight.Black
)

/** Brand, headings and buttons. */
val Bricolage = FontFamily(allWeights.map { variable(R.font.bricolage_var, it) })

/** Reading text. */
val DmSans = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { variable(R.font.dmsans_var, it) }
)

/** Condensed numerals: the big day numbers of the timeline. */
val Anton = FontFamily(Font(R.font.anton_regular, FontWeight.Normal))

/** Captions, dates and technical facts. */
val JetBrainsMono = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.Bold).map { variable(R.font.jetbrains_mono_var, it) }
)

val WordmarkStyle = TextStyle(
    fontFamily = Bricolage,
    fontWeight = FontWeight.Black,
    fontSize = 36.sp,
    lineHeight = 38.sp,
    letterSpacing = (-1.6).sp
)

val DayNumeralStyle = TextStyle(fontFamily = Anton, fontSize = 56.sp, lineHeight = 58.sp)

private val GalleryTypography = Typography(
    displayMedium = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Black, fontSize = 36.sp, lineHeight = 40.sp, letterSpacing = (-1.2).sp),
    headlineMedium = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Black, fontSize = 32.sp, lineHeight = 36.sp, letterSpacing = (-1).sp),
    headlineSmall = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Black, fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 28.sp, letterSpacing = (-0.4).sp),
    titleMedium = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = DmSans, fontSize = 17.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = DmSans, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, letterSpacing = 0.8.sp),
    labelSmall = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, letterSpacing = 0.4.sp)
)

@Composable
fun GalleryTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Darkroom.Accent,
            onPrimary = Darkroom.OnAccent,
            primaryContainer = Darkroom.AccentSoft,
            onPrimaryContainer = Darkroom.Accent,
            secondary = Darkroom.Ink,
            onSecondary = Darkroom.OnInk,
            error = Darkroom.Danger,
            onError = Color.Black,
            background = Darkroom.Canvas,
            onBackground = Darkroom.Ink,
            surface = Darkroom.Sheet,
            onSurface = Darkroom.Ink,
            surfaceVariant = Darkroom.Raised,
            onSurfaceVariant = Darkroom.Muted,
            surfaceContainer = Darkroom.Raised,
            surfaceContainerHigh = Darkroom.Raised,
            surfaceContainerLow = Darkroom.Sheet,
            outline = Darkroom.Hairline,
            outlineVariant = Darkroom.Hairline,
            scrim = Color(0xD9000000)
        ),
        typography = GalleryTypography,
        content = content
    )
}
