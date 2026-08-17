package com.packforge.app.data

data class ThemePreferences(
    val darkMode: Boolean = true,
    val amoledMode: Boolean = false,      // ⭐ NUEVO: negro puro
    val accentHex: String = "#2ECC71",    // ⭐ NUEVO: cualquier color HEX
    val vividColors: Boolean = true,      // ⭐ NUEVO: saturación boost
    val expressiveMotion: Boolean = true,
    val verboseFileLogs: Boolean = false
)
