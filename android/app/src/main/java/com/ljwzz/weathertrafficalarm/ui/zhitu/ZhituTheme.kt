package com.ljwzz.weathertrafficalarm.ui.zhitu

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Typography
import com.ljwzz.weathertrafficalarm.R

/** Color roles extracted from the current local prototype/Figma handoff. */
object ZhituColors {
    val Ink = Color(0xFF172D3C)
    val Muted = Color(0xFF647782)
    val Subtle = Color(0xFF8A9AA3)
    val Background = Color(0xFFF7F9FA)
    val Surface = Color.White
    val Brand = Color(0xFF007F78)
    val Navy = Color(0xFF123F4D)
    val Mint = Color(0xFFDEF4EC)
    val Sky = Color(0xFFE3F2FA)
    val Blue = Color(0xFF4F94C4)
    val Line = Color(0xFFE5ECEE)
    val Amber = Color(0xFFAD661C)
    val AmberBackground = Color(0xFFFFF1DA)
}

private val LightScheme = lightColorScheme(
    primary = ZhituColors.Brand,
    onPrimary = Color.White,
    primaryContainer = ZhituColors.Mint,
    onPrimaryContainer = ZhituColors.Brand,
    secondary = ZhituColors.Navy,
    onSecondary = Color.White,
    secondaryContainer = ZhituColors.Sky,
    onSecondaryContainer = ZhituColors.Ink,
    background = ZhituColors.Background,
    onBackground = ZhituColors.Ink,
    surface = ZhituColors.Surface,
    onSurface = ZhituColors.Ink,
    surfaceVariant = ZhituColors.Mint,
    onSurfaceVariant = ZhituColors.Muted,
    outline = ZhituColors.Line,
    error = Color(0xFFB3261E),
)

private val ZhituFont = FontFamily(
    Font(R.font.noto_sans_sc_regular, FontWeight.Normal),
    Font(R.font.noto_sans_sc_medium, FontWeight.Medium),
    Font(R.font.noto_sans_sc_bold, FontWeight.Bold),
)

private val ZhituTypography = Typography().run {
    copy(
        bodyLarge = bodyLarge.copy(fontFamily = ZhituFont), bodyMedium = bodyMedium.copy(fontFamily = ZhituFont),
        bodySmall = bodySmall.copy(fontFamily = ZhituFont), labelLarge = labelLarge.copy(fontFamily = ZhituFont),
        labelMedium = labelMedium.copy(fontFamily = ZhituFont), labelSmall = labelSmall.copy(fontFamily = ZhituFont),
        titleLarge = titleLarge.copy(fontFamily = ZhituFont), titleMedium = titleMedium.copy(fontFamily = ZhituFont),
        titleSmall = titleSmall.copy(fontFamily = ZhituFont), headlineLarge = headlineLarge.copy(fontFamily = ZhituFont),
        headlineMedium = headlineMedium.copy(fontFamily = ZhituFont), headlineSmall = headlineSmall.copy(fontFamily = ZhituFont),
    )
}

@Composable
fun ZhituTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightScheme, typography = ZhituTypography, content = content)
}
