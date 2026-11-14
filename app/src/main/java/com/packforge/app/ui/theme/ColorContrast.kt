package com.packforge.app.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.pow

/**
 * Utilidades de contraste según WCAG 2.1.
 *
 * Se centralizan aquí (paquete de tema) para que tanto el generador de paletas
 * como los componentes de UI usen EXACTAMENTE la misma fórmula. La versión
 * anterior usaba una luminancia simplificada (0.299R + 0.587G + 0.114B) sin
 * linealizar los canales sRGB, lo que sobreestimaba el contraste real —sobre
 * todo en verdes— y dejaba pasar combinaciones ilegibles.
 */
object ColorContrast {

    /** Umbral WCAG AA para texto normal. */
    const val AA_NORMAL = 4.5

    /** Umbral WCAG AA para texto grande (≥18pt, o ≥14pt en negrita) y componentes de UI. */
    const val AA_LARGE = 3.0

    /** Umbral WCAG AAA para texto normal. */
    const val AAA_NORMAL = 7.0

    /**
     * Luminancia relativa WCAG: linealiza cada canal sRGB por tramos y pondera
     * con los coeficientes CIE.
     */
    fun relativeLuminance(color: Color): Double {
        fun channel(v: Float): Double {
            val c = v.toDouble()
            return if (c <= 0.04045) c / 12.92
            else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) +
            0.7152 * channel(color.green) +
            0.0722 * channel(color.blue)
    }

    /** Relación de contraste entre dos colores (1.0 .. 21.0). */
    fun ratio(a: Color, b: Color): Double {
        val l1 = relativeLuminance(a)
        val l2 = relativeLuminance(b)
        val lighter = max(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /** ¿Cumplen [foreground] y [background] el umbral indicado? */
    fun passes(
        foreground: Color,
        background: Color,
        minRatio: Double = AA_NORMAL
    ): Boolean = ratio(foreground, background) >= minRatio

    /**
     * Devuelve negro o blanco —el que dé MÁS contraste— para usar encima de
     * [background].
     *
     * Es la corrección clave del generador de paletas: antes `onPrimary` se
     * decidía con la luminosidad del color SEMILLA y no con la del color
     * resultante, así que un verde brillante en modo oscuro acababa con texto
     * blanco a 1.5:1 (ilegible).
     */
    fun bestOn(background: Color): Color {
        val whiteRatio = ratio(Color.White, background)
        val blackRatio = ratio(Color.Black, background)
        return if (blackRatio >= whiteRatio) Color.Black else Color.White
    }
}
