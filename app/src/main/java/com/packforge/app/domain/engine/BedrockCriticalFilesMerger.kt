package com.packforge.app.domain.engine

import com.packforge.app.util.PackForgeLog
import com.packforge.app.domain.model.ConflictSeverity
import com.packforge.app.util.logFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Locale

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
    private const val TAG = "PackForge_BedrockMerger"

    data class TextureCoverageReport(
        val coveragePercent: Float,
        val missingBlocks: List<String>,
        val missingItems: List<String>,
        val detailedInfo: List<String>
    )

    // Helper
    /**
     * Reporta una colisión REAL entre dos addons en un catálogo indexado por
     * identificador (`texture_data`, `blocks.json`).
     *
     * Antes el primero ganaba **en silencio**: el segundo addon perdía su
     * textura/mapeo y ni el reporte ni la pantalla de Conflictos lo decían
     * (regla 15 de AGENTS.md). Un valor idéntico no es conflicto (regla 8).
     *
     * Severidad: MEDIUM si la clave es un id custom (perder su textura deja el
     * bloque/ítem con el atlas equivocado); LOW para overrides de vanilla o
     * claves planas, que es el patrón normal de un pack de texturas y no debe
     * bloquear la exportación.
     */
    private fun reportarColisionDeCatalogo(
        ruta: String,
        clave: String,
        valorPrevio: Any?,
        valorEntrante: Any?,
        duenioPrevio: String,
        duenioEntrante: String,
        tipo: String = "TEXTURE_OVERWRITE"
    ) {
        if (JsonDeepMerger.equivalentlyEqual(valorPrevio, valorEntrante)) return
        val esCustom = clave.contains(':')
        ConflictRegistry.logConflict(
            severity = if (esCustom) ConflictSeverity.MEDIUM else ConflictSeverity.LOW,
            type = tipo,
            file = ruta,
            addon1 = duenioPrevio,
            addon2 = duenioEntrante,
            description = "La clave '$clave' está definida con valores distintos en " +
                "'$duenioPrevio' y '$duenioEntrante'. Se conserva la de '$duenioPrevio' " +
                "(primero en el orden de fusión); la de '$duenioEntrante' no llega al pack. " +
                "Cambia el orden o el ganador en Conflictos si prefieres la otra."
        )
    }

    /**
     * Etiqueta legible del addon dueño de una carpeta de pack.
     *
     * `rpDir.name` suele ser `"rp"` (todas las raíces extraídas se llaman igual),
     * así que un conflicto acababa diciendo «rp choca con rp»: el reporte no
     * servía para localizar el addon. `AddonOwnerRegistry` guarda el nombre real
     * que ve el usuario; las llamadas directas a los mergers (tests) siguen
     * cayendo en el nombre de la carpeta.
     */
    private fun etiquetaAddon(dir: File): String =
        AddonOwnerRegistry.ownerOf(dir)?.let { AddonOwnerRegistry.labelOf(it) } ?: dir.name

    private fun calculateCoverage(
        terrainData: JSONObject, 
        itemData: JSONObject,
        missingBlocks: List<String>, 
        missingItems: List<String>
    ): Float {
        // Contar bloques/items totales vs mapeados
        // Lógica simplificada - en implementación real contar de blocks.json e items.json
        val totalEstimado = 50 //placeholder - contar real
        val mapeados = terrainData.length() + itemData.length()
        return if (totalEstimado > 0) (mapeados.toFloat() / totalEstimado * 100).coerceIn(0f, 100f)
        else 100f
    }

    fun validateTextureCoverageComplete(
        mergedBpDir: File, 
        mergedRpDir: File
    ): TextureCoverageReport {
        val report = TextureCoverageReport(0f, emptyList(), emptyList(), emptyList())
        
        // 1. Cargar texturas fusionadas
        val terrainFile = File(mergedRpDir, "textures/terrain_texture.json")
        val itemFile = File(mergedRpDir, "textures/item_texture.json")
        
        if (!terrainFile.exists() || !itemFile.exists()) {
            return report.copy(detailedInfo = listOf("Archivos de textura fusionados inexistentes"))
        }
        
        val terrainJson = JSONObject(terrainFile.readText())
        val itemJson = JSONObject(itemFile.readText())
        val terrainData = terrainJson.optJSONObject("texture_data") ?: JSONObject()
        val itemData = itemJson.optJSONObject("texture_data") ?: JSONObject()
        
        // 2. Analizar blocks.json del BP fusionado
        val blocksDir = File(mergedBpDir, "blocks")
        val missingBlocks = mutableListOf<String>()
        val missingItems = mutableListOf<String>()
        
        if (blocksDir.exists() && blocksDir.isDirectory) {
            blocksDir.listFiles()?.filter { it.name.endsWith(".json") }?.forEach { blockFile ->
                try {
                    val json = JSONObject(blockFile.readText())
                    // Extraer nombre del bloque (primera clave que no sea minecraft:)
                    val blockName = json.keys().asSequence()
                        .firstOrNull { key -> !key.startsWith("minecraft:") }
                    
                    if (blockName != null) {
                        val cleanName = blockName.trim()
                        // Verificar en terrain_texture
                        if (!terrainData.has(cleanName) && !itemData.has(cleanName)) {
                            missingBlocks.add("${cleanName} (en ${blockFile.name})")
                        }
                    }
                } catch (e: Exception) { /* ignorar JSONs rotos */ }
            }
        }
        
        // 3. Analizar items.json si existe
        val itemsDir = File(mergedBpDir, "items")
        if (itemsDir.exists() && itemsDir.isDirectory) {
            itemsDir.listFiles()?.filter { it.name.endsWith(".json") }?.forEach { itemFile ->
                try {
                    val json = JSONObject(itemFile.readText())
                    val itemName = json.keys().asSequence()
                        .firstOrNull { key -> !key.startsWith("minecraft:") }
                    
                    if (itemName != null) {
                        val cleanName = itemName.trim()
                        if (!itemData.has(cleanName)) {
                            missingItems.add("${cleanName} (en ${itemFile.name})")
                        }
                    }
                } catch (e: Exception) { /* ignorar */ }
            }
        }
        
        return TextureCoverageReport(
            coveragePercent = calculateCoverage(terrainData, itemData, missingBlocks, missingItems),
            missingBlocks = missingBlocks,
            missingItems = missingItems,
            detailedInfo = emptyList()
        )
    }

    // Helper para limpiar claves JSON
    private fun String.sanitizeKey(): String {
        // Elimina espacios, caracteres de control y caracteres Unicode invisibles
        return this.replace(Regex("[\\p{C}\\p{Z}]"), "").trim()
    }

    // =====================================================================
    // 1. TERRAIN TEXTURE - Mapea bloques a texturas (CRÍTICO)
    // =====================================================================
    fun mergeTerrainTexture(rpDirs: List<File>, destDir: File) {
        val mergedTextureData = JSONObject()
        // Quién aportó cada clave: sin esto el conflicto no dice a quién culpar.
        val duenios = HashMap<String, String>()
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
                            val cleanBlockName = blockName.sanitizeKey()
                            val value = data.get(blockName)
                            val cleanValue = JsonDeepMerger.cleanJsonValue(value)
                            if (!mergedTextureData.has(cleanBlockName)) {
                                mergedTextureData.put(cleanBlockName, cleanValue)
                                duenios[cleanBlockName] = etiquetaAddon(rpDir)
                            } else {
                                reportarColisionDeCatalogo(
                                    ruta = "textures/terrain_texture.json",
                                    clave = cleanBlockName,
                                    valorPrevio = mergedTextureData.get(cleanBlockName),
                                    valorEntrante = cleanValue,
                                    duenioPrevio = duenios[cleanBlockName] ?: "otro addon",
                                    duenioEntrante = etiquetaAddon(rpDir)
                                )
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
        val duenios = HashMap<String, String>()

        rpDirs.forEach { rpDir ->
            val file = File(rpDir, "textures/item_texture.json")
            if (file.exists()) {
                try {
                    val json = JsonDeepMerger.cleanJsonObject(JSONObject(file.readText(Charsets.UTF_8)))
                    json.optJSONObject("texture_data")?.let { data ->
                        data.keys().forEach { itemName ->
                            val cleanItemName = itemName.sanitizeKey()
                            val value = data.get(itemName)
                            val cleanValue = JsonDeepMerger.cleanJsonValue(value)
                            if (!mergedTextureData.has(cleanItemName)) {
                                mergedTextureData.put(cleanItemName, cleanValue)
                                duenios[cleanItemName] = etiquetaAddon(rpDir)
                            } else {
                                reportarColisionDeCatalogo(
                                    ruta = "textures/item_texture.json",
                                    clave = cleanItemName,
                                    valorPrevio = mergedTextureData.get(cleanItemName),
                                    valorEntrante = cleanValue,
                                    duenioPrevio = duenios[cleanItemName] ?: "otro addon",
                                    duenioEntrante = etiquetaAddon(rpDir)
                                )
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
        val duenios = HashMap<String, String>()

        rpDirs.forEach { rpDir ->
            val file = File(rpDir, "blocks.json")
            if (file.exists()) {
                try {
                    val json = JsonDeepMerger.cleanJsonObject(JSONObject(file.readText(Charsets.UTF_8)))
                    json.keys().forEach { key ->
                        val cleanKey = key.sanitizeKey()
                        // `format_version` no es un id de bloque: no entra al
                        // catálogo ni se compara con las claves de otros addons.
                        if (cleanKey != "format_version") {
                            val cleanValue = JsonDeepMerger.cleanJsonValue(json.get(key))
                            if (!merged.has(cleanKey)) {
                                merged.put(cleanKey, cleanValue)
                                duenios[cleanKey] = etiquetaAddon(rpDir)
                            } else {
                                reportarColisionDeCatalogo(
                                    ruta = "blocks.json",
                                    clave = cleanKey,
                                    valorPrevio = merged.get(cleanKey),
                                    valorEntrante = cleanValue,
                                    duenioPrevio = duenios[cleanKey] ?: "otro addon",
                                    duenioEntrante = etiquetaAddon(rpDir),
                                    tipo = "BLOCK_RENDER_OVERWRITE"
                                )
                            }
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
    // 4. ENTITY DEFINITIONS - RP/entity/*.json (CRÍTICO para mobs 3D)
    //    ⭐ DUCK TYPING: Clasifica por contenido raíz "minecraft:client_entity",
    //    NO por extensión .entity.json. Esto permite procesar archivos con
    //    nombres ofuscados (ej: .fT.json, .7I.json) correctamente.
    //
    //    ⭐ TICKET-CORE-015: el agrupado es por **identifier**, no por nombre de
    //    archivo. Con el agrupado por nombre, dos addons con `entity/mob.json`
    //    pero identifiers distintos se fusionaban sobre el mismo objeto raíz
    //    (`minecraft:client_entity` es una clave fija): el identifier del
    //    perdedor se sobrescribía y su entidad quedaba sin definición de
    //    cliente. El BP la seguía declarando → **mob invisible**. Ver
    //    DeclarationFileRouter.
    // =====================================================================
    suspend fun mergeEntityDefinitions(rpDirs: List<File>, destDir: File) {
        DeclarationFileRouter.route(
            sourceDirs = rpDirs,
            destRoot = destDir,
            subcarpeta = "entity",
            claveDeclaracion = "minecraft:client_entity"
        )
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
                                    val cleanKey = key.sanitizeKey()
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
                                            val cleanAnimKey = animKey.sanitizeKey()
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

    private fun canonicalBedrockLangCode(rawCode: String): String {
        val clean = rawCode.trim().replace('-', '_')
        val parts = clean.split('_')
        if (parts.size == 2) {
            return "${parts[0].lowercase(Locale.ROOT)}_${parts[1].uppercase(Locale.ROOT)}"
        }
        return when (clean.lowercase(Locale.ROOT)) {
            "en_us", "en" -> "en_US"
            "en_gb" -> "en_GB"
            "es_es" -> "es_ES"
            "es_mx" -> "es_MX"
            "pt_br" -> "pt_BR"
            "pt_pt" -> "pt_PT"
            "fr_fr" -> "fr_FR"
            "fr_ca" -> "fr_CA"
            "de_de" -> "de_DE"
            "it_it" -> "it_IT"
            "ru_ru" -> "ru_RU"
            "zh_cn" -> "zh_CN"
            "zh_tw" -> "zh_TW"
            "ja_jp" -> "ja_JP"
            "ko_kr" -> "ko_KR"
            else -> if (clean.length == 2) "${clean.lowercase(Locale.ROOT)}_${clean.uppercase(Locale.ROOT)}" else clean
        }
    }

    // =====================================================================
    // 7. LANG FILES - Traducciones (CRÍTICO para UI y nombres traducidos)
    // =====================================================================
    fun mergeLangFiles(allDirs: List<File>, destDir: File) {
        val translations = mutableMapOf<String, MutableMap<String, String>>()

        allDirs.forEach { dir ->
            if (!dir.exists()) return@forEach
            val cachedFiles = DirIndexCache.index(dir).allFiles
            val langFiles = cachedFiles.filter { it.extension.equals("lang", true) }

            langFiles.forEach { langFile ->
                val rawLangCode = langFile.nameWithoutExtension
                val lang = canonicalBedrockLangCode(rawLangCode)
                val map = translations.getOrPut(lang) { mutableMapOf() }

                try {
                    val rawText = langFile.readText(Charsets.UTF_8).replace("\uFEFF", "")
                    rawText.lines().forEach { line ->
                        val cleanLine = line.replace(Regex("[\\p{Cc}\\p{Cf}]"), "").trim()
                        if (cleanLine.contains("=") && !cleanLine.startsWith("#") && cleanLine.isNotBlank()) {
                            val parts = cleanLine.split("=", limit = 2)
                            if (parts.size == 2) {
                                val key = parts[0].trim().replace("\uFEFF", "")
                                val value = parts[1].trim()
                                if (key.isNotEmpty()) {
                                    map[key] = value
                                    if (key.startsWith("item.") || key.startsWith("tile.")) {
                                        if (!key.endsWith(".name")) {
                                            map.putIfAbsent("$key.name", value)
                                        } else {
                                            val shortKey = key.removeSuffix(".name")
                                            map.putIfAbsent(shortKey, value)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    PackForgeLog.e("PackForge_Lang", "Error leyendo ${langFile.name}: ${e.message}")
                }
            }
        }

        val primaryLangMap = translations["en_US"] ?: translations["en_GB"] ?: translations.values.firstOrNull()
        if (primaryLangMap != null) {
            val commonLangs = listOf("en_US", "es_ES", "es_MX", "pt_BR", "fr_FR", "de_DE", "ru_RU", "it_IT", "ja_JP", "ko_KR", "zh_CN", "zh_TW")
            commonLangs.forEach { lang ->
                val map = translations.getOrPut(lang) { mutableMapOf() }
                primaryLangMap.forEach { (k, v) ->
                    map.putIfAbsent(k, v)
                }
            }
        }

        val destTextsDir = File(destDir, "texts")
        destTextsDir.mkdirs()

        translations.forEach { (lang, map) ->
            val file = File(destTextsDir, "$lang.lang")
            OutputStreamWriter(FileOutputStream(file), StandardCharsets.UTF_8).use {
                it.write(map.entries.joinToString("\n") { entry -> "${entry.key}=${entry.value}" })
            }
            PackForgeLog.d("PackForge_Lang", "✅ $lang.lang: ${map.size} claves (con fallbacks)")
        }

        val languagesJson = JSONObject().apply {
            put("languages", JSONArray().apply {
                translations.keys.sorted().forEach { put(it) }
            })
        }
        val langJsonFile = File(destTextsDir, "languages.json")
        OutputStreamWriter(FileOutputStream(langJsonFile), StandardCharsets.UTF_8).use {
            it.write(languagesJson.toString(4))
        }
        PackForgeLog.d("PackForge_Lang", "✅ languages.json creado con ${translations.size} idiomas")
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
                            val cleanK = k.sanitizeKey()
                            if (!mergedEnt.has(cleanK)) mergedEnt.put(cleanK, ent.get(k))
                        }
                    }

                    // block_sounds
                    json.optJSONObject("block_sounds")?.let { blocks ->
                        val mergedBlocks = merged.optJSONObject("block_sounds")
                            ?: JSONObject().also { merged.put("block_sounds", it) }
                        blocks.keys().forEach { k ->
                            val cleanK = k.sanitizeKey()
                            if (!mergedBlocks.has(cleanK)) mergedBlocks.put(cleanK, blocks.get(k))
                        }
                    }

                    // individual_event_sounds
                    json.optJSONObject("individual_event_sounds")?.let { events ->
                        val mergedEvents = merged.optJSONObject("individual_event_sounds")
                            ?: JSONObject().also { merged.put("individual_event_sounds", it) }
                        events.keys().forEach { k ->
                            val cleanK = k.sanitizeKey()
                            if (!mergedEvents.has(cleanK)) mergedEvents.put(cleanK, events.get(k))
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
    //     Fusiona archivos con minecraft:geometry deduplicando por identifier.
    //     Los bloques con geometría (enredaderas, vallas, cruces, plantas 3D)
    //     referencian estos archivos via minecraft:geometry en su BP definition.
    //
    //     ⭐ DUCK TYPING: Clasifica por contenido raíz "minecraft:geometry",
    //     NO por extensión .geo.json. Archivos ofuscados (ej: .fT.json) se
    //     detectan correctamente independientemente de su nombre físico.
    // =====================================================================
    fun mergeGeometryFiles(rpDirs: List<File>, destDir: File) {
        // Mapa: ruta relativa del archivo -> JSONObject fusionado
        val geoFiles = mutableMapOf<String, JSONObject>()
        // Quién aportó cada archivo: sin esto el duplicado no dice a quién culpar.
        val propietarios = mutableMapOf<String, String>()
        var processedCount = 0

        rpDirs.forEach { rpDir ->
            // ⭐ OPTIMIZACIÓN: Usar DirIndexCache en lugar de walkTopDown()
            val cachedFiles = DirIndexCache.index(rpDir).allFiles
            for (file in cachedFiles) {
                // ⭐ SEMANTIC: Aceptar cualquier .json, clasificar por contenido
                if (!file.extension.equals("json", ignoreCase = true)) continue
                val relativePath = file.relativeTo(rpDir).path
                try {
                    val json = JsonDeepMerger.cleanJsonObject(JSONObject(file.readText(Charsets.UTF_8)))
                    // ⭐ DUCK TYPING: procesar si declara geometría en CUALQUIERA de
                    // sus dos formatos:
                    //  · moderno: { "minecraft:geometry": [ … ] }
                    //  · legacy:  { "geometry.cc.pillager": { "bones": [ … ] } }
                    //
                    // ⭐ FASE 5 (pérdida de datos real, medida con core-craft): antes
                    // solo se aceptaba el moderno, así que un archivo legacy se
                    // SALTABA y nunca se escribía. Sus geometrías desaparecían del
                    // pack fusionado aunque estuvieran empaquetadas.
                    val tieneModerno = json.has("minecraft:geometry")
                    val tieneLegacy = json.keys().asSequence().any {
                        it.startsWith("geometry.") && json.optJSONObject(it) != null
                    }
                    if (!tieneModerno && !tieneLegacy) continue

                    processedCount++
                    val existing = geoFiles[relativePath]

                    if (existing == null) {
                        geoFiles[relativePath] = json
                        propietarios[relativePath] = etiquetaAddon(rpDir)
                        logFile { "Agregada geometria (duck typing): $relativePath" }
                    } else {
                        // Fusionar deduplicando por identifier dentro de minecraft:geometry
                        mergeGeoJson(
                            existing,
                            json,
                            relativePath,
                            propietarios[relativePath] ?: "otro addon",
                            etiquetaAddon(rpDir)
                        )
                    }
                } catch (e: Exception) {
                    PackForgeLog.e("PackForge_Geometry", "Error procesando $relativePath: ${e.message}")
                }
            }
        }

        // Escribir todos los archivos de geometría fusionados
        geoFiles.forEach { (relativePath, geoJson) ->
            val destFile = File(destDir, relativePath)
            destFile.parentFile?.mkdirs()
            OutputStreamWriter(FileOutputStream(destFile), StandardCharsets.UTF_8).use {
                it.write(geoJson.toString())
            }
            logFile { "geometria guardada: $relativePath" }
        }

        PackForgeLog.d("PackForge_Geometry", "Geometrias fusionadas: ${geoFiles.size} archivos con minecraft:geometry")
    }

    /**
     * Fusiona dos archivos de geometria con el mismo path relativo,
     * deduplicando las entradas de minecraft:geometry por description.identifier.
     */
    private fun mergeGeoJson(
        base: JSONObject,
        incoming: JSONObject,
        path: String,
        duenioPrevio: String,
        duenioEntrante: String
    ) {
        // ⭐ FASE 5: además del array moderno hay que fusionar las claves raíz
        // legacy ("geometry.x": {…}). Antes solo se tocaba `minecraft:geometry`,
        // así que en un archivo legacy la geometría del segundo addon se perdía
        // al no existir el array.
        val baseArr = base.optJSONArray("minecraft:geometry")
        val incomingArr = incoming.optJSONArray("minecraft:geometry")

        if (baseArr != null || incomingArr != null) {
            val acumulado = baseArr ?: JSONArray()

            // Coleccionar las geometrías existentes por identifier, con su
            // CONTENIDO: así se distingue “el segundo addon redefine lo mismo”
            // (no hay conflicto) de “define otra malla y se pierde” (sí lo hay).
            val existentes = mutableMapOf<String, JSONObject>()
            for (i in 0 until acumulado.length()) {
                val geo = acumulado.optJSONObject(i) ?: continue
                val id = geo.optJSONObject("description")?.optString("identifier") ?: continue
                existentes.putIfAbsent(id, geo)
            }

            incomingArr?.let { inc ->
                for (i in 0 until inc.length()) {
                    val geo = inc.optJSONObject(i) ?: continue
                    val id = geo.optJSONObject("description")?.optString("identifier") ?: continue

                    val previa = existentes[id]
                    if (previa == null) {
                        acumulado.put(geo)
                        existentes[id] = geo
                        logFile { "  + Geometria $id agregada en $path" }
                    } else {
                        reportarGeometriaDuplicada(
                            path = path,
                            id = id,
                            valorPrevio = previa,
                            valorEntrante = geo,
                            duenioPrevio = duenioPrevio,
                            duenioEntrante = duenioEntrante,
                            mantenida = "primera"
                        )
                    }
                }
            }

            base.put("minecraft:geometry", acumulado)
        }

        // Claves raíz legacy: se añaden las que falten; si el id ya existe se
        // conserva la primera (mismo criterio que el array).
        incoming.keys().forEach { key ->
            if (!key.startsWith("geometry.")) return@forEach
            val valor = incoming.optJSONObject(key) ?: return@forEach
            val previo = base.optJSONObject(key)
            if (previo == null) {
                base.put(key, valor)
                logFile { "  + Geometria legacy $key agregada en $path" }
            } else {
                reportarGeometriaDuplicada(
                    path = path,
                    id = key,
                    valorPrevio = previo,
                    valorEntrante = valor,
                    duenioPrevio = duenioPrevio,
                    duenioEntrante = duenioEntrante,
                    mantenida = "primera"
                )
            }
        }
    }

    /**
     * Un `geometry.x` declarado por dos addons con CONTENIDO DISTINTO se
     * resolvía en silencio: ganaba la primera malla y el segundo mob aparecía
     * con el modelo equivocado. Antes solo quedaba un `PackForgeLog.w`, que en
     * Android va a logcat y nunca llega a `fusion_report.txt` (regla 18).
     *
     * Ahora se registra con los dos addons. La alternativa de contenido suele
     * poder recuperarse después: `EntityDependencyResolver` aliasa las
     * variantes con `_pf<hash6>` cuando la entidad lo requiere.
     */
    private fun reportarGeometriaDuplicada(
        path: String,
        id: String,
        valorPrevio: Any?,
        valorEntrante: Any?,
        duenioPrevio: String,
        duenioEntrante: String,
        mantenida: String
    ) {
        if (JsonDeepMerger.equivalentlyEqual(valorPrevio, valorEntrante)) {
            PackForgeLog.d(
                "PackForge_Geometry",
                "Geometría '$id' idéntica en '$duenioEntrante': se deduplica sin conflicto"
            )
            return
        }
        PackForgeLog.w(
            "PackForge_Geometry",
            "Geometría duplicada '$id' en $path (se mantiene la $mantenida)"
        )
        ConflictRegistry.logConflict(
            severity = ConflictSeverity.MEDIUM,
            type = "GEOMETRY_IDENTIFIER_COLLISION",
            file = path,
            addon1 = duenioPrevio,
            addon2 = duenioEntrante,
            description = "El identificador '$id' está definido con mallas distintas en " +
                "'$duenioPrevio' y '$duenioEntrante'. Se conserva la $mantenida y el motor " +
                "intentará aliasar la otra variante (_pf<hash>) para las entidades que la usen; " +
                "si un mob se ve con el modelo equivocado, revisa esta colisión."
        )
    }

    // =====================================================================
    // 9b. FAMILIAS DECLARATIVAS NUEVAS (3.8)
    //     item_catalog/, cameras/, dimensions/, worldgen/feature_rules/.
    // =====================================================================

    /**
     * Familias con raíz fija y `description.identifier` que NO tenían fusión
     * dedicada: se copiaban por ruta. Dos consecuencias medidas:
     *
     *  · el mismo identifier en archivos con nombres distintos quedaba
     *    DUPLICADO en el pack (Bedrock descarta uno en silencio), y
     *  · dos addons con el mismo nombre de archivo se pisaban (ganaba el
     *    último), perdiendo la definición del otro.
     *
     * Se reutiliza [DeclarationFileRouter]: una identidad = un archivo, con
     * separación `_pf<hash>` cuando dos ids distintos comparten nombre y fusión
     * por componente (con negociación de `format_version`) cuando el id
     * coincide.
     */
    private val FAMILIAS_DECLARATIVAS = listOf(
        "item_catalog" to "minecraft:item_catalog",
        "cameras" to "minecraft:camera_preset",
        "dimensions" to "minecraft:dimension",
        "worldgen/feature_rules" to "minecraft:feature_rules"
    )

    suspend fun mergeDeclarativeFamilies(bpDirs: List<File>, destDir: File) {
        for ((subcarpeta, clave) in FAMILIAS_DECLARATIVAS) {
            val resultado = DeclarationFileRouter.route(
                sourceDirs = bpDirs,
                destRoot = destDir,
                subcarpeta = subcarpeta,
                claveDeclaracion = clave
            )
            if (resultado.written == 0) continue
            logFile {
                "Familia declarativa '$subcarpeta': ${resultado.written} archivo(s), " +
                    "${resultado.fusionados} fusionado(s), ${resultado.divididos} separado(s)"
            }
        }
    }

    /**
     * 3.8 — `trading/` NO declara identifier (la tabla se referencia por RUTA
     * desde `minecraft:economy_trade_table`), así que no se puede enrutar por
     * identidad. Se fusiona por ruta con la prioridad del pipeline y se
     * REPORTAR cuando dos addons publican la misma tabla con contenido
     * distinto: antes se mezclaban sus listas de trades sin decírselo a nadie.
     */
    fun mergeTradingTables(bpDirs: List<File>, destDir: File) {
        val porRuta = LinkedHashMap<String, JSONObject>()
        val duenios = LinkedHashMap<String, MutableList<String>>()

        bpDirs.forEach { bpDir ->
            val addon = etiquetaAddon(bpDir)
            for (file in DirIndexCache.index(bpDir).jsonFiles) {
                val rel = file.relativeTo(bpDir).path.replace(File.separatorChar, '/')
                if (!rel.startsWith("trading/")) continue
                val json = runCatching {
                    JsonDeepMerger.cleanJsonObject(JSONObject(file.readText(Charsets.UTF_8)))
                }.getOrNull() ?: continue

                val previo = porRuta[rel]
                if (previo == null) {
                    porRuta[rel] = json
                    duenios.getOrPut(rel) { mutableListOf() }.add(addon)
                    continue
                }

                val autores = duenios.getOrPut(rel) { mutableListOf() }
                if (!autores.contains(addon)) autores.add(addon)
                if (!JsonDeepMerger.equivalentlyEqual(previo, json)) {
                    ConflictRegistry.logConflict(
                        severity = ConflictSeverity.LOW,
                        type = "TRADING_TABLE_MERGED",
                        file = rel,
                        addon1 = autores.firstOrNull() ?: addon,
                        addon2 = addon,
                        description = "Dos addons publican la tabla de comercio '$rel' con " +
                            "contenido distinto. Se fusionó por ruta (prioridad del pipeline: " +
                            "gana el addon más prioritario) y ambas listas de trades se " +
                            "combinan; revisa el resultado si esperabas solo una."
                    )
                }
                porRuta[rel] = try {
                    JsonDeepMerger.deepMerge(previo, json)
                } catch (_: Exception) {
                    json
                }
            }
        }

        porRuta.forEach { (rel, json) ->
            val destino = File(destDir, rel)
            destino.parentFile?.mkdirs()
            OutputStreamWriter(FileOutputStream(destino), StandardCharsets.UTF_8).use {
                it.write(json.toString())
            }
        }
        if (porRuta.isNotEmpty()) {
            logFile { "Tablas de comercio fusionadas: ${porRuta.size} archivo(s)" }
        }
    }

    // =====================================================================
    // 10. FLIPBOOK TEXTURES - Texturas animadas (agua, lava, bloques animados)
    // =====================================================================
    /** Campos que definen CÓMO se anima un flipbook. */
    private val FLIPBOOK_ANIMATION_FIELDS = listOf(
        "ticks_per_frame",
        "frames",
        "blend_frames",
        "replicate",
        "atlas_index",
        "atlas_tile"
    )

    /**
     * Fusiona `textures/flipbook_textures.json` de todos los RPs.
     *
     * Cada entrada describe UNA textura animada, así que la identidad es su
     * ruta (`flipbook_texture`) —o `atlas_tile` + `atlas_index` cuando se anima
     * una baldosa concreta del atlas—.
     *
     * Mejoras sobre la versión anterior:
     *  - Acepta también el formato de **array raíz** (`[ {...}, {...} ]`), que
     *    algunos addons usan y antes se descartaba por completo.
     *  - Normaliza la ruta antes de comparar (`\` → `/`, sin `./`, sin
     *    mayúsculas): `./textures/blocks/x` y `textures/blocks/x` son la misma
     *    textura y antes se duplicaban.
     *  - Cuando dos addons animan la MISMA textura se conserva la primera (el
     *    orden de fusión ya deja delante al de mayor prioridad), pero si la
     *    animación difiere (`ticks_per_frame`, `frames`, `blend_frames`…) se
     *    registra el conflicto. Antes se descartaba en silencio y el resultado
     *    era una textura "congelada" o animando a la velocidad de otro addon.
     */
    fun mergeFlipbookTextures(rpDirs: List<File>, destDir: File) {
        val mergedArray = JSONArray()
        val owners = mutableMapOf<String, Pair<JSONObject, String>>()
        var processedCount = 0
        var duplicateCount = 0

        rpDirs.forEach { rpDir ->
            val flipbookFile = File(rpDir, "textures/flipbook_textures.json")
            if (!flipbookFile.exists()) return@forEach

            val entries = readFlipbookEntries(flipbookFile, rpDir.name) ?: return@forEach
            processedCount++

            for (i in 0 until entries.length()) {
                val entry = entries.optJSONObject(i) ?: continue
                val key = flipbookKey(entry)

                if (key.isEmpty()) {
                    // Sin ruta ni atlas reconocible: se conserva para no perder
                    // contenido, aunque no se pueda deduplicar.
                    mergedArray.put(JsonDeepMerger.cleanJsonValue(entry))
                    continue
                }

                val previous = owners[key]
                if (previous == null) {
                    val clean = JsonDeepMerger.cleanJsonValue(entry)
                    val stored = clean as? JSONObject ?: entry
                    owners[key] = stored to rpDir.name
                    mergedArray.put(clean)
                } else {
                    duplicateCount++
                    reportFlipbookCollision(key, previous.second, rpDir.name, previous.first, entry)
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

        PackForgeLog.d(
            "PackForge_Flipbook",
            "flipbook_textures.json: $processedCount RP(s), ${mergedArray.length()} animación(es), " +
                "$duplicateCount duplicada(s)"
        )
    }

    /** Lee las entradas aceptando `{"flipbook_textures":[…]}` o un array raíz. */
    private fun readFlipbookEntries(file: File, addonName: String): JSONArray? {
        return try {
            val raw = file.readText(Charsets.UTF_8)
            val sanitized = BedrockUiMerger.sanitizeJsonComments(raw)
                .trimStart('\uFEFF', ' ', '\n', '\r', '\t')

            if (sanitized.startsWith("[")) {
                JSONArray(sanitized)
            } else {
                JsonDeepMerger.cleanJsonObject(JSONObject(sanitized))
                    .optJSONArray("flipbook_textures")
            }
        } catch (e: Exception) {
            PackForgeLog.e(
                "PackForge_Flipbook",
                "Error leyendo flipbook_textures.json de $addonName: ${e.message}"
            )
            null
        }
    }

    /** Clave de identidad de una entrada de flipbook, normalizada. */
    private fun flipbookKey(entry: JSONObject): String {
        val path = entry.optString("flipbook_texture")
        if (path.isNotBlank()) {
            return path.replace('\\', '/')
                .removePrefix("./")
                .trim()
                .lowercase(java.util.Locale.ROOT)
        }
        val atlas = entry.optString("atlas_tile")
        if (atlas.isNotBlank()) {
            val index = entry.optInt("atlas_index", -1)
            return "atlas:${atlas.trim().lowercase(java.util.Locale.ROOT)}#$index"
        }
        return ""
    }

    /** Registra el duplicado, detallando qué cambia en la animación. */
    private fun reportFlipbookCollision(
        key: String,
        firstAddon: String,
        secondAddon: String,
        first: JSONObject,
        second: JSONObject
    ) {
        val differences = FLIPBOOK_ANIMATION_FIELDS.mapNotNull { field ->
            val a = first.opt(field)
            val b = second.opt(field)
            if (a?.toString() != b?.toString()) {
                "$field: ${a ?: "—"} → ${b ?: "—"}"
            } else {
                null
            }
        }

        if (differences.isEmpty()) {
            PackForgeLog.d(
                "PackForge_Flipbook",
                "Flipbook duplicado idéntico '$key' en '$secondAddon': se conserva '$firstAddon'"
            )
            return
        }

        val detail = differences.joinToString("; ")
        PackForgeLog.w(
            "PackForge_Flipbook",
            "Flipbook duplicado '$key': se conserva '$firstAddon'; '$secondAddon' define $detail"
        )
        ConflictRegistry.logConflict(
            severity = ConflictSeverity.LOW,
            type = "FLIPBOOK_DUPLICATE",
            file = "textures/flipbook_textures.json",
            addon1 = firstAddon,
            addon2 = secondAddon,
            description = "La textura animada '$key' está definida en dos addons con " +
                "animación distinta. Se conserva la de '$firstAddon'. Diferencias: $detail"
        )
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
                    val textureName = rawTextureName.sanitizeKey()
                    // Si la textura ya esta mapeada en terrain_texture, ok
                    if (terrainData.has(textureName)) {
                        return@forEach
                    }

                    // ⭐ AUTO-REPARACIÓN MEJORADA: Buscar la textura en los addons originales
                    val pngPath = "textures/blocks/$textureName.png"
                    val foundPng = findTextureInAddons(rpDirs, pngPath) ?: DirIndexCache.index(rpDirs.firstOrNull() ?: return@forEach).allFiles.find { it.name.equals("$textureName.png", ignoreCase = true) }

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
                            PackForgeLog.d("PackForge_Materials", "Textura auto-detectada y agregada: $textureName")
                        }
                    } else {
                        PackForgeLog.w("PackForge_Materials", "Textura NO encontrada: $textureName (bloque ${blockFile.name})")
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

    private fun findTextureInAddons(rpDirs: List<File>, pngPath: String): File? {
        return rpDirs.firstNotNullOfOrNull { rpDir ->
            val f = File(rpDir, pngPath)
            if (f.exists()) f else null
        }
    }

    fun mergeEntityTextures(rpDirs: List<File>, mergedRpDir: File) {
        val entityDir = File(mergedRpDir, "entity")
        if (!entityDir.isDirectory) return
        var copied = 0
        // ⭐ SEMANTIC: Aceptar TODOS los .json en entity/, clasificar por contenido
        entityDir.listFiles()?.filter { it.extension.equals("json", ignoreCase = true) }?.forEach { entityFile ->
            try {
                val json = JSONObject(entityFile.readText(Charsets.UTF_8))
                // ⭐ DUCK TYPING: Buscar description en ambos formatos (client_entity y directo)
                val desc = json.optJSONObject("minecraft:client_entity")?.optJSONObject("description")
                    ?: json.optJSONObject("description") ?: return@forEach
                // Buscar textures en description
                val textures = desc.optJSONObject("textures") ?: JSONObject()
                textures.keys().forEach { key ->
                    val texPath = textures.optString(key).trim()
                    if (texPath.isBlank() || texPath.startsWith("atlas.") || texPath.contains("/")) {
                        // Solo copiar si es un path relativo a textures/
                        val pngFile = File(mergedRpDir, "textures/$texPath.png")
                        if (!pngFile.exists()) {
                            val found = rpDirs.firstNotNullOfOrNull { rpDir ->
                                val f = File(rpDir, "textures/$texPath.png")
                                if (f.exists()) f else null
                            }
                            if (found != null) {
                                pngFile.parentFile?.mkdirs()
                                found.copyTo(pngFile, overwrite = true)
                                copied++
                                PackForgeLog.d("PackForge_EntityTex", "Copiado: textures/$texPath.png para ${entityFile.name}")
                            } else {
                                PackForgeLog.w("PackForge_EntityTex", "Textura entity no encontrada: $texPath (${entityFile.name})")
                            }
                        }
                    }
                }
            } catch (e: Exception) { /* ignorar */ }
        }
        if (copied > 0) PackForgeLog.d("PackForge_EntityTex", "✅ $copied texturas de entity copiadas")
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
                // ⭐ TICKET-CORE-014 — El token estable se calcula UNA vez por
                // directorio (coste O(contenido)), no por archivo (O(n²)): solo
                // se usa en colisiones, pero el directorio hay que recorrerlo
                // entero para hashearlo.
                val tokenBp = tokenEstableDe(bpDir)
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
                                val token = tokenBp
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
                            val destFile = File(destRecipesDir, "${file.nameWithoutExtension}_$tokenBp.json")
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
                // ⭐ OPTIMIZACIÓN: Usar DirIndexCache en lugar de walkTopDown()
                val cachedFiles = DirIndexCache.index(lootDir).jsonFiles
                for (file in cachedFiles) {
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
        data class Candidate(val json: JSONObject, val version: List<Int>, val ownerName: String, val addonId: String)

        // ⚠️ Aquí se llamaba a SchemaNormalizer.normalize(...) para «adaptar al
        // esquema objetivo» antes del deepMerge. Se ha RETIRADO (TICKET-CORE-010):
        //
        //   · Este método solo procesa ficheros `player.entity.json`, es decir
        //     ENTIDADES, mientras que la única transformación verificada del
        //     normalizador exige un contenedor `minecraft:block`. La llamada no
        //     podía cambiar nada: era código muerto que aparentaba normalizar.
        //   · El «objetivo» se calculaba con `detectMaxFormatVersion` sobre los
        //     `manifest.json`, cuyo `format_version` es el del FORMATO DE
        //     MANIFIESTO (2 o 3), no el del esquema de contenido (1.x.y).
        //
        // El reparto de esquemas de cada addon se informa ahora en el reporte
        // (`SchemaNormalizer.summarizeSchemaSpread`), sin tocar el contenido:
        // Bedrock elige el esquema por el `format_version` de cada archivo, y
        // los antiguos siguen siendo válidos.

        val candidates = mutableListOf<Candidate>()
        bpDirs.forEachIndexed { index, bpDir ->
            val playerFile = sequenceOf(
                File(bpDir, "player.entity.json"),
                File(bpDir, "entities/player.entity.json")
            ).firstOrNull { it.exists() } ?: return@forEachIndexed
            try {
                val json = JsonDeepMerger.cleanJsonObject(JSONObject(playerFile.readText(Charsets.UTF_8)))
                // ⭐ FASE 5 (fix real): el override del jugador puede venir como
                // `minecraft:entity` (servidor) O como `minecraft:client_entity`
                // (cliente). En client_entity viven las ANIMACIONES y la GEOMETRÍA,
                // que es justo lo que personalizan los addons de jugador.
                //
                // Antes solo se miraba `minecraft:entity`, así que un override de
                // client_entity se descartaba aquí; luego quedaban DOS archivos
                // (player.entity.json + player.entity_pf1.json) y el pipeline se
                // quedaba con uno, perdiendo las animaciones del otro addon.
                val id = json.optJSONObject("minecraft:entity")?.optJSONObject("description")
                    ?.optString("identifier").orEmpty()
                    .ifEmpty {
                        json.optJSONObject("minecraft:client_entity")?.optJSONObject("description")
                            ?.optString("identifier").orEmpty()
                    }
                if (id != "minecraft:player") return@forEachIndexed // solo overrides reales del jugador

                // El JSON entra TAL CUAL al deepMerge: no se reescribe su
                // esquema (ver la nota del inicio de mergePlayerEntity).
                val addonId = bpDir.name
                    .replace(Regex("(?i)_?(bp|behavior|packs?)$"), "")
                    .replace(Regex("[^A-Za-z0-9_]"), "_")
                    .lowercase(Locale.ROOT)
                    .ifBlank { "addon_${index + 1}" }

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
                candidates.add(Candidate(json, version, bpDir.name, addonId))
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
            merged = mergePlayerEntityObjects(merged, incoming.json, incoming.ownerName)
        }

        val destFile = File(mergedBpDir, "player.entity.json")
        OutputStreamWriter(FileOutputStream(destFile), StandardCharsets.UTF_8).use {
            it.write(merged.toString(4))
        }

        // 3.6 — Mismo caso que el enrutador de declaraciones: los candidatos se
        // apilan por prioridad de `manifest.version` y el resultado declara un
        // solo esquema. Si no coinciden, se reporta (no se reescribe: regla 16).
        FormatVersionNegotiator.negotiate(
            filePath = "player.entity.json",
            identifier = "minecraft:player",
            aportantes = ordered.map {
                FormatVersionNegotiator.Aportante(
                    it.ownerName,
                    SchemaNormalizer.formatVersionOf(it.json)
                )
            },
            mantenida = SchemaNormalizer.formatVersionOf(merged)
        )
        ConflictRegistry.logConflict(
            severity = com.packforge.app.domain.model.ConflictSeverity.MEDIUM,
            type = "PLAYER_JSON_HYBRID_MERGED",
            file = "player.entity.json",
            addon1 = contributors.firstOrNull() ?: "?",
            addon2 = contributors.lastOrNull() ?: "?",
            description = "Fusión híbrida y apilada de player.entity.json entre ${contributors.size} addons " +
                "(${contributors.joinToString()}); eventos sequence y component_groups unificados."
        )
        PackForgeLog.d("PackForge_Player", "✅ player.entity.json fusionado de forma híbrida desde ${candidates.size} addons")
    }

    /**
     * Fusiona objetos de entidad del jugador apilando eventos mediante `sequence` y
     * combinando `component_groups`, `description` y `components`.
     */
    fun mergePlayerEntityObjects(base: JSONObject, incoming: JSONObject, ownerName: String): JSONObject {
        val rootKey = when {
            incoming.has("minecraft:entity") -> "minecraft:entity"
            incoming.has("minecraft:client_entity") -> "minecraft:client_entity"
            base.has("minecraft:entity") -> "minecraft:entity"
            else -> "minecraft:client_entity"
        }

        val baseEntityObj = base.optJSONObject(rootKey) ?: JSONObject()
        val incomingEntityObj = incoming.optJSONObject(rootKey) ?: JSONObject()

        val mergedEntityObj = JSONObject(baseEntityObj.toString())

        // 1. Fusionar description (animations, animation_controllers, scripts)
        val baseDesc = baseEntityObj.optJSONObject("description") ?: JSONObject()
        val incomingDesc = incomingEntityObj.optJSONObject("description") ?: JSONObject()
        val mergedDesc = JsonDeepMerger.deepMerge(baseDesc, incomingDesc)
        mergedEntityObj.put("description", mergedDesc)

        // 2. Fusionar component_groups
        val baseGroups = baseEntityObj.optJSONObject("component_groups") ?: JSONObject()
        val incomingGroups = incomingEntityObj.optJSONObject("component_groups") ?: JSONObject()
        val mergedGroups = JsonDeepMerger.deepMerge(baseGroups, incomingGroups)
        mergedEntityObj.put("component_groups", mergedGroups)

        // 3. Apilar events usando la lógica de sequence en JsonDeepMerger
        val baseEvents = baseEntityObj.optJSONObject("events") ?: JSONObject()
        val incomingEvents = incomingEntityObj.optJSONObject("events") ?: JSONObject()
        val mergedEvents = JsonDeepMerger.mergeBedrockEvents(baseEvents, incomingEvents)
        mergedEntityObj.put("events", mergedEvents)

        // 4. Fusionar components
        val baseComp = baseEntityObj.optJSONObject("components") ?: JSONObject()
        val incomingComp = incomingEntityObj.optJSONObject("components") ?: JSONObject()
        val mergedComp = BedrockComponentMerger.mergeComponents(baseComp, incomingComp)
        mergedEntityObj.put("components", mergedComp)

        val result = JSONObject(base.toString())
        result.put(rootKey, mergedEntityObj)
        if (incoming.has("format_version")) {
            result.put("format_version", incoming.get("format_version"))
        }
        return result
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

    fun mergeConservativePlayerEntity(bpDirs: List<File>, mergedBpDir: File) {
        val destFile = File(mergedBpDir, "player.entity.json")
        if (!destFile.exists()) return
        try {
            val base = JsonDeepMerger.cleanJsonObject(JSONObject(destFile.readText(Charsets.UTF_8)))
            val components = base.optJSONObject("minecraft:entity")?.optJSONObject("components") ?: return
            // Verificar si componentes de movimiento están definidos
            val movementKeys = listOf("minecraft:movement", "minecraft:jump_strength", "minecraft:gravity")
            val definedMovement = mutableMapOf<String, Any>()
            movementKeys.forEach { key ->
                components.opt(key)?.let { definedMovement[key] = it }
            }
            if (definedMovement.isNotEmpty()) {
                PackForgeLog.d("PackForge_Player", "Player tiene movimientos definidos: ${definedMovement.keys}")
                // No sobrescribir movement si ya está definido (conservar behavior del addon principal)
            }
        } catch (e: Exception) { PackForgeLog.e("PackForge_Player", "Error validando player: ${e.message}") }
    }

    // =====================================================================
    fun mergeSoundDefinitions(rpDirs: List<File>, destDir: File): Map<String, String> {
        val renames = LinkedHashMap<String, String>()
        val mergedDefs = JSONObject()
        var formatVersion = 1

        rpDirs.forEach { rpDir ->
            val file = File(rpDir, "sound_definitions.json")
            if (!file.exists()) return@forEach
            try {
                val token = tokenEstableDe(rpDir)
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
     * ⭐ OPTIMIZACIÓN: Usar DirIndexCache en lugar de walkTopDown()
     */
    fun applySoundKeyRenames(mergedRpDir: File, mergedBpDir: File, renames: Map<String, String>) {
        if (renames.isEmpty()) return
        listOf(mergedRpDir, mergedBpDir).forEach { root ->
            if (!root.isDirectory) return@forEach
            // ⭐ OPTIMIZACIÓN: Usar DirIndexCache en lugar de walkTopDown()
            val cachedFiles = DirIndexCache.index(root).jsonFiles
            for (file in cachedFiles) {
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
            val token = tokenEstableDe(rpDir)

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
            // ⭐ OPTIMIZACIÓN: Usar DirIndexCache en lugar de walkTopDown()
            val cachedFiles = DirIndexCache.index(root).jsonFiles
            fileLoop@ for (file in cachedFiles) {
                try {
                    val text = file.readText(Charsets.UTF_8)
                    if (text.contains("\"$oldId\"")) {
                        val json = JSONObject(text)

                        // 🛡️ NUNCA tocar una DEFINICIÓN de partícula. Su
                        // `identifier` es propio y ya se resolvió al escribirla;
                        // si se reescribe aquí, la definición del addon ANTERIOR
                        // (ya copiada al destino) se renombra al identificador
                        // nuevo y las dos partículas colapsan en una sola.
                        if (json.has("particle_effect")) continue@fileLoop

                        val probe = JSONObject(json.toString())
                        ResourcePathRegistry.applyRenames(probe, mapOf(oldId to newId))
                        OutputStreamWriter(FileOutputStream(file), StandardCharsets.UTF_8).use { it.write(probe.toString()) }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    // =====================================================================
    // 20. BIOMES_CLIENT - Config de cliente por bioma (biomes_client.json)
    //     Es un mapa `biomes: { "ns:bioma": { colores... } }`. Sin fusión, el
    //     copiado genérico dejaba solo los biomas del ÚLTIMO addon y los demás
    //     perdían agua/niebla/cielo personalizados.
    // =====================================================================
    fun mergeBiomesClient(rpDirs: List<File>, destDir: File): Int {
        val mergedBiomes = JSONObject()
        val owners = mutableMapOf<String, String>()
        var found = 0

        rpDirs.forEach { rpDir ->
            val file = listOf(
                File(rpDir, "biomes_client.json"),
                File(rpDir, "worldgen/biomes_client.json")
            ).firstOrNull { it.isFile } ?: return@forEach

            val json = try {
                JsonDeepMerger.cleanJsonObject(JSONObject(file.readText(Charsets.UTF_8)))
            } catch (e: Exception) {
                PackForgeLog.e("PackForge_Biomes", "Error en ${rpDir.name}: ${e.message}")
                return@forEach
            }
            val biomes = json.optJSONObject("biomes") ?: return@forEach
            found++

            biomes.keys().forEach { key ->
                val cleanKey = key.sanitizeKey()
                val value = biomes.get(key)
                val previousOwner = owners[cleanKey]
                when {
                    previousOwner == null -> {
                        mergedBiomes.put(cleanKey, value)
                        owners[cleanKey] = rpDir.name
                    }
                    // No se renombra el bioma: su nombre es una REFERENCIA que usan
                    // los archivos de bioma/entidades. Se conserva el primero y se
                    // informa, en vez de romper las referencias del addon ganador.
                    mergedBiomes.get(cleanKey).toString() != value.toString() -> {
                        PackForgeLog.w(
                            "PackForge_Biomes",
                            "biomes_client: '$cleanKey' duplicado; se conserva el de '$previousOwner'"
                        )
                        ConflictRegistry.logConflict(
                            severity = ConflictSeverity.LOW,
                            type = "BIOME_CLIENT_DUPLICATE",
                            file = "biomes_client.json",
                            addon1 = previousOwner,
                            addon2 = rpDir.name,
                            description = "El bioma '$cleanKey' tiene configuración de cliente " +
                                "distinta en dos addons. Se conserva la de '$previousOwner'."
                        )
                    }
                }
            }
        }

        if (found == 0) return 0
        writeJsonFile(File(destDir, "biomes_client.json"), JSONObject().put("biomes", mergedBiomes))
        PackForgeLog.d("PackForge_Biomes", "✅ biomes_client.json fusionado (${mergedBiomes.length()} biomas de $found addon(s))")
        return mergedBiomes.length()
    }

    // =====================================================================
    // 21. FONTS - Páginas de glifos personalizados (font/*.json)
    //     Cada archivo es un array de códigos de carácter. Si dos addons usan el
    //     mismo nombre de página, gana el último y se pierden glifos del otro.
    // =====================================================================
    fun mergeFontGlyphMaps(rpDirs: List<File>, destDir: File): Int {
        val byFileName = linkedMapOf<String, JSONArray>()
        var mergedFiles = 0

        rpDirs.forEach { rpDir ->
            val fontDir = File(rpDir, "font")
            if (!fontDir.isDirectory) return@forEach

            fontDir.listFiles()
                ?.filter { it.isFile && it.extension.equals("json", true) }
                ?.forEach { file ->
                    val parsed = try {
                        JSONArray(file.readText(Charsets.UTF_8))
                    } catch (_: Exception) {
                        // Formato no-array (objeto de configuración): no se puede
                        // unir con fiabilidad, así que se conserva el primero.
                        PackForgeLog.w(
                            "PackForge_Fonts",
                            "font/${file.name} no es un array de glifos; se conserva el primero"
                        )
                        return@forEach
                    }

                    val existing = byFileName[file.name]
                    byFileName[file.name] = if (existing == null) {
                        parsed
                    } else {
                        unionStringArray(existing, parsed)
                    }
                    mergedFiles++
                }
        }

        if (byFileName.isEmpty()) return 0
        val destFontDir = File(destDir, "font").apply { mkdirs() }
        byFileName.forEach { (name, array) ->
            writeJsonArrayFile(File(destFontDir, name), array)
        }
        PackForgeLog.d("PackForge_Fonts", "✅ $mergedFiles página(s) de glifos fusionadas en ${byFileName.size} archivo(s)")
        return byFileName.size
    }

    // =====================================================================
    // 22. TEXTURES_LIST - Índice plano de texturas (textures/textures_list.json)
    // =====================================================================
    fun mergeTexturesList(rpDirs: List<File>, destDir: File): Int {
        var merged: JSONArray? = null

        rpDirs.forEach { rpDir ->
            val file = File(rpDir, "textures/textures_list.json")
            if (!file.isFile) return@forEach
            val parsed = try {
                JSONArray(file.readText(Charsets.UTF_8))
            } catch (e: Exception) {
                PackForgeLog.e("PackForge_TexturesList", "Error en ${rpDir.name}: ${e.message}")
                return@forEach
            }
            merged = merged?.let { unionStringArray(it, parsed) } ?: parsed
        }

        val result = merged ?: return 0
        writeJsonArrayFile(File(destDir, "textures/textures_list.json"), result)
        PackForgeLog.d("PackForge_TexturesList", "✅ textures_list.json fusionado (${result.length()} entradas)")
        return result.length()
    }

    /** Unión de dos arrays preservando el orden y sin duplicados (por valor). */
    private fun unionStringArray(base: JSONArray, extra: JSONArray): JSONArray {
        val seen = linkedSetOf<String>()
        val result = JSONArray()
        listOf(base, extra).forEach { array ->
            for (i in 0 until array.length()) {
                val value = array.opt(i)?.toString() ?: continue
                if (seen.add(value)) result.put(array.opt(i))
            }
        }
        return result
    }

    private fun writeJsonFile(file: File, json: JSONObject) {
        file.parentFile?.mkdirs()
        OutputStreamWriter(FileOutputStream(file), StandardCharsets.UTF_8).use { it.write(json.toString()) }
    }

    private fun writeJsonArrayFile(file: File, array: JSONArray) {
        file.parentFile?.mkdirs()
        OutputStreamWriter(FileOutputStream(file), StandardCharsets.UTF_8).use { it.write(array.toString()) }
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

    // =====================================================================
    // 19. DETECCIÓN DE client_entity DUPLICADO (TICKET-CORE-003 Parte 5)
    //     Bedrock usa el ÚLTIMO `minecraft:client_entity` con un identifier
    //     dado y descarta el resto en silencio: al fusionar dos addons de
    //     entidades, uno de los dos simplemente deja de funcionar sin aviso.
    //     Nunca se resuelve en silencio: siempre pasa por ConflictRegistry.
    // =====================================================================
    fun detectDuplicateClientEntities(mergedRpDir: File) {
        val entityDir = File(mergedRpDir, "entity")
        if (!entityDir.isDirectory) return

        // identifier -> archivos que lo declaran (en orden de ruta: estable).
        val byId = LinkedHashMap<String, MutableList<File>>()
        val allFiles = DirIndexCache.index(entityDir).allFiles
        for (file in allFiles) {
            if (!file.extension.equals("json", ignoreCase = true)) continue
            val json = try {
                JSONObject(file.readText(Charsets.UTF_8))
            } catch (_: Exception) {
                continue
            }
            // Duck typing: solo cuenta si declara un client_entity con identifier.
            val id = json.optJSONObject("minecraft:client_entity")
                ?.optJSONObject("description")?.optString("identifier")
                ?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            byId.getOrPut(id) { mutableListOf() }.add(file)
        }

        for ((identifier, files) in byId) {
            if (files.size <= 1) continue

            // El ganador es el primero por orden de ruta (determinismo).
            val winner = files.first()
            val losers = files.drop(1)
            val isVanilla = identifier.startsWith("minecraft:")

            // ⭐ TICKET-CORE-004 — Un identifier VANILLA repetido NO es un
            // conflicto: es el patrón normal de cualquier addon que parchea
            // mobs del juego (Just-Health-Indicator sobrescribe 101). Nadie
            // tiene que decidir nada (el juego se queda el ganador, y el
            // ganador es el que ya calculó la fusión), así que registrarlo
            // como MEDIUM solo engordaba el contador "conflictos" y bajaba
            // el semáforo. Se informa igual como nota vanilla.
            if (isVanilla) {
                PackForgeLog.d(
                    "PackForge_Entity",
                    "${VanillaAssets.VANILLA_NOTE}: '$identifier' en ${files.size} archivos; " +
                        "usará ${winner.name} (reemplazo vanilla, no es conflicto)"
                )
                continue
            }

            val severity = ConflictSeverity.CRITICAL
            val kind = "ENTITY_DUPLICATE_CUSTOM"

            ConflictRegistry.logConflict(
                severity = severity,
                type = kind,
                file = "entity/${winner.name}",
                addon1 = "ganador: $identifier",
                addon2 = losers.joinToString(", ") { it.name },
                description = "El identifier CUSTOM '$identifier' está declarado en ${files.size} archivos: " +
                    "Bedrock descartará ${losers.size} en silencio. Revisa si ambos addons son necesarios."
            )

            // Duplicado custom: NO se mueve nada. La decisión es del usuario
            // en la pantalla de Conflictos (regla: nunca resolver en silencio).
        }
    }

    // =====================================================================
    // 17. ATTACHABLES - RP/attachables/*.json declaran minecraft:attachable.
    //     Fusiona por nombre de archivo deduplicando por clave raíz (ej.
    //     "minecraft:attachable"). Si el archivo ya existe en destino, se
    //     fusiona en profundidad; si no, se copia limpio.
    //
    // ⚠️ La resolución de dependencias de entidades (geometrías, animaciones y
    //     render_controllers ausentes) NO vive aquí: la hace
    //     EntityDependencyResolver.resolve(), que clasifica por CONTENIDO
    //     (duck typing) y además crea alias de variantes y restaura la
    //     geometría canónica cuando el deepmerge la mutó. El antiguo
    //     resolveMissingDependencies() de este objeto quedó sin uso al
    //     dejar de construirse el BedrockIdentifierIndex masivo, y se
    //     eliminó para no dejar dos resolutores divergentes.
    // =====================================================================
    suspend fun mergeAttachables(rpDirs: List<File>, destDir: File) {
        // Igual que las entidades: un attachable es UNA declaración con
        // identifier y la raíz es fija (`minecraft:attachable`). Fusionar por
        // nombre de archivo mezclaba dos attachables distintos en uno solo.
        DeclarationFileRouter.route(
            sourceDirs = rpDirs,
            destRoot = destDir,
            subcarpeta = "attachables",
            claveDeclaracion = "minecraft:attachable"
        )
    }

    // =====================================================================
    // 15. FUNCTIONS/TICK.JSON - Fusión de funciones tick ejecutadas en cada ciclo.
    // =====================================================================
    fun mergeTickJson(bpDirs: List<File>, destDir: File) {
        val tickFiles = bpDirs.mapNotNull { bpDir ->
            sequenceOf(
                File(bpDir, "functions/tick.json"),
                File(bpDir, "functions/ticks.json")
            ).firstOrNull { it.exists() }
        }

        if (tickFiles.isEmpty()) return

        val combinedValues = LinkedHashSet<String>()
        val contributors = mutableListOf<String>()

        tickFiles.forEach { file ->
            try {
                val json = JSONObject(file.readText(Charsets.UTF_8))
                val values = json.optJSONArray("values")
                if (values != null) {
                    for (i in 0 until values.length()) {
                        val v = values.optString(i, "").trim()
                        if (v.isNotEmpty()) {
                            combinedValues.add(v)
                        }
                    }
                    contributors.add(file.parentFile?.parentFile?.name ?: "addon")
                }
            } catch (e: Exception) {
                PackForgeLog.e("PackForge_Tick", "Error leyendo tick.json en ${file.absolutePath}: ${e.message}")
            }
        }

        if (combinedValues.isEmpty()) return

        val destFunctionsDir = File(destDir, "functions")
        destFunctionsDir.mkdirs()
        val destFile = File(destFunctionsDir, "tick.json")

        val resultJson = JSONObject().apply {
            put("values", JSONArray(combinedValues.toList()))
        }

        OutputStreamWriter(FileOutputStream(destFile), StandardCharsets.UTF_8).use {
            it.write(resultJson.toString(4))
        }

        ConflictRegistry.logConflict(
            severity = com.packforge.app.domain.model.ConflictSeverity.MEDIUM,
            type = "TICK_JSON_MERGED",
            file = "functions/tick.json",
            addon1 = contributors.firstOrNull() ?: "?",
            addon2 = contributors.lastOrNull() ?: "?",
            description = "Fusión de functions/tick.json (${combinedValues.size} funciones registradas: ${combinedValues.joinToString()})."
        )
        PackForgeLog.d("PackForge_Tick", "✅ functions/tick.json fusionado con ${combinedValues.size} funciones: ${combinedValues.joinToString()}")
    }

    // ══════════════════════════════════════════════════════════════════════
    //  TOKEN ESTABLE POR ADDON (TICKET-CORE-014)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Token `_pf<hex4>` ESTABLE para un directorio de addon.
     *
     * Los sufijos de desambiguación (`_pfXXXX`) deben ser únicos dentro de la
     * fusión PERO estables entre exportaciones del mismo addon: si el jugador
     * re-exporta y reinstala, los identificadores que ya viven en sus mundos
     * deben seguir resolviéndose.
     *
     * La versión anterior usaba `dir.name.hashCode()`, que cambia con cada
     * exportación porque el directorio extraído se llama `extracted_<epochMs>_…`.
     * Ahora el token se deriva del **contenido** del directorio: como la
     * extracción es determinista en bytes, el resumen SHA-256 es idéntico entre
     * exportaciones del mismo ZIP.
     *
     * Se lee el contenido completo del directorio (una sola vez por llamada), lo
     * que es caro si se llama en un bucle por archivo; por eso los llamadores lo
     * calculan UNA vez por directorio y lo reutilizan, no por entrada.
     */
    private fun tokenEstableDe(dir: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        dir.listFiles()?.forEach { f ->
            try {
                if (f.isDirectory) {
                    f.walkTopDown().forEach { if (it.isFile) digest.update(it.readBytes()) }
                } else {
                    digest.update(f.readBytes())
                }
            } catch (_: Exception) {
                // Un archivo ilegible no contribuye al resumen; el resto sí.
            }
        }
        val hex = digest.digest()
        val builder = StringBuilder(hex.size * 2)
        for (b in hex) builder.append("%02x".format(b))
        val crudo = builder.toString()
        return if (crudo.all { it == '0' }) "0000" else crudo.take(4)
    }
}


