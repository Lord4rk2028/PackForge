package com.packforge.app.ui.theme

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Formas Material 3 Expressive.
 *
 * Expressive sube la escala de formas respecto al baseline (12/16/24) y admite
 * esquinas asimétricas. Se mantienen valores moderados —24dp en tarjetas de
 * lista— para que las tarjetas compactas de la biblioteca no pierdan superficie
 * útil, reservando 28dp para superficies grandes (diálogos y hojas).
 */
object ExpressiveShapes {
    /** Icono contenedor dentro de una tarjeta (baseline: 14dp). */
    val iconContainer = RoundedCornerShape(18.dp)

    /** Cápsula de estadística. */
    val statChip = RoundedCornerShape(20.dp)

    /** Tarjeta estándar de lista. */
    val card = RoundedCornerShape(24.dp)

    /** Tarjeta protagonista (hub del Studio). */
    val heroCard = RoundedCornerShape(
        topStart = 28.dp,
        topEnd = 28.dp,
        bottomStart = 20.dp,
        bottomEnd = 20.dp
    )

    /** Portada cuadrada de un modpack. */
    val cover = RoundedCornerShape(20.dp)

    /** Acción directa dentro de una tarjeta. */
    val action = RoundedCornerShape(14.dp)

    /** Diálogo: la superficie más redondeada del sistema. */
    val dialog = RoundedCornerShape(28.dp)

    /** Bloque informativo o contenedor secundario. */
    val container = RoundedCornerShape(24.dp)
}

/**
 * Elevaciones Expressive adaptadas por tipo de superficie.
 *
 * Los valores son deliberadamente bajos: Expressive gana expresividad con la
 * forma y el color, no apilando sombras. Además, una sombra alta sobre fondos
 * AMOLED puros se ve sucia.
 */
object ExpressiveElevation {
    /** Tarjeta en reposo. */
    val card = 2.dp

    /** Tarjeta pulsada: baja respecto al reposo para dar sensación de hundimiento. */
    val cardPressed = 1.dp

    /** Superficie elevada (tarjeta protagonista). */
    val raised = 4.dp

    /** Diálogo. */
    val dialog = 12.dp

    /** Barra superior. */
    val bar = 3.dp

    /** FAB en reposo. */
    val fab = 6.dp
}

/**
 * Espaciado Expressive para superficies. Complementa a [Spacing] sin alterar
 * los valores que ya usan las demás pantallas.
 */
object ExpressiveSpacing {
    /** Padding interno de una tarjeta de contenido denso. */
    val cardContent = 14.dp

    /** Padding interno de una tarjeta protagonista. */
    val heroContent = 18.dp

    /** Separación vertical entre tarjetas de una lista. */
    val listGap = 14.dp

    /** Margen lateral de las listas. */
    val screenMargin = 16.dp
}

/**
 * Ancho mínimo de columna para la biblioteca responsiva.
 *
 * Con `GridCells.Adaptive` este valor decide cuántas tarjetas caben: una en
 * móvil (≈360-430dp de ancho), dos o más en tablets y plegables desplegados,
 * sin consultar el tipo de dispositivo ni escribir ramas por tamaño.
 */
val ModpackCardMinWidth: Dp = 340.dp

/**
 * Hápticos Expressive.
 *
 * Compose solo expone `LongPress` y `TextHandleMove` en `HapticFeedbackType`,
 * así que se usa `View.performHapticFeedback` para acceder a la escala completa
 * de Android (`CLOCK_TICK`, `CONTEXT_CLICK`, ...), disponible desde API 21.
 *
 * No depende del [MotionLevel]: "Reducir Movimiento" gobierna animaciones, no
 * vibración. Un usuario que reduce movimiento puede seguir queriendo respuesta
 * táctil, y quien la desactiva lo hace desde Ajustes del sistema, que
 * `performHapticFeedback` respeta automáticamente.
 */
@Immutable
class ExpressiveHaptics internal constructor(private val view: View?) {

    /** Toque ligero: navegación, selección, pulsación de tarjeta. */
    fun light() = perform(HapticFeedbackConstants.CLOCK_TICK)

    /** Toque medio: acción confirmada (compartir, guardar). */
    fun medium() = perform(HapticFeedbackConstants.CONTEXT_CLICK)

    /** Toque fuerte: acción con peso (confirmación importante). */
    fun heavy() = perform(HapticFeedbackConstants.LONG_PRESS)

    /** Rechazo: acción destructiva (borrar). REJECT existe desde Android 11. */
    fun reject() = perform(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.REJECT
        } else {
            HapticFeedbackConstants.LONG_PRESS
        }
    )

    private fun perform(constant: Int) {
        try {
            // No-op silencioso si el usuario desactivó la vibración táctil.
            view?.performHapticFeedback(constant)
        } catch (_: Throwable) {
            // Algunos fabricantes lanzan si el servicio de vibración no está listo.
        }
    }
}

/** Hápticos Expressive del árbol de composición actual. */
@Composable
fun rememberExpressiveHaptics(): ExpressiveHaptics {
    val view = LocalView.current
    return remember(view) { ExpressiveHaptics(view) }
}