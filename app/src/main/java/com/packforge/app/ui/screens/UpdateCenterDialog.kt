package com.packforge.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.packforge.app.domain.model.Addon
import com.packforge.app.domain.model.AddonUpdateState
import com.packforge.app.domain.model.ModpackUpdateReport
import com.packforge.app.domain.model.SourceMatch
import com.packforge.app.domain.model.UpdatePhase
import com.packforge.app.ui.components.AddonSite
import com.packforge.app.ui.components.CachedAsyncImage
import java.io.File
import java.util.Locale

/** Azul celeste: marca "hay versión nueva disponible". */
private val UpdateBlue = Color(0xFF29B6F6)

/** Verde: marca "ya actualizado" y la fuente recomendada. */
private val UpdateGreen = Color(0xFF4CAF50)

/**
 * CENTRO DE ACTUALIZACIONES.
 *
 * Ventana emergente con la lista de addons que componen un modpack. Cada
 * fila muestra portada, nombre, tamaño y sitio de origen. Si existe una
 * versión superior en alguna de las tres fuentes, la caja se marca con un
 * contorno azul celeste y un aviso "ACTUALIZACIÓN" con el número de fuentes
 * que la ofrecen, además de la comparación de versiones (local → remota).
 *
 * Al tocar un addon actualizable se despliegan debajo las fuentes en fila
 * horizontal. La del origen real del addon va primera, con borde verde y la
 * etiqueta "Recomendado", porque actualizar desde la misma fuente garantiza
 * el contenido que el autor publica.
 *
 * El diálogo se abre en cuanto se pulsa el botón y permanece abierto cuando
 * la búsqueda termina, haya novedades o no: el usuario decide cuándo salir,
 * tocando fuera de la ventana o con la X de la esquina superior derecha.
 */
@Composable
fun UpdateCenterDialog(
    modpackName: String,
    addons: List<Addon>,
    report: ModpackUpdateReport,
    isChecking: Boolean,
    applyingAddonId: String?,
    resolvingAddonId: String?,
    justUpdatedAddonId: String?,
    onOpenSource: (site: String, url: String) -> Unit,
    onApplyUpdate: (addonId: String, site: String, downloadUrl: String) -> Unit,
    onResolveDownloads: (addonId: String) -> Unit,
    onDismiss: () -> Unit
) {
    var expandedAddonId by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 640.dp),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(top = 16.dp, bottom = 6.dp)) {

                // ── Encabezado con X de cierre ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 18.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Actualizaciones",
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
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Cerrar",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // ── Resumen o barra de progreso de la búsqueda ──
                if (isChecking) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(15.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "Buscando en MCPEDL, CurseForge y ModBay…",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    val withUpdates = report.states.count { it.phase == UpdatePhase.UpdateAvailable }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (withUpdates > 0)
                            UpdateBlue.copy(alpha = 0.13f)
                        else
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (withUpdates > 0)
                                "$withUpdates de ${report.states.size} addon(s) tienen versión más reciente"
                            else
                                "Todo al día: ${report.states.size} addon(s) revisados",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (withUpdates > 0)
                                UpdateBlue
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                // ── Lista de addons del modpack ──
                LazyColumn(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(addons, key = { it.id }) { addon ->
                        AddonUpdateRow(
                            addon = addon,
                            state = report.states.firstOrNull { it.addonId == addon.id },
                            isExpanded = expandedAddonId == addon.id,
                            isApplying = applyingAddonId == addon.id,
                            isResolvingDownloads = resolvingAddonId == addon.id,
                            wasJustUpdated = justUpdatedAddonId == addon.id,
                            onToggle = {
                                val willExpand = expandedAddonId != addon.id
                                expandedAddonId = if (willExpand) addon.id else null
                                // Las URLs de descarga se resuelven solo cuando
                                // el usuario despliega las fuentes: hasta ese
                                // momento solo se ha estado buscando, y
                                // resolverlas todas ralentizaría la búsqueda
                                // sin aportar nada.
                                val state = report.states.firstOrNull { it.addonId == addon.id }
                                if (willExpand && state?.phase == UpdatePhase.UpdateAvailable) {
                                    onResolveDownloads(addon.id)
                                }
                            },
                            onOpenSource = onOpenSource,
                            onApplyUpdate = onApplyUpdate
                        )
                    }
                }
            }
        }
    }
}

