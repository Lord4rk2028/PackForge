package com.packforge.app.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.packforge.app.data.PackForgeDatabase
import com.packforge.app.util.PackForgeLog
import com.packforge.app.data.modrinth.ModrinthRepository
import com.packforge.app.domain.engine.AddonParser
import com.packforge.app.domain.engine.AddonUriCache
import com.packforge.app.domain.engine.ConflictEngine
import com.packforge.app.domain.engine.ModpackExporter
import com.packforge.app.domain.engine.PackForgeOrchestrator
import com.packforge.app.domain.model.*
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.UUID

sealed class PackForgeEvent {
    data class ShowSnackbar(val message: String, val isError: Boolean = false) : PackForgeEvent()
    object Vibration : PackForgeEvent()
}

class PackForgeViewModel(application: Application) : AndroidViewModel(application) {

    private val modrinthRepository by lazy { ModrinthRepository() }
    private var database: PackForgeDatabase? = null

    private val _events = MutableSharedFlow<PackForgeEvent>()
    val events: SharedFlow<PackForgeEvent> = _events.asSharedFlow()

    // ─── ESTADOS PRINCIPALES ─────────────────────────────────
    private val _addons = MutableStateFlow<List<Addon>>(emptyList())
    val addons = _addons.asStateFlow()

    private val _conflicts = MutableStateFlow<List<Conflict>>(emptyList())
    val conflicts = _conflicts.asStateFlow()

    private val _resolutions = MutableStateFlow<Map<String, String>>(emptyMap())
    val resolutions = _resolutions.asStateFlow()

    private val _metadata = MutableStateFlow(ModpackMetadata())
    val metadata = _metadata.asStateFlow()

    // ID del modpack que estamos editando actualmente (si viene del historial)
    private var editingModpackId: String? = null

    // ─── ESTADOS DE OPERACIÓN ────────────────────────────────
    private val _isImporting = MutableStateFlow(false)
    val isImporting = _isImporting.asStateFlow()

    private val _importProgress = MutableStateFlow<OperationProgress>(OperationProgress.Idle)
    val importProgress = _importProgress.asStateFlow()

    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState = _exportState.asStateFlow()

    private val _isMinecraftInstalled = MutableStateFlow(false)
    val isMinecraftInstalled = _isMinecraftInstalled.asStateFlow()

    private val _minecraftVersion = MutableStateFlow<String?>(null)
    val minecraftVersion = _minecraftVersion.asStateFlow()

    private val _minecraftUri = MutableStateFlow<String?>(null)
    val minecraftUri = _minecraftUri.asStateFlow()

    private val _compatibilityScore = MutableStateFlow(100)
    val compatibilityScore = _compatibilityScore.asStateFlow()

    private val _criticalConflictsCount = MutableStateFlow(0)
    val criticalConflictsCount = _criticalConflictsCount.asStateFlow()

    // ─── HISTORIAL ──────────────────────────────────────────
    private val _savedModpacks = MutableStateFlow<List<SavedModpack>>(emptyList())
    val savedModpacks = _savedModpacks.asStateFlow()

    // ─── ERRORES Y ESTADO DE NAVEGADOR ───────────────────────
    private val _webImportError = MutableStateFlow<String?>(null)
    val webImportError = _webImportError.asStateFlow()

    private val _mergeResult = MutableStateFlow<MergeResult?>(null)
    val mergeResult = _mergeResult.asStateFlow()

    private val _conflictStrategy = MutableStateFlow(ConflictStrategy.KEEP_FIRST)
    val conflictStrategy = _conflictStrategy.asStateFlow()

    // Merge conflicts from JsonDeepMerger
    private val _mergeConflicts = MutableStateFlow<List<com.packforge.app.domain.model.MergeConflict>>(emptyList())
    val mergeConflicts = _mergeConflicts.asStateFlow()

    fun setConflictStrategy(strategy: ConflictStrategy) {
        _conflictStrategy.value = strategy
    }

    fun updateMergeConflicts() {
        _mergeConflicts.value = com.packforge.app.domain.engine.JsonDeepMerger.mergeConflicts.toList()
    }

    fun resolveMergeConflict(index: Int, resolution: String) {
        val current = _mergeConflicts.value.toMutableList()
        if (index in current.indices) {
            current[index] = current[index].copy(resolution = resolution)
            _mergeConflicts.value = current
        }
    }

    // Guardar la última URL visitada de cada fuente para persistencia
    private val _lastWebUrls = MutableStateFlow(mapOf(
        "MCPEDL" to "https://mcpedl.com/category/mods-addons/",
        "CurseForge" to "https://www.curseforge.com/minecraft-bedrock",
        "ModBay" to "https://modbay.org/mods/"
    ))
    val lastWebUrls = _lastWebUrls.asStateFlow()

