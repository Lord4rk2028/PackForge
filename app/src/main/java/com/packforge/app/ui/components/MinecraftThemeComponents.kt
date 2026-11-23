package com.packforge.app.ui.components

import com.packforge.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.packforge.app.domain.model.Addon
import com.packforge.app.domain.model.AddonType
import com.packforge.app.domain.model.OperationProgress
import com.packforge.app.ui.theme.LocalMotionLevel
import com.packforge.app.ui.theme.MotionLevel
import com.packforge.app.ui.theme.bouncySpringOf
import com.packforge.app.ui.theme.rememberExpressiveHaptics
import java.io.File

@Composable
fun CraftingTableLayout(
    addons: List<Addon>,
    displayItems: List<AddonDisplayItem> = addons.map { AddonDisplayItem.Single(it) },
    onAddAddon: () -> Unit,
    onExport: () -> Unit,
    showResultSlot: Boolean = true,
    // (índice en la lista visible, dirección -1/+1). Misma semántica que
    // `PackForgeViewModel.moveDisplayItem`, que reconstruye la lista de addons
    // en el nuevo orden.
    onMoveDisplayItem: ((Int, Int) -> Unit)? = null
) {
    val activeItems = displayItems.filter {
        when (it) {
            is AddonDisplayItem.Single -> it.addon.enabled
            is AddonDisplayItem.Pair -> it.bp.enabled && it.rp.enabled
        }
    }
    val activeCount = activeItems.sumOf {
        when (it) {
            is AddonDisplayItem.Single -> 1
            is AddonDisplayItem.Pair -> 2
        }
    }
    // La mesa tiene 9 casillas. Con más de 9 ítems no caben todos: las 8
    // primeras van individuales y la 9ª se convierte en una cuadrícula 2x2
    // con las portadas de los ítems 9.º a 12.º. Al tocarla se abre el menú
    // contextual donde se puede reordenar la lista completa.
    val hasOverflow = displayItems.size > 9
    val overflowItems = if (hasOverflow) displayItems.drop(8).take(4) else emptyList()
    val hiddenCount = if (hasOverflow) displayItems.size - 8 else 0
    var showOverflowModal by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Extension,
                contentDescription = stringResource(R.string.crafting_table),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.crafting_table), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (addons.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "$activeCount/${displayItems.size}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        // ── Rejilla 3x3 estilo mesa de crafteo ──────────────
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    RoundedCornerShape(16.dp)
                )
                .padding(10.dp)
        ) {
            val cell = (maxWidth - 32.dp) / 3f
            val gridHeight = cell * 3 + 32.dp

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(gridHeight),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                userScrollEnabled = false
            ) {
                items(9) { index ->
                    when {
                        // 9.ª casilla con más de 9 ítems: cuadrícula 2x2 + menú.
                        hasOverflow && index == 8 -> OverflowSlot(
                            overflowItems = overflowItems,
                            hiddenCount = hiddenCount,
                            onClick = { showOverflowModal = true }
                        )
                        index < displayItems.size -> when (val item = displayItems[index]) {
                            is AddonDisplayItem.Single -> AddonSlot(addon = item.addon)
                            is AddonDisplayItem.Pair -> AddonPairSlot(pair = item)
                        }
                        index == displayItems.size -> AddEmptySlot(onAddAddon)
                        else -> EmptySlot()
                    }
                }
            }
        }

        if (showResultSlot) {
            Spacer(modifier = Modifier.height(20.dp))

            // ── Slot de resultado con brillo animado ──────────
            ResultSlot(
                addonCount = activeCount,
                onClick = onExport
            )
        }
    }

    // Menú contextual de orden (fuera del Column para que el diálogo se pinte
    // en su propia ventana y no quede recortado por la mesa).
    if (showOverflowModal && onMoveDisplayItem != null) {
        CraftingOverflowModal(
            displayItems = displayItems,
            onDismiss = { showOverflowModal = false },
            onMove = onMoveDisplayItem
        )
    }
}

