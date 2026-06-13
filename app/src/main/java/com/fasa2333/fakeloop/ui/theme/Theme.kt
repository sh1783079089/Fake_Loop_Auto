package com.fasa2333.fakeloop.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = LoopGreenDark,
    secondary = LoopMintDark,
    tertiary = LoopAmberDark,
    background = LoopBackgroundDark,
    surface = LoopSurfaceDark,
    surfaceVariant = LoopSurfaceVariantDark,
    onPrimary = Color(0xFF00382F),
    onSecondary = Color(0xFF0D352E),
    onTertiary = Color(0xFF402D00),
    onBackground = LoopTextDark,
    onSurface = LoopTextDark,
    onSurfaceVariant = Color(0xFFC4CCC7),
    outline = LoopOutlineDark
)

private val LightColorScheme = lightColorScheme(
    primary = LoopGreen,
    secondary = LoopMint,
    tertiary = LoopAmber,
    background = LoopBackground,
    surface = LoopSurface,
    surfaceVariant = LoopSurfaceVariant,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = LoopText,
    onSurface = LoopText,
    onSurfaceVariant = Color(0xFF404944),
    outline = LoopOutline
)

@Composable
fun FakeLoopTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor -> if (darkTheme) DarkColorScheme else LightColorScheme
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
