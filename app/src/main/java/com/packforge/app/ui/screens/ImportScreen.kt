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
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.draw.shadow
import com.packforge.app.ui.components.bounceClick
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
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
import com.packforge.app.ui.components.CraftingTableLayout
import com.packforge.app.ui.components.AddonDisplayItem
import com.packforge.app.ui.components.AddonPairCard
import com.packforge.app.ui.components.AddonPairDialog
import com.packforge.app.ui.components.buildAddonDisplayItems
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

    // ─── Agrupación visual BP+RP en "minicarpeta morada" (solo UI) ───
    val displayItems = remember(addons) { buildAddonDisplayItems(addons) }
    var openPairKey by remember { mutableStateOf<String?>(null) }
    val openPair = displayItems
        .filterIsInstance<AddonDisplayItem.Pair>()
        .firstOrNull { it.pairKey == openPairKey }
    // Si la pareja desapareció (p.ej. se borró un addon dentro de la carpeta),
    // la miniinterfaz se cierra y limpiamos la clave para no reabrirla sola.
    LaunchedEffect(openPair) {
        if (openPair == null) openPairKey = null
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .then(if (openPair != null) Modifier.blur(14.dp) else Modifier),
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

        // ─── MESA DE CRAFTEO DINÁMICA ────────────────────────
        // Refleja en vivo los addons importados en la rejilla 3x3.
        if (addons.isNotEmpty() && !isImporting) {
            item {
                CraftingTableLayout(
                    addons = addons,
                    onAddAddon = { launcher.launch(arrayOf("*/*")) },
                    onExport = {},
                    showResultSlot = false
                )
            }
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

        items(
            items = displayItems,
            key = { item ->
                when (item) {
                    is AddonDisplayItem.Single -> item.addon.id
                    is AddonDisplayItem.Pair -> "pair_${item.pairKey}"
                }
            },
            contentType = { item ->
                when (item) {
                    is AddonDisplayItem.Single -> item.addon.type
                    is AddonDisplayItem.Pair -> "pair"
                }
            }
        ) { item ->
            when (item) {
                is AddonDisplayItem.Single -> {
                    val addon = item.addon
                    val addonConflicts = conflictsByAddonId[addon.id].orEmpty()
                    val onToggle = remember(addon.id) { { onToggleAddon(addon.id) } }
                    val onMoveUp = remember(addon.id) { { onMoveAddon(addon.id, -1) } }
                    val onMoveDown = remember(addon.id) { { onMoveAddon(addon.id, 1) } }
                    val onRemove = remember(addon.id) { { onRemoveAddon(addon.id) } }

                    AddonCard(
                        addon = addon,
                        index = addon.priority,
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
                is AddonDisplayItem.Pair -> {
                    AddonPairCard(
                        pair = item,
                        onOpen = { openPairKey = item.pairKey }
                    )
                }
            }
        }

        // ─── ESTADO VACÍO ───────────────────────────────────
        if (addons.isEmpty() && !isImporting) {
            item {
                EmptyState()
            }
        }

        item { Spacer(modifier = Modifier.height(100.dp)) }
        }

        // ─── Miniinterfaz flotante de la carpeta RP+BP ──────────
        openPair?.let { pair ->
            AddonPairDialog(
                pair = pair,
                onClose = { openPairKey = null },
                onToggleAddon = onToggleAddon,
                onRemoveAddon = onRemoveAddon
            )
        }
    }
}

@Composable
fun ImportDropZone(
    isImporting: Boolean,
    progress: OperationProgress,
    onImportClick: () -> Unit
) {
    val alpha by animateFloatAsState(
        targetValue = if (isImporting) 0.75f else 1f,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "alpha"
    )

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(alpha)
            .border(
                width = 1.5.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                shape = RoundedCornerShape(24.dp)
            )
            .bounceClick(scaleDown = 0.98f, hapticFeedback = false) {
                if (!isImporting) onImportClick()
            },
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 3.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (isImporting && progress is OperationProgress.Loading) {
                val progVal = progress.progress ?: 0f
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(76.dp)
                ) {
                    CircularProgressIndicator(
                        progress = { progVal },
                        modifier = Modifier.fillMaxSize(),
                        strokeCap = StrokeCap.Round,
                        strokeWidth = 6.dp,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                    if (progress.progress == null) {
                        CircularProgressIndicator(
                            modifier = Modifier.fillMaxSize(),
                            strokeCap = StrokeCap.Round,
                            strokeWidth = 6.dp
                        )
                    } else {
                        Text(
                            text = "${(progVal * 100).toInt()}%",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Text(
                    text = progress.message,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                LinearProgressIndicator(
                    progress = { progVal },
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(100.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    strokeCap = StrokeCap.Round
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(34.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Taller de Addons & Packs",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Selecciona o arrastra archivos para comenzar",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Button(
                    onClick = onImportClick,
                    shape = RoundedCornerShape(100.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier.bounceClick(scaleDown = 0.94f) { onImportClick() }
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Examinar Archivos", fontWeight = FontWeight.SemiBold)
                }

                // Chips de formatos compatibles
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FormatChip(".mcaddon")
                    FormatChip(".mcpack")
                    FormatChip(".zip")
                }
            }
        }
    }
}

@Composable
private fun FormatChip(format: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f)
    ) {
        Text(
            text = format,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
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
            criticalCount > 0 -> MaterialTheme.colorScheme.error
            score >= 80 -> MaterialTheme.colorScheme.primary
            score >= 50 -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.error
        },
        animationSpec = tween(400, easing = FastOutSlowInEasing),
        label = "scoreColor"
    )

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                RoundedCornerShape(22.dp)
            ),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ─── Radial Gauge ──────────────────────────────────
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(72.dp)
            ) {
                CircularProgressIndicator(
                    progress = { score / 100f },
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 7.dp,
                    strokeCap = StrokeCap.Round,
                    color = scoreColor,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
                Text(
                    text = "$score%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = scoreColor
                )
            }

            // ─── Estadísticas y Badges ────────────────────────
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Diagnóstico de Compatibilidad",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "$activeAddons de $totalAddons addons activos en fusión",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(2.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (criticalCount > 0) {
                        DiagnosticBadge(
                            text = "$criticalCount Crítico(s)",
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    } else {
                        DiagnosticBadge(
                            text = "0 Críticos",
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (conflictCount > 0) {
                        DiagnosticBadge(
                            text = "$conflictCount Avisos",
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    } else {
                        DiagnosticBadge(
                            text = "100% Estable",
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticBadge(
    text: String,
    containerColor: Color,
    contentColor: Color
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = containerColor
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
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

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (hasCritical)
                    MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                else
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                shape = RoundedCornerShape(20.dp)
            ),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.elevatedCardElevation(
            defaultElevation = if (addon.enabled) 3.dp else 1.dp
        ),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (addon.enabled)
                MaterialTheme.colorScheme.surfaceContainerLow
            else
                MaterialTheme.colorScheme.surfaceContainerLowest
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Icono con soporte de imagen o glifo estilizado
                AddonCardIcon(
                    iconPath = addon.iconPath,
                    addonName = addon.name,
                    type = addon.type,
                    enabled = addon.enabled
                )

                // Info principal
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = addon.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (addon.enabled)
                            MaterialTheme.colorScheme.onSurface
                        else
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AddonTypeBadge(addon.type)

                        Text(
                            text = "v${addon.version} · ${"%.1f".format(addon.sizeBytes / 1024.0 / 1024.0)} MB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Switch de activación
                Switch(
                    checked = addon.enabled,
                    onCheckedChange = { onToggle() }
                )
            }

            // ─── BARRA DE ACCIONES INFERIOR ────────────────────
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Badge de Prioridad
                    Surface(
                        shape = RoundedCornerShape(100.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = "#${index + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Botones de reordenamiento con microinteracción de rebote
                    FilledTonalIconButton(
                        onClick = onMoveUp,
                        enabled = index > 0,
                        modifier = Modifier
                            .size(30.dp)
                            .bounceClick(scaleDown = 0.9f) { onMoveUp() }
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowUp,
                            contentDescription = "Subir prioridad",
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    FilledTonalIconButton(
                        onClick = onMoveDown,
                        enabled = index < total - 1,
                        modifier = Modifier
                            .size(30.dp)
                            .bounceClick(scaleDown = 0.9f) { onMoveDown() }
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Bajar prioridad",
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Botón de detalles con flecha expandible
                    TextButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.bounceClick(scaleDown = 0.94f) { expanded = !expanded }
                    ) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (expanded) "Ocultar" else "Detalles",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Botón de eliminar con confirmación táctil
                    IconButton(
                        onClick = onRemove,
                        modifier = Modifier
                            .size(32.dp)
                            .bounceClick(scaleDown = 0.88f) { onRemove() }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Quitar addon",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // ─── DETALLES EXPANDIDOS CON CHIPS ─────────────────
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(animationSpec = tween(250)) + expandVertically(),
                exit = fadeOut(animationSpec = tween(200)) + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (addon.hasScripts) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Shield,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Este addon contiene scripts de comportamiento activos",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // Fila de estadísticas de contenido
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StatCard(
                            label = "Entidades",
                            value = addon.entityIdentifiers.size.toString(),
                            modifier = Modifier.weight(1f)
                        )
                        StatCard(
                            label = "Ítems",
                            value = addon.itemIdentifiers.size.toString(),
                            modifier = Modifier.weight(1f)
                        )
                        StatCard(
                            label = "Recetas",
                            value = addon.recipeIdentifiers.size.toString(),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Archivos y versión de motor
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Archivos: ${addon.behaviorFiles.size} BP · ${addon.resourceFiles.size} RP",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Min MC: ${addon.minEngineVersion.joinToString(".")}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AddonTypeBadge(type: AddonType) {
    val (label, containerColor, contentColor) = when (type) {
        AddonType.BEHAVIOR_ONLY -> Triple("BP", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        AddonType.RESOURCE_ONLY -> Triple("RP", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        AddonType.BEHAVIOR_AND_RESOURCE -> Triple("BP + RP", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        AddonType.UNKNOWN -> Triple("GEN", MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = containerColor
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.ExtraBold,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun AddonCardIcon(
    iconPath: String?,
    addonName: String,
    type: AddonType,
    enabled: Boolean
) {
    val hasValidIcon = iconPath != null &&
        runCatching { java.io.File(iconPath).exists() }.getOrDefault(false)

    Box(
        modifier = Modifier
            .size(50.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (hasValidIcon)
                    Color.Transparent
                else
                    MaterialTheme.colorScheme.primaryContainer
            ),
        contentAlignment = Alignment.Center
    ) {
        if (hasValidIcon) {
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
                modifier = Modifier.size(26.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
fun EmptyState() {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                RoundedCornerShape(24.dp)
            ),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Extension,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            Text(
                text = "Tu taller está listo",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = "Comienza importando tus addons Bedrock (.mcaddon o .mcpack) para analizar compatibilidad y fusionarlos en un único modpack.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

