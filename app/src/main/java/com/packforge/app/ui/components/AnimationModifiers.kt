package com.packforge.app.ui.components

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.packforge.app.ui.theme.LocalMotionLevel
import com.packforge.app.ui.theme.MotionLevel
import com.packforge.app.ui.theme.MotionTimings

// Constantes de animación para transiciones de pantalla consistentes (300ms + easing suave)
val FADE_SLOW_SPEC = tween<Float>(
    durationMillis = 300,
    easing = LinearOutSlowInEasing
)

val SLIDE_SLOW_SPEC = tween<IntOffset>(
    durationMillis = 300,
    easing = LinearOutSlowInEasing
)

val EXPAND_SLOW_SPEC = tween<IntSize>(
    durationMillis = 300,
    easing = LinearOutSlowInEasing
)

/**
 * Añade un efecto táctil de compresión elástica ("bounce") al presionar cualquier elemento.
 * Se escala suavemente a [scaleDown] y rebota al soltar, con soporte opcional de vibración háptica.
 *
 * El gesto se adapta automáticamente al [MotionLevel] activo:
 * - FULL: rebote elástico actual, sin cambio de comportamiento.
 * - MODERATE / MINIMAL: mismo gesto, sin overshoot y más rápido (equipos antiguos y Android 8-9).
 * - NONE: sin animación de escala; el clic y el háptico siguen funcionando.
 */
fun Modifier.bounceClick(
    scaleDown: Float = 0.95f,
    hapticFeedback: Boolean = true,
    onPressChanged: ((Boolean) -> Unit)? = null,
    onClick: (() -> Unit)? = null
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current
    val motionLevel = LocalMotionLevel.current

    // Permite a la tarjeta reaccionar al tacto (p. ej. ajustar su elevación).
    if (onPressChanged != null) {
        LaunchedEffect(isPressed) { onPressChanged(isPressed) }
    }

    // El tipo se declara explícitamente: sin él Kotlin no puede inferir 'T' al
    // mezclar ramas que devuelven spring(...) con otras que devuelven tween(...).
    val animationSpec: AnimationSpec<Float> = when (motionLevel) {
        MotionLevel.FULL -> spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        )
        MotionLevel.MODERATE -> spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        )
        MotionLevel.MINIMAL -> tween(
            durationMillis = MotionTimings.shortDuration(motionLevel),
            easing = FastOutSlowInEasing
        )
        MotionLevel.NONE -> tween(durationMillis = 0)
    }

    // Con "Reducir Movimiento" el elemento no se deforma: solo responde al toque.
    val pressedScale = when (motionLevel) {
        MotionLevel.NONE -> 1f
        MotionLevel.MINIMAL -> 1f - (1f - scaleDown) * 0.5f
        else -> scaleDown
    }

    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1f,
        animationSpec = animationSpec,
        label = "bounceScale"
    )

    // ⚡ OPTIMIZACIÓN DE SCROLL: `graphicsLayer { scaleX/scaleY }` crea una capa
    // separada de RenderNode por cada elemento que la lleva. En una lista de
    // tarjetas, eso significa una capa por tarjeta en cada fotograma, y es el
    // mayor coste gráfico del desplazamiento.
    //
    // Se aplica solo cuando el elemento está presionado o está animando: cuando
    // `scale == 1f` se omite la capa y el elemento se pinta directamente, sin
    // sobrecoste de compositing. El gesto de "bounce" sigue siendo visible
    // porque la capa se crea al presionar y se destruye al soltar.
    val useLayer = motionLevel != MotionLevel.NONE && scale != 1f
    this
        .then(if (useLayer) Modifier.graphicsLayer(scaleX = scale, scaleY = scale) else Modifier)
        .then(
            if (onClick != null) {
                Modifier.clickable(
                    interactionSource = interactionSource,
                    indication = null
                ) {
                    if (hapticFeedback) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    onClick()
                }
            } else {
                Modifier
            }
        )
}

/**
 * Efecto shimmer suave y brillante para placeholders y estados de carga.
 *
 * Con "Reducir Movimiento" el brillo no recorre la tarjeta indefinidamente: se
 * queda en un color plano, eliminando una animación infinita —el mayor coste
 * continuo de batería y fotogramas de esta pantalla— sin alterar el layout.
 */
fun Modifier.shimmerLoading(
    baseColor: Color = Color.LightGray.copy(alpha = 0.2f),
    highlightColor: Color = Color.White.copy(alpha = 0.45f),
    durationMillis: Int = 1200
): Modifier = composed {
    val motionLevel = LocalMotionLevel.current

    if (motionLevel == MotionLevel.NONE) {
        return@composed this.background(baseColor)
    }

    val transition = rememberInfiniteTransition(label = "shimmerTransition")
    val translateAnim by transition.animateFloat(
        initialValue = -500f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = durationMillis, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerTranslate"
    )

    val brush = Brush.linearGradient(
        colors = listOf(baseColor, highlightColor, baseColor),
        start = Offset(translateAnim, translateAnim),
        end = Offset(translateAnim + 300f, translateAnim + 300f)
    )

    this.background(brush)
}
