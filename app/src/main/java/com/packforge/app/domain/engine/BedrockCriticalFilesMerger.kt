package com.packforge.app.domain.engine

import com.packforge.app.util.PackForgeLog
import com.packforge.app.util.logFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * Fusiona los archivos CRÍTICOS que Minecraft Bedrock necesita para mostrar texturas y nombres correctamente.
 * NO usa JsonDeepMerger genérico - cada archivo tiene lógica ESPECÍFICA.
 *
 * 1. textures/terrain_texture.json - Mapea bloques a texturas (CRÍTICO)
 * 2. textures/item_texture.json - Mapea items a texturas (CRÍTICO)
 * 3. blocks.json (raíz del RP) - Define renderizado de bloques (CRÍTICO)
 * 4. entity definitions (.entity.json) - Definiciones de entidades/mobs 3D (CRÍTICO para mobs)
 * 5. render_controllers - Controladores de render (CRÍTICO para mobs)
 * 6. animations + animation_controllers - Animaciones (CRÍTICO)
 * 7. .lang en textos de texts/ + languages.json - Traducciones (CRÍTICO para "desconocido")
 * 8. sounds.json - Sonidos de bloques/entidades
 *
 * SIN estos archivos, aunque las texturas .png existan, Minecraft NO SABE qué textura mostrar
 * y muestra "?" en bloques y "desconocido" en items. Los mobs con modelos 3D además
 * necesitan sus .entity.json, render_controllers y animaciones.
 */
object BedrockCriticalFilesMerger {

    // =====================================================================
    // 1. TERRAIN TEXTURE - Mapea bloques a texturas (CRÍTICO)
    // =====================================================================
    fun mergeTerrainTexture(rpDirs: List<File>, destDir: File) {
        val mergedTextureData = JSONObject()
        var maxPadding = 8
        var maxMipLevels = 4

        rpDirs.forEach { rpDir ->
            val file = File(rpDir, "textures/terrain_texture.json")
            if (file.exists()) {
                try {
                    // Limpiar el JSON completo (trim en claves y valores)
                    val json = JsonDeepMerger.cleanJsonObject(JSONObject(file.readText(Charsets.UTF_8)))

                    json.optJSONObject("texture_data")?.let { data ->
                        data.keys().forEach { blockName ->
                            val cleanBlockName = blockName.trim()  // ⭐ QUITAR ESPACIOS
                            val value = data.get(cleanBlockName)
                            val cleanValue = JsonDeepMerger.cleanJsonValue(value)
                            if (!mergedTextureData.has(cleanBlockName)) {
                                mergedTextureData.put(cleanBlockName, cleanValue)
                            }
                        }
                    }

                    maxPadding = maxOf(maxPadding, json.optInt("padding", 8))
                    maxMipLevels = maxOf(maxMipLevels, json.optInt("num_mip_levels", 4))

                    PackForgeLog.d("PackForge_Terrain", "✅ Leído terrain_texture.json de ${rpDir.name}")
                } catch (e: Exception) {
                    PackForgeLog.e("PackForge_Terrain", "Error: ${e.message}")
                }
            }
        }

        val merged = JSONObject().apply {
            put("resource_pack_name", "PackForge")
            put("texture_name", "atlas.terrain")
            put("padding", maxPadding)
            put("num_mip_levels", maxMipLevels)
            put("texture_data", mergedTextureData)
        }

        val destFile = File(destDir, "textures/terrain_texture.json")
        destFile.parentFile?.mkdirs()
        OutputStreamWriter(FileOutputStream(destFile), StandardCharsets.UTF_8).use {
            it.write(merged.toString())
        }
        // VALIDACIÓN: no deben quedar espacios al final en claves
        validateNoTrailingSpaces(destFile, "terrain_texture.json")
        PackForgeLog.d("PackForge_Terrain", "✅ terrain_texture.json fusionado: ${mergedTextureData.length()} bloques")
    }

    // =====================================================================
    // 2. ITEM TEXTURE - Mapea items a texturas (CRÍTICO)
    // =====================================================================
    fun mergeItemTexture(rpDirs: List<File>, destDir: File) {
        val mergedTextureData = JSONObject()

        rpDirs.forEach { rpDir ->
            val file = File(rpDir, "textures/item_texture.json")
            if (file.exists()) {
                try {
                    val json = JsonDeepMerger.cleanJsonObject(JSONObject(file.readText(Charsets.UTF_8)))
                    json.optJSONObject("texture_data")?.let { data ->
                        data.keys().forEach { itemName ->
                            val cleanItemName = itemName.trim()  // ⭐ QUITAR ESPACIOS
                            val value = data.get(cleanItemName)
                            val cleanValue = JsonDeepMerger.cleanJsonValue(value)
                            if (!mergedTextureData.has(cleanItemName)) {
                                mergedTextureData.put(cleanItemName, cleanValue)
                            }
                        }
                    }
                } catch (e: Exception) {
                    PackForgeLog.e("PackForge_ItemTexture", "Error: ${e.message}")
                }
            }
        }

        val merged = JSONObject().apply {
            put("resource_pack_name", "PackForge")
            put("texture_name", "atlas.items")
            put("texture_data", mergedTextureData)
        }

        val destFile = File(destDir, "textures/item_texture.json")
        destFile.parentFile?.mkdirs()
        OutputStreamWriter(FileOutputStream(destFile), StandardCharsets.UTF_8).use {
            it.write(merged.toString())
        }
        // VALIDACIÓN: no deben quedar espacios al final en claves
        validateNoTrailingSpaces(destFile, "item_texture.json")
        PackForgeLog.d("PackForge_ItemTexture", "✅ item_texture.json: ${mergedTextureData.length()} items")
    }

    // =====================================================================
    // 3. BLOCKS.JSON - Define renderizado de bloques (CRÍTICO)
    // =====================================================================
    fun mergeBlocksJson(rpDirs: List<File>, destDir: File) {
        val merged = JSONObject().apply {
            put("format_version", JSONArray().apply { put(1); put(1); put(0) })
        }

        rpDirs.forEach { rpDir ->
            val file = File(rpDir, "blocks.json")
            if (file.exists()) {
                try {
                    val json = JsonDeepMerger.cleanJsonObject(JSONObject(file.readText(Charsets.UTF_8)))
                    json.keys().forEach { key ->
                        val cleanKey = key.trim()  // ⭐ QUITAR ESPACIOS
                        if (cleanKey != "format_version" && !merged.has(cleanKey)) {
                            merged.put(cleanKey, JsonDeepMerger.cleanJsonValue(json.get(cleanKey)))
                        }
                    }
                } catch (e: Exception) {
                    PackForgeLog.e("PackForge_BlocksJson", "Error: ${e.message}")
                }
            }
        }

        val destFile = File(destDir, "blocks.json")
        OutputStreamWriter(FileOutputStream(destFile), StandardCharsets.UTF_8).use {
            it.write(merged.toString())
        }
        // VALIDACIÓN: no deben quedar espacios al final en claves
        validateNoTrailingSpaces(destFile, "blocks.json")
        PackForgeLog.d("PackForge_BlocksJson", "✅ blocks.json: ${merged.length() - 1} bloques")
    }

