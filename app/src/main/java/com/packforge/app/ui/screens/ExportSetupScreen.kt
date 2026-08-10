package com.packforge.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.packforge.app.ui.components.CachedAsyncImage
import com.packforge.app.ui.components.CraftingTableLayout
import com.packforge.app.ui.components.MinecraftProgressBar
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Publish
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.net.Uri
import android.os.Environment
import java.io.File
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry
import com.packforge.app.domain.model.Addon
import com.packforge.app.domain.model.Conflict
import com.packforge.app.domain.model.ConflictSeverity
import com.packforge.app.domain.model.ExportState
import com.packforge.app.domain.model.ModpackMetadata

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSetupScreen(
    viewModel: com.packforge.app.ui.viewmodel.PackForgeViewModel,
    metadata: ModpackMetadata,
    addons: List<Addon>,
    conflicts: List<Conflict>,
    resolutions: Map<String, String>,
    exportState: ExportState,
    isMinecraftInstalled: Boolean,
    minecraftVersion: String?,
    minecraftUri: String?,
    onMetadataChange: (ModpackMetadata) -> Unit,
    onExport: (outputUri: Uri?, importToMinecraft: Boolean) -> Unit,
    onResetExport: () -> Unit,
    onConnectMinecraft: (Uri) -> Unit,
    onDisconnectMinecraft: () -> Unit
) {
    val conflictStrategy by viewModel.conflictStrategy.collectAsStateWithLifecycle()
    val mergeResult by viewModel.mergeResult.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    // ── ESTADOS ─────────────────────────────────────────────
    var importToMinecraft by remember { mutableStateOf(minecraftUri != null) }
    var useCustomPath by remember { mutableStateOf(false) }
    var customUri by remember { mutableStateOf<Uri?>(null) }
    var mcDropdownExpanded by remember { mutableStateOf(false) }
    var showDebugZip by remember { mutableStateOf(false) }

    val activeAddons = addons.filter { it.enabled }
    val unresolvedCritical = conflicts.count {
        it.severity == ConflictSeverity.CRITICAL &&
        !resolutions.containsKey(it.id)
    }
    val unresolvedTotal = conflicts.count {
        !resolutions.containsKey(it.id)
    }

    val safeFileName = metadata.name
        .trim()
        .replace(" ", "_")
        .replace("[^a-zA-Z0-9_\\-]".toRegex(), "")
        .ifBlank { "modpack" }

    val addonImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.importAddons(context, uris, false)
        }
    }

    val coverPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            onMetadataChange(
                metadata.copy(coverUriString = uri.toString())
            )
        }
    }

    val mcFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            onConnectMinecraft(uri)
        }
    }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            customUri = uri
            useCustomPath = true
        }
    }

    // ── PANTALLA DE ÉXITO ────────────────────────────────────
    if (exportState is ExportState.Success) {
        ExportSuccessScreen(
            result = exportState,
            isMinecraftInstalled = isMinecraftInstalled,
            conflicts = mergeResult?.conflicts ?: emptyList(),
            validationResult = mergeResult?.validationResult,
            onReset = onResetExport
        )
        return
    }

    // ── CONTENIDO PRINCIPAL ──────────────────────────────────
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = androidx.compose.foundation.layout
            .PaddingValues(16.dp)
    ) {

        // PREVIEW
        item {
            ModpackPreviewCard(
                metadata = metadata,
                activeAddons = activeAddons.size,
                selectedTemplateIndex = -1
            )
        }

        // MESA DE CRAFTEO
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                CraftingTableLayout(
                    addons = addons,
                    onAddAddon = {
                        addonImportLauncher.launch(arrayOf("*/*"))
                    },
                    onExport = {
                        onExport(
                            if (useCustomPath) customUri else null,
                            importToMinecraft
                        )
                    }
                )
            }
        }

        // PERSONALIZACIÓN
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Detalles del Modpack",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Icon(
                                imageVector = Icons.Default.Title,
                                contentDescription = null,
                                modifier = Modifier.padding(8.dp).size(18.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    // ── PORTADA ──────────────────────────────────
                    Text(
                        text = "Portada del modpack",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val previewUri = metadata.coverUriString

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(20.dp)
                            )
                            .clickable { coverPickerLauncher.launch("image/*") },
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            previewUri != null -> {
                                val resolved = resolveCoverModel(previewUri)
                                val imageModel = resolved.first
                                val fileExists = resolved.second
                                
                                if (fileExists) {
                                    CachedAsyncImage(
                                        model = imageModel,
                                        contentDescription = "Portada personalizada",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = null,
                                            modifier = Modifier.size(32.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                        )
                                        Text(
                                            text = "Imagen no encontrada",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                        )
                                    }
                                }
                            }
                            else -> {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(56.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primaryContainer),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp),
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                    Text(
                                        text = "Toca para elegir portada",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    // ── CAMPOS DE TEXTO ───────────────────────
                    OutlinedTextField(
                        value = metadata.name,
                        onValueChange = {
                            onMetadataChange(metadata.copy(name = it))
                        },
                        label = { Text("Nombre del Modpack") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Title,
                                contentDescription = null
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = metadata.author,
                        onValueChange = {
                            onMetadataChange(
                                metadata.copy(author = it)
                            )
                        },
                        label = { Text("Autor") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Person,
                                contentDescription = null
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement =
                            Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = metadata.version,
                            onValueChange = {
                                onMetadataChange(
                                    metadata.copy(version = it)
                                )
                            },
                            label = { Text("Versión") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true
                        )

                        val mcVersions = listOf(
                            // Versiones oficiales recientes (serie 26.x)
                            "26.42", "26.41", "26.40", "26.35", "26.34", "26.33", "26.32", "26.31", "26.30",
                            "26.23", "26.22", "26.21", "26.20", "26.13", "26.12", "26.11", "26.10",
                            "26.3", "26.2", "26.1", "26.0",
                            // Versiones 1.21 (Caves & Cliffs de Bedrock 1.21)
                            "1.21.51", "1.21.50", "1.21.40", "1.21.30", "1.21.20", "1.21.10", "1.21.0",
                            "1.20.80", "1.20.73", "1.20.72", "1.20.70", "1.20.62", "1.20.60", "1.20.50", "1.20.40", "1.20.30", "1.20.20", "1.20.10", "1.20.0",
                            "1.19.83", "1.19.81", "1.19.80", "1.19.73", "1.19.71", "1.19.70", "1.19.63", "1.19.62", "1.19.60", "1.19.50", "1.19.40", "1.19.30", "1.19.20", "1.19.10", "1.19.0",
                            "1.18.33", "1.18.32", "1.18.31", "1.18.30", "1.18.12", "1.18.11", "1.18.10", "1.18.2", "1.18.1", "1.18.0",
                            "1.17.41", "1.17.40", "1.17.34", "1.17.33", "1.17.32", "1.17.30", "1.17.11", "1.17.10", "1.17.5", "1.17.4", "1.17.3", "1.17.2", "1.17.0",
                            "1.16.221", "1.16.220", "1.16.201", "1.16.200", "1.16.100", "1.16.40", "1.16.20", "1.16.0"
                            // Versiones beta y preview compatibles pero no mostradas
                            // "1.21.60 (Beta)", "1.21.55 (Preview)", "1.20.90 (Beta)", "1.20.85 (Preview)",
                            // "1.19.90 (Beta)", "1.19.85 (Preview)", "1.18.40 (Beta)", "1.18.35 (Preview)"
                        )
                        ExposedDropdownMenuBox(
                            expanded = mcDropdownExpanded,
                            onExpandedChange = {
                                mcDropdownExpanded = it
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            OutlinedTextField(
                                value = metadata.mcVersion,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("MC Bedrock") },
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults
                                        .TrailingIcon(
                                            expanded = mcDropdownExpanded
                                        )
                                },
                                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true),
                                shape = RoundedCornerShape(12.dp)
                            )
                            ExposedDropdownMenu(
                                expanded = mcDropdownExpanded,
                                onDismissRequest = {
                                    mcDropdownExpanded = false
                                }
                            ) {
                                mcVersions.forEach { v ->
                                    DropdownMenuItem(
                                        text = { Text(v) },
                                        onClick = {
                                            onMetadataChange(
                                                metadata.copy(
                                                    mcVersion = v
                                                )
                                            )
                                            mcDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = metadata.description,
                        onValueChange = {
                            onMetadataChange(
                                metadata.copy(description = it)
                            )
                        },
                        label = { Text("Descripción") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        minLines = 2,
                        maxLines = 4
                    )

                    OutlinedTextField(
                        value = metadata.tags.joinToString(", "),
                        onValueChange = { input ->
                            val tags = input.split(",")
                                .map { it.trim() }
                                .filter { it.isNotBlank() }
                            onMetadataChange(
                                metadata.copy(tags = tags)
                            )
                        },
                        label = {
                            Text("Etiquetas (separadas por coma)")
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Tag,
                                contentDescription = null
                            )
                        },
                        placeholder = {
                            Text("survival, rpg, pvp...")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )
                }
            }
        }

        // DESTINO
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Guardar en",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    DestinationOption(
                        icon = Icons.Default.Download,
                        title = "Carpeta Descargas",
                        subtitle = "Descargas/${safeFileName}" +
                            "_v${metadata.version}.mcaddon",
                        selected = !useCustomPath,
                        onClick = {
                            useCustomPath = false
                            customUri = null
                        }
                    )

                    DestinationOption(
                        icon = Icons.Default.FolderOpen,
                        title = "Elegir ubicación",
                        subtitle = if (customUri != null)
                            "Ubicación seleccionada ✓"
                        else
                            "Elige dónde guardar el archivo",
                        selected = useCustomPath,
                        onClick = {
                            createDocumentLauncher.launch(
                                "${safeFileName}" +
                                "_v${metadata.version}.mcaddon"
                            )
                        }
                    )

                    // ── CONEXIÓN DIRECTA CON MINECRAFT ──────────
                    // Ocultar en Android 11+ (SDK 30+) debido a restricciones de SAF
                    if (android.os.Build.VERSION.SDK_INT < 30) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .border(
                                    1.dp, 
                                    if (minecraftUri != null) Color(0xFF4CAF50).copy(alpha = 0.5f)
                                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable {
                                    if (minecraftUri == null) {
                                        mcFolderLauncher.launch(null)
                                    } else {
                                        onDisconnectMinecraft()
                                    }
                                }
                                .padding(16.dp),
                            color = if (minecraftUri != null) Color(0xFF4CAF50).copy(alpha = 0.05f) 
                                    else Color.Transparent
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = if (minecraftUri != null) Icons.Default.CheckCircle 
                                                 else Icons.Outlined.SportsEsports,
                                    contentDescription = null,
                                    tint = if (minecraftUri != null) Color(0xFF4CAF50) 
                                           else MaterialTheme.colorScheme.primary
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (minecraftUri != null) "Instalación Directa Activa" 
                                               else "Activar Instalación Directa",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = if (minecraftUri != null) "Toca para desconectar" 
                                               else "Selecciona la carpeta 'com.mojang'",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (minecraftUri == null) {
                                    Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }

        // MINECRAFT (SMART IMPORT ADAPTATION)
        // Ocultar en Android 11+ (SDK 30+) ya que no funciona correctamente
        if (android.os.Build.VERSION.SDK_INT < 30) {
            item {
                val isAndroid11Plus = android.os.Build.VERSION.SDK_INT >= 30
            
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isMinecraftInstalled) Color(0xFF4CAF50).copy(alpha = 0.2f)
                                    else MaterialTheme.colorScheme.errorContainer
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isMinecraftInstalled) Icons.Outlined.SportsEsports 
                                             else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (isMinecraftInstalled) Color(0xFF4CAF50) 
                                       else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isAndroid11Plus) "Smart Import (Android 11+)" 
                                       else "Instalación Directa",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (isMinecraftInstalled) "Listo para importar a Minecraft" 
                                       else "Instala Minecraft para activar",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (isMinecraftInstalled) {
                            Switch(
                                checked = importToMinecraft,
                                onCheckedChange = { importToMinecraft = it }
                            )
                        }
                    }
                    
                    if (isAndroid11Plus && isMinecraftInstalled) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "💡 Debido a las restricciones de Android, PackForge enviará el modpack directamente a Minecraft para que se instale solo.",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(12.dp),
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }
        }
        }

        // RESUMEN
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Resumen",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    SummaryRow(
                        icon = Icons.Default.CheckCircle,
                        label = "Addons activos",
                        value = "${activeAddons.size}",
                        color = MaterialTheme.colorScheme.primary
                    )
                    HorizontalDivider()
                    SummaryRow(
                        icon = if (unresolvedCritical > 0)
                            Icons.Default.Error
                        else Icons.Default.CheckCircle,
                        label = "Conflictos",
                        value = when {
                            unresolvedCritical > 0 ->
                                "$unresolvedCritical críticos"
                            unresolvedTotal > 0 ->
                                "$unresolvedTotal sin resolver"
                            else -> "Todos resueltos ✓"
                        },
                        color = when {
                            unresolvedCritical > 0 ->
                                MaterialTheme.colorScheme.error
                            unresolvedTotal > 0 ->
                                Color(0xFFF57C00)
                            else -> Color(0xFF4CAF50)
                        }
                    )
                }
            }
        }

        // BOTÓN EXPORTAR
        item {
            val canExport = metadata.name.isNotBlank() &&
                metadata.author.isNotBlank() &&
                activeAddons.isNotEmpty()
            
            val isExporting = exportState is ExportState.Loading || exportState is ExportState.Progress

            if (exportState is ExportState.Progress) {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = androidx.compose.material3.CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        MinecraftProgressBar(
                            progress = exportState.percent / 100f,
                            message = thematicExportMessage(exportState.message)
                        )
                    }
                }
            }

            Button(
                onClick = {
                    onExport(
                        if (useCustomPath) customUri else null,
                        importToMinecraft
                    )
                },
                enabled = canExport && !isExporting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor =
                        if (unresolvedCritical > 0)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.primary
                )
            ) {
                if (isExporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                        strokeCap = StrokeCap.Round
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(if (exportState is ExportState.Progress) "Exportando..." else "Iniciando...")
                } else {
                    Icon(
                        imageVector = Icons.Default.Publish,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (unresolvedCritical > 0)
                            "Exportar con riesgos"
                        else "Exportar Modpack",
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (!canExport) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = when {
                        activeAddons.isEmpty() ->
                            "Agrega al menos un addon"
                        metadata.name.isBlank() ->
                            "El nombre es obligatorio"
                        else -> "El autor es obligatorio"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // BOTÓN DEBUG VERIFICAR PORTADA
            var showIconDebug by remember { mutableStateOf(false) }
            OutlinedButton(
                onClick = { showIconDebug = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Verificar Portada")
            }

            if (showIconDebug) {
                IconDebugDialog(
                    coverUriString = metadata.coverUriString,
                    onDismiss = { showIconDebug = false }
                )
            }

            // BOTÓN DEBUG ZIP
            OutlinedButton(
                onClick = { showDebugZip = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Debug ZIP",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        if (exportState is ExportState.Error) {
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement =
                            Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = exportState.message,
                            color = MaterialTheme.colorScheme
                                .onErrorContainer
                        )
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(80.dp)) }
    }

    // DIÁLOGO DEBUG ZIP
    if (showDebugZip) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDebugZip = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text("Debug ZIP", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val safeFileName = metadata.name
                        .trim()
                        .replace(" ", "_")
                        .replace("[^a-zA-Z0-9_\\-]".toRegex(), "")
                        .ifBlank { "modpack" }
                    val zipFileName = "${safeFileName}_v${metadata.version}.mcaddon"
                    val zipFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), zipFileName)
                    
                    if (zipFile.exists()) {
                        // Leer entradas del ZIP
                        val zipEntries = try {
                            val entries = mutableListOf<String>()
                            java.io.FileInputStream(zipFile).use { fis ->
                                ZipInputStream(fis).use { zis ->
                                    var entry: ZipEntry? = zis.nextEntry
                                    while (entry != null) {
                                        entries.add(entry.name)
                                        entry = zis.nextEntry
                                    }
                                }
                            }
                            entries
                        } catch (e: Exception) {
                            listOf("Error al leer ZIP: ${e.message}")
                        }
                        
                        Text(
                            text = "📦 Entradas en ZIP (${zipEntries.size}):",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall
                        )
                        
                        zipEntries.take(20).forEach { entry ->
                            Text(
                                text = "  - $entry",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                        
                        if (zipEntries.size > 20) {
                            Text(
                                text = "... y ${zipEntries.size - 20} más",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        HorizontalDivider()
                        
                        // Buscar y mostrar manifests (CRÍTICO: buscar en carpetas BP_* y RP_*)
                        val bpManifestContent = zipEntries.find { 
                            val parts = it.split("/")
                            parts.size >= 2 && parts[0].startsWith("BP_", ignoreCase = true) && it.endsWith("manifest.json")
                        }?.let { entry ->
                            try {
                                java.io.FileInputStream(zipFile).use { fis ->
                                    ZipInputStream(fis).use { zis ->
                                        var zipEntry: ZipEntry? = zis.nextEntry
                                        var result: String? = null
                                        while (zipEntry != null) {
                                            if (zipEntry.name == entry) {
                                                result = zis.readBytes().toString(Charsets.UTF_8)
                                                break
                                            }
                                            zipEntry = zis.nextEntry
                                        }
                                        result
                                    }
                                }
                            } catch (e: Exception) {
                                "Error: ${e.message}"
                            }
                        }
                        
                        val rpManifestContent = zipEntries.find { 
                            val parts = it.split("/")
                            parts.size >= 2 && parts[0].startsWith("RP_", ignoreCase = true) && it.endsWith("manifest.json")
                        }?.let { entry ->
                            try {
                                java.io.FileInputStream(zipFile).use { fis ->
                                    ZipInputStream(fis).use { zis ->
                                        var zipEntry: ZipEntry? = zis.nextEntry
                                        var result: String? = null
                                        while (zipEntry != null) {
                                            if (zipEntry.name == entry) {
                                                result = zis.readBytes().toString(Charsets.UTF_8)
                                                break
                                            }
                                            zipEntry = zis.nextEntry
                                        }
                                        result
                                    }
                                }
                            } catch (e: Exception) {
                                "Error: ${e.message}"
                            }
                        }
                        
                        if (bpManifestContent != null) {
                            Text(
                                text = "📄 BP manifest.json:",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = bpManifestContent,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(8.dp)
                            )
                            
                            // Verificar UUIDs
                            val uuidVerificationResult = try {
                                val bpJson = org.json.JSONObject(bpManifestContent)
                                val bpHeaderUuid = bpJson.getJSONObject("header").getString("uuid")
                                val bpDeps = bpJson.optJSONArray("dependencies")
                                
                                if (bpDeps != null && bpDeps.length() > 0) {
                                    val bpDepUuid = bpDeps.getJSONObject(0).getString("uuid")
                                    val rpHeaderUuid = if (rpManifestContent != null) {
                                        org.json.JSONObject(rpManifestContent).getJSONObject("header").getString("uuid")
                                    } else null
                                    
                                    Triple(bpHeaderUuid, bpDepUuid, rpHeaderUuid)
                                } else null
                            } catch (e: Exception) {
                                null
                            }

                            if (uuidVerificationResult != null) {
                                val (bpHeaderUuid, bpDepUuid, rpHeaderUuid) = uuidVerificationResult
                                HorizontalDivider()
                                Text(
                                    text = "🔍 Verificación UUIDs:",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = "BP header UUID: $bpHeaderUuid",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = "BP dependency UUID: $bpDepUuid",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                
                                if (rpHeaderUuid != null) {
                                    Text(
                                        text = "RP header UUID: $rpHeaderUuid",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    
                                    val uuidsMatch = bpDepUuid == rpHeaderUuid
                                    Text(
                                        text = if (uuidsMatch) "✅ UUIDs coinciden" else "❌ UUIDs NO coinciden",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (uuidsMatch) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        
                        if (rpManifestContent != null) {
                            HorizontalDivider()
                            Text(
                                text = "📄 RP manifest.json:",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = rpManifestContent,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(8.dp)
                            )
                        }
                        
                        // Verificar BOM
                        HorizontalDivider()
                        Text(
                            text = "🔍 Verificación BOM:",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall
                        )
                        
                        val bpManifestBytes = bpManifestContent?.toByteArray(Charsets.UTF_8)
                        val rpManifestBytes = rpManifestContent?.toByteArray(Charsets.UTF_8)
                        
                        if (bpManifestBytes != null && bpManifestBytes.isNotEmpty()) {
                            val firstByte = String.format("%02X", bpManifestBytes[0])
                            val hasBom = bpManifestBytes.size >= 3 && 
                                        bpManifestBytes[0] == 0xEF.toByte() && 
                                        bpManifestBytes[1] == 0xBB.toByte() && 
                                        bpManifestBytes[2] == 0xBF.toByte()
                            Text(
                                text = "BP primer byte: $firstByte ${if (hasBom) "(❌ TIENE BOM)" else "(✅ Sin BOM)"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (hasBom) MaterialTheme.colorScheme.error else Color(0xFF4CAF50)
                            )
                        }
                        
                        if (rpManifestBytes != null && rpManifestBytes.isNotEmpty()) {
                            val firstByte = String.format("%02X", rpManifestBytes[0])
                            val hasBom = rpManifestBytes.size >= 3 && 
                                        rpManifestBytes[0] == 0xEF.toByte() && 
                                        rpManifestBytes[1] == 0xBB.toByte() && 
                                        rpManifestBytes[2] == 0xBF.toByte()
                            Text(
                                text = "RP primer byte: $firstByte ${if (hasBom) "(❌ TIENE BOM)" else "(✅ Sin BOM)"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (hasBom) MaterialTheme.colorScheme.error else Color(0xFF4CAF50)
                            )
                        }
                        
                    } else {
                        Text(
                            text = "❌ ZIP no encontrado en Descargas",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "Exporta el modpack primero para poder debuggearlo",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDebugZip = false }) {
                    Text("Cerrar")
                }
            }
        )
    }
}

/**
 * Convierte los mensajes internos del exportador en mensajes
 * temáticos estilo Minecraft (solo UI, no toca la lógica).
 */
private fun thematicExportMessage(raw: String): String = when {
    raw.contains("Extrayendo", ignoreCase = true) ||
        raw.contains("Clasificando", ignoreCase = true) -> "⛏️ Minando addons..."
    raw.contains("Fusionando", ignoreCase = true) -> "🔥 Fundiendo JSONs en el horno..."
    raw.contains("manifiesto", ignoreCase = true) ||
        raw.contains("Generando", ignoreCase = true) -> "✨ Encantando manifiestos..."
    raw.contains("Empaquetando", ignoreCase = true) ||
        raw.contains("ZIP", ignoreCase = true) -> "📦 Empaquetando tu modpack..."
    raw.contains("Validando", ignoreCase = true) -> "🔍 Inspeccionando tesoros..."
    raw.contains("Limpiando", ignoreCase = true) -> "🧹 Limpiando cofres..."
    else -> raw
}

@Composable
fun ModpackPreviewCard(
    metadata: ModpackMetadata,
    activeAddons: Int,
    selectedTemplateIndex: Int
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center
            ) {
                val coverUri = metadata.coverUriString
                when {
                    coverUri != null -> {
                        val resolved = resolveCoverModel(coverUri)
                        if (resolved.second) {
                            CachedAsyncImage(
                                model = resolved.first,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            DefaultIcon(metadata.name)
                        }
                    }
                    else -> {
                        DefaultIcon(metadata.name)
                    }
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = metadata.name.ifBlank { "Sin nombre" },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "por ${metadata.author.ifBlank { "?" }}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "v${metadata.version} · " +
                        "MC ${metadata.mcVersion} · " +
                        "$activeAddons addons",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Resuelve una cadena de portada (ruta absoluta "/...", "file:///...", "content://...",
 * o URL http) a un modelo de imagen válido para Coil y si realmente el archivo existe.
 */
private fun resolveCoverModel(coverUri: String): Pair<Any?, Boolean> {
    return when {
        coverUri.startsWith("/") -> {
            val f = File(coverUri)
            Pair(f, f.exists())
        }
        coverUri.startsWith("file://") -> {
            val f = try { File(java.net.URI(coverUri)) } catch (e: Exception) { File(coverUri) }
            Pair(f, f.exists())
        }
        else -> {
            // content:// o http(s):// - confiar en Coil para cargarlo
            Pair(Uri.parse(coverUri), true)
        }
    }
}

@Composable
private fun DefaultIcon(name: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.primaryContainer
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name.take(2).uppercase(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary
        )
    }
}

@Composable
fun DestinationOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(4.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (selected)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun SummaryRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    color: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(20.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}

@Composable
fun ExportSuccessScreen(
    result: ExportState.Success,
    isMinecraftInstalled: Boolean,
    conflicts: List<String>,
    validationResult: com.packforge.app.domain.engine.PackForgeValidator.ValidationResult?,
    onReset: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var showDebugInfo by remember { mutableStateOf(false) }
    var zipStructure by remember { mutableStateOf<List<String>>(emptyList()) }
    var bpManifestContent by remember { mutableStateOf<String?>(null) }
    var rpManifestContent by remember { mutableStateOf<String?>(null) }
    var fileSize by remember { mutableStateOf<String>("") }
    var bpUuid by remember { mutableStateOf<String>("") }
    var rpUuid by remember { mutableStateOf<String>("") }
    var bpDependencies by remember { mutableStateOf<String>("") }
    var bpManifestPath by remember { mutableStateOf<String>("") }
    var rpManifestPath by remember { mutableStateOf<String>("") }
    var bpHeaderName by remember { mutableStateOf<String>("") }
    var rpHeaderName by remember { mutableStateOf<String>("") }
    var dependencyMatch by remember { mutableStateOf<Boolean>(false) }
    
    // Helper function to extract UUID from manifest JSON
    fun extractUuidFromManifest(manifest: String): String {
        return try {
            val uuidPattern = """"uuid"\s*:\s*"([^"]+)"""".toRegex()
            val match = uuidPattern.find(manifest)
            match?.groupValues?.get(1) ?: "No encontrado"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
    
    // Helper function to extract header.name from manifest JSON
    fun extractHeaderName(manifest: String): String {
        return try {
            val namePattern = """"name"\s*:\s*"([^"]+)"""".toRegex()
            val match = namePattern.find(manifest)
            match?.groupValues?.get(1) ?: "No encontrado"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
    
    // Helper function to extract dependencies from manifest JSON
    fun extractDependenciesFromManifest(manifest: String): String {
        return try {
            val depsPattern = """"dependencies"\s*:\s*\[([^\]]+)\]""".toRegex()
            val match = depsPattern.find(manifest)
            if (match != null) {
                val depsContent = match.groupValues[1]
                val uuidPattern = """"uuid"\s*:\s*"([^"]+)"""".toRegex()
                val uuids = uuidPattern.findAll(depsContent).map { it.groupValues[1] }.toList()
                uuids.joinToString(", ")
            } else {
                "Sin dependencias"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
    
    // Debug function to read ZIP structure and verify manifests
    fun analyzeZip() {
        try {
            val zipFile = java.util.zip.ZipFile(java.io.File(result.filePath))
            val entries = mutableListOf<String>()
            zipFile.entries().toList().forEach { entry ->
                entries.add(entry.name)
            }
            zipStructure = entries.sorted()
            
            // Try to read BP and RP manifests
            val bpManifestEntry = entries.find { it.contains("manifest.json") && it.contains("behavior_packs") }
            val rpManifestEntry = entries.find { it.contains("manifest.json") && it.contains("resource_packs") }
            
            if (bpManifestEntry != null) {
                bpManifestPath = bpManifestEntry
                val inputStream = zipFile.getInputStream(java.util.zip.ZipEntry(bpManifestEntry))
                bpManifestContent = inputStream.bufferedReader().use { it.readText() }
                // Extract UUID, header.name and dependencies
                bpUuid = extractUuidFromManifest(bpManifestContent!!)
                bpHeaderName = extractHeaderName(bpManifestContent!!)
                bpDependencies = extractDependenciesFromManifest(bpManifestContent!!)
            } else {
                bpManifestPath = "NO ENCONTRADO"
            }
            
            if (rpManifestEntry != null) {
                rpManifestPath = rpManifestEntry
                val inputStream = zipFile.getInputStream(java.util.zip.ZipEntry(rpManifestEntry))
                rpManifestContent = inputStream.bufferedReader().use { it.readText() }
                // Extract UUID and header.name
                rpUuid = extractUuidFromManifest(rpManifestContent!!)
                rpHeaderName = extractHeaderName(rpManifestContent!!)
            } else {
                rpManifestPath = "NO ENCONTRADO"
            }
            
            // Verify dependency match
            dependencyMatch = bpDependencies.isNotEmpty() && bpDependencies.contains(rpUuid)
            
            val file = java.io.File(result.filePath)
            val sizeKB = file.length() / 1024
            fileSize = "${sizeKB} KB"
            
            zipFile.close()
            showDebugInfo = true
        } catch (e: Exception) {
            android.util.Log.e("PackForge", "Error analyzing ZIP: ${e.message}")
        }
    }
    
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.size(120.dp).clip(CircleShape).background(Color(0xFF4CAF50).copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(60.dp))
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        Text(text = "¡Modpack Fusionado!", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
        
        if (conflicts.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("Registro de conflictos:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    conflicts.take(5).forEach { 
                        Text("• $it", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (conflicts.size > 5) Text("... y ${conflicts.size - 5} más", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        // Reporte de validación
        if (validationResult != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("✅ VALIDACIÓN COMPLETADA", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    Text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", style = MaterialTheme.typography.bodySmall)
                    
                    Text("Texturas faltantes reparadas: ${validationResult.fixedReferences}", style = MaterialTheme.typography.bodySmall)
                    Text("Modelos faltantes reparados: ${validationResult.missingModels.size}", style = MaterialTheme.typography.bodySmall)
                    
                    if (validationResult.langKeysAdded.isNotEmpty()) {
                        validationResult.langKeysAdded.forEach { (lang, count) ->
                            Text("Claves de idioma fusionadas: $count ($lang)", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    
                    Text("Sonidos fusionados: ${if (validationResult.soundsFixed) "✅" else "❌"}", style = MaterialTheme.typography.bodySmall)
                    Text("Referencias rotas reparadas: ${validationResult.fixedReferences}", style = MaterialTheme.typography.bodySmall)
                    
                    if (validationResult.warnings.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("⚠️ ADVERTENCIAS (no críticas):", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        validationResult.warnings.take(3).forEach { warning ->
                            Text("• $warning", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (validationResult.warnings.size > 3) {
                            Text("... y ${validationResult.warnings.size - 3} más", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        if (isMinecraftInstalled) {
            Button(
                onClick = { com.packforge.app.domain.engine.ModpackExporter.openInMinecraft(context, result.fileName) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Outlined.SportsEsports, null)
                Spacer(Modifier.width(8.dp))
                Text("ABRIR EN MINECRAFT", fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
        
        // Debug button
        Button(
            onClick = { analyzeZip() },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) {
            Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Verificar Manifest", fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(12.dp))
        
        // Debug info display
        if (showDebugInfo) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("VERIFICACIÓN DE MANIFEST", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Text("Archivo: ${result.fileName}", style = MaterialTheme.typography.bodySmall)
                    Text("Tamaño: $fileSize", style = MaterialTheme.typography.bodySmall)
                    
                    HorizontalDivider()
                    
                    // BP Manifest Info
                    Text("BEHAVIOR PACK:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                    Text("Ruta en ZIP: $bpManifestPath", style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    Text("header.name: $bpHeaderName", style = MaterialTheme.typography.bodySmall)
                    Text("UUID: $bpUuid", style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    Text("Dependencies: $bpDependencies", style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    
                    if (bpManifestContent != null) {
                        HorizontalDivider()
                        Text("Contenido (primeros 200 chars):", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                        Text(
                            bpManifestContent!!.take(200),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                    
                    HorizontalDivider()
                    
                    // RP Manifest Info
                    Text("RESOURCE PACK:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                    Text("Ruta en ZIP: $rpManifestPath", style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    Text("header.name: $rpHeaderName", style = MaterialTheme.typography.bodySmall)
                    Text("UUID: $rpUuid", style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    
                    if (rpManifestContent != null) {
                        HorizontalDivider()
                        Text("Contenido (primeros 200 chars):", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                        Text(
                            rpManifestContent!!.take(200),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                    
                    HorizontalDivider()
                    
                    // Dependency verification
                    Text("VERIFICACIÓN DE DEPENDENCIAS:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                    Text(
                        if (dependencyMatch) "✅ BP depende del RP (UUID coincide)" else "❌ BP NO depende del RP o UUID no coincide",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (dependencyMatch) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                    )
                    
                    HorizontalDivider()
                    
                    // Full ZIP structure
                    Text("ESTRUCTURA COMPLETA ZIP (${zipStructure.size} archivos):", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                    zipStructure.take(30).forEach { entry ->
                        Text("  $entry", style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                    if (zipStructure.size > 30) {
                        Text("  ... y ${zipStructure.size - 30} archivos más", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        OutlinedButton(
            onClick = onReset,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("Volver al editor")
        }
    }
}

@Composable
private fun IconDebugDialog(
    coverUriString: String?,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var iconSize by remember { mutableStateOf<Long?>(null) }
    var iconExists by remember { mutableStateOf(false) }
    
    // Verificar el tamaño de la imagen
    androidx.compose.runtime.LaunchedEffect(coverUriString) {
        if (!coverUriString.isNullOrEmpty()) {
            try {
                // CRÍTICO: la portada guardada en "My Modpacks" es una RUTA DE ARCHIVO
                // absoluta ("/data/..."), no un content:// URI. contentResolver.openInputStream
                // FALLA con rutas de archivo, por eso mostraba "No se pudo leer la imagen".
                val isFilePath = coverUriString.startsWith("/") || coverUriString.startsWith("file://")

                if (isFilePath) {
                    val srcFile = if (coverUriString.startsWith("file://")) {
                        java.io.File(java.net.URI(coverUriString))
                    } else {
                        java.io.File(coverUriString)
                    }
                    if (srcFile.exists()) {
                        iconSize = srcFile.length()
                        iconExists = true
                    } else {
                        iconExists = false
                    }
                } else {
                    val uri = android.net.Uri.parse(coverUriString)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readBytes()
                        iconSize = bytes.size.toLong()
                        iconExists = true
                    }
                }
            } catch (e: Exception) {
                iconExists = false
            }
        }
    }
    
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "🎨 VERIFICACIÓN DE PORTADA",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HorizontalDivider()
                Text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                
                Text(
                    text = "URI de imagen: ${coverUriString ?: "null"}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
                
                if (iconSize != null) {
                    Text(
                        text = "Tamaño imagen original: ${iconSize} bytes",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else if (coverUriString.isNullOrEmpty()) {
                    Text(
                        text = "⚠️ No se seleccionó ninguna portada",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Text(
                        text = "❌ No se pudo leer la imagen",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                
                HorizontalDivider()
                
                if (iconSize != null) {
                    Text(
                        text = "✅ La portada está configurada correctamente",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF4CAF50)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cerrar")
            }
        }
    )
}
