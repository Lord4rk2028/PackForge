package com.packforge.app.data

import androidx.compose.ui.graphics.Color

data class ThemePreferences(
    val darkMode: Boolean = true,
    val accentColor: AccentColor = AccentColor.EMERALD,
    val expressiveMotion: Boolean = true
)

enum class AccentColor(val hex: Long) {
    EMERALD(0xFF2ECC71),   // Verde esmeralda (default, estilo Minecraft)
    FURNACE(0xFFFF6B35),   // Naranja horno
    DIAMOND(0xFF4FC3F7),   // Azul diamante
    REDSTONE(0xFFEF5350),  // Rojo redstone
    AMETHYST(0xFFAB47BC)   // Púrpura amatista
}

fun AccentColor.toColor(): Color = Color(hex)
