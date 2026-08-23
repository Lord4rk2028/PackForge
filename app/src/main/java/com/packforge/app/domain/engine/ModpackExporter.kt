package com.packforge.app.domain.engine

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.packforge.app.domain.model.Addon
import com.packforge.app.domain.model.ExportState
import com.packforge.app.domain.model.ModpackMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ModpackExporter {

    private const val MINECRAFT_PACKAGE = "com.mojang.minecraftpe"

    fun isMinecraftInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(MINECRAFT_PACKAGE, 0)
            true
        } catch (e: Exception) { false }
    }

    fun getMinecraftVersion(context: Context): String? {
        return try {
            context.packageManager.getPackageInfo(MINECRAFT_PACKAGE, 0).versionName
        } catch (e: Exception) { null }
    }

    class MergedPack(val name: String, val type: String) {
        val files = mutableMapOf<String, ByteArray>()
        val uuid = UUID.randomUUID().toString()
        val moduleUuid = UUID.randomUUID().toString()
    }

    suspend fun exportModpack(
        context: Context,
        addons: List<Addon>,
        metadata: ModpackMetadata,
        resolutions: Map<String, String>,
        outputUri: Uri?,
        importToMinecraft: Boolean,
        minecraftFolderUri: String? = null
    ): ExportState = withContext(Dispatchers.IO) {
        try {
            val active = addons.filter { it.enabled }.sortedBy { it.priority }
            if (active.isEmpty()) return@withContext ExportState.Error("No hay addons activos.")

            val mergedBP = MergedPack("${metadata.name} (BP)", "data")
            val mergedRP = MergedPack("${metadata.name} (RP)", "resources")
            val iconBytes = getIconBytes(context, metadata)

            // Procesar addons
            active.forEach { addon ->
                val packs = extractAndNormalizePacks(addon)
                packs.forEach { extracted ->
                    val isRP = detectIfResourcePack(extracted.files)
                    val targetPack = if (isRP) mergedRP else mergedBP
                    
                    extracted.files.forEach { (path, bytes) ->
                        val lowerPath = path.lowercase()
                        if (lowerPath == "manifest.json" || lowerPath == "pack_icon.png") return@forEach
                        
                        // Fusión de archivos Singleton
                        if (targetPack.files.containsKey(path)) {
                            if (lowerPath == "blocks.json" || 
                                lowerPath == "textures/item_texture.json" || 
                                lowerPath == "textures/terrain_texture.json" ||
                                lowerPath == "sounds/sound_definitions.json") {
                                targetPack.files[path] = mergeJsonFiles(targetPack.files[path], bytes, lowerPath)
                                return@forEach
                            }
                            
                            if (lowerPath.endsWith(".lang")) {
                                targetPack.files[path] = (String(targetPack.files[path]!!) + "\n" + String(bytes)).toByteArray()
                                return@forEach
                            }

                            // Si no es singleton, el ganador se elige por resolución o prioridad
                            val conflictKey = resolutions.keys.find { it.contains(path) }
                            if (conflictKey != null && resolutions[conflictKey] != addon.id) return@forEach
                        }
                        targetPack.files[path] = bytes
                    }
                }
            }

            // Generar Manifests vinculados
            mergedBP.files["manifest.json"] = generateManifest(metadata, mergedBP, mergedRP.uuid)
            mergedRP.files["manifest.json"] = generateManifest(metadata, mergedRP, mergedBP.uuid)

            val safeFileName = metadata.name.trim().replace("[^a-zA-Z0-9]".toRegex(), "_").ifBlank { "modpack" }
            val fileName = "${safeFileName}_v${metadata.version}.mcaddon"
            val tempFile = File(context.cacheDir, fileName)
            
            // Verificar que tenemos contenido para empaquetar
            val bpHasContent = mergedBP.files.size > 1 // >1 porque siempre tiene manifest.json
            val rpHasContent = mergedRP.files.size > 1
            
            if (!bpHasContent && !rpHasContent) {
                return@withContext ExportState.Error("No hay contenido válido para exportar. Los addons seleccionados pueden estar vacíos o corruptos.")
            }
            
            ZipOutputStream(FileOutputStream(tempFile)).use { zos ->
                if (bpHasContent) writeMergedToZip(zos, mergedBP, "modpack_BP", iconBytes)
                if (rpHasContent) writeMergedToZip(zos, mergedRP, "modpack_RP", iconBytes)
            }
            
            // Validar que el archivo temporal se creó y tiene tamaño > 0
            if (!tempFile.exists() || tempFile.length() == 0L) {
                return@withContext ExportState.Error("El archivo de exportación se generó vacío (0 bytes). Revisa Logcat para detalles.")
            }
            
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val outputFile = File(downloadsDir, fileName)
            tempFile.copyTo(outputFile, overwrite = true)
            
            // Verificación final
            if (!outputFile.exists() || outputFile.length() == 0L) {
                return@withContext ExportState.Error("El archivo se copió pero resultó vacío en Descargas.")
            }
            
            Log.i("ModpackExporter", "Exportación exitosa: ${outputFile.absolutePath} (${outputFile.length()} bytes)")
            ExportState.Success(fileName, "Descargas/$fileName", false)
        } catch (e: Exception) {
            Log.e("ModpackExporter", "Error crítico durante exportación", e)
            ExportState.Error("Error en fusión: ${e.message}")
        }
    }

    private fun mergeJsonFiles(existing: ByteArray?, new: ByteArray, path: String): ByteArray {
        if (existing == null) return new
        return try {
            val oldJson = JSONObject(String(existing))
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
        } catch (e: Exception) {
            Log.e("ModpackExporter", "Error al fusionar JSON en $path", e)
            existing
        }
    }

    private fun detectIfResourcePack(files: Map<String, ByteArray>): Boolean {
        val manifest = files["manifest.json"] ?: return files.keys.any { it.startsWith("textures/") }
        return try {
            JSONObject(String(manifest)).optJSONArray("modules")?.optJSONObject(0)?.optString("type") == "resources"
        } catch (e: Exception) { false }
    }

    private fun generateManifest(metadata: ModpackMetadata, pack: MergedPack, linkedUuid: String?): ByteArray {
        val json = JSONObject().apply {
            put("format_version", 2)
            put("header", JSONObject().apply {
                put("name", pack.name); put("uuid", pack.uuid); put("version", JSONArray(listOf(1, 0, 0)))
                put("min_engine_version", JSONArray(listOf(1, 20, 0)))
            })
            put("modules", JSONArray(listOf(JSONObject().apply {
                put("type", pack.type); put("uuid", pack.moduleUuid); put("version", JSONArray(listOf(1, 0, 0)))
            })))
            if (linkedUuid != null) {
                put("dependencies", JSONArray(listOf(JSONObject().apply { put("uuid", linkedUuid); put("version", JSONArray(listOf(1, 0, 0))) })))
            }
        }
        return json.toString(4).toByteArray()
    }

    private fun writeMergedToZip(zos: ZipOutputStream, pack: MergedPack, folder: String, icon: ByteArray?) {
        pack.files.forEach { (path, bytes) ->
            try {
                zos.putNextEntry(ZipEntry("$folder/$path"))
                zos.write(bytes)
                zos.closeEntry()
            } catch (e: Exception) {
                Log.e("ModpackExporter", "Error al escribir entrada en zip: $path", e)
            }
        }
        icon?.let {
            try {
                zos.putNextEntry(ZipEntry("$folder/pack_icon.png"))
                zos.write(it)
                zos.closeEntry()
            } catch (e: Exception) {
                Log.e("ModpackExporter", "Error al escribir icono en zip", e)
            }
        }
    }

    private fun extractAndNormalizePacks(addon: Addon): List<MergedPack> {
        val file = File(addon.sourceFilePath)
        if (!file.exists()) {
            Log.w("ModpackExporter", "Archivo de addon no existe: ${file.absolutePath}")
            return emptyList()
        }
        val results = mutableListOf<MergedPack>()
        try {
            ZipInputStream(BufferedInputStream(FileInputStream(file))).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name.lowercase().endsWith(".mcpack")) {
                        val content = readZipToMap(zip.readBytes())
                        if (content.isNotEmpty()) {
                            val p = MergedPack(addon.name, "")
                            p.files.putAll(normalizePaths(content))
                            results.add(p)
                        } else {
                            Log.w("ModpackExporter", "mcpack vacío o corrupto en: ${addon.name} - ${entry.name}")
                        }
                    }
                    entry = zip.nextEntry
                }
            }
            if (results.isEmpty()) {
                val content = readZipToMapFromUri(file)
                if (content.isNotEmpty()) {
                    val p = MergedPack(addon.name, "")
                    p.files.putAll(normalizePaths(content))
                    results.add(p)
                } else {
                    Log.w("ModpackExporter", "Addon sin contenido válido: ${addon.name}")
                }
            }
        } catch (e: Exception) {
            Log.e("ModpackExporter", "Error al extraer addon: ${addon.name}", e)
        }
        return results
    }

    private fun normalizePaths(files: Map<String, ByteArray>): Map<String, ByteArray> {
        val clean = files.mapKeys { it.key.replace("\\", "/").trimStart('/') }
        val manifestPath = clean.keys.find { it.endsWith("manifest.json") } ?: return clean
        val root = manifestPath.removeSuffix("manifest.json")
        return if (root.isEmpty()) clean else clean.filter { it.key.startsWith(root) }.mapKeys { it.key.removePrefix(root) }
    }

    private fun readZipToMapFromUri(file: File): Map<String, ByteArray> {
        val res = mutableMapOf<String, ByteArray>()
        try { 
            ZipInputStream(BufferedInputStream(FileInputStream(file))).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val bytes = zip.readBytes()
                        if (bytes.isNotEmpty()) {
                            res[entry.name] = bytes
                        } else {
                            Log.w("ModpackExporter", "Entrada vacía detectada: ${entry.name}")
                        }
                    }
                    entry = zip.nextEntry
                }
            } 
        } catch (e: Exception) {
            Log.e("ModpackExporter", "Error al leer ZIP desde archivo: ${file.name}", e)
        }
        return res
    }

    private fun readZipToMap(bytes: ByteArray): Map<String, ByteArray> {
        val res = mutableMapOf<String, ByteArray>()
        try { 
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val content = zip.readBytes()
                        if (content.isNotEmpty()) {
                            res[entry.name] = content
                        }
                    }
                    entry = zip.nextEntry
                }
            } 
        } catch (e: Exception) {
            Log.e("ModpackExporter", "Error al leer ZIP desde bytes (mcpack interno)", e)
        }
        return res
    }

    private fun getIconBytes(context: Context, metadata: ModpackMetadata): ByteArray? {
        val uriStr = metadata.coverUriString ?: return null
        return try { context.contentResolver.openInputStream(Uri.parse(uriStr))?.use { it.readBytes() } } catch (e: Exception) { null }
    }
    
    fun openInMinecraft(context: Context, fileName: String) {
        try {
            val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
            if (!file.exists()) return
            val fileUri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(fileUri, "application/octet-stream")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage("com.mojang.minecraftpe")
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
                val fileUri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(fileUri, "application/octet-stream")
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(android.content.Intent.createChooser(intent, "Abrir con Minecraft"))
            } catch (e2: Exception) {}
        }
    }
}
