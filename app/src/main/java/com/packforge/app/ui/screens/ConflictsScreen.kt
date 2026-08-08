package com.packforge.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.packforge.app.domain.model.*
import com.packforge.app.domain.model.MergeConflict

@Composable
fun ConflictsScreen(
    conflicts: List<Conflict>,
    addons: List<Addon>,
    resolutions: Map<String, String>,
    onResolve: (conflictId: String, winnerId: String) -> Unit,
    onDismiss: (conflictId: String) -> Unit,
    conflictStrategy: com.packforge.app.domain.model.ConflictStrategy = com.packforge.app.domain.model.ConflictStrategy.KEEP_FIRST,
    onConflictStrategyChange: (com.packforge.app.domain.model.ConflictStrategy) -> Unit = {},
    mergeConflicts: List<MergeConflict> = emptyList(),
    onResolveMergeConflict: (index: Int, resolution: String) -> Unit = { _, _ -> }
) {
    if (conflicts.isEmpty() && mergeConflicts.isEmpty()) {
        NoConflictsState()
        return
    }

    val resolvedCount = conflicts.count { resolutions.containsKey(it.id) }
    val totalCount = conflicts.size
    val mergeResolvedCount = mergeConflicts.count { it.resolution != "UNRESOLVED" }
    val mergeTotalCount = mergeConflicts.size

    val listState = rememberLazyListState()

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(16.dp)
    ) {
        item {
            ConflictSummaryCard(total = totalCount, resolved = resolvedCount, conflicts = conflicts)
        }

        // MERGE CONFLICTS SECTION
        if (mergeConflicts.isNotEmpty()) {
            item {
                MergeConflictsSection(
                    mergeConflicts = mergeConflicts,
                    onResolveMergeConflict = onResolveMergeConflict
                )
            }
        }

        // ESTRATEGIA DE CONFLICTO
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Estrategia de Conflicto", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Cómo actuar si dos addons modifican el mismo archivo (no fusionable):", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        com.packforge.app.domain.model.ConflictStrategy.values().forEach { strategy ->
                            val selected = conflictStrategy == strategy
                            FilterChip(
                                selected = selected,
                                onClick = { onConflictStrategyChange(strategy) },
                                label = { 
                                    Text(
                                        text = strategy.name.replace("_", " ").lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() },
                                        style = MaterialTheme.typography.labelSmall
                                    ) 
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // Dividir por severidad (TODAS, incluida WARNING)
        val grouped = conflicts.groupBy { it.severity }
        
        listOf(
            ConflictSeverity.CRITICAL,
            ConflictSeverity.HIGH,
            ConflictSeverity.MEDIUM,
            ConflictSeverity.LOW,
            ConflictSeverity.WARNING
        ).forEach { severity ->
            val list = grouped[severity] ?: emptyList()
            if (list.isNotEmpty()) {
                item { SeverityHeader(severity) }
                items(list, key = { it.id }, contentType = { it.type }) { conflict ->
                    ConflictCard(
                        conflict = conflict,
                        addons = addons,
                        resolution = resolutions[conflict.id],
                        onResolve = onResolve,
                        onDismiss = onDismiss
                    )
                }
            }
        }
        
        item { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

@Composable
fun ConflictSummaryCard(total: Int, resolved: Int, conflicts: List<Conflict>) {
    val progress = if (total > 0) resolved.toFloat() / total else 0f
    val criticalUnresolved = conflicts.count { it.severity == ConflictSeverity.CRITICAL && it.resolution == ConflictResolution.UNRESOLVED }

    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Resolución de Conflictos", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Badge(containerColor = if (criticalUnresolved > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primaryContainer) {
                    Text("$resolved/$total", color = if (criticalUnresolved > 0) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape))

            // ─── Resumen por severidad (TODAS visibles) ─────
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    ConflictSeverity.CRITICAL to Color(0xFFF44336),
                    ConflictSeverity.HIGH to Color(0xFFFF9800),
                    ConflictSeverity.MEDIUM to Color(0xFFFFC107),
                    ConflictSeverity.LOW to Color(0xFF4CAF50),
                    ConflictSeverity.WARNING to Color(0xFFB0BEC5)
                ).forEach { (severity, color) ->
                    val count = conflicts.count { it.severity == severity }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(shape = RoundedCornerShape(8.dp), color = color.copy(alpha = 0.15f)) {
                            Text(
                                text = count.toString(),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = color,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                        Text(
                            text = severity.name.lowercase().replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }

            if (criticalUnresolved > 0) {
                Text("⚠️ $criticalUnresolved conflictos críticos pendientes", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            } else {
                Text("✅ Listo para exportar", color = Color(0xFF4CAF50), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun SeverityHeader(severity: ConflictSeverity) {
    val color = when(severity) {
        ConflictSeverity.CRITICAL -> MaterialTheme.colorScheme.error          // Rojo
        ConflictSeverity.HIGH -> Color(0xFFF57C00)                            // Naranja
        ConflictSeverity.MEDIUM -> Color(0xFFFFB300)                          // Ámbar
        ConflictSeverity.LOW -> Color(0xFF4CAF50)                             // Verde
        ConflictSeverity.WARNING -> Color(0xFFFFB300)                         // Ámbar suave
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(severity.label, style = MaterialTheme.typography.titleSmall, color = color, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ConflictCard(conflict: Conflict, addons: List<Addon>, resolution: String?, onResolve: (String, String) -> Unit, onDismiss: (String) -> Unit) {
    var expanded by remember { mutableStateOf(resolution == null) }
    val isResolved = resolution != null

    ElevatedCard(
        modifier = Modifier.fillMaxWidth().border(1.dp, if (isResolved) Color(0xFF4CAF50).copy(0.4f) else Color.Transparent, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (isResolved) Icons.Default.CheckCircle else Icons.Default.Warning, null, tint = if (isResolved) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(conflict.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                    Text(if (isResolved) "Resuelto" else conflict.severity.label, style = MaterialTheme.typography.labelSmall, color = if (isResolved) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }

            AnimatedVisibility(visible = expanded) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(0.4f)
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(conflict.description, style = MaterialTheme.typography.bodySmall)
                    
                    Text("Ganador de la fusión:", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    
                    val affected = addons.filter { conflict.affectedAddonIds.contains(it.id) }
                    affected.forEach { addon ->
                        val selected = resolution == addon.id
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { onResolve(conflict.id, addon.id) },
                            shape = RoundedCornerShape(12.dp),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            border = borderStroke(selected)
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = selected, onClick = { onResolve(conflict.id, addon.id) })
                                Column {
                                    Text(addon.name, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                                    Text("Prioridad original: #${addon.priority + 1}", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }

                    // Opción de Fusionar (si aplica)
                    if (conflict.type == ConflictType.FILE_OVERLAP) {
                        val isMerge = resolution == "merge"
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { onResolve(conflict.id, "merge") },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isMerge) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                            border = borderStroke(isMerge, MaterialTheme.colorScheme.secondary)
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.AutoMirrored.Filled.MergeType, null, tint = if (isMerge) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(12.dp))
                                Text("Intentar fusión de código (Beta)", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    }
                }
            }
        }
    }
}

@Composable
fun borderStroke(selected: Boolean, color: Color = MaterialTheme.colorScheme.primary) =
    if (selected) androidx.compose.foundation.BorderStroke(2.dp, color) 
    else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)

@Composable
fun NoConflictsState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Shield, null, Modifier.size(64.dp), tint = Color(0xFF4CAF50))
            Text("¡Sin conflictos!", style = MaterialTheme.typography.titleLarge, color = Color(0xFF4CAF50))
            Text("Todo funcionará perfecto", modifier = Modifier.alpha(0.6f))
        }
    }
}

@Composable
fun MergeConflictsSection(
    mergeConflicts: List<MergeConflict>,
    onResolveMergeConflict: (index: Int, resolution: String) -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Conflictos de Fusión",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Badge(containerColor = MaterialTheme.colorScheme.errorContainer) {
                    Text(
                        "${mergeConflicts.size}",
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            
            Text(
                "Conflictos detectados durante la fusión de archivos JSON:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            mergeConflicts.forEachIndexed { index, conflict ->
                MergeConflictCard(
                    conflict = conflict,
                    index = index,
                    onResolve = onResolveMergeConflict
                )
            }
        }
    }
}

@Composable
fun MergeConflictCard(
    conflict: MergeConflict,
    index: Int,
    onResolve: (index: Int, resolution: String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val isResolved = conflict.resolution != "UNRESOLVED"
    
    val severityColor = when (conflict.severity) {
        ConflictSeverity.CRITICAL -> Color(0xFFF44336)   // Rojo
        ConflictSeverity.HIGH -> Color(0xFFFF9800)       // Naranja
        ConflictSeverity.MEDIUM -> Color(0xFFFFC107)     // Amarillo
        ConflictSeverity.LOW -> Color(0xFF4CAF50)        // Verde
        ConflictSeverity.WARNING -> Color(0xFFB0BEC5)    // Gris/Ámbar suave
    }
    
    val conflictTypeLabel = when (conflict.conflictType) {
        "ITEM_OVERWRITE" -> "Ítem sobrescrito"
        "ENTITY_OVERWRITE" -> "Entidad sobrescrita"
        "TEXTURE_OVERWRITE" -> "Textura sobrescrita"
        "RECIPE_OVERWRITE" -> "Receta sobrescrita"
        "NAMESPACE_COLLISION" -> "Identificador en conflicto"
        "UNKNOWN_STRUCTURE" -> "Estructura no reconocida"
        "MANIFEST_UUID_COLLISION" -> "UUID de manifiesto duplicado"
        else -> "Valor primitivo sobrescrito"
    }
    
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().border(
            2.dp,
            if (isResolved) Color(0xFF4CAF50).copy(0.4f) else severityColor,
            RoundedCornerShape(12.dp)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (isResolved) Icons.Default.CheckCircle else Icons.Default.Warning,
                    null,
                    tint = if (isResolved) Color(0xFF4CAF50) else severityColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        conflictTypeLabel,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        conflict.filePath,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // Etiqueta de severidad
                Surface(shape = RoundedCornerShape(8.dp), color = severityColor.copy(alpha = 0.15f)) {
                    Text(
                        text = conflict.severity.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = severityColor,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Spacer(Modifier.width(4.dp))
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            
            AnimatedVisibility(visible = expanded) {
                Column(
                    Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(0.3f))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (conflict.description.isNotBlank()) {
                        Text(
                            conflict.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "Archivo: ${conflict.filePath}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Addon que sobrescribe: ${conflict.sourceAddon}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Addon sobrescrito: ${conflict.targetAddon}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFF44336)
                    )
                    
                    Text(
                        "Resolución:",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onResolve(index, MergeConflict.RESOLUTION_KEEP_SOURCE) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (conflict.resolution == MergeConflict.RESOLUTION_KEEP_SOURCE)
                                    MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
                            ),
                            border = if (conflict.resolution == MergeConflict.RESOLUTION_KEEP_SOURCE)
                                null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Text("Mantener ${conflict.sourceAddon}", fontSize = 11.sp, maxLines = 1)
                        }
                        
                        Button(
                            onClick = { onResolve(index, MergeConflict.RESOLUTION_KEEP_TARGET) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (conflict.resolution == MergeConflict.RESOLUTION_KEEP_TARGET)
                                    MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
                            ),
                            border = if (conflict.resolution == MergeConflict.RESOLUTION_KEEP_TARGET)
                                null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Text("Mantener ${conflict.targetAddon}", fontSize = 11.sp, maxLines = 1)
                        }
                    }
                    
                    Button(
                        onClick = { onResolve(index, MergeConflict.RESOLUTION_MERGE) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (conflict.resolution == MergeConflict.RESOLUTION_MERGE)
                                MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surface
                        ),
                        border = if (conflict.resolution == MergeConflict.RESOLUTION_MERGE)
                            null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.MergeType, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Fusionar", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
