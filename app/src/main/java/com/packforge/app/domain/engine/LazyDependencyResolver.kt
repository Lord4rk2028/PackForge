package com.packforge.app.domain.engine

import com.packforge.app.util.PackForgeLog
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * FASE 2: DETECCIÓN DE HUECOS (HOLE DETECTION) - Lazy & Selectiva.
 * Escanea únicamente los archivos críticos ya copiados en el directorio de salida (ej: .entity.json),
 * parsea sus referencias y rellena los huecos buscando directamente en los ZIPs originales.
 */
object LazyDependencyResolver {
    private const val TAG = "PackForge_LazyResolver"

    data class RepairResult(
        val fixedReferences: Int,
        val missingReferences: List<String>
    )

    fun resolveAndRepair(
        outputDir: File,
        originalAddonPaths: List<String>
    ): RepairResult {
        PackForgeLog.d(TAG, "🔍 Iniciando detección de huecos (Lazy Hole Detection)...")
        var fixedCount = 0
        val missingRefs = mutableListOf<String>()

        val entityDir = File(outputDir, "entity")
        if (!entityDir.exists() || !entityDir.isDirectory) {
            PackForgeLog.d(TAG, "ℹ️ No hay carpeta entity en la salida para analizar.")
            return RepairResult(0, emptyList())
        }

        val entityFiles = entityDir.listFiles()?.filter { it.name.endsWith(".entity.json") } ?: emptyList()

        entityFiles.forEach { entityFile ->
            try {
                val json = JSONObject(entityFile.readText(Charsets.UTF_8))
                val clientEntity = json.optJSONObject("minecraft:client_entity") ?: json
                val desc = clientEntity.optJSONObject("description") ?: clientEntity

                // 1. Extraer referencias de Geometría
                val geoRef = extractGeometry(desc)
                if (geoRef != null) {
                    val geoFileName = "${geoRef.substringAfterLast(".")}.geo.json"
                    val targetGeoFile = File(outputDir, "models/entity/$geoFileName")
                    if (!targetGeoFile.exists()) {
                        val found = searchAndExtractFromZips(geoFileName, "models/entity/", outputDir, originalAddonPaths)
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
                        val found = searchAndExtractFromZips(animFileName, "animations/", outputDir, originalAddonPaths)
                        if (found) {
                            fixedCount++
                            PackForgeLog.d(TAG, "🔧 Reparado: Animación faltante '$animFileName' copiada.")
                        } else {
                            missingRefs.add("animation:$animPath")
                        }
                    }
                }

            } catch (e: Exception) {
                PackForgeLog.e(TAG, "Error parseando ${entityFile.name} en Fase 2: ${e.message}")
            }
        }

        PackForgeLog.d(TAG, "✅ Fase 2 completada: $fixedCount huecos reparados.")
        return RepairResult(fixedCount, missingRefs)
    }

    private fun extractGeometry(desc: JSONObject): String? {
        val geo = desc.opt("geometry")
        return when (geo) {
            is String -> geo
            is JSONObject -> {
                // Puede tener múltiples variantes, tomamos la primera o 'default'
                geo.optString("default").ifEmpty {
                    geo.keys().asSequence().firstOrNull()?.let { geo.optString(it) }
                }
            }
            else -> null
        }
    }

    private fun searchAndExtractFromZips(
        fileName: String,
        subFolderPath: String,
        outputDir: File,
        addonPaths: List<String>
    ): Boolean {
        for (addonPath in addonPaths) {
            try {
                ZipFile(addonPath).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (!entry.isDirectory && entry.name.endsWith(fileName, ignoreCase = true)) {
                            // Encontrado en el ZIP original, extraer al destino
                            val targetFile = File(outputDir, "$subFolderPath$fileName")
                            targetFile.parentFile?.mkdirs()
                            zip.getInputStream(entry).use { input ->
                                targetFile.outputStream().use { output ->
                                    input.copyTo(output, bufferSize = 16384)
                                }
                            }
                            return true
                        }
                    }
                }
            } catch (e: Exception) {
                PackForgeLog.w(TAG, "Error buscando en ZIP $addonPath: ${e.message}")
            }
        }
        return false
    }
}
