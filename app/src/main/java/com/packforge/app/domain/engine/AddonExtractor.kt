package com.packforge.app.domain.engine

import com.packforge.app.util.PackForgeLog
import org.json.JSONObject
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
        /** Varios packs anidados (.mcpack/.zip dentro del addon). Cada uno se clasifica por separado. */
        data class MULTI(
            val packs: List<File>
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

        // Detectar tipo de addon clasificando por CONTENIDO del manifest (no por nombre).
        val addonClassification = classify(extractedDir)
        val addonType = when (addonClassification) {
            is AddonClassification.BEHAVIOR_PACK -> AddonType.BEHAVIOR_PACK
            is AddonClassification.RESOURCE_PACK -> AddonType.RESOURCE_PACK
            is AddonClassification.BOTH,
            is AddonClassification.MULTI -> AddonType.BOTH
            is AddonClassification.UNKNOWN -> AddonType.UNKNOWN
        }

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
     * CLASIFICACIÓN UNIVERSAL por CONTENIDO del manifest.json (no por nombre de carpeta).
     *
     * 1. manifest.json en la raíz → tipo según modules[].type (JEI)
     * 2. ZIPs anidados (.mcpack/.zip) → se extraen a subcarpetas (More Tools) y se devuelven como MULTI
     * 3. Subcarpetas con manifest.json → cada una se clasifica por contenido (Corecraft, The Lost Mobs)
     */
    fun classify(dir: File): AddonClassification {
        // CASO 1: manifest.json en la raíz → clasificar por contenido
        val rootManifest = File(dir, "manifest.json")
        if (rootManifest.exists()) {
            return classifyByManifestContent(rootManifest)
        }

        // CASO 2: ZIPs anidados (.mcpack o .zip dentro) → extraer y clasificar cada uno
        val nestedZips = dir.walkTopDown()
            .filter { it.isFile && (it.extension.equals("mcpack", true) || it.extension.equals("zip", true)) }
            .toList()

        if (nestedZips.isNotEmpty()) {
            PackForgeLog.d("PackForge_Classify", "📦 ZIPs anidados detectados: ${nestedZips.size}")
            val packs = mutableListOf<File>()
            nestedZips.forEach { zip ->
                val packDir = File(dir, "nested_${zip.nameWithoutExtension}")
                packDir.mkdirs()
                val extracted = extractAddon(zip.absolutePath, packDir.absolutePath)
                if (extracted != null) {
                    zip.delete()
                    packs.add(packDir)
                    PackForgeLog.d("PackForge_Classify", "  ✅ Extraído: ${zip.name}")
                }
            }
            return AddonClassification.MULTI(packs)
        }

        // CASO 3: subcarpetas con manifest.json (Corecraft, Lost Mobs) → clasificar CADA UNA por contenido
        val subManifests = dir.listFiles()
            ?.filter { it.isDirectory && File(it, "manifest.json").exists() }
            ?: emptyList()

        if (subManifests.size >= 2) {
            var bpFolder: File? = null
            var rpFolder: File? = null

            subManifests.forEach { folder ->
                when (classifyByManifestContent(File(folder, "manifest.json"))) {
                    is AddonClassification.BEHAVIOR_PACK -> bpFolder = folder
                    is AddonClassification.RESOURCE_PACK -> rpFolder = folder
                    else -> {}
                }
            }

            if (bpFolder != null && rpFolder != null) {
                PackForgeLog.d("PackForge_Classify", "🔀 BOTH detectado por contenido:")
                PackForgeLog.d("PackForge_Classify", "   BP: ${bpFolder.name}")
                PackForgeLog.d("PackForge_Classify", "   RP: ${rpFolder.name}")
                return AddonClassification.BOTH(bpFolder, rpFolder)
            }
        }

        if (subManifests.size == 1) {
            return classifyByManifestContent(File(subManifests[0], "manifest.json"))
        }

        PackForgeLog.w("PackForge_Classify", "⚠️ Sin clasificación: ${dir.name}")
        return AddonClassification.UNKNOWN
    }

    /**
     * ⭐ LA CLAVE: clasificar por CONTENIDO del manifest, no por nombre ⭐
     * Lee modules[].type: "data" → BEHAVIOR_PACK, "resources"/"resource" → RESOURCE_PACK.
     * Funciona con CUALQUIER nombre de carpeta y con TODAS las versiones del manifest
     * (format_version 0/1 usa "resource" en singular; format_version 2/3 usa "resources").
     */
    fun classifyByManifestContent(manifestFile: File): AddonClassification {
        return try {
            val json = JSONObject(manifestFile.readText(Charsets.UTF_8))
            val modules = json.optJSONArray("modules") ?: return AddonClassification.UNKNOWN
            for (i in 0 until modules.length()) {
                when (modules.getJSONObject(i).optString("type")) {
                    "data" -> {
                        PackForgeLog.d("PackForge_Classify", "🔵 BP por contenido: ${manifestFile.parentFile?.name}")
                        return AddonClassification.BEHAVIOR_PACK
                    }
                    "resources", "resource" -> {
                        PackForgeLog.d("PackForge_Classify", "🟢 RP por contenido: ${manifestFile.parentFile?.name}")
                        return AddonClassification.RESOURCE_PACK
                    }
                }
            }
            AddonClassification.UNKNOWN
        } catch (e: Exception) {
            PackForgeLog.e("PackForge_Classify", "❌ Error leyendo manifest: ${e.message}")
            AddonClassification.UNKNOWN
        }
    }

    /**
     * Raíz real de un pack anidado: la carpeta que contiene manifest.json.
     * Si el manifest está en la raíz de `dir`, se devuelve `dir` mismo.
     */
    fun resolvePackRoot(dir: File): File {
        val manifest = dir.walkTopDown()
            .firstOrNull { it.isFile && it.name.equals("manifest.json", ignoreCase = true) }
        return manifest?.parentFile ?: dir
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