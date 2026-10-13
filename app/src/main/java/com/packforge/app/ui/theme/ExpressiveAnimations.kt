package com.packforge.app.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.unit.IntOffset

/**
 * Transiciones Material 3 Expressive que se adaptan solas al [MotionLevel] activo.
 *
 * Todas reciben el nivel como parámetro en lugar de leer [LocalMotionLevel], para
 * poder usarse también desde código no composable. Dentro de un @Composable basta
 * con `val level = currentMotionLevel`.
 *
 * En [MotionLevel.NONE] las duraciones son 0, por lo que el estado final se aplica
 * en el siguiente fotograma: se mantiene la funcionalidad y desaparece el
 * movimiento, que es justo lo que necesita un usuario con sensibilidad motora.
 */
object ExpressiveAnimations {

    // ── Springs ────────────────────────────────────────────────────────────

    /** Rebote elástico. Solo FULL lo usa con overshoot; el resto, springs planos. */
    fun bouncySpring(motionLevel: MotionLevel): SpringSpec<Float> = when (motionLevel) {
        MotionLevel.FULL -> spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        )
        MotionLevel.MODERATE -> spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        )
        MotionLevel.MINIMAL, MotionLevel.NONE -> spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessHigh
        )
    }

    /** Curva suave sin rebote: el patrón para color, tamaño y opacidad. */
    fun smoothSpring(motionLevel: MotionLevel): SpringSpec<Float> = when (motionLevel) {
        MotionLevel.FULL -> spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMedium
        )
        MotionLevel.MODERATE -> spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        )
        MotionLevel.MINIMAL, MotionLevel.NONE -> spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessHigh
        )
    }

    /** Sin rebote y rápido: ideal para cambios de tema, iconos y micro-acciones. */
    fun snappySpring(motionLevel: MotionLevel): SpringSpec<Float> = when (motionLevel) {
        MotionLevel.FULL, MotionLevel.MODERATE -> spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        )
        MotionLevel.MINIMAL, MotionLevel.NONE -> spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessHigh
        )
    }

    // ── Tweens ─────────────────────────────────────────────────────────────

    fun fastTween(motionLevel: MotionLevel): TweenSpec<Float> =
        tween(MotionTimings.shortDuration(motionLevel), easing = FastOutSlowInEasing)

    fun mediumTween(motionLevel: MotionLevel): TweenSpec<Float> =
        tween(MotionTimings.mediumDuration(motionLevel), easing = FastOutSlowInEasing)

    fun slowTween(motionLevel: MotionLevel): TweenSpec<Float> =
        tween(MotionTimings.longDuration(motionLevel), easing = FastOutSlowInEasing)

    /**
     * Variante de [bouncySpring] para desplazamientos en píxeles enteros.
     * `slideInVertically` espera un spec de `IntOffset`, no de `Float`.
     */
    fun bouncySpringOffset(motionLevel: MotionLevel): SpringSpec<IntOffset> = when (motionLevel) {
        MotionLevel.FULL -> spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        )
        MotionLevel.MODERATE -> spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        )
        MotionLevel.MINIMAL, MotionLevel.NONE -> spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessHigh
        )
    }

    // ── Transiciones de entrada y salida ───────────────────────────────────

    /**
     * Entrada expresiva para diálogos y hojas: fade + slide + scale.
     * En FULL el slide usa un spring con rebote; en NONE desaparece el movimiento.
     */
    fun fadeSlideScaleIn(motionLevel: MotionLevel): EnterTransition {
        val duration = MotionTimings.mediumDuration(motionLevel)
        return fadeIn(tween(duration)) +
            slideInVertically(
                animationSpec = bouncySpringOffset(motionLevel),
                initialOffsetY = { it / 2 }
            ) +
            scaleIn(
                animationSpec = tween(duration),
                initialScale = 0.94f
            )
    }

    /** Salida equivalente a [fadeSlideScaleIn], siempre más rápida que la entrada. */
    fun fadeSlideScaleOut(motionLevel: MotionLevel): ExitTransition {
        val duration = MotionTimings.shortDuration(motionLevel)
        return fadeOut(tween(duration)) +
            slideOutVertically(animationSpec = tween(duration)) +
            scaleOut(animationSpec = tween(duration), targetScale = 0.94f)
    }

    /**
     * Entrada de contenido en rejilla o lista: scale con rebote en FULL,
     * scale plano y breve en MINIMAL, instantáneo en NONE.
     */
    fun scaleInBounce(motionLevel: MotionLevel): EnterTransition {
        val duration = MotionTimings.mediumDuration(motionLevel)
        val initialScale = when (motionLevel) {
            MotionLevel.FULL -> 0.88f
            MotionLevel.MODERATE -> 0.94f
            else -> 1f
        }
        return scaleIn(
            animationSpec = bouncySpring(motionLevel),
            initialScale = initialScale
        ) + fadeIn(tween(duration))
    }

    /** Salida de contenido de rejilla o lista. */
    fun scaleOutSnappy(motionLevel: MotionLevel): ExitTransition {
        val duration = MotionTimings.shortDuration(motionLevel)
        return scaleOut(
            animationSpec = tween(duration),
            targetScale = 0.94f
        ) + fadeOut(tween(duration))
    }

    /** Entrada lateral para navegación hacia delante. */
    fun slideInFromEnd(motionLevel: MotionLevel): EnterTransition {
        val duration = MotionTimings.mediumDuration(motionLevel)
        return slideInHorizontally(
            animationSpec = tween(duration, easing = FastOutSlowInEasing),
            initialOffsetX = { it / 3 }
        ) + fadeIn(tween(duration))
    }

    /** Salida lateral para navegación hacia atrás. */
    fun slideOutToStart(motionLevel: MotionLevel): ExitTransition {
        val duration = MotionTimings.mediumDuration(motionLevel)
        return slideOutHorizontally(
            animationSpec = tween(duration, easing = FastOutSlowInEasing),
            targetOffsetX = { -it / 3 }
        ) + fadeOut(tween(duration))
    }
}

// ── Variantes genéricas ────────────────────────────────────────────────
//
// `ExpressiveAnimations` fija sus curvas en Float porque es lo que consumen
// `animateFloatAsState` y las transiciones. Sin embargo `animateDpAsState`,
// `animateColorAsState` y `animateIntAsState` exigen `AnimationSpec<Dp>`,
// `<Color>` e `<Int>`, y no aceptan un `SpringSpec<Float>`.
//
// Estas funciones libres infieren el tipo T para que la MISMA tabla de valores
// sirva en todos los casos, sin duplicar la escala por tipo de dato.

/** Curva con rebote para cualquier tipo animable. */
fun <T> bouncySpringOf(motionLevel: MotionLevel): SpringSpec<T> = when (motionLevel) {
    MotionLevel.FULL -> spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMediumLow
    )
    MotionLevel.MODERATE -> spring(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow
    )
    MotionLevel.MINIMAL, MotionLevel.NONE -> spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessHigh
    )
}

/** Curva rápida y sin rebote para cualquier tipo animable. */
fun <T> snappySpringOf(motionLevel: MotionLevel): SpringSpec<T> = when (motionLevel) {
    MotionLevel.FULL, MotionLevel.MODERATE -> spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium
    )
    MotionLevel.MINIMAL, MotionLevel.NONE -> spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessHigh
    )
}

/** Curva suave intermedia para cualquier tipo animable. */
fun <T> mediumTweenOf(motionLevel: MotionLevel): TweenSpec<T> =
    tween(MotionTimings.mediumDuration(motionLevel), easing = FastOutSlowInEasing)