    private val _activeWebSource = MutableStateFlow<String?>(null)
    val activeWebSource = _activeWebSource.asStateFlow()

    fun setActiveWebSource(source: String?) {
        _activeWebSource.value = source
    }

    fun updateWebUrl(source: String, url: String) {
        val current = _lastWebUrls.value.toMutableMap()
        current[source] = url
        _lastWebUrls.value = current
    }

    init {
        // Inicialización única de la base de datos y observación continua
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _isMinecraftInstalled.value = ModpackExporter.isMinecraftInstalled(application)
                _minecraftVersion.value = ModpackExporter.getMinecraftVersion(application)

                val prefs = application.getSharedPreferences("packforge_prefs", Context.MODE_PRIVATE)
                _minecraftUri.value = prefs.getString("mc_folder_uri", null)

                val db = PackForgeDatabase.getInstance(application)
                database = db
                
                // Observar cambios en la DB para actualizar la lista de My Modpacks
                db.savedModpackDao().getAll().distinctUntilChanged().collect { list ->
                    _savedModpacks.value = list
                }
            } catch (e: Exception) {
                _events.emit(PackForgeEvent.ShowSnackbar("Error al conectar con la base de datos", true))
            }
        }
    }

    fun clearWebError() { _webImportError.value = null }

    fun importFromWebUrl(context: Context, downloadUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _webImportError.value = null
            _importProgress.value = OperationProgress.Loading("Iniciando descarga...")
            
            val fileName = downloadUrl.substringBefore('?').substringAfterLast('/')
                .takeIf { it.contains(".") } ?: "web_addon.mcaddon"
            
            modrinthRepository.downloadToCache(context, downloadUrl, fileName) { p ->
                _importProgress.value = OperationProgress.Loading("Descargando...", p)
            }.onSuccess { uri ->
                importAddons(context, listOf(uri))
                _events.emit(PackForgeEvent.ShowSnackbar("¡Descarga completada e importada!"))
            }.onFailure {
                _webImportError.value = "Error al descargar: ${it.message}"
                _importProgress.value = OperationProgress.Idle
            }
        }
    }

    fun importAddons(context: Context, uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            _isImporting.value = true
            val newAddons = mutableListOf<Addon>()
            uris.forEachIndexed { index, uri ->
                _importProgress.value = OperationProgress.Loading("Analizando (${index+1}/${uris.size})...", index.toFloat()/uris.size)
                AddonParser.parseFromUri(context, uri)?.let { addon ->
                    AddonUriCache.saveUri(addon.id, uri)
                    newAddons.add(addon)
                }
            }
            if (newAddons.isNotEmpty()) {
                val current = _addons.value.toMutableList()
                current.addAll(newAddons)
                _addons.value = current.mapIndexed { i, a -> a.copy(priority = i) }
                recalculateConflicts()
                _events.emit(PackForgeEvent.ShowSnackbar("Se añadieron ${newAddons.size} addons"))
                _events.emit(PackForgeEvent.Vibration)
            }
            _isImporting.value = false
            _importProgress.value = OperationProgress.Idle
        }
    }

    private fun recalculateConflicts() {
        viewModelScope.launch(Dispatchers.Default) {
            val active = _addons.value.filter { it.enabled }
            PackForgeLog.d("PackForge", "Recalculando conflictos para ${active.size} addons activos")
            val newConflicts = ConflictEngine.analyze(active)
            PackForgeLog.d("PackForge", "Conflictos detectados: ${newConflicts.size}")
            _conflicts.value = newConflicts
            _compatibilityScore.value = ConflictEngine.getCompatibilityScore(active)
            _criticalConflictsCount.value = newConflicts.count { 
                it.severity == ConflictSeverity.CRITICAL && !_resolutions.value.containsKey(it.id)
            }
        }
    }

    fun removeAddon(id: String) {
        AddonUriCache.removeUri(id)
        _addons.value = _addons.value.filter { it.id != id }.mapIndexed { i, a -> a.copy(priority = i) }
        recalculateConflicts()
    }

    fun toggleAddon(id: String) {
        _addons.value = _addons.value.map { if (it.id == id) it.copy(enabled = !it.enabled) else it }
        recalculateConflicts()
    }

    fun moveAddon(id: String, dir: Int) {
        val list = _addons.value.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx == -1 || idx + dir !in list.indices) return
        val temp = list[idx]
        list[idx] = list[idx + dir]
        list[idx + dir] = temp
        _addons.value = list.mapIndexed { i, a -> a.copy(priority = i) }
        recalculateConflicts()
    }

    fun resolveConflict(cId: String, wId: String) {
        _resolutions.value = _resolutions.value.toMutableMap().apply { put(cId, wId) }
        recalculateConflicts()
    }

    fun dismissConflict(cId: String) {
        _resolutions.value = _resolutions.value.toMutableMap().apply { put(cId, "dismiss") }
        recalculateConflicts()
    }

    fun updateMetadata(m: ModpackMetadata) { _metadata.value = m }

    fun saveMinecraftFolderUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val application = getApplication<Application>()
            // Persistir permiso
            application.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or 
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION 
            )
            
            val uriString = uri.toString()
            application.getSharedPreferences("packforge_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("mc_folder_uri", uriString)
                .apply()
            
            _minecraftUri.value = uriString
            _events.emit(PackForgeEvent.ShowSnackbar("¡Conexión con Minecraft establecida!"))
        }
    }

    fun disconnectMinecraft() {
        getApplication<Application>().getSharedPreferences("packforge_prefs", Context.MODE_PRIVATE)
            .edit().remove("mc_folder_uri").apply()
        _minecraftUri.value = null
    }

    fun exportModpack(context: Context, outUri: Uri?, toMc: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _exportState.value = ExportState.Progress("Iniciando exportación...", 0)
                
                // Obtener rutas de archivos de addons activos
                val activeAddons = _addons.value.filter { it.enabled }
                val activeAddonPaths = activeAddons.map { it.sourceFilePath }
                
                if (activeAddonPaths.isEmpty()) {
                    _exportState.value = ExportState.Error("No hay addons activos para fusionar")
                    return@launch
                }
                
                _exportState.value = ExportState.Progress("Procesando recursos...", 5)

                // Procesar icono personalizado si existe
                var customIconPath: String? = null
                val coverUriString = _metadata.value.coverUriString
                if (!coverUriString.isNullOrEmpty()) {
                    try {
                        val uri = Uri.parse(coverUriString)
                        val tempIcon = File(context.cacheDir, "temp_pack_icon_${System.currentTimeMillis()}.png")
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            tempIcon.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        if (tempIcon.exists()) {
                            customIconPath = tempIcon.absolutePath
                        }
                    } catch (e: Exception) {
                        PackForgeLog.e("PackForge", "Error al procesar el icono: ${e.message}")
                    }
                }

                // Sanitizar nombre del archivo
                val customName = _metadata.value.name.trim()
                    .replace(Regex("[/\\\\:*?\"<>|]"), "_") // Eliminar caracteres inválidos
                    .ifBlank { "PackForge_Modpack" }
                
                // Usar PackForgeOrchestrator para fusionar
                val outputDir = context.cacheDir.absolutePath
                val addonNames = activeAddons.map { it.name }
                
                val result = PackForgeOrchestrator.mergeAddons(
                    addonPaths = activeAddonPaths,
                    outputDir = outputDir,
                    progressCallback = object : PackForgeOrchestrator.ProgressCallback {
                        override suspend fun onProgress(message: String) {
                            val percent = when {
                                message.contains("Extrayendo") -> 10
                                message.contains("Clasificando") -> 30
                                message.contains("Behavior") -> 45
                                message.contains("Resource") -> 65
                                message.contains("manifiestos") -> 80
                                message.contains("Empaquetando") -> 90
                                message.contains("Limpiando") -> 98
                                else -> 5
                            }
                            _exportState.value = ExportState.Progress(message, percent)
                        }
                    },
                    addonNames = addonNames,
                    customName = customName,
                    customIconPath = customIconPath
                )
                
                // Borrar icono temporal si se creó
                customIconPath?.let { File(it).delete() }
                
                // Update merge conflicts after merge
                updateMergeConflicts()

                if (result.success && result.outputPath != null) {
                    _exportState.value = ExportState.Progress("Guardando archivo final...", 99)
                    val outputFile = File(result.outputPath)
                    
                    // Si no se seleccionó URI, copiar a Downloads por defecto
                    val finalPath = if (outUri == null) {
                        val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                        val targetFile = File(downloadsDir, "$customName.mcaddon")
                        try {
                            outputFile.copyTo(targetFile, overwrite = true)
                            targetFile.absolutePath
                        } catch (e: Exception) {
                            PackForgeLog.e("PackForge", "Error al copiar a Downloads: ${e.message}")
                            outputFile.absolutePath
                        }
                    } else {
                        // Copiar a la ubicación seleccionada por el usuario
                        try {
                            context.contentResolver.openOutputStream(outUri)?.use { output ->
                                outputFile.inputStream().copyTo(output)
                            }
                            outUri.toString()
                        } catch (e: Exception) {
                            PackForgeLog.e("PackForge", "Error al guardar en ubicación seleccionada: ${e.message}")
                            outputFile.absolutePath
                        }
                    }
                    
                    _exportState.value = ExportState.Success(
                        fileName = "$customName.mcaddon",
                        filePath = finalPath,
                        importedToMinecraft = false
                    )
                    saveModpackToHistory(context, "$customName.mcaddon", finalPath)
                    _events.emit(PackForgeEvent.ShowSnackbar("¡Modpack fusionado con éxito!"))
                } else {
                    _exportState.value = ExportState.Error(result.errorMessage ?: "Fallo en la fusión")
                }
            } catch (e: Exception) {
                PackForgeLog.e("PackForge_Export", "Error en exportación", e)
                _exportState.value = ExportState.Error(e.message ?: "Error desconocido")
            } finally {
                _importProgress.value = OperationProgress.Idle
            }
        }
    }

    fun resetExportState() { _exportState.value = ExportState.Idle }

    fun clearAll() {
        AddonUriCache.clear()
        _addons.value = emptyList()
        _conflicts.value = emptyList()
        _resolutions.value = emptyMap()
        _metadata.value = ModpackMetadata()
        _compatibilityScore.value = 100
        _criticalConflictsCount.value = 0
        editingModpackId = null
    }

    private suspend fun saveModpackToHistory(context: Context, fileName: String, filePath: String) {
        withContext(Dispatchers.IO) {
            val db = database ?: PackForgeDatabase.getInstance(context)
            val active = _addons.value // Guardamos todos, pero marcamos cuáles están activos
            
            val modpackId = editingModpackId ?: UUID.randomUUID().toString()

            // ─── PERSISTIR ICONO DE PORTADA ──────────────────
            var persistentCoverPath = _metadata.value.coverUriString
            if (!persistentCoverPath.isNullOrEmpty() && persistentCoverPath.startsWith("content://")) {
                try {
                    val iconDir = File(context.filesDir, "modpack_icons")
                    if (!iconDir.exists()) iconDir.mkdirs()
                    
                    val iconFile = File(iconDir, "${modpackId}_cover.png")
                    val uri = Uri.parse(persistentCoverPath)
                    
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        iconFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    persistentCoverPath = iconFile.absolutePath
                    PackForgeLog.d("PackForge", "Icono persistido en: $persistentCoverPath")
                } catch (e: Exception) {
                    PackForgeLog.e("PackForge", "Error al persistir icono: ${e.message}")
                }
            }
            
            val saved = SavedModpack(
                id = modpackId,
                name = _metadata.value.name,
                author = _metadata.value.author,
                version = _metadata.value.version,
                mcVersion = _metadata.value.mcVersion,
                description = _metadata.value.description,
                addonNames = Gson().toJson(active.filter { it.enabled }.map { it.name }),
                addonCount = active.count { it.enabled },
                filePath = filePath,
                fileName = fileName,
                createdAt = System.currentTimeMillis(),
                coverUriString = persistentCoverPath,
                tags = _metadata.value.tags.joinToString(","),
                addonsJson = Gson().toJson(active)
            )
            db.savedModpackDao().insert(saved)
            // Si era nuevo, ahora ya tenemos su ID
            editingModpackId = saved.id
        }
    }

    fun loadModpack(m: SavedModpack) {
        viewModelScope.launch {
            try {
                val type = object : com.google.gson.reflect.TypeToken<List<Addon>>() {}.type
                val restored: List<Addon> = Gson().fromJson(m.addonsJson, type)
                
                // Verificar que los archivos existen, si no, avisar al usuario
                val missingFiles = restored.any { !java.io.File(it.sourceFilePath).exists() }
                if (missingFiles) {
                    _events.emit(PackForgeEvent.ShowSnackbar("Algunos archivos del modpack original se perdieron.", true))
                }

                editingModpackId = m.id
                _addons.value = restored
                _metadata.value = ModpackMetadata(
                    name = m.name, 
                    author = m.author, 
                    version = m.version, 
                    mcVersion = m.mcVersion, 
                    description = m.description, 
                    iconEmoji = "🎮", 
                    tags = m.tags.split(",").filter { it.isNotBlank() }, 
                    coverUriString = m.coverUriString
                )
                
                recalculateConflicts()
                _events.emit(PackForgeEvent.ShowSnackbar("Modpack '${m.name}' cargado para editar"))
            } catch (e: Exception) {
                _events.emit(PackForgeEvent.ShowSnackbar("Error al cargar modpack", true))
            }
        }
    }

    fun deleteFromHistory(context: Context, id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val db = database ?: PackForgeDatabase.getInstance(context)
            db.savedModpackDao().deleteById(id)
            if (editingModpackId == id) {
                withContext(Dispatchers.Main) { clearAll() }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        AddonUriCache.clear()
    }
}
