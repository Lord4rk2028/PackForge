package com.packforge.app.ui.components

import com.packforge.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Extension
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
import kotlin.math.sin
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.packforge.app.domain.model.Addon
import com.packforge.app.domain.model.AddonType
import com.packforge.app.ui.theme.LocalMotionLevel
import com.packforge.app.ui.theme.MotionLevel
import com.packforge.app.ui.theme.bouncySpringOf
import java.io.File

@Composable
fun CraftingTableLayout(
    addons: List<Addon>,
    displayItems: List<AddonDisplayItem> = addons.map { AddonDisplayItem.Single(it) },
    onAddAddon: () -> Unit,
    onExport: () -> Unit,
    showResultSlot: Boolean = true
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
                        text = "$activeCount/9",
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
