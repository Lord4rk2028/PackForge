package com.packforge.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.packforge.app.ui.components.CachedAsyncImage
import com.packforge.app.domain.model.Addon
import com.packforge.app.domain.model.AddonType
import com.packforge.app.domain.model.Conflict
import com.packforge.app.domain.model.ConflictSeverity
import com.packforge.app.domain.model.OperationProgress

@Composable
fun ImportScreen(
    addons: List<Addon>,
    conflicts: List<Conflict>,
    isImporting: Boolean,
    importProgress: OperationProgress,
    compatibilityScore: Int,
    onImportUris: (List<android.net.Uri>) -> Unit,
    onRemoveAddon: (String) -> Unit,
    onToggleAddon: (String) -> Unit,
    onMoveAddon: (String, Int) -> Unit
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()

    val conflictsByAddonId by remember(conflicts) {
        derivedStateOf {
            buildMap<String, List<Conflict>> {
                conflicts.forEach { conflict ->
                    conflict.affectedAddonIds.forEach { addonId ->
                        put(addonId, getOrDefault(addonId, emptyList()) + conflict)
                    }
                }
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            onImportUris(uris)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
    ) {

        // ─── ZONA DE IMPORTAR ────────────────────────────────
        item {
            ImportDropZone(
                isImporting = isImporting,
                progress = importProgress,
                onImportClick = {
                    launcher.launch(arrayOf("*/*"))
                }
            )
        }

        // ─── SCORE DE COMPATIBILIDAD ─────────────────────────
        if (addons.isNotEmpty()) {
            item {
                CompatibilityScoreCard(
                    score = compatibilityScore,
                    totalAddons = addons.size,
                    activeAddons = addons.count { it.enabled },
                    conflictCount = conflicts.size,
                    criticalCount = conflicts.count {
                        it.severity == ConflictSeverity.CRITICAL
                    }
                )
            }
        }

        // ─── LISTA DE ADDONS ─────────────────────────────────
        if (addons.isNotEmpty()) {
            item {
                Text(
                    text = "Addons importados",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }

        itemsIndexed(
            items = addons,
            key = { _, addon -> addon.id },
            contentType = { _, addon -> addon.type }
        ) { index, addon ->
            val addonConflicts = conflictsByAddonId[addon.id].orEmpty()
            val onToggle = remember(addon.id) { { onToggleAddon(addon.id) } }
            val onMoveUp = remember(addon.id) { { onMoveAddon(addon.id, -1) } }
            val onMoveDown = remember(addon.id) { { onMoveAddon(addon.id, 1) } }
            val onRemove = remember(addon.id) { { onRemoveAddon(addon.id) } }

            AddonCard(
                addon = addon,
                index = index,
                total = addons.size,
                conflictCount = addonConflicts.size,
                hasCritical = addonConflicts.any {
                    it.severity == ConflictSeverity.CRITICAL
                },
                onToggle = onToggle,
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
                onRemove = onRemove
            )
        }

        // ─── ESTADO VACÍO ───────────────────────────────────
        if (addons.isEmpty() && !isImporting) {
            item {
                EmptyState()
            }
        }

        item { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

@Composable
fun ImportDropZone(
    isImporting: Boolean,
    progress: OperationProgress,
    onImportClick: () -> Unit
) {
    val alpha by animateFloatAsState(
        targetValue = if (isImporting) 0.6f else 1f,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "alpha"
    )

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(alpha)
            .border(
                width = 2.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                shape = RoundedCornerShape(28.dp)
            )
            .clickable(enabled = !isImporting) { onImportClick() },
        shape = RoundedCornerShape(28.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 3.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (isImporting && progress is OperationProgress.Loading) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(64.dp),
                        progress = { progress.progress ?: 0f },
                        strokeCap = StrokeCap.Round,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (progress.progress == null) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(64.dp),
                            strokeCap = StrokeCap.Round
                        )
                    } else {
                        Text(
                            text = "${(progress.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Text(
                    text = progress.message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Text(
                    text = "Importar Addons",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Toca para seleccionar archivos .mcaddon o .mcpack",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onImportClick,
                    shape = RoundedCornerShape(100.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Seleccionar archivos")
                }
            }
        }
    }
}

@Composable
fun CompatibilityScoreCard(
    score: Int,
    totalAddons: Int,
    activeAddons: Int,
    conflictCount: Int,
    criticalCount: Int
) {
    val scoreColor by animateColorAsState(
        targetValue = when {
            score >= 80 -> Color(0xFF4CAF50)
            score >= 50 -> Color(0xFFFFC107)
            else -> Color(0xFFF44336)
        },
        animationSpec = tween(500, easing = FastOutSlowInEasing),
        label = "scoreColor"
    )

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Compatibilidad",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "$activeAddons de $totalAddons activos",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "$score%",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = scoreColor
                )
            }

            LinearProgressIndicator(
                progress = { score / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(100.dp)),
                color = scoreColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeCap = StrokeCap.Round
            )

            if (conflictCount > 0) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (criticalCount > 0)
                            Icons.Default.Warning
                        else
                            Icons.Outlined.Info,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (criticalCount > 0)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (criticalCount > 0)
                            "$criticalCount conflicto(s) crítico(s) detectado(s)"
                        else
                            "$conflictCount advertencia(s) — revisa la pestaña Conflictos",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (criticalCount > 0)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = Color(0xFF4CAF50)
                    )
                    Text(
                        text = "Todos los addons son compatibles",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF4CAF50)
                    )
                }
            }
        }
    }
}

