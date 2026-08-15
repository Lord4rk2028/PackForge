package com.packforge.app.ui.theme

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.google.android.material.color.MaterialColors
import com.google.android.material.color.utilities.Scheme
import com.packforge.app.data.ThemePreferences

fun buildColorScheme(
    seedHex: String,
    dark: Boolean,
    amoled: Boolean,
    vivid: Boolean
): ColorScheme {
    val seedColor = try {
        Color(android.graphics.Color.parseColor(seedHex))
    } catch (e: Exception) {
        Color(0xFF2ECC71) // Default
    }

    val scheme = if (dark) Scheme.dark(seedColor.toArgb()) else Scheme.light(seedColor.toArgb())

    fun Color.vivid(): Color {
        if (!vivid) return this
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(this.toArgb(), hsv)
        hsv[1] = (hsv[1] * 1.35f).coerceAtMost(1f)
        return Color(android.graphics.Color.HSVToColor(hsv))
    }

    val base = ColorScheme(
        primary = Color(scheme.primary).vivid(),
        onPrimary = Color(scheme.onPrimary),
        primaryContainer = Color(scheme.primaryContainer).vivid(),
        onPrimaryContainer = Color(scheme.onPrimaryContainer),
        secondary = Color(scheme.secondary).vivid(),
        onSecondary = Color(scheme.onSecondary),
        secondaryContainer = Color(scheme.secondaryContainer).vivid(),
        onSecondaryContainer = Color(scheme.onSecondaryContainer),
        tertiary = Color(scheme.tertiary).vivid(),
        onTertiary = Color(scheme.onTertiary),
        error = Color(scheme.error),
        onError = Color(scheme.onError),
        background = Color(scheme.background),
        onBackground = Color(scheme.onBackground),
        surface = Color(scheme.surface),
        onSurface = Color(scheme.onSurface),
        surfaceVariant = Color(scheme.surfaceVariant),
        onSurfaceVariant = Color(scheme.onSurfaceVariant),
        outline = Color(scheme.outline),
        outlineVariant = Color(scheme.outlineVariant),
        inverseSurface = Color(scheme.inverseSurface),
        inverseOnSurface = Color(scheme.inverseOnSurface),
        inversePrimary = Color(scheme.inversePrimary),
        surfaceTint = Color(scheme.primary)
    )

    return if (dark && amoled) {
        base.copy(
            background = Color(0xFF000000),
            surface = Color(0xFF000000),
            surfaceContainerLowest = Color(0xFF000000),
            surfaceContainerLow = Color(0xFF050505),
            surfaceContainer = Color(0xFF0A0A0A),
            surfaceContainerHigh = Color(0xFF111111),
            surfaceContainerHighest = Color(0xFF161616),
            inverseSurface = Color(0xFFE2E2E2)
        )
    } else base
}

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
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(32.dp)
        ),
        content = content
    )
}
