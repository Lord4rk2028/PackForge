package com.packforge.app.domain.engine

import com.packforge.app.util.PackForgeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile

/**
 * FASE 2: DETECCIÓN DE HUECOS (HOLE DETECTION) - Lazy & Selectiva + Paralelizada.
 * Escanea únicamente los archivos críticos ya copiados en el directorio de salida,
 * parsea sus referencias y rellena los huecos buscando directamente en los ZIPs originales.
 *
 * ⭐ DUCK TYPING: Clasifica archivos de entidad por contenido raíz
 * "minecraft:client_entity", NO por extensión .entity.json. Esto permite
 * procesar correctamente archivos con nombres ofuscados (ej: .fT.json).
 *
 * ⭐ CONCURRENCIA ESTRUCTURADA: Procesa cada archivo de entidad en paralelo
 * usando supervisorScope + async, limitado por Dispatchers.IO.
 */
object LazyDependencyResolver {
    private const val TAG = "PackForge_LazyResolver"

    data class RepairResult(
        val fixedReferences: Int,
        val missingReferences: List<String>
    )

    /** Entrada del índice de ZIPs: archivo dentro de un ZIP específico. */
    private data class ZipEntryRef(
        val zipPath: String,
        val entryName: String
    )

    /** Índice unificado: nombre de archivo (case-insensitive) → lista de ubicaciones en ZIPs. */
    private class ZipIndex {
        private val index = HashMap<String, MutableList<ZipEntryRef>>()

        fun add(zipPath: String, entryName: String) {
            val key = entryName.lowercase()
            index.getOrPut(key) { mutableListOf() }.add(ZipEntryRef(zipPath, entryName))
        }

        fun find(fileName: String): ZipEntryRef? {
            val key = fileName.lowercase()
            return index[key]?.firstOrNull()
        }

        fun size(): Int = index.size
    }

