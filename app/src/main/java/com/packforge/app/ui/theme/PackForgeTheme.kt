package com.packforge.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.packforge.app.data.ThemePreferences
import com.packforge.app.ui.theme.ColorSchemeBuilder.buildColorScheme
import com.packforge.app.ui.theme.Spacing

@Composable
fun PackForgeTheme(
    prefs: ThemePreferences,
    content: @Composable () -> Unit
) {
    val colorScheme = buildColorScheme(
        seedHex = prefs.accentHex,
        dark = prefs.darkMode,
        amoled = prefs.amoledMode,
        vivid = prefs.vividColors
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = PackForgeTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(Spacing.xSmall),
            small = RoundedCornerShape(Spacing.small),
            medium = RoundedCornerShape(Spacing.medium),
            large = RoundedCornerShape(Spacing.large),
            extraLarge = RoundedCornerShape(Spacing.xLarge)
        ),
        content = content
    )
}