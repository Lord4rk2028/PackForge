package com.packforge.app.util

import android.util.Log

object PackForgeConfig {
    // APAGADO por defecto = exportación rápida
    @Volatile var verboseFileLogs: Boolean = false
}

// Helper que NO construye el string si está apagado (cero costo)
inline fun logFile(msg: () -> String) {
    if (PackForgeConfig.verboseFileLogs) Log.d("PackForge_Debug", msg())
}
