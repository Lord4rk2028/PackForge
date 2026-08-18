package com.packforge.app.domain.engine

import com.packforge.app.util.FileUtils
import com.packforge.app.util.PackForgeLog
import com.packforge.app.util.logFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Validador de referencias cruzadas entre BP y RP
 * Ejecuta DESPUÉS de la fusión y ANTES de crear el ZIP
 *
 * RENDIMIENTO: construye UN índice en memoria al inicio (REGLA 1) y NUNCA hace
 * walkTopDown()/listFiles() recursivos ni File().exists() con rutas construidas
 * dentro de bucles sobre los JSONs. Cada lookup es O(1).
 */
object PackForgeValidator {
    private const val TAG = "PackForge_Validator"
    private const val EMIT_INTERVAL_MS = 250L // máx. 4 updates/segundo

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

    // ─────────────────────────────────────────────────────────────────────
    // REGLA 2: PROGRESO CON THROTTLE (máx. 4 updates/segundo)
    // PROHIBIDO: withContext(Dispatchers.Main) por archivo o emitir el
    // StateFlow por cada archivo (20.000 emisiones = UI colapsada).
    // ─────────────────────────────────────────────────────────────────────
    data class ProgressState(val processed: Int, val total: Int)

    private val _progressState = MutableStateFlow(ProgressState(0, 0))
    val progressState: StateFlow<ProgressState> = _progressState.asStateFlow()

    private var lastEmit = 0L

