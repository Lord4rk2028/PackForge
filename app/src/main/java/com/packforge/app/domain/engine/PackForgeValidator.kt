package com.packforge.app.domain.engine

import com.packforge.app.util.PackForgeLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Validador de referencias cruzadas entre BP y RP
 * Ejecuta DESPUÉS de la fusión y ANTES de crear el ZIP
 */
object PackForgeValidator {
    private const val TAG = "PackForge_Validator"

    /**
     * Resultado de la validación
     */
    data class ValidationResult(
        val missingTextures: List<String>,
        val missingModels: List<String>,
        val fixedReferences: Int,
        val langKeysAdded: Map<String, Int>,
        val soundsFixed: Boolean,
        val warnings: List<String>
    )

    /**
     * Ejecuta todas las validaciones y reparaciones
     */
    fun validate(
        bpDir: File?,
        rpDir: File?,
        originalAddons: List<String>
    ): ValidationResult {
        PackForgeLog.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        PackForgeLog.d(TAG, "🔍 INICIANDO VALIDACIÓN DE REFERENCIAS CRUZADAS")
        PackForgeLog.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        val missingTextures = mutableListOf<String>()
        val missingModels = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        var fixedReferences = 0
        val langKeysAdded = mutableMapOf<String, Int>()
        var soundsFixed = false

        if (bpDir == null || !bpDir.exists()) {
            PackForgeLog.e(TAG, "❌ BP dir no existe")
            return ValidationResult(emptyList(), emptyList(), 0, emptyMap(), false, listOf("BP dir no existe"))
        }

        if (rpDir == null || !rpDir.exists()) {
            PackForgeLog.e(TAG, "❌ RP dir no existe")
            return ValidationResult(emptyList(), emptyList(), 0, emptyMap(), false, listOf("RP dir no existe"))
        }

        // Convertir rutas de addons a File
        val addonDirs = originalAddons.map { File(it) }

        // VALIDACIÓN 1: Referencias de Items (BP → RP)
        PackForgeLog.d(TAG, "📦 VALIDACIÓN 1: Referencias de Items")
        val itemTexturesFixed = validateItemReferences(bpDir, rpDir, addonDirs)
        fixedReferences += itemTexturesFixed

        // VALIDACIÓN 2: Referencias de Bloques (BP → RP)
        PackForgeLog.d(TAG, "📦 VALIDACIÓN 2: Referencias de Bloques")
        val blockTexturesFixed = validateBlockReferences(bpDir, rpDir, addonDirs)
        fixedReferences += blockTexturesFixed

        // VALIDACIÓN 3: Referencias de Entidades (RP ↔ RP)
        PackForgeLog.d(TAG, "📦 VALIDACIÓN 3: Referencias de Entidades")
        val entityRefsFixed = validateEntityReferences(rpDir, addonDirs, missingTextures, missingModels)
        fixedReferences += entityRefsFixed

        // VALIDACIÓN 4: Archivos .lang (CRÍTICO - "desconocido")
        PackForgeLog.d(TAG, "📦 VALIDACIÓN 4: Archivos .lang (concatenar)")
        val langResult = mergeLangFiles(addonDirs, rpDir)
        langKeysAdded.putAll(langResult)

        // VALIDACIÓN 5: sounds.json (concatenar)
        PackForgeLog.d(TAG, "📦 VALIDACIÓN 5: sounds.json (concatenar)")
        soundsFixed = mergeSoundsJson(addonDirs, rpDir)

        // VALIDACIÓN 6: Render controllers y animaciones
        PackForgeLog.d(TAG, "📦 VALIDACIÓN 6: Render controllers y animaciones")
        val renderRefsFixed = validateRenderControllers(rpDir, addonDirs, missingTextures)
        fixedReferences += renderRefsFixed

        PackForgeLog.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        PackForgeLog.d(TAG, "✅ VALIDACIÓN COMPLETADA")
        PackForgeLog.d(TAG, "   Referencias reparadas: $fixedReferences")
        PackForgeLog.d(TAG, "   Texturas faltantes: ${missingTextures.size}")
        PackForgeLog.d(TAG, "   Modelos faltantes: ${missingModels.size}")
        langKeysAdded.forEach { (lang, count) ->
            PackForgeLog.d(TAG, "   Claves $lang.lang: $count")
        }
        PackForgeLog.d(TAG, "   Sonidos fusionados: ${if (soundsFixed) "✅" else "❌"}")
        if (warnings.isNotEmpty()) {
            PackForgeLog.w(TAG, "   ADVERTENCIAS: ${warnings.size}")
            warnings.forEach { PackForgeLog.w(TAG, "      • $it") }
        }
        PackForgeLog.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        return ValidationResult(
            missingTextures,
            missingModels,
            fixedReferences,
            langKeysAdded,
            soundsFixed,
            warnings
        )
    }