    // =====================================================================
    // 4. ENTITY DEFINITIONS - RP/entity/*.entity.json (CRÍTICO para mobs 3D)
    // =====================================================================
    fun mergeEntityDefinitions(rpDirs: List<File>, destDir: File) {
        val destEntityDir = File(destDir, "entity")
        destEntityDir.mkdirs()

        rpDirs.forEach { rpDir ->
            val entityDir = File(rpDir, "entity")
            if (entityDir.exists()) {
                entityDir.listFiles()?.filter { it.name.endsWith(".entity.json") }?.forEach { file ->
                    val destFile = File(destEntityDir, file.name)

                    if (!destFile.exists()) {
                        // Copiar LIMPIANDO espacios en claves/valores (evita "desconocido")
                        try {
                            val clean = JsonDeepMerger.cleanJsonObject(JSONObject(file.readText(Charsets.UTF_8)))
                            OutputStreamWriter(FileOutputStream(destFile), StandardCharsets.UTF_8).use {
                                it.write(clean.toString())
                            }
                        } catch (e: Exception) {
                            file.copyTo(destFile)
                        }
                        PackForgeLog.d("PackForge_Entity", "✅ Copiado: entity/${file.name}")
                    } else {
                        // FUSIONAR inteligentemente
                        try {
                            val base = JsonDeepMerger.cleanJsonObject(JSONObject(destFile.readText(Charsets.UTF_8)))
                            val merge = JsonDeepMerger.cleanJsonObject(JSONObject(file.readText(Charsets.UTF_8)))

                            // Fusionar recursivamente las definiciones de entidad
                            merge.keys().forEach { entityKey ->
                                val cleanKey = entityKey.trim()  // ⭐ QUITAR ESPACIOS
                                if (base.has(cleanKey)) {
                                    val baseVal = base.get(cleanKey)
                                    val mergeVal = merge.get(cleanKey)
                                    if (baseVal is JSONObject && mergeVal is JSONObject) {
                                        base.put(cleanKey, JsonDeepMerger.deepMerge(baseVal, mergeVal))
                                    } else {
                                        base.put(cleanKey, mergeVal)
                                    }
                                } else {
                                    base.put(cleanKey, merge.get(cleanKey))
                                }
                            }

                            OutputStreamWriter(FileOutputStream(destFile), StandardCharsets.UTF_8).use {
                                it.write(base.toString())
                            }
                            PackForgeLog.d("PackForge_Entity", "🔀 Fusionado: entity/${file.name}")
                        } catch (e: Exception) {
                            PackForgeLog.e("PackForge_Entity", "Error fusionando ${file.name}: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    // =====================================================================
    // 5. RENDER CONTROLLERS - RP/render_controllers/*.json (CRÍTICO para mobs)
    // =====================================================================
    fun mergeRenderControllers(rpDirs: List<File>, destDir: File) {
        val destDir2 = File(destDir, "render_controllers")
        destDir2.mkdirs()

        rpDirs.forEach { rpDir ->
            val rcDir = File(rpDir, "render_controllers")
            if (rcDir.exists()) {
                rcDir.listFiles()?.forEach { file ->
                    val destFile = File(destDir2, file.name)

                    if (!destFile.exists()) {
                        // Copiar LIMPIANDO espacios en claves/valores
                        try {
                            val clean = JsonDeepMerger.cleanJsonObject(JSONObject(file.readText(Charsets.UTF_8)))
                            OutputStreamWriter(FileOutputStream(destFile), StandardCharsets.UTF_8).use {
                                it.write(clean.toString())
                            }
                        } catch (e: Exception) {
                            file.copyTo(destFile)
                        }
                    } else {
                        try {
                            val base = JsonDeepMerger.cleanJsonObject(JSONObject(destFile.readText(Charsets.UTF_8)))
                            val merge = JsonDeepMerger.cleanJsonObject(JSONObject(file.readText(Charsets.UTF_8)))

                            // Fusionar render_controllers con deepMerge si ya existen
                            merge.optJSONObject("render_controllers")?.let { rcData ->
                                val baseRc = base.optJSONObject("render_controllers")
                                    ?: JSONObject().also { base.put("render_controllers", it) }
                                rcData.keys().forEach { key ->
                                    val cleanKey = key.trim()  // ⭐ QUITAR ESPACIOS
                                    if (baseRc.has(cleanKey)) {
                                        val baseVal = baseRc.get(cleanKey)
                                        val mergeVal = rcData.get(cleanKey)
                                        if (baseVal is JSONObject && mergeVal is JSONObject) {
                                            baseRc.put(cleanKey, JsonDeepMerger.deepMerge(baseVal, mergeVal))
                                        } else {
                                            baseRc.put(cleanKey, mergeVal)
                                        }
                                    } else {
                                        baseRc.put(cleanKey, rcData.get(cleanKey))
                                    }
                                }
                            }

                            OutputStreamWriter(FileOutputStream(destFile), StandardCharsets.UTF_8).use {
                                it.write(base.toString())
                            }
                        } catch (e: Exception) {
                            PackForgeLog.e("PackForge_RC", "Error: ${e.message}")
                        }
                    }
                }
            }
        }
        PackForgeLog.d("PackForge_RC", "✅ render_controllers fusionados")
    }

    // =====================================================================
    // 6. ANIMATIONS - RP/animations/ + RP/animation_controllers/ (CRÍTICO)
    // =====================================================================
    fun mergeAnimations(rpDirs: List<File>, destDir: File) {
        val dirsToCheck = listOf("animations", "animation_controllers")

        dirsToCheck.forEach { dirName ->
            val destDirAnim = File(destDir, dirName)
            destDirAnim.mkdirs()

            rpDirs.forEach { rpDir ->
                val srcDir = File(rpDir, dirName)
                if (srcDir.exists()) {
                    srcDir.listFiles()?.forEach { file ->
                        val destFile = File(destDirAnim, file.name)

                        if (!destFile.exists()) {
                            // Copiar LIMPIANDO espacios en claves/valores
                            try {
                                val clean = JsonDeepMerger.cleanJsonObject(JSONObject(file.readText(Charsets.UTF_8)))
                                OutputStreamWriter(FileOutputStream(destFile), StandardCharsets.UTF_8).use {
                                    it.write(clean.toString())
                                }
                            } catch (e: Exception) {
                                file.copyTo(destFile)
                            }
                        } else {
                            try {
                                val base = JsonDeepMerger.cleanJsonObject(JSONObject(destFile.readText(Charsets.UTF_8)))
                                val merge = JsonDeepMerger.cleanJsonObject(JSONObject(file.readText(Charsets.UTF_8)))

                                // Fusionar animations o animation_controllers de manera recursiva profunda
                                listOf("animations", "animation_controllers").forEach { key ->
                                    merge.optJSONObject(key)?.let { data ->
                                        val baseData = base.optJSONObject(key)
                                            ?: JSONObject().also { base.put(key, it) }
                                        data.keys().forEach { animKey ->
                                            val cleanAnimKey = animKey.trim()  // ⭐ QUITAR ESPACIOS
                                            if (baseData.has(cleanAnimKey)) {
                                                val baseVal = baseData.get(cleanAnimKey)
                                                val mergeVal = data.get(cleanAnimKey)
                                                if (baseVal is JSONObject && mergeVal is JSONObject) {
                                                    baseData.put(cleanAnimKey, JsonDeepMerger.deepMerge(baseVal, mergeVal))
                                                } else {
                                                    baseData.put(cleanAnimKey, mergeVal)
                                                }
                                            } else {
                                                baseData.put(cleanAnimKey, data.get(cleanAnimKey))
                                            }
                                        }
                                    }
                                }

                                OutputStreamWriter(FileOutputStream(destFile), StandardCharsets.UTF_8).use {
                                    it.write(base.toString())
                                }
                            } catch (e: Exception) {
                                PackForgeLog.e("PackForge_Anim", "Error ${file.name}: ${e.message}")
                            }
                        }
                    }
                }
            }
        }
        PackForgeLog.d("PackForge_Anim", "✅ Animaciones fusionadas")
    }

    // =====================================================================
    // 7. LANG FILES - Traducciones (CRÍTICO para "desconocido")
    // =====================================================================
    fun mergeLangFiles(allDirs: List<File>, destDir: File) {
        val translations = mutableMapOf<String, MutableMap<String, String>>()

        allDirs.forEach { dir ->
            listOf("texts", "RP/texts", "BP/texts").forEach { textsPath ->
                val textsDir = File(dir, textsPath)
                if (textsDir.exists()) {
                    textsDir.listFiles()?.filter { it.extension == "lang" }?.forEach { langFile ->
                        val lang = langFile.nameWithoutExtension
                        val map = translations.getOrPut(lang) { mutableMapOf() }

                        langFile.readLines(Charsets.UTF_8).forEach { line ->
                            if (line.contains("=") && !line.startsWith("#") && line.isNotBlank()) {
                                val parts = line.split("=", limit = 2)
                                if (parts.size == 2) map[parts[0].trim()] = parts[1].trim()
                            }
                        }
                    }
                }
            }
        }

        val destTextsDir = File(destDir, "texts")
        destTextsDir.mkdirs()

        translations.forEach { (lang, map) ->
            val file = File(destTextsDir, "$lang.lang")
            OutputStreamWriter(FileOutputStream(file), StandardCharsets.UTF_8).use {
                it.write(map.entries.joinToString("\n") { "${it.key}=${it.value}" })
            }
            PackForgeLog.d("PackForge_Lang", "✅ $lang.lang: ${map.size} claves")
        }

        // CRÍTICO: languages.json
        val languagesJson = JSONObject().apply {
            put("languages", JSONArray().apply {
                translations.keys.sorted().forEach { put(it) }
            })
        }
        val langJsonFile = File(destTextsDir, "languages.json")
        OutputStreamWriter(FileOutputStream(langJsonFile), StandardCharsets.UTF_8).use {
            it.write(languagesJson.toString())
        }
        PackForgeLog.d("PackForge_Lang", "✅ languages.json creado")
    }

    // =====================================================================
    // 8. SOUNDS.JSON - Sonidos de bloques/entidades
    // =====================================================================
    fun mergeSoundsJson(rpDirs: List<File>, destDir: File) {
        val merged = JSONObject()

        rpDirs.forEach { rpDir ->
            val file = File(rpDir, "sounds.json")
            if (file.exists()) {
                try {
                    val json = JsonDeepMerger.cleanJsonObject(JSONObject(file.readText(Charsets.UTF_8)))

                    // entity_sounds.entities
                    json.optJSONObject("entity_sounds")?.optJSONObject("entities")?.let { ent ->
                        val mergedEnt = merged.optJSONObject("entity_sounds")?.optJSONObject("entities")
                            ?: JSONObject().also {
                                merged.put("entity_sounds", JSONObject().put("entities", it))
                            }
                        ent.keys().forEach { k ->
                            val cleanK = k.trim()
                            if (!mergedEnt.has(cleanK)) mergedEnt.put(cleanK, ent.get(cleanK))
                        }
                    }

                    // block_sounds
                    json.optJSONObject("block_sounds")?.let { blocks ->
                        val mergedBlocks = merged.optJSONObject("block_sounds")
                            ?: JSONObject().also { merged.put("block_sounds", it) }
                        blocks.keys().forEach { k ->
                            val cleanK = k.trim()
                            if (!mergedBlocks.has(cleanK)) mergedBlocks.put(cleanK, blocks.get(cleanK))
                        }
                    }

                    // individual_event_sounds
                    json.optJSONObject("individual_event_sounds")?.let { events ->
                        val mergedEvents = merged.optJSONObject("individual_event_sounds")
                            ?: JSONObject().also { merged.put("individual_event_sounds", it) }
                        events.keys().forEach { k ->
                            val cleanK = k.trim()
                            if (!mergedEvents.has(cleanK)) mergedEvents.put(cleanK, events.get(cleanK))
                        }
                    }
                } catch (e: Exception) {
                    PackForgeLog.e("PackForge_Sounds", "Error: ${e.message}")
                }
            }
        }

        val destFile = File(destDir, "sounds.json")
        OutputStreamWriter(FileOutputStream(destFile), StandardCharsets.UTF_8).use {
            it.write(merged.toString())
        }
        PackForgeLog.d("PackForge_Sounds", "✅ sounds.json fusionado")
    }

    // =====================================================================
    // 9. GEOMETRY FILES - Geometrías 3D de bloques complejos (CRÍTICO)
    //     Fusiona models/**/*.geo.json deduplicando por identifier.
    //     Los bloques con geometría (enredaderas, vallas, cruces, plantas 3D)
    //     referencian estos archivos via minecraft:geometry en su BP definition.
    // =====================================================================
    fun mergeGeometryFiles(rpDirs: List<File>, destDir: File) {
        // Mapa: ruta relativa del .geo.json -> JSONObject fusionado
        val geoFiles = mutableMapOf<String, JSONObject>()
        var processedCount = 0

        rpDirs.forEach { rpDir ->
            rpDir.walkTopDown().forEach { file ->
                if (file.isFile && file.name.endsWith(".geo.json", ignoreCase = true)) {
                    val relativePath = file.relativeTo(rpDir).path
                    processedCount++
                    try {
                        val json = JsonDeepMerger.cleanJsonObject(JSONObject(file.readText(Charsets.UTF_8)))
                        val existing = geoFiles[relativePath]

                        if (existing == null) {
                            geoFiles[relativePath] = json
                            logFile { "Agregada geometria: $relativePath" }
                        } else {
                            // Fusionar deduplicando por identifier dentro de minecraft:geometry
                            mergeGeoJson(existing, json, relativePath)
                        }
                    } catch (e: Exception) {
                        PackForgeLog.e("PackForge_Geometry", "Error procesando $relativePath: ${e.message}")
                    }
                }
            }
        }

        // Escribir todos los .geo.json fusionados
        geoFiles.forEach { (relativePath, geoJson) ->
            val destFile = File(destDir, relativePath)
            destFile.parentFile?.mkdirs()
            OutputStreamWriter(FileOutputStream(destFile), StandardCharsets.UTF_8).use {
                it.write(geoJson.toString())
            }
            logFile { "geometria guardada: $relativePath" }
        }

        PackForgeLog.d("PackForge_Geometry", "Geometrias fusionadas: ${geoFiles.size} archivos .geo.json")
    }

    /**
     * Fusiona dos archivos de geometria con el mismo path relativo,
     * deduplicando las entradas de minecraft:geometry por description.identifier.
     */
    private fun mergeGeoJson(base: JSONObject, incoming: JSONObject, path: String) {
        val baseArr = base.optJSONArray("minecraft:geometry") ?: JSONArray()
        val incomingArr = incoming.optJSONArray("minecraft:geometry") ?: return

        // Coleccionar identifiers existentes
        val existingIdentifiers = mutableSetOf<String>()
        for (i in 0 until baseArr.length()) {
            val desc = baseArr.optJSONObject(i)?.optJSONObject("description")
            val id = desc?.optString("identifier")
            if (id != null) existingIdentifiers.add(id)
        }

        for (i in 0 until incomingArr.length()) {
            val geo = incomingArr.optJSONObject(i) ?: continue
            val id = geo.optJSONObject("description")?.optString("identifier") ?: continue

            if (!existingIdentifiers.contains(id)) {
                baseArr.put(geo)
                existingIdentifiers.add(id)
                logFile { "  + Geometria $id agregada en $path" }
            } else {
                PackForgeLog.w("PackForge_Geometry", "  Geometria duplicada $id en $path (manteniendo primera)")
            }
        }

        base.put("minecraft:geometry", baseArr)
    }

    // =====================================================================
    // 10. FLIPBOOK TEXTURES - Texturas animadas (agua, lava, algunos bloques)
    //     Fusiona textures/flipbook_textures.json deduplicando por flipbook_texture
    // =====================================================================
    fun mergeFlipbookTextures(rpDirs: List<File>, destDir: File) {
        val mergedArray = JSONArray()
        val seenKeys = mutableSetOf<String>()
        var processedCount = 0

        rpDirs.forEach { rpDir ->
            val flipbookFile = File(rpDir, "textures/flipbook_textures.json")
            if (flipbookFile.exists()) {
                try {
                    val json = JsonDeepMerger.cleanJsonObject(JSONObject(flipbookFile.readText(Charsets.UTF_8)))
                    processedCount++
                    json.optJSONArray("flipbook_textures")?.let { entries ->
                        for (i in 0 until entries.length()) {
                            val entry = entries.optJSONObject(i) ?: continue
                            val key = entry.optString("flipbook_texture")
                                .ifEmpty { entry.optString("atlas_tile") }
                                .trim()  // ⭐ QUITAR ESPACIOS
                            if (key.isEmpty() || seenKeys.add(key)) {
                                mergedArray.put(JsonDeepMerger.cleanJsonValue(entry))
                            } else {
                                PackForgeLog.w("PackForge_Flipbook", "Flipbook duplicado: $key (manteniendo primero)")
                            }
                        }
                    }
                } catch (e: Exception) {
                    PackForgeLog.e("PackForge_Flipbook", "Error procesando flipbook_textures.json: ${e.message}")
                }
            }
        }

        if (processedCount > 0) {
            val merged = JSONObject().apply {
                put("flipbook_textures", mergedArray)
            }
            val destFile = File(destDir, "textures/flipbook_textures.json")
            destFile.parentFile?.mkdirs()
            OutputStreamWriter(FileOutputStream(destFile), StandardCharsets.UTF_8).use {
                it.write(merged.toString())
            }
        }
        PackForgeLog.d("PackForge_Flipbook", "flipbook_textures.json fusionado desde $processedCount RPs con ${mergedArray.length()} animaciones")
    }

    // =====================================================================
    // 11. MATERIAL INSTANCES - Verifica que las texturas referenciadas por
    //     material_instances en los bloques del BP existan en terrain_texture.json.
    //     Los bloques con geometria compleja (enredaderas, hojas, glass) usan
    //     material_instances como: { "side": { "texture": "vine_tex", "render_method": "alpha_test" } }
    //     Si la textura no esta mapeada, se agrega y se copia el PNG desde los addons.
    // =====================================================================
    fun mergeMaterialInstances(
        bpDirs: List<File>,
        rpDirs: List<File>,
        mergedBpDir: File,
        mergedRpDir: File
    ) {
        val blocksDir = File(mergedBpDir, "blocks")
        if (!blocksDir.exists()) {
            PackForgeLog.d("PackForge_Materials", "No existe carpeta blocks en BP fusionado")
            return
        }

        // Cargar el terrain_texture.json fusionado (para saber que nombres estan mapeados)
        val terrainFile = File(mergedRpDir, "textures/terrain_texture.json")
        val terrainData = if (terrainFile.exists()) {
            try {
                JsonDeepMerger.cleanJsonObject(
                    JSONObject(terrainFile.readText(Charsets.UTF_8))
                        .optJSONObject("texture_data") ?: JSONObject()
                )
            } catch (e: Exception) {
                PackForgeLog.e("PackForge_Materials", "Error leyendo terrain_texture.json fusionado: ${e.message}")
                JSONObject()
            }
        } else {
            JSONObject()
        }

        // Mapa de nombres de textura faltantes -> encontrar y copiar
        val missingTextures = mutableMapOf<String, Boolean>()
        var addedCount = 0

        blocksDir.listFiles()?.filter { it.extension == "json" }?.forEach { blockFile ->
            try {
                val json = JsonDeepMerger.cleanJsonObject(JSONObject(blockFile.readText(Charsets.UTF_8)))
                val components = json.optJSONObject("components")
                val matInstances = components?.optJSONObject("minecraft:material_instances")?.optJSONObject("mappings")
                    ?: components?.optJSONObject("minecraft:material_instances")

                // Recorrer todas las instancias de material para extraer nombres de textura
                val textureNames = mutableSetOf<String>()
                collectTextureNamesFromMaterialInstances(matInstances, textureNames)

                // Tambien el bloque puede tener minecraft:material_instances plano (vanilla)
                components?.optJSONObject("minecraft:material_instances")?.keys()?.forEach { key ->
                    if (key != "mappings") {
                        components.optJSONObject("minecraft:material_instances")
                            ?.optJSONObject(key)?.optString("texture")
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { textureNames.add(it) }
                    }
                }

                textureNames.forEach { rawTextureName ->
                    val textureName = rawTextureName.trim()  // ⭐ QUITAR ESPACIOS
                    // Si la textura ya esta mapeada en terrain_texture, ok
                    if (terrainData.has(textureName)) {
                        return@forEach
                    }

                    // Buscar el PNG en los addons originales y copiarlo
                    val pngPath = "textures/blocks/$textureName.png"
                    val foundPng = rpDirs.firstNotNullOfOrNull { rpDir ->
                        val f = File(rpDir, pngPath)
                        if (f.exists()) f else null
                    } ?: rpDirs.firstNotNullOfOrNull { rpDir ->
                        rpDir.walkTopDown().find { it.isFile && it.name.equals("$textureName.png", ignoreCase = true) }
                    }

                    if (foundPng != null) {
                        // Copiar PNG al destino
                        val destPng = File(mergedRpDir, pngPath)
                        destPng.parentFile?.mkdirs()
                        foundPng.copyTo(destPng, overwrite = true)

                        // Agregar entrada al terrain_texture.json
                        if (!terrainData.has(textureName)) {
                            terrainData.put(
                                textureName,
                                JSONObject().put("textures", "textures/blocks/$textureName")
                            )
                            addedCount++
                            PackForgeLog.d("PackForge_Materials", "Textura material agregada: $textureName (copiada desde ${foundPng.relativeTo(foundPng.parentFile?.parentFile ?: foundPng)})")
                        }
                    } else {
                        PackForgeLog.w("PackForge_Materials", "Textura de material no encontrada: $textureName (bloque ${blockFile.name})")
                        missingTextures[textureName] = true
                    }
                }
            } catch (e: Exception) {
                PackForgeLog.e("PackForge_Materials", "Error procesando material_instances de ${blockFile.name}: ${e.message}")
            }
        }

        // Guardar terrain_texture.json actualizado
        if (addedCount > 0) {
            OutputStreamWriter(FileOutputStream(terrainFile), StandardCharsets.UTF_8).use {
                it.write(JSONObject().apply {
                    put("resource_pack_name", "PackForge")
                    put("texture_name", "atlas.terrain")
                    put("padding", 8)
                    put("num_mip_levels", 4)
                    put("texture_data", terrainData)
                }.toString())
            }
            // VALIDACIÓN tras el paso de material instances
            validateNoTrailingSpaces(terrainFile, "terrain_texture.json (tras material_instances)")
        }

        PackForgeLog.d("PackForge_Materials", "Material instances verificados: $addedCount texturas agregadas, ${missingTextures.size} faltantes")
    }

    // =====================================================================
    // 12. RECIPES - Fusión semántica de recetas de crafting/hornos/alquimia
    //     Bedrock usa archivos individuales por receta en recipes/
    //     Estructura: { "format_version": 1, "minecraft:recipe_shaped": { "description": { "identifier": "..." }, "tags": [...], "pattern": [...], "key": {...}, "result": {...} } }
    // =====================================================================
    fun mergeRecipes(bpDirs: List<File>, destDir: File) {
        val destRecipesDir = File(destDir, "recipes")
        if (!destRecipesDir.exists()) destRecipesDir.mkdirs()

        val seenRecipes = mutableMapOf<String, File>() // identifier -> archivo origen

        bpDirs.forEach { bpDir ->
            val recipesDir = File(bpDir, "recipes")
            if (recipesDir.exists()) {
                recipesDir.listFiles()?.filter { it.extension == "json" }?.forEach { file ->
                    try {
                        val json = JsonDeepMerger.cleanJsonObject(JSONObject(file.readText(Charsets.UTF_8)))
                        // Extraer el identifier de la receta
                        val recipeKey = json.keys().asSequence().firstOrNull { it.startsWith("minecraft:recipe_") }
                        val identifier = recipeKey?.let { json.getJSONObject(it).optJSONObject("description")?.optString("identifier") }
                            ?.takeIf { it.isNotBlank() }
                        
                        if (identifier != null) {
                            if (!seenRecipes.containsKey(identifier)) {
                                seenRecipes[identifier] = file
                                val destFile = File(destRecipesDir, file.name)
                                OutputStreamWriter(FileOutputStream(destFile), StandardCharsets.UTF_8).use {
                                    it.write(json.toString())
                                }
                                PackForgeLog.d("PackForge_Recipes", "✅ Receta agregada: $identifier (${file.name})")
                            } else {
                                // Colisión de receta: alias del identifier y conservar AMBAS
                                // (las recetas no se referencian por id desde los items, así
                                // que ambas variantes quedan craftables sin romper nada).
                                val token = Integer.toHexString(bpDir.name.hashCode()).takeLast(4).padStart(4, '0')
                                val newId = "${identifier}_pf$token"
                                val recipeKey = json.keys().asSequence()
                                    .firstOrNull { it.startsWith("minecraft:recipe_") }
                                recipeKey?.let { rk ->
                                    json.optJSONObject(rk)?.optJSONObject("description")?.put("identifier", newId)
                                }
                                val destFile = File(destRecipesDir, "${file.nameWithoutExtension}_pf$token.json")
                                OutputStreamWriter(FileOutputStream(destFile), StandardCharsets.UTF_8).use {
                                    it.write(json.toString())
                                }
                                PackForgeLog.d("PackForge_Recipes", "🔀 Receta duplicada con alias: $identifier → $newId")
                            }
                        } else {
                            // Sin identifier, copiar con nombre único
                            val destFile = File(destRecipesDir, "${file.nameWithoutExtension}_${bpDir.name.hashCode().toString(16)}.json")
                            OutputStreamWriter(FileOutputStream(destFile), StandardCharsets.UTF_8).use {
                                it.write(json.toString())
                            }
                        }
                    } catch (e: Exception) {
                        PackForgeLog.e("PackForge_Recipes", "Error procesando receta ${file.name}: ${e.message}")
                    }
                }
            }
        }
        PackForgeLog.d("PackForge_Recipes", "Recetas fusionadas: ${seenRecipes.size} únicas")
    }

    // =====================================================================
    // 13. LOOT TABLES - Fusión semántica de tablas de botín
    //     Estructura: { "pools": [ { "rolls": 1, "entries": [ { "type": "item", "name": "minecraft:diamond", "weight": 1, "functions": [...] } ] } ] }
    //     Fusiona pools por nombre de tabla (archivo), deduplicando entradas por item/identifier
    // =====================================================================
    fun mergeLootTables(bpDirs: List<File>, destDir: File) {
        val destLootDir = File(destDir, "loot_tables")
        if (!destLootDir.exists()) destLootDir.mkdirs()

        bpDirs.forEach { bpDir ->
            val lootDir = File(bpDir, "loot_tables")
            if (lootDir.exists()) {
                lootDir.walkTopDown().filter { it.isFile && it.extension == "json" }.forEach { file ->
                    val relativePath = file.relativeTo(lootDir).path
                    val destFile = File(destLootDir, relativePath)
                    destFile.parentFile?.mkdirs()

                    try {
                        val newJson = JsonDeepMerger.cleanJsonObject(JSONObject(file.readText(Charsets.UTF_8)))
                        
                        if (destFile.exists()) {
                            // Fusionar con loot table existente
                            val baseJson = JsonDeepMerger.cleanJsonObject(JSONObject(destFile.readText(Charsets.UTF_8)))
                            val merged = mergeLootTableObjects(baseJson, newJson)
                            OutputStreamWriter(FileOutputStream(destFile), StandardCharsets.UTF_8).use {
                                it.write(merged.toString())
                            }
                            PackForgeLog.d("PackForge_Loot", "🔀 Loot table fusionada: $relativePath")
                        } else {
                            // Nueva loot table
                            OutputStreamWriter(FileOutputStream(destFile), StandardCharsets.UTF_8).use {
                                it.write(newJson.toString())
                            }
                            PackForgeLog.d("PackForge_Loot", "✅ Loot table agregada: $relativePath")
                        }
                    } catch (e: Exception) {
                        PackForgeLog.e("PackForge_Loot", "Error fusionando loot table $relativePath: ${e.message}")
                        file.copyTo(destFile, overwrite = true)
                    }
                }
            }
        }
    }

    /** Fusiona dos objetos de loot table combinando sus pools y deduplicando entradas */
    private fun mergeLootTableObjects(base: JSONObject, merge: JSONObject): JSONObject {
        val result = JSONObject(base.toString())
        
        val basePools = result.optJSONArray("pools") ?: JSONArray().also { result.put("pools", it) }
        val mergePools = merge.optJSONArray("pools") ?: return result
        
        // Para cada pool entrante, buscar pool existente con mismo nombre o combinar
        for (i in 0 until mergePools.length()) {
            val mergePool = mergePools.optJSONObject(i) ?: continue
            val poolName = mergePool.optString("name", "pool_$i")
            
            // Buscar pool base con mismo nombre
            var basePoolIndex = -1
            for (j in 0 until basePools.length()) {
                if (basePools.optJSONObject(j)?.optString("name", "pool_$j") == poolName) {
                    basePoolIndex = j
                    break
                }
            }
            
            if (basePoolIndex >= 0) {
                // Fusionar entries del pool
                val basePool = basePools.getJSONObject(basePoolIndex)
                val mergedPool = mergeLootPools(basePool, mergePool)
                basePools.put(basePoolIndex, mergedPool)
            } else {
                // Pool nuevo
                basePools.put(mergePool)
            }
        }
        
        return result
    }

    /** Fusiona dos pools de loot table combinando entries y deduplicando por item/name */
    private fun mergeLootPools(base: JSONObject, merge: JSONObject): JSONObject {
        val result = JSONObject(base.toString())
        
        val baseEntries = result.optJSONArray("entries") ?: JSONArray().also { result.put("entries", it) }
        val mergeEntries = merge.optJSONArray("entries") ?: return result
        
        val seenEntries = mutableSetOf<String>()
        
        // Registrar entries existentes
        for (i in 0 until baseEntries.length()) {
            val entry = baseEntries.optJSONObject(i) ?: continue
            val key = entry.optString("name", "") 
                .ifEmpty { entry.optString("type", "") }
                .ifEmpty { entry.toString() }
            seenEntries.add(key)
        }
        
        // Agregar entries nuevos no duplicados
        for (i in 0 until mergeEntries.length()) {
            val entry = mergeEntries.optJSONObject(i) ?: continue
            val key = entry.optString("name", "")
                .ifEmpty { entry.optString("type", "") }
                .ifEmpty { entry.toString() }
            if (!seenEntries.contains(key)) {
                baseEntries.put(entry)
                seenEntries.add(key)
            } else {
                PackForgeLog.d("PackForge_Loot", "  Entrada loot duplicada ignorada: $key")
            }
        }
        
        // Mantener otros campos del pool merge (rolls, conditions, etc.) si son más generosos
        merge.keys().forEach { key ->
            if (key != "entries" && key != "name") {
                val mergeVal = merge.get(key)
                val baseVal = result.opt(key)
                if (baseVal == null || (mergeVal is Int && baseVal is Int && mergeVal > baseVal)) {
                    result.put(key, mergeVal)
                }
            }
        }
        
        return result
    }

    // =====================================================================
    // 14. PLAYER.ENTITY.JSON - Fusión profunda del jugador entre addons.
    //     Base = el addon con manifest de versión MÁS ALTA; los demás se
    //     fusionan en orden ascendente para que el de mayor versión gane las
    //     colisiones escalares (deepMerge: el último en fusionar gana).
    //     Colisiones numéricas bajo "components" se registran como conflictos
    //     HIGH para revisión manual (ej. dos addons cambiando minecraft:movement).
    // =====================================================================
    fun mergePlayerEntity(bpDirs: List<File>, mergedBpDir: File) {
        data class Candidate(val json: JSONObject, val version: List<Int>, val ownerName: String)

        val candidates = mutableListOf<Candidate>()
        bpDirs.forEach { bpDir ->
            val playerFile = sequenceOf(
                File(bpDir, "player.entity.json"),
                File(bpDir, "entities/player.entity.json")
            ).firstOrNull { it.exists() } ?: return@forEach
            try {
                val json = JsonDeepMerger.cleanJsonObject(JSONObject(playerFile.readText(Charsets.UTF_8)))
                val id = json.optJSONObject("minecraft:entity")?.optJSONObject("description")
                    ?.optString("identifier").orEmpty()
                if (id != "minecraft:player") return@forEach // solo overrides reales del jugador
                val manifest = File(bpDir, "manifest.json")
                var version = listOf(1, 0, 0)
                if (manifest.exists()) {
                    try {
                        val mev = JSONObject(manifest.readText(Charsets.UTF_8))
                            .optJSONObject("header")?.optJSONArray("version")
                        if (mev != null && mev.length() >= 3) {
                            version = (0 until mev.length()).map { mev.optInt(it, 0) }
                        }
                    } catch (_: Exception) {}
                }
                candidates.add(Candidate(json, version, bpDir.name))
            } catch (e: Exception) {
                PackForgeLog.e("PackForge_Player", "Error leyendo ${playerFile.name}: ${e.message}")
            }
        }

        if (candidates.isEmpty()) return

        // Orden ascendente por versión: el más alto se procesa al final (gana).
        val ordered = candidates.sortedBy { c ->
            c.version.getOrElse(0) { 0 } * 1_000_000 +
                c.version.getOrElse(1) { 0 } * 1_000 +
                c.version.getOrElse(2) { 0 }
        }
        var merged = JSONObject(ordered.first().json.toString())
        val contributors = mutableListOf(ordered.first().ownerName)

        for (i in 1 until ordered.size) {
            val incoming = ordered[i]
            contributors.add(incoming.ownerName)
            registerNumericConflicts(merged, incoming.json, "components", incoming.ownerName)
            merged = JsonDeepMerger.deepMerge(merged, incoming.json)
        }

        val destFile = File(mergedBpDir, "player.entity.json")
        OutputStreamWriter(FileOutputStream(destFile), StandardCharsets.UTF_8).use {
            it.write(merged.toString())
        }
        ConflictRegistry.logConflict(
            severity = com.packforge.app.domain.model.ConflictSeverity.MEDIUM,
            type = "PLAYER_JSON_MERGED",
            file = "player.entity.json",
            addon1 = contributors.firstOrNull() ?: "?",
            addon2 = contributors.lastOrNull() ?: "?",
            description = "Fusión profunda de player.entity.json entre ${contributors.size} addons " +
                "(${contributors.joinToString()}); base por versión más alta."
        )
        PackForgeLog.d("PackForge_Player", "✅ player.entity.json fusionado desde ${candidates.size} addons")
    }

    /** Registra conflictos HIGH cuando ambos lados definen números distintos en la misma hoja. */
    private fun registerNumericConflicts(base: Any, incoming: Any, path: String, owner: String) {
        when {
            base is JSONObject && incoming is JSONObject -> {
                incoming.keys().forEach { key ->
                    base.opt(key)?.let { registerNumericConflicts(it, incoming.get(key), "$path.$key", owner) }
                }
            }
            base is JSONArray && incoming is JSONArray -> Unit // arrays: deepMerge concatena
            base is Number && incoming is Number -> {
                if (base.toDouble() != incoming.toDouble()) {
                    ConflictRegistry.logConflict(
                        severity = com.packforge.app.domain.model.ConflictSeverity.HIGH,
                        type = "PLAYER_NUMERIC_CONFLICT",
                        file = "player.entity.json",
                        addon1 = "base(mayorVersión)",
                        addon2 = owner,
                        description = "'$path': ${base} → ${incoming}. Revisa manualmente si el comportamiento no es el esperado."
                    )
                }
            }
        }
    }

    // =====================================================================
    // 15. SOUND_DEFINITIONS.JSON - Fusiona definiciones de sonido del RP.
    //     Clave duplicada con contenido distinto → la clave del addon posterior
    //     se renombra a `<clave>_pf<hex4>` y SE DEVUELVE el mapa de renombres
    //     para actualizar referencias en sounds.json / entidades (applySoundKeyRenames).
    // =====================================================================
    fun mergeSoundDefinitions(rpDirs: List<File>, destDir: File): Map<String, String> {
        val renames = LinkedHashMap<String, String>()
        val mergedDefs = JSONObject()
        var formatVersion = 1

        rpDirs.forEach { rpDir ->
            val file = File(rpDir, "sound_definitions.json")
            if (!file.exists()) return@forEach
            try {
                val token = Integer.toHexString(rpDir.name.hashCode()).takeLast(4).padStart(4, '0')
                val json = JsonDeepMerger.cleanJsonObject(JSONObject(file.readText(Charsets.UTF_8)))
                formatVersion = maxOf(formatVersion, json.optInt("format_version", 1))
                json.keys().forEach { key ->
                    if (key == "format_version") return@forEach
                    val value = json.get(key)
                    if (!mergedDefs.has(key)) {
                        mergedDefs.put(key, value)
                    } else {
                        // Duplicada: si es idéntica, dedupe; si difiere, alias.
                        if (mergedDefs.get(key).toString() != value.toString()) {
                            var newKey = "${key}_pf$token"
                            while (mergedDefs.has(newKey)) newKey += "_x"
                            mergedDefs.put(newKey, value)
                            renames[key] = newKey
                            PackForgeLog.d("PackForge_SoundDef", "🔊 Definición duplicada '$key' → '$newKey'")
                        }
                    }
                }
            } catch (e: Exception) {
                PackForgeLog.e("PackForge_SoundDef", "Error en ${rpDir.name}: ${e.message}")
            }
        }

        if (mergedDefs.length() == 0) return renames

        val output = JSONObject().apply {
            put("format_version", formatVersion)
            mergedDefs.keys().forEach { put(it, mergedDefs.get(it)) }
        }
        val destFile = File(destDir, "sound_definitions.json")
        OutputStreamWriter(FileOutputStream(destFile), StandardCharsets.UTF_8).use { it.write(output.toString()) }
        PackForgeLog.d("PackForge_SoundDef", "✅ sound_definitions.json: ${mergedDefs.length()} definiciones, ${renames.size} aliases")
        return renames
    }

    /**
     * Aplica los renombres de claves de sound_definitions sobre TODOS los JSON
     * del pack fusionado (sounds.json del RP y entidades/eventos del BP).
     * Reemplazo por VALOR EXACTO: seguro contra subcadenas.
     */
    fun applySoundKeyRenames(mergedRpDir: File, mergedBpDir: File, renames: Map<String, String>) {
        if (renames.isEmpty()) return
        listOf(mergedRpDir, mergedBpDir).forEach { root ->
            if (!root.isDirectory) return@forEach
            root.walkTopDown().filter { it.isFile && it.extension.equals("json", true) }.forEach { file ->
                try {
                    val text = file.readText(Charsets.UTF_8)
                    if (renames.keys.any { text.contains("\"$it\"") }) {
                        val json = JSONObject(text)
                        if (ResourcePathRegistry.applyRenames(json, renames)) {
                            OutputStreamWriter(FileOutputStream(file), StandardCharsets.UTF_8).use {
                                it.write(json.toString())
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        PackForgeLog.d("PackForge_SoundDef", "🔁 Referencias de sonido actualizadas: ${renames.size} claves")
    }

    // =====================================================================
    // 16. PARTICLE EFFECTS - particles/*.json declaran description.identifier.
    //     Colisión de identifier → se renombra (y también el archivo físico,
    //     inocuo porque el binding es por identifier) y se actualiza cualquier
    //     referencia exacta al identificador viejo dentro del RP/BP fusionados.
    // =====================================================================
    fun mergeParticles(rpDirs: List<File>, mergedRpDir: File, mergedBpDir: File) {
        val seen = mutableMapOf<String, String>() // identifier → token usado
        var added = 0

        rpDirs.forEach { rpDir ->
            val particlesDir = File(rpDir, "particles")
            if (!particlesDir.isDirectory) return@forEach
            val token = Integer.toHexString(rpDir.name.hashCode()).takeLast(4).padStart(4, '0')

            particlesDir.listFiles()?.filter { it.extension.equals("json", true) }?.forEach { file ->
                try {
                    val json = JsonDeepMerger.cleanJsonObject(JSONObject(file.readText(Charsets.UTF_8)))
                    val desc = json.optJSONObject("particle_effect")?.optJSONObject("description")
                    val id = desc?.optString("identifier").orEmpty()

                    var finalId = id
                    if (id.isNotEmpty() && !id.startsWith("minecraft:")) {
                        if (seen.containsKey(id)) {
                            finalId = "${id}_pf$token"
                            if (desc != null) desc.put("identifier", finalId)
                            rewriteParticleRefs(listOf(mergedRpDir, mergedBpDir), id, finalId)
                            PackForgeLog.d("PackForge_Particles", "✨ Partícula duplicada '$id' → '$finalId'")
                        } else {
                            seen[id] = token
                        }
                    }

                    val destFile = File(File(mergedRpDir, "particles"), file.name)
                    destFile.parentFile?.mkdirs()
                    OutputStreamWriter(FileOutputStream(destFile), StandardCharsets.UTF_8).use { it.write(json.toString()) }
                    added++
                } catch (e: Exception) {
                    PackForgeLog.e("PackForge_Particles", "Error en ${file.name}: ${e.message}")
                }
            }
        }
        if (added > 0) PackForgeLog.d("PackForge_Particles", "✅ $added efectos de partículas fusionados")
    }

    private fun rewriteParticleRefs(roots: List<File>, oldId: String, newId: String) {
        roots.forEach { root ->
            if (!root.isDirectory) return@forEach
            root.walkTopDown().filter { it.isFile && it.extension.equals("json", true) }.forEach { file ->
                try {
                    val text = file.readText(Charsets.UTF_8)
                    if (text.contains("\"$oldId\"")) {
                        val json = JSONObject(text)
                        val probe = JSONObject(json.toString())
                        ResourcePathRegistry.applyRenames(probe, mapOf(oldId to newId))
                        OutputStreamWriter(FileOutputStream(file), StandardCharsets.UTF_8).use { it.write(probe.toString()) }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    // =====================================================================
    // VALIDADOR POST-FUSIÓN: verifica que los archivos JSON críticos no
    // tengan espacios al final en claves (causa de bloques "?" y "desconocido")
    // =====================================================================
    fun validateNoTrailingSpaces(file: File, fileName: String): Boolean {
        if (!file.exists()) {
            PackForgeLog.w("PackForge_Validate", "Archivo no existe para validar: $fileName")
            return true
        }
        return try {
            val json = JsonDeepMerger.cleanJsonObject(JSONObject(file.readText(Charsets.UTF_8)))
            var hasSpaces = false

            lateinit var checkObject: (JSONObject, String) -> Unit
            lateinit var checkArray: (JSONArray, String) -> Unit

            checkObject = { obj, path ->
                obj.keys().forEach { key ->
                    if (key != key.trim()) {
                        PackForgeLog.e("PackForge_Validate", "❌ ESPACIO en clave: '$key' en $path")
                        hasSpaces = true
                    }
                    val value = obj.get(key)
                    when (value) {
                        is JSONObject -> checkObject(value, "$path.$key")
                        is JSONArray -> checkArray(value, "$path.$key")
                        is String -> {
                            if (value != value.trim() && value.isNotBlank()) {
                                PackForgeLog.w("PackForge_Validate", "⚠️ Espacio en valor: '$value' en $path.$key")
                            }
                        }
                        else -> {}
                    }
                }
            }

            checkArray = { arr, path ->
                for (i in 0 until arr.length()) {
                    when (val v = arr.get(i)) {
                        is JSONObject -> checkObject(v, "$path[$i]")
                        is JSONArray -> checkArray(v, "$path[$i]")
                        else -> {}
                    }
                }
            }

            checkObject(json, fileName)

            if (!hasSpaces) {
                PackForgeLog.d("PackForge_Validate", "✅ $fileName sin espacios en claves")
            }
            !hasSpaces
        } catch (e: Exception) {
            PackForgeLog.e("PackForge_Validate", "Error validando $fileName: ${e.message}")
            true
        }
    }

    /**
     * Recorre recursivamente un objeto de material_instances extrayendo los nombres
     * de textura de cada instancia (estructura "mappings": { "side": { "texture": "x" } }).
     */
    private fun collectTextureNamesFromMaterialInstances(
        obj: JSONObject?,
        output: MutableSet<String>
    ) {
        if (obj == null) return
        obj.keys().forEach { key ->
            val value = obj.opt(key)
            when (value) {
                is JSONObject -> {
                    val texture = value.optString("texture")
                    if (texture.isNotEmpty()) {
                        output.add(texture)
                    }
                    // Recursivo por si hay mappings anidados
                    collectTextureNamesFromMaterialInstances(value, output)
                }
            }
        }
    }
}