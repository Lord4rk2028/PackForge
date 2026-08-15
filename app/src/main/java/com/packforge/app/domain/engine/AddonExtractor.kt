package com.packforge.app.domain.engine

import com.packforge.app.util.PackForgeLog
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

object AddonExtractor {
    private const val TAG = "PackForge_Extractor"

    // ── LÍMITES DE SEGURIDAD AL DESCOMPRIMIR ─────────────────────────
    /** Máximo de entradas (archivos/carpetas) permitidas en un ZIP. */
    private const val MAX_ZIP_ENTRIES = 1_000_000
    /** Tamaño máximo por archivo extraído (50 MB). */
    private const val MAX_ENTRY_SIZE = 50L * 1024 * 1024
    /** Tamaño total máximo acumulado de la extracción (200 MB). */
    private const val MAX_TOTAL_SIZE = 200L * 1024 * 1024

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

            // Determinación segura de la "zona" de extracción (una sola vez).
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var entryCount = 0
            var totalWritten = 0L

            FileInputStream(sourceFile).use { fis ->
                ZipInputStream(fis.buffered()).use { zis ->
                    var entry: ZipEntry? = zis.nextEntry
                    while (entry != null) {
                        // 1) Límite de nº de entradas: evita "zip bombs" con millones de entradas.
                        entryCount++
                        if (entryCount > MAX_ZIP_ENTRIES) {
                            throw IllegalStateException("ZIP excede el límite técnico del formato (1,000,000 entradas)")
                        }

                        // 2) VALIDACIÓN ZIP SLIP en TODAS las entradas (archivos y carpetas).
                        if (!isSecurePath(destDir, entry.name)) {
                            throw SecurityException("Zip Slip detected: ${entry.name}")
                        }

                        if (!entry.isDirectory) {
                            // 3) Límite por archivo según el tamaño declarado en la cabecera ZIP.
                            if (entry.size > MAX_ENTRY_SIZE) {
                                throw IllegalStateException("Archivo demasiado grande en ZIP: ${entry.name} (${entry.size} bytes)")
                            }

                            val newFile = File(destDir, entry.name)
                            newFile.parentFile?.mkdirs()

                            // Descompresión normal (con byte-accounting para los límites).
                            var fileWritten = 0L
                            FileOutputStream(newFile).use { fos ->
                                var read = zis.read(buffer)
                                while (read != -1) {
                                    fos.write(buffer, 0, read)
                                    fileWritten += read
                                    totalWritten += read

                                    // 4) Límite real por archivo (cubre entradas con size = -1).
                                    if (fileWritten > MAX_ENTRY_SIZE) {
                                        throw IllegalStateException("Archivo demasiado grande al descomprimir: ${entry.name}")
                                    }
                                    // 5) Límite total acumulado (200 MB).
                                    if (totalWritten > MAX_TOTAL_SIZE) {
                                        throw IllegalStateException("Tamaño total de extracción supera el límite (200 MB)")
                                    }
                                    read = zis.read(buffer)
                                }
                            }
                            PackForgeLog.d(TAG, "Extraído: ${entry.name} ($fileWritten bytes)")
                        } else {
                            // Entrada de directorio: también debe estar dentro de la zona segura.
                            File(destDir, entry.name).mkdirs()
                            PackForgeLog.d(TAG, "Directorio: ${entry.name}")
                        }
                        entry = zis.nextEntry
                    }
                }
            }

