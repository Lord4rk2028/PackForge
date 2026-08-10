package com.packforge.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.packforge.app.data.ThemePreferences

@Composable
fun PackForgeTheme(
    prefs: ThemePreferences,
    content: @Composable () -> Unit
) {
    val accent = Color(prefs.accentColor.hex)

    val colorScheme = if (prefs.darkMode) {
        darkColorScheme(
            primary = accent,
            onPrimary = Color(0xFF0A1A10),
            primaryContainer = accent.copy(alpha = 0.25f),
            secondary = Color(0xFF8FD6A8),
            surface = Color(0xFF0D1410),        // Negro obsidiana
            surfaceContainer = Color(0xFF15201A),
            surfaceContainerHigh = Color(0xFF1C2B22),
            background = Color(0xFF0A0F0C),
            onSurface = Color(0xFFE2E8E4),
            error = Color(0xFFF44336)
        )
    } else {
        lightColorScheme(
            primary = accent.copy(blue = accent.blue * 0.8f),
            surface = Color(0xFFF4F8F5),
            background = Color(0xFFEAF2ED)
        )
    }

    // MATERIAL 3 EXPRESSIVE: material3 1.4.0 ya usa animaciones con springs
    // (motion expresivo) por defecto en todos sus componentes. La API pública
    // de MotionScheme/LocalMotionScheme no está disponible en la versión estable
    // 1.4.0 (es API interna), así que usamos el tema con las formas redondeadas
    // expresivas y el color/accento dinámico.
    MaterialTheme(
        colorScheme = colorScheme,
        typography = PackForgeTypography,
        shapes = Shapes(
            // Formas expresivas redondeadas
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(32.dp)
        ),
        content = content
    )
}