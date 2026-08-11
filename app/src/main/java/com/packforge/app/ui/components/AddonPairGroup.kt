package com.packforge.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.packforge.app.domain.model.Addon
import com.packforge.app.domain.model.AddonType

// ══════════════════════════════════════════════════════════════════
// AGRUPACIÓN UI: parejas RP+BP en una "minicarpeta morada"
// ══════════════════════════════════════════════════════════════════
// Solo UI/estética: NO afecta al Merge (que sigue usando la lista plana).

/** Ítem de la lista de addons tras agrupar parejas BP/RP. */
sealed interface AddonDisplayItem {
    data class Single(val addon: Addon) : AddonDisplayItem
    data class Pair(val bp: Addon, val rp: Addon, val pairKey: String) : AddonDisplayItem
}

private val PAIR_STOPWORDS = setOf(
    "bp", "behavior", "behaviour", "behaviors", "behaviours",
    "rp", "resource", "resources", "pack", "packs", "pack6",
    "addon", "addons", "mc", "mcpack", "mcaddon", "bedrock",
    "folder", "b", "r", "v1", "v2", "v3", "beta", "test", "zip", "file"
)

/** Clave de pareja: nombre normalizado sin "BP/RP/Behavior/Resource/..." */
fun normalizedPairKey(name: String): String {
    return name.lowercase()
        .replace(Regex("\\d+\\.\\d+\\.\\d+"), " ")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .split(" ")
        .filter { it.isNotBlank() && it !in PAIR_STOPWORDS }
        .joinToString(" ")
}

/**
 * Convierte la lista plana en ítems visuales agrupando un addon BP + uno RP
 * con el MISMO nombre base en una única carpeta. El resto quedan individuales.
 */
fun buildAddonDisplayItems(addons: List<Addon>): List<AddonDisplayItem> {
    val candidates = addons.filter {
        it.type == AddonType.BEHAVIOR_ONLY || it.type == AddonType.RESOURCE_ONLY
    }
    val byKey = candidates.groupBy { normalizedPairKey(it.name) }
    val consumed = mutableSetOf<String>()
    val items = mutableListOf<AddonDisplayItem>()

    addons.forEach { addon ->
        if (addon.id in consumed) return@forEach
        val key = normalizedPairKey(addon.name)
        val group = byKey[key].orEmpty()
        val bp = group.firstOrNull { it.type == AddonType.BEHAVIOR_ONLY }
        val rp = group.firstOrNull { it.type == AddonType.RESOURCE_ONLY }

        if (bp != null && rp != null && (bp.id == addon.id || rp.id == addon.id)) {
            consumed.add(bp.id)
            consumed.add(rp.id)
            items.add(AddonDisplayItem.Pair(bp, rp, key))
        } else {
            items.add(AddonDisplayItem.Single(addon))
        }
    }
    return items
}

// ── COLORES DE LA "CARPETA MORADA" ──────────────────────────────
private val PurpleFolder = Color(0xFF7B1FA2)
private val PurpleFolderDark = Color(0xFF4A148C)
private val PurpleContainerLight = Color(0xFFF3E5F5)
private val PurpleContainerDark = Color(0xFF3A2A52)
private val PurpleTextDark = Color(0xFFE1BEE7)
private val PurpleLabelDark = Color(0xFFCE93D8)

@Composable
private fun folderContainerColor(): Color =
    if (isSystemInDarkTheme()) PurpleContainerDark else PurpleContainerLight

@Composable
private fun folderTitleColor(): Color =
    if (isSystemInDarkTheme()) PurpleTextDark else PurpleFolderDark

@Composable
private fun folderLabelColor(): Color =
    if (isSystemInDarkTheme()) PurpleLabelDark else PurpleFolder

/** Tarjeta "carpeta" morada que representa la pareja BP+RP en la lista. */
@Composable
fun AddonPairCard(
    pair: AddonDisplayItem.Pair,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bothEnabled = pair.bp.enabled && pair.rp.enabled
    val container = folderContainerColor()
    val titleColor = folderTitleColor()
    val labelColor = folderLabelColor()

    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onOpen() },
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 3.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (bothEnabled) container else container.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Icono combinado con minicarpeta morada en la esquina ──
            Box(modifier = Modifier.size(52.dp)) {
                val icon = pair.rp.iconPath ?: pair.bp.iconPath
                if (icon != null) {
                    CachedAsyncImage(
                        model = icon,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp))
                            .background(PurpleFolder.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Extension,
                            contentDescription = null,
                            tint = PurpleFolder,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
                // Minicarpeta morada en la esquina del icono
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(2.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(PurpleFolder),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = "Carpeta RP+BP",
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }

            // ── Información ────────────────────────────────────
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = pair.rp.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "RP + BP · Completo",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = labelColor
                )
                Text(
                    text = "${pair.bp.name} · ${pair.rp.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Flechita de apertura
            Text("📂", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Miniinterfaz flotante con los 2 addons, X para cerrar y fondo desenfocado. */
@Composable
fun AddonPairDialog(
    pair: AddonDisplayItem.Pair,
    onClose: () -> Unit,
    onToggleAddon: (String) -> Unit,
    onRemoveAddon: (String) -> Unit
) {
    val labelColor = folderLabelColor()

    // Fondo oscurecido que resalta la miniinterfaz (el blur sobre el fondo
    // real se aplica en el contenedor de la pantalla).
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 20.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // ── Cabecera con X ─────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "RP + BP · Completo",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = labelColor
                        )
                        Text(
                            text = "${pair.bp.name} + ${pair.rp.name}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cerrar",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // ── Los dos addons de la pareja ───────────────
                AddonPairRow(
                    addon = pair.bp,
                    label = "🔵 Behavior",
                    checked = pair.bp.enabled,
                    onToggle = {
                        // Deseleccionar uno deselecciona el otro.
                        onToggleAddon(pair.bp.id)
                        onToggleAddon(pair.rp.id)
                    },
                    onDelete = { onRemoveAddon(pair.bp.id) }
                )
                AddonPairRow(
                    addon = pair.rp,
                    label = "🟢 Resource",
                    checked = pair.rp.enabled,
                    onToggle = {
                        onToggleAddon(pair.bp.id)
                        onToggleAddon(pair.rp.id)
                    },
                    onDelete = { onRemoveAddon(pair.rp.id) }
                )

                // ── Nota ───────────────────────────────────────
                Text(
                    text = "Si borras uno, la carpeta se deshace y el otro vuelve a la lista.\n" +
                        "Borra y vuelve a importar para actualizar el addon cuando salga una versión nueva.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AddonPairRow(
    addon: Addon,
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    val labelColor = folderLabelColor()

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(modifier = Modifier.size(40.dp)) {
                if (addon.iconPath != null) {
                    CachedAsyncImage(
                        model = addon.iconPath,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(10.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(10.dp))
                            .background(PurpleFolder.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Extension,
                            contentDescription = null,
                            tint = PurpleFolder,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = addon.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor
                )
            }
            Switch(checked = checked, onCheckedChange = { onToggle() })
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Quitar ${addon.name}",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}