package com.packforge.app.domain.engine

import com.packforge.app.util.PackForgeLog
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
            it.write(merged.toString(2))
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
            it.write(merged.toString(2))
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
            it.write(merged.toString(2))
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
                                it.write(clean.toString(2))
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

                            // Fusionar cada definición de entidad (claves limpias)
                            merge.keys().forEach { entityKey ->
                                val cleanKey = entityKey.trim()  // ⭐ QUITAR ESPACIOS
                                if (!base.has(cleanKey)) {
                                    base.put(cleanKey, merge.get(cleanKey))
                                }
                            }

                            OutputStreamWriter(FileOutputStream(destFile), StandardCharsets.UTF_8).use {
                                it.write(base.toString(2))
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
                                it.write(clean.toString(2))
                            }
                        } catch (e: Exception) {
                            file.copyTo(destFile)
                        }
                    } else {
                        try {
                            val base = JsonDeepMerger.cleanJsonObject(JSONObject(destFile.readText(Charsets.UTF_8)))
                            val merge = JsonDeepMerger.cleanJsonObject(JSONObject(file.readText(Charsets.UTF_8)))

                            // Fusionar render_controllers
                            merge.optJSONObject("render_controllers")?.let { rcData ->
                                val baseRc = base.optJSONObject("render_controllers")
                                    ?: JSONObject().also { base.put("render_controllers", it) }
                                rcData.keys().forEach { key ->
                                    val cleanKey = key.trim()  // ⭐ QUITAR ESPACIOS
                                    if (!baseRc.has(cleanKey)) baseRc.put(cleanKey, rcData.get(cleanKey))
                                }
                            }

                            OutputStreamWriter(FileOutputStream(destFile), StandardCharsets.UTF_8).use {
                                it.write(base.toString(2))
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
                                    it.write(clean.toString(2))
                                }
                            } catch (e: Exception) {
                                file.copyTo(destFile)
                            }
                        } else {
                            try {
                                val base = JsonDeepMerger.cleanJsonObject(JSONObject(destFile.readText(Charsets.UTF_8)))
                                val merge = JsonDeepMerger.cleanJsonObject(JSONObject(file.readText(Charsets.UTF_8)))

                                // Fusionar animations o animation_controllers
                                listOf("animations", "animation_controllers").forEach { key ->
                                    merge.optJSONObject(key)?.let { data ->
                                        val baseData = base.optJSONObject(key)
                                            ?: JSONObject().also { base.put(key, it) }
                                        data.keys().forEach { animKey ->
                                            val cleanAnimKey = animKey.trim()  // ⭐ QUITAR ESPACIOS
                                            if (!baseData.has(cleanAnimKey)) {
                                                baseData.put(cleanAnimKey, data.get(cleanAnimKey))
                                            }
                                        }
                                    }
                                }

                                OutputStreamWriter(FileOutputStream(destFile), StandardCharsets.UTF_8).use {
                                    it.write(base.toString(2))
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
            it.write(languagesJson.toString(2))
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
            it.write(merged.toString(2))
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
                            PackForgeLog.d("PackForge_Geometry", "Agregada geometria: $relativePath")
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
                it.write(geoJson.toString(2))
            }
            PackForgeLog.d("PackForge_Geometry", "geometria guardada: $relativePath")
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
                PackForgeLog.d("PackForge_Geometry", "  + Geometria $id agregada en $path")
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
                it.write(merged.toString(2))
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
                }.toString(2))
            }
            // VALIDACIÓN tras el paso de material instances
            validateNoTrailingSpaces(terrainFile, "terrain_texture.json (tras material_instances)")
        }

        PackForgeLog.d("PackForge_Materials", "Material instances verificados: $addedCount texturas agregadas, ${missingTextures.size} faltantes")
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