            PackForgeLog.d(TAG, "Extracción completada en: $destinationPath")
            return destinationPath

        } catch (e: SecurityException) {
            // Zip Slip detectado: se limpia lo parcialmente extraído y se NOTIFICA.
            cleanupExtractedFolder(destinationPath)
            PackForgeLog.e(TAG, "❌ ${e.message}", e)
            throw e
        } catch (e: IllegalStateException) {
            // Límite de seguridad excedido (entradas / tamaño): limpiar y notificar.
            cleanupExtractedFolder(destinationPath)
            PackForgeLog.e(TAG, "❌ Límite de seguridad excedido: ${e.message}", e)
            throw e
        } catch (e: Exception) {
            // Otro fallo (I/O, ZIP corrupto): limpiar parciales y fallar suave.
            cleanupExtractedFolder(destinationPath)
            PackForgeLog.e(TAG, "Error al extraer addon: ${e.message}", e)
            return null
        }
    }

    /**
     * Valida que una entrada del ZIP no escape del directorio de destino
     * (vulnerabilidad "Zip Slip"). Compara rutas CANONICALES para neutralizar
     * "../", rutas absolutas y separadores alternativos ("..\\evil").
     *
     * @return true si la entrada desemboca ESTRICTAMENTE dentro de destDir.
     */
    private fun isSecurePath(destDir: File, entryName: String): Boolean {
        return try {
            val destCanonical = destDir.canonicalPath
            val targetCanonical = File(destDir, entryName).canonicalPath
            targetCanonical.startsWith(destCanonical + File.separator)
        } catch (e: IOException) {
            false
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
                    // zip.delete() // ⚠️ ¡NO ELIMINAR EL ZIP! Necesitamos mantenerlo para Nivel 3.
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

    // ══════════════════════════════════════════════════════════════════
    // RESOLUCIÓN UNIVERSAL DE NOMBRE + TIPO (3 NIVELES)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Resultado de resolución de un addon: nombre visible + clasificación.
     */
    data class AddonInfo(
        val displayName: String,
        val classification: AddonClassification
    )

    /**
     * ⭐ Lee manifest.json desde un ZIP usando ZipFile (acceso aleatorio, rápido) ⭐
     * Busca "manifest.json" en la raíz o en cualquier subcarpeta del ZIP.
     * NO extrae nada al disco.
     */
    fun readManifestFromZip(zipFile: File): JSONObject? {
        return try {
            PackForgeLog.d("PackForge_Info", "Intentando leer manifest de ZIP: ${zipFile.absolutePath}")
            ZipFile(zipFile).use { zip ->
                // Priorizar manifest.json en la raíz
                val entry = zip.getEntry("manifest.json")
                    ?: zip.entries().asSequence().firstOrNull {
                        val isManifest = !it.isDirectory && it.name.replace("\\", "/")
                            .endsWith("manifest.json", ignoreCase = true)
                        if (isManifest) PackForgeLog.d("PackForge_Info", "  Manifest encontrado: ${it.name}")
                        isManifest
                    }
                
                if (entry == null) {
                    PackForgeLog.w("PackForge_Info", "  No se encontró manifest.json en ${zipFile.name}")
                }
                
                entry?.let { e ->
                    zip.getInputStream(e).bufferedReader(Charsets.UTF_8).use { reader ->
                        JSONObject(reader.readText())
                    }
                }
            }
        } catch (e: Exception) {
            PackForgeLog.w("PackForge_Info", "No se pudo leer manifest de ${zipFile.name}: ${e.message}")
            null
        }
    }

    /**
     * ⭐ Lee manifest.json desde bytes de un ZIP en memoria (streaming) ⭐
     * Para usar cuando ya tenemos los bytes en memoria (ej: al leer un .mcpack
     * desde un ZipInputStream dentro de un .mcaddon).
     */
    fun readManifestFromZipBytes(zipBytes: ByteArray): JSONObject? {
        return try {
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory &&
                        entry.name.replace("\\", "/")
                            .endsWith("manifest.json", ignoreCase = true)
                    ) {
                        return JSONObject(String(zis.readBytes(), Charsets.UTF_8))
                    }
                    entry = zis.nextEntry
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * ⭐ RESOLUCIÓN UNIVERSAL de nombre + tipo buscando en 3 niveles ⭐
     *
     * NIVEL 1: manifest.json en la raíz
     * NIVEL 2: manifest.json en subcarpetas (BP_xxx/, RP_xxx/)
     * NIVEL 3: manifest.json DENTRO de .mcpack/.zip anidados (leer SIN extraer)
     *
     * FALLBACK del nombre: NUNCA "desconocido" → usa el nombre del archivo original.
     */
    fun resolveAddonInfo(extractedDir: File, originalFile: File): AddonInfo {
        var name: String? = null

        // ── NIVEL 1: manifest.json en la raíz ──
        val rootManifest = File(extractedDir, "manifest.json")
        if (rootManifest.exists()) {
            try {
                val json = JSONObject(rootManifest.readText(Charsets.UTF_8))
                name = json.optJSONObject("header")?.optString("name")
                    ?.takeIf { it.isNotBlank() }
                PackForgeLog.d("PackForge_Info", "  Nivel 1 (raíz): name=$name")
            } catch (e: Exception) {
                PackForgeLog.w("PackForge_Info", "  Nivel 1: manifest inválido: ${e.message}")
            }
        }

        // ── NIVEL 2: manifest.json en subcarpetas (BP_xxx, RP_xxx) ──
        if (name.isNullOrBlank()) {
            extractedDir.listFiles()?.filter { it.isDirectory }?.forEach { sub ->
                if (name.isNullOrBlank()) {
                    val m = File(sub, "manifest.json")
                    if (m.exists()) {
                        try {
                            val json = JSONObject(m.readText(Charsets.UTF_8))
                            name = json.optJSONObject("header")?.optString("name")
                                ?.takeIf { it.isNotBlank() }
                            PackForgeLog.d("PackForge_Info", "  Nivel 2 (sub ${sub.name}): name=$name")
                        } catch (e: Exception) {
                            PackForgeLog.w("PackForge_Info", "  Nivel 2: manifest inválido en ${sub.name}: ${e.message}")
                        }
                    }
                }
            }
        }

        // ── NIVEL 3: manifest.json DENTRO de .mcpack/.zip anidados ──
        if (name.isNullOrBlank()) {
            extractedDir.walkTopDown()
                .filter {
                    it.isFile && (
                        it.extension.equals("mcpack", true) ||
                        it.extension.equals("zip", true)
                    )
                }
                .forEach { nested ->
                    if (name.isNullOrBlank()) {
                        val manifest = readManifestFromZip(nested)
                        name = manifest?.optJSONObject("header")?.optString("name")
                            ?.takeIf { it.isNotBlank() }
                        PackForgeLog.d("PackForge_Info", "  Nivel 3 (ZIP ${nested.name}): name=$name")
                    }
                }
        }

        // ── FALLBACK: NUNCA "desconocido" ──
        val finalName = if (name.isNullOrBlank()) {
            PackForgeLog.d("PackForge_Info", "  Fallback: usando nombre del archivo: ${originalFile.nameWithoutExtension}")
            originalFile.nameWithoutExtension
        } else {
            name
        }

        PackForgeLog.d("PackForge_Info", "📛 Addon: ${originalFile.name} → nombre resuelto: $finalName")

        return AddonInfo(finalName, classify(extractedDir))
    }
}