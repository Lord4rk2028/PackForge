package com.packforge.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.packforge.app.service.MergeForegroundService
import com.packforge.app.service.MergeSession

/**
 * ═══════════════════════════════════════════════════════════════════════
 * OVERLAY DE FUSIÓN EN CURSO (solo re-fusión desde "My Modpacks")
 * ═══════════════════════════════════════════════════════════════════════
 * Cubre TODA la app con un velo que bloquea la interacción mientras el
 * servicio re-fusiona, mostrando los diálogos de fusión en vivo (fase +
 * porcentaje). Al terminar se desvanece suavemente y queda solo el snackbar
 * de resultado. La exportación normal NO lo usa: ya tiene su propia UI.
 */
@Composable
fun MergeOverlay() {
    val session by MergeSession.state.collectAsStateWithLifecycle()
    val visible = !session.done && session.phase != "idle" && session.origin == "library"
    val context = LocalContext.current
    var showConfirmCancel by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(220)) + scaleIn(initialScale = 0.94f, animationSpec = tween(220)),
        exit = fadeOut(tween(260)) + scaleOut(targetScale = 0.96f, animationSpec = tween(260))
    ) {
        // Velo opaco que consume todos los toques: bloquea el editor/navegación.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.78f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { /* bloqueado a propósito */ },
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    // Botón para cancelar el proceso
                    IconButton(
                        onClick = { showConfirmCancel = true },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancelar proceso",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 4.dp
                        )

                        CrossfadeText(session.phase.ifBlank { "Preparando fusión…" })

                        LinearProgressIndicator(
                            progress = { session.percent.coerceIn(0, 100) / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )

                        Text(
                            text = "${session.percent.coerceIn(0, 100)}%",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(Modifier.height(2.dp))

                        Text(
                            text = "Puedes salir de la app: la fusión continúa en segundo plano y la notificación te avisará al terminar.",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }
    }

    if (showConfirmCancel && visible) {
        AlertDialog(
            onDismissRequest = { showConfirmCancel = false },
            title = { Text("Cancelar recarga", fontWeight = FontWeight.Bold) },
            text = { Text("¿Estás seguro de cancelar la recarga del modpack? Se eliminará todo el progreso de esta fusión.") },
            confirmButton = {
                TextButton(onClick = {
                    showConfirmCancel = false
                    MergeForegroundService.stop(context)
                }) {
                    Text("Sí, cancelar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmCancel = false }) {
                    Text("No, continuar")
                }
            }
        )
    }
}

/** Transición suave entre mensajes de fase sin saltos bruscos. */
@Composable
private fun CrossfadeText(text: String) {
    androidx.compose.animation.Crossfade(
        targetState = text,
        animationSpec = tween(200),
        label = "mergePhase"
    ) { phase ->
        Text(
            text = phase,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            minLines = 1,
            maxLines = 2,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
