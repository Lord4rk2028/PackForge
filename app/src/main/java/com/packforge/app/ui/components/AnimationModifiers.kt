package com.packforge.app.ui.components

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
 */
fun Modifier.bounceClick(
    scaleDown: Float = 0.95f,
    hapticFeedback: Boolean = true,
    onClick: (() -> Unit)? = null
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current

    val scale by animateFloatAsState(
        targetValue = if (isPressed) scaleDown else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "bounceScale"
    )

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
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
 */
fun Modifier.shimmerLoading(
    baseColor: Color = Color.LightGray.copy(alpha = 0.2f),
    highlightColor: Color = Color.White.copy(alpha = 0.45f),
    durationMillis: Int = 1200
): Modifier = composed {
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
