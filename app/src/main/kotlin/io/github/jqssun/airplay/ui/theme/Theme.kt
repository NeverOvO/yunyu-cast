package io.github.jqssun.airplay.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val YunyuColors = darkColorScheme(
    primary = Color(0xFFA7CFFF),
    onPrimary = Color(0xFF102640),
    primaryContainer = Color(0xFF28466A),
    onPrimaryContainer = Color(0xFFDCEBFF),
    secondary = Color(0xFFDAC7E2),
    onSecondary = Color(0xFF32293C),
    secondaryContainer = Color(0xFF2B405C),
    onSecondaryContainer = Color(0xFFF4F8FF),
    tertiary = Color(0xFFE8C8D0),
    background = Color(0xFF101E35),
    onBackground = Color(0xFFF4F8FF),
    surface = Color(0xFF14243C),
    onSurface = Color(0xFFF4F8FF),
    surfaceVariant = Color(0xFF1B304D),
    onSurfaceVariant = Color(0xFFB9CAE0),
    surfaceContainer = Color(0xFF1B304D),
    surfaceContainerHigh = Color(0xFF243B58),
    surfaceContainerHighest = Color(0xFF2D4663),
    outline = Color(0xFF7990AE),
    outlineVariant = Color(0xFF3A5271),
    error = Color(0xFFFFB4AB)
)

private val YunyuTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 36.sp, lineHeight = 44.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 28.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 17.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 23.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp)
)

@Composable
fun AirPlayTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = YunyuColors, typography = YunyuTypography, content = content)
}
