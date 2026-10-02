package com.packforge.app.ui.screens

import com.packforge.app.R
import androidx.compose.ui.res.stringResource
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import com.packforge.app.ui.components.bounceClick
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.packforge.app.ui.components.CachedAsyncImage
import com.packforge.app.ui.components.AddonSite
import com.packforge.app.ui.components.MorphingFab
import com.packforge.app.ui.components.MorphingFabItem
import com.packforge.app.ui.components.SiteSelector
import com.packforge.app.util.PackForgeLog
import com.packforge.app.domain.model.OperationProgress
import com.packforge.app.domain.model.SavedModpackSummary
import com.packforge.app.domain.model.Addon
import com.packforge.app.domain.model.AddonUpdateState
import com.packforge.app.domain.model.ModpackUpdateReport
import com.packforge.app.domain.model.UpdatePhase
import com.packforge.app.ui.viewmodel.PackForgeViewModel
import com.packforge.app.ui.viewmodel.ThemeViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioScreen(
    viewModel: PackForgeViewModel,
    themeViewModel: ThemeViewModel,
    savedModpacks: List<SavedModpackSummary>,
    isImporting: Boolean,
    importProgress: OperationProgress,
    webImportError: String?,
    onDeleteModpack: (String) -> Unit,
    onLoadModpack: (SavedModpackSummary) -> Unit,
    onImportFromUrl: (String, String, String) -> Unit,
    onClearError: () -> Unit
) {
    val activeWebSource by viewModel.activeWebSource.collectAsStateWithLifecycle()
    val lastWebUrls by viewModel.lastWebUrls.collectAsStateWithLifecycle()
    val showMyModpacks by viewModel.showMyModpacks.collectAsStateWithLifecycle()
    val showThemeSettings by viewModel.showThemeSettings.collectAsStateWithLifecycle()
    val webImportSuccess by viewModel.webImportSuccess.collectAsStateWithLifecycle()
    // Estado del centro de actualizaciones
    val isCheckingUpdates by viewModel.isCheckingUpdates.collectAsStateWithLifecycle()
    val checkingUpdatesForId by viewModel.checkingUpdatesForId.collectAsStateWithLifecycle()
    val updateTargetModpack by viewModel.updateTargetModpack.collectAsStateWithLifecycle()
    val updateReport by viewModel.updateReport.collectAsStateWithLifecycle()
    val isApplyingUpdate by viewModel.isApplyingUpdate.collectAsStateWithLifecycle()
    val resolvingAddonId by viewModel.resolvingAddonId.collectAsStateWithLifecycle()
    val justUpdatedAddonId by viewModel.justUpdatedAddonId.collectAsStateWithLifecycle()
    // Contexto de la app (no de la Activity): lo necesita el ViewModel para
    // descargar y analizar el addon actualizado.
    val appContext = LocalContext.current.applicationContext

    // Interceptar gesto de atrás para cerrar sub-pantallas y evitar que NavHost retroceda a ImportScreen
    BackHandler(enabled = showMyModpacks || showThemeSettings) {
        if (showMyModpacks) viewModel.setShowMyModpacks(false)
        if (showThemeSettings) viewModel.setShowThemeSettings(false)
    }

    // Confirmation dialog for modpack regeneration
    var modpackToRegenerate by remember { mutableStateOf<SavedModpackSummary?>(null) }

    // Manejo de navegadores internos con persistencia
    activeWebSource?.let { source ->
        val site = AddonSite.fromSourceKey(source)
        val currentUrl = lastWebUrls[source] ?: ""
        val persistentWebView = viewModel.getPersistentWebView(source, LocalContext.current)
        WebBrowserScreen(
            title = site.displayName,
            currentUrl = currentUrl,
            initialUrl = site.browseUrl,
            currentSite = site,
            importError = webImportError,
            isImporting = isImporting,
            importProgress = importProgress,
            webImportSuccess = webImportSuccess,
            onBack = {
                // ULTIMÁTUM: Una sola pulsación vuelve directo a Studio, ignorando el historial web.
                viewModel.setActiveWebSource(null)
            },
            onSiteSelect = { newSite ->
                // Cambiar de sitio: se reconfigura el WebView persistente del nuevo.
                viewModel.setActiveWebSource(newSite.sourceKey)
            },
            onUrlChanged = { newUrl -> viewModel.updateWebUrl(source, newUrl) },
            onImportFromUrl = onImportFromUrl,
            onClearError = onClearError,
            webView = persistentWebView
        )
        return
    }

    if (showThemeSettings) {
        ThemeSettingsScreen(
            viewModel = themeViewModel,
            onBack = { viewModel.setShowThemeSettings(false) }
        )
        return
    }

    if (showMyModpacks) {
        MyModpacksScreen(
            modpacks = savedModpacks,
            onBack = { viewModel.setShowMyModpacks(false) },
            onDelete = onDeleteModpack,
            onLoad = onLoadModpack,
            onOpenSources = {
                viewModel.setShowMyModpacks(false)
                viewModel.setActiveWebSource("MCPEDL")
            },
            onOpenSource = { site, url ->
                viewModel.openAddonSource(site, url)
            },
            onRegenerate = { modpack ->
                modpackToRegenerate = modpack
            },
            onImportModpack = { uri -> viewModel.importModpackFromFile(uri) },
            onCheckUpdates = { modpackId ->
                val mp = savedModpacks.firstOrNull { it.id == modpackId }
                if (mp != null) viewModel.checkModpackUpdates(mp)
            },
            isCheckingUpdates = isCheckingUpdates,
            checkingUpdatesForId = checkingUpdatesForId
        )
        modpackToRegenerate?.let { target ->
            AlertDialog(
                onDismissRequest = { modpackToRegenerate = null },
                title = { Text(stringResource(R.string.studio_regenerate_title), fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        text = "¿Estás seguro de regenerar '${target.name}' con el motor actual?\nEsto fusionará todos los addons nuevamente y reemplazará el contenido actual.",
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        modpackToRegenerate = null
                        viewModel.regenerateModpack(target.id)
                    }) {
                        Text(stringResource(R.string.common_accept), color = MaterialTheme.colorScheme.primary)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { modpackToRegenerate = null }) {
                        Text(stringResource(R.string.common_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            )
        }
        // ═══ CENTRO DE ACTUALIZACIONES ═══
        // El diálogo se abre en cuanto se pulsa el botón (para que el usuario
        // vea el progreso) y PERMANECE abierto cuando la búsqueda termina,
        // aunque no haya nada nuevo: solo se cierra si el usuario toca fuera
        // de la ventana o pulsa la X.
        updateTargetModpack?.let { targetPack ->
            val packAddons = remember(targetPack.id) { decodeModpackAddons(targetPack.addonsJson) }
            val effectiveReport = updateReport
                ?: ModpackUpdateReport(targetPack.id, packAddons.map { AddonUpdateState(addonId = it.id) })
            UpdateCenterDialog(
                modpackName = targetPack.name,
                addons = packAddons,
                report = effectiveReport,
                isChecking = isCheckingUpdates,
                applyingAddonId = isApplyingUpdate,
                resolvingAddonId = resolvingAddonId,
                justUpdatedAddonId = justUpdatedAddonId,
                onOpenSource = { site, url ->
                    viewModel.openAddonSource(site, url)
                },
                onApplyUpdate = { addonId, site, downloadUrl ->
                    viewModel.applyAddonUpdate(appContext, targetPack, addonId, site, downloadUrl)
                },
                onResolveDownloads = { addonId -> viewModel.resolveSourceDownloads(addonId) },
                onDismiss = { viewModel.closeUpdateDialog() }
            )
        }
        return
    }

    // File picker para importar modpacks desde StudioScreen principal
    val importFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                appContext.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            viewModel.importModpackFromFile(uri)
        }
    }

    val listState = rememberLazyListState()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp)
        ) {
            // ── Header hero con gradiente ────────────────────
            item {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.10f),
                                        Color.Transparent
                                    )
                                )
                            )
                            .padding(20.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Workspaces,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "Centro de Modpacks",
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Gestiona tus creaciones y fuentes de contenido",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // ── Fila de estadísticas rápidas ─────────────────
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    StatChip(
                        value = if (savedModpacks.isNotEmpty()) "${savedModpacks.size}" else "—",
                        label = "Modpacks",
                        icon = Icons.Default.Folder,
                        modifier = Modifier.weight(1f)
                    )
                    StatChip(
                        value = "${savedModpacks.sumOf { it.addonCount }}",
                        label = "Addons",
                        icon = Icons.Outlined.Extension,
                        modifier = Modifier.weight(1f)
                    )
                    StatChip(
                        value = "3",
                        label = "Fuentes",
                        icon = Icons.Default.Public,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                StudioCard(
                    icon = Icons.Default.Folder,
                    title = "Mis Modpacks",
                    description = "Edita, comparte o borra tus modpacks guardados",
                    badge = if(savedModpacks.isNotEmpty()) savedModpacks.size.toString() else null,
                    accent = MaterialTheme.colorScheme.primary
                ) {
                    viewModel.setShowMyModpacks(true)
                }
            }

            item {
                StudioCard(
                    icon = Icons.Default.Settings,
                    title = "Ajustes de Tema",
                    description = "Modo oscuro, color de acento y animaciones",
                    badge = null,
                    accent = MaterialTheme.colorScheme.tertiary
                ) {
                    viewModel.setShowThemeSettings(true)
                }
            }

            item {
                OutlinedButton(
                    onClick = {
                        importFileLauncher.launch(
                            arrayOf(
                                "application/zip",
                                "application/octet-stream",
                                "application/x-mcaddon",
                                "application/x-mcpack",
                                "*/*"
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start,
                        content = {
                            Icon(Icons.Default.Upload, contentDescription = stringResource(R.string.cd_import))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Importar Modpack",
                                style = MaterialTheme.typography.titleSmall
                            )
                        }
                    )
                }
            }

            item {
                Text(
                    text = "Fuentes Bedrock",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            // ── SELECTOR DE SITIOS CON LOGOS ─────────────────
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 14.dp, horizontal = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SiteSelector(
                            currentSite = AddonSite.fromSourceKey(activeWebSource),
                            onSelect = { site ->
                                viewModel.setActiveWebSource(site.sourceKey)
                            }
                        )
                        Text(
                            text = "Toca un sitio para navegar y descargar addons",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                        )
                    }
                }
            }

            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lightbulb,
                                contentDescription = stringResource(R.string.cd_info),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Text(
                            text = "Navega y pulsa 'Descargar' en cualquier fuente. PackForge detectará el archivo automáticamente y te avisará al finalizar.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // FAB EXPRESIVO con morphing: atajos a las fuentes
        MorphingFab(
            items = listOf(
                MorphingFabItem("MCPEDL", Icons.Default.Search) {
                    viewModel.setActiveWebSource("MCPEDL")
                },
                MorphingFabItem("CurseForge", Icons.Outlined.Extension) {
                    viewModel.setActiveWebSource("CurseForge")
                },
                MorphingFabItem("ModBay", Icons.Default.Star) {
                    viewModel.setActiveWebSource("ModBay")
                }
            ),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 16.dp)
        )
    }
}

