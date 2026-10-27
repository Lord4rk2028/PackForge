package com.packforge.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.packforge.app.domain.model.Addon
import com.packforge.app.ui.components.CachedAsyncImage
import com.packforge.app.ui.components.ExpressiveDialog
import com.packforge.app.ui.theme.ExpressiveShapes
import com.packforge.app.ui.theme.rememberExpressiveHaptics
import java.io.File
import java.util.Locale

/**
 * VISOR DE ADDONS de un modpack.
 *
 * Ventana emergente que lista los addons que componen el modpack con su
 * portada, tamaño y especificaciones. Se abre desde "Ver addons" en el menú
 * de tres puntos de cada tarjeta de la biblioteca.
 *
 * Se cierra de las DOS formas que pidió el diseño:
 *  · tocando fuera de la ventana (lo aporta `Dialog.onDismissRequest`), y
 *  · con la X de la esquina superior derecha.
 *
 * Los addons ya llegan decodificados: el diálogo no parsea JSON ni toca disco,
 * así que abrirlo es instantáneo aunque el modpack tenga cuarenta addons.
 */
@Composable
fun ModpackAddonsDialog(
    modpackName: String,
    addons: List<Addon>,
    onDismiss: () -> Unit
) {
    val haptics = rememberExpressiveHaptics()
    val totalBytes = addons.sumOf { it.sizeBytes }

    ExpressiveDialog(
        // onDismissRequest se dispara al tocar fuera o al pulsar atrás.
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 620.dp),
            shape = ExpressiveShapes.dialog,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column {
                // ── Encabezado con la X de cierre ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Addons del modpack",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = modpackName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = {
                        haptics.light()
                        onDismiss()
                    }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Cerrar",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // ── Resumen: cuántos addons y cuánto pesan en total ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SummaryChip("${addons.size} addons")
                    if (totalBytes > 0) {
                        SummaryChip(formatBytes(totalBytes))
                    }
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                if (addons.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Este modpack no tiene addons registrados",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(
                            start = 14.dp, end = 14.dp, top = 10.dp, bottom = 16.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            items = addons,
                            key = { it.id.ifBlank { it.fileName } },
                            contentType = { "addon" }
                        ) { addon ->
                            AddonRow(addon)
                        }
                    }
                }
            }
        }
    }
}

/** Píldora de resumen (recuento y peso total). */
@Composable
private fun SummaryChip(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/**
 * Fila de un addon: portada cuadrada, nombre, versión y tamaño.
 *
 * La portada se resuelve con la misma lógica que la del modpack (ruta
 * absoluta, `file://` o `content://`), porque `Addon.iconPath` puede venir en
 * cualquiera de esas formas según cómo se importó.
 */
@Composable
private fun AddonRow(addon: Addon) {
    val iconModel: Any? = remember(addon.iconPath) {
        val path = addon.iconPath ?: return@remember null
        when {
            path.startsWith("/") -> File(path)
            path.startsWith("file://") -> try {
                File(java.net.URI(path))
            } catch (_: Exception) {
                path
            }
            else -> path
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Portada del addon (o icono genérico si no tiene).
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (iconModel != null) {
                    // Sin `Modifier.size` propio: el Box padre ya fija 50dp y
                    // pasarle un tamaño aquí creaba size(50).fillMaxSize(), es
                    // decir medidas contradictorias que dejaban el hueco vacío.
                    CachedAsyncImage(
                        model = iconModel,
                        contentDescription = null,
                        cornerRadius = 14.dp,
                        decodeSize = 50.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Extension,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = addon.name.ifBlank { addon.fileName },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                // Especificaciones: tipo · versión · tamaño
                Text(
                    text = buildString {
                        append(addon.type.displayName)
                        if (addon.version.isNotBlank()) {
                            append(" · v")
                            append(addon.version)
                        }
                        if (addon.sizeBytes > 0) {
                            append(" · ")
                            append(formatBytes(addon.sizeBytes))
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Autor y origen, solo si el manifest los trae.
                val extras = listOfNotNull(
                    addon.author?.takeIf { it.isNotBlank() },
                    addon.sourceSite?.takeIf { it.isNotBlank() }
                )
                if (extras.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        extras.forEachIndexed { index, extra ->
                            if (index > 0) {
                                Box(
                                    modifier = Modifier
                                        .size(3.dp)
                                        .clip(CircleShape)
                                        .background(
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                                .copy(alpha = 0.5f)
                                        )
                                )
                            }
                            Text(
                                text = extra,
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Tamaño destacado a la derecha.
            if (addon.sizeBytes > 0) {
                Text(
                    text = formatBytes(addon.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * Tamaño legible (KB/MB) con separador decimal local.
 */
private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> ""
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
    else -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
}