    /**
     * Emite progreso solo si pasaron 250ms desde la última emisión (o es el final).
     * Cero costo cuando está throttleado: no se construye el string ni se toca la UI.
     */
    private suspend fun emitProgress(
        callback: PackForgeOrchestrator.ProgressCallback?,
        processed: Int,
        total: Int
    ) {
        if (total <= 0) return
        val now = System.currentTimeMillis()
        if (now - lastEmit >= EMIT_INTERVAL_MS || processed == total) {
            lastEmit = now
            _progressState.value = ProgressState(processed, total)
            callback?.onProgress("Validando referencias... ($processed/$total)")
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // REGLA 1: ÍNDICE EN MEMORIA — se construye UNA sola vez, al iniciar la
    // validación. Todo lookup posterior es O(1).
    // ─────────────────────────────────────────────────────────────────────
    private class PackIndex(val rpRoot: File) {
        /** Rutas relativas exactas de todos los archivos del RP fusionado. */
        val rpIndex = HashSet<String>(30000)
        /** Nombre de archivo en minúsculas -> archivo (búsqueda "difusa" en el RP). */
        val rpByName = HashMap<String, File>(30000)
        /** Nombre de archivo en minúsculas -> archivo original en los addons. */
        val addonByName = HashMap<String, File>(30000)

        fun has(rpRelativePath: String): Boolean = rpIndex.contains(rpRelativePath)

        /**
         * Búsqueda O(1) por nombre de archivo: primero en el RP fusionado
         * (puede estar en otra carpeta) y luego en los addons originales.
         */
        fun findByName(fileName: String): File? {
            val key = fileName.lowercase()
            return rpByName[key] ?: addonByName[key]
        }

        /** Actualiza el índice después de copiar un archivo dentro del RP. */
        fun markCopiedInRp(dest: File) {
            val rel = dest.relativeTo(rpRoot).invariantSeparatorsPath
            rpIndex.add(rel)
            rpByName[dest.name.lowercase()] = dest
        }
    }

    private fun buildIndex(rpDir: File, addonDirs: List<File>): PackIndex {
        val idx = PackIndex(rpDir)

        // UNA sola pasada por el RP fusionado
        rpDir.walkTopDown().filter { it.isFile }.forEach { file ->
            idx.rpIndex.add(file.relativeTo(rpDir).invariantSeparatorsPath)
            idx.rpByName.putIfAbsent(file.name.lowercase(), file)
        }

        // UNA sola pasada por los addons originales (solo el índice por nombre,
        // reemplaza los walkTopDown().find{} de antes que corrían dentro de bucles)
        addonDirs.forEach { addonDir ->
            if (!addonDir.exists()) return@forEach
            addonDir.walkTopDown().filter { it.isFile }.forEach { file ->
                idx.addonByName.putIfAbsent(file.name.lowercase(), file)
            }
        }

        return idx
    }

    /**
     * Ejecuta todas las validaciones y reparaciones
     */
    suspend fun validate(
        bpDir: File?,
        rpDir: File?,
        originalAddons: List<String>,
        progressCallback: PackForgeOrchestrator.ProgressCallback? = null
    ): ValidationResult {
        val tValidador = System.currentTimeMillis()
        lastEmit = 0L
        _progressState.value = ProgressState(0, 0)

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

        // ⭐ CONSTRUIR ÍNDICE EN MEMORIA UNA SOLA VEZ (REGLA 1) ⭐
        val tIdx = System.currentTimeMillis()
        val idx = buildIndex(rpDir, addonDirs)
        PackForgeLog.d(
            TAG,
            "📋 Índice en memoria construido en ${(System.currentTimeMillis() - tIdx)}ms: " +
                "RP=${idx.rpIndex.size} archivos, byName=${idx.rpByName.size}, addons=${idx.addonByName.size}"
        )

        // VALIDACIÓN 1: Referencias de Items (BP → RP)
        PackForgeLog.d(TAG, "📦 VALIDACIÓN 1: Referencias de Items")
        fixedReferences += validateItemReferences(bpDir, idx, progressCallback)

        // VALIDACIÓN 2 + 2b: Referencias de Bloques y Geometría (BP → RP)
        // Un solo recorrido y un solo parseo por JSON de bloque (REGLA 5)
        PackForgeLog.d(TAG, "📦 VALIDACIÓN 2+2b: Bloques y Geometría")
        fixedReferences += validateBlockReferences(bpDir, idx, progressCallback)

        // VALIDACIÓN 3: Referencias de Entidades (RP ↔ RP)
        PackForgeLog.d(TAG, "📦 VALIDACIÓN 3: Referencias de Entidades")
        fixedReferences += validateEntityReferences(rpDir, idx, missingTextures, missingModels, progressCallback)

        // VALIDACIÓN 4: Archivos .lang (CRÍTICO - "desconocido")
        PackForgeLog.d(TAG, "📦 VALIDACIÓN 4: Archivos .lang (concatenar)")
        langKeysAdded.putAll(mergeLangFiles(addonDirs, rpDir))

        // VALIDACIÓN 5: sounds.json (concatenar)
        PackForgeLog.d(TAG, "📦 VALIDACIÓN 5: sounds.json (concatenar)")
        soundsFixed = mergeSoundsJson(addonDirs, rpDir)

        // VALIDACIÓN 6: Render controllers y animaciones
        PackForgeLog.d(TAG, "📦 VALIDACIÓN 6: Render controllers y animaciones")
        fixedReferences += validateRenderControllers(rpDir, idx, missingTextures)

        val tValidadorFin = System.currentTimeMillis()

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
        PackForgeLog.d("PackForge_Perf", "⏱️ Validador: ${(tValidadorFin - tValidador) / 1000.0}s")

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
     * Existencias al índice (O(1)), jamás File().exists() en el bucle.
     */
    private suspend fun validateItemReferences(
        bpDir: File,
        idx: PackIndex,
        progressCallback: PackForgeOrchestrator.ProgressCallback?
    ): Int {
        var fixedCount = 0
        val itemsDir = File(bpDir, "items")

        if (!itemsDir.exists()) {
            PackForgeLog.d(TAG, "   No existe carpeta items en BP")
            return 0
        }

        val texturesItemsDir = File(idx.rpRoot, "textures/items")
        texturesItemsDir.mkdirs()

        val itemFiles = itemsDir.listFiles()?.filter { it.extension == "json" }?.toList() ?: emptyList()
        var processed = 0

        for (itemFile in itemFiles) {
            try {
                val json = JSONObject(itemFile.readText())
                val iconObj = json.optJSONObject("minecraft:icon")
                val textureName = iconObj?.optString("texture")

                if (textureName != null) {
                    val relPath = "textures/items/$textureName.png"

                    if (!idx.has(relPath)) {
                        logFile { "⚠️ Item sin textura: ${itemFile.name} -> texture:$textureName" }

                        val found = searchAndCopyTexture(textureName, texturesItemsDir, idx)
                        if (found) {
                            fixedCount++
                            logFile { "✅ Textura reparada: $textureName.png" }
                        } else {
                            logFile { "❌ Textura no encontrada: $textureName.png" }
                        }
                    }
                }
            } catch (e: Exception) {
                logFile { "Error validando item ${itemFile.name}: ${e.message}" }
            }

            processed++
            if (processed % 25 == 0 || processed == itemFiles.size) {
                emitProgress(progressCallback, processed, itemFiles.size)
            }
        }

        PackForgeLog.d(TAG, "   Items validados: ${fixedCount} texturas reparadas")
        return fixedCount
    }

    /**
     * VALIDACIÓN 2 + 2b combinadas: Bloques (texturas) y Geometría (BP → RP).
     * Cada JSON de bloque se lee UNA sola vez (REGLA 5): antes se leía dos veces
     * (una en validación 2 y otra en 2b).
     */
    private suspend fun validateBlockReferences(
        bpDir: File,
        idx: PackIndex,
        progressCallback: PackForgeOrchestrator.ProgressCallback?
    ): Int {
        var fixedCount = 0
        val blocksDir = File(bpDir, "blocks")

        if (!blocksDir.exists()) {
            PackForgeLog.d(TAG, "   No existe carpeta blocks en BP")
            return 0
        }

        val texturesBlocksDir = File(idx.rpRoot, "textures/blocks")
        texturesBlocksDir.mkdirs()
        val modelsEntityDir = File(idx.rpRoot, "models/entity")
        modelsEntityDir.mkdirs()
        val modelsBlocksDir = File(idx.rpRoot, "models/blocks")
        modelsBlocksDir.mkdirs()

        val blockFiles = blocksDir.listFiles()?.filter { it.extension == "json" }?.toList() ?: emptyList()
        var processed = 0

        for (blockFile in blockFiles) {
            try {
                // REGLA 5: el JSON se parsea UNA sola vez y se reutiliza para
                // la comprobación de texturas Y de geometría
                val json = JSONObject(blockFile.readText())
                val components = json.optJSONObject("components")

                // ── VALIDACIÓN 2: texturas del bloque ──
                val texturesObj = components?.optJSONObject("minecraft:block")?.optJSONObject("textures")
                texturesObj?.keys()?.forEach { textureKey ->
                    val textureName = texturesObj.optString(textureKey)

                    if (textureName.isNotEmpty()) {
                        val relPath = "textures/blocks/$textureName.png"

                        if (!idx.has(relPath)) {
                            logFile { "⚠️ Bloque sin textura: ${blockFile.name} -> $textureKey:$textureName" }

                            val found = searchAndCopyTexture(textureName, texturesBlocksDir, idx)
                            if (found) {
                                fixedCount++
                                logFile { "✅ Textura de bloque reparada: $textureName.png" }
                            } else {
                                logFile { "❌ Textura de bloque no encontrada: $textureName.png" }
                            }
                        }
                    }
                }

                // ── VALIDACIÓN 2b: geometría del bloque ──
                val geometryRef = extractGeometryReference(components?.opt("minecraft:geometry"))
                    ?: extractGeometryReference(json.opt("minecraft:geometry"))

                if (geometryRef != null) {
                    val geometryName = geometryRef.substringAfterLast(".")
                    if (geometryName.isNotEmpty()) {
                        val existsInEntity = idx.has("models/entity/$geometryName.geo.json")
                        val existsInBlocks = idx.has("models/blocks/$geometryName.geo.json")

                        if (!existsInEntity && !existsInBlocks) {
                            logFile { "⚠️ Bloque sin geometría: ${blockFile.name} -> $geometryRef" }

                            val found = searchAndCopyFile(geometryName, ".geo.json", modelsEntityDir, idx)
                            if (found) {
                                fixedCount++
                                logFile { "✅ Geometría de bloque reparada: $geometryName.geo.json" }
                            } else {
                                logFile { "❌ Geometría de bloque no encontrada: $geometryName.geo.json" }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                logFile { "Error validando bloque ${blockFile.name}: ${e.message}" }
            }

            processed++
            if (processed % 25 == 0 || processed == blockFiles.size) {
                emitProgress(progressCallback, processed, blockFiles.size)
            }
        }

        PackForgeLog.d(TAG, "   Bloques validados (texturas+geometría): ${fixedCount} referencias reparadas")
        return fixedCount
    }

    /**
     * Extrae el valor "geometry.xxx" de una referencia de geometría que puede ser:
     * - JSONObject { "value": "geometry.xxx" } (formato moderno)
     * - String "geometry.xxx" (formato simple)
     */
    private fun extractGeometryReference(value: Any?): String? {
        return when (value) {
            is JSONObject -> {
                val v = value.optString("value")
                if (v.isNotEmpty()) v else null
            }
            is String -> if (value.isNotEmpty()) value else null
            else -> null
        }
    }

    /**
     * VALIDACIÓN 3: Referencias de Entidades (RP ↔ RP)
     */
    private suspend fun validateEntityReferences(
        rpDir: File,
        idx: PackIndex,
        missingTextures: MutableList<String>,
        missingModels: MutableList<String>,
        progressCallback: PackForgeOrchestrator.ProgressCallback?
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

        val entityFiles = entityDir.listFiles()?.filter { it.name.endsWith(".entity.json") }?.toList() ?: emptyList()
        var processed = 0

        for (entityFile in entityFiles) {
            try {
                val json = JSONObject(entityFile.readText())

                // Validar texturas
                val texturesObj = json.optJSONObject("textures")
                texturesObj?.keys()?.forEach { textureKey ->
                    val texturePath = texturesObj.optString(textureKey)
                    val textureName = texturePath.substringAfterLast("/")

                    if (!idx.has("textures/entity/$textureName.png")) {
                        logFile { "⚠️ Entidad sin textura: ${entityFile.name} -> $textureKey:$texturePath" }

                        val found = searchAndCopyTexture(textureName, texturesEntityDir, idx)
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

                    if (!idx.has("models/entity/$geometryName.geo.json")) {
                        logFile { "⚠️ Entidad sin geometría: ${entityFile.name} -> $geometryStr" }

                        val found = searchAndCopyFile(geometryName, ".geo.json", modelsEntityDir, idx)
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

                    if (!idx.has("animations/$animName.animation.json")) {
                        logFile { "⚠️ Entidad sin animación: ${entityFile.name} -> $animKey:$animPath" }

                        val found = searchAndCopyFile(animName, ".animation.json", animationsDir, idx)
                        if (found) {
                            fixedCount++
                        }
                    }
                }
            } catch (e: Exception) {
                logFile { "Error validando entidad ${entityFile.name}: ${e.message}" }
            }

            processed++
            if (processed % 25 == 0 || processed == entityFiles.size) {
                emitProgress(progressCallback, processed, entityFiles.size)
            }
        }

        PackForgeLog.d(TAG, "   Entidades validadas: ${fixedCount} referencias reparadas")
        return fixedCount
    }

    /**
     * VALIDACIÓN 4: Archivos .lang (concatenar)
     *
     * REGLA 5: la fase de archivos críticos YA leyó los .lang originales y escribió
     * la fusión en destDir. Si la salida existe, se reutiliza SIN re-leer las mismas
     * fuentes del disco (evita doble parseo en fases distintas). Solo se leen las
     * fuentes si esa salida no existe (p. ej. pack sin fase de críticos).
     */
    private fun mergeLangFiles(addonDirs: List<File>, destDir: File): Map<String, Int> {
        val destTextsDir = File(destDir, "texts")
        destTextsDir.mkdirs()

        val result = mutableMapOf<String, Int>()

        // Salida de la fase de archivos críticos (fuente única ya fusionada)
        val existingLangFiles = destTextsDir.listFiles()?.filter { it.extension == "lang" }?.toList()
            ?: emptyList()

        if (existingLangFiles.isNotEmpty()) {
            // ⭐ Reutilizar la salida fusionada: contar claves sin re-leer las fuentes ⭐
            existingLangFiles.forEach { langFile ->
                val keyCount = langFile.readLines()
                    .count { it.contains('=') && !it.startsWith('#') && it.isNotBlank() }
                result[langFile.nameWithoutExtension] = keyCount
            }
            logFile { ".lang reutilizados desde fase de archivos críticos: ${existingLangFiles.size} archivos" }
        } else {
            // Fallback: la fase de críticos no produjo .lang (p. ej. sin RPs).
            // Leer las fuentes solo en ese caso.
            val langMap = mutableMapOf<String, MutableMap<String, String>>()

            addonDirs.forEach { addonDir ->
                val textsDir = File(addonDir, "texts")
                if (textsDir.exists()) {
                    textsDir.listFiles()?.filter { it.extension == "lang" }?.forEach { langFile ->
                        val langName = langFile.nameWithoutExtension
                        langFile.readLines().forEach { line ->
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

            langMap.forEach { (langName, translations) ->
                val langFile = File(destTextsDir, "$langName.lang")
                langFile.writeText(translations.entries.joinToString("\n") { "${it.key}=${it.value}" })
                result[langName] = translations.size
                logFile { "✅ Fusionado $langName.lang: ${translations.size} claves (fallback)" }
            }
        }

        // ⭐ CRÍTICO: languages.json también debe existir ⭐
        val languagesFile = File(destTextsDir, "languages.json")
        if (!languagesFile.exists()) {
            val languages = if (result.isNotEmpty()) result.keys.toList() else emptyList()
            languagesFile.writeText(JSONObject().put("languages", JSONArray(languages)).toString())
            PackForgeLog.d(TAG, "✅ Creado languages.json con ${languages.size} idiomas")
        }

        if (result.isNotEmpty()) {
            PackForgeLog.d(TAG, "✅ .lang en RP: ${result.size} idiomas")
        }

        return result
    }

    /**
     * VALIDACIÓN 5: sounds.json (concatenar)
     *
     * REGLA 5: la fase de archivos críticos ya fusionó sounds.json a destDir.
     * Si la salida existe, NO se re-leen los sounds.json originales (doble
     * parseo prohibido). Solo se fusiona desde las fuentes si la salida no existe.
     */
    private fun mergeSoundsJson(addonDirs: List<File>, destDir: File): Boolean {
        val destFile = File(destDir, "sounds.json")

        if (destFile.exists() && destFile.length() > 2) {
            // ⭐ Salida de la fase de archivos críticos: reutilizar sin re-leer fuentes ⭐
            logFile { "sounds.json reutilizado desde fase de archivos críticos (${destFile.length()} bytes)" }
            return true
        }

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
            destFile.writeText(merged.toString()) // ⚡ JSON COMPACTO (sin indentación)
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
        idx: PackIndex,
        missingTextures: MutableList<String>
    ): Int {
        var fixedCount = 0

        // Validar render_controllers
        val renderControllersDir = File(rpDir, "render_controllers")
        if (renderControllersDir.exists()) {
            val texturesEntityDir = File(rpDir, "textures/entity")
            texturesEntityDir.mkdirs()

            renderControllersDir.listFiles()?.filter { it.extension == "json" }?.forEach { rcFile ->
                try {
                    val content = rcFile.readText()
                    // Buscar referencias a texturas en Query.texture
                    val textureMatches = Regex("Query\\.texture\\(['\"]([^'\"]+)['\"]\\)").findAll(content)

                    textureMatches.forEach { match ->
                        val textureName = match.groupValues[1]

                        if (!idx.has("textures/entity/$textureName.png")) {
                            logFile { "⚠️ Render controller sin textura: ${rcFile.name} -> $textureName" }

                            val found = searchAndCopyTexture(textureName, texturesEntityDir, idx)
                            if (found) {
                                fixedCount++
                            } else {
                                missingTextures.add("textures/entity/$textureName.png")
                            }
                        }
                    }
                } catch (e: Exception) {
                    logFile { "Error validando render controller ${rcFile.name}: ${e.message}" }
                }
            }
        }

        PackForgeLog.d(TAG, "   Render controllers validados: ${fixedCount} texturas reparadas")
        return fixedCount
    }

    private val TEXTURE_EXTENSIONS = listOf(".png", ".tga", ".jpg")

    /**
     * Busca una textura O(1) en el índice (RP fusionado y luego addons originales)
     * y la copia al destino con fastCopy (FileChannel.transferFrom, REGLA 4).
     */
    private fun searchAndCopyTexture(textureName: String, destDir: File, idx: PackIndex): Boolean {
        for (ext in TEXTURE_EXTENSIONS) {
            val found = idx.findByName("$textureName$ext")
            if (found != null) {
                val destFile = File(destDir, "$textureName.png")
                FileUtils.fastCopy(found, destFile)
                idx.markCopiedInRp(destFile)
                logFile { "Textura encontrada en índice: ${found.invariantSeparatorsPath} -> ${destFile.name}" }
                return true
            }
        }
        return false
    }

    /**
     * Busca un archivo O(1) en el índice (RP fusionado y luego addons originales)
     * y lo copia al destino con fastCopy (REGLA 4).
     */
    private fun searchAndCopyFile(fileName: String, extension: String, destDir: File, idx: PackIndex): Boolean {
        val found = idx.findByName("$fileName$extension")
        if (found != null) {
            val destFile = File(destDir, "$fileName$extension")
            FileUtils.fastCopy(found, destFile)
            idx.markCopiedInRp(destFile)
            logFile { "Archivo encontrado en índice: ${found.invariantSeparatorsPath} -> ${destFile.name}" }
            return true
        }
        return false
    }
}
