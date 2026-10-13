package com.packforge.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.packforge.app.data.ThemePreferences
import com.packforge.app.ui.theme.ColorSchemeBuilder.buildColorScheme
import com.packforge.app.ui.theme.Spacing

/**
 * Tema raíz de PackForge.
 *
 * Además de colores, tipografía y formas, resuelve el [MotionLevel] activo y lo
 * publica mediante [LocalMotionLevel]. Cualquier componente composed —como
 * `Modifier.bounceClick`— lo lee de ahí y se adapta sin que sus pantallas
 * tengan que cambiar.
 *
 * La paleta NO se toca en el rediseño: `accentHex`, `darkMode`, `amoledMode` y
 * `vividColors` siguen produciendo exactamente el mismo colorScheme.
 */
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

    // "Reducir Movimiento" del usuario + detección automática del dispositivo.
    val motionLevel = rememberMotionLevel(userReduceMotion = prefs.reduceMotion)

    CompositionLocalProvider(LocalMotionLevel provides motionLevel) {
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
}