@Composable
fun AddonCard(
    addon: Addon,
    index: Int,
    total: Int,
    conflictCount: Int,
    hasCritical: Boolean,
    onToggle: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    val elevation by animateDpAsState(
        targetValue = if (addon.enabled) 3.dp else 1.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "elevation"
    )

    val iconBgColor by animateColorAsState(
        targetValue = if (addon.enabled)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "iconBg"
    )

    val iconColor by animateColorAsState(
        targetValue = if (addon.enabled)
            MaterialTheme.colorScheme.onPrimaryContainer
        else
            MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "iconColor"
    )

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = elevation)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AddonCardIcon(
                    iconPath = addon.iconPath,
                    addonName = addon.name,
                    type = addon.type,
                    enabled = addon.enabled,
                    iconBgColor = iconBgColor,
                    iconColor = iconColor
                )

                AddonCardInfo(
                    addon = addon,
                    conflictCount = conflictCount,
                    hasCritical = hasCritical
                )

                Switch(
                    checked = addon.enabled,
                    onCheckedChange = { onToggle() }
                )
            }

            // ─── CONTROLES ───────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Prioridad
                Surface(
                    shape = RoundedCornerShape(100.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text(
                        text = "#${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(
                            horizontal = 8.dp, vertical = 4.dp
                        )
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Botones mover
                FilledTonalIconButton(
                    onClick = onMoveUp,
                    enabled = index > 0,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = "Subir prioridad",
                        modifier = Modifier.size(18.dp)
                    )
                }
                FilledTonalIconButton(
                    onClick = onMoveDown,
                    enabled = index < total - 1,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Bajar prioridad",
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // Ver archivos
                TextButton(
                    onClick = { expanded = !expanded }
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (expanded) "Ocultar" else "Detalles",
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                // Quitar
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Quitar addon",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            // ─── DETALLES EXPANDIDOS ─────────────────────────
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(animationSpec = tween(300, easing = FastOutSlowInEasing)) +
                        slideInVertically(animationSpec = tween(300, easing = FastOutSlowInEasing)),
                exit = fadeOut(animationSpec = tween(200, easing = FastOutSlowInEasing)) +
                       slideOutVertically(animationSpec = tween(200, easing = FastOutSlowInEasing))
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                    if (addon.entityIdentifiers.isNotEmpty()) {
                        DetailRow(
                            icon = Icons.Default.Extension,
                            label = "Entidades",
                            value = addon.entityIdentifiers
                                .take(5)
                                .joinToString(", ")
                        )
                    }
                    if (addon.itemIdentifiers.isNotEmpty()) {
                        DetailRow(
                            icon = Icons.Default.Folder,
                            label = "Ítems",
                            value = addon.itemIdentifiers
                                .take(5)
                                .joinToString(", ")
                        )
                    }
                    if (addon.recipeIdentifiers.isNotEmpty()) {
                        DetailRow(
                            icon = Icons.Default.Folder,
                            label = "Recetas",
                            value = addon.recipeIdentifiers
                                .take(5)
                                .joinToString(", ")
                        )
                    }
                    if (addon.hasScripts) {
                        DetailRow(
                            icon = Icons.Default.Warning,
                            label = "Scripts",
                            value = "Este addon tiene scripts de comportamiento",
                            valueColor = MaterialTheme.colorScheme.error
                        )
                    }
                    DetailRow(
                        icon = Icons.Default.Folder,
                        label = "Behavior",
                        value = "${addon.behaviorFiles.size} archivos"
                    )
                    DetailRow(
                        icon = Icons.Default.Folder,
                        label = "Resource",
                        value = "${addon.resourceFiles.size} archivos"
                    )
                    DetailRow(
                        icon = Icons.Outlined.Info,
                        label = "Versión MC",
                        value = addon.minEngineVersion.joinToString(".")
                    )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddonCardIcon(
    iconPath: String?,
    addonName: String,
    type: AddonType,
    enabled: Boolean,
    iconBgColor: Color,
    iconColor: Color
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(iconBgColor),
        contentAlignment = Alignment.Center
    ) {
        if (iconPath != null) {
            CachedAsyncImage(
                model = iconPath,
                contentDescription = "Icono de $addonName",
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
        } else {
            Icon(
                imageVector = when (type) {
                    AddonType.BEHAVIOR_AND_RESOURCE -> Icons.Default.Extension
                    AddonType.BEHAVIOR_ONLY -> Icons.Default.Extension
                    AddonType.RESOURCE_ONLY -> Icons.Outlined.Extension
                    AddonType.UNKNOWN -> Icons.Outlined.Extension
                },
                contentDescription = type.displayName,
                modifier = Modifier.size(24.dp),
                tint = iconColor
            )
        }
    }
}

@Composable
private fun RowScope.AddonCardInfo(
    addon: Addon,
    conflictCount: Int,
    hasCritical: Boolean
) {
    Column(modifier = Modifier.weight(1f)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = addon.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (conflictCount > 0) {
                Badge(
                    containerColor = if (hasCritical)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        text = conflictCount.toString(),
                        color = if (hasCritical)
                            MaterialTheme.colorScheme.onError
                        else
                            MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
        Text(
            text = when (addon.type) {
                AddonType.BEHAVIOR_ONLY -> "🔵 Behavior"
                AddonType.RESOURCE_ONLY -> "🟢 Resource"
                AddonType.BEHAVIOR_AND_RESOURCE -> "🟣 Completo"
                AddonType.UNKNOWN -> "⚪ Desconocido"
            },
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = when (addon.type) {
                AddonType.BEHAVIOR_ONLY -> Color(0xFF2196F3)
                AddonType.RESOURCE_ONLY -> Color(0xFF4CAF50)
                AddonType.BEHAVIOR_AND_RESOURCE -> Color(0xFF9C27B0)
                AddonType.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Text(
            text = "v${addon.version} · ${
                "%.1f".format(addon.sizeBytes / 1024.0 / 1024.0)
            } MB · ${addon.files.size} archivos",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun DetailRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    valueColor: Color = Color.Unspecified
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = if (valueColor == Color.Unspecified)
                MaterialTheme.colorScheme.onSurfaceVariant
            else valueColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.Extension,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
        Text(
            text = "Sin addons importados",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
        Text(
            text = "Toca el botón de arriba para agregar\narchivos .mcaddon o .mcpack",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}