    /**
     * VALIDACIÓN 1: Referencias de Items (BP → RP)
     */
    private fun validateItemReferences(bpDir: File, rpDir: File, addonDirs: List<File>): Int {
        var fixedCount = 0
        val itemsDir = File(bpDir, "items")
        
        if (!itemsDir.exists()) {
            PackForgeLog.d(TAG, "   No existe carpeta items en BP")
            return 0
        }

        val texturesItemsDir = File(rpDir, "textures/items")
        texturesItemsDir.mkdirs()

        itemsDir.listFiles()?.filter { it.extension == "json" }?.forEach { itemFile ->
            try {
                val json = JSONObject(itemFile.readText())
                val iconObj = json.optJSONObject("minecraft:icon")
                val textureName = iconObj?.optString("texture")
                
                if (textureName != null) {
                    val expectedTexture = File(texturesItemsDir, "$textureName.png")
                    
                    if (!expectedTexture.exists()) {
                        PackForgeLog.w(TAG, "⚠️ Item sin textura: ${itemFile.name} -> texture:$textureName")
                        
                        // Buscar recursivamente en todos los addons
                        val found = searchAndCopyTexture(textureName, addonDirs, texturesItemsDir)
                        if (found) {
                            fixedCount++
                            PackForgeLog.d(TAG, "✅ Textura reparada: $textureName.png")
                        } else {
                            PackForgeLog.w(TAG, "❌ Textura no encontrada: $textureName.png")
                        }
                    }
                }
            } catch (e: Exception) {
                PackForgeLog.e(TAG, "Error validando item ${itemFile.name}: ${e.message}")
            }
        }

        PackForgeLog.d(TAG, "   Items validados: ${fixedCount} texturas reparadas")
        return fixedCount
    }

