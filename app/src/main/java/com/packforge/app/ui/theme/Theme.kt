package com.packforge.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Definiciones de colores base si es necesario, 
// aunque el tema ahora es dinámico según las preferencias.

val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF2ECC71),
    onPrimary = Color(0xFF0A1A10),
    primaryContainer = Color(0xFF1A3D2A),
    onPrimaryContainer = Color(0xFF8FD6A8),
    secondary = Color(0xFF4FC3F7),
    onSecondary = Color(0xFF0A1A10),
    secondaryContainer = Color(0xFF1A3D3A),
    onSecondaryContainer = Color(0xFF8FD6A8),
    tertiary = Color(0xFFEF5350),
    onTertiary = Color(0xFF0A1A10),
    error = Color(0xFFF44336),
    onError = Color(0xFFFAFAFA),
    errorContainer = Color(0xFF450A0A),
    onErrorContainer = Color(0xFFFF6B35),
    background = Color(0xFF0A0F0C),
    onBackground = Color(0xFFE2E8E4),
    surface = Color(0xFF0D1410),
    onSurface = Color(0xFFE2E8E4),
    surfaceVariant = Color(0xFF1C2B22),
    onSurfaceVariant = Color(0xFF8FD6A8),
    outline = Color(0xFF4CAF50),
    outlineVariant = Color(0xFF2ECC71),
    surfaceContainer = Color(0xFF15201A),
    surfaceContainerHigh = Color(0xFF1C2B22),
    surfaceContainerHighest = Color(0xFF2A3D33)
)

val LightColorScheme = lightColorScheme(
    primary = Color(0xFF2ECC71),
    onPrimary = Color(0xFFFAFAFA),
    primaryContainer = Color(0xFFD4F5DD),
    onPrimaryContainer = Color(0xFF062E1A),
    secondary = Color(0xFF38BDF8),
    onSecondary = Color(0xFFFAFAFA),
    secondaryContainer = Color(0xFFD4F5DD),
    onSecondaryContainer = Color(0xFF062E1A),
    tertiary = Color(0xFFEF5350),
    onTertiary = Color(0xFFFAFAFA),
    error = Color(0xFFF44336),
    onError = Color(0xFFFAFAFA),
    errorContainer = Color(0xFFFCE4EC),
    onErrorContainer = Color(0xFF880E4F),
    background = Color(0xFFEAF2ED),
    onBackground = Color(0xFF0D1410),
    surface = Color(0xFFF4F8F5),
    onSurface = Color(0xFF0D1410),
    surfaceVariant = Color(0xFFD4F5DD),
    onSurfaceVariant = Color(0xFF0D1410),
    outline = Color(0xFF2ECC71),
    outlineVariant = Color(0xFF8FD6A8),
    surfaceContainer = Color(0xFFE8F5ED),
    surfaceContainerHigh = Color(0xFFD4F5DD),
    surfaceContainerHighest = Color(0xFFC4EED8)
)
