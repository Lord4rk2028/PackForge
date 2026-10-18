package com.packforge.app.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.unit.IntOffset
import com.packforge.app.ui.theme.MotionLevel

/**
 * Sistema de transiciones optimizado para navegación entre pantallas.
 * 
 * Características:
 * - Transiciones rápidas y fluidas (200ms base)
 * - Diferentes estilos según el contexto de navegación
 * - Adaptativo al MotionLevel del dispositivo
 * - Usa Material Motion System con easings profesionales
 */

/**
 * Especificaciones de animación optimizadas para transiciones de pantalla.
 * Usa EmphasizedEasing (Material Design 3) para movimiento natural y rápido.
 */
object TransitionSpecs {
    // Easing emphasize para entrada (más rápido al inicio, suave al final)
    private val EmphasizedEasing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    
    // Easing emphasize acelerado para salida (inicio suave, salida rápida)
    private val EmphasizedAccelerateEasing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)
    
    // Easing estándar para transiciones simétricas
    private val StandardEasing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)

    /**
     * Duración base de transiciones de pantalla según MotionLevel.
     * Más rápido que las constantes antiguas para mejor sensación.
     */
    fun screenTransitionDuration(motionLevel: MotionLevel): Int = when (motionLevel) {
        MotionLevel.FULL -> 250        // Óptimo para transiciones fluidas
        MotionLevel.MODERATE -> 200    // Más rápido en equipos medios
        MotionLevel.MINIMAL -> 150     // Mínimo para gama baja
        MotionLevel.NONE -> 0          // Sin animación
    }

    /**
     * Duración para transiciones rápidas (ej: popBackStack).
     */
    fun quickTransitionDuration(motionLevel: MotionLevel): Int = when (motionLevel) {
        MotionLevel.FULL -> 200
        MotionLevel.MODERATE -> 150
        MotionLevel.MINIMAL -> 100
        MotionLevel.NONE -> 0
    }

    fun enterSpec(motionLevel: MotionLevel) = tween<IntOffset>(
        durationMillis = screenTransitionDuration(motionLevel),
        easing = EmphasizedEasing
    )

    fun exitSpec(motionLevel: MotionLevel) = tween<IntOffset>(
        durationMillis = quickTransitionDuration(motionLevel),
        easing = EmphasizedAccelerateEasing
    )

    fun fadeEnterSpec(motionLevel: MotionLevel) = tween<Float>(
        durationMillis = screenTransitionDuration(motionLevel),
        easing = StandardEasing
    )

    fun fadeExitSpec(motionLevel: MotionLevel) = tween<Float>(
        durationMillis = quickTransitionDuration(motionLevel),
        easing = StandardEasing
    )
}

/**
 * Tipos de transición según el contexto de navegación.
 */
enum class TransitionType {
    /** Transición estándar hacia adelante (Import → Export) */
    SLIDE_FORWARD,
    
    /** Transición hacia atrás (volver) */
    SLIDE_BACKWARD,
    
    /** Transición lateral (Import ↔ Studio) */
    SLIDE_LATERAL,
    
    /** Transición vertical hacia arriba (abrir modal/conflictos) */
    SLIDE_UP,
    
    /** Transición de fade para cambios sutiles */
    FADE
}

/**
 * Calcula el tipo de transición según las rutas origen y destino.
 */
fun getTransitionType(initialRoute: String?, targetRoute: String?): TransitionType {
    if (initialRoute == null || targetRoute == null) return TransitionType.FADE
    
    return when {
        // Import ↔ Export (flujo principal)
        initialRoute == Screen.Import.route && targetRoute == Screen.Export.route -> 
            TransitionType.SLIDE_FORWARD
        initialRoute == Screen.Export.route && targetRoute == Screen.Import.route -> 
            TransitionType.SLIDE_BACKWARD
        
        // Import/Export → Conflicts (modal desde abajo)
        (initialRoute == Screen.Import.route || initialRoute == Screen.Export.route) 
            && targetRoute == Screen.Conflicts.route -> 
            TransitionType.SLIDE_UP
        
        // Conflicts → Import/Export (cerrar modal)
        initialRoute == Screen.Conflicts.route 
            && (targetRoute == Screen.Import.route || targetRoute == Screen.Export.route) -> 
            TransitionType.SLIDE_UP
        
        // Import ↔ Studio (navegación lateral)
        (initialRoute == Screen.Import.route && targetRoute == Screen.Studio.route) ||
        (initialRoute == Screen.Studio.route && targetRoute == Screen.Import.route) -> 
            TransitionType.SLIDE_LATERAL
        
        // Studio ↔ Export (navegación lateral)
        (initialRoute == Screen.Studio.route && targetRoute == Screen.Export.route) ||
        (initialRoute == Screen.Export.route && targetRoute == Screen.Studio.route) -> 
            TransitionType.SLIDE_LATERAL
        
        else -> TransitionType.FADE
    }
}

