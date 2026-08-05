package com.packforge.app.domain.engine

import com.packforge.app.util.PackForgeLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * Fusiona los archivos CRITICOS que Minecraft Bedrock necesita para mostrar texturas y nombres correctamente.
 *
 * 1. textures/terrain_texture.json - Mapea nombres a texturas de BLOQUES
 * 2. textures/item_texture.json - Mapea nombres a texturas de ITEMS
 * 3. blocks.json (raiz del RP) - Define renderizado de bloques
 * 4. texts/*.lang + languages.json - Traducciones de nombres (CRITICO para "desconocido")
 * 5. models/**/*.geo.json - GEOMETRIAS 3D de bloques complejos (enredaderas, vallas, cruces, plantas)
 * 6. textures/flipbook_textures.json - Texturas animadas
 * 7. material_instances del BP vs terrain_texture.json - Texturas referenciadas por materiales
 *
 * SIN estos archivos, aunque las texturas .png existan, Minecraft NO SABE que textura mostrar
 * y muestra "?" en bloques y "desconocido" en items. Los bloques con geometria compleja
 * (enredaderas, vallas, plantas, modelos 3D) ademas necesitan sus .geo.json y material_instances.
 */
object BedrockCriticalFilesMerger2 {
    private const val TAG_TERRAIN = "PackForge_Terrain"
    private const val TAG_ITEM = "PackForge_Item"
    private const val TAG_BLOCKS = "PackForge_Blocks"
    private const val TAG_LANG = "PackForge_Lang"
    private const val TAG_GEO = "PackForge_Geometry"
    private const val TAG_FLIP = "PackForge_Flipbook"
    private const val TAG_MAT = "PackForge_Materials"

    // =====================================================================
    // 1. TERRAIN TEXTURE - Mapea bloques a texturas
    // =====================================================================
    fun mergeTerrainTexture(rpDirs: List<File>, destDir: File) {
        val mergedTextureData = JSONObject()
        var mergedPadding = 8
        var mergedMipLevels = 4
        var processedCount = 0

        rpDirs.forEach { rpDir ->
            val terrainFile = File(rpDir, "textures/terrain_texture.json")
            if (terrainFile.exists()) {
                try {
                    val json = JSONObject(terrainFile.readText(StandardCharsets.UTF_8))
                    processedCount++

                    json.optJSONObject("texture_data")?.let { textureData ->
                        textureData.keys().forEach { blockName ->
                            if (!mergedTextureData.has(blockName)) {
                                mergedTextureData.put(blockName, textureData.get(blockName))
                                PackForgeLog.d(TAG_TERRAIN, "Agregado bloque: $blockName")
                            } else {
                                PackForgeLog.w(TAG_TERRAIN, "Bloque duplicado: $blockName (manteniendo primero)")
                            }
                        }
                    }

                    json.optInt("padding", 8).let { if (it > mergedPadding) mergedPadding = it }
                    json.optInt("num_mip_levels", 4).let { if (it > mergedMipLevels) mergedMipLevels = it }
                } catch (e: Exception) {
                    PackForgeLog.e(TAG_TERRAIN, "Error procesando terrain_texture.json: ${e.message}")
                }
            }
        }

        val merged = JSONObject().apply {
            put("resource_pack_name", "PackForge")
            put("texture_name", "atlas.terrain")
            put("padding", mergedPadding)
            put("num_mip_levels", mergedMipLevels)
            put("texture_data", mergedTextureData)
        }

        val destFile = File(destDir, "textures/terrain_texture.json")
        destFile.parentFile?.mkdirs()
        writeJson(destFile, merged)
        PackForgeLog.d(TAG_TERRAIN, "terrain_texture.json fusionado desde $processedCount RPs con ${mergedTextureData.length()} bloques")
    }

