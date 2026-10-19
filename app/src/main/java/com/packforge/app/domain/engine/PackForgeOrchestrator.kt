package com.packforge.app.domain.engine

import android.graphics.Bitmap
import com.packforge.app.domain.model.ConflictSeverity
import android.graphics.BitmapFactory
import com.packforge.app.util.PackForgeConfig
import com.packforge.app.util.PackForgeLog
import com.packforge.app.util.FileUtils
import com.packforge.app.util.logFile
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import java.io.BufferedOutputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipFile
import com.packforge.app.domain.engine.JsonDeepMerger
import com.packforge.app.domain.engine.FusionIssue
import com.packforge.app.domain.engine.FusionReportBuilder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

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
        val reportPath: String? = null,
        /** Portada auto-seleccionada de un addon cuando no se puso una personalizada */
        val autoCoverPath: String? = null
    )

    /**
     * Callback para progreso de fusión
     */
    interface ProgressCallback {
        suspend fun onProgress(message: String, percent: Int = -1)
    }

    /**
     * FASE 1B — Copia de todos los addons al directorio de salida usando buffers.
     * NO hace parsing JSON.
     * OPTIMIZACIÓN: Usa DirIndexCache en lugar de walkTopDown() (3-10x más rápido).
     *
     * ⭐ REQ-B: consume el plan de [planSourceMutations], de modo que la ruta
     * física de cada archivo y las referencias de sus JSONs quedan consistentes.
     */
    private suspend fun fastMergeAllSources(
        sourceDirs: List<String>,
        targetDir: File,
        progressCallback: ProgressCallback?,
        resourceRegistry: ResourcePathRegistry,
        totalFiles: Int = 0,
        processedCounter: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger(0)
    ) {
        targetDir.mkdirs()

        val localTotal = if (totalFiles > 0) totalFiles else sourceDirs.map { DirIndexCache.index(File(it)).allFiles.size }.sum()

        sourceDirs.forEachIndexed { index, sourcePath ->
            val sourceFile = File(sourcePath)
            progressCallback?.onProgress("Copiando addon ${index + 1}/${sourceDirs.size}: ${sourceFile.name}...")

            // Mutaciones ya planificadas para ESTA fuente.
            val renames = resourceRegistry.mutationsFor(sourceFile)

            // ⭐ OPTIMIZACIÓN: Usar índice cacheado en lugar de walkTopDown()
            val cachedFiles = DirIndexCache.index(sourceFile).allFiles
            for ((fileIndex, file) in cachedFiles.withIndex()) {
                if (fileIndex % 32 == 0) coroutineContext.ensureActive()
                val relativePath = file.relativeTo(sourceFile).path
                val key = relativePath.replace('\\', '/')
                val targetRel = renames[key] ?: key
                val targetFile = File(targetDir, targetRel)

                targetFile.parentFile?.mkdirs()
                file.inputStream().use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output, bufferSize = 16384)
                    }
                }
                processedCounter.incrementAndGet()
                if (localTotal > 0 && (fileIndex % 10 == 0 || fileIndex == cachedFiles.size - 1)) {
                    val done = processedCounter.get()
                    progressCallback?.onProgress(
                        "Copiando archivos... $done/$localTotal",
                        (done * 72 / localTotal).coerceIn(0, 72)
                    )
                }
            }
        }
    }

    /**
     * FASE 1A — PLAN de mutaciones (sin tocar disco).
     *
     * Recorre las MISMAS fuentes en el MISMOS orden que la copia, simulando qué
     * rutas quedarían ocupadas, y registra en [resourceRegistry] la ruta mutada
     * de cada colisión ASOCIADA A SU FUENTE.
     *
     * Existe como fase separada porque las mutaciones deben estar conocidas
     * ANTES de copiar: solo así se pueden reescribir los JSONs de cada fuente
     * con SU PROPIO mapa. Si se detectara la colisión durante la copia, los
     * JSONs quedarían ya copiados en el destino y se perdería el vínculo
     * archivo→addon, obligando a un mapa global (que se machaca entre fuentes).
     *
     * Además sustituye al `System.currentTimeMillis()` original, que generaba
     * el mismo nombre destino para dos colisiones dentro del mismo milisegando.
     */
    private fun planSourceMutations(
        sourceDirs: List<String>,
        targetDir: File,
        resourceRegistry: ResourcePathRegistry
    ) {
        targetDir.mkdirs()
        val occupied = HashSet<String>()
        DirIndexCache.index(targetDir).allFiles.forEach {
            occupied.add(it.relativeTo(targetDir).path.replace('\\', '/'))
        }
        // Secuencia compartida por ruta: dos fuentes distintas que colisionen en
        // la misma ruta reciben sufijos distintos.
        val seqByPath = HashMap<String, Int>()

        for (sourcePath in sourceDirs) {
            val sourceFile = File(sourcePath)
            if (!sourceFile.isDirectory) continue
            for (file in DirIndexCache.index(sourceFile).allFiles) {
                val rel = file.relativeTo(sourceFile).path.replace('\\', '/')
                if (occupied.add(rel)) continue // ruta libre: no hay colisión

                var seq = seqByPath.getOrPut(rel) { 0 } + 1
                val targetFile = File(targetDir, rel)
                val parent = targetFile.parentFile
                val stem = targetFile.nameWithoutExtension
                val ext = targetFile.extension
                var mutated = buildMutatedName(parent, stem, ext, seq)
                while (occupied.contains(mutated.relativeTo(targetDir).path.replace('\\', '/'))) {
                    seq += 1
                    mutated = buildMutatedName(parent, stem, ext, seq)
            }
                seqByPath[rel] = seq
                val mutatedRel = mutated.relativeTo(targetDir).path.replace('\\', '/')
                occupied.add(mutatedRel)
                // ⭐ REQ-B: persiste el renombre en el registro para que
                // fastMergeAllSources (línea ~134) coloque el archivo en su ruta
                // mutada y applySourceRewrites (Fase 1B) reescriba las referencias
                // JSON de ESTE addon. Sin este registro, hasGlobalMutations() era
                // false, la reescritura se saltaba y las referencias quedaban rotas
                // al colisionar dos addons en la misma ruta.
                resourceRegistry.registerSourceMutation(sourceFile, rel, mutatedRel)
            }
        }
        // El plan no copia nada: la caché del destino quedó obsoleta.
        DirIndexCache.invalidate(targetDir)
    }

    /**
     * Construye el nombre físico mutado de forma DETERMINISTA y única:
     * `nombre_pf<seq>.<ext>`. Sustituye al `System.currentTimeMillis()` original,
     * que podía generar el mismo destino para dos colisiones concurrentes.
     *
     * [dir] admite null (un File sin padre) devolviendo un nombre relativo.
     */
    private fun buildMutatedName(dir: File?, stem: String, ext: String, seq: Int): File {
        val name = if (ext.isEmpty()) "${stem}_pf$seq" else "${stem}_pf$seq.$ext"
        return if (dir != null) File(dir, name) else File(name)
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
        resourceRegistry.clearGlobalMutations()

        try {
            // a) EXTRAER TODOS LOS ADDONS (PARALELIZADO - 3-5x más rápido)
            progressCallback?.onProgress("Extrayendo addons en paralelo...", 4)
            PackForgeLog.d("PackForge_Export", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            PackForgeLog.d("PackForge_Export", "🚀 INICIANDO EXPORTACIÓN: ${addonPaths.size} addons (PARALELO)")

            val tExtraccionInicio = System.currentTimeMillis()
            
            val extractionResults = withContext(Dispatchers.IO) {
                addonPaths.mapIndexed { index, addonPath ->
                    async(Dispatchers.IO) {
                        val addonFile = File(addonPath)
                        val addonDisplayName = addonNames.getOrNull(index) ?: addonFile.nameWithoutExtension
                        
                        if (!addonFile.exists()) {
                            PackForgeLog.w(TAG, "⚠️ Addon no existe, saltando: $addonPath")
                            FusionReportBuilder.addIssue(addonDisplayName, "ZIP", "El archivo no existe en disco", "El archivo del addon no se encontró.", FusionIssue.Severity.FATAL)
                            return@async null
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
                        extractedPath
                    }
                }.awaitAll()
            }

            extractionResults.filterNotNull().forEach { extractedDirs.add(it) }
            coroutineContext.ensureActive()

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
            progressCallback?.onProgress("Clasificando addons...", 8)
            PackForgeLog.d("PackForge_Debug", "Clasificando ${extractedDirs.size} addons extraídos")
            
            val bpDirs = mutableListOf<String>()
            val rpDirs = mutableListOf<String>()
            
            for ((idx, extractedDir) in extractedDirs.withIndex()) {
                coroutineContext.ensureActive()
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

            // ARQUITECTURA EAGER+LAZY: NO se construye índice masivo aquí.
            // El índice anterior hacía walkTopDown + JSON parse de TODOS los archivos (O(N*M) -> 20min).
            // Ahora se difiere: solo se escanean los *.entity.json del output en Fase 2.
            PackForgeLog.d(TAG, "⚡ Fase 1 Eager: copia rápida sin pre-parseo masivo")

            // LOG OBLIGATORIO: Resumen de clasificación
            PackForgeLog.d("PackForge_Export", "📊 RESUMEN DE CLASIFICACIÓN:")
            PackForgeLog.d("PackForge_Export", "  BPs detectados: ${bpDirs.size}")
            PackForgeLog.d("PackForge_Export", "  RPs detectados: ${rpDirs.size}")

            // ══ RENAMESPACER (H1 — REACTIVADO CON DISEÑO OPTIMIZADO) ══
            // ANTES aquí se ejecutaba IdentifierRemapper.run() y se DESACTIVÓ
            // porque leía Y parseaba TODOS los JSON de todos los BP/RP sin
            // filtro (readDeclaredIdentifier = readText + JSONObject por
            // archivo) → bloqueaba la UI 10+ min en "Clasificando addons".
            //
            // Se reactiva con el diseño acordado (H1):
            //  · filtro barato de texto ANTES de parsear (sin "identifier" ni
            //    se abre el JSON) → en packs sin colisiones el costo es O(bytes)
            //  · DirIndexCache: una sola pasada de disco
            //  · la reescritura SOLO ocurre si hay colisiones reales y SOBRE
            //    los archivos de los addons perdedores
            //  · progressCallback para que la UI muestre avance real
            // Sin esto, dos addons con el MISMO id se aniquilan en silencio
            // ("uno gana") y el contenido del perdedor desaparece.
            idRenames = if (bpDirs.size + rpDirs.size >= 2) {
                try {
                    IdentifierRemapper.run(
                        bpDirs.map { File(it) },
                        rpDirs.map { File(it) }
                    ) { msg ->
                        // Wrapper para progreso: convertir onProgress (suspend) a función regular
                        runBlocking { progressCallback?.onProgress(msg) }
                    }
                } catch (e: Exception) {
                    PackForgeLog.e(TAG, "Renamespacer falló (no bloqueante): ${e.message}")
                    emptyList()
                }
            } else {
                emptyList()
            }
            // El remapper REESCRIBE y RENOMBRA archivos en los directorios de
            // origen: el índice cacheado queda obsoleto y debe invalidarse
            // antes de que la copia lo consuma.
            if (idRenames.isNotEmpty()) {
                bpDirs.forEach { DirIndexCache.invalidate(File(it)) }
                rpDirs.forEach { DirIndexCache.invalidate(File(it)) }
            }
            coroutineContext.ensureActive()
            
            // ARQUITECTURA NUEVA: FASE 1 EAGER + FASE 2 LAZY (Evita indexado masivo de 20 min)
            // Los archivos críticos se fusionan de forma selectiva después de la copia rápida
            val mergedBpDir = File(tempDir, "merged_bp")
            val mergedRpDir = File(tempDir, "merged_rp")
            
            // ══ FASE 1A: PLAN DE MUTACIONES DE RECURSOS ══
            // Detecta colisiones de ruta SIN copiar, asociando cada renombrado a
            // su addon de origen. Debe ir antes de la copia y de la reescritura.
            planSourceMutations(bpDirs, mergedBpDir, resourceRegistry)
            planSourceMutations(rpDirs, mergedRpDir, resourceRegistry)

            // ══ FASE 1B: REESCRITURA DE REFERENCIAS POR FUENTE ══
            // ⭐ REQ-B: cada JSON de origen se actualiza con las rutas mutadas de
            // SU PROPIO addon antes de copiarse. Así terrain_texture.json,
            // item_texture.json y description.textures del addon N apuntan a los
            // archivos que N realmente dejó en el pack fusionado.
            if (resourceRegistry.hasGlobalMutations()) {
                progressCallback?.onProgress("Actualizando referencias de recursos...", 12)
                val tRewriteStart = System.currentTimeMillis()
                val rewritten = resourceRegistry.applySourceRewrites(
                    bpDirs.map { File(it) } + rpDirs.map { File(it) }
                )
                PackForgeLog.d(
                    "PackForge_Export",
                    "🔄 Fase 1B: $rewritten JSONs reescritos (${resourceRegistry.totalMutations()} mutaciones, ${System.currentTimeMillis() - tRewriteStart}ms)"
                )
                // Los JSONs cambiaron en disco: invalidar para releer contenido fresco.
                DirIndexCache.clear()
            }

            // FASE 1C: Copia Eager - Solo copia de bytes con buffer de 16KB
            progressCallback?.onProgress("Copiando archivos (Fase 1 - Rápida)...", 3)
            val tEagerStart = System.currentTimeMillis()
            val copyTotalFiles = (bpDirs + rpDirs).map { DirIndexCache.index(File(it)).allFiles.size }.sum()
            val copyDone = java.util.concurrent.atomic.AtomicInteger(0)
            fastMergeAllSources(bpDirs, mergedBpDir, progressCallback, resourceRegistry, copyTotalFiles, copyDone)
            fastMergeAllSources(rpDirs, mergedRpDir, progressCallback, resourceRegistry, copyTotalFiles, copyDone)
            coroutineContext.ensureActive()
            PackForgeLog.d("PackForge_Perf", "⏱️ Fase 1 Eager completada en: ${(System.currentTimeMillis() - tEagerStart) / 1000.0}s")

            // FASE 2: Detección de huecos - Solo parsea *.entity.json del output
            progressCallback?.onProgress("Reparando dependencias faltantes (Fase 2)...", 74)
            progressCallback?.onProgress("Reparando dependencias faltantes (Fase 2)...")
            LazyDependencyResolver.resolveAndRepair(mergedRpDir, rpDirs)

            // FASE 3: Fusión de archivos críticos específicos (terrain, item, blocks, lang, etc)
            // OPTIMIZACIÓN: Fusión en paralelo para mejorar rendimiento en multi-core.
            var soundKeyRenames: Map<String, String> = emptyMap()
            var dependencyNotes: List<String> = emptyList()
            if (rpDirs.isNotEmpty() || bpDirs.isNotEmpty()) {
                progressCallback?.onProgress("Fusionando archivos críticos (paralelo)...", 76)
                val rpDirFiles = rpDirs.map { File(it) }
                val bpDirFiles = bpDirs.map { File(it) }
                val addonDirFiles = extractedDirs.map { File(it) }
                val merger = BedrockCriticalFilesMerger

                withContext(Dispatchers.IO) {
                    // Tareas paralelas de RP
                    val rpTasks = listOf(
                        async { merger.mergeTerrainTexture(rpDirFiles, mergedRpDir) },
                        async { merger.mergeItemTexture(rpDirFiles, mergedRpDir) },
                        async { merger.mergeBlocksJson(rpDirFiles, mergedRpDir) },
                        async { merger.mergeLangFiles(addonDirFiles, mergedRpDir) },
                        async { merger.mergeFlipbookTextures(rpDirFiles, mergedRpDir) },
                        async { merger.mergeGeometryFiles(rpDirFiles, mergedRpDir) },
                        async { merger.mergeRenderControllers(rpDirFiles, mergedRpDir) },
                        async { merger.mergeAnimations(rpDirFiles, mergedRpDir) },
                        async { merger.mergeSoundsJson(rpDirFiles, mergedRpDir) },
                        async { merger.mergeEntityDefinitions(rpDirFiles, mergedRpDir) },
                        async { merger.mergeEntityTextures(rpDirFiles, mergedRpDir) },
                        async { merger.mergeAttachables(rpDirFiles, mergedRpDir) }
                    )
                    // Tareas paralelas de BP
                    val bpTasks = listOf(
                        async { merger.mergePlayerEntity(bpDirFiles, mergedBpDir) },
                        async { merger.mergeLootTables(bpDirFiles, mergedBpDir) },
                        async { merger.mergeRecipes(bpDirFiles, mergedBpDir) }
                    )
                    (rpTasks + bpTasks).awaitAll()
                }
                
                // ⚠️ mergeMaterialInstances debe ser síncrono al final, depende de terrainTexture+entity+animations
                merger.mergeMaterialInstances(bpDirFiles, rpDirFiles, mergedBpDir, mergedRpDir)
                PackForgeLog.d("PackForge_Export", "✅ Fase 3 (Paralela) completada")

                // FASE 3B: Validación de cobertura de texturas
                try {
                    val coverageReport = merger.validateTextureCoverageComplete(mergedBpDir, mergedRpDir)
                    if (coverageReport.coveragePercent < 100f) {
                        PackForgeLog.w("PackForge_Export", "⚠️ Cobertura de texturas: ${coverageReport.coveragePercent.toInt()}% - Bloques faltantes: ${coverageReport.missingBlocks.size}, Items faltantes: ${coverageReport.missingItems.size}")
                    } else {
                        PackForgeLog.d("PackForge_Export", "✅ Cobertura de texturas: 100%")
                    }
                } catch (e: Exception) {
                    PackForgeLog.w("PackForge_Export", "Validación de cobertura no bloqueante: ${e.message}")
                }

                // === FASE 3D: RESOLUCION DE DEPENDENCIAS DE ENTIDADES ===
                // Recupera geometrias/texturas/animaciones/RCs faltantes clasificando
                // por CONTENIDO (duck typing), crea alias de variantes y restaura la
                // geometria canonica si el deepmerge la muto. Va DESPUES de la Fase 3
                // para ver los archivos ya fusionados.
                if (rpDirs.isNotEmpty()) {
                    try {
                        progressCallback?.onProgress("Resolviendo dependencias de entidades...", 78);
                        dependencyNotes = EntityDependencyResolver.resolve(
                            rpDirs.map { File(it) }, mergedRpDir
                        );
                    } catch (e: Exception) {
                        PackForgeLog.w(
                            "PackForge_Export",
                            "Resolucion de dependencias no bloqueante: ${e.message}"
                        );
                    }
                }

                // === FASE 3E: DETECCION DE client_entity DUPLICADO (TICKET-CORE-003) ===
  // Bedrock descarta en silencio los client_entity con identifier repetido.
      // Se reportan siempre por ConflictRegistry (nunca se resuelve en silencio);
                // los duplicados VANILLA se apartan a entity/.shadow/ para no romper el pack.
        try {
            merger.detectDuplicateClientEntities(mergedRpDir)
        } catch (e: Exception) {
    PackForgeLog.w("PackForge_Export", "Deteccion de duplicados no bloqueante: ${e.message}")
     }
            }
            coroutineContext.ensureActive()
            val tFusionFin = tEagerStart
            val totalJsonsMerged = 0 // Se reporta en fase 3 si se desea
            
            // e) GENERAR MANIFIESTOS VINCULADOS
            progressCallback?.onProgress("Generando manifiestos...", 80)
            coroutineContext.ensureActive()
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
            progressCallback?.onProgress("Analizando scripts...", 82)
            val scriptFindings = ScriptCollisionAnalyzer.analyze(File(mergedBpDir, "scripts"))
            scriptFindings.forEach {
                PackForgeLog.w(TAG, it)
                ConflictRegistry.logConflict(
                    severity = com.packforge.app.domain.model.ConflictSeverity.HIGH,
                    type = "SCRIPT_COLLISION",
                    file = "scripts/main.js",
                    addon1 = it.take(64),
                    addon2 = "-",
                    description = it
                )
            }

            // g0) DECIFRADOR: recuperar addons closed-source con contenido oculto
            // ⚠️ DEBE ejecutarse ANTES del validador y del healer: restaura los
            // archivos canónicos rotos para que la validación vea el pack real.
            // Se procesa RP y BP: los addons cerrados también entierran copias
            // en claro dentro del behavior pack (entidades/bloques/objetos).
            progressCallback?.onProgress("Descifrando addons closed-source...", 84)
            PackForgeLog.d("PackForge_Export", "PASO 4d: Ejecutando BedrockDecifrator...")
            val decryptReport = try {
                BedrockDecifrator.decrypt(mergedRpDir, mergedBpDir)
            } catch (e: Exception) {
                PackForgeLog.w("PackForge_Export", "Decifrator tuvo error no bloqueante: ${e.message}")
                BedrockDecifrator.DecryptReport(emptyList(), emptyList(), 0, listOf("Error: ${e.message}"))
            }
            // La escritura invalida el índice: todo lo que venga después debe
            // releer el disco, no el snapshot cacheado previo.
            if (decryptReport.restored.isNotEmpty()) {
                DirIndexCache.invalidate(mergedRpDir)
                DirIndexCache.invalidate(mergedBpDir)
            }
            if (decryptReport.restored.isNotEmpty() || decryptReport.unrecoverable.isNotEmpty()) {
                PackForgeLog.w("PackForge_Export", "Decifrador: ${decryptReport.summary}")
            }
            PackForgeLog.d("PackForge_Export", "PASO 4d completado")

            // f0) HEALER · REPARACIÓN: copia texturas/modelos faltantes desde los
            // addons originales ANTES de validar, para que el validador mida el
            // estado REAL del pack. Si una geometría no está en ningún addon,
            // genera un placeholder de caja para que la entidad exista en el
            // mundo en vez de no renderizarse.
            progressCallback?.onProgress("Reparando texturas y modelos faltantes...", 87)
            val healerRepair = try {
                PackForgeHealer.repair(mergedBpDir, mergedRpDir, extractedDirs)
            } catch (e: Exception) {
                PackForgeLog.w("PackForge_Export", "Healer.repair no bloqueante: ${e.message}")
                PackForgeHealer.RepairResult()
            }
            if (healerRepair.totalFixed > 0) {
                DirIndexCache.invalidate(mergedRpDir)
                DirIndexCache.invalidate(mergedBpDir)
                PackForgeLog.d("PackForge_Export", "💊 ${healerRepair.summary}")
            }
            val healerNotes = mutableListOf<String>()
            if (healerRepair.totalFixed > 0) healerNotes += healerRepair.summary
            if (healerRepair.placeholdersGenerated > 0) {
                healerNotes += "Geometrías placeholder generadas (caja 8x8x8): ${healerRepair.placeholdersGenerated}"
            }
            healerRepair.stillMissingTextures.take(5).forEach {
                healerNotes += "sin textura en ningún addon: $it"
            }
            healerRepair.stillMissingModels.take(5).forEach {
                healerNotes += "sin modelo en ningún addon: $it"
            }

            // f) EJECUTAR VALIDADOR DE REFERENCIAS CRUZADAS
            progressCallback?.onProgress("Validando referencias...", 89)
            PackForgeLog.d("PackForge_Export", "🔧 PASO 5: Ejecutando validador de referencias...")
            val validationResult = PackForgeValidator.validate(
                bpDir = mergedBpDir,
                rpDir = mergedRpDir,
                originalAddons = extractedDirs,
                identifierIndex = null, // Ya no se usa el índice masivo; resolución se hace en LazyDependencyResolver
                progressCallback = progressCallback
            )
            coroutineContext.ensureActive()
            PackForgeLog.d("PackForge_Export", "🔧 PASO 5 completado")

            // VERACIDAD DEL REPORTE: si faltan modelos, texturas o hay entidades
            // incompletas, el resumen NO puede afirmar "sin errores críticos".
            validationResult.let { vr ->
                if (vr.missingModels.isNotEmpty()) {
                    FusionReportBuilder.addIssue(
                        "validación post-fusión", "models/",
                        "Modelos/geometría faltantes: ${vr.missingModels.size}",
                        "${vr.missingModels.size} modelo(s) o geometría(s) sin resolver (p.ej. ${vr.missingModels.take(2).joinToString(", ")})",
                        FusionIssue.Severity.RECOVERABLE
                    )
                }
                if (vr.missingTextures.isNotEmpty()) {
                    FusionReportBuilder.addIssue(
                        "validación post-fusión", "textures/",
                        "Texturas faltantes: ${vr.missingTextures.size}",
                        "${vr.missingTextures.size} textura(s) no disponibles (p.ej. ${vr.missingTextures.take(2).joinToString(", ")})",
                        FusionIssue.Severity.RECOVERABLE
                    )
                }
                if (vr.incompleteEntities.isNotEmpty()) {
                    FusionReportBuilder.addIssue(
                        "validación post-fusión", "entity/",
                        "Entidades incompletas: ${vr.incompleteEntities.size}",
                        "${vr.incompleteEntities.size} entidad(es) con dependencias faltantes (p.ej. ${vr.incompleteEntities.take(2).joinToString("; ")})",
                        FusionIssue.Severity.RECOVERABLE
                    )
                }
            }

            // g1) HEALER: análisis post-merge NO bloqueante (texturas rotas, fuzzy match)
            progressCallback?.onProgress("Analizando integridad del pack...", 91)
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
            val resolvedCoverPath = applyCustomIcon(mergedBpDir, mergedRpDir, customIconPath)
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
            progressCallback?.onProgress("Empaquetando modpack...", 93)
            coroutineContext.ensureActive()
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

            // Generar metadata de PackForge.ID para identificación exclusiva de modpacks
            val modpackId = java.util.UUID.randomUUID().toString()
            val idJson = org.json.JSONObject().apply {
                put("signature", "PACKFORGE_MODPACK")
                put("id", modpackId)
                put("name", customName)
                put("author", customAuthor)
                put("version", customVersion)
                put("description", customDescription)
                put("addonCount", addonPaths.size)
                put("exportedAt", System.currentTimeMillis())
                put("addonNames", org.json.JSONArray(addonNames))
            }.toString()

            createMcAddon(
                if (bpDirs.isNotEmpty()) mergedBpDir else null,
                if (rpDirs.isNotEmpty()) mergedRpDir else null,
                outputFile,
                idJson
            )

            // ⭐ VERIFICACIÓN POST-ZIP ⭐
            // El síntoma real del usuario fue "RP sin portada" e "ZIP inválido",
            // y ambos se detectan LeyENDO el .mcaddon recién escrito, no el
            // directorio intermedio. Se comprueba aquí, sobre el archivo final.
            val zipAudit = auditFinalMcAddon(outputFile, bpDirs.isNotEmpty(), rpDirs.isNotEmpty())
            zipAudit.forEach { (severity, type, detail) ->
                ConflictRegistry.logConflict(
                    severity = severity,
                    type = type,
                    file = outputFile.name,
                    addon1 = "empaquetado final",
                    addon2 = "-",
                    description = detail
                )
            }
            if (zipAudit.isEmpty()) {
                PackForgeLog.d("PackForge_ZIP", "✅ Auditoría del .mcaddon: manifiestos y portadas presentes")
            }

            // ══ VALIDACIÓN POST-FUSIÓN + REPORTE LEGIBLE ══
            progressCallback?.onProgress("Generando reporte de fusión...", 96)
            val syntaxErrors = MergeReportGenerator.validateJsonSyntax(mergedBpDir) +
                MergeReportGenerator.validateJsonSyntax(mergedRpDir)

        // TICKET-CORE-003 Parte 4: auditoria de integridad (texto plano y claves cifradas).
  // No bloquea la exportacion: se registra y se muestra en el reporte.
   val integrityFindings = try {
        MergeReportGenerator.auditJsonIntegrity(mergedBpDir) +
             MergeReportGenerator.auditJsonIntegrity(mergedRpDir)
   } catch (e: Exception) {
            PackForgeLog.w("PackForge_Export", "Auditoria de integridad no bloqueante: ${e.message}")
          emptyList()
        }
        integrityFindings.forEach { finding ->
        ConflictRegistry.logConflict(
        severity = when (finding.severity) {
     MergeReportGenerator.IntegrityFinding.Severity.INFO -> ConflictSeverity.LOW
            MergeReportGenerator.IntegrityFinding.Severity.LOW -> ConflictSeverity.LOW
       MergeReportGenerator.IntegrityFinding.Severity.HIGH -> ConflictSeverity.HIGH
       },
    type = finding.kind,
 file = finding.relativePath,
        addon1 = "integridad del pack",
          addon2 = "-",
          description = finding.detail
  )
        }
        val integrityLines = MergeReportGenerator.formatIntegrityFindings(integrityFindings)
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
      integrityFindings = integrityLines,
                    validationResult = validationResult,
                    dependencyNotes = dependencyNotes,
                    zipValidation = zipValidation,
                    zipAuditNotes = zipAudit.map { it.third },
                    decryptRestored = decryptReport.restored.size,
                    decryptUnrecoverable = decryptReport.unrecoverable,
                    decryptUnrecoverableNeutralized = decryptReport.neutralizedUnrecoverable.size,
                    decryptDecoysNeutralized = decryptReport.renamedDecoys.size,
                    healerNotes = healerNotes,
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
            progressCallback?.onProgress("Limpiando temporales...", 98)
            cleanupTempDirs(tempDir)
            val reportPath = File(outputDir, "fusion_report.txt").absolutePath
            // OJO: AÑADIR (append), no sobrescribir. El reporte detallado ya se
            // escribió arriba (MergeReportGenerator: renombres de IDs, aliases de
            // recursos, hallazgos de scripts, errores de sintaxis). Antes esta
            // segunda escritura lo machacaba y el usuario perdía todo el
            // diagnóstico, quedándose solo con el resumen de FusionReportBuilder.
            OutputStreamWriter(
                FileOutputStream(reportPath, /* append = */ true),
                StandardCharsets.UTF_8
            ).use {
                it.write("\n")
                it.write(FusionReportBuilder.generateReport())
            }

            return MergeResult(
                success = !FusionReportBuilder.hasFatal(),
                outputPath = outputFile.absolutePath,
                bpUuid = bpUuid,
                rpUuid = rpUuid,
                totalJsonsMerged = totalJsonsMerged,
                reportPath = reportPath,
                autoCoverPath = resolvedCoverPath?.takeIf { customIconPath == null }
            )
            
        } catch (e: CancellationException) {
            PackForgeLog.d(TAG, "Fusión cancelada")
            cleanupTempDirs(tempDir)
            throw e
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
                // ⭐ OPTIMIZACIÓN: Usar DirIndexCache en lugar de walkTopDown() para diagnóstico
                DirIndexCache.index(bpDir).allFiles.forEach { file ->
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
                // ⭐ OPTIMIZACIÓN: Usar DirIndexCache en lugar de walkTopDown() para diagnóstico
                DirIndexCache.index(rpDir).allFiles.forEach { file ->
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

            // B8: los alias de structures/ se referencian en .mcfunction como
            // `/structure load <stem>`; hay que reescribir esos comandos o la
            // estructura renombrada queda inaccesible ("Structure not found").
            val structureStems = FolderAliasRegistry.structureStemRenames(folderRenames)

            // H2: exponer colisiones de ui/ como hallazgos HIGH. Antes el alias
            // de ui/ era ignorado por Bedrock (binding por nombre exacto) y la
            // UI del perdedor se PERDÍA en silencio; ahora el usuario lo ve.
            FolderAliasRegistry.uiConflicts
                .filter { it.loserAddon == sourceAddonName }
                .forEach { c ->
                    ConflictRegistry.logConflict(
                        severity = com.packforge.app.domain.model.ConflictSeverity.HIGH,
                        type = "UI_COLLISION",
                        file = c.path,
                        addon1 = c.winnerAddon,
                        addon2 = c.loserAddon,
                        description = "La UI '${c.path}' de '$sourceAddonName' NO se aplicará: " +
                            "'${c.winnerAddon}' gana la ruta canónica (Bedrock carga la UI por " +
                            "nombre exacto). El archivo perdedor quedó archivado en " +
                            "_packforge/ui_discard/ para recuperación manual."
                    )
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
                    } else if (structureStems.isNotEmpty() &&
                        file.extension.equals("mcfunction", ignoreCase = true)
                    ) {
                        // B8: reescribir referencias `/structure load <stem>`
                        // antes de copiar; sin esto la estructura renombrada
                        // queda inaccesible en gameplay.
                        try {
                            var text = file.readText(Charsets.UTF_8)
                            var changed = false
                            structureStems.forEach { (oldStem, newStem) ->
                                if (!text.contains(oldStem)) return@forEach
                                val re = Regex("\\b" + Regex.escape(oldStem) + "\\b")
                                if (re.containsMatchIn(text)) {
                                    text = re.replace(text, newStem)
                                    changed = true
                                }
                            }
                            if (changed) {
                                targetFile.parentFile?.mkdirs()
                                targetFile.writeText(text, Charsets.UTF_8)
                                PackForgeLog.d(
                                    "PackForge_Debug",
                                    "  🧩 Estructuras reescritas en ${file.name} ($effectivePath)"
                                )
                            } else {
                                FileUtils.fastCopy(file, targetFile)
                            }
                        } catch (e: Exception) {
                            PackForgeLog.w(
                                "PackForge_Debug",
                                "  ⚠️ No se pudo reescribir ${file.name}, copia directa: ${e.message}"
                            )
                            FileUtils.fastCopy(file, targetFile)
                        }
                        fileOriginIndex.putIfAbsent(effectivePath, sourceIndex)
                        nonJsonCount++
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

        // Garantizar dependencias de script siempre completas (fix trabe con UI / iluminación dinámica).
        // Se inyectan @minecraft/server + @minecraft/server-ui si hay cualquier script,
        // más las librerías reales detectadas en los manifests originales/librerías.
        // No duplicar entradas ya presentes; conservar versión mayor.
        val scriptDependencies = bpManifestObj.optJSONArray("dependencies") ?: JSONArray().also { bpManifestObj.put("dependencies", it) }
        fun ensureModuleDep(name: String, fallbackVersion: String) {
            val existing = (0 until scriptDependencies.length())
                .mapNotNull { scriptDependencies.optJSONObject(it) }
                .firstOrNull { it.optString("module_name", "") == name }
            if (existing != null) {
                val current = existing.optString("version", fallbackVersion)
                if (compareSemver(fallbackVersion, current) > 0) existing.put("version", fallbackVersion)
                return
            }
            // Si ya viene por biblioteca detectada, tomar su versión máxima
            val libVersion = scriptResult.libraryDeps[name] ?: fallbackVersion
            scriptDependencies.put(JSONObject().apply {
                put("module_name", name)
                put("version", libVersion)
            })
        }
        if (scriptResult.addonCount > 0) {
            scriptResult.libraryDeps.forEach { (name, ver) ->
                var existing: JSONObject? = null
                for (i in 0 until scriptDependencies.length()) {
                    val dep = scriptDependencies.optJSONObject(i) ?: continue
                    if (dep.optString("module_name", "") == name) { existing = dep; break }
                }
                if (existing != null) {
                    if (compareSemver(ver, existing.optString("version", "1.0.0")) > 0) existing.put("version", ver)
                } else {
                    scriptDependencies.put(JSONObject().apply { put("module_name", name); put("version", ver) })
                }
            }
            ensureModuleDep("@minecraft/server", "1.16.0")
            ensureModuleDep("@minecraft/server-ui", "1.3.0")
            // B2 (auditoría CORE): SIN inyección ciega de `dynamic_light`.
            // Los capabilities son declaración del AUTOR: buildMergedBpManifest
            // ya propagó los que los manifests originales traían (REGLA 7).
            // Inyectarlo aquí a todo pack con scripts cambiaba la iluminación
            // y obligaba la capability a todos los jugadores de la sesión.
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

        // Añadir el módulo script combinado si hay scripts (entry validado en mergeScripts)
        if (scriptResult.addonCount > 0) {
            newModules.put(JSONObject().apply {
                put("type", "script")
                put("language", "javascript")
                put("uuid", UUID.randomUUID().toString())
                put("version", JSONArray(listOf(1, 0, 0)))
                put("entry", "scripts/main.js")
            })
            val mainJs = File(mergedBpDir, "scripts/main.js")
            require(mainJs.exists() && mainJs.length() > 0) {
                "❌ scripts/main.js no existe o está vacío tras mergeScripts"
            }
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
        // Comparación semántica de versión (major, minor) >= (1, 20).
        // OJO: no usar `getInt(0) >= 1 && getInt(1) >= 20` porque eso exige
        // major>=1 Y minor>=20 a la vez, y rechazaría versiones válidas como
        // 1.19.x (minor=19) o cualquier 2.x (minor=0).
        val mevMajor = mev.optInt(0, 0)
        val mevMinor = mev.optInt(1, 0)
        require(mevMajor > 1 || (mevMajor == 1 && mevMinor >= 20)) {
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
    private fun applyCustomIcon(mergedBpDir: File?, mergedRpDir: File?, customIconPath: String?): String? {
        PackForgeLog.d("PackForge_Icon", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        PackForgeLog.d("PackForge_Icon", "🎨 APLICANDO PORTADA PERSONALIZADA")
        PackForgeLog.d("PackForge_Icon", "   BP dir: ${mergedBpDir?.absolutePath ?: "null"}")
        PackForgeLog.d("PackForge_Icon", "   RP dir: ${mergedRpDir?.absolutePath ?: "null"}")
        PackForgeLog.d("PackForge_Icon", "   Icon path: $customIconPath")
        
        // Si no se proporcionó icono personalizado, elegir uno de los addons fusionados
        // (portada aleatoria pero consistente para BP y RP)
        val effectiveIconPath = if (customIconPath == null) {
            PackForgeLog.w("PackForge_Icon", "⚠️ No se proporcionó icono personalizado. Buscando portada de addon...")
            val bpDir = mergedBpDir ?: mergedRpDir
            val rpDir = mergedRpDir ?: mergedBpDir
            val iconCandidate = pickAddonIcon(bpDir, rpDir)
            if (iconCandidate != null) {
                PackForgeLog.d("PackForge_Icon", "🖼️ Portada elegida de addon: $iconCandidate")
            } else {
                PackForgeLog.w("PackForge_Icon", "⚠️ No se encontró portada de addon válida")
            }
            iconCandidate
        } else {
            customIconPath
        }

        if (effectiveIconPath == null) {
            PackForgeLog.w("PackForge_Icon", "⚠️ Sin portada disponible. Saltando.")
            return null
        }

        val iconFile = File(effectiveIconPath)
        if (!iconFile.exists()) {
            PackForgeLog.e("PackForge_Icon", "❌ El archivo de icono no existe: $effectiveIconPath")
            return null
        }

        PackForgeLog.d("PackForge_Icon", "   Icon file existe: ${iconFile.exists()}")
        PackForgeLog.d("PackForge_Icon", "   Icon file tamaño: ${iconFile.length()} bytes")

        // ⭐ BUG REAL (verificado en Test 1.mcaddon): la portada suele salir de
        // pickAddonIcon(), que elige un pack_icon.png DENTRO de mergedRpDir o
        // mergedBpDir. El bucle borraba el destino antes de copiar: si el origen
        // y el destino eran el MISMO archivo, el delete() destruía la fuente y
        // el RP quedaba SIN pack_icon.png (el juego mostraba el pack sin
        // portada). Se lee el origen UNA vez en memoria y se trabaja sobre esa
        // copia: el disco nunca vuelve a ser la fuente de verdad.
        val iconBytes = try {
            iconFile.readBytes()
        } catch (e: Exception) {
            PackForgeLog.e("PackForge_Icon", "❌ No se pudo leer la portada: ${e.message}")
            return null
        }
        if (iconBytes.isEmpty()) {
            PackForgeLog.e("PackForge_Icon", "❌ La portada está vacía")
            return null
        }

        val dirs = listOfNotNull(mergedBpDir, mergedRpDir).filter { it.exists() }

        if (dirs.isEmpty()) {
            PackForgeLog.e("PackForge_Icon", "❌ No hay directorios válidos para aplicar icono")
            return null
        }

        PackForgeLog.d("PackForge_Icon", "   Directorios a procesar: ${dirs.size}")

        dirs.forEach { dir ->
            try {
                val targetIcon = File(dir, "pack_icon.png")

                // Si el origen ES el destino, no hay nada que borrar: se
                // sobrescribe en su sitio sin destruir la fuente.
                val sourceIsTarget = try {
                    iconFile.canonicalPath == targetIcon.canonicalPath
                } catch (_: Exception) { false }

                if (targetIcon.exists() && !sourceIsTarget) {
                    val deleted = targetIcon.delete()
                    PackForgeLog.d("PackForge_Icon", "🗑️ Icono anterior eliminado de ${dir.name}: $deleted")
                } else {
                    PackForgeLog.d("PackForge_Icon", "ℹ️ Sin icono anterior en ${dir.name}")
                }

                // CRÍTICO: Minecraft espera pack_icon.png CUADRADO (256x256 o
                // 512x512). Si la portada es ancha o vertical se hace
                // center-crop a cuadrado + escalado, para que el icono encaje
                // sin espacios vacíos.
                val processed = processIconCrop(iconBytes, targetIcon)

                if (processed) {
                    PackForgeLog.d("PackForge_Icon", "✅ pack_icon.png generado (256x256, center-crop) en ${dir.name}")
                } else {
                    // Fallback: escribir los bytes originales tal cual.
                    PackForgeLog.w("PackForge_Icon", "⚠️ No se pudo procesar la portada, copiando original")
                    FileOutputStream(targetIcon).use { it.write(iconBytes) }
                }

                // VERIFICAR que el archivo se creó
                if (targetIcon.exists() && targetIcon.length() > 0) {
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
        
        // CRÍTICO: verificar que el icono quedó en disco antes de empaquetar.
        // Bug histórico: el RP salía SIN pack_icon.png y el juego mostraba el
        // pack sin portada. Si falta, se reintenta desde los bytes ya leídos
        // para no depender del estado del disco.
        dirs.forEach { dir ->
            val check = File(dir, "pack_icon.png")
            if (!check.exists() || check.length() == 0L) {
                PackForgeLog.w("PackForge_Icon", "⚠️ Reintentando icono en ${dir.name}")
                try {
                    FileOutputStream(check).use { it.write(iconBytes) }
                    if (check.length() > 0) {
                        PackForgeLog.d("PackForge_Icon", "✅ Icono recuperado en ${dir.name}")
                    }
                } catch (e: Exception) {
                    PackForgeLog.e("PackForge_Icon", "❌ Reintento fallido en ${dir.name}: ${e.message}")
                }
            }
        }

        PackForgeLog.d("PackForge_Icon", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        return effectiveIconPath
    }

    /**
     * Busca un pack_icon.png válido dentro de los addons fusionados para usar como
     * portada automática cuando el usuario no seleccionó una. Elige ALEATORIAMENTE
     * entre todos los iconos disponibles (como pediste: probabilidad uniforme cada
     * vez), y ese mismo gana tanto para BP como RP (consistencia visual).
     */
    private fun pickAddonIcon(bpDir: File?, rpDir: File?): String? {
        val candidatos = mutableListOf<String>()
        for (dir in listOfNotNull(bpDir, rpDir)) {
            if (!dir.exists()) continue
            val cachedFiles = DirIndexCache.index(dir).allFiles
            for (file in cachedFiles) {
                if (file.name.equals("pack_icon.png", ignoreCase = true) && file.length() > 0) {
                    candidatos.add(file.absolutePath)
                }
            }
        }
        if (candidatos.isEmpty()) return null
        val elegido = candidatos.random()
        PackForgeLog.d("PackForge_Icon", "🎲 Portada aleatoria: $elegido entre ${candidatos.size} candidatos")
        return elegido
    }

    /**
     * Convierte la portada en un icono CUADRADO (center-crop) de 256x256 PNG,
     * el formato que Minecraft Bedrock espera para pack_icon.png.
     *
     * Recibe los bytes YA EN MEMORIA (no una ruta): así el archivo de origen
     * puede seguir existiendo, haber sido renombrado o vivir dentro del propio
     * pack que se está modificando, sin que el resultado dependa del disco.
     *
     * Devuelve true si se procesó correctamente.
     */
    private fun processIconCrop(iconBytes: ByteArray, targetFile: File): Boolean {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(iconBytes, 0, iconBytes.size, options)
            val srcW = options.outWidth
            val srcH = options.outHeight
            if (srcW <= 0 || srcH <= 0) return false

            // Decodificar con reducción de sampleo para no saturar memoria
            val sampleSize = calculateInSampleSize(srcW, srcH, 512)
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val srcBitmap = BitmapFactory.decodeByteArray(iconBytes, 0, iconBytes.size, decodeOpts)
                ?: return false

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
            targetFile.exists() && targetFile.length() > 0
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
    private fun createMcAddon(mergedBpDir: File?, mergedRpDir: File?, outputFile: File, idContent: String? = null) {
        if (mergedBpDir != null) DirIndexCache.invalidate(mergedBpDir)
        if (mergedRpDir != null) DirIndexCache.invalidate(mergedRpDir)
        val bpIdx = mergedBpDir?.let { DirIndexCache.index(it) }
        val rpIdx = mergedRpDir?.let { DirIndexCache.index(it) }
        var entryCount = 0
        ZipOutputStream(
            BufferedOutputStream(FileOutputStream(outputFile), 262144) // ⭐ 256KB buffer
        ).use { zos ->
            // Nivel de compresión: solo afecta a las entradas que van en
            // streaming (>32 MB). El grueso del pack se emite STORED (ver
            // addFolderToZip) porque es lo único que elimina el data
            // descriptor que hacía que Bedrock rechazase el .mcaddon.
            zos.setLevel(Deflater.BEST_SPEED) // Nivel 1: rápido, tamaño casi igual

            // Inyectar PackForge.ID al inicio del ZIP para identificación exclusiva de modpack
            if (!idContent.isNullOrBlank()) {
                val idEntry = ZipEntry("PackForge.ID")
                zos.putNextEntry(idEntry)
                zos.write(idContent.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
                entryCount++
                PackForgeLog.d("PackForge_ZIP", "🏷️ PackForge.ID agregado en la raíz del modpack")
            }

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
            // ⭐ NO EMPAQUETAR BASURA INTERNA ⭐
            // - `.bak`: evidencia que deja el decifrador al neutralizar señuelos.
            //   Iban dentro del .mcaddon (15 en Test 1) y exponían al usuario
            //   restos de addons cerrados.
            // - `.json` con nombre oculto (`.OU.json`): son las copias
            //   ENTERRADAS del esquema closed-source, en rutas absurdas que
            //   Bedrock ignora. Ningún asset legítimo de Bedrock usa ese patrón.
            // - `_packforge/`: archivo perdedor de ui/ archivado por H2.
            // Todo sigue en disco para el reporte; solo dejamos de enviarlo.
            val ext = curFile.extension.lowercase(Locale.ROOT)
            val isInternalJunk = ext == "bak" ||
                curFile.name.startsWith(".") ||
                relForJunkCheck(sourceFolder, curFile).startsWith("_packforge/")
            if (isInternalJunk) return@forEach

            val relativePath = curFile.relativeTo(sourceFolder).path
            if (curFile.name == "pack_icon.png") {
                logFile { "🎨 Agregando pack_icon.png al ZIP desde: ${curFile.absolutePath} (${curFile.length()} bytes) en $zipFolderPath/$relativePath" }
            }
            // Los nombres de entrada ZIP DEBEN usar '/' (especificación ZIP).
            // File.path usa el separador de la PLATAFORMA: en Windows daría
            // 'entities\\foo.json' y Minecraft rechazaría el paquete.
            val relNormalized = relativePath.replace('\\', '/')
            val zipEntryName = if (relNormalized.isEmpty()) "$zipFolderPath/${curFile.name}" else "$zipFolderPath/$relNormalized"

            // ⭐ COMPATIBILIDAD BEDROCK: sin "data descriptor" ⭐
            //
            // CAUSA REAL del "ZIP inválido", confirmada por forensia sobre el
            // .mcaddon que el usuario importó: las 130 entradas tenían activo
            // el bit 3 de la cabecera local (data descriptor) con tamaño y CRC
            // en 0. ZipOutputStream lo activa SIEMPRE sobre un stream no
            // seekable, y el lector estricto de Bedrock lo rechaza.
            //
            // Verificado empíricamente (sandbox, 3 casos):
            //   · DEFLATED + size/crc declarados → bit 3 SIGUE activo (no sirve)
            //   · STORED   + size/crc declarados → bit 3 DESAPARECE  ✅
            // Por eso la salida usa STORED: es lo único que elimina el
            // descriptor. Minecraft acepta entradas sin comprimir sin problema,
            // y el .mcaddon resultante es incluso más rápido de importar.
            val size = curFile.length()
            if (size in 1..ZIP_INLINE_MAX_BYTES) {
                val bytes = curFile.readBytes()
                val crc = CRC32().apply { update(bytes) }.value
                val entry = ZipEntry(zipEntryName).apply {
                    method = ZipEntry.STORED
                    setSize(bytes.size.toLong())
                    setCrc(crc)
                }
                zos.putNextEntry(entry)
                zos.write(bytes)
                zos.closeEntry()
            } else {
                // Vacíos o enormes: streaming. Conservan el data descriptor,
                // pero evitan cargar el binario completo en memoria.
                zos.putNextEntry(ZipEntry(zipEntryName))
                FileInputStream(curFile).use { ins -> ins.copyTo(zos, 65536) }
                zos.closeEntry()
            }
            count++
        }
        return count
    }

    /**
     * Auditoría del .mcaddon YA ESCRITO: comprueba sobre el archivo final lo que
     * el juego va a comprobar al importar.
     *
     * Devuelve una lista de (severidad, tipo, detalle). Vacía = paquete sano.
     *
     * Verifica:
     *  1. El ZIP abre y tiene directorio central válido (EOCD).
     *  2. Cada pack esperado declara su manifest.json.
     *  3. Cada pack tiene su pack_icon.png (si no, el juego lo muestra sin portada).
     *  4. Ninguna entrada usa '\\' como separador (ZIP inválido en parsers estrictos).
     */
    private fun auditFinalMcAddon(
        outputFile: File,
        expectBp: Boolean,
        expectRp: Boolean
    ): List<Triple<com.packforge.app.domain.model.ConflictSeverity, String, String>> {
        val findings = mutableListOf<Triple<com.packforge.app.domain.model.ConflictSeverity, String, String>>()
        if (!outputFile.exists() || outputFile.length() == 0L) {
            findings += Triple(
                com.packforge.app.domain.model.ConflictSeverity.CRITICAL,
                "MADDON_VACIO",
                "El archivo ${outputFile.name} no existe o está vacío."
            )
            return findings
        }

        val entryNames: List<String> = try {
            ZipFile(outputFile).use { zip ->
                zip.entries().toList().map { it.name }
            }
        } catch (e: Exception) {
            findings += Triple(
                com.packforge.app.domain.model.ConflictSeverity.CRITICAL,
                "ZIP_CORRUPTO",
                "El .mcaddon no se puede abrir como ZIP (${e.javaClass.simpleName}: ${e.message}). " +
                    "Minecraft lo rechazará como 'ZIP inválido'."
            )
            return findings
        }

        if (entryNames.isEmpty()) {
            findings += Triple(
                com.packforge.app.domain.model.ConflictSeverity.CRITICAL,
                "ZIP_SIN_ENTRADAS",
                "El .mcaddon no contiene ningún archivo."
            )
            return findings
        }

        // (4) Separadores: el ZIP exige '/'.
        val withBackslash = entryNames.count { it.contains('\\') }
        if (withBackslash > 0) {
            findings += Triple(
                com.packforge.app.domain.model.ConflictSeverity.CRITICAL,
                "ZIP_SEPARADOR_INVALIDO",
                "$withBackslash entrada(s) usan '\\' en lugar de '/'. Minecraft no las reconoce."
            )
        }

        val packs = buildList {
            if (expectBp) add("BP_PackForge")
            if (expectRp) add("RP_PackForge")
        }

        packs.forEach { packRoot ->
            // (2) manifest.json
            if (entryNames.none { it == "$packRoot/manifest.json" }) {
                findings += Triple(
                    com.packforge.app.domain.model.ConflictSeverity.CRITICAL,
                    "MANIFEST_AUSENTE",
                    "Falta $packRoot/manifest.json dentro del .mcaddon: Minecraft no detectará el pack."
                )
            }
            // (3) pack_icon.png
            if (entryNames.none { it == "$packRoot/pack_icon.png" }) {
                findings += Triple(
                    com.packforge.app.domain.model.ConflictSeverity.HIGH,
                    "PORTADA_AUSENTE",
                    "Falta $packRoot/pack_icon.png: el pack se mostrará sin portada en el juego."
                )
            }
        }

        return findings
    }

    /** Ruta relativa normalizada para decidir si un archivo es basura interna. */
    private fun relForJunkCheck(sourceFolder: File, file: File): String =
        file.relativeTo(sourceFolder).path.replace('\\', '/')

    /** Umbral para declarar size+CRC en la cabecera local (evita data descriptor). */
    private const val ZIP_INLINE_MAX_BYTES = 32L * 1024 * 1024
    
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
            // ⭐ OPTIMIZACIÓN: Usar DirIndexCache en lugar de walkTopDown()
            val allFiles = DirIndexCache.index(bpDir).allFiles
            val scriptFiles = allFiles
                .filter { it.extension.lowercase() in SCRIPT_EXTENSIONS }
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



