package dev.lyra.music.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Gold = Color(0xFFF5C66B)
private val GoldDark = Color(0xFFB8893A)
private val Ink = Color(0xFF14141C)

private val DarkColors = darkColorScheme(
    primary = Gold,
    onPrimary = Ink,
    primaryContainer = GoldDark,
    onPrimaryContainer = Color(0xFFFFF0D0),
    secondary = Color(0xFFCFC6B8),
    background = Color(0xFF0F0F14),
    surface = Color(0xFF14141C),
    surfaceVariant = Color(0xFF24242E),
    onSurface = Color(0xFFECE6DC),
    onSurfaceVariant = Color(0xFFB9B2A6),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF7A5A14),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE2A8),
    onPrimaryContainer = Color(0xFF271900),
    secondary = Color(0xFF6B5F4D),
    background = Color(0xFFFCF8F2),
    surface = Color(0xFFFCF8F2),
    surfaceVariant = Color(0xFFEDE6DA),
    onSurface = Color(0xFF1D1B17),
    onSurfaceVariant = Color(0xFF4E463B),
)

/** themeMode: 0 = follow system, 1 = light, 2 = dark. */
@Composable
fun LyraTheme(
    themeMode: Int = 0,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colors,
        typography = Typography(),
        content = content,
    )
}
