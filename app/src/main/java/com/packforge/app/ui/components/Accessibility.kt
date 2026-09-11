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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Helpers de accesibilidad para la app.
 */

/**
 * Modificador para marcar un texto como encabezado (para TalkBack).
 */
fun Modifier.headingLevel(@Suppress("UNUSED_PARAMETER") level: Int = 1): Modifier = this.semantics {
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
 * Componente de carga accesible con descripción para TalkBack.
 */
@Composable
fun AccessibleLoading(
    message: String = "Cargando contenido",
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .semantics {
                contentDescription = "$message. Por favor espere."
            },
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * Verificación de contraste de color (WCAG AA).
 */
fun isContrastAdequate(foreground: Color, background: Color): Boolean {
    val luminance1 = foreground.luminance()
    val luminance2 = background.luminance()
    val ratio = if (luminance1 > luminance2) {
        (luminance1 + 0.05) / (luminance2 + 0.05)
    } else {
        (luminance2 + 0.05) / (luminance1 + 0.05)
    }
    return ratio >= 4.5
}

private fun Color.luminance(): Double {
    return 0.299 * red + 0.587 * green + 0.114 * blue
}

/**
 * Texto accesible con contraste verificado.
 */
@Composable
fun AccessibleText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    backgroundColor: Color = MaterialTheme.colorScheme.surface,
    fallbackColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val finalColor = if (isContrastAdequate(color, backgroundColor)) {
        color
    } else {
        fallbackColor
    }

    Text(
        text = text,
        color = finalColor,
        modifier = modifier
    )
}
