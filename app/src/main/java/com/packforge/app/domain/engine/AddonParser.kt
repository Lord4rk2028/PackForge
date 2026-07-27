package com.packforge.app.domain.engine

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.packforge.app.domain.model.Addon
import com.packforge.app.domain.model.AddonType
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

            // 3. Analizar el archivo ya guardado internamente
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
            var realName: String? = null
            var iconPath: String? = null

            internalFile.inputStream().use { fileInput ->
                ZipInputStream(BufferedInputStream(fileInput)).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val entryName = entry.name.replace("\\", "/")
                            allFiles.add(entryName)

                            // Extraer icono
                            if (entryName.lowercase().endsWith("pack_icon.png")) {
                                try {
                                    val iconFile = File(context.cacheDir, "icon_${addonId}.png")
                                    iconFile.outputStream().use { zip.copyTo(it) }
                                    iconPath = iconFile.absolutePath
                                } catch (e: Exception) {}
                            }

                            // Clasificación rápida para el modelo visual
                            val lower = entryName.lowercase()
                            when {
                                lower.contains("behavior") || lower.startsWith("entities/") || lower.startsWith("items/") -> behaviorFiles.add(entryName)
                                lower.contains("resource") || lower.startsWith("textures/") || lower.startsWith("models/") -> resourceFiles.add(entryName)
                            }

                            if (lower.endsWith(".js") && lower.startsWith("scripts/")) hasScripts = true

                            // Leer manifest y identifiers
                            if (lower.endsWith("manifest.json") || (lower.endsWith(".json") && (lower.contains("entities/") || lower.contains("items/") || lower.contains("recipes/")))) {
                                val jsonText = String(zip.readBytes())
                                try {
                                    val json = JSONObject(jsonText)
                                    if (lower.endsWith("manifest.json")) {
                                        rawManifest = jsonText
                                        json.optJSONObject("header")?.let { h ->
                                            manifestUuid = h.optString("uuid", manifestUuid)
                                            realName = if (h.has("name")) h.getString("name") else null
                                            h.optJSONArray("version")?.let { v -> version = "${v.optInt(0)}.${v.optInt(1)}.${v.optInt(2)}" }
                                            h.optJSONArray("min_engine_version")?.let { v -> minEngineVersion = listOf(v.optInt(0, 1), v.optInt(1, 20), v.optInt(2, 0)) }
                                        }
                                    }
                                    if (lower.contains("entities/")) extractEntityIdentifier(json)?.let { entityIdentifiers.add(it) }
                                    if (lower.contains("items/")) extractItemIdentifier(json)?.let { itemIdentifiers.add(it) }
                                    if (lower.contains("recipes/")) extractRecipeIdentifier(json)?.let { recipeIdentifiers.add(it) }
                                } catch (e: Exception) {}
                            }
                        }
                        entry = zip.nextEntry
                    }
                }
            }

            // Determinar tipo basado en manifest.json si está disponible
            val addonType = if (rawManifest.isNotBlank()) {
                try {
                    val manifest = JSONObject(rawManifest)
                    val modules = manifest.optJSONArray("modules")
                    val moduleTypes = mutableListOf<String>()
                    
                    if (modules != null) {
                        for (i in 0 until modules.length()) {
                            val module = modules.optJSONObject(i)
                            if (module != null) {
                                val type = module.optString("type")
                                if (type.isNotEmpty()) {
                                    moduleTypes.add(type)
                                }
                            }
                        }
                    }
                    
                    when {
                        moduleTypes.contains("data") && moduleTypes.contains("resources") -> AddonType.BEHAVIOR_AND_RESOURCE
                        moduleTypes.contains("data") -> AddonType.BEHAVIOR_ONLY
                        moduleTypes.contains("resources") -> AddonType.RESOURCE_ONLY
                        else -> {
                            // Fallback a detección por archivos
                            when {
                                behaviorFiles.isNotEmpty() && resourceFiles.isNotEmpty() -> AddonType.BEHAVIOR_AND_RESOURCE
                                behaviorFiles.isNotEmpty() -> AddonType.BEHAVIOR_ONLY
                                resourceFiles.isNotEmpty() -> AddonType.RESOURCE_ONLY
                                else -> AddonType.UNKNOWN
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Fallback a detección por archivos si hay error al leer manifest
                    when {
                        behaviorFiles.isNotEmpty() && resourceFiles.isNotEmpty() -> AddonType.BEHAVIOR_AND_RESOURCE
                        behaviorFiles.isNotEmpty() -> AddonType.BEHAVIOR_ONLY
                        resourceFiles.isNotEmpty() -> AddonType.RESOURCE_ONLY
                        else -> AddonType.UNKNOWN
                    }
                }
            } else {
                // Sin manifest.json, usar detección por archivos
                when {
                    behaviorFiles.isNotEmpty() && resourceFiles.isNotEmpty() -> AddonType.BEHAVIOR_AND_RESOURCE
                    behaviorFiles.isNotEmpty() -> AddonType.BEHAVIOR_ONLY
                    resourceFiles.isNotEmpty() -> AddonType.RESOURCE_ONLY
                    else -> AddonType.UNKNOWN
                }
            }

            Addon(
                id = addonId,
                name = realName ?: fileName.substringBeforeLast("."),
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
}
