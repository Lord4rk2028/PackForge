package com.packforge.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.packforge.app.ui.components.CachedAsyncImage
import com.packforge.app.ui.components.AddonSite
import com.packforge.app.ui.components.MorphingFab
import com.packforge.app.ui.components.MorphingFabItem
import com.packforge.app.ui.components.PackForgeTopBar
import com.packforge.app.ui.components.SiteSelector
import com.packforge.app.util.PackForgeLog
import com.packforge.app.domain.model.OperationProgress
import com.packforge.app.domain.model.SavedModpack
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
    savedModpacks: List<SavedModpack>,
    isImporting: Boolean,
    importProgress: OperationProgress,
    webImportError: String?,
    onDeleteModpack: (String) -> Unit,
    onLoadModpack: (SavedModpack) -> Unit,
    onImportFromUrl: (String) -> Unit,
    onClearError: () -> Unit
) {
    val activeWebSource by viewModel.activeWebSource.collectAsStateWithLifecycle()
    val lastWebUrls by viewModel.lastWebUrls.collectAsStateWithLifecycle()
    val showMyModpacks by viewModel.showMyModpacks.collectAsStateWithLifecycle()
    val showThemeSettings by viewModel.showThemeSettings.collectAsStateWithLifecycle()
    val webImportSuccess by viewModel.webImportSuccess.collectAsStateWithLifecycle()

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
                // NAVEGACIÓN INTELIGENTE: si el WebView tiene historial hacia atrás,
                // primero retrocede; solo se cierra el navegador en la raíz.
                if (!viewModel.onStudioWebBackPressed()) {
                    viewModel.setActiveWebSource(null)
                }
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
            onOpenSources = { viewModel.setShowMyModpacks(false) }
        )
        return
    }

    val listState = rememberLazyListState()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp)
        ) {
            item {
                Column(modifier = Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Biblioteca de Modpacks",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Gestiona tus creaciones y fuentes de contenido",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                StudioCard(
                    icon = Icons.Default.Folder,
                    title = "My Modpacks",
                    description = "Edita, comparte o borra tus modpacks guardados",
                    badge = if(savedModpacks.isNotEmpty()) savedModpacks.size.toString() else null,
                    accent = Color(0xFF2ECC71)
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
                            Text("💡", fontSize = 18.sp)
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

@Composable
fun StudioCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    badge: String?,
    accent: Color,
    onClick: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth().clickable { onClick() }, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(26.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    if (badge != null) Badge { Text(badge) }
                }
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════
// TAREA 5: STUDIO = BIBLIOTECA DE MODPACKS (grid tipo Steam)
// ═════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyModpacksScreen(
    modpacks: List<SavedModpack>,
    onBack: () -> Unit,
    onDelete: (String) -> Unit,
    onLoad: (SavedModpack) -> Unit,
    onOpenSources: () -> Unit = {}
) {
    val context = LocalContext.current
    var modpackToDelete by remember { mutableStateOf<SavedModpack?>(null) }

    Scaffold(
        topBar = {
            PackForgeTopBar(
                title = "Biblioteca de Modpacks",
                onBackClick = onBack
            )
        }
    ) { padding ->
        if (modpacks.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.FolderOpen, null, Modifier.size(64.dp).alpha(0.4f))
                    Text("Sin modpacks guardados", style = MaterialTheme.typography.titleMedium, modifier = Modifier.alpha(0.6f))
                    Text(
                        "Toca + para explorar fuentes y crear tu primer modpack",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                }
            }
        } else {
            // ── GRID 2 columnas tipo Steam ───────────────────
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(16.dp)
            ) {
                items(modpacks, key = { it.id }, contentType = { "modpack" }) { modpack ->
                    val onLoadThis = remember(modpack.id) { { onLoad(modpack); onBack() } }
                    val onDeleteThis = remember(modpack.id) { { modpackToDelete = modpack } }
                    val onShareThis = remember(modpack.id) { { shareModpack(context, modpack) } }
                    ModpackLibraryCard(
                        modpack = modpack,
                        onLoad = onLoadThis,
                        onDelete = onDeleteThis,
                        onShare = onShareThis
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Spacer(modifier = Modifier.height(80.dp))
                }
            }
        }
    }

    // FAB EXPRESIVO con morphing
    Box(modifier = Modifier.fillMaxSize()) {
        MorphingFab(
            items = listOf(
                MorphingFabItem("Explorar fuentes", Icons.Default.Search, onClick = onOpenSources),
                MorphingFabItem("Compartir uno", Icons.Default.Share) {
                    modpacks.firstOrNull()?.let { shareModpack(context, it) }
                }
            ),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 24.dp)
        )
    }

    modpackToDelete?.let { m ->
        AlertDialog(onDismissRequest = { modpackToDelete = null }, title = { Text("Eliminar modpack") },
            text = { Text("¿Eliminar '${m.name}'? Se borrará del historial de PackForge.") },
            confirmButton = { TextButton(onClick = { onDelete(m.id); modpackToDelete = null }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { modpackToDelete = null }) { Text("Cancelar") } }
        )
    }
}

fun shareModpack(context: android.content.Context, modpack: SavedModpack) {
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
    val file = possiblePaths.firstOrNull { it.exists() && it.length() > 0 }

    if (file != null) {
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
    } else {
        PackForgeLog.e("StudioScreen", "Archivo no encontrado en ninguna ruta. Rutas intentadas: ${possiblePaths.map { it.absolutePath }}")
        android.widget.Toast.makeText(context, "El archivo ya no existe en el almacenamiento", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/**
 * Tarjeta tipo Steam: portada grande 16:9, nombre, fecha y nº de addons.
 */
@Composable
fun ModpackLibraryCard(
    modpack: SavedModpack,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit
) {
    val coverPath = modpack.coverUriString
    val hasValidCover = !coverPath.isNullOrBlank() && (!coverPath.startsWith("/") || File(coverPath).exists())

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onLoad() },
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ── Portada 16:9 ─────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                when {
                    coverPath != null && hasValidCover -> {
                        CachedAsyncImage(
                            model = coverPath,
                            contentDescription = "Portada de ${modpack.name}",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                    else -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Extension,
                                null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                                modifier = Modifier.size(28.dp)
                            )
                            Text(
                                text = modpack.name.take(12),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Badge de nº de addons
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                ) {
                    Text(
                        text = "🧩 ${modpack.addonCount}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            // ── Metadatos ────────────────────────────────────
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = modpack.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "v${modpack.version} · MC ${modpack.mcVersion}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatModpackDate(modpack.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            // ── Acciones ─────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = onLoad,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp)
                ) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(15.dp))
                    Text("Editar", style = MaterialTheme.typography.labelSmall)
                }
                FilledTonalIconButton(
                    onClick = onShare,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.Share, null, Modifier.size(16.dp))
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error
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