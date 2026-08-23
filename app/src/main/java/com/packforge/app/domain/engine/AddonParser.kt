package com.packforge.app.domain.engine

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.packforge.app.domain.model.Addon
import com.packforge.app.domain.model.AddonType
import com.packforge.app.util.FileUtils
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipInputStream

object AddonParser {

    fun parseFromUri(context: Context, uri: Uri): Addon? {
        return try {
            // 1. Obtener nombre y tamaño real del archivo
            var fileName = "addon_${System.currentTimeMillis()}.mcaddon"
            var fileSize = 0L
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) fileName = cursor.getString(nameIndex) ?: fileName
                    if (sizeIndex >= 0) fileSize = cursor.getLong(sizeIndex)
                }
            }

            // Verificar extensiones permitidas
            if (!fileName.endsWith(".mcaddon", ignoreCase = true) &&
                !fileName.endsWith(".mcpack", ignoreCase = true) &&
                !fileName.endsWith(".zip", ignoreCase = true)) {
                return null
            }

            val addonId = UUID.randomUUID().toString()
            
            // 2. GUARDAR COPIA PERMANENTE EN ALMACENAMIENTO INTERNO (Solución al error 22B)
            val addonsDir = File(context.filesDir, "addons").apply { mkdirs() }
            val internalFile = File(addonsDir, "${addonId}_$fileName")
            
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(internalFile).use { output ->
                    input.copyTo(output)
                }
            }

            // 3. Analizar el archivo ya guardado internamente mediante extracción temporal robusta
            val parseTempDir = File(context.cacheDir, "parse_temp_${addonId}")
            parseTempDir.mkdirs()
            
            val extractedPath = AddonExtractor.extractAddon(internalFile.absolutePath, parseTempDir.absolutePath)
            if (extractedPath == null) {
                parseTempDir.deleteRecursively()
                return null
            }

            val info = AddonExtractor.resolveAddonInfo(parseTempDir, internalFile)
            val displayName = info.displayName
            val addonClassification = info.classification

            val addonType = when (addonClassification) {
                is AddonExtractor.AddonClassification.BEHAVIOR_PACK -> AddonType.BEHAVIOR_ONLY
                is AddonExtractor.AddonClassification.RESOURCE_PACK -> AddonType.RESOURCE_ONLY
                is AddonExtractor.AddonClassification.BOTH,
                is AddonExtractor.AddonClassification.MULTI -> AddonType.BEHAVIOR_AND_RESOURCE
                else -> AddonType.UNKNOWN
            }

            val allFiles = mutableListOf<String>()
            val behaviorFiles = mutableListOf<String>()
            val resourceFiles = mutableListOf<String>()
            val entityIdentifiers = mutableListOf<String>()
            val itemIdentifiers = mutableListOf<String>()
            val recipeIdentifiers = mutableListOf<String>()
            var hasScripts = false
            var version = "1.0.0"
            var manifestUuid = UUID.randomUUID().toString()
            var minEngineVersion = listOf(1, 20, 0)
            var rawManifest = ""
            var iconPath: String? = null

            parseTempDir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    val entryName = file.relativeTo(parseTempDir).path.replace("\\", "/")
                    allFiles.add(entryName)

                    // Extraer icono
                    if (entryName.lowercase().endsWith("pack_icon.png") && iconPath == null) {
                        try {
                            val iconFile = File(context.cacheDir, "icon_${addonId}.png")
                            FileUtils.fastCopy(file, iconFile)
                            iconPath = iconFile.absolutePath
                        } catch (e: Exception) {}
                    }

                    // Clasificación para el modelo visual
                    val lower = entryName.lowercase()
                    when {
                        lower.contains("behavior") || lower.contains("bp/") || lower.startsWith("entities/") || lower.startsWith("items/") -> behaviorFiles.add(entryName)
                        lower.contains("resource") || lower.contains("rp/") || lower.startsWith("textures/") || lower.startsWith("models/") -> resourceFiles.add(entryName)
                    }

                    if (SCRIPT_EXTENSIONS.any { lower.endsWith(".$it") } && (lower.startsWith("scripts/") || lower.contains("/scripts/"))) {
                        hasScripts = true
                    }

                    // Leer manifest y identifiers
                    if (lower.endsWith("manifest.json")) {
                        try {
                            val jsonText = file.readText(Charsets.UTF_8)
                            val json = JSONObject(jsonText)
                            rawManifest = jsonText
                            json.optJSONObject("header")?.let { h ->
                                manifestUuid = h.optString("uuid", manifestUuid)
                                h.optJSONArray("version")?.let { v -> version = "${v.optInt(0)}.${v.optInt(1)}.${v.optInt(2)}" }
                                h.optJSONArray("min_engine_version")?.let { v -> minEngineVersion = listOf(v.optInt(0, 1), v.optInt(1, 20), v.optInt(2, 0)) }
                            }
                        } catch (e: Exception) {}
                    }

                    if (lower.endsWith(".json")) {
                        try {
                            val json = JSONObject(file.readText(Charsets.UTF_8))
                            if (lower.contains("entities/")) extractEntityIdentifier(json)?.let { entityIdentifiers.add(it) }
                            if (lower.contains("items/")) extractItemIdentifier(json)?.let { itemIdentifiers.add(it) }
                            if (lower.contains("recipes/")) extractRecipeIdentifier(json)?.let { recipeIdentifiers.add(it) }
                        } catch (e: Exception) {}
                    }
                }
            }

            // Limpiar carpeta temporal de análisis
            parseTempDir.deleteRecursively()

            Addon(
                id = addonId,
                name = displayName,
                fileName = fileName,
                type = addonType,
                version = version,
                sizeBytes = fileSize,
                files = allFiles,
                behaviorFiles = behaviorFiles,
                resourceFiles = resourceFiles,
                entityIdentifiers = entityIdentifiers,
                itemIdentifiers = itemIdentifiers,
                recipeIdentifiers = recipeIdentifiers,
                hasScripts = hasScripts,
                minEngineVersion = minEngineVersion,
                manifestUuid = manifestUuid,
                rawManifest = rawManifest,
                iconPath = iconPath,
                sourceFilePath = internalFile.absolutePath
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun extractEntityIdentifier(json: JSONObject): String? = 
        json.optJSONObject("minecraft:entity")?.optJSONObject("description")?.optString("identifier")?.takeIf { it.isNotBlank() }

    private fun extractItemIdentifier(json: JSONObject): String? = 
        json.optJSONObject("minecraft:item")?.optJSONObject("description")?.optString("identifier")?.takeIf { it.isNotBlank() }

    private fun extractRecipeIdentifier(json: JSONObject): String? {
        val types = listOf("minecraft:recipe_shaped", "minecraft:recipe_shapeless", "minecraft:recipe_furnace")
        for (t in types) {
            val id = json.optJSONObject(t)?.optJSONObject("description")?.optString("identifier")
            if (id != null) return id
        }
        return null
    }

    /**
     * Recupera el icono (pack_icon.png) de un addon ya guardado en almacenamiento
     * interno a partir de su archivo fuente. Se usa al cargar un modpack desde
     * "My Modpacks": el icono cacheado puede haber desaparecido, pero el archivo
     * fuente persiste, así que lo extraemos de nuevo para que la portada/original
     * siempre se muestre (sin recurrir a un color sólido de respaldo).
     *
     * @return ruta absoluta del icono extraído, o null si no se pudo recuperar.
     */
    fun recoverIconFromSource(sourceFilePath: String, addonId: String, context: Context): String? {
        val src = File(sourceFilePath)
        if (!src.exists()) return null
        val tempDir = File(context.cacheDir, "icon_recover_$addonId")
        tempDir.mkdirs()
        val extracted = AddonExtractor.extractAddon(src.absolutePath, tempDir.absolutePath) ?: run {
            tempDir.deleteRecursively()
            return null
        }
        var iconPath: String? = null
        File(extracted).walkTopDown().forEach { f ->
            if (f.isFile && f.name.equals("pack_icon.png", ignoreCase = true) && iconPath == null) {
                val dest = File(context.cacheDir, "icon_$addonId.png")
                try {
                    FileUtils.fastCopy(f, dest)
                    iconPath = dest.absolutePath
                } catch (_: Exception) { /* ignorar */ }
            }
        }
        tempDir.deleteRecursively()
        return iconPath
    }
}
