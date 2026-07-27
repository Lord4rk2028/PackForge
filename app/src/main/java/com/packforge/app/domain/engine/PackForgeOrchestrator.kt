package com.packforge.app.domain.engine

import com.packforge.app.util.PackForgeLog
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object PackForgeOrchestrator {
    private const val TAG = "PackForge_Orchestrator"

    /**
     * Resultado de la fusión de addons
     */
    data class MergeResult(
        val success: Boolean,
        val outputPath: String?,
        val bpUuid: String?,
        val rpUuid: String?,
        val totalJsonsMerged: Int,
        val errorMessage: String? = null
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
        customIconPath: String? = null
    ): MergeResult {
        val extractedDirs = mutableListOf<String>()
        val tempDir = File(outputDir, "temp_merge")
        
        // Clear previous conflicts
        JsonDeepMerger.clearConflicts()
        
        try {
            // a) EXTRAER TODOS LOS ADDONS
            progressCallback?.onProgress("Extrayendo addons...")
            PackForgeLog.d("PackForge_Debug", "=== INICIO FUSIÓN ===")
            PackForgeLog.d("PackForge_Debug", "Total de addons a procesar: ${addonPaths.size}")
            
            for ((index, addonPath) in addonPaths.withIndex()) {
                val addonFile = File(addonPath)
                if (!addonFile.exists()) {
                    PackForgeLog.e("PackForge_Debug", "Addon no existe: $addonPath")
                    continue
                }
                
                PackForgeLog.d("PackForge_Debug", "Procesando addon $index: $addonPath")
                
                val extractDir = File(tempDir, "extracted_${System.currentTimeMillis()}_${addonFile.nameWithoutExtension}")
                val extractedPath = AddonExtractor.extractAddon(addonPath, extractDir.absolutePath)
                
                if (extractedPath != null) {
                    extractedDirs.add(extractedPath)
                    PackForgeLog.d("PackForge_Debug", "Addon extraído: $addonPath -> $extractedPath")
                    
                    // Listar TODOS los archivos encontrados
                    val extractedDirFile = File(extractedPath)
                    val fileCount = extractedDirFile.walkTopDown().count { it.isFile }
                    PackForgeLog.d("PackForge_Debug", "Archivos extraídos: $fileCount")
                    
                    extractedDirFile.walkTopDown().forEach { file ->
                        if (file.isFile) {
                            PackForgeLog.d("PackForge_Debug", "  - ${file.relativeTo(extractedDirFile).path}")
                        }
                    }
                } else {
                    PackForgeLog.e("PackForge_Debug", "Error al extraer: $addonPath")
                }
            }
            
            if (extractedDirs.isEmpty()) {
                return MergeResult(false, null, null, null, 0, "No se pudo extraer ningún addon")
            }
            
            // b) CLASIFICAR ADDONS POR TIPO
            progressCallback?.onProgress("Clasificando addons...")
            PackForgeLog.d("PackForge_Debug", "Clasificando ${extractedDirs.size} addons extraídos")
            
            val bpDirs = mutableListOf<String>()
            val rpDirs = mutableListOf<String>()
            
            for (extractedDir in extractedDirs) {
                val analysis = AddonExtractor.analyzeExtractedAddon(extractedDir)
                PackForgeLog.d("PackForge_Debug", "Addon $extractedDir clasificado como: ${analysis.addonType}")
                PackForgeLog.d("PackForge_Debug", "  - Total JSONs: ${analysis.totalJsonFiles}")
                PackForgeLog.d("PackForge_Debug", "  - Manifest files: ${analysis.manifestFiles}")
                PackForgeLog.d("PackForge_Debug", "  - Item files: ${analysis.itemFiles.size}")
                PackForgeLog.d("PackForge_Debug", "  - Entity files: ${analysis.entityFiles.size}")
                PackForgeLog.d("PackForge_Debug", "  - Texture files: ${analysis.otherJsonFiles.size}")
                
                when (analysis.addonType) {
                    AddonExtractor.AddonType.BEHAVIOR_PACK -> {
                        bpDirs.add(extractedDir)
                        PackForgeLog.d("PackForge_Debug", "  -> Agregado a lista de BPs")
                    }
                    AddonExtractor.AddonType.RESOURCE_PACK -> {
                        rpDirs.add(extractedDir)
                        PackForgeLog.d("PackForge_Debug", "  -> Agregado a lista de RPs")
                    }
                    AddonExtractor.AddonType.BOTH -> {
                        PackForgeLog.d("PackForge_Debug", "  -> Addon tiene estructura BOTH, separando en BP y RP")
                        val (bpPath, rpPath) = separateBothAddon(extractedDir, tempDir)
                        if (bpPath != null) {
                            bpDirs.add(bpPath)
                            PackForgeLog.d("PackForge_Debug", "  -> BP separado en: $bpPath")
                        }
                        if (rpPath != null) {
                            rpDirs.add(rpPath)
                            PackForgeLog.d("PackForge_Debug", "  -> RP separado en: $rpPath")
                        }
                    }
                    AddonExtractor.AddonType.UNKNOWN -> {
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
            
            // c) FUSIONAR BEHAVIOR PACKS
            val mergedBpDir = File(tempDir, "merged_bp")
            val bpJsonCount = if (bpDirs.isNotEmpty()) {
                progressCallback?.onProgress("Fusionando Behavior Packs...")
                PackForgeLog.d("PackForge_Debug", "Iniciando fusión de ${bpDirs.size} Behavior Packs")
                mergePackType(bpDirs, mergedBpDir, "manifest.json")
            } else 0
            
            // d) FUSIONAR RESOURCE PACKS
            val mergedRpDir = File(tempDir, "merged_rp")
            val rpJsonCount = if (rpDirs.isNotEmpty()) {
                progressCallback?.onProgress("Fusionando Resource Packs...")
                PackForgeLog.d("PackForge_Debug", "Iniciando fusión de ${rpDirs.size} Resource Packs")
                mergePackType(rpDirs, mergedRpDir, "manifest.json")
            } else 0
            
            val totalJsonsMerged = bpJsonCount + rpJsonCount
            PackForgeLog.d("PackForge_Debug", "Total JSONs fusionados: $totalJsonsMerged")
            
            // Verificar archivos en directorios fusionados
            val bpFileCount = mergedBpDir.walkTopDown().count { it.isFile }
            val rpFileCount = mergedRpDir.walkTopDown().count { it.isFile }
            PackForgeLog.d("PackForge_Debug", "Archivos en BP fusionado: $bpFileCount")
            PackForgeLog.d("PackForge_Debug", "Archivos en RP fusionado: $rpFileCount")
            PackForgeLog.d("PackForge_Debug", "=== FIN FUSIÓN ===")
            
            // e) GENERAR MANIFIESTOS VINCULADOS
            progressCallback?.onProgress("Generando manifiestos...")
            val (bpUuid, rpUuid) = generateLinkedManifests(mergedBpDir, mergedRpDir, bpDirs.isNotEmpty(), rpDirs.isNotEmpty(), customName)
            
            PackForgeLog.d(TAG, "UUIDs generados - BP: $bpUuid, RP: $rpUuid")

            // f) COPIAR ICONO PERSONALIZADO (SI EXISTE)
            if (customIconPath != null && File(customIconPath).exists()) {
                val iconFile = File(customIconPath)
                if (bpDirs.isNotEmpty()) {
                    val targetBpIcon = File(mergedBpDir, "pack_icon.png")
                    iconFile.copyTo(targetBpIcon, overwrite = true)
                }
                if (rpDirs.isNotEmpty()) {
                    val targetRpIcon = File(mergedRpDir, "pack_icon.png")
                    iconFile.copyTo(targetRpIcon, overwrite = true)
                }
                PackForgeLog.d(TAG, "Icono personalizado copiado a los packs fusionados")
            }
            
            // g) EMPAQUETAR
            progressCallback?.onProgress("Empaquetando modpack...")
            val outputFile = File(outputDir, "$customName.mcpack")
            
            val packDirs = mutableListOf<File>()
            if (bpDirs.isNotEmpty()) packDirs.add(mergedBpDir)
            if (rpDirs.isNotEmpty()) packDirs.add(mergedRpDir)
            
            // CRÍTICO: Verificar antes de crear ZIP
            if (bpDirs.isNotEmpty() && rpDirs.isNotEmpty()) {
                verifyBeforeZip(mergedBpDir, mergedRpDir)
            }
            
            val success = createModpackZip(packDirs, outputFile, customName)
            
            if (!success) {
                return MergeResult(false, null, null, null, totalJsonsMerged, "Error al empaquetar modpack")
            }
            
            // h) LIMPIEZA
            progressCallback?.onProgress("Limpiando temporales...")
            cleanupTempDirs(tempDir)
            
            PackForgeLog.d(TAG, "Modpack creado exitosamente: ${outputFile.absolutePath}")
            
            return MergeResult(
                success = true,
                outputPath = outputFile.absolutePath,
                bpUuid = bpUuid,
                rpUuid = rpUuid,
                totalJsonsMerged = totalJsonsMerged
            )
            
        } catch (e: Exception) {
            PackForgeLog.e(TAG, "Error en fusión de addons: ${e.message}", e)
            cleanupTempDirs(tempDir)
            return MergeResult(false, null, null, null, 0, "Error: ${e.message}")
        }
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
            bpSubfolder.walkTopDown().forEach { file ->
                if (file.isFile) {
                    val relativePath = file.relativeTo(bpSubfolder).path
                    val targetFile = File(bpDir, relativePath)
                    targetFile.parentFile?.mkdirs()
                    file.copyTo(targetFile)
                    bpFileCount++
                    
                    // Log especial para manifest.json
                    if (file.name == "manifest.json") {
                        PackForgeLog.d("PackForge_Debug", "  ✅ manifest.json copiado a: ${targetFile.relativeTo(bpDir).path}")
                    }
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
            rpSubfolder.walkTopDown().forEach { file ->
                if (file.isFile) {
                    val relativePath = file.relativeTo(rpSubfolder).path
                    val targetFile = File(rpDir, relativePath)
                    targetFile.parentFile?.mkdirs()
                    file.copyTo(targetFile)
                    rpFileCount++
                    
                    // Log especial para manifest.json
                    if (file.name == "manifest.json") {
                        PackForgeLog.d("PackForge_Debug", "  ✅ manifest.json copiado a: ${targetFile.relativeTo(rpDir).path}")
                    }
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
     * Fusiona múltiples packs del mismo tipo (BP o RP)
     */
    private fun mergePackType(sourceDirs: List<String>, targetDir: File, manifestName: String): Int {
        var jsonCount = 0
        var nonJsonCount = 0
        
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        
        PackForgeLog.d("PackForge_Debug", "Iniciando mergePackType con ${sourceDirs.size} directorios fuente")
        PackForgeLog.d("PackForge_Debug", "Directorio destino: ${targetDir.absolutePath}")
        
        for ((sourceIndex, sourceDir) in sourceDirs.withIndex()) {
            val sourceFile = File(sourceDir)
            val sourceAddonName = sourceFile.name
            
            PackForgeLog.d("PackForge_Debug", "Procesando sourceDir $sourceIndex: $sourceDir")
            PackForgeLog.d("PackForge_Debug", "  Nombre del addon: $sourceAddonName")
            
            // Recorrer todos los archivos del source
            sourceFile.walk().forEach { file ->
                if (file.isFile) {
                    val relativePath = file.relativeTo(sourceFile).path
                    val targetFile = File(targetDir, relativePath)
                    targetFile.parentFile?.mkdirs()
                    
                    // Skip manifest.json (se generará nuevo al final)
                    if (file.name == manifestName) {
                        PackForgeLog.d("PackForge_Debug", "  ⏭️  Saltando manifest.json: $relativePath")
                        return@forEach
                    }
                    
                    if (file.name.endsWith(".json", ignoreCase = true)) {
                        // Archivo JSON
                        if (targetFile.exists()) {
                            // Fusionar con DeepMerge
                            PackForgeLog.d("PackForge_Debug", "  🔀 Fusionando (JSON): $relativePath")
                            PackForgeLog.d("PackForge_Debug", "    - ¿Ya existe en destino? true")
                            PackForgeLog.d("PackForge_Debug", "    - Acción: DEEPMERGE")
                            
                            try {
                                val existingContent = targetFile.readText()
                                val newContent = file.readText()
                                
                                // Set merge context for conflict tracking
                                JsonDeepMerger.setMergeContext(
                                    sourceAddon = sourceAddonName,
                                    targetAddon = targetDir.name,
                                    filePath = relativePath
                                )
                                
                                val merged = JsonDeepMerger.deepMergeStrings(existingContent, newContent)
                                targetFile.writeText(merged)
                                
                                jsonCount++
                                PackForgeLog.d("PackForge_Debug", "    ✅ Fusionado exitosamente")
                            } catch (e: Exception) {
                                PackForgeLog.e("PackForge_Debug", "    ❌ Error al fusionar $relativePath: ${e.message}")
                                // En caso de error, sobrescribir
                                file.copyTo(targetFile, overwrite = true)
                                jsonCount++
                            }
                        } else {
                            // Copiar directamente
                            PackForgeLog.d("PackForge_Debug", "  ✅ Copiando (JSON nuevo): $relativePath")
                            PackForgeLog.d("PackForge_Debug", "    - ¿Ya existe en destino? false")
                            PackForgeLog.d("PackForge_Debug", "    - Acción: COPIAR")
                            file.copyTo(targetFile)
                            jsonCount++
                        }
                    } else {
                        // Archivo no-JSON (texturas, sonidos, modelos, etc.)
                        PackForgeLog.d("PackForge_Debug", "  📄 Procesando (no-JSON): $relativePath")
                        
                        if (targetFile.exists()) {
                            // Colisión de archivos no-JSON
                            PackForgeLog.w("PackForge_Debug", "    ⚠️  Colisión (no-JSON): $relativePath")
                            PackForgeLog.w("PackForge_Debug", "    - ¿Ya existe en destino? true")
                            PackForgeLog.w("PackForge_Debug", "    - Acción: SOBRESCRIBIR (manteniendo nuevo)")
                            file.copyTo(targetFile, overwrite = true)
                        } else {
                            PackForgeLog.d("PackForge_Debug", "    ✅ Copiando (no-JSON nuevo): $relativePath")
                            PackForgeLog.d("PackForge_Debug", "    - ¿Ya existe en destino? false")
                            PackForgeLog.d("PackForge_Debug", "    - Acción: COPIAR")
                            file.copyTo(targetFile)
                        }
                        nonJsonCount++
                    }
                }
            }
            
            PackForgeLog.d("PackForge_Debug", "  Finalizado procesamiento de $sourceAddonName")
            PackForgeLog.d("PackForge_Debug", "    - JSONs procesados en este addon: ${sourceFile.walk().count { it.isFile && it.name.endsWith(".json", ignoreCase = true) }}")
            PackForgeLog.d("PackForge_Debug", "    - No-JSONs procesados en este addon: ${sourceFile.walk().count { it.isFile && !it.name.endsWith(".json", ignoreCase = true) }}")
        }
        
        PackForgeLog.d("PackForge_Debug", "=== Resumen mergePackType ===")
        PackForgeLog.d("PackForge_Debug", "Total JSONs fusionados: $jsonCount")
        PackForgeLog.d("PackForge_Debug", "Total no-JSONs copiados: $nonJsonCount")
        PackForgeLog.d("PackForge_Debug", "Total archivos en destino: ${targetDir.walkTopDown().count { it.isFile }}")
        
        return jsonCount
    }
    
    /**
     * Genera manifiestos vinculados para BP y RP
     */
    private fun generateLinkedManifests(
        bpDir: File,
        rpDir: File,
        hasBp: Boolean,
        hasRp: Boolean,
        customName: String = "PackForge Modpack"
    ): Pair<String?, String?> {
        var bpUuid: String? = null
        var rpUuid: String? = null
        
        if (hasBp && hasRp) {
            // Generar manifiestos vinculados
            val (bpManifest, rpManifest) = ManifestGenerator.generateLinkedManifests(customName)
            
            // Escribir usando UTF-8 sin BOM
            ManifestGenerator.writeManifestToFile(bpManifest, File(bpDir, "manifest.json"))
            ManifestGenerator.writeManifestToFile(rpManifest, File(rpDir, "manifest.json"))
            
            PackForgeLog.d("PackForge_Debug", "Manifests escritos con UTF-8 sin BOM")
            
            // Extraer UUIDs de los manifiestos generados
            bpUuid = extractUuidFromManifest(bpManifest)
            rpUuid = extractUuidFromManifest(rpManifest)
            
        } else if (hasBp) {
            // Solo BP
            val bpManifest = ManifestGenerator.generateBehaviorPackManifest(customName)
            ManifestGenerator.writeManifestToFile(bpManifest, File(bpDir, "manifest.json"))
            bpUuid = extractUuidFromManifest(bpManifest)
            
        } else if (hasRp) {
            // Solo RP
            val rpManifest = ManifestGenerator.generateResourcePackManifest(customName)
            ManifestGenerator.writeManifestToFile(rpManifest, File(rpDir, "manifest.json"))
            rpUuid = extractUuidFromManifest(rpManifest)
        }
        
        return Pair(bpUuid, rpUuid)
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
     * - behavior_packs/ (sin carpeta intermedia)
     * - resource_packs/ (sin carpeta intermedia)
     */
    private fun createModpackZip(sourceDirs: List<File>, outputFile: File, packName: String): Boolean {
        return try {
            ZipOutputStream(FileOutputStream(outputFile)).use { zos ->
                for (sourceDir in sourceDirs) {
                    val folderName = sourceDir.name
                    
                    // Determinar si es BP o RP basado en el nombre
                    val packType = when {
                        folderName.contains("merged_bp", ignoreCase = true) || 
                        folderName.contains("separated_bp", ignoreCase = true) -> "behavior_packs"
                        folderName.contains("merged_rp", ignoreCase = true) || 
                        folderName.contains("separated_rp", ignoreCase = true) -> "resource_packs"
                        folderName.contains("bp", ignoreCase = true) -> "behavior_packs"
                        folderName.contains("rp", ignoreCase = true) -> "resource_packs"
                        else -> {
                            // Intentar detectar por contenido
                            if (File(sourceDir, "entities").exists() || File(sourceDir, "items").exists()) {
                                "behavior_packs"
                            } else {
                                "resource_packs"
                            }
                        }
                    }
                    
                    PackForgeLog.d(TAG, "Empaquetando: $sourceDir -> $packType/")
                    PackForgeLog.d("PackForge_Debug", "Verificando estructura de ZIP para: $sourceDir")
                    
                    // Verificar que manifest.json esté en la raíz del directorio
                    val manifestFile = File(sourceDir, "manifest.json")
                    if (manifestFile.exists()) {
                        PackForgeLog.d("PackForge_Debug", "✅ manifest.json encontrado en raíz: ${manifestFile.absolutePath}")
                    } else {
                        PackForgeLog.e("PackForge_Debug", "❌ ERROR: manifest.json NO encontrado en raíz de $sourceDir")
                        // Listar archivos para debug
                        sourceDir.walkTopDown().filter { it.isFile }.forEach { file ->
                            PackForgeLog.d("PackForge_Debug", "  Archivo en directorio: ${file.relativeTo(sourceDir).path}")
                        }
                    }
                    
                    // Agregar directorio recursivamente SIN carpeta intermedia
                    addDirectoryToZip(zos, sourceDir, packType)
                }
                
                // CRÍTICO: Finalizar ZIP correctamente
                zos.finish()
                zos.flush()
            }
            
            // Verificar que el archivo tenga contenido
            val fileSize = outputFile.length()
            PackForgeLog.d(TAG, "ZIP creado: ${outputFile.absolutePath}, tamaño: $fileSize bytes")
            
            if (fileSize == 0L) {
                PackForgeLog.e(TAG, "ERROR: El ZIP creado tiene 0 bytes")
                return false
            }
            
            true
        } catch (e: Exception) {
            PackForgeLog.e(TAG, "Error al crear ZIP: ${e.message}", e)
            false
        }
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