/**
 * Fila de un addon dentro del centro de actualizaciones.
 */
@Composable
private fun AddonUpdateRow(
    addon: Addon,
    state: AddonUpdateState?,
    isExpanded: Boolean,
    isApplying: Boolean,
    isResolvingDownloads: Boolean,
    wasJustUpdated: Boolean,
    onToggle: () -> Unit,
    onOpenSource: (site: String, url: String) -> Unit,
    onApplyUpdate: (addonId: String, site: String, downloadUrl: String) -> Unit
) {
    val phase = state?.phase ?: UpdatePhase.Idle

    val borderColor by animateColorAsState(
        targetValue = when {
            wasJustUpdated -> UpdateGreen
            phase == UpdatePhase.UpdateAvailable -> UpdateBlue
            else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        },
        animationSpec = tween(280),
        label = "addonRowBorder"
    )
    val borderWidth by animateFloatAsState(
        targetValue = if (wasJustUpdated || phase == UpdatePhase.UpdateAvailable) 2f else 1f,
        animationSpec = tween(280),
        label = "addonRowBorderWidth"
    )
    val containerColor by animateColorAsState(
        targetValue = if (wasJustUpdated)
            UpdateGreen.copy(alpha = 0.08f)
        else
            MaterialTheme.colorScheme.surfaceContainerLow,
        animationSpec = tween(280),
        label = "addonRowContainer"
    )

    val showDetails = isExpanded && phase == UpdatePhase.UpdateAvailable

    Surface(
        onClick = onToggle,
        enabled = phase != UpdatePhase.Idle && phase != UpdatePhase.Updating,
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        border = BorderStroke(borderWidth.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {

            // ── Fila principal: portada · datos · estado ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AddonThumb(addon)

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = addon.name,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append(formatBytesLabel(addon.sizeBytes))
                            addon.author?.takeIf { it.isNotBlank() }?.let {
                                append(" · ")
                                append(it)
                            }
                            addon.sourceSite?.takeIf { it.isNotBlank() }?.let {
                                append(" · ")
                                append(it)
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                AddonUpdateStatusChip(
                    phase = phase,
                    sourceCount = state?.matches?.count { it.version.isNotBlank() } ?: 0,
                    isApplying = isApplying
                )
            }

            // ── Comparación de versiones (solo si hay update) ──
            if (phase == UpdatePhase.UpdateAvailable && !state!!.remoteVersion.isBlank()) {
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Actualizada: v${state.remoteVersion}",
                        style = MaterialTheme.typography.labelSmall,
                        color = UpdateBlue,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Tu versión: v${state.localVersion}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ── Fuentes: se deslizan desde arriba al expandse ──
            AnimatedVisibility(
                visible = showDetails,
                enter = expandVertically(animationSpec = tween(300)) + fadeIn(tween(300)),
                exit = shrinkVertically(animationSpec = tween(240)) + fadeOut(tween(240))
            ) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    Text(
                        text = "Elegir fuente de actualización",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    SourceChoicesRow(
                        matches = state?.matches.orEmpty().filter { it.version.isNotBlank() },
                        recommendedSite = state?.recommendedSite.orEmpty(),
                        isResolving = isResolvingDownloads,
                        onPick = { match ->
                            val direct = match.downloadUrl
                            if (!direct.isNullOrBlank()) {
                                // Tenemos el enlace directo: se actualiza aquí
                                // mismo, sin salir de la biblioteca.
                                onApplyUpdate(addon.id, match.site, direct)
                            } else {
                                // CurseForge y algunas páginas exigen JavaScript:
                                // se abre el WebView para que descargue el usuario.
                                onOpenSource(match.site, match.pageUrl)
                            }
                        }
                    )
                }
            }

            // ── Mensaje para addons ya al día ──
            if (phase == UpdatePhase.UpToDate) {
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Verified,
                        contentDescription = null,
                        tint = UpdateGreen,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = "Ya tienes la última versión disponible",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ── Confirmación tras actualizar ──
            if (wasJustUpdated) {
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = UpdateGreen,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "ADDON ACTUALIZADO",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = UpdateGreen
                    )
                }
            }
        }
    }
}

