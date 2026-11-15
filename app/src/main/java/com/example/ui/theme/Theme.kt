package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Immutable
data class MarketColors(
    val bullish: Color,
    val bullishContainer: Color,
    val bearish: Color,
    val bearishContainer: Color,
    val amber: Color,
    val amberContainer: Color,
    val forecast: Color,
    val forecastContainer: Color,
    val violet: Color,
    val cardBorder: Color,
    val gridLine: Color,
    val surfaceElevated: Color
)

private val DarkMarketColors = MarketColors(
    bullish = BullishEmerald,
    bullishContainer = BullishEmeraldContainer,
    bearish = BearishCrimson,
    bearishContainer = BearishCrimsonContainer,
    amber = MapleAmber,
    amberContainer = MapleAmberContainer,
    forecast = ForecastCyan,
    forecastContainer = ForecastCyanContainer,
    violet = VioletSignal,
    cardBorder = BorderSlate,
    gridLine = Color(0xFF1A2F45),
    surfaceElevated = ElevatedCardNavy
)

private val LightMarketColors = MarketColors(
    bullish = DeepEmerald,
    bullishContainer = Color(0xFFD1FAE5),
    bearish = DeepCrimson,
    bearishContainer = Color(0xFFFFE4E6),
    amber = Color(0xFFD97706),
    amberContainer = Color(0xFFFEF3C7),
    forecast = Color(0xFF0284C7),
    forecastContainer = Color(0xFFE0F2FE),
    violet = Color(0xFF7C3AED),
    cardBorder = LightBorder,
    gridLine = Color(0xFFE2E8F0),
    surfaceElevated = LightSurfaceVariant
)

val LocalMarketColors = staticCompositionLocalOf { DarkMarketColors }

private val DarkTerminalColorScheme = darkColorScheme(
    primary = BullishEmerald,
    onPrimary = Color(0xFF032118),
    primaryContainer = BullishEmeraldContainer,
    onPrimaryContainer = Color(0xFFA7F3D0),
    secondary = ForecastCyan,
    onSecondary = Color(0xFF06263A),
    secondaryContainer = ForecastCyanContainer,
    onSecondaryContainer = Color(0xFFBAE6FD),
    tertiary = MapleAmber,
    onTertiary = Color(0xFF2B1B02),
    tertiaryContainer = MapleAmberContainer,
    onTertiaryContainer = Color(0xFFFDE68A),
    background = MidnightNavy,
    onBackground = TextPrimary,
    surface = DeepSlate,
    onSurface = TextPrimary,
    surfaceVariant = ElevatedCardNavy,
    onSurfaceVariant = TextSecondary,
    outline = BorderSlate,
    outlineVariant = Color(0xFF1C3044),
    error = BearishCrimson,
    onError = Color(0xFFFFFFFF),
    errorContainer = BearishCrimsonContainer,
    onErrorContainer = Color(0xFFFECDD3)
)

private val LightTerminalColorScheme = lightColorScheme(
    primary = DeepEmerald,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1FAE5),
    onPrimaryContainer = Color(0xFF064E3B),
    secondary = Color(0xFF0284C7),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F2FE),
    onSecondaryContainer = Color(0xFF0C4A6E),
    tertiary = Color(0xFFD97706),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFEF3C7),
    onTertiaryContainer = Color(0xFF78350F),
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    outline = LightBorder,
    outlineVariant = Color(0xFFE2E8F0),
    error = DeepCrimson,
    onError = Color.White,
    errorContainer = Color(0xFFFFE4E6),
    onErrorContainer = Color(0xFF881337)
)

val MaplePulseShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp)
)

@Composable
fun MaplePulseTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkTerminalColorScheme else LightTerminalColorScheme
    val marketColors = if (darkTheme) DarkMarketColors else LightMarketColors

    CompositionLocalProvider(LocalMarketColors provides marketColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = MaplePulseTypography,
            shapes = MaplePulseShapes,
            content = content
        )
    }
}
