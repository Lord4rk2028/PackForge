package com.packforge.app.domain.engine

import android.content.Context
import android.net.Uri
import com.packforge.app.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object AddonMerger {

    suspend fun mergeAddons(
        context: Context,
        addons: List<Addon>,
        metadata: ModpackMetadata,
        strategy: ConflictStrategy,
        onProgress: (Float) -> Unit
    ): MergeResult = withContext(Dispatchers.IO) {
        val conflictsLog = mutableListOf<String>()
        try {
            val activeAddons = addons.filter { it.enabled }.sortedBy { it.priority }
            if (activeAddons.isEmpty()) return@withContext MergeResult(errorMessage = "No hay addons activos para fusionar.")

            val mergedBP = MergedPackData("data")
            val mergedRP = MergedPackData("resources")
            
            val totalSteps = activeAddons.size.toFloat()
            
            activeAddons.forEachIndexed { index, addon ->
                onProgress(index / totalSteps)
                
                val sourceFile = File(addon.sourceFilePath)
                if (!sourceFile.exists()) {
                    conflictsLog.add("ERROR: No se encontró el archivo fuente para ${addon.name}")
                    return@forEachIndexed
                }

                // Extraer packs (pueden ser varios .mcaddon dentro de un .mcaddon)
                val extractedPacks = extractPacksFromZip(sourceFile)
                
                extractedPacks.forEach { extracted ->
                    val isRP = detectIfResourcePack(extracted)
                    val target = if (isRP) mergedRP else mergedBP
                    
                    extracted.forEach { (path, content) ->
                        val lowerPath = path.lowercase()
                        if (lowerPath == "manifest.json" || lowerPath == "pack_icon.png") return@forEach

                        if (target.files.containsKey(path)) {
                            // Lógica de Fusión para Singletons (Siempre se fusionan)
                            if (isSingletonFile(lowerPath)) {
                                target.files[path] = mergeFiles(target.files[path]!!, content, lowerPath)
                            } else {
                                // Lógica de Conflicto según Estrategia
                                when (strategy) {
                                    ConflictStrategy.KEEP_FIRST -> {
                                        conflictsLog.add("Conflicto en $path: Manteniendo versión de addon anterior.")
                                    }
                                    ConflictStrategy.KEEP_LAST -> {
                                        target.files[path] = content
                                        conflictsLog.add("Conflicto en $path: Reemplazado por ${addon.name}.")
                                    }
                                    ConflictStrategy.LOG_AND_SKIP -> {
                                        conflictsLog.add("Conflicto en $path: Omitiendo archivo de ${addon.name}.")
                                    }
                                }
                            }
                        } else {
                            target.files[path] = content
                        }
                    }
                }
            }

            // Generar Identidades Únicas y Vinculación
            val bpUuid = UUID.randomUUID().toString()
            val rpUuid = UUID.randomUUID().toString()
            
            mergedBP.files["manifest.json"] = createManifest(metadata, "data", bpUuid, rpUuid)
            mergedRP.files["manifest.json"] = createManifest(metadata, "resources", rpUuid, bpUuid)

            // Escribir archivo final
            val outputFileName = "${metadata.name.replace(" ", "_")}_v${metadata.version}.mcaddon"
            val outputFile = File(context.getExternalFilesDir(null), outputFileName)
            
            val iconBytes = metadata.coverUriString?.let {
                try { context.contentResolver.openInputStream(Uri.parse(it))?.readBytes() } catch (e: Exception) { null }
            }

            ZipOutputStream(FileOutputStream(outputFile)).use { zos ->
                if (mergedBP.files.size > 1) writeToZip(zos, mergedBP.files, "modpack_BP", iconBytes)
                if (mergedRP.files.size > 1) writeToZip(zos, mergedRP.files, "modpack_RP", iconBytes)
            }

            onProgress(1.0f)
            MergeResult(outputFile = outputFile, conflicts = conflictsLog)

        } catch (e: Exception) {
            MergeResult(errorMessage = "Error crítico en fusión: ${e.message}")
        }
    }

    private class MergedPackData(val type: String) {
        val files = mutableMapOf<String, ByteArray>()
    }

    private fun isSingletonFile(path: String): Boolean {
        return path == "blocks.json" || 
               path.contains("item_texture.json") || 
               path.contains("terrain_texture.json") ||
               path.contains("sound_definitions.json") ||
               path.endsWith(".lang")
    }

    private fun mergeFiles(old: ByteArray, new: ByteArray, path: String): ByteArray {
        return if (path.endsWith(".lang")) {
            (String(old) + "\n" + String(new)).toByteArray()
        } else {
            try {
                val oldJson = JSONObject(String(old))
                val newJson = JSONObject(String(new))
                if (path.contains("texture.json")) {
                    val oldData = oldJson.optJSONObject("texture_data") ?: JSONObject()
                    val newData = newJson.optJSONObject("texture_data") ?: JSONObject()
                    newData.keys().forEach { oldData.put(it, newData.get(it)) }
                    oldJson.put("texture_data", oldData)
                } else {
                    newJson.keys().forEach { if (it != "format_version") oldJson.put(it, newJson.get(it)) }
                }
                oldJson.toString(4).toByteArray()
            } catch (e: Exception) { old }
        }
    }

    private fun extractPacksFromZip(file: File): List<Map<String, ByteArray>> {
        val packs = mutableListOf<Map<String, ByteArray>>()
        try {
            ZipInputStream(BufferedInputStream(FileInputStream(file))).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.lowercase().endsWith(".mcaddon")) {
                        packs.add(normalizePack(readZipToMap(zip.readBytes())))
                    }
                    entry = zip.nextEntry
                }
            }
            if (packs.isEmpty()) {
                packs.add(normalizePack(readZipToMap(file.readBytes())))
            }
        } catch (e: Exception) {}
        return packs
    }

    private fun normalizePack(files: Map<String, ByteArray>): Map<String, ByteArray> {
        val clean = files.mapKeys { it.key.replace("\\", "/").trimStart('/') }
        val mPath = clean.keys.find { it.endsWith("manifest.json") } ?: return clean
        val root = mPath.removeSuffix("manifest.json")
        return if (root.isEmpty()) clean else clean.filter { it.key.startsWith(root) }.mapKeys { it.key.removePrefix(root) }
    }

    private fun readZipToMap(bytes: ByteArray): Map<String, ByteArray> {
        val res = mutableMapOf<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) res[entry.name] = zip.readBytes()
                    entry = zip.nextEntry
                }
            }
        } catch (e: Exception) {}
        return res
    }

    private fun detectIfResourcePack(files: Map<String, ByteArray>): Boolean {
        val manifest = files["manifest.json"] ?: return files.keys.any { it.startsWith("textures/") }
        return try {
            JSONObject(String(manifest)).optJSONArray("modules")?.optJSONObject(0)?.optString("type") == "resources"
        } catch (e: Exception) { false }
    }

    private fun createManifest(metadata: ModpackMetadata, type: String, myUuid: String, depUuid: String): ByteArray {
        val json = JSONObject().apply {
            put("format_version", 2)
            put("header", JSONObject().apply {
                put("name", "${metadata.name} (${if (type == "data") "BP" else "RP"})")
                put("uuid", myUuid)
                put("version", JSONArray(listOf(1, 0, 0)))
                put("min_engine_version", JSONArray(listOf(1, 20, 0)))
            })
            put("modules", JSONArray(listOf(JSONObject().apply {
                put("type", type)
                put("uuid", UUID.randomUUID().toString())
                put("version", JSONArray(listOf(1, 0, 0)))
            })))
            put("dependencies", JSONArray(listOf(JSONObject().apply {
                put("uuid", depUuid)
                put("version", JSONArray(listOf(1, 0, 0)))
            })))
        }
        return json.toString(4).toByteArray()
    }

    private fun writeToZip(zos: ZipOutputStream, files: Map<String, ByteArray>, folder: String, icon: ByteArray?) {
        files.forEach { (path, content) ->
            try {
                zos.putNextEntry(ZipEntry("$folder/$path"))
                zos.write(content)
                zos.closeEntry()
            } catch (e: Exception) {}
        }
        icon?.let {
            try {
                zos.putNextEntry(ZipEntry("$folder/pack_icon.png"))
                zos.write(it)
                zos.closeEntry()
            } catch (e: Exception) {}
        }
    }
}