/**
 * Cápsula de estadística rápida del Studio: valor grande + etiqueta corta
 * con icono. Da contexto de un vistazo (cuántos modpacks/addons hay).
 */
@Composable
private fun StatChip(
    value: String,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun StudioCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    badge: String?,
    accent: Color,
    onClick: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .bounceClick(scaleDown = 0.97f) { onClick() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(accent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(26.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (badge != null) {
                        Badge(containerColor = accent) { Text(badge) }
                    }
                }
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

// ═════════════════════════════════════════════════════════════
// TAREA 5: STUDIO = BIBLIOTECA DE MODPACKS (grid tipo Steam)
// ═════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyModpacksScreen(
    modpacks: List<SavedModpackSummary>,
    onBack: () -> Unit,
    onDelete: (String) -> Unit,
    onLoad: (SavedModpackSummary) -> Unit,
    onOpenSources: () -> Unit = {},
    onOpenSource: (site: String, url: String) -> Unit = { _, _ -> },
    onRegenerate: (SavedModpackSummary) -> Unit = {},
    onImportModpack: (Uri) -> Unit = {},
    onCheckUpdates: (String) -> Unit = {},
    isCheckingUpdates: Boolean = false,
    checkingUpdatesForId: String? = null
) {
    val context = LocalContext.current
    val shareScope = rememberCoroutineScope()
    var modpackToDelete by remember { mutableStateOf<SavedModpackSummary?>(null) }
    var showShareDialog by remember { mutableStateOf(false) }

    // Diálogo con bordes de colores para compartir modpacks
    if (showShareDialog) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showShareDialog = false }
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(
                    width = 2.dp,
                    brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.secondary,
                            MaterialTheme.colorScheme.tertiary
                        )
                    )
                ),
                shadowElevation = 10.dp
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(8.dp).size(20.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "Compartir Modpack",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Selecciona el modpack para exportar o enviar",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (modpacks.isEmpty()) {
                        Text(
                            text = "No tienes modpacks guardados aún.\nCrea uno desde la pantalla de Exportación.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.heightIn(max = 340.dp)
                        ) {
                            items(modpacks) { modpack: SavedModpackSummary ->
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .bounceClick(scaleDown = 0.97f) {
                                            shareModpack(context, modpack, shareScope)
                                            showShareDialog = false
                                        },
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                            modifier = Modifier.size(44.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    Icons.Default.Folder,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }
                                        }

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = modpack.name.ifBlank { "Sin nombre" },
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "v${modpack.version} · ${modpack.addonCount} addons",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        Icon(
                                            imageVector = Icons.Default.Share,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showShareDialog = false }) {
                            Text(stringResource(R.string.common_close))
                        }
                    }
                }
            }
        }
    }

    // File picker para importar modpacks
    val importFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            onImportModpack(uri)
        }
    }

    Scaffold(
        topBar = {
            // Barra flotante superior con efecto de vidrio
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shadowElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                    Text(
                        text = "Biblioteca de Modpacks",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        floatingActionButton = {
            MorphingFab(
                items = listOf(
                    MorphingFabItem("Importar Modpack", Icons.Default.Upload) {
                        importFileLauncher.launch(
                            arrayOf(
                                "application/zip",
                                "application/octet-stream",
                                "application/x-mcaddon",
                                "application/x-mcpack",
                                "*/*"
                            )
                        )
                    },
                    MorphingFabItem("Explorar fuentes", Icons.Default.Search) {
                        onOpenSources()
                    },
                    MorphingFabItem("Compartir uno", Icons.Default.Share) {
                        if (modpacks.isNotEmpty()) {
                            showShareDialog = true
                        } else {
                            android.widget.Toast.makeText(context, "No hay modpacks para compartir", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                ),
                modifier = Modifier.padding(end = 4.dp, bottom = 8.dp)
            )
        }
    ) { padding ->
        if (modpacks.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.FolderOpen, null, Modifier.size(64.dp).alpha(0.4f))
                    Text(stringResource(R.string.studio_no_modpacks), style = MaterialTheme.typography.titleMedium, modifier = Modifier.alpha(0.6f))
                    Text(
                        "Toca + para explorar fuentes y crear tu primer modpack",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                    FilledTonalButton(
                        onClick = onOpenSources,
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.studio_explore_sources))
                    }
                }
            }
        } else {
            // ── LISTA VERTICAL: una tarjeta horizontal por fila, ancho completo ──
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp)
            ) {
                items(modpacks, key = { it.id }, contentType = { "modpack" }) { modpack ->
                    val onLoadThis = remember(modpack.id) { { onLoad(modpack); onBack() } }
                    val onDeleteThis = remember(modpack.id) { { modpackToDelete = modpack } }
                    val onShareThis = remember(modpack.id) { { shareModpack(context, modpack, shareScope) } }
                    val onRegenThis = remember(modpack.id) { { onRegenerate(modpack) } }
                    val onCheckUpdatesThis = remember(modpack.id) { { onCheckUpdates(modpack.id) } }
                    val source = remember(modpack.id) { extractModpackSource(modpack.sourceJsonHead) }
                    ModpackLibraryCard(
                        modpack = modpack,
                        modpackSource = source,
                        onLoad = onLoadThis,
                        onDelete = onDeleteThis,
                        onShare = onShareThis,
                        onRegenerate = onRegenThis,
                        onOpenSource = { source?.let { onOpenSource(it.site, it.url) } },
                        onCheckUpdates = onCheckUpdatesThis,
                        isCheckingUpdates = isCheckingUpdates && checkingUpdatesForId == modpack.id
                    )
                }
                item {
                    Spacer(modifier = Modifier.height(72.dp))
                }
            }
        }
    }

    modpackToDelete?.let { m ->
        AlertDialog(onDismissRequest = { modpackToDelete = null }, title = { Text(stringResource(R.string.studio_delete_title)) },
            text = { Text(stringResource(R.string.studio_delete_message, m.name)) },
            confirmButton = { TextButton(onClick = { onDelete(m.id); modpackToDelete = null }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { modpackToDelete = null }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }
}

fun shareModpack(context: android.content.Context, modpack: SavedModpackSummary, scope: CoroutineScope) {
    // Intentar múltiples rutas posibles. PRIORIDAD: la copia permanente en el
    // almacenamiento interno de la app (filesDir/exports) creada al exportar,
    // que garantiza que "Compartir" funcione siempre aunque el fichero de
    // Downloads/SAF haya desaparecido.
    val exportsDir = File(context.filesDir, "exports")
    val possiblePaths = listOfNotNull(
        File(exportsDir, modpack.fileName).takeIf { it.exists() && it.length() > 0 },
        File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), modpack.fileName),
        File(context.cacheDir, modpack.fileName),
        File(context.filesDir, modpack.fileName).takeIf { it.exists() && it.length() > 0 },
        File(modpack.filePath) // Ruta guardada en el historial
    )

    // Buscar el primer archivo que exista y tenga contenido
    var file = possiblePaths.firstOrNull { it.exists() && it.length() > 0 }

    // Coincidencia flexible en la carpeta de exports: el nombre guardado puede
    // diferir en mayúsculas o usar "_" en lugar de espacios según la versión.
    if (file == null) {
        file = exportsDir.listFiles()?.firstOrNull { f ->
            f.length() > 0 &&
                (f.name.equals(modpack.fileName, ignoreCase = true) ||
                    f.name.equals(modpack.fileName.replace(" ", "_"), ignoreCase = true))
        }
    }

    if (file != null) {
        launchShareIntent(context, modpack, file)
        return
    }

    // Fallback: si el historial guarda una content:// o file:// URI (exportación
    // vía "Elegir ubicación" SAF), copiarla a cache EN SEGUNDO PLANO y compartir.
    // La copia de un archivo grande no debe bloquear el hilo de UI (jank/ANR).
    val storedPath = modpack.filePath
    if (storedPath.startsWith("content://") || storedPath.startsWith("file://")) {
        scope.launch {
            val copied = withContext(Dispatchers.IO) {
                try {
                    // Limpiar temporales de comparticiones anteriores para no acumular basura.
                    context.cacheDir.listFiles()
                        ?.filter { it.name.startsWith("share_") }
                        ?.forEach { it.delete() }
                    val cacheCopy = File(context.cacheDir, "share_${System.currentTimeMillis()}_${modpack.fileName}")
                    val src = if (storedPath.startsWith("content://")) {
                        context.contentResolver.openInputStream(Uri.parse(storedPath))
                    } else {
                        try { File(java.net.URI(storedPath)).inputStream() } catch (e: Exception) { null }
                    }
                    src?.use { input ->
                        cacheCopy.outputStream().use { out -> input.copyTo(out) }
                    }
                    if (cacheCopy.exists() && cacheCopy.length() > 0) cacheCopy else null
                } catch (e: Exception) {
                    PackForgeLog.e("StudioScreen", "No se pudo copiar URI para compartir: ${e.message}")
                    null
                }
            }
            if (copied != null) {
                launchShareIntent(context, modpack, copied)
            } else {
                shareNotFoundToast(context)
            }
        }
        return
    }

    PackForgeLog.e("StudioScreen", "Archivo no encontrado en ninguna ruta. Rutas intentadas: ${possiblePaths.map { it.absolutePath }}")
    shareNotFoundToast(context)
}

