package com.example.mystuff.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

val BackgroundObsidian = Color(0xFF070807)
val SurfaceObsidian = Color(0xFF121412)
val AccentCopper = Color(0xFFFA542F)
val AccentSage = Color(0xFF8DA68C)
val TextLimestone = Color(0xFFF2F4F2)
val TextStoneGray = Color(0xFF848A84)
val BorderSteel = Color(0xFF1F241F)

val BitkeyColorScheme = darkColorScheme(
    background = BackgroundObsidian,
    surface = SurfaceObsidian,
    surfaceVariant = BorderSteel,
    primary = AccentCopper,
    onPrimary = BackgroundObsidian,
    primaryContainer = AccentCopper,
    onPrimaryContainer = BackgroundObsidian,
    secondary = AccentSage,
    onSecondary = BackgroundObsidian,
    secondaryContainer = BorderSteel,
    onSecondaryContainer = TextLimestone,
    outline = BorderSteel,
    outlineVariant = BorderSteel,
    onBackground = TextLimestone,
    onSurface = TextLimestone,
    onSurfaceVariant = TextStoneGray,
)

val BitkeyTypography = Typography(
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 28.sp,
        letterSpacing = 0.sp,
        color = TextLimestone,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        color = TextStoneGray,
    ),
)

@Composable
fun MyStuffTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BitkeyColorScheme,
        typography = BitkeyTypography,
        content = content,
    )
}
