package com.packforge.app.domain.engine

import com.packforge.app.util.PackForgeLog
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object AddonExtractor {
    private const val TAG = "PackForge_Extractor"

    /**
     * Resultado del análisis de un addon extraído
     */
    data class AddonAnalysis(
        val addonClassification: AddonClassification,
        val addonType: AddonType, // Mantener para compatibilidad
        val totalJsonFiles: Int,
        val manifestFiles: List<String>,
        val itemFiles: List<String>,
        val entityFiles: List<String>,
        val lootFiles: List<String>,
        val recipeFiles: List<String>,
        val otherJsonFiles: List<String>
    )

    /**
     * Clasificación detallada de un addon con rutas de subcarpetas si es BOTH
     */
    sealed class AddonClassification {
        object BEHAVIOR_PACK : AddonClassification()
        object RESOURCE_PACK : AddonClassification()
        data class BOTH(
            val bpSubfolder: File,
            val rpSubfolder: File
        ) : AddonClassification()
        object UNKNOWN : AddonClassification()
    }
    
    /**
     * Tipo de addon detectado (legacy, mantener para compatibilidad)
     */
    enum class AddonType {
        BEHAVIOR_PACK,
        RESOURCE_PACK,
        BOTH,
        UNKNOWN
    }

    /**
     * Extrae el contenido de un archivo .mcaddon o .mcpack a una carpeta de destino
     * 
     * @param sourcePath Ruta del archivo .mcaddon/.mcpack
     * @param destinationPath Ruta de la carpeta donde se extraerán los archivos
     * @return Ruta de la carpeta extraída, o null si hubo error
     */
    fun extractAddon(sourcePath: String, destinationPath: String): String? {
        try {
            val sourceFile = File(sourcePath)
            if (!sourceFile.exists()) {
                PackForgeLog.e(TAG, "Archivo fuente no existe: $sourcePath")
                return null
            }

            val destDir = File(destinationPath)
            if (!destDir.exists()) {
                destDir.mkdirs()
            }

            val fis = FileInputStream(sourceFile)
            val zis = ZipInputStream(fis.buffered())
            
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val newFile = File(destDir, entry.name)
                    
                    // Crear directorios padre si no existen
                    newFile.parentFile?.mkdirs()
                    
                    // Extraer archivo
                    val fos = FileOutputStream(newFile)
                    zis.copyTo(fos)
                    fos.close()
                    
                    PackForgeLog.d(TAG, "Extraído: ${entry.name}")
                }
                entry = zis.nextEntry
            }
            
            zis.closeEntry()
            zis.close()
            fis.close()
            
            PackForgeLog.d(TAG, "Extracción completada en: $destinationPath")
            return destinationPath
            
        } catch (e: Exception) {
            PackForgeLog.e(TAG, "Error al extraer addon: ${e.message}", e)
            return null
        }
    }

    /**
     * Analiza una carpeta extraída y clasifica todos los archivos JSON
     * 
     * @param extractedPath Ruta de la carpeta extraída
     * @return AddonAnalysis con la clasificación de archivos
     */
    fun analyzeExtractedAddon(extractedPath: String): AddonAnalysis {
        val extractedDir = File(extractedPath)
        if (!extractedDir.exists()) {
            PackForgeLog.e(TAG, "Carpeta extraída no existe: $extractedPath")
            return AddonAnalysis(
                AddonClassification.UNKNOWN, AddonType.UNKNOWN, 0, emptyList(), emptyList(),
                emptyList(), emptyList(), emptyList(), emptyList()
            )
        }

        val manifestFiles = mutableListOf<String>()
        val itemFiles = mutableListOf<String>()
        val entityFiles = mutableListOf<String>()
        val lootFiles = mutableListOf<String>()
        val recipeFiles = mutableListOf<String>()
        val otherJsonFiles = mutableListOf<String>()

        // Buscar recursivamente todos los archivos JSON
        findJsonFiles(extractedDir, "", manifestFiles, itemFiles, entityFiles, lootFiles, recipeFiles, otherJsonFiles)

        // Detectar tipo de addon
        val (addonClassification, addonType) = detectAddonType(manifestFiles, extractedDir)

        val totalJsonFiles = manifestFiles.size + itemFiles.size + entityFiles.size + 
                           lootFiles.size + recipeFiles.size + otherJsonFiles.size

        PackForgeLog.d(TAG, "Análisis completado - Tipo: $addonType, Total JSONs: $totalJsonFiles")

        // REGISTRAR: estructuras raras detectadas (para que aparezcan en Conflictos)
        if (addonClassification is AddonClassification.UNKNOWN) {
            ConflictRegistry.logConflict(
                severity = com.packforge.app.domain.model.ConflictSeverity.WARNING,
                type = "UNKNOWN_STRUCTURE",
                file = extractedDir.name,
                addon1 = extractedDir.name,
                addon2 = "",
                description = "Estructura de addon no reconocida. Puede que algunos " +
                    "archivos no se fusionen correctamente."
            )
        }

        return AddonAnalysis(
            addonClassification = addonClassification,
            addonType = addonType,
            totalJsonFiles = totalJsonFiles,
            manifestFiles = manifestFiles,
            itemFiles = itemFiles,
            entityFiles = entityFiles,
            lootFiles = lootFiles,
            recipeFiles = recipeFiles,
            otherJsonFiles = otherJsonFiles
        )
    }

    /**
     * Busca recursivamente archivos JSON y los clasifica
     */
    private fun findJsonFiles(
        dir: File,
        basePath: String,
        manifestFiles: MutableList<String>,
        itemFiles: MutableList<String>,
        entityFiles: MutableList<String>,
        lootFiles: MutableList<String>,
        recipeFiles: MutableList<String>,
        otherJsonFiles: MutableList<String>
    ) {
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                val newPath = if (basePath.isEmpty()) file.name else "$basePath/${file.name}"
                findJsonFiles(file, newPath, manifestFiles, itemFiles, entityFiles, lootFiles, recipeFiles, otherJsonFiles)
            } else if (file.name.endsWith(".json", ignoreCase = true)) {
                val fullPath = if (basePath.isEmpty()) file.name else "$basePath/${file.name}"
                
                when {
                    file.name == "manifest.json" -> {
                        manifestFiles.add(fullPath)
                    }
                    file.name == "blocks.json" || 
                    basePath.contains("items", ignoreCase = true) ||
                    basePath.contains("item", ignoreCase = true) -> {
                        itemFiles.add(fullPath)
                    }
                    basePath.contains("entities", ignoreCase = true) -> {
                        entityFiles.add(fullPath)
                    }
                    basePath.contains("loot_tables", ignoreCase = true) -> {
                        lootFiles.add(fullPath)
                    }
                    basePath.contains("recipes", ignoreCase = true) -> {
                        recipeFiles.add(fullPath)
                    }
                    else -> {
                        otherJsonFiles.add(fullPath)
                    }
                }
            }
        }
    }

    /**
     * Detecta el tipo de addon basándose en la estructura de archivos
     * Retorna AddonClassification con rutas de subcarpetas si es BOTH
     */
    private fun detectAddonType(manifestFiles: List<String>, extractedDir: File): Pair<AddonClassification, AddonType> {
        PackForgeLog.d("PackForge_Classify", "=== INICIO DETECCIÓN DE TIPO ===")
        PackForgeLog.d("PackForge_Classify", "Manifest files encontrados: ${manifestFiles.map { it }}")
        
        // Buscar manifest.json en diferentes ubicaciones
        val hasRootManifest = manifestFiles.any { it == "manifest.json" }
        val hasBehaviorPack = extractedDir.walk().any { 
            it.isDirectory && it.name.equals("behavior_packs", ignoreCase = true) 
        }
        val hasResourcePack = extractedDir.walk().any { 
            it.isDirectory && it.name.equals("resource_packs", ignoreCase = true) 
        }
        
        // Detectar carpetas BP_* y RP_* (como BP_CCR_CCR, RP_CCR_CCR)
        val hasBpSubfolder = extractedDir.walk().any { 
            it.isDirectory && it.name.startsWith("BP_", ignoreCase = true) 
        }
        val hasRpSubfolder = extractedDir.walk().any { 
            it.isDirectory && it.name.startsWith("RP_", ignoreCase = true) 
        }
        
        // Verificar manifests en subcarpetas BP_*/RP_* (CRÍTICO para Corecraft)
        val hasBpManifest = manifestFiles.any { manifestPath ->
            val lowerPath = manifestPath.lowercase()
            (lowerPath.startsWith("bp_") || lowerPath.contains("/bp_") || lowerPath.contains("\\bp_")) && 
            lowerPath.contains("manifest.json")
        }
        val hasRpManifest = manifestFiles.any { manifestPath ->
            val lowerPath = manifestPath.lowercase()
            (lowerPath.startsWith("rp_") || lowerPath.contains("/rp_") || lowerPath.contains("\\rp_")) && 
            lowerPath.contains("manifest.json")
        }
        
        PackForgeLog.d("PackForge_Classify", "hasRootManifest: $hasRootManifest")
        PackForgeLog.d("PackForge_Classify", "hasBehaviorPack: $hasBehaviorPack")
        PackForgeLog.d("PackForge_Classify", "hasResourcePack: $hasResourcePack")
        PackForgeLog.d("PackForge_Classify", "hasBpSubfolder: $hasBpSubfolder")
        PackForgeLog.d("PackForge_Classify", "hasRpSubfolder: $hasRpSubfolder")
        PackForgeLog.d("PackForge_Classify", "hasBpManifest: $hasBpManifest")
        PackForgeLog.d("PackForge_Classify", "hasRpManifest: $hasRpManifest")

        // CRÍTICO: Priorizar detección BOTH con subcarpetas BP_*/RP_* (caso Corecraft)
        // Este es el caso más común para addons que tienen ambos packs
        if (hasBpManifest && hasRpManifest) {
            // Encontrar las rutas específicas de los manifests
            val bpManifestPath = manifestFiles.find { manifestPath ->
                val lowerPath = manifestPath.lowercase()
                (lowerPath.startsWith("bp_") || lowerPath.contains("/bp_") || lowerPath.contains("\\bp_")) && 
                lowerPath.contains("manifest.json")
            }
            val rpManifestPath = manifestFiles.find { manifestPath ->
                val lowerPath = manifestPath.lowercase()
                (lowerPath.startsWith("rp_") || lowerPath.contains("/rp_") || lowerPath.contains("\\rp_")) && 
                lowerPath.contains("manifest.json")
            }
            
            // Encontrar las carpetas padre de los manifests (las subcarpetas BP_* y RP_*)
            val bpSubfolder = bpManifestPath?.let { path ->
                val parts = path.split("/", "\\")
                if (parts.isNotEmpty()) {
                    val folderName = parts[0]
                    extractedDir.listFiles()?.find { it.name == folderName }
                } else null
            }
            val rpSubfolder = rpManifestPath?.let { path ->
                val parts = path.split("/", "\\")
                if (parts.isNotEmpty()) {
                    val folderName = parts[0]
                    extractedDir.listFiles()?.find { it.name == folderName }
                } else null
            }
            
            if (bpSubfolder != null && rpSubfolder != null) {
                PackForgeLog.d("PackForge_Classify", "✅ BOTH detectado:")
                PackForgeLog.d("PackForge_Classify", "   BP subfolder: ${bpSubfolder.name}")
                PackForgeLog.d("PackForge_Classify", "   RP subfolder: ${rpSubfolder.name}")
                return Pair(AddonClassification.BOTH(bpSubfolder, rpSubfolder), AddonType.BOTH)
            }
            
            PackForgeLog.d("PackForge_Classify", "✅ Clasificado como BOTH - BP: $bpManifestPath, RP: $rpManifestPath")
            // Fallback: BOTH sin subcarpetas específicas (se usará separateBothAddon original)
            return Pair(AddonClassification.BOTH(extractedDir, extractedDir), AddonType.BOTH)
        }
        
        // Analizar el contenido del manifest.json si existe en raíz
        if (hasRootManifest) {
            val manifestFile = File(extractedDir, "manifest.json")
            if (manifestFile.exists()) {
                try {
                    val manifestContent = manifestFile.readText()
                    val isData = manifestContent.contains("\"type\"", ignoreCase = true) && 
                                 manifestContent.contains("\"data\"", ignoreCase = true)
                    val isResources = manifestContent.contains("\"type\"", ignoreCase = true) && 
                                      manifestContent.contains("\"resources\"", ignoreCase = true)
                    
                    PackForgeLog.d("PackForge_Classify", "Manifest en raíz - isData: $isData, isResources: $isResources")
                    
                    return when {
                        isData && isResources -> {
                            PackForgeLog.d("PackForge_Classify", "Clasificado como: BOTH (manifest raíz)")
                            Pair(AddonClassification.BOTH(extractedDir, extractedDir), AddonType.BOTH)
                        }
                        isData -> {
                            PackForgeLog.d("PackForge_Classify", "Clasificado como: BEHAVIOR_PACK (manifest raíz)")
                            Pair(AddonClassification.BEHAVIOR_PACK, AddonType.BEHAVIOR_PACK)
                        }
                        isResources -> {
                            PackForgeLog.d("PackForge_Classify", "Clasificado como: RESOURCE_PACK (manifest raíz)")
                            Pair(AddonClassification.RESOURCE_PACK, AddonType.RESOURCE_PACK)
                        }
                        else -> {
                            PackForgeLog.d("PackForge_Classify", "Clasificado como: UNKNOWN (manifest raíz sin tipo reconocido)")
                            Pair(AddonClassification.UNKNOWN, AddonType.UNKNOWN)
                        }
                    }
                } catch (e: Exception) {
                    PackForgeLog.e("PackForge_Classify", "Error al leer manifest.json: ${e.message}")
                }
            }
        }
        
        // Detectar BP solo en subcarpeta BP_*
        if (hasBpManifest && !hasRpManifest) {
            PackForgeLog.d("PackForge_Classify", "Clasificado como: BEHAVIOR_PACK (manifest en subcarpeta BP_*)")
            return Pair(AddonClassification.BEHAVIOR_PACK, AddonType.BEHAVIOR_PACK)
        }
        
        // Detectar RP solo en subcarpeta RP_*
        if (hasRpManifest && !hasBpManifest) {
            PackForgeLog.d("PackForge_Classify", "Clasificado como: RESOURCE_PACK (manifest en subcarpeta RP_*)")
            return Pair(AddonClassification.RESOURCE_PACK, AddonType.RESOURCE_PACK)
        }

        // Fallback basado en estructura de carpetas
        val result = when {
            hasBehaviorPack && hasResourcePack -> {
                PackForgeLog.d("PackForge_Classify", "Clasificado como: BOTH (carpetas behavior_packs + resource_packs)")
                Pair(AddonClassification.BOTH(extractedDir, extractedDir), AddonType.BOTH)
            }
            hasBehaviorPack -> {
                PackForgeLog.d("PackForge_Classify", "Clasificado como: BEHAVIOR_PACK (carpeta behavior_packs)")
                Pair(AddonClassification.BEHAVIOR_PACK, AddonType.BEHAVIOR_PACK)
            }
            hasResourcePack -> {
                PackForgeLog.d("PackForge_Classify", "Clasificado como: RESOURCE_PACK (carpeta resource_packs)")
                Pair(AddonClassification.RESOURCE_PACK, AddonType.RESOURCE_PACK)
            }
            hasBpSubfolder && hasRpSubfolder -> {
                PackForgeLog.d("PackForge_Classify", "Clasificado como: BOTH (subcarpetas BP_* + RP_*)")
                Pair(AddonClassification.BOTH(extractedDir, extractedDir), AddonType.BOTH)
            }
            hasBpSubfolder -> {
                PackForgeLog.d("PackForge_Classify", "Clasificado como: BEHAVIOR_PACK (subcarpeta BP_*)")
                Pair(AddonClassification.BEHAVIOR_PACK, AddonType.BEHAVIOR_PACK)
            }
            hasRpSubfolder -> {
                PackForgeLog.d("PackForge_Classify", "Clasificado como: RESOURCE_PACK (subcarpeta RP_*)")
                Pair(AddonClassification.RESOURCE_PACK, AddonType.RESOURCE_PACK)
            }
            else -> {
                PackForgeLog.w("PackForge_Classify", "Clasificado como: UNKNOWN (estructura no reconocida)")
                Pair(AddonClassification.UNKNOWN, AddonType.UNKNOWN)
            }
        }
        
        PackForgeLog.d("PackForge_Classify", "=== FIN DETECCIÓN DE TIPO ===")
        return result
    }

    /**
     * Limpia una carpeta extraída (borra todos los archivos)
     */
    fun cleanupExtractedFolder(path: String): Boolean {
        return try {
            val dir = File(path)
            if (dir.exists()) {
                dir.deleteRecursively()
                PackForgeLog.d(TAG, "Carpeta limpiada: $path")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            PackForgeLog.e(TAG, "Error al limpiar carpeta: ${e.message}", e)
            false
        }
    }
}
