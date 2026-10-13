package com.packforge.app.data

data class ThemePreferences(
    val darkMode: Boolean = true,
    val amoledMode: Boolean = false,        // ⭐ Negro puro para OLED y ahorro batería
    val accentHex: String = "#2ECC71",      // ⭐ Color personalizado HEX
    val vividColors: Boolean = true,        // ⭐ Saturación boost
    val reduceMotion: Boolean = false,      // ⭐ Reducir animaciones (accesibilidad)
    val verboseFileLogs: Boolean = false
)
