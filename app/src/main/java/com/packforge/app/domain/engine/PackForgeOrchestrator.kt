package com.packforge.app.domain.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.packforge.app.util.PackForgeConfig
import com.packforge.app.util.PackForgeLog
import com.packforge.app.util.FileUtils
import com.packforge.app.util.logFile
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.io.BufferedOutputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipFile
import com.packforge.app.domain.engine.JsonDeepMerger
import com.packforge.app.domain.engine.FusionIssue
import com.packforge.app.domain.engine.FusionReportBuilder

object PackForgeOrchestrator {
    private const val TAG = "PackForge_Orchestrator"

    /** Directorios/archivos que se excluyen del merge genérico porque tienen fusiones dedicadas. */
    private val CRITICAL_DIRS = listOf(
        "entity/",            // RP entity/*.entity.json → mergeEntityDefinitions
        "attachables/",       // RP attachables/ → mergeEntityDefinitions + graph resolver
        "particles/",         // RP particles/ → mergeParticles + graph resolver
        "render_controllers/", // RP render_controllers/ → mergeRenderControllers
        "animations/",        // RP animations/ → mergeAnimations
        "animation_controllers/", // RP animation_controllers/ → mergeAnimations
        "blocks.json",        // RP blocks.json → mergeBlocksJson
        "textures/terrain_texture.json", // → mergeTerrainTexture
        "textures/item_texture.json",    // → mergeItemTexture
        "sounds.json",        // → mergeSoundsJson
        "sound_definitions.json", // → mergeSoundDefinitions
        "textures/flipbook_textures.json", // → mergeFlipbookTextures
        "player.json",        // RP player.json → analyzed, not auto-merged
        "player.entity.json", // BP player.entity.json → mergePlayerEntity
        "loot_tables/",       // BP loot_tables/ → mergeLootTables
        "recipes/",           // BP recipes/ → mergeRecipes
    )

    /** Directorios críticos específicos del Behavior Pack. */
    private val CRITICAL_DIRS_BP = listOf(
        "loot_tables/",
        "recipes/",
        "player.entity.json",
    )

    /** Directorios críticos específicos del Resource Pack. */
    private val CRITICAL_DIRS_RP = listOf(
        "entity/",
        "attachables/",
        "particles/",
        "render_controllers/",
        "animations/",
        "animation_controllers/",
        "blocks.json",
        "textures/terrain_texture.json",
        "textures/item_texture.json",
        "sounds.json",
        "sound_definitions.json",
        "textures/flipbook_textures.json",
        "player.json",
    )

    /**
     * Resultado de la fusión de addons
     */
    data class MergeResult(
        val success: Boolean,
        val outputPath: String?,
        val bpUuid: String?,
        val rpUuid: String?,
        val totalJsonsMerged: Int,
        val errorMessage: String? = null,
        val validationResult: PackForgeValidator.ValidationResult? = null,
        val reportPath: String? = null
    )

    /**
     * Callback para progreso de fusión
     */
    interface ProgressCallback {
        suspend fun onProgress(message: String)
    }

