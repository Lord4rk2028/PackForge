package com.packforge.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.packforge.app.data.ThemePreferences

object ColorSchemeBuilder {

    fun buildColorScheme(
        seedHex: String,
        dark: Boolean,
        amoled: Boolean,
        vivid: Boolean
    ): ColorScheme {
        val seed = Color(android.graphics.Color.parseColor(seedHex))
        val hsl = colorToHsl(seed)
        var h = hsl.h
        var s = hsl.s
        var l = hsl.l

        if (vivid) {
            s = (s * 1.35f).coerceAtMost(1f)
        }

        val primary = hslToColor(h, s, l)
        val primaryContainer = hslToColor(h, s * 0.75f, l * 0.75f)
        val onPrimary = if (l > 0.55f) Color(0xFF000000) else Color(0xFFFFFFFF)
        val onPrimaryContainer = onPrimary

        val secondaryH = (h + 35f) % 360f
        val secondary = hslToColor(secondaryH, s * 0.55f, l * 0.9f)
        val secondaryContainer = hslToColor(secondaryH, s * 0.4f, l * 0.75f)
        val onSecondary = if (l > 0.55f) Color(0xFF000000) else Color(0xFFFFFFFF)
        val onSecondaryContainer = onSecondary

        val tertiaryH = (h + 150f) % 360f
        val tertiary = hslToColor(tertiaryH, s * 0.65f, l * 0.85f)
        val tertiaryContainer = hslToColor(tertiaryH, s * 0.45f, l * 0.7f)
        val onTertiary = if (l > 0.55f) Color(0xFF000000) else Color(0xFFFFFFFF)
        val onTertiaryContainer = onTertiary

        val error = Color(0xFFB3261E)
        val onError = Color(0xFFFFFFFF)
        val errorContainer = Color(0xFFF9DEDC)
        val onErrorContainer = Color(0xFF410E0B)

        val bgL = if (dark) 0.045f else 0.96f
        val surfaceL = if (dark) 0.07f else 0.94f
        val surfaceVariantL = if (dark) 0.11f else 0.90f
        val neutralS = 0.03f

        val background = hslToColor(h, neutralS, bgL)
        val onBackground = hslToColor(h, neutralS, if (dark) 0.88f else 0.12f)
        val surface = hslToColor(h, neutralS, surfaceL)
        val onSurface = hslToColor(h, neutralS, if (dark) 0.88f else 0.12f)
        val surfaceVariant = hslToColor(h, neutralS, surfaceVariantL)
        val onSurfaceVariant = hslToColor(h, neutralS, if (dark) 0.72f else 0.32f)
        val outline = hslToColor(h, neutralS, if (dark) 0.50f else 0.40f)
        val outlineVariant = hslToColor(h, neutralS, if (dark) 0.28f else 0.75f)

        val inverseSurface = hslToColor(h, neutralS, if (dark) 0.90f else 0.10f)
        val inverseOnSurface = hslToColor(h, neutralS, if (dark) 0.12f else 0.88f)
        val inversePrimary = hslToColor(h, s, if (dark) 0.85f else 0.15f)
        val surfaceTint = primary

        val surfaceContainerLowest = hslToColor(h, neutralS, if (dark) 0.02f else 0.98f)
        val surfaceContainerLow = hslToColor(h, neutralS, if (dark) 0.05f else 0.96f)
        val surfaceContainer = hslToColor(h, neutralS, if (dark) 0.08f else 0.93f)
        val surfaceContainerHigh = hslToColor(h, neutralS, if (dark) 0.11f else 0.88f)
        val surfaceContainerHighest = hslToColor(h, neutralS, if (dark) 0.14f else 0.82f)
        val surfaceBright = hslToColor(h, neutralS, if (dark) 0.12f else 1.0f)
        val surfaceDim = hslToColor(h, neutralS, if (dark) 0.0f else 0.92f)

        val primaryFixed = primaryContainer
        val primaryFixedDim = hslToColor(h, s * 0.75f, l * 0.65f)
        val onPrimaryFixed = onPrimaryContainer
        val onPrimaryFixedVariant = onPrimary

        val secondaryFixed = secondaryContainer
        val secondaryFixedDim = hslToColor(secondaryH, s * 0.4f, l * 0.65f)
        val onSecondaryFixed = onSecondaryContainer
        val onSecondaryFixedVariant = onSecondary

        val tertiaryFixed = tertiaryContainer
        val tertiaryFixedDim = hslToColor(tertiaryH, s * 0.45f, l * 0.6f)
        val onTertiaryFixed = onTertiaryContainer
        val onTertiaryFixedVariant = onTertiary

        val base = ColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            inversePrimary = inversePrimary,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            surfaceTint = surfaceTint,
            inverseSurface = inverseSurface,
            inverseOnSurface = inverseOnSurface,
            error = error,
            onError = onError,
            errorContainer = errorContainer,
            onErrorContainer = onErrorContainer,
            outline = outline,
            outlineVariant = outlineVariant,
            scrim = Color(0xFF000000),
            surfaceBright = surfaceBright,
            surfaceDim = surfaceDim,
            surfaceContainer = surfaceContainer,
            surfaceContainerHigh = surfaceContainerHigh,
            surfaceContainerHighest = surfaceContainerHighest,
            surfaceContainerLow = surfaceContainerLow,
            surfaceContainerLowest = surfaceContainerLowest,
            primaryFixed = primaryFixed,
            primaryFixedDim = primaryFixedDim,
            onPrimaryFixed = onPrimaryFixed,
            onPrimaryFixedVariant = onPrimaryFixedVariant,
            secondaryFixed = secondaryFixed,
            secondaryFixedDim = secondaryFixedDim,
            onSecondaryFixed = onSecondaryFixed,
            onSecondaryFixedVariant = onSecondaryFixedVariant,
            tertiaryFixed = tertiaryFixed,
            tertiaryFixedDim = tertiaryFixedDim,
            onTertiaryFixed = onTertiaryFixed,
            onTertiaryFixedVariant = onTertiaryFixedVariant
        )