/**
 * Crea la transición de entrada optimizada según el tipo y dirección.
 */
fun createEnterTransition(
    transitionType: TransitionType,
    motionLevel: MotionLevel
): EnterTransition {
    if (motionLevel == MotionLevel.NONE) {
        return EnterTransition.None
    }

    return when (transitionType) {
        TransitionType.SLIDE_FORWARD -> {
            // Slide desde la derecha con fade
            slideInHorizontally(
                animationSpec = TransitionSpecs.enterSpec(motionLevel),
                initialOffsetX = { fullWidth -> fullWidth / 3 }
            ) + fadeIn(
                animationSpec = TransitionSpecs.fadeEnterSpec(motionLevel),
                initialAlpha = 0.3f
            )
        }
        
        TransitionType.SLIDE_BACKWARD -> {
            // Slide desde la izquierda con fade (para pop)
            slideInHorizontally(
                animationSpec = TransitionSpecs.enterSpec(motionLevel),
                initialOffsetX = { fullWidth -> -fullWidth / 4 }
            ) + fadeIn(
                animationSpec = TransitionSpecs.fadeEnterSpec(motionLevel),
                initialAlpha = 0.5f
            )
        }
        
        TransitionType.SLIDE_LATERAL -> {
            // Slide lateral suave
            slideInHorizontally(
                animationSpec = TransitionSpecs.enterSpec(motionLevel),
                initialOffsetX = { fullWidth -> fullWidth / 4 }
            ) + fadeIn(
                animationSpec = TransitionSpecs.fadeEnterSpec(motionLevel),
                initialAlpha = 0.4f
            )
        }
        
        TransitionType.SLIDE_UP -> {
            // Slide desde abajo (modal)
            slideInVertically(
                animationSpec = TransitionSpecs.enterSpec(motionLevel),
                initialOffsetY = { fullHeight -> fullHeight / 3 }
            ) + fadeIn(
                animationSpec = TransitionSpecs.fadeEnterSpec(motionLevel),
                initialAlpha = 0.3f
            )
        }
        
        TransitionType.FADE -> {
            fadeIn(
                animationSpec = TransitionSpecs.fadeEnterSpec(motionLevel)
            )
        }
    }
}

/**
 * Crea la transición de salida optimizada según el tipo y dirección.
 */
fun createExitTransition(
    transitionType: TransitionType,
    motionLevel: MotionLevel
): ExitTransition {
    if (motionLevel == MotionLevel.NONE) {
        return ExitTransition.None
    }

    return when (transitionType) {
        TransitionType.SLIDE_FORWARD -> {
            // Slide hacia la izquierda con fade
            slideOutHorizontally(
                animationSpec = TransitionSpecs.exitSpec(motionLevel),
                targetOffsetX = { fullWidth -> -fullWidth / 4 }
            ) + fadeOut(
                animationSpec = TransitionSpecs.fadeExitSpec(motionLevel),
                targetAlpha = 0.5f
            )
        }
        
        TransitionType.SLIDE_BACKWARD -> {
            // Slide hacia la derecha con fade (para pop)
            slideOutHorizontally(
                animationSpec = TransitionSpecs.exitSpec(motionLevel),
                targetOffsetX = { fullWidth -> fullWidth / 3 }
            ) + fadeOut(
                animationSpec = TransitionSpecs.fadeExitSpec(motionLevel),
                targetAlpha = 0.3f
            )
        }
        
        TransitionType.SLIDE_LATERAL -> {
            // Slide lateral con scale sutil
            slideOutHorizontally(
                animationSpec = TransitionSpecs.exitSpec(motionLevel),
                targetOffsetX = { fullWidth -> -fullWidth / 5 }
            ) + fadeOut(
                animationSpec = TransitionSpecs.fadeExitSpec(motionLevel),
                targetAlpha = 0.6f
            )
        }
        
        TransitionType.SLIDE_UP -> {
            // Slide hacia abajo (cerrar modal)
            slideOutVertically(
                animationSpec = TransitionSpecs.exitSpec(motionLevel),
                targetOffsetY = { fullHeight -> fullHeight / 3 }
            ) + fadeOut(
                animationSpec = TransitionSpecs.fadeExitSpec(motionLevel),
                targetAlpha = 0.3f
            )
        }
        
        TransitionType.FADE -> {
            fadeOut(
                animationSpec = TransitionSpecs.fadeExitSpec(motionLevel)
            )
        }
    }
}