    /**
     * Construye un índice unificado de todos los ZIPs originales una sola vez.
     * Mapea nombreDeArchivo → ubicación en el ZIP para búsquedas O(1).
     */
    private fun buildZipIndex(addonPaths: List<String>): ZipIndex {
        val index = ZipIndex()
        for (addonPath in addonPaths) {
            try {
                ZipFile(addonPath).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (!entry.isDirectory) {
                            val name = entry.name
                            if (name.endsWith(".geo.json", ignoreCase = true) ||
                                name.endsWith(".animation.json", ignoreCase = true) ||
                                name.endsWith(".json", ignoreCase = true)) {
                                index.add(addonPath, name)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                PackForgeLog.w(TAG, "Error indexando ZIP $addonPath: ${e.message}")
            }
        }
        PackForgeLog.d(TAG, "Índice de ZIPs construido: ${index.size()} entradas únicas de ${addonPaths.size} addons")
        return index
    }

    /**
     * Resuelve y repara dependencias faltantes en paralelo.
     *
     * @param outputDir Directorio de salida fusionado
     * @param originalAddonPaths Rutas a los .mcaddon/.zip originales
     * @return RepairResult con conteo de reparados y lista de faltantes
     */
    suspend fun resolveAndRepair(
        outputDir: File,
        originalAddonPaths: List<String>
    ): RepairResult = withContext(Dispatchers.IO) {
        PackForgeLog.d(TAG, "🔍 Iniciando detección de huecos (Lazy Hole Detection, paralelo)...")

        // ⭐ OPTIMIZACIÓN FASE 2: Construir índice de ZIPs UNA sola vez
        val zipIndex = buildZipIndex(originalAddonPaths)

        val entityDir = File(outputDir, "entity")
        if (!entityDir.exists() || !entityDir.isDirectory) {
            PackForgeLog.d(TAG, "ℹ️ No hay carpeta entity en la salida para analizar.")
            return@withContext RepairResult(0, emptyList())
        }

        // ⭐ SEMANTIC: Aceptar TODOS los .json, clasificar por contenido (duck typing)
        val entityFiles = entityDir.listFiles()?.filter { it.name.endsWith(".json", true) } ?: emptyList()
        
        if (entityFiles.isEmpty()) {
            PackForgeLog.d(TAG, "ℹ️ No hay archivos .json en entity/ para analizar.")
            return@withContext RepairResult(0, emptyList())
        }

        // ⭐ CONCURRENCIA ESTRUCTURADA: supervisorScope permite que un hijo falle
        // sin cancelar a los demás. Usamos async para paralelismo real.
        val results = supervisorScope {
            entityFiles.map { entityFile ->
                async {
                    try {
                        processEntityFile(entityFile, outputDir, zipIndex)
                    } catch (e: Exception) {
                        PackForgeLog.e(TAG, "Error parseando ${entityFile.name}: ${e.message}")
                        EntityResult(0, listOf("Error: ${entityFile.name} - ${e.message}"))
                    }
                }
            }.awaitAll()
        }

        val fixedCount = results.sumOf { it.fixedCount }
        val missingRefs = results.flatMap { it.missingRefs }.distinct()
        
        PackForgeLog.d(TAG, "✅ Fase 2 completada (paralelo): $fixedCount huecos reparados, ${missingRefs.size} faltantes")
        RepairResult(fixedCount, missingRefs)
    }

    /** Resultado de procesar un archivo de entidad individual. */
    private data class EntityResult(
        val fixedCount: Int,
        val missingRefs: List<String>
    )

    /** Procesa un archivo de entidad y sus referencias (geometría, animaciones). */
    private fun processEntityFile(
        entityFile: File,
        outputDir: File,
        zipIndex: ZipIndex
    ): EntityResult {
        var fixedCount = 0
        val missingRefs = mutableListOf<String>()

        try {
            val json = JSONObject(entityFile.readText(StandardCharsets.UTF_8))
            // ⭐ DUCK TYPING: Solo procesar archivos con "minecraft:client_entity"
            if (!json.has("minecraft:client_entity")) return EntityResult(0, emptyList())
            val clientEntity = json.optJSONObject("minecraft:client_entity") ?: return EntityResult(0, emptyList())
            val desc = clientEntity.optJSONObject("description") ?: clientEntity

            // 1. Extraer referencias de Geometría
            val geoRef = extractGeometry(desc)
            if (geoRef != null) {
                val geoFileName = "${geoRef.substringAfterLast(".")}.geo.json"
                val targetGeoFile = File(outputDir, "models/entity/$geoFileName")
                if (!targetGeoFile.exists()) {
                    val found = searchAndExtractFromZipIndex(geoFileName, "models/entity/", outputDir, zipIndex)
                    if (found) {
                        fixedCount++
                        PackForgeLog.d(TAG, "🔧 Reparado: Geometría faltante '$geoFileName' copiada.")
                    } else {
                        missingRefs.add("geometry:$geoRef")
                    }
                }
            }

            // 2. Extraer referencias de Animaciones
            val animsObj = desc.optJSONObject("animations") ?: json.optJSONObject("animations")
            animsObj?.keys()?.forEach { animKey ->
                val animPath = animsObj.optString(animKey)
                val animFileName = "${animPath.substringAfterLast("/")}.animation.json"
                val targetAnimFile = File(outputDir, "animations/$animFileName")
                if (!targetAnimFile.exists()) {
                    val found = searchAndExtractFromZipIndex(animFileName, "animations/", outputDir, zipIndex)
                    if (found) {
                        fixedCount++
                        PackForgeLog.d(TAG, "🔧 Reparado: Animación faltante '$animFileName' copiada.")
                    } else {
                        missingRefs.add("animation:$animPath")
                    }
                }
            }

        } catch (e: Exception) {
            PackForgeLog.e(TAG, "Error parseando ${entityFile.name}: ${e.message}")
            return EntityResult(0, listOf("Error: ${entityFile.name} - ${e.message}"))
        }

        return EntityResult(fixedCount, missingRefs)
    }

    private fun extractGeometry(desc: JSONObject): String? {
        // Alias ("Geometry.default") y Molang no son ids: se ignoran en lugar de
        // buscarse como archivos de modelo.
        return BedrockRefs.geometryIds(desc.opt("geometry")).firstOrNull()
    }

    /**
     * Busca un archivo en el índice de ZIPs (construido una sola vez) y lo extrae.
     * Evita reabrir ZIPs y recorrer todas sus entradas por cada archivo faltante.
     */
    private fun searchAndExtractFromZipIndex(
        fileName: String,
        subFolderPath: String,
        outputDir: File,
        zipIndex: ZipIndex
    ): Boolean {
        val entryRef = zipIndex.find(fileName) ?: return false
        try {
            ZipFile(entryRef.zipPath).use { zip ->
                val entry = zip.getEntry(entryRef.entryName)
                if (entry == null || entry.isDirectory) return false
                val targetFile = File(outputDir, "$subFolderPath$fileName")
                targetFile.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output, bufferSize = 16384)
                    }
                }
                return true
            }
        } catch (e: Exception) {
            PackForgeLog.w(TAG, "Error extrayendo ${entryRef.entryName} de ${entryRef.zipPath}: ${e.message}")
        }
        return false
    }
}
