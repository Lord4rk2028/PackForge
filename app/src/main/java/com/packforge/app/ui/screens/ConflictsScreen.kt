package com.packforge.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.packforge.app.domain.model.Addon
import com.packforge.app.domain.model.Conflict
import com.packforge.app.domain.model.ConflictResolution
import com.packforge.app.domain.model.ConflictSeverity
import com.packforge.app.domain.model.ConflictStrategy
import com.packforge.app.domain.model.ConflictType
import com.packforge.app.domain.model.MergeConflict

@Composable
fun ConflictsScreen(
    conflicts: List<Conflict>,
    addons: List<Addon>,
    resolutions: Map<String, String>,
    onResolve: (conflictId: String, winnerId: String) -> Unit,
    onDismiss: (conflictId: String) -> Unit,
    conflictStrategy: ConflictStrategy = ConflictStrategy.KEEP_FIRST,
    onConflictStrategyChange: (ConflictStrategy) -> Unit = {},
    mergeConflicts: List<MergeConflict> = emptyList(),
    onResolveMergeConflict: (index: Int, resolution: String) -> Unit = { _, _ -> }
) {
    if (conflicts.isEmpty() && mergeConflicts.isEmpty()) {
        NoConflictsState()
        return
    }

    val resolvedCount = conflicts.count { resolutions.containsKey(it.id) }
    val totalCount = conflicts.size

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

        // MERGE CONFLICTS SECTION (BATALLA DE ADDONS)
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
                        ConflictStrategy.values().forEach { strategy ->
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
                    ConflictSeverity.CRITICAL to MaterialTheme.colorScheme.error,
                    ConflictSeverity.HIGH to MaterialTheme.colorScheme.tertiary,
                    ConflictSeverity.MEDIUM to MaterialTheme.colorScheme.tertiary.copy(alpha = 0.8f),
                    ConflictSeverity.LOW to MaterialTheme.colorScheme.primary,
                    ConflictSeverity.WARNING to MaterialTheme.colorScheme.outline
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
                Text("✅ Listo para exportar", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun SeverityHeader(severity: ConflictSeverity) {
    val color = when(severity) {
        ConflictSeverity.CRITICAL -> MaterialTheme.colorScheme.error          // Rojo
        ConflictSeverity.HIGH -> MaterialTheme.colorScheme.tertiary
        ConflictSeverity.MEDIUM -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.8f)
        ConflictSeverity.LOW -> MaterialTheme.colorScheme.primary
        ConflictSeverity.WARNING -> MaterialTheme.colorScheme.outline
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
        modifier = Modifier.fillMaxWidth().border(1.dp, if (isResolved) MaterialTheme.colorScheme.primary.copy(0.4f) else Color.Transparent, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (isResolved) Icons.Default.CheckCircle else Icons.Default.Warning, null, tint = if (isResolved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(conflict.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                    Text(if (isResolved) "Resuelto" else conflict.severity.label, style = MaterialTheme.typography.labelSmall, color = if (isResolved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
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
    if (selected) BorderStroke(2.dp, color)
    else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)

@Composable
fun NoConflictsState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Shield, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Text("¡Sin conflictos!", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Text("Todo funcionará perfecto", modifier = Modifier.alpha(0.6f))
        }
    }
}

// ═════════════════════════════════════════════════════════════
// TAREA 4: CONFLICTOS = "BATALLA DE ADDONS"
// ═════════════════════════════════════════════════════════════

/**
 * Sección "Batalla de Addons": cada conflicto de fusión se muestra
 * como una tarjeta de batalla entre dos addons con espada "⚔️".
 */
@Composable
fun MergeConflictsSection(
    mergeConflicts: List<MergeConflict>,
    onResolveMergeConflict: (index: Int, resolution: String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "⚔️ Batalla de Addons",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Conflictos detectados durante la fusión de archivos JSON",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Badge(containerColor = MaterialTheme.colorScheme.errorContainer) {
                Text(
                    "${mergeConflicts.size}",
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        mergeConflicts.forEachIndexed { index, conflict ->
            ConflictBattleCard(
                conflict = conflict,
                isResolved = conflict.resolution != "UNRESOLVED",
                onResolve = { resolution ->
                    onResolveMergeConflict(index, resolution)
                }
            )
        }
    }
}

/**
 * Tarjeta de batalla entre dos addons (KEEP_SOURCE vs KEEP_TARGET),
 * con borde coloreado según la severidad del conflicto.
 */
@Composable
fun ConflictBattleCard(
    conflict: MergeConflict,
    isResolved: Boolean = false,
    onResolve: (String) -> Unit
) {
    val borderColor = when (conflict.severity) {
        ConflictSeverity.LOW -> MaterialTheme.colorScheme.primary
        ConflictSeverity.MEDIUM -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.8f)
        ConflictSeverity.HIGH -> MaterialTheme.colorScheme.tertiary
        ConflictSeverity.CRITICAL -> MaterialTheme.colorScheme.error
        ConflictSeverity.WARNING -> MaterialTheme.colorScheme.outline
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

    Card(
        border = BorderStroke(2.dp, if (isResolved) Color(0xFF4CAF50).copy(alpha = 0.4f) else borderColor),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // ── Encabezado con severidad ─────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isResolved) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (isResolved) Color(0xFF4CAF50) else borderColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = conflictTypeLabel,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = conflict.filePath,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Surface(shape = RoundedCornerShape(8.dp), color = borderColor.copy(alpha = 0.15f)) {
                    Text(
                        text = if (isResolved) "RESUELTO" else conflict.severity.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isResolved) Color(0xFF4CAF50) else borderColor,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // ── Batalla: dos lados enfrentados ───────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BattleSide(
                    name = conflict.sourceAddon,
                    selected = conflict.resolution == MergeConflict.RESOLUTION_KEEP_SOURCE,
                    color = borderColor,
                    modifier = Modifier.weight(1f),
                    onClick = { onResolve(MergeConflict.RESOLUTION_KEEP_SOURCE) }
                )

                Text("⚔️", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 4.dp))

                BattleSide(
                    name = conflict.targetAddon,
                    selected = conflict.resolution == MergeConflict.RESOLUTION_KEEP_TARGET,
                    color = borderColor,
                    modifier = Modifier.weight(1f),
                    onClick = { onResolve(MergeConflict.RESOLUTION_KEEP_TARGET) }
                )
            }

            // ── Descripción (si existe) ──────────────────────
            if (conflict.description.isNotBlank()) {
                Text(
                    text = conflict.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ── Fusionar ambos ───────────────────────────────
            val isMerge = conflict.resolution == MergeConflict.RESOLUTION_MERGE
            TextButton(
                onClick = { onResolve(MergeConflict.RESOLUTION_MERGE) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors(
                    containerColor = if (isMerge)
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                )
            ) {
                Icon(Icons.AutoMirrored.Filled.MergeType, null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (isMerge) "✓ Fusionados" else "Fusionar ambos", fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Un lado de la batalla: tarjeta clicable para elegir ganador.
 */
@Composable
fun BattleSide(
    name: String,
    selected: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        color = if (selected) color.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) color else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = if (selected) "🏆" else "🛡️",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = name,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Text(
                text = if (selected) "Ganador" else "Elegir",
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) color else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
