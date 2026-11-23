package com.packforge.app.ui.screens

import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.packforge.app.R
import androidx.compose.ui.res.stringResource
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import com.packforge.app.ui.components.bounceClick
import com.packforge.app.ui.components.ExpressiveAlertDialog
import com.packforge.app.ui.components.ExpressiveDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.packforge.app.ui.components.CachedAsyncImage
import com.packforge.app.ui.components.AddonSite
import com.packforge.app.ui.components.BlockLetterWord
import com.packforge.app.ui.components.CoverColorExtractor
import com.packforge.app.ui.components.ModpackParsingCache
import com.packforge.app.ui.components.ModpackImportDialog
import com.packforge.app.data.PackForgeDatabase
import com.packforge.app.domain.engine.AddonParser
import com.packforge.app.ui.theme.ExpressiveElevation
import com.packforge.app.ui.theme.ExpressiveShapes
import com.packforge.app.ui.theme.ExpressiveSpacing
import com.packforge.app.ui.theme.ModpackCardMinWidth
import com.packforge.app.ui.theme.MotionLevel
import com.packforge.app.ui.theme.currentMotionLevel
import com.packforge.app.ui.theme.mediumTweenOf
import com.packforge.app.ui.theme.rememberExpressiveHaptics
import com.packforge.app.ui.theme.snappySpringOf
import com.packforge.app.util.PackForgeLog
import com.packforge.app.domain.model.OperationProgress
import com.packforge.app.domain.model.SavedModpackSummary
import com.packforge.app.domain.model.Addon
import com.packforge.app.domain.model.AddonUpdateState
import com.packforge.app.domain.model.ModpackUpdateReport
import com.packforge.app.domain.model.UpdatePhase
import com.packforge.app.domain.model.withSafeLists
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
        // ⚡ PAUSA DE WEBS EN SEGUNDO PLANO:
        // al salir del navegador, su WebView se detiene. Una WebView oculta
        // sigue ejecutando JavaScript, animaciones CSS y temporizadores, así que
        // las 3 fuentes visitadas dejaban tres páginas activas compitiendo por la
        // CPU. Eso era la causa de que "Mis modpacks" fuera trabado sin importar
        // cuántos modpacks hubiera. Al volver a entrar se reactivan.
        DisposableEffect(source) {
            viewModel.resumeAllWebViews()
            onDispose { viewModel.pauseAllWebViews() }
        }

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

    // ═══ DIÁLOGO DE IMPORTACIÓN DE MODPACK A LA BIBLIOTECA ═══
    if (importProgress !is OperationProgress.Idle) {
        ModpackImportDialog(
            progress = importProgress,
            onDismiss = { viewModel.dismissImportProgress() }
        )
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
        // ═══ DIÁLOGOS DE LA BIBLIOTECA — EXCLUSIVIDAD MUTUA ═══
        //
        // El de regenerar y el centro de actualizaciones se componían AMBOS con
        // `let`, sin nada que impidiera tenerlos abiertos a la vez. Como cada uno
        // abre su propia ventana de Android con su propio scrim, quedaban dos
        // ventanas apiladas: la de arriba se quedaba con la entrada y la de abajo
        // seguía visible sin poder cerrarse. Es el "menú que se traba al
        // superponerse" llevado al caso más visible, porque la biblioteca es la
        // pantalla con más diálogos de la app.
        //
        // Se da prioridad a la regeneración (acción destructiva que el usuario
        // acaba de confirmar) y el centro de actualizaciones espera a que se
        // cierre.
        modpackToRegenerate?.let { target ->
            ExpressiveAlertDialog(
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
        //
        // Solo se compone si no hay una regeneración pendiente: ver la nota de
        // exclusividad mutua de arriba.
        if (modpackToRegenerate == null) {
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
    val haptics = rememberExpressiveHaptics()
    var showInfoBanner by remember { mutableStateOf(true) }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(ExpressiveSpacing.listGap),
        contentPadding = PaddingValues(
            start = ExpressiveSpacing.screenMargin,
            end = ExpressiveSpacing.screenMargin,
            top = ExpressiveSpacing.screenMargin,
            bottom = 96.dp
        )
    ) {
        // ── Línea de contexto ───────────────────
        // Sin título aquí: el nombre de la pantalla vive en la TopBar (como
        // wordmark de bloques). Repetirlo dentro creaba el mismo texto dos veces
        // en la misma vista, que es justo lo que se veía duplicado.
        item {
            Text(
                text = "Gestiona tus modpacks y accede a fuentes",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // ── Stats Compacto Horizontal Unificado ─────────────────
        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Modpacks
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .bounceClick(scaleDown = 0.96f, hapticFeedback = false) {
                                haptics.light()
                                viewModel.setShowMyModpacks(true)
                            }
                            .padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (savedModpacks.isNotEmpty()) "${savedModpacks.size}" else "—",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Modpacks",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    VerticalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    // Addons
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "${savedModpacks.sumOf { it.addonCount }}",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Text(
                            text = "Addons",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    VerticalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    // Fuentes
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .bounceClick(scaleDown = 0.96f, hapticFeedback = false) {
                                haptics.light()
                                // Scroll hacia abajo para ver fuentes
                            }
                            .padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "3",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                        Text(
                            text = "Fuentes",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // ── Mis Modpacks (Hero principal con háptica HEAVY) ─────────────────
        item {
            StudioCard(
                icon = Icons.Default.Folder,
                title = "Mis Modpacks",
                description = "Edita, comparte o borra tus modpacks guardados",
                badge = if(savedModpacks.isNotEmpty()) savedModpacks.size.toString() else null,
                accent = MaterialTheme.colorScheme.primary,
                onClickHaptic = { haptics.heavy() }
            ) {
                viewModel.setShowMyModpacks(true)
            }
        }

        // ── Ajustes de Tema (Tarjeta secundaria con háptica MEDIUM) ─────────────────
        item {
            StudioCard(
                icon = Icons.Default.Settings,
                title = "Ajustes de Tema",
                description = "Modo oscuro, color de acento y movimiento",
                badge = null,
                accent = MaterialTheme.colorScheme.tertiary,
                onClickHaptic = { haptics.medium() }
            ) {
                viewModel.setShowThemeSettings(true)
            }
        }

        // ── Importar Modpack (Botón destacado pill con háptica MEDIUM) ─────────────────
        item {
            Button(
                onClick = {
                    haptics.medium()
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
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Importar Modpack",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // ── Carrusel Horizontal de Fuentes (con LOGOS REALES) ─────────────────
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Fuentes Bedrock",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 0.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Se recorren los sitios REALES del enum (misma fuente de
                    // verdad que usa el ViewModel), así que el logo, el nombre y
                    // la clave de navegación nunca se desincronizan.
                    items(AddonSite.entries.size) { idx ->
                        val site = AddonSite.entries[idx]
                        val selected = AddonSite.fromSourceKey(activeWebSource) == site

                        // Contenedor TRANSPARENTE con el borde del color de la web.
                        // Se descarta `ElevatedCard`: su relleno opaco tapaba el
                        // fondo y competía con el logo. Aquí el color que
                        // identifica al sitio es el borde, dejando ver el fondo.
                        Surface(
                            modifier = Modifier
                                .width(124.dp)
                                .bounceClick(scaleDown = 0.94f, hapticFeedback = false) {
                                    haptics.medium()
                                    viewModel.setActiveWebSource(site.sourceKey)
                                },
                            shape = ExpressiveShapes.card,
                            color = if (selected) {
                                // Seleccionado: tinte muy leve del color del sitio,
                                // manteniendo el fondo visible.
                                site.fallbackColor.copy(alpha = 0.12f)
                            } else {
                                Color.Transparent
                            },
                            border = BorderStroke(
                                width = if (selected) 2.dp else 1.5.dp,
                                color = site.fallbackColor.copy(
                                    alpha = if (selected) 0.95f else 0.55f
                                )
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 14.dp, horizontal = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Logo OFICIAL del sitio (favicon real), en squircle
                                // transparente: se apoya sobre el fondo de la tarjeta.
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = Color.Transparent,
                                    modifier = Modifier.size(46.dp)
                                ) {
                                    AsyncImage(
                                        // ⚡ MEMOIZADO por sitio: construir el
                                        // `ImageRequest` en cada composición
                                        // asignaba un objeto nuevo y obligaba a
                                        // Coil a rehacer el pipeline de la
                                        // imagen cada vez que el carrusel se
                                        // recomponía (al scrollear el Studio).
                                        //
                                        // `LocalContext.current` se lee AQUÍ, en
                                        // contexto @Composable: dentro de la
                                        // lambda de `remember` no se puede.
                                        model = siteLogoRequest(site),
                                        contentDescription = stringResource(
                                            R.string.cd_logo_of,
                                            site.displayName
                                        ),
                                        modifier = Modifier
                                            .padding(6.dp)
                                            .clip(RoundedCornerShape(11.dp)),
                                        placeholder = ColorPainter(
                                            site.fallbackColor.copy(alpha = 0.35f)
                                        ),
                                        error = ColorPainter(
                                            site.fallbackColor.copy(alpha = 0.35f)
                                        )
                                    )
                                }
                                Text(
                                    text = site.displayName,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (selected) {
                                        site.fallbackColor
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── Banner de Tips Descartable ─────────────────
        if (showInfoBanner) {
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Lightbulb,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Navega cualquier fuente, toca 'Descargar' y PackForge detecta el archivo automáticamente.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        // Sin `.size(24.dp)`: Material reserva 48dp de
                        // objetivo táctil para el botón de cerrar el banner.
                        IconButton(
                            onClick = { showInfoBanner = false }
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Descartar",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
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
    val haptics = rememberExpressiveHaptics()
    Surface(
        shape = ExpressiveShapes.statChip,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        // El chip responde al toque aunque no navegue: refuerza que la fila es
        // informativa y a la vez "viva".
        modifier = modifier.bounceClick(scaleDown = 0.94f) { haptics.light() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(17.dp)
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
    onClickHaptic: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val haptics = rememberExpressiveHaptics()
    val motionLevel = currentMotionLevel
    var pressed by remember { mutableStateOf(false) }

    // Al pulsar, la tarjeta se hunde (baja la elevación) en vez de saltar: es la
    // lectura Expressive de "acuse de recibo". Con movimiento reducido se
    // mantiene la elevación para no introducir parpadeo.
    val elevation by animateDpAsState(
        targetValue = if (pressed && motionLevel != MotionLevel.NONE) {
            ExpressiveElevation.cardPressed
        } else {
            ExpressiveElevation.raised
        },
        animationSpec = snappySpringOf(motionLevel),
        label = "studioCardElevation"
    )

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = accent.copy(alpha = if (pressed) 0.55f else 0.30f),
                shape = ExpressiveShapes.heroCard
            )
            .bounceClick(
                scaleDown = 0.96f,
                hapticFeedback = false,
                onPressChanged = { pressed = it }
            ) {
                // Ejecutar el háptico personalizado si existe, si no usar light()
                if (onClickHaptic != null) {
                    onClickHaptic()
                } else {
                    haptics.light()
                }
                onClick()
            }
            .semantics(mergeDescendants = true) {
                // Tarjeta como botón grande y navegable: TalkBack anuncia
                // qué hace y el título asociado.
                role = Role.Button
                contentDescription = "$title: $description"
            },
        shape = ExpressiveShapes.heroCard,
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = elevation),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            Modifier.padding(ExpressiveSpacing.heroContent),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(ExpressiveShapes.iconContainer)
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(27.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
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
                tint = accent.copy(alpha = 0.75f)
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
    isLoading: Boolean = false,
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
    val haptics = rememberExpressiveHaptics()
    var modpackToDelete by remember { mutableStateOf<SavedModpackSummary?>(null) }
    var showShareDialog by remember { mutableStateOf(false) }

    // ═══ ESTADO DE MENÚS Y DIÁLOGOS — A NIVEL DE PANTALLA ═══
    //
    // Ni el menú "⋮" de una tarjeta ni el visor de addons pueden guardar su
    // estado dentro de la tarjeta: la tarjeta es un ítem de la grilla perezosa y
    // se recicla al scrollear. Con el estado dentro, (a) el menú/ventana quedaba
    // abierto sin dueño al reciclar el ítem —el "se traban cuando se sobreponen"
    // reportado— y (b) al reutilizarse el ítem para OTRO modpack el menú
    // reaparecía sobre la tarjeta equivocada. Aquí, en cambio, el estado tiene la
    // misma vida que la pantalla.
    //
    // Se guarda el ID (no el objeto completo) del modpack con el menú abierto:
    // así, si la lista se recarga, el menú simplemente se cierra en vez de
    // apuntar a una tarjeta que ya no existe.
    var openMenuModpackId by remember { mutableStateOf<String?>(null) }
    var addonsDialogModpack by remember { mutableStateOf<SavedModpackSummary?>(null) }

    // ═══ DATOS DE TARJETA PRECALCULADOS — LA SOLUCIÓN DEFINITIVA AL LAG ═══
    //
    // Antes cada tarjeta lanzaba SUS PROPIAS corrutinas al entrar en el viewport
    // (`LaunchedEffect` por tarjeta) para: (a) parsear hasta 64 KB de JSON
    // buscando el origen web y (b) decodificar la portada y extraer su color
    // dominante. Con un deslizamiento rápido eso eran 6-10 parses concurrentes
    // POR FOTOGRAMA compitiendo por CPU con el hilo de render: el jank no venía
    // del hilo principal sino de la saturación de núcleos, y por eso las
    // optimizaciones anteriores del hilo principal no bastaron.
    //
    // La alternativa completa: el trabajo se hace UNA vez por modpack, AQUÍ a
    // nivel de pantalla y FUERA del recorrido del scroll. La grilla solo LEE
    // resultados listos; la tarjeta es un composable puro sin corrutinas.
    //
    // Detalles que lo hacen correcto:
    //  · `mutableStateMapOf` observa POR CLAVE: cuando termina el cálculo de un
    //    modpack solo se recompose SU tarjeta, no la grilla entera.
    //  · Mientras el dato no está (primer arranque), la tarjeta se pinta al
    //    instante con sus respaldos (borde del tema, sin badge, sin fecha) y se
    //    completa al llegar el cálculo: la composición NUNCA espera.
    //  · El llenado es SECUENCIAL y en orden de lista (las visibles primero):
    //    no lanza N parses en paralelo que compitan entre sí.
    //  · Reabrir la pantalla es casi gratis: `extractModpackSource` y
    //    `CoverColorExtractor.extract` tienen caché propia por contenido.
    val cardData = remember { mutableStateMapOf<String, ModpackCardUi>() }
    LaunchedEffect(modpacks) {
        val appContext = context.applicationContext

        // Limpieza: si un modpack fue borrado, su dato precalculado no sirve
        // ya y solo ocuparía memoria (y crecería con cada borrado/creación).
        val validIds = modpacks.mapTo(mutableSetOf()) { it.id }
        cardData.keys.removeAll { it !in validIds }

        for (modpack in modpacks) {
            val existing = cardData[modpack.id]
            if (existing != null && existing.summary == modpack) continue

            // Origen web: parseo del head de 64 KB en Default.
            val source = if (modpack.sourceJsonHead.isBlank()) null
            else withContext(Dispatchers.Default) { extractModpackSource(modpack.sourceJsonHead) }

            // Color dominante de la portada: IO + decodificado a 32 px.
            val coverColor = CoverColorExtractor.extract(appContext, modpack.coverUriString)

            cardData[modpack.id] = ModpackCardUi(
                summary = modpack,
                source = source,
                coverColor = coverColor,
                versionLine = buildVersionLine(modpack),
                coverModel = resolveCoverModel(modpack.coverUriString)
            )
        }
    }

    // ═══ VISOR DE ADDONS — FUERA DE LA LISTA ═══
    // El `addonsJson` completo NO vive en el summary ligero (leerlo de todas las
    // filas reventaba el CursorWindow), así que se pide la fila completa por id
    // en segundo plano al abrir el visor: es un evento puntual, no parte del scroll.
    //
    // `modpackToDelete == null` implementa la exclusividad mutua: si el usuario
    // pide borrar mientras el visor está abierto, solo se compone la confirmación
    // de borrado. Sin esta guarda habría dos ventanas de diálogo apiladas y la de
    // abajo quedaría visible pero inalcanzable —el "menú trabado".
    //
    // Además cede frente al menú de compartir (`showShareDialog`): si el usuario
    // está eligiendo un modpack para compartir y mientras tanto pulsa "Ver addons",
    // solo se muestra el visor de compartir.
    addonsDialogModpack?.takeIf {
        modpackToDelete == null && !showShareDialog
    }?.let { target ->
        val db = remember { PackForgeDatabase.getInstance(context.applicationContext) }
        var dialogAddons by remember(target.id) { mutableStateOf<List<Addon>?>(null) }
        LaunchedEffect(target.id) {
            dialogAddons = withContext(Dispatchers.IO) {
                val full = db.savedModpackDao().getById(target.id)
                val parsed = decodeModpackAddons(full?.addonsJson.orEmpty())
                // Los iconos se cachean en filesDir/addon_icons. Si el sistema
                // limpió la caché, `iconPath` apunta a un archivo que ya no
                // existe y la portada NO se ve: Coil recibe una ruta muerta.
                // Aquí se re-extrae del addon fuente, igual que hace
                // `hydrateModpackForEditing` al abrir el editor.
                // En PARALELO: cada recuperación abre el ZIP del addon, así que
                // hacerlo en serie multiplicaba la espera por el número de addons.
                coroutineScope {
                    parsed.map { addon ->
                        async<Addon> {
                            val cachedIcon = addon.iconPath
                            val exists = cachedIcon != null &&
                                runCatching { File(cachedIcon).exists() }.getOrDefault(false)
                            if (exists || addon.sourceFilePath.isBlank()) {
                                addon
                            } else {
                                val recovered = AddonParser.recoverIconFromSource(
                                    addon.sourceFilePath,
                                    addon.id,
                                    context.applicationContext
                                )
                                if (recovered != null) addon.copy(iconPath = recovered) else addon
                            }
                        }
                    }.awaitAll()
                }
            }
        }

        if (dialogAddons != null) {
            ModpackAddonsDialog(
                modpackName = target.name,
                addons = dialogAddons!!,
                onDismiss = { addonsDialogModpack = null }
            )
        } else {
            // Mientras carga la fila completa: ventana con indicador, para que
            // el toque tenga respuesta inmediata en lugar de no hacer nada.
            ExpressiveDialog(onDismissRequest = { addonsDialogModpack = null }) {
                Surface(
                    shape = ExpressiveShapes.dialog,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier.padding(24.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Leyendo addons...", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    // Diálogo con bordes de colores para compartir modpacks
    if (showShareDialog) {
        ExpressiveDialog(
            onDismissRequest = { showShareDialog = false }
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                shape = ExpressiveShapes.dialog,
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
                shadowElevation = ExpressiveElevation.dialog
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
                                        .bounceClick(scaleDown = 0.97f, hapticFeedback = false) {
                                            haptics.medium()
                                            shareModpack(context, modpack, shareScope)
                                            showShareDialog = false
                                        },
                                    shape = ExpressiveShapes.container,
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Surface(
                                            shape = ExpressiveShapes.action,
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

    // ═══ CARGA DE LA PANTALLA — BARRA DE CARGA ═══
    // La pantalla completa (grilla + contenido) se carga de forma secuencial:
    // 1. Se recibe la lista de modpacks (puede estar vacía inicialmente)
    // 2. Si `isLoading` es true, se muestra la barra de carga hasta que los datos
    //    estén completamente listos para mostrar.
    // 3. Solo cuando `isLoading` es false se renderiza la grilla real.
    //    Esto garantiza que el usuario no vea contenido parcial durante el
    //    primer render, eliminando el jank visible en la implementación anterior.
    if (isLoading) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp
                )
                Text(
                    "Cargando tu biblioteca...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        return
    }

    Scaffold(
        topBar = {
            // Barra flotante superior con efecto de vidrio
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shadowElevation = ExpressiveElevation.bar
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
        // Sin FAB: sus tres acciones ya viven en la propia pantalla y el botón
        // flotante competía por la atención con las tarjetas. "Importar" está
        // en el Hub, "Explorar fuentes" en el carrusel y "Compartir" en el menú
        // de cada tarjeta, así que aquí solo estorbaba.
        floatingActionButton = {}
    ) { padding ->
        if (modpacks.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.FolderOpen, null, Modifier.size(64.dp).alpha(0.4f))
                    Text(stringResource(R.string.studio_no_modpacks), style = MaterialTheme.typography.titleMedium, modifier = Modifier.alpha(0.6f))
                    Text(
                        "Explora una fuente o importa un archivo para crear tu primer modpack",
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
            // ── GRILLA RESPONSIVA ──
            // `Adaptive` calcula las columnas por ancho disponible: una sola
            // tarjeta horizontal a ancho completo en móvil (donde el contenido
            // necesita todo el espacio) y dos o más en tablet o plegable
            // desplegado, sin ramas por tipo de dispositivo.
            // El scroll lo aporta la propia grilla; `LazyColumn` no puede
            // anidarse dentro de una lista lazy.
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = ModpackCardMinWidth),
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(ExpressiveSpacing.listGap),
                horizontalArrangement = Arrangement.spacedBy(ExpressiveSpacing.listGap),
                contentPadding = PaddingValues(
                    start = ExpressiveSpacing.screenMargin,
                    end = ExpressiveSpacing.screenMargin,
                    top = ExpressiveSpacing.screenMargin,
                    bottom = 96.dp
                )
            ) {
                gridItems(
                    items = modpacks,
                    key = { it.id },
                    contentType = { "modpack" }
                ) { modpack ->
                    // Las lambdas se memorizan por id para no recrearlas en cada
                    // recomposición, y leen el valor ACTUAL de los callbacks vía
                    // `rememberUpdatedState`: sin esto capturaban la versión del
                    // primer render y, tras un guardado o un borrado, la tarjeta
                    // seguía invocando una lambda con el estado viejo.
                    val currentOnLoad by rememberUpdatedState(onLoad)
                    val currentOnBack by rememberUpdatedState(onBack)
                    val currentOnRegenerate by rememberUpdatedState(onRegenerate)
                    val currentOnCheckUpdates by rememberUpdatedState(onCheckUpdates)
                    val currentOnOpenSource by rememberUpdatedState(onOpenSource)
                    val currentModpack by rememberUpdatedState(modpack)
                    val currentContext by rememberUpdatedState(context)
                    val currentShareScope by rememberUpdatedState(shareScope)

                    val onLoadThis = remember(modpack.id) {
                        { currentOnLoad(currentModpack); currentOnBack() }
                    }
                    val onDeleteThis = remember(modpack.id) {
                        {
                            // Cerrar el menú antes de abrir el diálogo: el menú es
                            // otra ventana flotante y, si sigue abierta, compite
                            // con el scrim del diálogo.
                            openMenuModpackId = null
                            modpackToDelete = modpack
                        }
                    }
                    val onShareThis = remember(modpack.id) {
                        { shareModpack(currentContext, currentModpack, currentShareScope) }
                    }
                    val onRegenThis = remember(modpack.id) { { currentOnRegenerate(modpack) } }
                    val onCheckUpdatesThis = remember(modpack.id) {
                        { currentOnCheckUpdates(modpack.id) }
                    }
                    // ⚡ Menú "⋮" con invalidación POR TARJETA.
                    //
                    // Leer `openMenuModpackId == modpack.id` directamente dentro
                    // del ítem hacía que CADA cambio del id abierto recompusiera
                    // TODAS las tarjetas visibles (todas leen el mismo estado).
                    // Con un `derivedStateOf` por tarjeta, cada una solo se
                    // invalida cuando SU propio booleano gira: abrir o cerrar el
                    // menú recompone una tarjeta, no diez.
                    val menuOpenForThis by remember(modpack.id) {
                        derivedStateOf { openMenuModpackId == modpack.id }
                    }
                    val checkingThis = isCheckingUpdates && checkingUpdatesForId == modpack.id

                    // Datos PRECALCULADOS a nivel de pantalla (ver arriba): la
                    // lectura es por clave y solo dispara la recomposición de
                    // ESTA tarjeta cuando su dato llega o cambia. Durante el
                    // scroll no hay corrutinas ni parseos: todo ya está listo.
                    val cardUi = cardData[modpack.id]

                    ModpackLibraryCard(
                        modpack = modpack,
                        cardUi = cardUi,
                        onLoad = onLoadThis,
                        onDelete = onDeleteThis,
                        onShare = onShareThis,
                        onRegenerate = onRegenThis,
                        onOpenSource = { cardUi?.source?.let { currentOnOpenSource(it.site, it.url) } },
                        onCheckUpdates = onCheckUpdatesThis,
                        onShowAddons = {
                            openMenuModpackId = null
                            addonsDialogModpack = modpack
                        },
                        // Estado del menú controlado por la pantalla: sobrevive al
                        // reciclado del ítem y garantiza que solo UNA tarjeta
                        // tenga su menú abierto a la vez.
                        menuOpen = menuOpenForThis,
                        onMenuOpenChange = { open ->
                            openMenuModpackId = if (open) modpack.id else null
                        },
                        isCheckingUpdates = checkingThis
                        // Sin `animateItem()`: animaba la entrada y el
                        // reordenamiento de CADA tarjeta, y con una lista corta
                        // eso añade trabajo de animación por fotograma sin
                        // aportar nada visible durante el desplazamiento.
                    )
                }
            }
        }
    }

    // ═══ EXCLUSIVIDAD MUTUA DE DIÁLOGOS ═══
    //
    // Cada `ExpressiveDialog`/`ExpressiveAlertDialog` abre su PROPIA ventana de
    // Android, con su propio scrim que captura todos los toques. Con flags
    // independientes nada impedía tener dos en `true` a la vez, y entonces quedan
    // dos ventanas apiladas: la de arriba roba la entrada y la de abajo sigue
    // visible pero inalcanzable. Ese es el síntoma "se traban cuando se
    // sobreponen sobre otros".
    //
    // La exclusividad se garantiza por CONSTRUCCIÓN: el visor de addons solo se
    // compone si no hay confirmación de borrado pendiente (ver la guarda
    // `takeIf { modpackToDelete == null }` en el bloque del visor). El borrado
    // gana porque es la acción que el usuario acaba de pedir y la única con
    // consecuencia destructiva.
    modpackToDelete?.let { m ->
        ExpressiveAlertDialog(
            onDismissRequest = { modpackToDelete = null },
            title = { Text(stringResource(R.string.studio_delete_title)) },
            text = { Text(stringResource(R.string.studio_delete_message, m.name)) },
            confirmButton = {
                TextButton(onClick = {
                    haptics.heavy()
                    onDelete(m.id)
                    modpackToDelete = null
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { modpackToDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
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
    val haptics = rememberExpressiveHaptics()

    // ⚡ Color DIRECTO, sin `animateColorAsState`.
    //
    // Antes cada botón instalaba un `Animatable<Color>` con su corrutina y su
    // observador de estado: tres por tarjeta, decenas vivas durante el scroll,
    // para un color que en la práctica NUNCA cambia (solo "Actualizar" se
    // resalta mientras consulta las fuentes, un caso puntual). El cambio
    // instantáneo es imperceptible en un botón de 36 dp y cuesta cero
    // fotogramas.
    val containerColor = if (isHighlighted)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceContainerHighest
    val contentColor = if (isHighlighted)
        MaterialTheme.colorScheme.onPrimaryContainer
    else
        MaterialTheme.colorScheme.onSurfaceVariant

    // `Surface(onClick = ...)` ya aporta el gesto Y el ripple de Material, que
    // es la respuesta táctil estándar. Antes se le sumaba `bounceClick(0.9f)` —
    // un `Modifier.composed` con interaction source, `collectIsPressedAsState` y
    // un `Animatable` POR BOTÓN— solo para el gesto de compresión: tres por
    // tarjeta, multiplicado por las tarjetas visibles durante el scroll.
    Surface(
        onClick = {
            haptics.light()
            onClick()
        },
        enabled = !isSpinning,
        shape = ExpressiveShapes.action,
        color = containerColor,
        modifier = modifier.height(36.dp)
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
    /**
     * Datos PRECALCULADOS a nivel de pantalla (`ModpackCardUi`), o `null`
     * mientras el cálculo de fondo no haya llegado.
     *
     * La tarjeta es un composable PURO: no lanza corrutinas, no parsea JSON ni
     * decodifica imágenes para extraer color. Todo eso se hizo una vez por
     * modpack en `MyModpacksScreen`, fuera del recorrido del scroll. Con `null`
     * se pintan los respaldos (borde del tema, sin badge, línea sin fecha) y el
     * dato completo llega un instante después sin bloquear la composición.
     */
    cardUi: ModpackCardUi? = null,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onRegenerate: () -> Unit = {},
    onOpenSource: () -> Unit = {},
    onCheckUpdates: () -> Unit = {},
    onShowAddons: () -> Unit = {},
    isCheckingUpdates: Boolean = false,
    /**
     * Estado del menú "⋮" CONTROLADO POR LA PANTALLA, no local a la tarjeta.
     *
     * El `DropdownMenu` debe seguir viviendo dentro de la tarjeta porque ancla su
     * posición al `Box` del botón; si se moviera al nivel de pantalla aparecería
     * descolgado, en el centro. Lo que se iza es solo el ESTADO.
     *
     * Con el estado dentro de la tarjeta (un `remember` local), y siendo la
     * tarjeta un ítem de una lista perezosa, al scrollear la grilla recicla el
     * ítem: el estado volvía a `false` con la ventana del menú todavía abierta,
     * dejando un menú huérfano y sin dueño que lo cerrara —el "menú trabado"— y,
     * peor, al reutilizarse el ítem para otro modpack el menú reaparecía sobre la
     * tarjeta equivocada. Con el estado en la pantalla, la tarjeta solo lo informa.
     */
    menuOpen: Boolean = false,
    onMenuOpenChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val haptics = rememberExpressiveHaptics()

    // ═══ TARJETA PURA — CERO TRABAJO POR COMPOSICIÓN ═══
    //
    // Todo lo que aquí se calculaba (resolver el File de la portada, lanzar una
    // corrutina por tarjeta para extraer el color dominante, parsear el origen
    // web) vive ahora en `ModpackCardUi`, precalculado UNA vez por modpack en
    // `MyModpacksScreen`, fuera del recorrido del scroll. La tarjeta solo lee.
    //
    // Con `cardUi == null` (dato aún en camino) se usan los respaldos: borde del
    // acento del tema, sin badge de origen y línea de versión sin fecha. La
    // tarjeta nunca se bloquea esperando un cálculo.
    val modpackSource = cardUi?.source
    val coverModel: Any? = cardUi?.coverModel
    val coverColor = cardUi?.coverColor
    val borderColor = coverColor ?: MaterialTheme.colorScheme.primary

    // Visor de addons: el ESTADO vive en la pantalla (ver MyModpacksScreen), no
    // aquí. Antes este `remember` y el `ModpackAddonsDialog` estaban DENTRO de
    // la tarjeta, que es un ítem de una lista perezosa: al scrollear, la grilla
    // recicla el ítem, la tarjeta se recompone con `showAddonsDialog = false` y
    // el `Dialog` —que ya había abierto su propia ventana de Android— quedaba
    // huérfano, sin dueño que lo cerrara. Ese es el "menú que se traba".
    // Ahora la tarjeta solo AVISA de que se pulsó "Ver addons".

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

    // `Surface` en lugar de `ElevatedCard`: la sombra de `ElevatedCard`
                    // obliga a RenderNode a dibujar y recortar un desenfoque por
                    // cada tarjeta en cada fotograma, y era el mayor coste
                    // gráfico del desplazamiento. El borde ya identifica la
                    // tarjeta con el color de su portada, así que la sombra no
                    // aportaba nada y sí se notaba como tirones.
                    //
                    // El `animateDpAsState` de elevación también desaparece: sin
                    // sombra no hay nada que animar, y animar una elevación por
                    // tarjeta era otra fuente de trabajo por fotograma.
                    Surface(
                        modifier = modifier
                            .fillMaxWidth()
                            .border(
                                width = if (coverColor != null) 1.5.dp else 1.dp,
                                color = borderColor.copy(alpha = 0.45f),
                                shape = ExpressiveShapes.card
                            )
                            // ⚡ `clickable(interactionSource = null, ...)`: la
                            // sobrecarga con `null` ni siquiera asigna el
                            // interaction source. Antes aquí iba `bounceClick`:
                            // con `scaleDown = 1f` no deformaba nada visible,
                            // pero su `Modifier.composed` se instanciaba por
                            // tarjeta en cada composición del scroll.
                            .clickable(
                                interactionSource = null,
                                indication = null
                            ) {
                                haptics.light()
                                onLoad()
                            }
                            .semantics(mergeDescendants = true) {
                                // Tarjeta de biblioteca: botón navegable con
                                // nombre y cantidad de addons.
                                role = Role.Button
                                contentDescription =
                                    "Modpack ${modpack.name}, ${modpack.addonCount} addons"
                            },
                        shape = ExpressiveShapes.card,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        // Elevación tonal (sin sombra difusa): mantiene la
                        // jerarquía visual sin coste de desenfoque.
                        tonalElevation = 2.dp
                    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(ExpressiveSpacing.cardContent),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ── Portada grande con badges superpuestos ─────────────────
            Box(
                modifier = Modifier
                    .size(118.dp)
                    .clip(ExpressiveShapes.cover)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                when {
                    coverModel != null -> CachedAsyncImage(
                        model = coverModel,
                        contentDescription = stringResource(R.string.cd_cover_of, modpack.name),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        cornerRadius = 20.dp,
                        // La portada se dibuja en 118dp: que Coil la decodifique
                        // ya a ese tamaño evita cargar la imagen completa.
                        decodeSize = 118.dp,
                        // El Box padre YA recorta con la misma forma (20dp): el
                        // clip interno sería un segundo outline+canvas por
                        // portada en cada fotograma del scroll.
                        clip = false
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
                            onClick = { onMenuOpenChange(true) },
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
                            onDismissRequest = { onMenuOpenChange(false) }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Ver addons (${modpack.addonCount})") },
                                leadingIcon = {
                                    Icon(
                                        Icons.AutoMirrored.Filled.List,
                                        null,
                                        Modifier.size(18.dp)
                                    )
                                },
                                onClick = {
                                    onMenuOpenChange(false)
                                    haptics.light()
                                    onShowAddons()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Compartir") },
                                leadingIcon = { Icon(Icons.Default.Share, null, Modifier.size(18.dp)) },
                                onClick = { onMenuOpenChange(false); haptics.medium(); onShare() }
                            )
                            if (modpackSource != null) {
                                DropdownMenuItem(
                                    text = { Text("Abrir origen (${modpackSource.site})") },
                                    leadingIcon = {
                                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp))
                                    },
                                    onClick = { onMenuOpenChange(false); haptics.light(); onOpenSource() }
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
                                // Háptico de rechazo: avisa de que la acción es destructiva
                                // antes de que aparezca el diálogo de confirmación.
                                onClick = { onMenuOpenChange(false); haptics.reject(); onDelete() }
                            )
                        }
                    }
                }

                // Versión + fecha en una sola línea. PRECALCULADA en
                // `ModpackCardUi`: formatear la fecha con `SimpleDateFormat` por
                // tarjeta y por composición asignaba buffers justo cuando la
                // tarjeta entra al viewport. El respaldo sin fecha solo se ve
                // durante el instante en que el cálculo de fondo llega.
                Text(
                    text = cardUi?.versionLine
                        ?: "v${modpack.version} · MC ${modpack.mcVersion}",
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
        // UN solo formato compartido: crear `SimpleDateFormat` por tarjeta y
        // por pasada de composición inicializa datos de locale en el hilo
        // principal justo cuando una tarjeta entra al viewport durante el
        // scroll. Se usa exclusivamente desde el hilo principal (Composable),
        // donde SimpleDateFormat no necesita sincronización.
        ModpackDateCache.format.format(Date(timestamp))
    } catch (e: Exception) {
        ""
    }
}

/** Inicialización cara amortizada fuera del hot path de scroll. */
private object ModpackDateCache {
    val format = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
}

/**
 * Reutiliza el `ImageRequest` del favicon por sitio, evitando reconstruir el
 * pipeline de Coil en cada composición del carrusel horizontal.
 */
@Composable
private fun siteLogoRequest(site: AddonSite): Any {
    val context = LocalContext.current
    return remember(site, context) {
        ImageRequest.Builder(context)
            .data(site.logoUrl)
            .size(34 * 3)
            .crossfade(false)
            .build()
    }
}

/** Origen web de un addon dentro de un modpack guardado. */
@Immutable
data class ModpackSource(
    val site: String,      // "MCPEDL" | "CurseForge" | "ModBay"
    val url: String,       // URL de la página del addon
    val addonName: String  // nombre del addon para mostrar en el botón
)

/**
 * Datos de una tarjeta de la biblioteca PRECALCULADOS una vez por modpack.
 *
 * Es la pieza central de la solución al lag del scroll: todo lo que antes se
 * calculaba DENTRO de la tarjeta (y por tanto durante el deslizamiento) ahora
 * se calcula a nivel de pantalla, fuera del recorrido del scroll:
 *
 *  · [source] — parseo del head de 64 KB del `addonsJson` (antes: una corrutina
 *    con Gson + regexes POR TARJETA cada vez que entraba al viewport).
 *  · [coverColor] — decodificado de la portada a 32 px y extracción del color
 *    dominante (antes: otra corrutina con I/O por tarjeta).
 *  · [versionLine] — la línea "v1.2 · MC 1.20.10 · 12 ene 2025" ya formateada
 *    (antes: `SimpleDateFormat.format` por tarjeta y por composición).
 *  · [coverModel] — el `File`/URI que Coil necesita, resuelto una vez.
 *
 * `@Immutable` + igualdad estructural: `MyModpacksScreen` compara el summary
 * guardado con el actual para decidir si recalcular, así que reabrir la
 * pantalla o recargar la lista no repite ningún trabajo.
 */
@Immutable
data class ModpackCardUi(
    val summary: SavedModpackSummary,
    val source: ModpackSource?,
    val coverColor: Color?,
    val versionLine: String,
    val coverModel: Any?
)

/** Línea de versión+MC+fecha de una tarjeta, formateada UNA vez por modpack. */
private fun buildVersionLine(modpack: SavedModpackSummary): String = buildString {
    append("v")
    append(modpack.version)
    append(" · MC ")
    append(modpack.mcVersion)
    val date = formatModpackDate(modpack.createdAt)
    if (date.isNotBlank()) {
        append(" · ")
        append(date)
    }
}

/**
 * Resuelve el modelo que Coil necesita para la portada, UNA vez por modpack.
 * Coil quiere un `File` (no un String de ruta absoluta) para la portada
 * persistida en almacenamiento interno; un `content://` se pasa tal cual.
 */
private fun resolveCoverModel(coverPath: String?): Any? = when {
    coverPath == null -> null
    coverPath.startsWith("/") -> File(coverPath)
    coverPath.startsWith("file://") ->
        try { File(java.net.URI(coverPath)) } catch (e: Exception) { coverPath }
    else -> coverPath
}

/**
 * Deserializa la lista de addons guardada en un modpack, para pintarlos
 * dentro del centro de actualizaciones y del visor de addons (portada,
 * tamaño y sitio de origen).
 *
 * Reutiliza la instancia de Gson y el `Type` precompilados: antes se creaban
 * ambos en cada llamada, y esta función se invoca por tarjeta durante el
 * scroll de la biblioteca.
 */
fun decodeModpackAddons(addonsJson: String): List<Addon> {
    if (addonsJson.isBlank()) return emptyList()
    return try {
        ModpackParsingCache.gson
            .fromJson<List<Addon>>(addonsJson, ModpackParsingCache.addonListType)
            ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }.withSafeLists()
}

/**
 * Extrae el ORIGEN WEB del primer addon que tenga sourceUrl+sourceSite
 * persistidos en el addonsJson del modpack. Devuelve null si ninguno vino
 * del WebView interno (p. ej. importado por share externo o por archivo).
 *
 * Recibe el addonsJson COMPLETO o solo el head truncado (64 KB) de la lista
 * ligera: si el fragmento no se puede parsear por estar cortado a mitad de
 * JSON, se escanea el texto para localizar el primer "sourceSite" y su addon.
 *
 * ⚡ RENDIMIENTO: el resultado se memoiza por fragmento de JSON. Esta función
 * corre una vez por tarjeta visible en cada pasada de scroll, y antes
 * construía `Gson()` más cinco `Regex(...)` cada vez —compilar un patrón es
 * lo más caro de todo—, que era la causa principal del tirón al deslizar.
 * Con la caché, revisitar una tarjeta ya vista no vuelve a parsear nada.
 */
fun extractModpackSource(addonsJson: String): ModpackSource? {
    if (addonsJson.isBlank()) return null

    // 1) ¿Ya se calculó para este mismo fragmento?
    ModpackParsingCache.cachedSource(addonsJson)?.let { cached ->
        return cached.value as? ModpackSource
    }

    val result = computeModpackSource(addonsJson)

    // Solo se memoiza el acierto: un fallo puede deberse a un head truncado
    // justo antes del campo, y no interesa fijar el negativo.
    if (result != null) ModpackParsingCache.putSource(addonsJson, result)
    return result
}

private fun computeModpackSource(addonsJson: String): ModpackSource? {
    // 1) Ruta rápida: JSON completo (modpacks cortos o sin truncar)
    try {
        val addons: List<Addon> = ModpackParsingCache.gson
            .fromJson(addonsJson, ModpackParsingCache.addonListType)
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
        val site = ModpackParsingCache.siteRegex
            .find(addonsJson, siteIdx)?.groupValues?.get(1) ?: return null
        val urlMatch = ModpackParsingCache.urlRegex
            .findAll(addonsJson).lastOrNull { it.range.first < siteIdx }
        val url = urlMatch?.groupValues?.get(1) ?: return null
        val name = ModpackParsingCache.nameRegex.findAll(addonsJson)
            .lastOrNull { it.range.first < urlMatch.range.first }
            ?.groupValues?.get(1) ?: ""
        ModpackSource(site, url, name)
    } catch (e: Exception) {
        null
    }
}
