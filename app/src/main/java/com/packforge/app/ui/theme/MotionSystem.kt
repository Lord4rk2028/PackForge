package com.packforge.app.ui.theme

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * Niveles de expresividad de movimiento según el dispositivo y las preferencias
 * del usuario. PackForge soporta Android 8 (API 26) en adelante, así que el
 * movimiento se degrada de forma explícita en lugar de romper o ir a tirones.
 */
enum class MotionLevel {
    /** API 31+ en equipo capaz → rebote elástico completo (comportamiento actual). */
    FULL,

    /** API 29-30 → spring suave, sin overshoot. */
    MODERATE,

    /** API 26-28, gama baja o ahorro de batería → scale sutil y sin rebote. */
    MINIMAL,

    /** "Reducir Movimiento" o accesibilidad del sistema → sin animación alguna. */
    NONE
}

/**
 * MotionLevel vigente para toda la app.
 *
 * Lo provee [PackForgeTheme] y lo leen componentes como `Modifier.bounceClick`,
 * de modo que las ~50 pantallas y tarjetas existentes se adaptan solas, sin
 * tener que comprobar nada ni cambiar su código.
 */
val LocalMotionLevel = staticCompositionLocalOf { MotionLevel.FULL }

/** Atajo para leer el nivel vigente desde cualquier bloque @Composable. */
val currentMotionLevel: MotionLevel
    @Composable get() = LocalMotionLevel.current

/**
 * Resuelve el nivel de movimiento respetando, en este orden de prioridad:
 *
 * 1. El interruptor "Reducir Movimiento" de Ajustes de Tema (manda sobre todo).
 * 2. Accesibilidad del sistema ("Quitar animaciones"): ajuste global obligatorio.
 * 3. Ahorro de batería: la fluidez pasa a segundo plano.
 * 4. Memoria del dispositivo (< 2 GB = gama baja, el rebote genera jank).
 * 5. Versión de Android.
 */
@Composable
fun rememberMotionLevel(userReduceMotion: Boolean = false): MotionLevel {
    val context = LocalContext.current
    return remember(userReduceMotion) { calculateMotionLevel(context, userReduceMotion) }
}

private fun calculateMotionLevel(context: Context, userReduceMotion: Boolean): MotionLevel {
    // 1. Preferencia explícita del usuario.
    if (userReduceMotion) return MotionLevel.NONE

    // 2. Accesibilidad del sistema (Android 9+ expone el ajuste de animaciones).
    if (isAccessibilityAnimationReduced(context)) return MotionLevel.NONE

    // 3. Ahorro de batería.
    if (isBatterySaverEnabled(context)) return MotionLevel.MINIMAL

    // 4. Gama baja: se conserva el gesto, pero sin física elástica.
    if (isLowEndDevice(context)) return MotionLevel.MINIMAL

    // 5. Versión de Android.
    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> MotionLevel.FULL     // Android 12+
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> MotionLevel.MODERATE // Android 10-11
        else -> MotionLevel.MINIMAL                                             // Android 8-9
    }
}

/**
 * Android 9 (API 28) introdujo el ajuste global de escala de animaciones, que
 * muchos usuarios activan desde Accesibilidad. Si está a 0, la app no debe
 * animar nada por mucho que el usuario no haya tocado nuestro interruptor.
 */
private fun isAccessibilityAnimationReduced(context: Context): Boolean = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f
    } else {
        // Android 8 no ofrece este ajuste: se asume movimiento normal.
        false
    }
} catch (_: Exception) {
    false
}

/** ¿Está activo el ahorro de batería del sistema? */
private fun isBatterySaverEnabled(context: Context): Boolean = try {
    (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode ?: false
} catch (_: Exception) {
    false
}

/**
 * ¿Dispositivo de gama baja? Se usa el umbral conservador de 2 GB de RAM: por
 * debajo, las animaciones elásticas producen tirones visibles.
 */
private fun isLowEndDevice(context: Context): Boolean = try {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    if (activityManager == null) {
        false
    } else {
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        memoryInfo.totalMem < TWO_GB
    }
} catch (_: Exception) {
    false
}

private const val TWO_GB = 2L * 1024L * 1024L * 1024L

/** Descripción corta del nivel activo, para Ajustes y diagnóstico. */
fun MotionLevel.describe(): String = when (this) {
    MotionLevel.FULL -> "Animaciones completas con rebote"
    MotionLevel.MODERATE -> "Animaciones suaves sin rebote"
    MotionLevel.MINIMAL -> "Animaciones mínimas (dispositivo o sistema)"
    MotionLevel.NONE -> "Animaciones desactivadas"
}

/**
 * Duraciones base en ms según el nivel activo.
 *
 * Con [MotionLevel.NONE] todas valen 0: `tween(0)` resuelve en el siguiente
 * fotograma, así que el estado final se aplica al instante sin animarlo.
 */
object MotionTimings {
    fun shortDuration(motionLevel: MotionLevel): Int = when (motionLevel) {
        MotionLevel.FULL -> 200
        MotionLevel.MODERATE -> 150
        MotionLevel.MINIMAL -> 100
        MotionLevel.NONE -> 0
    }

    fun mediumDuration(motionLevel: MotionLevel): Int = when (motionLevel) {
        MotionLevel.FULL -> 300
        MotionLevel.MODERATE -> 200
        MotionLevel.MINIMAL -> 150
        MotionLevel.NONE -> 0
    }

    fun longDuration(motionLevel: MotionLevel): Int = when (motionLevel) {
        MotionLevel.FULL -> 400
        MotionLevel.MODERATE -> 250
        MotionLevel.MINIMAL -> 200
        MotionLevel.NONE -> 0
    }

    fun extraLongDuration(motionLevel: MotionLevel): Int = when (motionLevel) {
        MotionLevel.FULL -> 500
        MotionLevel.MODERATE -> 300
        MotionLevel.MINIMAL -> 200
        MotionLevel.NONE -> 0
    }
}