    // =====================================================================
    // 2. ITEM TEXTURE - Mapea items a texturas
    // =====================================================================
    fun mergeItemTexture(rpDirs: List<File>, destDir: File) {
        val mergedTextureData = JSONObject()
        var processedCount = 0

        rpDirs.forEach { rpDir ->
            val itemFile = File(rpDir, "textures/item_texture.json")
            if (itemFile.exists()) {
                try {
                    val json = JSONObject(itemFile.readText(StandardCharsets.UTF_8))
                    processedCount++

                    json.optJSONObject("texture_data")?.let { textureData ->
                        textureData.keys().forEach { itemName ->
                            if (!mergedTextureData.has(itemName)) {
                                mergedTextureData.put(itemName, textureData.get(itemName))
                                PackForgeLog.d(TAG_ITEM, "Agregado item: $itemName")
                            } else {
                                PackForgeLog.w(TAG_ITEM, "Item duplicado: $itemName (manteniendo primero)")
                            }
                        }
                    }
                } catch (e: Exception) {
                    PackForgeLog.e(TAG_ITEM, "Error procesando item_texture.json: ${e.message}")
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
        writeJson(destFile, merged)
        PackForgeLog.d(TAG_ITEM, "item_texture.json fusionado desde $processedCount RPs con ${mergedTextureData.length()} items")
    }

    // =====================================================================
    // 3. BLOCKS.JSON - Define renderizado de bloques (mejorado: conserva
    //    el format_version mas alto entre los addons, string o array)
    // =====================================================================
    fun mergeBlocksJson(rpDirs: List<File>, destDir: File) {
        val merged = JSONObject()
        var bestFormatVersion: Any? = null
        var processedCount = 0

        rpDirs.forEach { rpDir ->
            val blocksFile = File(rpDir, "blocks.json")
            if (blocksFile.exists()) {
                try {
                    val json = JSONObject(blocksFile.readText(StandardCharsets.UTF_8))
                    processedCount++

                    // Conservar el format_version mas alto
                    if (json.has("format_version")) {
                        val candidate = json.get("format_version")
                        if (compareFormatVersion(candidate, bestFormatVersion) > 0) {
                            bestFormatVersion = candidate
                        }
                    }

                    json.keys().forEach { key ->
                        if (key != "format_version" && !merged.has(key)) {
                            merged.put(key, json.get(key))
                            PackForgeLog.d(TAG_BLOCKS, "Agregado bloque def: $key")
                        } else if (key != "format_version") {
                            PackForgeLog.w(TAG_BLOCKS, "Bloque duplicado: $key (manteniendo primero)")
                        }
                    }
                } catch (e: Exception) {
                    PackForgeLog.e(TAG_BLOCKS, "Error procesando blocks.json: ${e.message}")
                }
            }
        }

        // Escribir format_version: el mas alto encontrado, o [1,1,0] si ninguno
        if (bestFormatVersion != null) {
            merged.put("format_version", bestFormatVersion)
        } else {
            merged.put("format_version", JSONArray().apply { put(1); put(1); put(0) })
        }

        val destFile = File(destDir, "blocks.json")
        writeJson(destFile, merged)
        PackForgeLog.d(TAG_BLOCKS, "blocks.json fusionado desde $processedCount RPs con ${merged.length() - 1} definiciones (format_version=$bestFormatVersion)")
    }

    /**
     * Compara dos format_version (pueden ser string "1.16.0" o array [1,16,0]).
     * Devuelve >0 si a > b, <0 si a < b, 0 si iguales o no comparables.
     */
    private fun compareFormatVersion(a: Any?, b: Any?): Int {
        val va = parseFormatVersion(a) ?: return 0
        val vb = parseFormatVersion(b) ?: return 0
        for (i in 0 until maxOf(va.size, vb.size)) {
            val ca = va.getOrElse(i) { 0 }
            val cb = vb.getOrElse(i) { 0 }
            if (ca != cb) return ca - cb
        }
        return 0
    }

    private fun parseFormatVersion(value: Any?): List<Int>? {
        return when (value) {
            is String -> value.trim().split(".").mapNotNull { it.toIntOrNull() }
            is JSONArray -> {
                val result = mutableListOf<Int>()
                for (i in 0 until value.length()) {
                    result.add(value.optInt(i, 0))
                }
                result
            }
            is Int -> listOf(value)
            else -> null
        }
    }

    // =====================================================================
    // 4. LANG FILES - Traducciones (CRITICO para "desconocido")
    // =====================================================================
    fun mergeLangFiles(addonDirs: List<File>, destDir: File) {
        val langMap = mutableMapOf<String, MutableMap<String, String>>()
        var totalFilesProcessed = 0

        addonDirs.forEach { addonDir ->
            // Buscar en todas las ubicaciones posibles de texts
            listOf(
                "texts",
                "BP/texts",
                "RP/texts",
                "behavior_pack/texts",
                "resource_pack/texts",
                "behavior_packs/texts",
                "resource_packs/texts"
            ).forEach { textsPath ->
                val textsDir = File(addonDir, textsPath)
                if (textsDir.exists()) {
                    textsDir.listFiles()?.filter { it.extension == "lang" }?.forEach { langFile ->
                        val langName = langFile.nameWithoutExtension
                        val translations = langMap.getOrPut(langName) { mutableMapOf() }
                        var linesProcessed = 0

                        langFile.readLines(StandardCharsets.UTF_8).forEach { line ->
                            val trimmed = line.trim()
                            if (trimmed.contains("=") && !trimmed.startsWith("#") && trimmed.isNotBlank()) {
                                val parts = trimmed.split("=", limit = 2)
                                if (parts.size == 2) {
                                    val key = parts[0].trim()
                                    val value = parts[1].trim()
                                    if (key.isNotEmpty() && value.isNotEmpty()) {
                                        translations[key] = value
                                        linesProcessed++
                                    }
                                }
                            }
                        }

                        totalFilesProcessed++
                        PackForgeLog.d(TAG_LANG, "Procesado ${langFile.name}: $linesProcessed claves (total en $langName: ${translations.size})")
                    }
                }
            }
        }

        val destTextsDir = File(destDir, "texts")
        destTextsDir.mkdirs()

        langMap.forEach { (langName, translations) ->
            val langFile = File(destTextsDir, "$langName.lang")
            val content = translations.entries.joinToString("\n") { "${it.key}=${it.value}" }
            writeText(langFile, content)
            PackForgeLog.d(TAG_LANG, "$langName.lang fusionado: ${translations.size} claves")
        }

        // CRITICO: languages.json DEBE existir para que Minecraft detecte los idiomas
        val languagesFile = File(destTextsDir, "languages.json")
        val languagesArray = JSONArray()
        langMap.keys.sorted().forEach { languagesArray.put(it) }

        val languagesJson = JSONObject().apply {
            put("languages", languagesArray)
        }
        writeJson(languagesFile, languagesJson)
        PackForgeLog.d(TAG_LANG, "languages.json creado con idiomas: ${langMap.keys.joinToString(", ")}")
        PackForgeLog.d(TAG_LANG, "Total archivos .lang procesados: $totalFilesProcessed, idiomas unicos: ${langMap.size}")
    }

    // =====================================================================
    // 5. GEOMETRY FILES - Geometrias 3D de bloques complejos
    //    Fusiona models/**/*.geo.json deduplicando por identifier.
    //    Los bloques con geometria (enredaderas, vallas, cruces, plantas 3D)
    //    referencian estos archivos via minecraft:geometry en su BP definition.
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
                        val json = JSONObject(file.readText(StandardCharsets.UTF_8))
                        val existing = geoFiles[relativePath]

                        if (existing == null) {
                            geoFiles[relativePath] = json
                            PackForgeLog.d(TAG_GEO, "Agregada geometria: $relativePath")
                        } else {
                            // Fusionar deduplicando por identifier dentro de minecraft:geometry
                            mergeGeoJson(existing, json, relativePath)
                        }
                    } catch (e: Exception) {
                        PackForgeLog.e(TAG_GEO, "Error procesando $relativePath: ${e.message}")
                    }
                }
            }
        }

        // Escribir todos los .geo.json fusionados
        geoFiles.forEach { (relativePath, geoJson) ->
            val destFile = File(destDir, relativePath)
            destFile.parentFile?.mkdirs()
            writeJson(destFile, geoJson)
            PackForgeLog.d(TAG_GEO, "geometria guardada: $relativePath")
        }

        PackForgeLog.d(TAG_GEO, "Geometrias fusionadas: ${geoFiles.size} archivos .geo.json")
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
                PackForgeLog.d(TAG_GEO, "  + Geometria $id agregada en $path")
            } else {
                PackForgeLog.w(TAG_GEO, "  Geometria duplicada $id en $path (manteniendo primera)")
            }
        }

        base.put("minecraft:geometry", baseArr)
    }

    // =====================================================================
    // 6. FLIPBOOK TEXTURES - Texturas animadas (agua, lava, algunos bloques)
    //    Fusiona textures/flipbook_textures.json deduplicando por flipbook_texture
    // =====================================================================
    fun mergeFlipbookTextures(rpDirs: List<File>, destDir: File) {
        val mergedArray = JSONArray()
        val seenKeys = mutableSetOf<String>()
        var processedCount = 0

        rpDirs.forEach { rpDir ->
            val flipbookFile = File(rpDir, "textures/flipbook_textures.json")
            if (flipbookFile.exists()) {
                try {
                    val json = JSONObject(flipbookFile.readText(StandardCharsets.UTF_8))
                    processedCount++
                    json.optJSONArray("flipbook_textures")?.let { entries ->
                        for (i in 0 until entries.length()) {
                            val entry = entries.optJSONObject(i) ?: continue
                            val key = entry.optString("flipbook_texture")
                                .ifEmpty { entry.optString("atlas_tile") }
                            if (key.isEmpty() || seenKeys.add(key)) {
                                mergedArray.put(entry)
                            } else {
                                PackForgeLog.w(TAG_FLIP, "Flipbook duplicado: $key (manteniendo primero)")
                            }
                        }
                    }
                } catch (e: Exception) {
                    PackForgeLog.e(TAG_FLIP, "Error procesando flipbook_textures.json: ${e.message}")
                }
            }
        }

        if (processedCount > 0) {
            val merged = JSONObject().apply {
                put("flipbook_textures", mergedArray)
            }
            val destFile = File(destDir, "textures/flipbook_textures.json")
            destFile.parentFile?.mkdirs()
            writeJson(destFile, merged)
        }
        PackForgeLog.d(TAG_FLIP, "flipbook_textures.json fusionado desde $processedCount RPs con ${mergedArray.length()} animaciones")
    }

    // =====================================================================
    // 7. MATERIAL INSTANCES - Verifica que las texturas referenciadas por
    //    material_instances en los bloques del BP existan en terrain_texture.json.
    //    Los bloques con geometria compleja (enredaderas, hojas, glass) usan
    //    material_instances como: { "side": { "texture": "vine_tex", "render_method": "alpha_test" } }
    //    Si la textura no esta mapeada, se agrega y se copia el PNG desde los addons.
    // =====================================================================
    fun mergeMaterialInstances(
        bpDirs: List<File>,
        rpDirs: List<File>,
        mergedBpDir: File,
        mergedRpDir: File
    ) {
        val blocksDir = File(mergedBpDir, "blocks")
        if (!blocksDir.exists()) {
            PackForgeLog.d(TAG_MAT, "No existe carpeta blocks en BP fusionado")
            return
        }

        // Cargar el terrain_texture.json fusionado (para saber que nombres estan mapeados)
        val terrainFile = File(mergedRpDir, "textures/terrain_texture.json")
        val terrainData = if (terrainFile.exists()) {
            try {
                JSONObject(terrainFile.readText(StandardCharsets.UTF_8))
                    .optJSONObject("texture_data") ?: JSONObject()
            } catch (e: Exception) {
                PackForgeLog.e(TAG_MAT, "Error leyendo terrain_texture.json fusionado: ${e.message}")
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
                val json = JSONObject(blockFile.readText(StandardCharsets.UTF_8))
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

                textureNames.forEach { textureName ->
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
                            PackForgeLog.d(TAG_MAT, "Textura material agregada: $textureName (copiada desde ${foundPng.relativeTo(foundPng.parentFile?.parentFile ?: foundPng)})")
                        }
                    } else {
                        PackForgeLog.w(TAG_MAT, "Textura de material no encontrada: $textureName (bloque ${blockFile.name})")
                        missingTextures[textureName] = true
                    }
                }
            } catch (e: Exception) {
                PackForgeLog.e(TAG_MAT, "Error procesando material_instances de ${blockFile.name}: ${e.message}")
            }
        }

        // Guardar terrain_texture.json actualizado
        if (addedCount > 0) {
            writeJson(terrainFile, JSONObject().apply {
                put("resource_pack_name", "PackForge")
                put("texture_name", "atlas.terrain")
                put("padding", 8)
                put("num_mip_levels", 4)
                put("texture_data", terrainData)
            })
        }

        PackForgeLog.d(TAG_MAT, "Material instances verificados: $addedCount texturas agregadas, ${missingTextures.size} faltantes")
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

    // =====================================================================
    // Helpers de escritura UTF-8 sin BOM
    // =====================================================================
    private fun writeJson(file: File, json: JSONObject) {
        OutputStreamWriter(FileOutputStream(file), StandardCharsets.UTF_8).use { it.write(json.toString(2)) }
    }

    private fun writeText(file: File, content: String) {
        OutputStreamWriter(FileOutputStream(file), StandardCharsets.UTF_8).use { it.write(content) }
    }
}
