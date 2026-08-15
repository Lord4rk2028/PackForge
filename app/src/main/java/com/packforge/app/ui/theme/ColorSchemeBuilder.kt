package com.packforge.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.material3.ColorScheme
import com.google.material.color.scheme.Scheme
import com.google.material.color.utilities.Hct

/**
 * Genera un ColorScheme Material 3 completo desde un color semilla HEX.
 * Incluye soporte para:
 * - Modo AMOLED (negro puro #000000)
 * - Colores vivos (boost de saturación +35%)
 * - Modo claro/oscuro
 */
object ColorSchemeBuilder {

    /**
     * Construye el ColorScheme completo desde el color semilla.
     */
    fun buildColorScheme(
        seedHex: String,
        dark: Boolean,
        amoled: Boolean,
        vivid: Boolean
    ): ColorScheme {
        val seed = Color(android.graphics.Color.parseColor(seedHex))
        val argb = seed.toArgb()
        
        // Generar esquema Material 3 desde el color semilla
        val scheme = if (dark) Scheme.dark(argb) else Scheme.light(argb)
        
        // Función para aplicar boost de viveza (saturación +35%)
        fun applyVivid(argbColor: Long): Long {
            if (!vivid) return argbColor
            val hct = Hct.fromInt(argbColor)
            // Boost de croma (saturación perceptual) en +35%
            val boostedChroma = (hct.chroma * 1.35f).coerceAtMost(200f) // cap razonable
            return Hct.from(hct.hue, boostedChroma, hct.tone).toInt()
        }
        
        val base = ColorScheme(
            primary = Color(applyVivid(scheme.primary)),
            onPrimary = Color(scheme.onPrimary),
            primaryContainer = Color(applyVivid(scheme.primaryContainer)),
            onPrimaryContainer = Color(scheme.onPrimaryContainer),
            secondary = Color(applyVivid(scheme.secondary)),
            onSecondary = Color(scheme.onSecondary),
            secondaryContainer = Color(applyVivid(scheme.secondaryContainer)),
            onSecondaryContainer = Color(scheme.onSecondaryContainer),
            tertiary = Color(applyVivid(scheme.tertiary)),
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
            surfaceTint = Color(applyVivid(scheme.primary)),
            surfaceContainerLowest = Color(scheme.surfaceContainerLowest),
            surfaceContainerLow = Color(scheme.surfaceContainerLow),
            surfaceContainer = Color(scheme.surfaceContainer),
            surfaceContainerHigh = Color(scheme.surfaceContainerHigh),
            surfaceContainerHighest = Color(scheme.surfaceContainerHighest)
        )
        
        // ⭐ MODO AMOLED: negro PURO en fondos si está activo y es modo oscuro
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
}