        return if (dark && amoled) {
            base.copy(
                background = Color(0xFF000000),
                surface = Color(0xFF000000),
                surfaceContainerLowest = Color(0xFF000000),
                surfaceContainerLow = Color(0xFF020202),
                surfaceContainer = Color(0xFF060606),
                surfaceContainerHigh = Color(0xFF0C0C0C),
                surfaceContainerHighest = Color(0xFF111111),
                surfaceVariant = Color(0xFF141414),
                onSurfaceVariant = Color(0xFFAAAAAA),
                inverseSurface = Color(0xFFE2E2E2),
                surfaceBright = Color(0xFF000000),
                surfaceDim = Color(0xFF000000)
            )
        } else base
    }

    private data class HSL(val h: Float, val s: Float, val l: Float)

    private fun colorToHsl(color: Color): HSL {
        val r = color.red
        val g = color.green
        val b = color.blue
        val max = maxOf(r, maxOf(g, b))
        val min = minOf(r, minOf(g, b))
        val l = (max + min) / 2f
        if (max == min) return HSL(0f, 0f, l)
        val d = max - min
        val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
        val h = when (max) {
            r -> ((g - b) / d + if (g < b) 6f else 0f) / 6f
            g -> ((b - r) / d + 2f) / 6f
            else -> ((r - g) / d + 4f) / 6f
        }
        return HSL(h * 360f, s, l)
    }

    private fun hslToColor(h: Float, s: Float, l: Float): Color {
        val v = l + s * minOf(l, 1f - l)
        val newS = if (v == 0f) 0f else 2f * (1f - l / v)
        val argb = android.graphics.Color.HSVToColor(floatArrayOf(h, newS.coerceIn(0f, 1f), v.coerceIn(0f, 1f)))
        return Color(argb)
    }

    private fun minOf(a: Float, b: Float): Float = if (a < b) a else b
}
