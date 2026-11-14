package com.packforge.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.packforge.app.ui.theme.ColorContrast
import kotlin.math.max

/**
 * Helpers de accesibilidad para la app.
 */

/**
 * Marca un texto como encabezado para TalkBack.
 */
fun Modifier.headingLevel(level: Int = 1): Modifier = this.semantics {
    // Usamos el flag booleano de encabezado estándar de Compose.
    // Aunque no soporte nivel explícito en esta versión, TalkBack
    // lo anunciará correctamente como un punto de navegación.
    heading()
}

/**
 * Contenedor con descripción para TalkBack.
 */
@Composable
fun AccessibleContainer(
    description: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .semantics {
                contentDescription = description
            },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/**
 * Componente de carga accesible.
 */
@Composable
fun AccessibleLoading(
    message: String = "Cargando contenido",
    progress: Float? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                this.contentDescription = "$message. Por favor espere."
                if (progress != null) {
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        progress.coerceIn(0f, 1f),
                        0f..1f
                    )
                }
            },
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            progress = { progress?.coerceIn(0f, 1f) ?: -1f },
            color = MaterialTheme.colorScheme.primary
        )
    }
}

// ─── Contraste WCAG ────────────────────────────────────────────────────────

fun contrastRatio(foreground: Color, background: Color): Double =
    ColorContrast.ratio(foreground, background)

fun isContrastAdequate(
    foreground: Color,
    background: Color,
    minRatio: Double = ColorContrast.AA_NORMAL
): Boolean = ColorContrast.passes(foreground, background, minRatio)

@Composable
fun AccessibleText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    backgroundColor: Color = MaterialTheme.colorScheme.surface,
    fallbackColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val finalColor = if (isContrastAdequate(color, backgroundColor)) color else fallbackColor

    Text(
        text = text,
        color = finalColor,
        modifier = modifier
    )
}

// ─── Objetivos táctiles ────────────────────────────────────────────────────

fun Modifier.expandTouchTarget(min: Dp = 48.dp): Modifier = this.then(
    Modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val minPx = min.roundToPx()
        val targetWidth = max(placeable.width, minPx)
        val targetHeight = max(placeable.height, minPx)
        val x = (targetWidth - placeable.width) / 2
        val y = (targetHeight - placeable.height) / 2
        layout(targetWidth, targetHeight) {
            placeable.placeRelative(x, y)
        }
    }
)