    /**
     * Fusiona múltiples addons y crea el modpack final
     * 
     * @param addonPaths Lista de rutas de archivos .mcaddon/.mcpack
     * @param outputDir Directorio donde se guardará el modpack final
     * @param progressCallback Callback opcional para reportar progreso
     * @return MergeResult con el resultado de la operación
     */
    suspend fun mergeAddons(
        addonPaths: List<String>,
        outputDir: String,
        progressCallback: ProgressCallback? = null,
        addonNames: List<String> = emptyList(),
        customName: String = "PackForge_Modpack",
        customAuthor: String = "",
        customVersion: String = "1.0.0",
        customDescription: String = "",
        customIconPath: String? = null
    ): MergeResult {
        val tInicio = System.currentTimeMillis()
        val extractedDirs = mutableListOf<String>()
        val tempDir = File(outputDir, "temp_merge")
        val resourceRegistry = ResourcePathRegistry()
        var idRenames: List<IdentifierRemapper.RemapEntry> = emptyList()

        // Clear previous state
        JsonDeepMerger.clearConflicts()
        ConflictRegistry.clear()
        FolderAliasRegistry.clear()
        DirIndexCache.clear()
        FusionReportBuilder.clear()

        try {
            // a) EXTRAER TODOS LOS ADDONS
            progressCallback?.onProgress("Extrayendo addons...")
            PackForgeLog.d("PackForge_Export", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            PackForgeLog.d("PackForge_Export", "🚀 INICIANDO EXPORTACIÓN: ${addonPaths.size} addons")

            for ((index, addonPath) in addonPaths.withIndex()) {
                val addonFile = File(addonPath)
                val addonDisplayName = addonNames.getOrNull(index) ?: addonFile.nameWithoutExtension
                progressCallback?.onProgress("Extrayendo ${index + 1}/${addonPaths.size}: $addonDisplayName...")

                if (!addonFile.exists()) {
                    PackForgeLog.w(TAG, "⚠️ Addon no existe, saltando: $addonPath")
                    FusionReportBuilder.addIssue(addonDisplayName, "ZIP", "El archivo no existe en disco", "El archivo del addon no se encontró.", FusionIssue.Severity.FATAL)
                    continue
                }

                PackForgeLog.d("PackForge_Perf", "⏱️ Extrayendo ${addonFile.name} (${addonFile.length() / 1024} KB)")
                val tAddonInicio = System.currentTimeMillis()
                val extractDir = File(tempDir, "extracted_${System.currentTimeMillis()}_${addonFile.nameWithoutExtension}")
                val extractedPath = try {
                    AddonExtractor.extractAddon(addonPath, extractDir.absolutePath)
                } catch (e: Exception) {
                    PackForgeLog.e(TAG, "Error extrayendo $addonDisplayName: ${e.message}")
                    FusionReportBuilder.addIssue(addonDisplayName, "ZIP", e.message ?: "Error desconocido", "No se pudo extraer el addon.", FusionIssue.Severity.FATAL)
                    null
                }
                PackForgeLog.d("PackForge_Perf", "⏱️ Extraído en ${(System.currentTimeMillis() - tAddonInicio) / 1000.0}s")

                if (extractedPath != null) {
                    extractedDirs.add(extractedPath)
                }
            }

            val tExtraccion = System.currentTimeMillis()
            PackForgeLog.d("PackForge_Perf", "⏱️ Extracción total: ${(tExtraccion - tInicio) / 1000.0}s")

            if (extractedDirs.isEmpty()) {
                val reportStr = buildString {
                    appendLine("FUSIÓN FALLIDA: ningún addon pudo extraerse.")
                    appendLine(FusionReportBuilder.generateReport())
                }
                return MergeResult(false, null, null, null, 0, reportStr)
            }
            
            // b) CLASIFICAR ADDONS POR TIPO
            progressCallback?.onProgress("Clasificando addons...")
            PackForgeLog.d("PackForge_Debug", "Clasificando ${extractedDirs.size} addons extraídos")
            
            val bpDirs = mutableListOf<String>()
            val rpDirs = mutableListOf<String>()
            
            for ((idx, extractedDir) in extractedDirs.withIndex()) {
                val dirFile = File(extractedDir)
                PackForgeLog.d(TAG, "🔍 Analizando directorio: ${dirFile.name}") // DIAGNÓSTICO
                progressCallback?.onProgress("Clasificando addon ${idx + 1}/${extractedDirs.size}: ${dirFile.name}...")
                val analysis = AddonExtractor.analyzeExtractedAddon(extractedDir)
                logFile { "Addon $extractedDir clasificado como: ${analysis.addonType} | JSONs=${analysis.totalJsonFiles} manifests=${analysis.manifestFiles.size} items=${analysis.itemFiles.size} entities=${analysis.entityFiles.size}" }
                
                when (analysis.addonClassification) {
                    is AddonExtractor.AddonClassification.BEHAVIOR_PACK -> {
                        bpDirs.add(extractedDir)
                        logFile { "  -> Agregado a lista de BPs" }
                    }
                    is AddonExtractor.AddonClassification.RESOURCE_PACK -> {
                        rpDirs.add(extractedDir)
                        logFile { "  -> Agregado a lista de RPs" }
                    }
                    is AddonExtractor.AddonClassification.BOTH -> {
                        // ⭐ AMBAS CARPETAS DEBEN PROCESARSE ⭐
                        logFile { "🔀 Procesando BOTH: ${File(extractedDir).name} (BP=${analysis.addonClassification.bpSubfolder.name}, RP=${analysis.addonClassification.rpSubfolder.name})" }
                        
                        // Usar directamente las rutas del clasificador
                        val (bpPath, rpPath) = separateBothAddonDirect(
                            analysis.addonClassification.bpSubfolder,
                            analysis.addonClassification.rpSubfolder,
                            tempDir
                        )
                        if (bpPath != null) {
                            bpDirs.add(bpPath)
                            PackForgeLog.d("PackForge_Debug", "  -> BP separado en: $bpPath")
                        }
                        if (rpPath != null) {
                            rpDirs.add(rpPath)
                            PackForgeLog.d("PackForge_Debug", "  -> RP separado en: $rpPath")
                        }
                    }
                    is AddonExtractor.AddonClassification.MULTI -> {
                        // 📦 Addon con packs anidados (.mcpack/.zip): clasificar y fusionar cada uno.
                        // CASO "More Tools": el .mcaddon contiene BP.mcpack y RP.mcpack.
                        val packs = analysis.addonClassification.packs
                        PackForgeLog.d("PackForge_Process", "📦 Procesando MULTI (${packs.size} packs anidados): ${File(extractedDir).name}")
                        packs.forEach { packDir ->
                            when (val sub = AddonExtractor.classify(packDir)) {
                                is AddonExtractor.AddonClassification.BEHAVIOR_PACK -> {
                                    val root = AddonExtractor.resolvePackRoot(packDir)
                                    bpDirs.add(root.absolutePath)
                                    PackForgeLog.d("PackForge_Debug", "  -> Pack anidado BP: ${root.name}")
                                }
                                is AddonExtractor.AddonClassification.RESOURCE_PACK -> {
                                    val root = AddonExtractor.resolvePackRoot(packDir)
                                    rpDirs.add(root.absolutePath)
                                    PackForgeLog.d("PackForge_Debug", "  -> Pack anidado RP: ${root.name}")
                                }
                                is AddonExtractor.AddonClassification.BOTH -> {
                                    val (bpPath, rpPath) = separateBothAddonDirect(
                                        sub.bpSubfolder, sub.rpSubfolder, tempDir
                                    )
                                    if (bpPath != null) {
                                        bpDirs.add(bpPath)
                                        PackForgeLog.d("PackForge_Debug", "  -> Pack anidado BOTH -> BP en: $bpPath")
                                    }
                                    if (rpPath != null) {
                                        rpDirs.add(rpPath)
                                        PackForgeLog.d("PackForge_Debug", "  -> Pack anidado BOTH -> RP en: $rpPath")
                                    }
                                }
                                else -> PackForgeLog.w("PackForge_Debug", "⚠️ Pack anidado ignorado: ${packDir.name}")
                            }
                        }
                    }
                    is AddonExtractor.AddonClassification.UNKNOWN -> {
                        PackForgeLog.w("PackForge_Debug", "  -> Addon UNKNOWN, intentando detectar por manifest")
                        // Intentar detectar por estructura de carpetas
                        if (File(extractedDir, "manifest.json").exists()) {
                            val manifestContent = File(extractedDir, "manifest.json").readText()
                            if (manifestContent.contains("\"data\"")) {
                                bpDirs.add(extractedDir)
                                PackForgeLog.d("PackForge_Debug", "  -> Detectado como BP por manifest")
                            }
                            if (manifestContent.contains("\"resources\"")) {
                                rpDirs.add(extractedDir)
                                PackForgeLog.d("PackForge_Debug", "  -> Detectado como RP por manifest")
                            }
                        }
                    }
                }
            }
            
            PackForgeLog.d("PackForge_Debug", "BPs detectados: ${bpDirs.size}")
            PackForgeLog.d("PackForge_Debug", "RPs detectados: ${rpDirs.size}")
            
            // LOG OBLIGATORIO: Resumen de clasificación
            PackForgeLog.d("PackForge_Export", "📊 RESUMEN DE CLASIFICACIÓN:")
            PackForgeLog.d("PackForge_Export", "  BPs detectados: ${bpDirs.size}")
            PackForgeLog.d("PackForge_Export", "  RPs detectados: ${rpDirs.size}")

            // ═══ RENAMESPACER: hacer compatibles addons que definen el MISMO
            // identificador (entity/item/recipe). Renombra los que colisionan y
            // reescribe sus referencias ANTES de fusionar para que coexistan.
            idRenames = IdentifierRemapper.run(
                behaviorDirs = bpDirs.map { File(it) },
                resourceDirs = rpDirs.map { File(it) }
            )
            
            // c) FUSIONAR BEHAVIOR PACKS
            val mergedBpDir = File(tempDir, "merged_bp")
            val bpJsonCount = if (bpDirs.isNotEmpty()) {
                progressCallback?.onProgress("Fusionando Behavior Packs...")
                PackForgeLog.d("PackForge_Debug", "Iniciando fusión de ${bpDirs.size} Behavior Packs")
                mergePackType(bpDirs, mergedBpDir, "manifest.json", resourceRegistry, CRITICAL_DIRS_BP)
            } else 0
            
            // d) FUSIONAR RESOURCE PACKS
            val mergedRpDir = File(tempDir, "merged_rp")
            val rpJsonCount = if (rpDirs.isNotEmpty()) {
                progressCallback?.onProgress("Fusionando Resource Packs...")
                PackForgeLog.d("PackForge_Debug", "Iniciando fusión de ${rpDirs.size} Resource Packs")
                mergePackType(rpDirs, mergedRpDir, "manifest.json", resourceRegistry, CRITICAL_DIRS_RP)
            } else 0
            
            val totalJsonsMerged = bpJsonCount + rpJsonCount
            PackForgeLog.d("PackForge_Debug", "Total JSONs fusionados: $totalJsonsMerged")

            val tFusionFin = System.currentTimeMillis()
            PackForgeLog.d("PackForge_Perf", "⏱️ Fusión: ${(tFusionFin - tExtraccion) / 1000.0}s")
            
            // Cachear índices de los BPs/RPs extraídos una sola vez para Reuse por grafo/validator/healer
            PackForgeLog.d("PackForge_Perf", "⏱️ Iniciando indexación de ${bpDirs.size} BPs + ${rpDirs.size} RPs...")
            val bpFilesForCache = bpDirs.map { File(it) }
            val rpFilesForCache = rpDirs.map { File(it) }
            bpFilesForCache.forEach { DirIndexCache.index(it) }
            rpFilesForCache.forEach { DirIndexCache.index(it) }
            PackForgeLog.d("PackForge_Perf", "⏱️ Indexación: ${(System.currentTimeMillis() - tFusionFin) / 1000.0}s (${DirIndexCache.totalFilesIndexed()} archivos)")
            
            // Verificar archivos en directorios fusionados
            val bpFileCount = DirIndexCache.index(mergedBpDir).allFiles.size
            val rpFileCount = DirIndexCache.index(mergedRpDir).allFiles.size
            PackForgeLog.d("PackForge_Debug", "Archivos en BP fusionado: $bpFileCount")
            PackForgeLog.d("PackForge_Debug", "Archivos en RP fusionado: $rpFileCount")
            PackForgeLog.d("PackForge_Debug", "=== FIN FUSIÓN ===")
            
            // ⭐⭐⭐ CRÍTICO: Fusionar archivos críticos de Bedrock (terrain_texture, item_texture, blocks.json, .lang,
            // geometrías 3D, flipbook textures, entity definitions, render controllers, animations, sounds y material_instances)
            // Estos archivos DEBEN fusionarse DESPUÉS de mergePackType y ANTES de generar manifiestos/ZIP
            // rpDirs contiene las rutas de TODOS los RPs extraídos (NO el mergedRpDir)
            // bpDirs contiene las rutas de TODOS los BPs extraídos (para material_instances)
            // extractedDirs contiene las rutas de TODOS los addons extraídos (para .lang)
            var soundKeyRenames: Map<String, String> = emptyMap()
            var dependencyNotes: List<String> = emptyList()
            if (rpDirs.isNotEmpty() || bpDirs.isNotEmpty()) {
                progressCallback?.onProgress("Fusionando archivos críticos de Bedrock...")
                PackForgeLog.d("PackForge_Export", "🔧 FUSIONANDO ARCHIVOS CRÍTICOS DE BEDROCK...")

                // Convertir listas de strings a List<File>
                val rpDirFiles = rpDirs.map { File(it) }
                val bpDirFiles = bpDirs.map { File(it) }
                val addonDirFiles = extractedDirs.map { File(it) }

                // ⭐ PRE-CACHEAR: indexar TODAS las raíces en DirIndexCache ANTES de
                // que los mergers hagan walkTopDown. Esto convierte N×M walkTopDown
                // redundantes en UNA sola pasada por addon.
                val tCacheInicio = System.currentTimeMillis()
                val allDirsToIndex = (rpDirFiles + bpDirFiles + addonDirFiles).distinct()
                allDirsToIndex.forEach { DirIndexCache.index(it) }
                PackForgeLog.d("PackForge_Perf", "⏱️ Pre-cacheo de ${allDirsToIndex.size} raíces: ${(System.currentTimeMillis() - tCacheInicio) / 1000.0}s")

                val merger = BedrockCriticalFilesMerger

                // 1. Fusionar terrain_texture.json (mapea bloques → texturas) - CRÍTICO
                progressCallback?.onProgress("Fusionando terrain_texture.json...")
                merger.mergeTerrainTexture(rpDirFiles, mergedRpDir)

                // 2. Fusionar item_texture.json (mapea items → texturas) - CRÍTICO
                progressCallback?.onProgress("Fusionando item_texture.json...")
                merger.mergeItemTexture(rpDirFiles, mergedRpDir)

                // 3. Fusionar blocks.json (define renderizado de bloques, conservando format_version alto) - CRÍTICO
                progressCallback?.onProgress("Fusionando blocks.json...")
                merger.mergeBlocksJson(rpDirFiles, mergedRpDir)

                // 4. Fusionar entity/*.entity.json (definiciones de mobs 3D) - CRÍTICO para mobs
                progressCallback?.onProgress("Fusionando entidades...")
                merger.mergeEntityDefinitions(rpDirFiles, mergedRpDir)

                // 5. Fusionar render_controllers (controladores de render de mobs) - CRÍTICO para mobs
                progressCallback?.onProgress("Fusionando render_controllers...")
                merger.mergeRenderControllers(rpDirFiles, mergedRpDir)

                // 6. Fusionar animations + animation_controllers - CRÍTICO para animaciones
                progressCallback?.onProgress("Fusionando animaciones...")
                merger.mergeAnimations(rpDirFiles, mergedRpDir)

                // 7a. Fusionar sound_definitions.json (definiciones de audio).
                // Duplicados con contenido distinto → clave con alias + mapa de renombres.
                progressCallback?.onProgress("Fusionando sound_definitions.json...")
                soundKeyRenames = merger.mergeSoundDefinitions(rpDirFiles, mergedRpDir)

                // 7b. Fusionar sounds.json (eventos → definiciones de audio)
                progressCallback?.onProgress("Fusionando sounds.json...")
                merger.mergeSoundsJson(rpDirFiles, mergedRpDir)

                // 7c. Actualizar referencias a claves de sonido renombradas en RP y BP
                progressCallback?.onProgress("Aplicando renombres de sonidos...")
                merger.applySoundKeyRenames(mergedRpDir, mergedBpDir, soundKeyRenames)

                // 8. Fusionar .lang + crear languages.json (CRÍTICO para nombres "desconocido")
                // En AMBOS packs (BP y RP pueden tener traducciones)
                progressCallback?.onProgress("Fusionando traducciones .lang...")
                merger.mergeLangFiles(addonDirFiles, mergedRpDir)
                merger.mergeLangFiles(addonDirFiles, mergedBpDir)

                // 9. Fusionar geometrías 3D (.geo.json) deduplicando por identifier
                // CRÍTICO para bloques con geometría compleja: enredaderas, vallas, cruces, plantas 3D
                progressCallback?.onProgress("Fusionando geometrías 3D...")
                merger.mergeGeometryFiles(rpDirFiles, mergedRpDir)

                // 10. Fusionar flipbook_textures.json (texturas animadas)
                progressCallback?.onProgress("Fusionando flipbook_textures...")
                merger.mergeFlipbookTextures(rpDirFiles, mergedRpDir)

                // 11. Verificar material_instances del BP contra terrain_texture.json del RP
                // Si un bloque referencía una textura no mapeada, se agrega y se copia el PNG
                progressCallback?.onProgress("Fusionando material_instances...")
                merger.mergeMaterialInstances(
                    bpDirs = bpDirFiles,
                    rpDirs = rpDirFiles,
                    mergedBpDir = mergedBpDir,
                    mergedRpDir = mergedRpDir
                )

                // 12. Fusionar RECETAS (crafting, horno, alquimia, etc.) - CRÍTICO para items funcionales
                progressCallback?.onProgress("Fusionando recetas...")
                merger.mergeRecipes(bpDirFiles, mergedBpDir)

                // 13. Fusionar LOOT TABLES (drops de mobs, bloques, cofres) - CRÍTICO para drops
                progressCallback?.onProgress("Fusionando loot_tables...")
                merger.mergeLootTables(bpDirFiles, mergedBpDir)

                // 14. PLAYER.ENTITY.JSON - fusión profunda del jugador entre addons
                // (base = manifest de versión más alta; colisiones numéricas → conflicto HIGH)
                progressCallback?.onProgress("Fusionando player.entity.json...")
                merger.mergePlayerEntity(bpDirFiles, mergedBpDir)

                // 15. PARTÍCULAS - dedupe por identifier con alias único
                progressCallback?.onProgress("Fusionando partículas...")
                merger.mergeParticles(rpDirFiles, mergedRpDir, mergedBpDir)
                
                // 16. RESOLUCIÓN DE DEPENDENCIAS POR GRAFO (FASES 1-3):
                // indexa TODOS los inputs por identificador interno y resuelve
                // recursivamente cada referencia real de entidades/items del destino.
                progressCallback?.onProgress("Resolviendo grafo de dependencias...")
                val graph = DependencyGraphResolver.run(
                    rpDirs = rpDirFiles,
                    bpDirs = bpDirFiles,
                    outputRp = mergedRpDir,
                    outputBp = mergedBpDir
                )
                dependencyNotes = graph.notes

                // FASE 4: si falta algo crítico NO-vanilla, registrar error pero continuar (Fusión Robusta).
                if (graph.criticalErrors.isNotEmpty()) {
                    graph.criticalErrors.forEach { error ->
                        FusionReportBuilder.addIssue(
                            "Grafo de dependencias",
                            error.requester,
                            "Falta ${error.type.label} '\${error.id}' requerido por \${error.requester}",
                            "Al addon le falta un recurso necesario para funcionar. Algunos objetos podrían verse invisibles o rotos.",
                            FusionIssue.Severity.RECOVERABLE
                        )
                    }
                }

                PackForgeLog.d("PackForge_Export", "✅ Archivos críticos fusionados exitosamente")
            }
            
            // e) GENERAR MANIFIESTOS VINCULADOS
            progressCallback?.onProgress("Generando manifiestos...")
            val (bpUuid, rpUuid) = generateLinkedManifests(
                bpDirs = bpDirs,
                rpDirs = rpDirs,
                mergedBpDir = mergedBpDir,
                mergedRpDir = mergedRpDir,
                customName = customName,
                customAuthor = customAuthor,
                customVersion = customVersion,
                customDescription = customDescription
            )
            
            PackForgeLog.d(TAG, "UUIDs generados - BP: $bpUuid, RP: $rpUuid")

            // f2) ANÁLISIS DE COLISIONES DE SCRIPTS (no bloqueante)
            progressCallback?.onProgress("Analizando scripts...")
            val scriptFindings = ScriptCollisionAnalyzer.analyze(File(mergedBpDir, "scripts"))
            scriptFindings.forEach { PackForgeLog.w(TAG, it) }

            // f) EJECUTAR VALIDADOR DE REFERENCIAS CRUZADAS
            progressCallback?.onProgress("Validando referencias...")
            PackForgeLog.d("PackForge_Export", "🔧 PASO 5: Ejecutando validador de referencias...")
            val validationResult = PackForgeValidator.validate(
                bpDir = mergedBpDir,
                rpDir = mergedRpDir,
                originalAddons = extractedDirs,
                progressCallback = progressCallback
            )
            PackForgeLog.d("PackForge_Export", "🔧 PASO 5 completado")

            // g1) HEALER: análisis post-merge NO bloqueante (texturas rotas, fuzzy match)
            progressCallback?.onProgress("Analizando integridad del pack...")
            PackForgeLog.d("PackForge_Export", "💊 PASO 5b: Ejecutando PackForgeHealer...")
            val healReport = try {
                PackForgeHealer.heal(mergedBpDir, mergedRpDir)
            } catch (e: Exception) {
                PackForgeLog.w("PackForge_Export", "Healer tuvo error no bloqueante: ${e.message}")
                PackForgeHealer.HealReport(emptyList(), emptyList(), listOf("Error: ${e.message}"))
            }
            if (healReport.hasIssues) {
                PackForgeLog.w("PackForge_Export", "💊 ${healReport.summary}")
            }
            PackForgeLog.d("PackForge_Export", "💊 PASO 5b completado")

            // g) APLICAR ICONO PERSONALIZADO (AL FINAL, DESPUÉS DE TODO)
            PackForgeLog.d("PackForge_Export", "🔧 PASO 6: Aplicando icono personalizado...")
            applyCustomIcon(mergedBpDir, mergedRpDir, customIconPath)
            PackForgeLog.d("PackForge_Export", "🔧 PASO 6 completado")

            val tCriticosValidacionIconoFin = System.currentTimeMillis()
            PackForgeLog.d("PackForge_Perf", "⏱️ Críticos+Validador+Icono: ${(tCriticosValidacionIconoFin - tFusionFin) / 1000.0}s")

// VERIFICACIÓN: listar archivos en mergedBpDir y mergedRpDir
             mergedBpDir.listFiles()?.forEach { file ->
                 PackForgeLog.d("PackForge_Export", "   BP file: ${file.name} (${file.length()} bytes)")
             }
             mergedRpDir.listFiles()?.forEach { file ->
                 PackForgeLog.d("PackForge_Export", "   RP file: ${file.name} (${file.length()} bytes)")
             }

// h) EMPAQUETAR
            progressCallback?.onProgress("Empaquetando modpack...")
            val outputFile = File(outputDir, "$customName.mcaddon")

            // LOG OBLIGATORIO: Antes de crear ZIP
            PackForgeLog.d("PackForge_Export", "📦 CREANDO ZIP:")
            PackForgeLog.d("PackForge_Export", "  Archivo de salida: ${outputFile.name}")
            PackForgeLog.d("PackForge_Export", "  Extensión: ${outputFile.extension}")
            if (outputFile.extension != "mcaddon") {
                PackForgeLog.e("PackForge_Export", "❌ ERROR: La extensión NO es .mcaddon!")
            }

            // CRÍTICO: Verificar antes de crear ZIP
            if (bpDirs.isNotEmpty() && rpDirs.isNotEmpty()) {
                verifyBeforeZip(mergedBpDir, mergedRpDir)
            }

            createMcAddon(
                if (bpDirs.isNotEmpty()) mergedBpDir else null,
                if (rpDirs.isNotEmpty()) mergedRpDir else null,
                outputFile
            )

            // ══ VALIDACIÓN POST-FUSIÓN + REPORTE LEGIBLE ══
            progressCallback?.onProgress("Generando reporte de fusión...")
            val syntaxErrors = MergeReportGenerator.validateJsonSyntax(mergedBpDir) +
                MergeReportGenerator.validateJsonSyntax(mergedRpDir)
            syntaxErrors.forEach { PackForgeLog.e(TAG, it) }

            // ÚLTIMA BARRERA: validar el paquete FINAL empaquetado (referencias de
            // entidades contra las entradas reales del ZIP). Los errores NO bloquean
            // la entrega pero SÍ llegan a ConflictRegistry/UI y al reporte.
            val zipValidation = EntityDependencyResolver.validateMerge(outputFile.absolutePath)
            zipValidation.take(30).forEach { line ->
                ConflictRegistry.logConflict(
                    severity = com.packforge.app.domain.model.ConflictSeverity.HIGH,
                    type = "BROKEN_REFERENCE_ZIP",
                    file = outputFile.name,
                    addon1 = "paquete final",
                    addon2 = "-",
                    description = line
                )
            }

            val reportLines = MergeReportGenerator.build(
                MergeReportGenerator.Inputs(
                    idRenames = idRenames,
                    resourceAliases = resourceRegistry.aliasLog.toList(),
                    soundRenames = soundKeyRenames,
                    scriptFindings = scriptFindings,
                    syntaxErrors = syntaxErrors,
                    validationResult = validationResult,
                    dependencyNotes = dependencyNotes,
                    zipValidation = zipValidation,
                    totalJsonsMerged = totalJsonsMerged,
                    elapsedSeconds = (System.currentTimeMillis() - tInicio) / 1000.0
                )
            )
            val reportFile = File(outputDir, "fusion_report.txt")
            MergeReportGenerator.writeToFile(reportLines, reportFile)

            val tZip = System.currentTimeMillis()
            PackForgeLog.d("PackForge_Perf", "⏱️ ZIP: ${(tZip - tCriticosValidacionIconoFin) / 1000.0}s")
            PackForgeLog.d("PackForge_Perf", "⏱️ TOTAL: ${(tZip - tInicio) / 1000.0}s")

            // LOG OBLIGATORIO: Al final del ZIP
            PackForgeLog.d("PackForge_Export", "✅ ARCHIVO CREADO:")
            PackForgeLog.d("PackForge_Export", "  Ruta: ${outputFile.absolutePath}")
            PackForgeLog.d("PackForge_Export", "  Tamaño: ${outputFile.length() / 1024} KB")
            PackForgeLog.d("PackForge_Export", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            
            // h) LIMPIEZA
            progressCallback?.onProgress("Limpiando temporales...")
            cleanupTempDirs(tempDir)
            val reportPath = File(outputDir, "fusion_report.txt").absolutePath
            OutputStreamWriter(FileOutputStream(reportPath), StandardCharsets.UTF_8).use {
                it.write(FusionReportBuilder.generateReport())
            }

            return MergeResult(
                success = !FusionReportBuilder.hasFatal(),
                outputPath = outputFile.absolutePath,
                bpUuid = bpUuid,
                rpUuid = rpUuid,
                totalJsonsMerged = totalJsonsMerged,
                reportPath = reportPath
            )
            
        } catch (e: Exception) {
            PackForgeLog.e(TAG, "Error crítico en fusión: ${e.message}", e)
            FusionReportBuilder.addIssue("Orchestrator", "Global", e.message ?: "Error desconocido", "Error inesperado al fusionar.", FusionIssue.Severity.FATAL)
            
            val reportPath = File(outputDir, "fusion_report.txt").absolutePath
            OutputStreamWriter(FileOutputStream(reportPath), StandardCharsets.UTF_8).use {
                it.write(FusionReportBuilder.generateReport())
            }
            
            return MergeResult(false, null, null, null, 0, "Error: ${e.message}", null, reportPath)
        } finally {
            // No limpiar tempDir aquí para que el usuario pueda ver los archivos fallidos si hay reporte
        }
    }
    
    /**
     * Separa un addon tipo BOTH en sus componentes BP y RP usando rutas directas
     * CRÍTICO: Usa las rutas directas del clasificador en lugar de buscarlas nuevamente
     */
    private fun separateBothAddonDirect(bpSubfolder: File, rpSubfolder: File, tempDir: File): Pair<String?, String?> {
        PackForgeLog.d("PackForge_Debug", "=== INICIO SEPARACIÓN BOTH DIRECT ===")
        PackForgeLog.d("PackForge_Debug", "BP subfolder: ${bpSubfolder.absolutePath}")
        PackForgeLog.d("PackForge_Debug", "RP subfolder: ${rpSubfolder.absolutePath}")
        
        var bpPath: String? = null
        var rpPath: String? = null
        
        // Separar BP
        if (bpSubfolder.exists()) {
            val bpDir = File(tempDir, "separated_bp_${System.currentTimeMillis()}")
            bpDir.mkdirs()
            
            var bpFileCount = 0
            DirIndexCache.index(bpSubfolder).allFiles.forEach { file ->
                val relativePath = file.relativeTo(bpSubfolder).path
                val targetFile = File(bpDir, relativePath)
                targetFile.parentFile?.mkdirs()
                FileUtils.fastCopy(file, targetFile)
                bpFileCount++

                if (file.name == "manifest.json") {
                    logFile { "  ✅ manifest.json copiado a: ${targetFile.relativeTo(bpDir).path}" }
                }
            }
            
            val bpManifest = File(bpDir, "manifest.json")
            if (bpManifest.exists()) {
                PackForgeLog.d("PackForge_Debug", "✅ BP manifest.json en raíz: ${bpManifest.absolutePath}")
            } else {
                PackForgeLog.e("PackForge_Debug", "❌ ERROR: BP manifest.json NO en raíz de $bpDir")
            }
            
            bpPath = bpDir.absolutePath
            PackForgeLog.d("PackForge_Debug", "✅ Procesando separated_bp: $bpFileCount archivos en $bpPath")
        }
        
        // Separar RP
        if (rpSubfolder.exists()) {
            val rpDir = File(tempDir, "separated_rp_${System.currentTimeMillis()}")
            rpDir.mkdirs()
            
            var rpFileCount = 0
            DirIndexCache.index(rpSubfolder).allFiles.forEach { file ->
                val relativePath = file.relativeTo(rpSubfolder).path
                val targetFile = File(rpDir, relativePath)
                targetFile.parentFile?.mkdirs()
                FileUtils.fastCopy(file, targetFile)
                rpFileCount++

                if (file.name == "manifest.json") {
                    logFile { "  ✅ manifest.json copiado a: ${targetFile.relativeTo(rpDir).path}" }
                }
            }
            
            val rpManifest = File(rpDir, "manifest.json")
            if (rpManifest.exists()) {
                PackForgeLog.d("PackForge_Debug", "✅ RP manifest.json en raíz: ${rpManifest.absolutePath}")
            } else {
                PackForgeLog.e("PackForge_Debug", "❌ ERROR: RP manifest.json NO en raíz de $rpDir")
            }
            
            rpPath = rpDir.absolutePath
            PackForgeLog.d("PackForge_Debug", "✅ Procesando separated_rp: $rpFileCount archivos en $rpPath")
        }
        
        PackForgeLog.d("PackForge_Debug", "=== FIN SEPARACIÓN BOTH DIRECT ===")
        PackForgeLog.d("PackForge_Debug", "Resultados: BP=$bpPath, RP=$rpPath")
        
        return Pair(bpPath, rpPath)
    }
    
    /**
     * Separa un addon tipo BOTH en sus componentes BP y RP
     * Detecta carpetas BP_* y RP_* y las mueve a directorios separados
     * CRÍTICO: El contenido de BP_* se copia DIRECTAMENTE al directorio BP separado,
     * NO a separated_bp/BP_*. Esto asegura que manifest.json esté en la raíz.
     */
    private fun separateBothAddon(extractedDir: String, tempDir: File): Pair<String?, String?> {
        PackForgeLog.d("PackForge_Debug", "=== INICIO SEPARACIÓN BOTH ===")
        PackForgeLog.d("PackForge_Debug", "Directorio extraído: $extractedDir")
        
        val sourceDir = File(extractedDir)
        var bpPath: String? = null
        var rpPath: String? = null
        
        // Buscar carpetas BP_* y RP_*
        val bpSubfolder = sourceDir.listFiles()?.find { 
            it.isDirectory && it.name.startsWith("BP_", ignoreCase = true) 
        }
        val rpSubfolder = sourceDir.listFiles()?.find { 
            it.isDirectory && it.name.startsWith("RP_", ignoreCase = true) 
        }
        
        PackForgeLog.d("PackForge_Debug", "BP subfolder encontrado: ${bpSubfolder?.name}")
        PackForgeLog.d("PackForge_Debug", "RP subfolder encontrado: ${rpSubfolder?.name}")
        
        // Separar BP
        if (bpSubfolder != null) {
            val bpDir = File(tempDir, "separated_bp_${System.currentTimeMillis()}")
            bpDir.mkdirs()
            
            // Copiar todo el contenido de BP_* al directorio separado
            // CRÍTICO: Usar ruta relativa para NO incluir la carpeta BP_* en el destino
            var bpFileCount = 0
            DirIndexCache.index(bpSubfolder).allFiles.forEach { file ->
                val relativePath = file.relativeTo(bpSubfolder).path
                val targetFile = File(bpDir, relativePath)
                targetFile.parentFile?.mkdirs()
                FileUtils.fastCopy(file, targetFile)
                bpFileCount++

                // Log especial para manifest.json
                if (file.name == "manifest.json") {
                    logFile { "  ✅ manifest.json copiado a: ${targetFile.relativeTo(bpDir).path}" }
                }
            }
            
            // Verificar que manifest.json esté en la raíz del directorio separado
            val bpManifest = File(bpDir, "manifest.json")
            if (bpManifest.exists()) {
                PackForgeLog.d("PackForge_Debug", "✅ BP manifest.json en raíz: ${bpManifest.absolutePath}")
            } else {
                PackForgeLog.e("PackForge_Debug", "❌ ERROR: BP manifest.json NO en raíz de $bpDir")
                bpDir.walkTopDown().filter { it.isFile }.forEach { file ->
                    PackForgeLog.d("PackForge_Debug", "  Archivo en BP separado: ${file.relativeTo(bpDir).path}")
                }
            }
            
            bpPath = bpDir.absolutePath
            PackForgeLog.d("PackForge_Debug", "BP separado: $bpFileCount archivos en $bpPath")
        }
        
        // Separar RP
        if (rpSubfolder != null) {
            val rpDir = File(tempDir, "separated_rp_${System.currentTimeMillis()}")
            rpDir.mkdirs()
            
            // Copiar todo el contenido de RP_* al directorio separado
            // CRÍTICO: Usar ruta relativa para NO incluir la carpeta RP_* en el destino
            var rpFileCount = 0
            DirIndexCache.index(rpSubfolder).allFiles.forEach { file ->
                val relativePath = file.relativeTo(rpSubfolder).path
                val targetFile = File(rpDir, relativePath)
                targetFile.parentFile?.mkdirs()
                FileUtils.fastCopy(file, targetFile)
                rpFileCount++

                // Log especial para manifest.json
                if (file.name == "manifest.json") {
                    logFile { "  ✅ manifest.json copiado a: ${targetFile.relativeTo(rpDir).path}" }
                }
            }
            
            // Verificar que manifest.json esté en la raíz del directorio separado
            val rpManifest = File(rpDir, "manifest.json")
            if (rpManifest.exists()) {
                PackForgeLog.d("PackForge_Debug", "✅ RP manifest.json en raíz: ${rpManifest.absolutePath}")
            } else {
                PackForgeLog.e("PackForge_Debug", "❌ ERROR: RP manifest.json NO en raíz de $rpDir")
                rpDir.walkTopDown().filter { it.isFile }.forEach { file ->
                    PackForgeLog.d("PackForge_Debug", "  Archivo en RP separado: ${file.relativeTo(rpDir).path}")
                }
            }
            
            rpPath = rpDir.absolutePath
            PackForgeLog.d("PackForge_Debug", "RP separado: $rpFileCount archivos en $rpPath")
        }
        
        PackForgeLog.d("PackForge_Debug", "=== FIN SEPARACIÓN BOTH ===")
        PackForgeLog.d("PackForge_Debug", "Resultados: BP=$bpPath, RP=$rpPath")
        
        return Pair(bpPath, rpPath)
    }
    
    /**
     * Fusiona múltiples packs del mismo tipo (BP o RP) - PASO ÚNICO
     *
     * ⚠️ CORREGIDO: sourceDirs son DIRECTORIOS ya extraídos en disco (carpetas),
     * NO archivos ZIP. La versión anterior intentaba abrirlos con ZipFile(), lo que
     * lanzaba una excepción silenciada y provocaba que NINGÚN archivo se copiara,
     * generando un modpack vacío (<1 MB).
     *
     * Ahora recorre cada directorio con walkTopDown() y copia/fusiona cada archivo.
     */
    private fun mergePackType(
        sourceDirs: List<String>,
        targetDir: File,
        manifestName: String,
        registry: ResourcePathRegistry = ResourcePathRegistry(),
        criticalDirs: List<String> = emptyList()
    ): Int {
        var jsonCount = 0
        var nonJsonCount = 0

        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        logFile { "Iniciando mergePackType con ${sourceDirs.size} directorios fuente" }
        logFile { "Directorio destino: ${targetDir.absolutePath}" }

        // Prioridad: índice 0 = mayor prioridad (usuario ordena de mayor a menor).
        // Se trackea quién escribió cada fichero para que el ganador real sea el que creó el base.
        val fileOriginIndex = mutableMapOf<String, Int>()
        for ((sourceIndex, sourceDir) in sourceDirs.withIndex()) {
            val sourceFile = File(sourceDir)
            val sourceAddonName = sourceFile.name

            logFile { "Procesando sourceDir $sourceIndex (prioridad ${sourceIndex + 1}/${sourceDirs.size}): $sourceDir" }
            logFile { "  Nombre del addon: $sourceAddonName" }

            if (!sourceFile.exists() || !sourceFile.isDirectory) {
                PackForgeLog.e("PackForge_Debug", "❌ sourceDir no existe o no es directorio: $sourceDir")
                continue
            }

            // ⭐ RESOURCE PATH REGISTRY: pre-planificar aliases de binarios ANTES de copiar.
            // Devuelve rutaAntigua→rutaNueva (con variantes sin extensión) para ESTA fuente.
            val pathRenames = try { registry.planAndCommit(sourceFile) } catch (e: Exception) {
                PackForgeLog.e("PackForge_Debug", "Error planificando recursos de $sourceAddonName: ${e.message}")
                emptyMap<String, String>()
            }

            // ⭐ FOLDER ALIAS REGISTRY: aliasing de estructuras/fogs/ui por contenido.
            val folderRenames = try { FolderAliasRegistry.planAndCommit(sourceFile, sourceAddonName) } catch (e: Exception) {
                PackForgeLog.e("PackForge_Debug", "Error planificando carpetas de $sourceAddonName: ${e.message}")
                emptyMap<String, String>()
            }

            // Combinar ambos mapas de renombre (binarios + carpetas)
            val allRenames = pathRenames + folderRenames
            if (allRenames.isNotEmpty()) {
                PackForgeLog.d("PackForge_Debug", "  🖼️ ${pathRenames.size} rutas de recurso + ${folderRenames.size} rutas de carpeta con alias para $sourceAddonName")
            }

            try {
                // Usar índice cacheado en vez de segundo walkTopDown (reducción 3-10x en I/O)
                val cachedFiles = DirIndexCache.index(sourceFile).allFiles
                for (file in cachedFiles) {
                    val relativePath = file.relativeTo(sourceFile).path.replace("\\", "/")

                    // Saltar manifest.json (se generará nuevo al final)
                    if (relativePath == manifestName || relativePath == "manifest.json" || file.name == "manifest.json") {
                        continue
                    }

                    // ⭐ PASO 1: Excluir "scripts/" de la copia genérica (se fusionan aparte) ⭐
                    if (relativePath.startsWith("scripts/")) {
                        continue
                    }

                    // ⭐ PASO 2: Excluir archivos críticos que tienen fusiones DEDICADAS y semánticas
                    // (evita "Frankenstein" por deep-merge genérico + fusión dedicada duplicada)
                    if (criticalDirs.any { relativePath.startsWith(it) }) {
                        continue
                    }

                    // Ruta física efectiva tras aliasing del registro (recursos + carpetas)
                    val effectivePath = allRenames[relativePath] ?: relativePath
                    val targetFile = File(targetDir, effectivePath)
                    targetFile.parentFile?.mkdirs()

                    if (file.name.endsWith(".json", ignoreCase = true)) {
                        // Archivo JSON - Fusionar en memoria (sin I/O intermedio)
                        try {
                            val newJson = JsonDeepMerger.cleanJsonObject(JSONObject(file.readText(Charsets.UTF_8)))
                            ResourcePathRegistry.applyRenames(newJson, allRenames)

                            JsonDeepMerger.setMergeContext(
                                sourceAddon = sourceAddonName,
                                targetAddon = targetDir.name,
                                filePath = effectivePath
                            )
                            val winnerIndex = fileOriginIndex[effectivePath] ?: 0
                            JsonDeepMerger.setPriorityContext(sourceIndex = sourceIndex, winnerIndex = winnerIndex)

                            // Fusión en memoria - sin I/O
                            JsonDeepMerger.mergeIntoBuffer(effectivePath, newJson, sourceAddonName, targetDir.name)
                            fileOriginIndex[effectivePath] = sourceIndex
                            JsonDeepMerger.clearPriorityContext()
                            jsonCount++
                        } catch (e: Exception) {
                            JsonDeepMerger.clearPriorityContext()
                            PackForgeLog.e("PackForge_Debug", "    ❌ Error procesando JSON $effectivePath: ${e.message}")
                            // Fallback a copia directa
                            FileUtils.fastCopy(file, targetFile)
                            fileOriginIndex.putIfAbsent(effectivePath, sourceIndex)
                            jsonCount++
                        }
                    } else {
                        FileUtils.fastCopy(file, targetFile)
                        fileOriginIndex.putIfAbsent(effectivePath, sourceIndex)
                        nonJsonCount++
                    }
                }
            } catch (e: Exception) {
                PackForgeLog.e("PackForge_Debug", "Error procesando addon $sourceAddonName: ${e.message}", e)
            }

            logFile { "  Finalizado procesamiento de $sourceAddonName" }
        }

        PackForgeLog.d("PackForge_Debug", "=== Resumen mergePackType ===")
        PackForgeLog.d("PackForge_Debug", "Total JSONs fusionados: $jsonCount")
        PackForgeLog.d("PackForge_Debug", "Total no-JSONs copiados: $nonJsonCount")
        PackForgeLog.d("PackForge_Debug", "Total archivos en destino: ${DirIndexCache.index(targetDir).allFiles.size}")

        return jsonCount
    }
    
    /**
     * Genera manifiestos vinculados BP↔RP usando el generador EXACTO que garantiza:
     * min_engine_version obligatorio, módulo script y dependencias @minecraft/ (librerías).
     *
     * @param bpDirs Directorios de los Behavior Packs ORIGINALES extraídos
     * @param rpDirs Directorios de los Resource Packs ORIGINALES extraídos
     * @param mergedBpDir Carpeta del BP fusionado (donde se escribe manifest.json)
     * @param mergedRpDir Carpeta del RP fusionado (donde se escribe manifest.json)
     * @param customName Nombre del modpack
     * @return Pair con los UUIDs de header (BP, RP)
     */
    private fun generateLinkedManifests(
        bpDirs: List<String>,
        rpDirs: List<String>,
        mergedBpDir: File,
        mergedRpDir: File,
        customName: String = "PackForge Modpack",
        customAuthor: String = "",
        customVersion: String = "1.0.0",
        customDescription: String = ""
    ): Pair<String?, String?> {
        // Preflight the actual extracted content, not just the metadata shown in the
        // import screen. Findings are added to the existing conflict UI so the user
        // gets an actionable reason for every unsafe export.
        val compatibilityFindings = BedrockCompatibilityAnalyzer.analyze(bpDirs, rpDirs)
        compatibilityFindings.forEach { finding ->
            ConflictRegistry.logConflict(
                severity = finding.severity,
                type = finding.type,
                file = finding.file,
                addon1 = finding.source,
                addon2 = finding.target,
                description = finding.description
            )
        }
        val blockingFindings = compatibilityFindings.filter { it.blocksExport }
        if (blockingFindings.isNotEmpty()) {
            throw IllegalStateException(
                "Compatibilidad no resuelta: " +
                    blockingFindings.joinToString(" | ") { it.description }
            )
        }

        // Recolectar manifests originales de BP y RP
        val originalBpManifestFiles = bpDirs.mapNotNull { dir ->
            val f = File(dir, "manifest.json")
            if (f.exists()) f else null
        }
        val originalRpManifests = rpDirs.mapNotNull { dir ->
            val f = File(dir, "manifest.json")
            if (f.exists()) f else null
        }

        // El nombre visible de un addon no es una identidad segura: muchos packs
        // comparten el mismo header.name. Usar el UUID evita sobrescrituras de scripts.
        val bpSources = mutableListOf<ScriptSource>()
        bpDirs.forEachIndexed { index, dir ->
            val f = File(dir, "manifest.json")
            if (f.exists()) {
                try {
                    val json = JSONObject(f.readText())
                    val uuid = json.optJSONObject("header")?.optString("uuid", "").orEmpty()
                    val stablePart = uuid.filter { it.isLetterOrDigit() }.take(12)
                        .ifBlank { "source_${index + 1}" }
                    bpSources.add(ScriptSource("addon_${index + 1}_$stablePart", File(dir), json))
                } catch (e: Exception) {
                    PackForgeLog.w(TAG, "Error leyendo manifest de $dir: ${e.message}")
                }
            }
        }

        PackForgeLog.d(TAG, "Manifests BP originales: ${originalBpManifestFiles.size}")
        PackForgeLog.d(TAG, "Manifests RP originales: ${originalRpManifests.size}")

        // ══ PASO 2: FUSIONAR SCRIPTS ══
        val scriptResult = mergeScripts(bpSources, mergedBpDir)
        // Nunca exportar un addon "plano" si su lógica no pudo conservarse. Es
        // preferible detener la exportación con una causa accionable a generar un
        // .mcaddon que parezca válido pero haya perdido comportamiento.
        if (scriptResult.skippedAddons.isNotEmpty()) {
            throw IllegalStateException(
                "No se pudieron preservar los scripts de: ${scriptResult.skippedAddons.joinToString()}. " +
                    "Revisa el entry del módulo script en el manifest original."
            )
        }

        // ══ GENERAR RP MANIFEST (función exacta) ══
        val rpManifestObj = ManifestGenerator.generateRpManifest(customName)
        val newRpHeaderUuid = rpManifestObj.optJSONObject("header")?.optString("uuid")
            ?: java.util.UUID.randomUUID().toString()

        // ══ GENERAR BP MANIFEST usando buildMergedBpManifest (más completo) ══
        val originalRpHeaderUuids = originalRpManifests.mapNotNull { f ->
            try {
                val json = JSONObject(f.readText(Charsets.UTF_8))
                json.optJSONObject("header")?.optString("uuid")
            } catch (e: Exception) { null }
        }.toSet()

        val bpManifestObj = ManifestGenerator.buildMergedBpManifest(
            originalBpManifests = originalBpManifestFiles,
            originalRpHeaderUuids = originalRpHeaderUuids,
            newRpHeaderUuid = newRpHeaderUuid,
            packName = customName,
            hasScriptsFolder = scriptResult.addonCount > 0,
            packAuthor = customAuthor,
            packVersion = customVersion,
            packDescription = customDescription
        )

        // Añadir dependencias de librerías detectadas por mergeScripts
        if (scriptResult.addonCount > 0) {
            val dependencies = bpManifestObj.optJSONArray("dependencies") ?: JSONArray().also { bpManifestObj.put("dependencies", it) }
            scriptResult.libraryDeps.forEach { (name, ver) ->
                // Solo añadir si no existe ya
                var exists = false
                for (i in 0 until dependencies.length()) {
                    val dep = dependencies.optJSONObject(i)
                    if (dep != null && dep.optString("module_name", "") == name) {
                        exists = true
                        // Actualizar a versión mayor si procede
                        val existingVer = dep.optString("version", "1.0.0")
                        if (compareSemver(ver, existingVer) > 0) {
                            dep.put("version", ver)
                        }
                        break
                    }
                }
                if (!exists) {
                    dependencies.put(JSONObject().apply {
                        put("module_name", name)
                        put("version", ver)
                    })
                }
            }
            // Fallback si no hay @minecraft/server
            if (!scriptResult.libraryDeps.containsKey("@minecraft/server")) {
                var hasServer = false
                for (i in 0 until dependencies.length()) {
                    if (dependencies.optJSONObject(i)?.optString("module_name", "") == "@minecraft/server") {
                        hasServer = true
                        break
                    }
                }
                if (!hasServer) {
                    dependencies.put(JSONObject().apply {
                        put("module_name", "@minecraft/server")
                        put("version", "1.16.0")
                    })
                }
            }
        }

        // ⭐ PASO 3: Añadir módulo de script combinado ⭐
        // Eliminamos cualquier módulo script existente para evitar conflictos
        val modulesArray = bpManifestObj.optJSONArray("modules") ?: JSONArray().also { bpManifestObj.put("modules", it) }
        val newModules = JSONArray()
        for (i in 0 until modulesArray.length()) {
            val mod = modulesArray.getJSONObject(i)
            if (mod.optString("type", "") != "script") {
                newModules.put(mod)
            }
        }
        bpManifestObj.put("modules", newModules)

        // Añadir el módulo script combinado si hay scripts
        if (scriptResult.addonCount > 0) {
            newModules.put(JSONObject().apply {
                put("type", "script")
                put("language", "javascript")
                put("uuid", UUID.randomUUID().toString())
                put("version", JSONArray(listOf(1, 0, 0)))
                put("entry", "scripts/main.js")  // ⭐ SIEMPRE el combinado
            })
        }

        val newBpHeaderUuid = bpManifestObj.optJSONObject("header")?.optString("uuid")
            ?: java.util.UUID.randomUUID().toString()

        // ══ ESCRIBIR using UTF-8 sin BOM ══
        val bpManifestFile = File(mergedBpDir, "manifest.json")
        val rpManifestFile = File(mergedRpDir, "manifest.json")
        if (mergedBpDir.exists() || mergedBpDir.mkdirs()) {
            ManifestGenerator.writeManifestToFile(bpManifestObj.toString(), bpManifestFile)
        } else {
            PackForgeLog.e(TAG, "No se pudo crear el directorio del BP: ${mergedBpDir.absolutePath}")
        }
        if (mergedRpDir.exists() || mergedRpDir.mkdirs()) {
            ManifestGenerator.writeManifestToFile(rpManifestObj.toString(), rpManifestFile)
        } else {
            PackForgeLog.e(TAG, "No se pudo crear el directorio del RP: ${mergedRpDir.absolutePath}")
        }

        // ══ CAMBIO 1: IMPRIMIR MANIFIESTOS COMPLETOS EN LOGCAT ══
        val bpManifestContent = bpManifestFile.readText()
        android.util.Log.d("PackForge_Manifest", "═══════════════════════════════════════")
        android.util.Log.d("PackForge_Manifest", "📄 BP MANIFEST COMPLETO (${bpManifestFile.length()} bytes):")
        android.util.Log.d("PackForge_Manifest", bpManifestContent)
        android.util.Log.d("PackForge_Manifest", "═══════════════════════════════════════")

        val rpManifestContent = rpManifestFile.readText()
        android.util.Log.d("PackForge_Manifest", "═══════════════════════════════════════")
        android.util.Log.d("PackForge_Manifest", "📄 RP MANIFEST COMPLETO (${rpManifestFile.length()} bytes):")
        android.util.Log.d("PackForge_Manifest", rpManifestContent)
        android.util.Log.d("PackForge_Manifest", "═══════════════════════════════════════")

        // ══ CAMBIO 3: VALIDACIONES OBLIGATORIAS (flexibles: soportan packs sin scripts) ══
        val bpContent = bpManifestFile.readText()
        val bpJson = JSONObject(bpContent)
        val bpHeader = bpJson.getJSONObject("header")

        require(bpHeader.has("min_engine_version")) {
            "❌ BP manifest NO tiene min_engine_version"
        }
        val mev = bpHeader.getJSONArray("min_engine_version")
        require(mev.getInt(0) >= 1 && mev.getInt(1) >= 20) {
            "❌ min_engine_version es muy bajo: ${mev}"
        }

        // Validar integridad: debe existir al menos un módulo "data"
        val modules = bpJson.getJSONArray("modules")
        val hasDataModule = (0 until modules.length()).any { i ->
            modules.optJSONObject(i)?.optString("type", "") == "data"
        }
        require(hasDataModule) {
            "❌ BP manifest sin módulo 'data' obligatorio"
        }

        // Validar que si hay RP, el BP tiene la dependencia al RP
        val dependencies = bpJson.optJSONArray("dependencies") ?: JSONArray()
        val rpDepPresent = (0 until dependencies.length()).any { i ->
            (dependencies.optJSONObject(i)?.optString("uuid", "") ?: "").lowercase() == newRpHeaderUuid.lowercase()
        }
        require(rpDepPresent) {
            "❌ BP manifest sin dependencia al RP fusionado"
        }

        android.util.Log.d(
            "PackForge_Manifest",
            "✅ BP manifest VALIDADO: min_engine_version=${mev}, modules=${modules.length()} (data+${if (hasDataModule) "✓" else "✗"}), deps=${dependencies.length()}"
        )

        PackForgeLog.d(TAG, "Manifests escritos con UTF-8 sin BOM")
        PackForgeLog.d(TAG, "UUIDs - BP: $newBpHeaderUuid, RP: $newRpHeaderUuid")

        return Pair(newBpHeaderUuid, newRpHeaderUuid)
    }
    
    /**
     * Extrae el UUID del header de un manifiesto
     */
    private fun extractUuidFromManifest(manifest: String): String? {
        return try {
            val json = JSONObject(manifest)
            json.getJSONObject("header").getString("uuid")
        } catch (e: Exception) {
            PackForgeLog.e(TAG, "Error al extraer UUID: ${e.message}")
            null
        }
    }
    
    /**
     * Aplica el icono personalizado al modpack DESPUÉS de fusionar los addons
     * CRÍTICO: Debe llamarse DESPUÉS de fusionar y ANTES de crear el ZIP
     */
    private fun applyCustomIcon(mergedBpDir: File?, mergedRpDir: File?, customIconPath: String?) {
        PackForgeLog.d("PackForge_Icon", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        PackForgeLog.d("PackForge_Icon", "🎨 APLICANDO PORTADA PERSONALIZADA")
        PackForgeLog.d("PackForge_Icon", "   BP dir: ${mergedBpDir?.absolutePath ?: "null"}")
        PackForgeLog.d("PackForge_Icon", "   RP dir: ${mergedRpDir?.absolutePath ?: "null"}")
        PackForgeLog.d("PackForge_Icon", "   Icon path: $customIconPath")
        
        if (customIconPath == null) {
            PackForgeLog.w("PackForge_Icon", "⚠️ No se proporcionó icono personalizado. Saltando.")
            return
        }
        
        val iconFile = File(customIconPath)
        if (!iconFile.exists()) {
            PackForgeLog.e("PackForge_Icon", "❌ El archivo de icono no existe: $customIconPath")
            return
        }
        
        PackForgeLog.d("PackForge_Icon", "   Icon file existe: ${iconFile.exists()}")
        PackForgeLog.d("PackForge_Icon", "   Icon file tamaño: ${iconFile.length()} bytes")
        
        val dirs = listOfNotNull(mergedBpDir, mergedRpDir).filter { it.exists() }
        
        if (dirs.isEmpty()) {
            PackForgeLog.e("PackForge_Icon", "❌ No hay directorios válidos para aplicar icono")
            return
        }
        
        PackForgeLog.d("PackForge_Icon", "   Directorios a procesar: ${dirs.size}")
        
        dirs.forEach { dir ->
            try {
                val targetIcon = File(dir, "pack_icon.png")
                
                // ELIMINAR icono existente (de los addons)
                if (targetIcon.exists()) {
                    val deleted = targetIcon.delete()
                    PackForgeLog.d("PackForge_Icon", "🗑️ Icono anterior eliminado de ${dir.name}: $deleted")
                } else {
                    PackForgeLog.d("PackForge_Icon", "ℹ️ No había icono anterior en ${dir.name}")
                }
                
                // CRÍTICO: Minecraft espera pack_icon.png CUADRADO (normalmente 256x256 o 512x512).
                // Si el usuario sube una portada ancha o vertical, se debe ajustar (center-crop a
                // cuadrado + escalar) para que el icono encaje perfectamente sin espacios vacíos.
                val processed = processIconCrop(iconFile, targetIcon)
                
                if (processed) {
                    PackForgeLog.d("PackForge_Icon", "✅ pack_icon.png generado (256x256, center-crop) en ${dir.name}")
                } else {
                    // Fallback: copiar tal cual si no se pudo procesar
                    PackForgeLog.w("PackForge_Icon", "⚠️ No se pudo procesar la portada, copiando original")
                    FileUtils.fastCopy(iconFile, targetIcon)
                }
                
                // VERIFICAR que el archivo se creó
                if (targetIcon.exists()) {
                    PackForgeLog.d("PackForge_Icon", "✅ pack_icon.png creado en ${dir.name}")
                    PackForgeLog.d("PackForge_Icon", "   Tamaño: ${targetIcon.length()} bytes")
                    PackForgeLog.d("PackForge_Icon", "   Ruta: ${targetIcon.absolutePath}")
                } else {
                    PackForgeLog.e("PackForge_Icon", "❌ pack_icon.png NO se creó en ${dir.name}")
                }
                
            } catch (e: Exception) {
                PackForgeLog.e("PackForge_Icon", "❌ Error procesando ${dir.name}: ${e.message}", e)
            }
        }
        
        PackForgeLog.d("PackForge_Icon", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    }
    
    /**
     * Convierte la portada en un icono CUADRADO (center-crop) de 256x256 PNG,
     * el formato que Minecraft Bedrock espera para pack_icon.png.
     * Devuelve true si se procesó correctamente.
     */
    private fun processIconCrop(sourceFile: File, targetFile: File): Boolean {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(sourceFile.absolutePath, options)
            val srcW = options.outWidth
            val srcH = options.outHeight
            if (srcW <= 0 || srcH <= 0) return false
            
            // Decodificar con reducción de sampleo para no saturar memoria
            val sampleSize = calculateInSampleSize(srcW, srcH, 512)
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val srcBitmap = BitmapFactory.decodeFile(sourceFile.absolutePath, decodeOpts) ?: return false
            
            // Center-crop a cuadrado
            val side = minOf(srcBitmap.width, srcBitmap.height)
            val left = (srcBitmap.width - side) / 2
            val top = (srcBitmap.height - side) / 2
            val cropped = Bitmap.createBitmap(srcBitmap, left, top, side, side)
            
            // Escalar a 256x256
            val resized = Bitmap.createScaledBitmap(cropped, 256, 256, true)
            if (resized != cropped) cropped.recycle()
            if (resized != srcBitmap) srcBitmap.recycle()
            
            FileOutputStream(targetFile).use { out ->
                resized.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            resized.recycle()
            true
        } catch (e: Exception) {
            PackForgeLog.e("PackForge_Icon", "❌ Error procesando portada: ${e.message}")
            false
        }
    }
    
    private fun calculateInSampleSize(width: Int, height: Int, reqSize: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= reqSize && h / 2 >= reqSize) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }
    
    /**
     * Verifica que los manifests estén correctos antes de crear el ZIP
     * CRÍTICO: Esta función previene errores de importación en Minecraft Bedrock
     */
    private fun verifyBeforeZip(mergedBpDir: File, mergedRpDir: File) {
        PackForgeLog.d("PackForge_Verify", "=== INICIO VERIFICACIÓN PRE-ZIP ===")
        
        val bpManifest = File(mergedBpDir, "manifest.json")
        val rpManifest = File(mergedRpDir, "manifest.json")
        
        require(bpManifest.exists()) { "❌ Falta manifest.json en BP" }
        require(rpManifest.exists()) { "❌ Falta manifest.json en RP" }
        
        // Verificar contenido
        val bpJson = JSONObject(bpManifest.readText(StandardCharsets.UTF_8))
        val rpJson = JSONObject(rpManifest.readText(StandardCharsets.UTF_8))
        
        PackForgeLog.d("PackForge_Verify", "BP header.name: ${bpJson.getJSONObject("header").getString("name")}")
        PackForgeLog.d("PackForge_Verify", "BP header.uuid: ${bpJson.getJSONObject("header").getString("uuid")}")
        PackForgeLog.d("PackForge_Verify", "BP modules: ${bpJson.getJSONArray("modules").length()}")
        PackForgeLog.d("PackForge_Verify", "BP dependencies: ${bpJson.optJSONArray("dependencies")?.length() ?: 0}")
        
        PackForgeLog.d("PackForge_Verify", "RP header.name: ${rpJson.getJSONObject("header").getString("name")}")
        PackForgeLog.d("PackForge_Verify", "RP header.uuid: ${rpJson.getJSONObject("header").getString("uuid")}")
        
        // Verificar UUIDs coinciden
        val rpHeaderUuid = rpJson.getJSONObject("header").getString("uuid")
        val bpDeps = bpJson.optJSONArray("dependencies")
        require(bpDeps != null && bpDeps.length() > 0) { "❌ BP no tiene dependencies" }
        
        val bpDepUuid = bpDeps.getJSONObject(0).getString("uuid")
        require(bpDepUuid == rpHeaderUuid) { 
            "❌ UUID de dependency del BP ($bpDepUuid) no coincide con UUID del RP ($rpHeaderUuid)" 
        }
        
        // Verificar que no haya BOM en los manifests
        val bpBytes = bpManifest.readBytes()
        val rpBytes = rpManifest.readBytes()
        
        val bpHasBom = bpBytes.size >= 3 && bpBytes[0] == 0xEF.toByte() && bpBytes[1] == 0xBB.toByte() && bpBytes[2] == 0xBF.toByte()
        val rpHasBom = rpBytes.size >= 3 && rpBytes[0] == 0xEF.toByte() && rpBytes[1] == 0xBB.toByte() && rpBytes[2] == 0xBF.toByte()
        
        require(!bpHasBom) { "❌ BP manifest tiene BOM UTF-8" }
        require(!rpHasBom) { "❌ RP manifest tiene BOM UTF-8" }
        
        PackForgeLog.d("PackForge_Verify", "✅ Sin BOM (primer byte BP: ${String.format("%02X", bpBytes[0])})")
        PackForgeLog.d("PackForge_Verify", "✅ Sin BOM (primer byte RP: ${String.format("%02X", rpBytes[0])})")
        
        PackForgeLog.d("PackForge_Verify", "✅ Todas las verificaciones pasaron")
        PackForgeLog.d("PackForge_Verify", "=== FIN VERIFICACIÓN PRE-ZIP ===")
    }
    
    /**
     * Crea el archivo ZIP final del modpack con estructura correcta de Minecraft Bedrock
     * Estructura esperada:
     * - BP_PackForge/
     * - RP_PackForge/
     * CRÍTICO: NO usar behavior_packs/ o resource_packs/
     */
    private fun createMcAddon(mergedBpDir: File?, mergedRpDir: File?, outputFile: File) {
        if (mergedBpDir != null) DirIndexCache.invalidate(mergedBpDir)
        if (mergedRpDir != null) DirIndexCache.invalidate(mergedRpDir)
        val bpIdx = mergedBpDir?.let { DirIndexCache.index(it) }
        val rpIdx = mergedRpDir?.let { DirIndexCache.index(it) }
        var entryCount = 0
        ZipOutputStream(
            BufferedOutputStream(FileOutputStream(outputFile), 262144) // ⭐ 256KB buffer
        ).use { zos ->
            zos.setLevel(Deflater.BEST_SPEED) // ⭐ Nivel 1: ~5x más rápido, tamaño casi igual

            if (mergedBpDir != null && mergedBpDir.exists()) {
                entryCount += addFolderToZip(zos, mergedBpDir, "BP_PackForge", bpIdx)
                PackForgeLog.d("PackForge_ZIP", "✅ BP agregado como BP_PackForge/")
            }

            if (mergedRpDir != null && mergedRpDir.exists()) {
                entryCount += addFolderToZip(zos, mergedRpDir, "RP_PackForge", rpIdx)
                PackForgeLog.d("PackForge_ZIP", "✅ RP agregado como RP_PackForge/")
            }

            zos.finish()
        }

        android.util.Log.d("PackForge_ZIP", "📦 Entradas totales en ZIP: $entryCount")
        // Se mantiene log informativo para monitoreo de rendimiento, el límite técnico es 65,535 entradas ZIP estándar.
        if (entryCount > 50000) {
            android.util.Log.w("PackForge_ZIP", "⚠️ ZIP muy grande ($entryCount entradas) - verificando compatibilidad")
        }
        
        PackForgeLog.d("PackForge_ZIP", "📦 Archivo final: ${outputFile.name}")
        PackForgeLog.d("PackForge_ZIP", "   Extensión: ${outputFile.extension}")
        PackForgeLog.d("PackForge_ZIP", "   Tamaño: ${outputFile.length() / 1024} KB")
    }
    
    private fun addFolderToZip(zos: ZipOutputStream, sourceFolder: File, zipFolderPath: String, idx: DirIndexCache.IndexedDir? = null): Int {
        val entries: List<File> = idx?.allFiles ?: sourceFolder.walkTopDown().filter { it.isFile }.toList()
        var count = 0
        entries.forEach { curFile: File ->
            val relativePath = curFile.relativeTo(sourceFolder).path
            if (curFile.name == "pack_icon.png") {
                logFile { "🎨 Agregando pack_icon.png al ZIP desde: ${curFile.absolutePath} (${curFile.length()} bytes) en $zipFolderPath/$relativePath" }
            }
            val zipEntryName = if (relativePath.isEmpty()) "$zipFolderPath/${curFile.name}" else "$zipFolderPath/$relativePath"
            zos.putNextEntry(ZipEntry(zipEntryName))
            FileInputStream(curFile).use { ins -> ins.copyTo(zos, 65536) }
            zos.closeEntry()
            count++
        }
        return count
    }
    
    /**
     * Agrega un directorio recursivamente al ZIP
     * @param zos ZipOutputStream
     * @param dir Directorio a agregar
     * @param basePath Ruta base en el ZIP (ej: "behavior_packs")
     */
    private fun addDirectoryToZip(zos: ZipOutputStream, dir: File, basePath: String) {
        dir.listFiles()?.forEach { file ->
            val zipEntryName = if (basePath.isEmpty()) file.name else "$basePath/${file.name}"
            
            if (file.isDirectory) {
                // Agregar carpeta al ZIP
                zos.putNextEntry(ZipEntry("$zipEntryName/"))
                zos.closeEntry()
                // Recursivamente agregar contenido
                addDirectoryToZip(zos, file, zipEntryName)
            } else {
                // Agregar archivo al ZIP
                zos.putNextEntry(ZipEntry(zipEntryName))
                FileInputStream(file).use { fis ->
                    fis.copyTo(zos)
                }
                zos.closeEntry()
            }
        }
    }
    
    data class ScriptMergeResult(
        val addonCount: Int,
        val libraryDeps: Map<String, String>,  // module_name -> versión máxima
        val skippedAddons: List<String> = emptyList()
    )

    private data class ScriptSource(
        val key: String,
        val directory: File,
        val manifest: JSONObject
    )

    private fun mergeScripts(
        bpSources: List<ScriptSource>,
        mergedBpDir: File
    ): ScriptMergeResult {
        val imports = mutableListOf<String>()
        val libraryDeps = mutableMapOf<String, String>()
        val skippedAddons = mutableListOf<String>()

        bpSources.forEach { source ->
            val key = source.key
            val bpDir = source.directory
            val scriptFiles = bpDir.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in SCRIPT_EXTENSIONS }
                .toList()
            if (scriptFiles.isEmpty()) return@forEach

            // 2. Leer el entry original del manifest
            val manifest = source.manifest
            var entry = "scripts/main.js"
            manifest.optJSONArray("modules")?.let { mods ->
                for (i in 0 until mods.length()) {
                    val m = mods.getJSONObject(i)
                    if (m.optString("type") == "script") {
                        entry = m.optString("entry", "scripts/main.js")
                    }
                }
            }
            val relativeEntry = BedrockCompatibilityAnalyzer.resolveScriptEntry(bpDir, entry)
            if (relativeEntry == null) {
                skippedAddons.add(key)
                PackForgeLog.e(
                    "PackForge_Scripts",
                    "Script omitido para '$key': entry inválido o inexistente '$entry'"
                )
                return@forEach
            }
            val targetDir = File(mergedBpDir, "scripts/$key")
            val copied = scriptFiles.all { file ->
                val target = File(targetDir, file.relativeTo(bpDir).invariantSeparatorsPath)
                target.parentFile?.mkdirs()
                try {
                    FileUtils.fastCopy(file, target)
                    true
                } catch (_: Exception) {
                    false
                }
            }
            if (!copied) {
                skippedAddons.add(key)
                PackForgeLog.e("PackForge_Scripts", "No se pudieron copiar los scripts de '$key'")
                return@forEach
            }
            PackForgeLog.d("PackForge_Scripts", "Scripts de '$key' preservados en scripts/$key/")
            imports.add("import \"./$key/$relativeEntry\";")

            // 3. Recoger versiones de librerías @minecraft/*
            manifest.optJSONArray("dependencies")?.let { deps ->
                for (i in 0 until deps.length()) {
                    val dep = deps.getJSONObject(i)
                    val name = dep.optString("module_name", "")
                    if (name.startsWith("@minecraft/")) {
                        val ver = dep.optString("version", "1.0.0")
                        val current = libraryDeps[name]
                        if (current == null || compareSemver(ver, current) > 0) {
                            libraryDeps[name] = ver
                        }
                    }
                }
            }
        }

        if (imports.isEmpty()) return ScriptMergeResult(0, emptyMap(), skippedAddons)

        // 4. ⭐ Generar el main.js COMBINADO ⭐
        val mainJs = File(mergedBpDir, "scripts/main.js")
        mainJs.parentFile?.mkdirs()
        mainJs.writeText(
            "// PackForge combined entry\n" +
            imports.joinToString("\n") + "\n"
        )

        PackForgeLog.d("PackForge_Scripts", "✅ main.js combinado con ${imports.size} imports")
        libraryDeps.forEach { (name, ver) ->
            PackForgeLog.d("PackForge_Scripts", "📚 $name → $ver")
        }

        if (skippedAddons.isNotEmpty()) {
            PackForgeLog.w(
                "PackForge_Scripts",
                "Scripts no incluidos por entry inválido: ${skippedAddons.joinToString()}"
            )
        }
        return ScriptMergeResult(imports.size, libraryDeps, skippedAddons)
    }

    private fun compareSemver(a: String, b: String): Int {
        val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
        val pb = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until 3) {
            val cmp = (pa.getOrElse(i){0}).compareTo(pb.getOrElse(i){0})
            if (cmp != 0) return cmp
        }
        return 0
    }

    /**
     * Limpia directorios temporales
     */
    private fun cleanupTempDirs(tempDir: File) {
        try {
            if (tempDir.exists()) {
                tempDir.deleteRecursively()
                PackForgeLog.d(TAG, "Directorios temporales limpiados")
            }
        } catch (e: Exception) {
            PackForgeLog.e(TAG, "Error al limpiar temporales: ${e.message}")
        }
    }
}
