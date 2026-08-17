package com.packforge.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.packforge.app.domain.model.OperationProgress
import com.packforge.app.ui.components.AddonSite

/** Selector compacto para modo landscape: muestra el sitio actual en un menú desplegable. */
@Composable
fun LandscapeSiteSelector(
    currentSite: AddonSite,
    onSelect: (AddonSite) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val sites = AddonSite.entries

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = currentSite.displayName,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .wrapContentWidth(align = Alignment.CenterHorizontally)
        )
        IconButton(onClick = { expanded = !expanded }) {
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = "Cambiar sitio",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
        if (expanded) {
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.width(180.dp)
            ) {
                sites.forEach { site ->
                    DropdownMenuItem(
                        text = { Text(text = site.displayName) },
                        onClick = {
                            if (site != currentSite) onSelect(site)
                            expanded = false
                        },
                        trailingIcon = {
                            if (site == currentSite) Icon(Icons.Default.Check, contentDescription = "Seleccionado", tint = MaterialTheme.colorScheme.primary)
                        }
                    )
                }
            }
        }
    }
}

/**
 * Barra de descarga compacta que aparece mientras se importa un addon de la web.
 * Muestra el progreso real cuando está disponible; si la descarga es muy rápida,
 * apenas se alcanza a ver antes de pasar al check verde.
 */
@Composable
fun WebImportProgressBar(loading: OperationProgress.Loading?) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.5.dp,
                    progress = { loading?.progress ?: 0f }
                )
                Text(
                    text = loading?.message ?: "Descargando...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.height(6.dp))
            if ((loading?.progress ?: 0f) > 0f) {
                LinearProgressIndicator(
                    progress = { (loading?.progress ?: 0f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    strokeCap = StrokeCap.Round
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    strokeCap = StrokeCap.Round
                )
            }
        }
    }
}

/**
 * Chip verde con check + "Addon importado". Se muestra tras una transición suave
 * desde la barra de descarga y desaparece por sí solo a los ~2.6 segundos.
 */
@Composable
fun WebImportSuccessChip() {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
            Text(
                text = "Addon importado",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

fun isAddonDownloadUrl(url: String): Boolean {
    val lower = url.lowercase()
    return lower.endsWith(".mcaddon") || lower.endsWith(".mcpack") ||
           lower.contains(".mcaddon?") || lower.contains(".mcpack?")
}