/** Aviso claro y accionable cuando el modpack no se encuentra en el dispositivo. */
private fun shareNotFoundToast(context: android.content.Context) {
    android.widget.Toast.makeText(
        context,
        "El modpack no está en el dispositivo. Expórtalo de nuevo y compártelo desde la exportación.",
        android.widget.Toast.LENGTH_LONG
    ).show()
}

/** Lanza el Intent de compartir de un archivo ya resuelto (debe llamarse en hilo principal). */
private fun launchShareIntent(context: android.content.Context, modpack: SavedModpackSummary, file: File) {
    try {
        // Verificar que el archivo tenga contenido
        if (file.length() == 0L) {
            android.widget.Toast.makeText(context, "El archivo está vacío (0 bytes)", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip" // Usar MIME type correcto para .mcpack
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_SUBJECT, "Modpack: ${modpack.name}")
            putExtra(Intent.EXTRA_TEXT, "Modpack creado con PackForge - ${modpack.name} v${modpack.version}")
        }
        context.startActivity(Intent.createChooser(intent, "Compartir Modpack: ${modpack.name}"))
    } catch (e: Exception) {
        PackForgeLog.e("StudioScreen", "Error al compartir: ${e.message}", e)
        android.widget.Toast.makeText(context, "Error al compartir: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/**
 * Acción directa de la tarjeta: píldora compacta de icono + label. Ocupa
 * menos que un IconButton cuadrado y se lee sin ambigüedad.
 */
@Composable
private fun DirectCardAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSpinning: Boolean = false,
    isHighlighted: Boolean = false
) {
    val containerColor by animateColorAsState(
        targetValue = if (isHighlighted)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.surfaceContainerHighest,
        animationSpec = tween(200),
        label = "directActionContainer"
    )
    val contentColor = if (isHighlighted)
        MaterialTheme.colorScheme.onPrimaryContainer
    else
        MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        onClick = onClick,
        enabled = !isSpinning,
        shape = RoundedCornerShape(10.dp),
        color = containerColor,
        modifier = modifier
            .height(34.dp)
            .bounceClick(scaleDown = 0.9f) { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(3.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
                maxLines = 1
            )
        }
    }
}

/**
 * Tarjeta HORIZONTAL de la biblioteca: portada cuadrada a la izquierda,
 * contenido y acciones a la derecha.
 *
 * Diseño optimizado para móvil: en lugar de apilar portada + metadata + dos
 * filas de botones (tarjetas altísimas que obligan a scrollear de a una),
 * todo cabe en una fila compacta. Más tarjetas visibles, menos scroll.
 *
 * Las acciones se reparten en botones directos (Editar, Actualizar,
 * Regenerar) y un menú desplegable (⋮) para las secundarias (Compartir,
 * Abrir origen, Borrar), para no amontonar cinco botones.
 */
@Composable
fun ModpackLibraryCard(
    modpack: SavedModpackSummary,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onRegenerate: () -> Unit = {},
    modpackSource: ModpackSource? = null,
    onOpenSource: () -> Unit = {},
    onCheckUpdates: () -> Unit = {},
    isCheckingUpdates: Boolean = false
) {
    var menuOpen by remember { mutableStateOf(false) }
    val coverPath = modpack.coverUriString
    // Coil necesita un File (no un String de ruta absoluta) para cargar la
    // portada persistida en almacenamiento interno; un content:// se pasa tal cual.
    val coverModel: Any? = when {
        coverPath == null -> null
        coverPath.startsWith("/") -> File(coverPath)
        coverPath.startsWith("file://") -> try { File(java.net.URI(coverPath)) } catch (e: Exception) { coverPath }
        else -> coverPath
    }

    // La flecha de "Actualizar" gira mientras el motor revisa las 3 fuentes.
    val spinAngle by if (isCheckingUpdates) {
        val transition = rememberInfiniteTransition(label = "updateSpin")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "updateSpinAngle"
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .bounceClick(scaleDown = 0.97f) { onLoad() },
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ── Portada grande con badges superpuestos ─────────────────
            Box(
                modifier = Modifier
                    .size(118.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                when {
                    coverModel != null -> CachedAsyncImage(
                        model = coverModel,
                        contentDescription = stringResource(R.string.cd_cover_of, modpack.name),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    else -> Icon(
                        Icons.Default.Extension,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier.align(Alignment.Center).size(30.dp)
                    )
                }

                // Contador de addons, abajo sobre la portada.
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(5.dp)
                ) {
                    Text(
                        text = "${modpack.addonCount} addons",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }

                // Badge de ORIGEN WEB (arriba-izquierda): el addon vino de
                // MCPEDL/CurseForge/ModBay y su página sigue accesible.
                if (modpackSource != null) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(5.dp)
                    ) {
                        Text(
                            text = modpackSource.site,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }
            }

            // ── Contenido + acciones ─────────────────────────────────
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(118.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = modpack.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    // Menú de acciones secundarias: mantiene la fila limpia.
                    Box {
                        Surface(
                            onClick = { menuOpen = true },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest
                        ) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "Más opciones",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(4.dp).size(16.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Compartir") },
                                leadingIcon = { Icon(Icons.Default.Share, null, Modifier.size(18.dp)) },
                                onClick = { menuOpen = false; onShare() }
                            )
                            if (modpackSource != null) {
                                DropdownMenuItem(
                                    text = { Text("Abrir origen (${modpackSource.site})") },
                                    leadingIcon = {
                                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp))
                                    },
                                    onClick = { menuOpen = false; onOpenSource() }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Borrar", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Delete, null,
                                        Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                },
                                onClick = { menuOpen = false; onDelete() }
                            )
                        }
                    }
                }

                // Versión + fecha en una sola línea.
                Text(
                    text = buildString {
                        append("v")
                        append(modpack.version)
                        append(" · MC ")
                        append(modpack.mcVersion)
                        val date = formatModpackDate(modpack.createdAt)
                        if (date.isNotBlank()) {
                            append(" · ")
                            append(date)
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Acciones directas: las tres más frecuentes.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    DirectCardAction(
                        icon = Icons.Default.Edit,
                        label = "Editar",
                        onClick = onLoad,
                        modifier = Modifier.weight(1f)
                    )
                    DirectCardAction(
                        icon = Icons.Default.Autorenew,
                        label = "Actualizar",
                        isSpinning = isCheckingUpdates,
                        isHighlighted = isCheckingUpdates,
                        onClick = onCheckUpdates,
                        modifier = Modifier.weight(1f)
                    )
                    DirectCardAction(
                        icon = Icons.Default.Refresh,
                        label = "Regenerar",
                        onClick = onRegenerate,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

private fun formatModpackDate(timestamp: Long): String {
    return try {
        SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
            .format(Date(timestamp))
    } catch (e: Exception) {
        ""
    }
}

/** Origen web de un addon dentro de un modpack guardado. */
data class ModpackSource(
    val site: String,      // "MCPEDL" | "CurseForge" | "ModBay"
    val url: String,       // URL de la página del addon
    val addonName: String  // nombre del addon para mostrar en el botón
)

/**
 * Deserializa la lista de addons guardada en un modpack, para pintarlos
 * dentro del centro de actualizaciones (portada, tamaño y sitio de origen).
 */
fun decodeModpackAddons(addonsJson: String): List<Addon> {
    if (addonsJson.isBlank()) return emptyList()
    return try {
        val type = object : com.google.gson.reflect.TypeToken<List<Addon>>() {}.type
        com.google.gson.Gson().fromJson<List<Addon>>(addonsJson, type) ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }
}

/**
 * Extrae el ORIGEN WEB del primer addon que tenga sourceUrl+sourceSite
 * persistidos en el addonsJson del modpack. Devuelve null si ninguno vino
 * del WebView interno (p. ej. importado por share externo o por archivo).
 *
 * Recibe el addonsJson COMPLETO o solo el head truncado (64 KB) de la lista
 * ligera: si el fragmento no se puede parsear por estar cortado a mitad de
 * JSON, se escanea el texto para localizar el primer "sourceSite" y su addon.
 */
fun extractModpackSource(addonsJson: String): ModpackSource? {
    if (addonsJson.isBlank()) return null
    // 1) Ruta rápida: JSON completo (modpacks cortos o sin truncar)
    try {
        val type = object : com.google.gson.reflect.TypeToken<List<Addon>>() {}.type
        val addons: List<Addon> = com.google.gson.Gson().fromJson(addonsJson, type)
            ?: emptyList()
        addons.firstOrNull { !it.sourceUrl.isNullOrBlank() && !it.sourceSite.isNullOrBlank() }
            ?.let { return ModpackSource(it.sourceSite!!, it.sourceUrl!!, it.name) }
    } catch (_: Exception) { /* fragmento truncado: usar escaneo por texto */ }

    // 2) Fallback para el HEAD truncado: localizar el primer "sourceSite" y
    //    reconstruir su addon con su name y sourceUrl (Gson serializa los
    //    campos en orden de declaración: ... name ... sourceUrl sourceSite).
    return try {
        val siteIdx = addonsJson.indexOf("\"sourceSite\"")
        if (siteIdx < 0) return null
        val site = Regex("\"sourceSite\"\\s*:\\s*\"([^\"]+)\"")
            .find(addonsJson, siteIdx)?.groupValues?.get(1) ?: return null
        val urlRe = Regex("\"sourceUrl\"\\s*:\\s*\"([^\"]+)\"")
        val urlMatch = urlRe.findAll(addonsJson).lastOrNull { it.range.first < siteIdx }
        val url = urlMatch?.groupValues?.get(1) ?: return null
        val nameRe = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"")
        val name = nameRe.findAll(addonsJson)
            .lastOrNull { it.range.first < urlMatch.range.first }
            ?.groupValues?.get(1) ?: ""
        ModpackSource(site, url, name)
    } catch (e: Exception) {
        null
    }
}
