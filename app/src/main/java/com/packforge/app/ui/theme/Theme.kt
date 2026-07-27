package com.packforge.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Emerald40,
    onPrimary = NavyDark,
    primaryContainer = Emerald10,
    onPrimaryContainer = Emerald80,
    secondary = Sky40,
    onSecondary = NavyDark,
    secondaryContainer = Slate20,
    onSecondaryContainer = SnowWhite,
    tertiary = Slate80,
    onTertiary = NavyDark,
    error = Rose40,
    onError = SnowWhite,
    errorContainer = Color(0xFF450A0A), // Very dark red
    onErrorContainer = Rose40,
    background = NavyDark,
    onBackground = SnowWhite,
    surface = NavySurface,
    onSurface = SnowWhite,
    surfaceVariant = NavyVariant,
    onSurfaceVariant = SilverGrey,
    outline = Slate40
)

private val LightColorScheme = lightColorScheme(
    primary = Emerald40,
    onPrimary = SnowWhite,
    primaryContainer = Color(0xFFD1FAE5), // Very light emerald
    onPrimaryContainer = Emerald10,
    secondary = Sky40,
    onSecondary = SnowWhite,
    secondaryContainer = SilverGrey,
    onSecondaryContainer = Slate10,
    tertiary = Slate40,
    onTertiary = SnowWhite,
    error = Rose40,
    onError = SnowWhite,
    errorContainer = Color(0xFFFECDD3), // Light rose
    onErrorContainer = Color(0xFF881337), // Dark rose
    background = GhostWhite,
    onBackground = Slate10,
    surface = SnowWhite,
    onSurface = Slate10,
    surfaceVariant = SilverGrey,
    onSurfaceVariant = Slate20,
    outline = Slate80
)

@Composable
fun PackForgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