/**
 * Fila horizontal de "cajitas" con las fuentes que ofrecen la versión nueva.
 * La del origen real del addon va primera, con borde verde y la etiqueta
 * "Recomendado".
 */
@Composable
private fun SourceChoicesRow(
    matches: List<SourceMatch>,
    recommendedSite: String,
    isResolving: Boolean,
    onPick: (SourceMatch) -> Unit
) {
    val ordered = remember(matches, recommendedSite) {
        matches.sortedByDescending { it.site == recommendedSite }
    }
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(ordered, key = { it.site }) { match ->
            val site = AddonSite.fromSourceKey(match.site)
            val isRecommended = match.site == recommendedSite
            // Mientras se buscan los enlaces de descarga, la cajita avisa con un
            // spinner para que el usuario entienda por qué aún no hace nada al
            // tocarla.
            val isPending = isResolving && match.downloadUrl.isNullOrBlank()

            Surface(
                onClick = { if (!isPending) onPick(match) },
                enabled = !isPending,
                shape = RoundedCornerShape(10.dp),
                color = if (isRecommended)
                    UpdateGreen.copy(alpha = 0.10f)
                else
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(
                    width = if (isRecommended) 2.dp else 1.dp,
                    color = if (isRecommended) UpdateGreen else MaterialTheme.colorScheme.outlineVariant
                ),
                modifier = Modifier.width(if (isRecommended) 128.dp else 104.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 9.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (isPending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(15.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(
                        text = site.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isRecommended) UpdateGreen else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    Text(
                        text = "v${match.version}",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    if (isRecommended) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = "Recomendado",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            color = UpdateGreen,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

/**
 * Distintivo de estado a la derecha de la fila: "ACTUALIZACIÓN" con el
 * número de fuentes que ofrecen la versión, o el spinner mientras descarga.
 */
@Composable
private fun AddonUpdateStatusChip(
    phase: UpdatePhase,
    sourceCount: Int,
    isApplying: Boolean
) {
    when {
        isApplying -> {
            CircularProgressIndicator(modifier = Modifier.size(17.dp), strokeWidth = 2.dp)
        }
        phase == UpdatePhase.UpdateAvailable -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "ACTUALIZACIÓN",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = UpdateBlue
                    )
                    Text(
                        text = "DISPONIBLE",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = UpdateBlue
                    )
                }
                Box(
                    modifier = Modifier
                        .size(17.dp)
                        .background(UpdateBlue, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "$sourceCount",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
        phase == UpdatePhase.Updated -> {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = "Actualizado",
                tint = UpdateGreen,
                modifier = Modifier.size(17.dp)
            )
        }
        phase == UpdatePhase.NoMatch -> {
            Icon(
                imageVector = Icons.Filled.CloudOff,
                contentDescription = "No encontrado en ninguna fuente",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

/**
 * Miniatura de portada del addon, con icono de reserva si no hay imagen.
 *
 * La ruta se valida antes de pasarla a Coil: los iconos de los addons vienen
 * de archivos extraídos que pueden haber sido borrados por el sistema o por
 * una limpieza de caché. Enviar a Coil una File que ya no existe no rompe la
 * app, pero sí deja el hueco vacío; y usar un content:// caducado sí provoca
 * fallos, así que aquí solo se aceptan rutas internas verificadas.
 */
@Composable
private fun AddonThumb(addon: Addon) {
    val model: Any? = remember(addon.iconPath) {
        val path = addon.iconPath
        when {
            path.isNullOrBlank() -> null
            // Solo rutas internas absolutas verificadas en disco.
            path.startsWith("/") -> File(path).takeIf { it.exists() && it.length() > 0L }
            // content:// y file:// caducados: mejor el icono de reserva.
            else -> null
        }
    }
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center
    ) {
        if (model != null) {
            CachedAsyncImage(
                model = model,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Download,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.size(17.dp)
            )
        }
    }
}

/** Formatea un tamaño en bytes a una etiqueta legible ("2.4 MB"). */
private fun formatBytesLabel(bytes: Long): String = when {
    bytes <= 0L -> "—"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
    bytes < 1024L * 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
    else -> String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}