@Composable
private fun OverflowSlot(
    overflowItems: List<AddonDisplayItem>,
    hiddenCount: Int,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .border(
                2.dp,
                MaterialTheme.colorScheme.secondary.copy(alpha = 0.75f),
                RoundedCornerShape(8.dp)
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        // Cuadrícula 2x2 con las portadas de los 4 ítems siguientes a la mesa.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            val preview = overflowItems.take(4)
            repeat(2) { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    repeat(2) { col ->
                        val index = row * 2 + col
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            if (index < preview.size) {
                                MiniItemCover(
                                    item = preview[index],
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }
        }

        // Contador de ítems que quedan fuera de la mesa.
        if (hiddenCount > 0) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp),
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.primary
            ) {
                Text(
                    text = "+$hiddenCount",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
    }
}

/** Portada mini de un ítem de la mesa (individual o pareja BP+RP). */
@Composable
private fun MiniItemCover(
    item: AddonDisplayItem,
    modifier: Modifier = Modifier
) {
    val coverPath = when (item) {
        is AddonDisplayItem.Single -> item.addon.iconPath
        is AddonDisplayItem.Pair -> item.bp.iconPath ?: item.rp.iconPath
    }
    val label = when (item) {
        is AddonDisplayItem.Single -> item.addon.name
        is AddonDisplayItem.Pair -> item.pairKey
    }

    if (coverPath != null) {
        CachedAsyncImage(
            model = File(coverPath),
            contentDescription = label,
            modifier = modifier.clip(RoundedCornerShape(4.dp)),
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label.take(1).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

/** Dos rayitas verticales: el asa de arrastre del menú de orden. */
@Composable
private fun DragBars(modifier: Modifier = Modifier, active: Boolean = false) {
    val color = if (active) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(2) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(18.dp)
                    .background(color, RoundedCornerShape(1.dp))
            )
        }
    }
}

/**
 * Diálogo animado de progreso e información al importar modpacks a la biblioteca.
 *
 * Muestra:
 *  - En estado `Loading`: barra de progreso, mensaje animado y círculo de carga.
 *  - En estado `Success`: símbolo grande de checkmark verde con el mensaje
 *    "(Nombre del Modpack) importado a la biblioteca exitosamente".
 *  - En estado `Error`: mensaje de error con opción de cerrar.
 */
@Composable
fun ModpackImportDialog(
    progress: OperationProgress,
    modpackName: String? = null,
    onDismiss: () -> Unit
) {
    if (progress is OperationProgress.Idle) return

    val haptics = rememberExpressiveHaptics()

    Dialog(
        onDismissRequest = {
            if (progress !is OperationProgress.Loading) onDismiss()
        },
        properties = DialogProperties(
            dismissOnBackPress = progress !is OperationProgress.Loading,
            dismissOnClickOutside = progress !is OperationProgress.Loading,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(20.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            shadowElevation = 16.dp,
            border = BorderStroke(
                1.dp,
                when (progress) {
                    is OperationProgress.Success -> MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    is OperationProgress.Error -> MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                    else -> MaterialTheme.colorScheme.outlineVariant
                }
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                when (progress) {
                    is OperationProgress.Loading -> {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                progress = { progress.progress ?: 0f },
                                modifier = Modifier.size(48.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 4.dp
                            )
                        }

                        Text(
                            text = "Importando Modpack",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Text(
                            text = progress.message.ifBlank { "Procesando archivos..." },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        LinearProgressIndicator(
                            progress = { progress.progress ?: 0f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    }

                    is OperationProgress.Success -> {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = "Importación completada",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(56.dp)
                            )
                        }

                        Text(
                            text = "¡Importación Completada!",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        val displayName = modpackName?.ifBlank { null }
                            ?: progress.message.substringBefore(" añadido").substringBefore(" importado").substringAfter("Modpack '").removeSuffix("'")

                        Text(
                            text = "$displayName importado a la biblioteca exitosamente",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = {
                                haptics.medium()
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("Aceptar", fontWeight = FontWeight.Bold)
                        }
                    }

                    is OperationProgress.Error -> {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.errorContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Error",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(48.dp)
                            )
                        }

                        Text(
                            text = "Error al Importar",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )

                        Text(
                            text = progress.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("Cerrar")
                        }
                    }

                    else -> {}
                }
            }
        }
    }
}

/**
 * Menú contextual que aparece al tocar la 9.ª casilla cuando la mesa tiene más
 * de 9 ítems.
 *
 * Muestra TODOS los ítems en orden con su portada; a la izquierda de cada uno
 * hay dos rayitas que, manteniéndolas presionadas y deslizando arriba/abajo,
 * reordenan el ítem al instante (la mesa se redibuja en el mismo fotograma).
 * Se cierra con la X de la esquina o tocando fuera del cuadro.
 */
@Composable
private fun CraftingOverflowModal(
    displayItems: List<AddonDisplayItem>,
    onDismiss: () -> Unit,
    onMove: (index: Int, direction: Int) -> Unit
) {
    val haptics = rememberExpressiveHaptics()
    val rowHeight = 56.dp
    val rowHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) { rowHeight.toPx() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .padding(vertical = 24.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // ── Cabecera con la X ──────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Orden de la mesa",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${displayItems.size} addons · mantén las rayitas y desliza",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cerrar",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // ── Lista reordenable ──────────────────────────
                LazyColumn(
                    modifier = Modifier.heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(
                        items = displayItems,
                        key = { _, item -> displayItemKey(item) }
                    ) { index, item ->
                        ReorderableRow(
                            modifier = Modifier.animateItem(),
                            item = item,
                            index = index,
                            total = displayItems.size,
                            rowHeight = rowHeight,
                            rowHeightPx = rowHeightPx,
                            onMove = { fromIndex, direction ->
                                if (fromIndex + direction in displayItems.indices) {
                                    haptics.light()
                                    onMove(fromIndex, direction)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun displayItemKey(item: AddonDisplayItem): String = when (item) {
    is AddonDisplayItem.Single -> item.addon.id
    is AddonDisplayItem.Pair -> "pair_${item.pairKey}"
}

/**
 * Fila del menú de orden.
 *
 * Arrastre: `detectDragGesturesAfterLongPress` sobre las rayitas. Con animaciones
 * fluidas de escala, elevación y deslizamiento (offset) que responden
 * instantáneamente al toque.
 */
@Composable
private fun ReorderableRow(
    modifier: Modifier = Modifier,
    item: AddonDisplayItem,
    index: Int,
    total: Int,
    rowHeight: androidx.compose.ui.unit.Dp,
    rowHeightPx: Float,
    onMove: (fromIndex: Int, direction: Int) -> Unit
) {
    var dragAccum by remember { mutableStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    val currentOnMove by rememberUpdatedState(onMove)
    val currentIndex by rememberUpdatedState(index)
    val currentTotal by rememberUpdatedState(total)

    var dynamicIndex by remember { mutableStateOf(index) }
    LaunchedEffect(index) {
        if (!isDragging) {
            dynamicIndex = index
        }
    }

    val animatedScale by animateFloatAsState(
        targetValue = if (isDragging) 1.04f else 1.0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "dragScale"
    )
    val animatedOffsetY by animateFloatAsState(
        targetValue = if (isDragging) dragAccum else 0f,
        animationSpec = if (isDragging) snap() else spring(stiffness = Spring.StiffnessMediumLow),
        label = "dragOffsetY"
    )
    val animatedElevation by animateDpAsState(
        targetValue = if (isDragging) 8.dp else 1.dp,
        label = "dragElevation"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(rowHeight)
            .graphicsLayer {
                translationY = animatedOffsetY
                scaleX = animatedScale
                scaleY = animatedScale
                shadowElevation = animatedElevation.toPx()
            },
        shape = RoundedCornerShape(14.dp),
        shadowElevation = animatedElevation,
        color = if (isDragging) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        border = if (isDragging) {
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        } else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Asa de arrastre: dos rayitas verticales.
            Box(
                modifier = Modifier
                    .size(width = 32.dp, height = 44.dp)
                    .pointerInput(displayItemKey(item)) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                isDragging = true
                                dragAccum = 0f
                                dynamicIndex = currentIndex
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragAccum += dragAmount.y
                                val threshold = rowHeightPx * 0.45f
                                if (dragAccum <= -threshold && dynamicIndex > 0) {
                                    currentOnMove(dynamicIndex, -1)
                                    dynamicIndex -= 1
                                    dragAccum += rowHeightPx * 0.5f
                                } else if (dragAccum >= threshold && dynamicIndex < currentTotal - 1) {
                                    currentOnMove(dynamicIndex, 1)
                                    dynamicIndex += 1
                                    dragAccum -= rowHeightPx * 0.5f
                                }
                            },
                            onDragEnd = {
                                isDragging = false
                                dragAccum = 0f
                            },
                            onDragCancel = {
                                isDragging = false
                                dragAccum = 0f
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                DragBars(active = isDragging)
            }

            Text(
                text = "${index + 1}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(20.dp)
            )

            MiniItemCover(
                item = item,
                modifier = Modifier.size(36.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when (item) {
                        is AddonDisplayItem.Single -> item.addon.name
                        is AddonDisplayItem.Pair -> item.pairKey.replaceFirstChar { it.uppercase() }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when (item) {
                        is AddonDisplayItem.Single -> item.addon.type.displayName
                        is AddonDisplayItem.Pair -> "BP + RP"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Alternativa accesible al arrastre (y útil en pantallas pequeñas).
            IconButton(
                onClick = { currentOnMove(currentIndex, -1) },
                enabled = index > 0,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowUp,
                    contentDescription = "Subir",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = { currentOnMove(currentIndex, 1) },
                enabled = index < total - 1,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Bajar",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun AddonPairSlot(pair: AddonDisplayItem.Pair) {
    val bothEnabled = pair.bp.enabled && pair.rp.enabled

    // Se muestra la portada real de la pareja (BP si la tiene, si no la del RP)
    // en vez de un panel de color con iconos: el fondo tonal se veía como un
    // bloque "invertido" que no tenía nada que ver con la portada del addon.
    val coverPath = pair.bp.iconPath ?: pair.rp.iconPath
    val disabledFilter = remember(bothEnabled) {
        if (bothEnabled) null
        else ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (coverPath != null) Color.Transparent
                else MaterialTheme.colorScheme.tertiaryContainer
            )
            .border(
                1.5.dp,
                if (bothEnabled) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.9f)
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                RoundedCornerShape(8.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (coverPath != null) {
            CachedAsyncImage(
                model = File(coverPath),
                contentDescription = pair.pairKey,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                colorFilter = disabledFilter
            )
        } else {
            Text(
                text = pair.pairKey.take(1).uppercase(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = if (bothEnabled) MaterialTheme.colorScheme.onTertiaryContainer
                else MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.5f)
            )
        }

        // Distintivo discreto de que son dos packs (BP + RP).
        Surface(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp),
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
        ) {
            Text(
                text = "BP + RP",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
            )
        }
    }
}

@Composable
private fun MiniAddonIcon(addon: Addon, smallSize: androidx.compose.ui.unit.Dp) {
    if (addon.iconPath != null) {
        CachedAsyncImage(
            model = File(addon.iconPath),
            contentDescription = addon.name,
            modifier = Modifier
                .size(smallSize)
                .clip(androidx.compose.foundation.shape.CircleShape),
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = Modifier
                .size(smallSize)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = addon.name.first().toString(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
fun AddonSlot(addon: Addon) {
    val disabledFilter = remember(addon.enabled) {
        if (addon.enabled) null
        else ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            // Sin fondo detrás de la portada: si el PNG es transparente debe
            // verse el fondo de la mesa, no un bloque de color.
            .background(
                if (addon.iconPath != null) Color.Transparent
                else MaterialTheme.colorScheme.primaryContainer
            )
            .border(
                1.5.dp,
                if (addon.enabled) MaterialTheme.colorScheme.outline
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                RoundedCornerShape(8.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (addon.iconPath != null) {
            CachedAsyncImage(
                model = File(addon.iconPath),
                contentDescription = addon.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                colorFilter = disabledFilter
            )
        } else {
            Text(
                text = addon.name.first().toString(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = if (addon.enabled) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
            )
        }
        // Desactivado = portada en gris. Antes se pintaba una capa oscura con
        // una X encima, que tapaba la portada y se veía como una píldora negra.
    }
}

@Composable
fun AddEmptySlot(onAddAddon: () -> Unit) {
    // Entrada única: el "+" aparece con un rebote suave y luego queda quieto.
    // Antes aquí había un `rememberInfiniteTransition` con pulso permanente:
    // la casilla se ANIMABA sin cesar mientras la pantalla de inicio estaba
    // abierta — un coste continuo de CPU/GPU en reposo sin beneficio.
    val motionLevel = LocalMotionLevel.current
    val scale = if (motionLevel == MotionLevel.NONE) {
        1f
    } else {
        var appeared by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { appeared = true }
        animateFloatAsState(
            targetValue = if (appeared) 1f else 0.9f,
            animationSpec = bouncySpringOf<Float>(motionLevel),
            label = "addSlotEntrance"
        ).value
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
            .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .clickable { onAddAddon() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.Add,
            null,
            modifier = Modifier
                .size(32.dp)
                .graphicsLayer(scaleX = scale, scaleY = scale),
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
fun EmptySlot() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .border(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                RoundedCornerShape(8.dp)
            )
    )
}

@Composable
fun ResultSlot(
    addonCount: Int,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "glow")
    val glow by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2000), RepeatMode.Reverse), label = "glow"
    )

    val canCraft = addonCount > 0

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.secondaryContainer
                    )
                )
            )
            .border(
                2.dp,
                if (canCraft)
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.2f + glow * 0.6f)
                else
                    MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(14.dp)
            )
            .clickable(enabled = canCraft) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = stringResource(R.string.cd_craft),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (canCraft) "Craftear Modpack" else "Añade addons para craftear",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (canCraft)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
            if (canCraft) {
                Text(
                    text = "$addonCount addon(s) en la mesa",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
fun MinecraftProgressBar(
    progress: Float,
    message: String,
    messageIcon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // ── Barra de progreso Material You Expresiva (Olas gruesas) ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .border(
                    1.5.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                    RoundedCornerShape(10.dp)
                )
        ) {
            val progressValue = progress.coerceIn(0f, 1f)

            // Relleno SÓLIDO en color primario (sin gradiente y sin dibujos
            // dentro): una barra limpia, solo color.
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progressValue)
                    .clip(RoundedCornerShape(9.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (messageIcon != null) {
                    Icon(
                        imageVector = messageIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun AddonCard(addon: Addon) {
    Card(
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(4.dp),
        modifier = Modifier.padding(8.dp)
    ) {
        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CachedAsyncImage(
                model = addon.iconPath?.let { File(it) },
                contentDescription = null,
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp))
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(addon.name, style = MaterialTheme.typography.titleMedium)
                SuggestionChip(
                    onClick = {},
                    label = { Text(when(addon.type) {
                        AddonType.BEHAVIOR_AND_RESOURCE -> "Completo"
                        AddonType.BEHAVIOR_ONLY -> "Behavior"
                        AddonType.RESOURCE_ONLY -> "Resource"
                        else -> "Unknown"
                    })}
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// TAREA 5: FAB EXPRESIVO con morphing
// ─────────────────────────────────────────────────────────────

/** Un ítem de acción del menú del FAB morfeable. */
data class MorphingFabItem(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val onClick: () -> Unit
)

/**
 * FAB expresivo "+" que "morfea" (rota y escala) al expandirse,
 * desplegando un mini-menú de acciones encima de él.
 */
@Composable
fun MorphingFab(
    items: List<MorphingFabItem>,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    val rotation by animateFloatAsState(
        targetValue = if (expanded) 45f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "fabRotation"
    )
    val scale by animateFloatAsState(
        targetValue = if (expanded) 1.12f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "fabScale"
    )

    Box(modifier = modifier) {
        // ── Mini menú de acciones ───────────────────────────
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(
                expandFrom = Alignment.Bottom,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            ),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
            modifier = Modifier.align(Alignment.BottomEnd)
        ) {
            Column(
                // Bottom alto: el menú se despliega POR ENCIMA del FAB sin
                // solaparse con el botón "+".
                modifier = Modifier.padding(end = 12.dp, bottom = 88.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items.forEach { item ->
                    Surface(
                        shape = RoundedCornerShape(100.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shadowElevation = 6.dp,
                        modifier = Modifier.clickable { item.onClick() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Icon(
                                imageVector = item.icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }

        // ── FAB con morphing ────────────────────────────────
        FloatingActionButton(
            onClick = { expanded = !expanded },
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
        ) {
            Icon(
                imageVector = if (expanded) Icons.Default.Close else Icons.Default.Add,
                contentDescription = if (expanded) "Cerrar opciones" else "Más opciones",
                modifier = Modifier.graphicsLayer { rotationZ = rotation }
            )
        }
    }
}
