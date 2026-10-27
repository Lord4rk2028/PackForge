package com.packforge.app.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.unit.IntOffset
import com.packforge.app.ui.theme.MotionLevel

/**
 * Sistema de transiciones de pantalla optimizado y accesible.
 *
 * Principios:
 * 1. Respeto total a "Reducir movimiento" (MotionLevel.MINIMAL / NONE):
 *    Cuando el usuario activa "Reducir movimiento" en la app o sistema,
 *    NO se ejecutan desplazamientos laterales ni verticales. Se realiza
 *    un Crossfade suave (fadeIn + fadeOut) o cambio instantáneo (NONE),
 *    evitando Mareo por movimiento, superposiciones extrañas y saltos de offset.
 *
 * 2. Transiciones estilo Material 3 Shared Axis (MotionLevel.FULL / MODERATE):
 *    - Avance (SLIDE_FORWARD): La nueva pantalla entra desde la derecha (+100% offscreen)
 *      mientras la anterior se desplaza ligeramente a la izquierda (-30%) con atenuación.
 *    - Retroceso (SLIDE_BACKWARD): La nueva pantalla entra desde la izquierda (-100% offscreen)
 *      mientras la anterior se desplaza a la derecha (+30%) con atenuación.
 *    - Al entrar desde +100%/-100%, la pantalla que entra nunca se dibuja superpuesta
 *      sobre la actual en el centro de la vista.
 *
 * 3. Modales (MODAL_OPEN / MODAL_CLOSE):
 *    Entrada limpia desde abajo (+100% vertical offscreen) y salida hacia abajo (+100%).
 */
object TransitionSpecs {

    /** Curva estándar de movimiento suave para Compose (Material 3). */
    private val MotionCurve = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

    /** Duración por MotionLevel. */
    fun screenTransitionDuration(motionLevel: MotionLevel): Int = when (motionLevel) {
        MotionLevel.FULL -> 220
        MotionLevel.MODERATE -> 180
        MotionLevel.MINIMAL -> 140
        MotionLevel.NONE -> 0
    }

    fun slideSpec(motionLevel: MotionLevel): TweenSpec<IntOffset> = tween(
        durationMillis = screenTransitionDuration(motionLevel),
        easing = MotionCurve
    )

    fun fadeSpec(motionLevel: MotionLevel): TweenSpec<Float> = tween(
        durationMillis = screenTransitionDuration(motionLevel),
        easing = MotionCurve
    )
}

/** Tipos de transición según el contexto de navegación. */
enum class TransitionType {
    /** Hacia adelante según el orden de las pestañas (entra por la derecha). */
    SLIDE_FORWARD,

    /** Hacia atrás según el orden de las pestañas (entra por la izquierda). */
    SLIDE_BACKWARD,

    /** Abrir el modal de conflictos (sube desde abajo). */
    MODAL_OPEN,

    /** Cerrar el modal de conflictos. */
    MODAL_CLOSE,

    /** Crossfade entre destinos sin relación espacial conocida. */
    FADE
}

/** Orden físico de las pestañas en la barra inferior (izquierda → derecha). */
private fun orderOf(route: String?): Int? = when (route) {
    Screen.Import.route -> 0
    Screen.Conflicts.route -> 1
    Screen.Export.route -> 2
    Screen.Studio.route -> 3
    else -> null
}

/**
 * Calcula el tipo de transición de [initialRoute] a [targetRoute].
 */
fun getTransitionType(initialRoute: String?, targetRoute: String?): TransitionType {
    if (initialRoute == null || targetRoute == null) return TransitionType.FADE

    if (targetRoute == Screen.Conflicts.route) return TransitionType.MODAL_OPEN
    if (initialRoute == Screen.Conflicts.route) return TransitionType.MODAL_CLOSE

    val from = orderOf(initialRoute)
    val to = orderOf(targetRoute)
    if (from == null || to == null) return TransitionType.FADE

    return when {
        to > from -> TransitionType.SLIDE_FORWARD
        to < from -> TransitionType.SLIDE_BACKWARD
        else -> TransitionType.FADE
    }
}

/**
 * Transición de entrada.
 */
fun createEnterTransition(
    transitionType: TransitionType,
    motionLevel: MotionLevel
): EnterTransition {
    if (motionLevel == MotionLevel.NONE) return EnterTransition.None

    // Con "Reducir movimiento" (MINIMAL), usar solo Fade (sin desplazamientos)
    if (motionLevel == MotionLevel.MINIMAL || transitionType == TransitionType.FADE) {
        return fadeIn(TransitionSpecs.fadeSpec(motionLevel))
    }

    return when (transitionType) {
        TransitionType.SLIDE_FORWARD -> slideInHorizontally(
            animationSpec = TransitionSpecs.slideSpec(motionLevel),
            initialOffsetX = { fullWidth -> fullWidth }
        ) + fadeIn(TransitionSpecs.fadeSpec(motionLevel), initialAlpha = 0.2f)

        TransitionType.SLIDE_BACKWARD -> slideInHorizontally(
            animationSpec = TransitionSpecs.slideSpec(motionLevel),
            initialOffsetX = { fullWidth -> -fullWidth }
        ) + fadeIn(TransitionSpecs.fadeSpec(motionLevel), initialAlpha = 0.2f)

        TransitionType.MODAL_OPEN -> slideInVertically(
            animationSpec = TransitionSpecs.slideSpec(motionLevel),
            initialOffsetY = { fullHeight -> fullHeight }
        ) + fadeIn(TransitionSpecs.fadeSpec(motionLevel), initialAlpha = 0.2f)

        TransitionType.MODAL_CLOSE -> fadeIn(TransitionSpecs.fadeSpec(motionLevel))

        TransitionType.FADE -> fadeIn(TransitionSpecs.fadeSpec(motionLevel))
    }
}

/**
 * Transición de salida.
 */
fun createExitTransition(
    transitionType: TransitionType,
    motionLevel: MotionLevel
): ExitTransition {
    if (motionLevel == MotionLevel.NONE) return ExitTransition.None

    // Con "Reducir movimiento" (MINIMAL), usar solo Fade (sin desplazamientos)
    if (motionLevel == MotionLevel.MINIMAL || transitionType == TransitionType.FADE) {
        return fadeOut(TransitionSpecs.fadeSpec(motionLevel))
    }

    return when (transitionType) {
        TransitionType.SLIDE_FORWARD -> slideOutHorizontally(
            animationSpec = TransitionSpecs.slideSpec(motionLevel),
            targetOffsetX = { fullWidth -> -(fullWidth * 0.3f).toInt() }
        ) + fadeOut(TransitionSpecs.fadeSpec(motionLevel), targetAlpha = 0f)

        TransitionType.SLIDE_BACKWARD -> slideOutHorizontally(
            animationSpec = TransitionSpecs.slideSpec(motionLevel),
            targetOffsetX = { fullWidth -> (fullWidth * 0.3f).toInt() }
        ) + fadeOut(TransitionSpecs.fadeSpec(motionLevel), targetAlpha = 0f)

        TransitionType.MODAL_OPEN -> fadeOut(TransitionSpecs.fadeSpec(motionLevel), targetAlpha = 0f)

        TransitionType.MODAL_CLOSE -> slideOutVertically(
            animationSpec = TransitionSpecs.slideSpec(motionLevel),
            targetOffsetY = { fullHeight -> fullHeight }
        ) + fadeOut(TransitionSpecs.fadeSpec(motionLevel), targetAlpha = 0f)

        TransitionType.FADE -> fadeOut(TransitionSpecs.fadeSpec(motionLevel))
    }
}