    /**
     * VALIDACIÓN 2: Referencias de Bloques (BP → RP)
     */
    private fun validateBlockReferences(bpDir: File, rpDir: File, addonDirs: List<File>): Int {
        var fixedCount = 0
        val blocksDir = File(bpDir, "blocks")
        
        if (!blocksDir.exists()) {
            PackForgeLog.d(TAG, "   No existe carpeta blocks en BP")
            return 0
        }

        val terrainTextureFile = File(rpDir, "textures/terrain_texture.json")
       val texturesBlocksDir = File(rpDir, "textures/blocks")
        texturesBlocksDir.mkdirs()

        blocksDir.listFiles()?.filter { it.extension == "json" }?.forEach { blockFile ->
            try {
                val json = JSONObject(blockFile.readText())
                val components = json.optJSONObject("components")
                val minecraftBlock = components?.optJSONObject("minecraft:block")
                val texturesObj = minecraftBlock?.optJSONObject("textures")
                
                texturesObj?.keys()?.forEach { textureKey ->
                    val textureName = texturesObj.optString(textureKey)
                    
                    if (textureName.isNotEmpty()) {
                        val expectedTexture = File(texturesBlocksDir, "$textureName.png")
                        
                        if (!expectedTexture.exists()) {
                            PackForgeLog.w(TAG, "⚠️ Bloque sin textura: ${blockFile.name} -> $textureKey:$textureName")
                            
                            // Buscar recursivamente en todos los addons
                            val found = searchAndCopyTexture(textureName, addonDirs, texturesBlocksDir)
                            if (found) {
                                fixedCount++
                                PackForgeLog.d(TAG, "✅ Textura de bloque reparada: $textureName.png")
                            } else {
                                PackForgeLog.w(TAG, "❌ Textura de bloque no encontrada: $textureName.png")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                PackForgeLog.e(TAG, "Error validando bloque ${blockFile.name}: ${e.message}")
            }
        }

        PackForgeLog.d(TAG, "   Bloques validados: ${fixedCount} texturas reparadas")
        return fixedCount
    }

    /**
     * VALIDACIÓN 3: Referencias de Entidades (RP ↔ RP)
     */
    private fun validateEntityReferences(
        rpDir: File,
        addonDirs: List<File>,
        missingTextures: MutableList<String>,
        missingModels: MutableList<String>
    ): Int {
        var fixedCount = 0
        val entityDir = File(rpDir, "entity")
        
        if (!entityDir.exists()) {
            PackForgeLog.d(TAG, "   No existe carpeta entity en RP")
            return 0
        }

        val texturesEntityDir = File(rpDir, "textures/entity")
        texturesEntityDir.mkdirs()
        
        val modelsEntityDir = File(rpDir, "models/entity")
        modelsEntityDir.mkdirs()
        
        val animationsDir = File(rpDir, "animations")
        animationsDir.mkdirs()

        entityDir.listFiles()?.filter { it.name.endsWith(".entity.json") }?.forEach { entityFile ->
            try {
                val json = JSONObject(entityFile.readText())
                
                // Validar texturas
                val texturesObj = json.optJSONObject("textures")
                texturesObj?.keys()?.forEach { textureKey ->
                    val texturePath = texturesObj.optString(textureKey)
                    val textureName = texturePath.substringAfterLast("/")
                    val expectedTexture = File(texturesEntityDir, "$textureName.png")
                    
                    if (!expectedTexture.exists()) {
                        PackForgeLog.w(TAG, "⚠️ Entidad sin textura: ${entityFile.name} -> $textureKey:$texturePath")
                        
                        val found = searchAndCopyTexture(textureName, addonDirs, texturesEntityDir)
                        if (found) {
                            fixedCount++
                        } else {
                            missingTextures.add("textures/entity/$textureName.png")
                        }
                    }
                }
                
                // Validar geometría
                val geometryStr = json.optString("geometry")
                if (geometryStr.isNotEmpty()) {
                    val geometryName = geometryStr.substringAfterLast(".")
                    val expectedGeo = File(modelsEntityDir, "$geometryName.geo.json")
                    
                    if (!expectedGeo.exists()) {
                        PackForgeLog.w(TAG, "⚠️ Entidad sin geometría: ${entityFile.name} -> $geometryStr")
                        
                        val found = searchAndCopyFile(geometryName, ".geo.json", addonDirs, modelsEntityDir)
                        if (!found) {
                            missingModels.add("models/entity/$geometryName.geo.json")
                        }
                    }
                }
                
                // Validar animaciones
                val animationsObj = json.optJSONObject("animations")
                animationsObj?.keys()?.forEach { animKey ->
                    val animPath = animationsObj.optString(animKey)
                    val animName = animPath.substringAfterLast("/")
                    val expectedAnim = File(animationsDir, "$animName.animation.json")
                    
                    if (!expectedAnim.exists()) {
                        PackForgeLog.w(TAG, "⚠️ Entidad sin animación: ${entityFile.name} -> $animKey:$animPath")
                        
                        val found = searchAndCopyFile(animName, ".animation.json", addonDirs, animationsDir)
                        if (found) {
                            fixedCount++
                        }
                    }
                }
            } catch (e: Exception) {
                PackForgeLog.e(TAG, "Error validando entidad ${entityFile.name}: ${e.message}")
            }
        }

        PackForgeLog.d(TAG, "   Entidades validadas: ${fixedCount} referencias reparadas")
        return fixedCount
    }

    /**
     * VALIDACIÓN 4: Archivos .lang (concatenar)
     */
    private fun mergeLangFiles(addonDirs: List<File>, destDir: File): Map<String, Int> {
        val langMap = mutableMapOf<String, MutableMap<String, String>>()
        
        addonDirs.forEach { addonDir ->
            val textsDir = File(addonDir, "texts")
            if (textsDir.exists()) {
                textsDir.listFiles()?.filter { it.extension == "lang" }?.forEach { langFile ->
                    val langName = langFile.nameWithoutExtension // "es_ES", "en_US"
                    val lines = langFile.readLines()
                    
                    lines.forEach { line ->
                        if (line.contains("=") && !line.startsWith("#")) {
                            val parts = line.split("=", limit = 2)
                            if (parts.size == 2) {
                                val key = parts[0].trim()
                                val value = parts[1].trim()
                                langMap.getOrPut(langName) { mutableMapOf() }[key] = value
                            }
                        }
                    }
                }
            }
        }

        // Escribir archivos .lang fusionados
        val destTextsDir = File(destDir, "texts")
        destTextsDir.mkdirs()

        val result = mutableMapOf<String, Int>()
        
        langMap.forEach { (langName, translations) ->
            val langFile = File(destTextsDir, "$langName.lang")
            langFile.writeText(translations.entries.joinToString("\n") { "${it.key}=${it.value}" })
            result[langName] = translations.size
            PackForgeLog.d(TAG, "✅ Fusionado $langName.lang: ${translations.size} claves")
        }

        // ⭐ CRÍTICO: languages.json también debe existir ⭐
        val languagesFile = File(destTextsDir, "languages.json")
        if (!languagesFile.exists()) {
            val languages = langMap.keys.toList()
            languagesFile.writeText(JSONObject().put("languages", JSONArray(languages)).toString(2))
            PackForgeLog.d(TAG, "✅ Creado languages.json con ${languages.size} idiomas")
        }

        return result
    }

    /**
     * VALIDACIÓN 5: sounds.json (concatenar)
     */
    private fun mergeSoundsJson(addonDirs: List<File>, destDir: File): Boolean {
        val merged = JSONObject()
        merged.put("entity_sounds", JSONObject().put("entities", JSONObject()))
        merged.put("block_sounds", JSONObject())
        merged.put("individual_event_sounds", JSONObject())
        
        var foundAny = false
        
        addonDirs.forEach { addonDir ->
            val soundsFile = File(addonDir, "sounds.json")
            if (soundsFile.exists()) {
                foundAny = true
                try {
                    val json = JSONObject(soundsFile.readText())
                    
                    // Fusionar entity_sounds.entities
                    json.optJSONObject("entity_sounds")?.optJSONObject("entities")?.let { entities ->
                        val mergedEntities = merged.getJSONObject("entity_sounds").getJSONObject("entities")
                        entities.keys().forEach { key ->
                            mergedEntities.put(key, entities.get(key))
                        }
                    }
                    
                    // Fusionar block_sounds
                    json.optJSONObject("block_sounds")?.let { blocks ->
                        val mergedBlocks = merged.getJSONObject("block_sounds")
                        blocks.keys().forEach { key ->
                            mergedBlocks.put(key, blocks.get(key))
                        }
                    }
                    
                    // Fusionar individual_event_sounds
                    json.optJSONObject("individual_event_sounds")?.let { events ->
                        val mergedEvents = merged.getJSONObject("individual_event_sounds")
                        events.keys().forEach { key ->
                            mergedEvents.put(key, events.get(key))
                        }
                    }
                } catch (e: Exception) {
                    PackForgeLog.w(TAG, "Error fusionando sounds.json de ${addonDir.name}: ${e.message}")
                }
            }
        }
        
        if (foundAny) {
            val destFile = File(destDir, "sounds.json")
            destFile.writeText(merged.toString(2))
            PackForgeLog.d(TAG, "✅ sounds.json fusionado correctamente")
            return true
        }
        
        PackForgeLog.d(TAG, "ℹ️ No se encontraron archivos sounds.json")
        return false
    }

    /**
     * VALIDACIÓN 6: Render controllers y animaciones
     */
    private fun validateRenderControllers(
        rpDir: File,
        addonDirs: List<File>,
        missingTextures: MutableList<String>
    ): Int {
        var fixedCount = 0
        
        // Validar render_controllers
        val renderControllersDir = File(rpDir, "render_controllers")
        if (renderControllersDir.exists()) {
            renderControllersDir.listFiles()?.filter { it.extension == "json" }?.forEach { rcFile ->
                try {
                    val content = rcFile.readText()
                    // Buscar referencias a texturas en Query.texture
                    val textureMatches = Regex("Query\\.texture\\(['\"]([^'\"]+)['\"]\\)").findAll(content)
                    
                    textureMatches.forEach { match ->
                        val textureName = match.groupValues[1]
                        val texturesEntityDir = File(rpDir, "textures/entity")
                        val expectedTexture = File(texturesEntityDir, "$textureName.png")
                        
                        if (!expectedTexture.exists()) {
                            PackForgeLog.w(TAG, "⚠️ Render controller sin textura: ${rcFile.name} -> $textureName")
                            
                            val found = searchAndCopyTexture(textureName, addonDirs, texturesEntityDir)
                            if (found) {
                                fixedCount++
                            } else {
                                missingTextures.add("textures/entity/$textureName.png")
                            }
                        }
                    }
                } catch (e: Exception) {
                    PackForgeLog.e(TAG, "Error validando render controller ${rcFile.name}: ${e.message}")
                }
            }
        }
        
        PackForgeLog.d(TAG, "   Render controllers validados: ${fixedCount} texturas reparadas")
        return fixedCount
    }

    /**
     * Busca una textura recursivamente en todos los addons y la copia al destino
     */
    private fun searchAndCopyTexture(
        textureName: String,
        addonDirs: List<File>,
        destDir: File
    ): Boolean {
        val extensions = listOf(".png", ".tga", ".jpg")
        
        for (ext in extensions) {
            for (addonDir in addonDirs) {
                val found = addonDir.walkTopDown().find { 
                    it.isFile && 
                    it.name.equals("$textureName$ext", ignoreCase = true)
                }
                
                if (found != null) {
                    val destFile = File(destDir, "$textureName.png")
                    found.copyTo(destFile, overwrite = true)
                    PackForgeLog.d(TAG, "   Textura encontrada en: ${found.relativeTo(addonDir).path}")
                    return true
                }
            }
        }
        
        return false
    }

    /**
     * Busca un archivo recursivamente en todos los addons y lo copia al destino
     */
    private fun searchAndCopyFile(
        fileName: String,
        extension: String,
        addonDirs: List<File>,
        destDir: File
    ): Boolean {
        for (addonDir in addonDirs) {
            val found = addonDir.walkTopDown().find { 
                it.isFile && 
                it.name.equals("$fileName$extension", ignoreCase = true)
            }
            
            if (found != null) {
                val destFile = File(destDir, "$fileName$extension")
                found.copyTo(destFile, overwrite = true)
                PackForgeLog.d(TAG, "   Archivo encontrado en: ${found.relativeTo(addonDir).path}")
                return true
            }
        }
        
        return false
    }
}
