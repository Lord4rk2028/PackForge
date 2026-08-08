package com.packforge.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.packforge.app.ui.components.CachedAsyncImage
import com.packforge.app.ui.components.PackForgeTopBar
import com.packforge.app.util.PackForgeLog
import com.packforge.app.domain.model.OperationProgress
import com.packforge.app.domain.model.SavedModpack
import com.packforge.app.ui.viewmodel.PackForgeViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioScreen(
    viewModel: PackForgeViewModel,
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
    val webImportSuccess by viewModel.webImportSuccess.collectAsStateWithLifecycle()

    // Manejo de navegadores internos con persistencia
    activeWebSource?.let { source ->
        val currentUrl = lastWebUrls[source] ?: ""
        val initialUrl = when(source) {
            "MCPEDL" -> "https://mcpedl.com/category/mods-addons/"
            "CurseForge" -> "https://www.curseforge.com/minecraft-bedrock"
            "ModBay" -> "https://modbay.org/mods/"
            else -> ""
        }
        val persistentWebView = viewModel.getPersistentWebView(source, LocalContext.current)
        WebBrowserScreen(
            title = source,
            currentUrl = currentUrl,
            initialUrl = initialUrl,
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
            onUrlChanged = { newUrl -> viewModel.updateWebUrl(source, newUrl) },
            onImportFromUrl = onImportFromUrl,
            onClearError = onClearError,
            webView = persistentWebView
        )
        return
    }

    if (showMyModpacks) {
        MyModpacksScreen(
            modpacks = savedModpacks,
            onBack = { viewModel.setShowMyModpacks(false) },
            onDelete = onDeleteModpack,
            onLoad = onLoadModpack
        )
        return
    }

    val listState = rememberLazyListState()

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(16.dp)
    ) {
        item {
            Column(modifier = Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "PackForge Studio", 
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
                badge = if(savedModpacks.isNotEmpty()) savedModpacks.size.toString() else null
            ) {
                viewModel.setShowMyModpacks(true)
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

        item {
            StudioCard(
                icon = Icons.Default.Search, 
                title = "MCPEDL", 
                description = "Addons y mapas de la comunidad", 
                badge = "Popular"
            ) {
                viewModel.setActiveWebSource("MCPEDL")
            }
        }

        item {
            StudioCard(
                icon = Icons.Outlined.Extension, 
                title = "CurseForge", 
                description = "Addons verificados de alta calidad", 
                badge = null
            ) {
                viewModel.setActiveWebSource("CurseForge")
            }
        }

        item {
            StudioCard(
                icon = Icons.Default.Star, 
                title = "ModBay", 
                description = "Texturas, skins y complementos", 
                badge = null
            ) {
                viewModel.setActiveWebSource("ModBay")
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

        item { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

@Composable
fun StudioCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, description: String, badge: String?, onClick: () -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth().clickable { onClick() }, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(26.dp))
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyModpacksScreen(modpacks: List<SavedModpack>, onBack: () -> Unit, onDelete: (String) -> Unit, onLoad: (SavedModpack) -> Unit) {
    val context = LocalContext.current
    var modpackToDelete by remember { mutableStateOf<SavedModpack?>(null) }

    Scaffold(
        topBar = {
            PackForgeTopBar(
                title = "My Modpacks",
                onBackClick = onBack
            )
        }
    ) { padding ->
        if (modpacks.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.FolderOpen, null, Modifier.size(64.dp).alpha(0.4f))
                    Text("Sin modpacks guardados", style = MaterialTheme.typography.titleMedium, modifier = Modifier.alpha(0.6f))
                }
            }
        } else {
            val modpackListState = rememberLazyListState()
            LazyColumn(
                state = modpackListState,
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(16.dp)
            ) {
                items(modpacks, key = { it.id }, contentType = { "modpack" }) { modpack ->
                    val onLoad = remember(modpack.id) { { onLoad(modpack); onBack() } }
                    val onDelete = remember(modpack.id) { { modpackToDelete = modpack } }
                    val onShare = remember(modpack.id) { { shareModpack(context, modpack) } }
                    SavedModpackCard(
                        modpack = modpack,
                        onLoad = onLoad,
                        onDelete = onDelete,
                        onShare = onShare
                    )
                }
                item { Spacer(modifier = Modifier.height(80.dp)) }
            }
        }
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
    // Intentar múltiples rutas posibles para el archivo
    val possiblePaths = listOf(
        File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), modpack.fileName),
        File(context.cacheDir, modpack.fileName),
        File(context.filesDir, modpack.fileName),
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

@Composable
fun SavedModpackCard(modpack: SavedModpack, onLoad: () -> Unit, onDelete: () -> Unit, onShare: () -> Unit) {
    val addonNames = try { org.json.JSONArray(modpack.addonNames).let { a -> (0 until a.length()).map { a.getString(it) } } } catch(e: Exception) { emptyList() }

    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    val coverPath = modpack.coverUriString
                    if (!coverPath.isNullOrBlank()) {
                        val fileExists = !coverPath.startsWith("/") || File(coverPath).exists()
                        if (fileExists) {
                            CachedAsyncImage(
                                model = coverPath,
                                contentDescription = "Portada de ${modpack.name}",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(Icons.Default.Extension, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(30.dp))
                        }
                    } else {
                        Icon(Icons.Default.Extension, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(30.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(modpack.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("v${modpack.version} · MC ${modpack.mcVersion} · ${modpack.addonCount} addons", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onLoad, Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) { 
                    Icon(Icons.Default.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Cargar/Editar")
                }
                FilledTonalButton(onClick = onShare, Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) { 
                    Icon(Icons.Default.Share, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Compartir") 
                }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
