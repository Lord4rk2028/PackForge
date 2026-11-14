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
        val incompleteEntities: List<String>,
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
            callback?.onProgress(
                "Validando referencias... ($processed/$total)",
                if (total > 0) (85 + processed * 4 / total).coerceIn(85, 89) else -1
            )
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
        DirIndexCache.index(rpDir).allFiles.forEach { file ->
            idx.rpIndex.add(file.relativeTo(rpDir).invariantSeparatorsPath)
            idx.rpByName.putIfAbsent(file.name.lowercase(), file)
        }

        // UNA sola pasada por los addons originales (solo el índice por nombre,
        // reemplaza los walkTopDown().find{} de antes que corrían dentro de bucles)
        addonDirs.forEach { addonDir ->
            if (!addonDir.exists()) return@forEach
            DirIndexCache.index(addonDir).allFiles.forEach { file ->
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
        identifierIndex: BedrockIdentifierIndex? = null,
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
        val incompleteEntities = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        var fixedReferences = 0
        val langKeysAdded = mutableMapOf<String, Int>()
        var soundsFixed = false

        if (bpDir == null || !bpDir.exists()) {
            PackForgeLog.e(TAG, "❌ BP dir no existe")
            return ValidationResult(
                missingTextures = emptyList(),
                missingModels = emptyList(),
                incompleteEntities = emptyList(),
                fixedReferences = 0,
                langKeysAdded = emptyMap(),
                soundsFixed = false,
                warnings = listOf("BP dir no existe")
            )
        }

        if (rpDir == null || !rpDir.exists()) {
            PackForgeLog.e(TAG, "❌ RP dir no existe")
            return ValidationResult(
                missingTextures = emptyList(),
                missingModels = emptyList(),
                incompleteEntities = emptyList(),
                fixedReferences = 0,
                langKeysAdded = emptyMap(),
                soundsFixed = false,
                warnings = listOf("RP dir no existe")
            )
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
        fixedReferences += validateEntityReferences(rpDir, idx, identifierIndex ?: BedrockIdentifierIndex(emptyMap()), missingTextures, missingModels, progressCallback)

        // VALIDACIÓN 4: Archivos .lang (CRÍTICO - "desconocido")
        PackForgeLog.d(TAG, "📦 VALIDACIÓN 4: Archivos .lang (concatenar)")
        langKeysAdded.putAll(mergeLangFiles(addonDirs, rpDir))

        // VALIDACIÓN 5: sounds.json (concatenar)
        PackForgeLog.d(TAG, "📦 VALIDACIÓN 5: sounds.json (concatenar)")
        soundsFixed = mergeSoundsJson(addonDirs, rpDir)

        // VALIDACIÓN 6: Render controllers y animaciones
        PackForgeLog.d(TAG, "📦 VALIDACIÓN 6: Render controllers y animaciones")
        fixedReferences += validateRenderControllers(rpDir, idx, identifierIndex ?: BedrockIdentifierIndex(emptyMap()), missingTextures)

        // VALIDACIÓN 7: Integridad por entidad — una entidad sin geometría,
        // texturas o render controller NO se renderiza aunque el resto del
        // pack sea válido. Acumula en las mismas listas de faltantes.
        PackForgeLog.d(TAG, "📦 VALIDACIÓN 7: Integridad de entidades (geometría/texturas)")
        try {
            validateEntityIntegrity(bpDir, rpDir, idx, incompleteEntities, missingTextures, missingModels, warnings)
        } catch (e: Exception) {
            PackForgeLog.w(TAG, "Validación de integridad de entidades no bloqueante: ${e.message}")
        }

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
        PackForgeLog.d(TAG, "   Entidades incompletas: ${incompleteEntities.size}")
        incompleteEntities.forEach { PackForgeLog.w(TAG, "      • $it") }
        PackForgeLog.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        PackForgeLog.d("PackForge_Perf", "⏱️ Validador: ${(tValidadorFin - tValidador) / 1000.0}s")

        return ValidationResult(
            missingTextures = missingTextures,
            missingModels = missingModels,
            incompleteEntities = incompleteEntities,
            fixedReferences = fixedReferences,
            langKeysAdded = langKeysAdded,
            soundsFixed = soundsFixed,
            warnings = warnings
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
        identifierIndex: BedrockIdentifierIndex,
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

        val modelsEntityDir = File(rpDir, "models/entity")
        modelsEntityDir.mkdirs()

        val animationsDir = File(rpDir, "animations")
        animationsDir.mkdirs()

        // ⭐ DUCK TYPING: se aceptan TODOS los .json de entity/ y se clasifican por
        // contenido raíz "minecraft:client_entity" (NO por extensión .entity.json),
        // para no ignorar entidades con nombres ofuscados (ej: .fT.json).
        val entityFiles = entityDir.listFiles()
            ?.filter { it.extension.equals("json", ignoreCase = true) }
            ?.toList() ?: emptyList()
        var processed = 0

        for (entityFile in entityFiles) {
            try {
                val json = JSONObject(entityFile.readText())
                if (!json.has("minecraft:client_entity")) continue
                val desc = json.optJSONObject("minecraft:client_entity")?.optJSONObject("description") ?: json.optJSONObject("description")

                // Validar texturas.
                // - Solo strings: un valor de textura puede ser un array (variantes)
                //   y `optString` lo serializaba como "[...]" → falso "sin textura".
                // - Se comprueba la RUTA COMPLETA, no solo el nombre del archivo:
                //   antes cualquier textura fuera de textures/entity/ se daba por
                //   ausente y se copiaba a la carpeta equivocada.
                val texturesObj = json.optJSONObject("textures") ?: desc?.optJSONObject("textures")
                texturesObj?.keys()?.forEach { textureKey ->
                    val raw = texturesObj.opt(textureKey) as? String ?: return@forEach
                    if (BedrockRefs.isAlias(raw) || BedrockRefs.isExpression(raw)) return@forEach
                    val texturePath = raw.replace("\\", "/").trimStart('/')
                    if (texturePath.isEmpty() || VanillaAssets.isVanillaTexture(texturePath)) return@forEach

                    if (!indexHasTexture(idx, texturePath)) {
                        logFile { "⚠️ Entidad sin textura: ${entityFile.name} -> $textureKey:$texturePath" }

                        val found = searchAndCopyTextureToPath(texturePath, rpDir, idx)
                        if (found) {
                            fixedCount++
                        } else {
                            missingTextures.add(texturePath)
                        }
                    }
                }

                // Validar geometría por IDENTIFICADOR (no por nombre de archivo).
                // BedrockRefs descarta alias y Molang, que antes se buscaban como
                // si fueran modelos.
                val geoSource = desc?.opt("geometry") ?: json.opt("geometry")
                val geometryIds = BedrockRefs.geometryIds(geoSource)
                geometryIds.forEach { geometryId ->
                    if (VanillaAssets.isVanillaGeometry(geometryId)) return@forEach
                    logFile { "🔍 Buscando geometría por identificador: $geometryId" }

                    val geoFile = identifierIndex.resolve(geometryId)
                    if (geoFile != null) {
                        // ⭐ FASE 5 — BORRADO SILENCIOSO DE 72 GEOMETRÍAS (bug real,
                        // medido con core-craft: 155 modelos antes del validador,
                        // 83 después).
                        //
                        // La geometría ya vive en models/entity del pack fusionado,
                        // así que `identifierIndex.resolve` devuelve ESE MISMO
                        // archivo. `File.copyTo(dest, overwrite = true)` de Kotlin
                        // BORRA el destino antes de copiar: con origen == destino se
                        // borraba a sí mismo y lanzaba NoSuchFileException, que el
                        // catch de abajo se tragaba. Cada entidad validada aniquilaba
                        // su propia geometría en silencio, y el paquete entregado
                        // salía sin los modelos (y el reporte luego decía que
                        // faltaban, ¡porque los acababa de borrar!).
                        //
                        // Si el archivo ya está en su sitio, no hay nada que copiar:
                        // sólo hay que marcarlo como presente.
                        val destFile = File(modelsEntityDir, geoFile.name)
                        if (destFile.canonicalFile == geoFile.canonicalFile) {
                            idx.markCopiedInRp(destFile)
                            logFile { "✅ Geometría ya en su sitio: ${geoFile.name}" }
                        } else {
                            geoFile.copyTo(destFile, overwrite = true)
                            idx.markCopiedInRp(destFile)
                            fixedCount++
                            logFile { "✅ Geometría encontrada en índice y copiada: ${geoFile.name}" }
                        }
                    } else {
                        missingModels.add("$geometryId (por identificador)")
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
        identifierIndex: BedrockIdentifierIndex,
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
                    
                    // Validar referencias a materiales/render controllers (opcional, basado en identifierIndex)
                    // ...
                } catch (e: Exception) {
                    logFile { "Error validando render controller ${rcFile.name}: ${e.message}" }
                }
            }
        }

        PackForgeLog.d(TAG, "   Render controllers validados: ${fixedCount} texturas reparadas")
        return fixedCount
    }

    /**
     * VALIDACIÓN 7: INTEGRIDAD POR ENTIDAD.
     *
     * Una entidad puede existir como JSON válido y aun así NO renderizarse si
     * le falta alguna dependencia obligatoria. Comprueba por cada
     * client_entity del RP: definición en BP, geometría por IDENTIFICADOR
     * dentro de models/ (no por nombre de archivo: el decifrador restaura
     * `carryblock.geometry.json` pero la referencia es `geometry.carryblock`,
     * origen de los "Modelos faltantes: 5"), texturas reales excluyendo
     * vanilla, render controllers y materiales con namespace.
     *
     * Parsea con LenientJson: los archivos pueden conservar el cebo
     * closed-source (comentario inicial + claves duplicadas). Nunca lanza:
     * la validación NO bloquea la exportación.
     */
    private fun validateEntityIntegrity(
        bpDir: File,
        rpDir: File,
        idx: PackIndex,
        incompleteEntities: MutableList<String>,
        missingTextures: MutableList<String>,
        missingModels: MutableList<String>,
        warnings: MutableList<String>
    ) {
        fun loadJson(f: File): JSONObject? = try {
            LenientJson.parseObject(f.readText(Charsets.UTF_8))
        } catch (e: Exception) { null }

        // ── Inventario 1: identificadores de geometría reales ──
        //
        // ⭐ FASE 5 (falso positivo masivo, visible en Better MC): hay DOS
        // formatos de geometría en Bedrock y aquí solo se leía uno.
        //
        //  · Moderno: { "minecraft:geometry": [ { "description": { "identifier": … } } ] }
        //  · Legacy:  { "geometry.cc.pillager": { "bones": [ … ] } }
        //
        // CoreCraft usa el legacy. Al no indexarlo, sus geometrías (que SÍ están
        // en models/) se declaraban ausentes: de ahí los "438 modelos faltantes"
        // y las 80 entidades incompletas del reporte. Eran falsos.
        val geometryIds = mutableSetOf<String>()
        DirIndexCache.index(File(rpDir, "models")).allFiles
            .filter { it.isFile && it.name.endsWith(".json", true) }
            .forEach { f ->
                val json = loadJson(f) ?: return@forEach

                // Formato moderno.
                json.optJSONArray("minecraft:geometry")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val id = arr.optJSONObject(i)
                            ?.optJSONObject("description")?.optString("identifier")
                        if (!id.isNullOrBlank()) geometryIds.add(id)
                    }
                }

                // Formato legacy: claves raíz "geometry.*".
                json.keys().forEach { key ->
                    if (key.startsWith("geometry.") && json.optJSONObject(key) != null) {
                        geometryIds.add(key)
                    }
                }
            }

        // ── Inventario 2: render controllers declarados en el RP ──
        val rcIds = mutableSetOf<String>()
        DirIndexCache.index(File(rpDir, "render_controllers")).allFiles
            .filter { it.isFile && it.name.endsWith(".json", true) }
            .forEach { f ->
                (loadJson(f) ?: return@forEach).optJSONObject("render_controllers")
                    ?.keys()?.forEach { rcIds.add(it) }
            }

        // ── Inventario 3: entidades declaradas en el BP ──
        val bpEntityIds = mutableSetOf<String>()
        DirIndexCache.index(File(bpDir, "entities")).allFiles
            .filter { it.isFile && it.name.endsWith(".json", true) }
            .forEach { f ->
                val id = (loadJson(f) ?: return@forEach)
                    .optJSONObject("minecraft:entity")
                    ?.optJSONObject("description")?.optString("identifier")
                if (!id.isNullOrBlank()) bpEntityIds.add(id)
            }

        // ── Inventario 4: materiales custom en materials/ ──
        //
        // ⭐ FASE 5 (falso positivo real, visible en el reporte de Better MC):
        // Bedrock NO guarda los materiales en `.json`: los guarda en
        // `materials/*.material` (p.ej. `entity.material`), que es JSON pero con
        // extensión propia. Al filtrar por `.json`, este inventario quedaba
        // SIEMPRE vacío y cualquier `render_controllers` con material con
        // namespace se reportaba como roto — aunque el material estuviera ahí.
        //
        // Se aceptan ambas extensiones (hay packs que sí usan .json).
        val customMaterials = mutableSetOf<String>()
        val materialRoot = File(rpDir, "materials")
        if (materialRoot.isDirectory) {
            DirIndexCache.index(materialRoot).allFiles
                .filter {
                    it.isFile && (it.name.endsWith(".json", true) ||
                        it.name.endsWith(".material", true))
                }
                .forEach { f ->
                    (loadJson(f) ?: return@forEach).optJSONObject("materials")
                        ?.keys()?.forEach { k ->
                            // "version" es un campo de cabecera, no un material.
                            if (k != "version") customMaterials.add(k)
                        }
                }
        }

        val entityDir = File(rpDir, "entity")
        if (!entityDir.isDirectory) return
        val reported = incompleteEntities.toMutableSet()

        val entityFiles = DirIndexCache.index(entityDir).allFiles
            .filter { it.isFile && it.name.endsWith(".json", true) }

        // ⭐ FASE 5 — Quién usa de verdad cada `client_entity` sin definición en
        // BP, resuelto en UNA sola pasada por el BP y ANTES de recorrer el RP
        // (antes se abría el árbol entero una vez por huérfana).
        //
        // Sólo se enumeran identificadores que el propio pack declara: no hay
        // ninguna lista de entidades vanilla que actualizar cuando Mojang añada
        // un mob. Un re-skin que nada invoca es contenido muerto del origen; uno
        // que una spawn rule o un script invoca es un fallo de verdad.
        val citadas: Map<String, String> = if (bpEntityIds.isEmpty()) {
            emptyMap()
        } else {
            val candidatas = mutableSetOf<String>()
            entityFiles.forEach { f ->
                val desc = loadJson(f)
                    ?.optJSONObject("minecraft:client_entity")
                    ?.optJSONObject("description") ?: return@forEach
                val id = desc.optString("identifier")
                if (id.isNotBlank() && id !in bpEntityIds) candidatas.add(id)
            }
            EntityOrphanClassifier.findReferences(bpDir, candidatas)
        }

        entityFiles.forEach { f ->
                val json = loadJson(f)
                // JSON ilegible: lo reporta el audit de integridad, no duplicar.
                if (json == null) return@forEach
                val desc = json.optJSONObject("minecraft:client_entity")
                    ?.optJSONObject("description") ?: return@forEach
                val ident = desc.optString("identifier").ifBlank { null }
                val problems = mutableListOf<String>()

                // (1) Definición en BP
                //
                // ⭐ FASE 5 — NO toda `client_entity` sin server entity es un
                // defecto de la fusión. Hay dos casos opuestos y antes se
                // sumaban en el mismo contador ("entidades incompletas"),
                // que por eso nunca podía llegar a 0:
                //
                //  · REFERENCIADA: algún archivo del BP la invoca (spawn rule,
                //    script, estructura, otra entidad, /summon). Falta su
                //    `minecraft:entity` → el mob no aparecerá. Defecto real.
                //
                //  · HUÉRFANA: no la nombra absolutamente nadie. Tampoco podía
                //    aparecer con el addon ORIGINAL instalado, así que la
                //    fusión no ha roto nada y no hay nada que reparar sin
                //    inventar un mob. Es contenido muerto del origen.
                //    (Medido en core-craft 1.2.8: las 5 `cc:*_market_villager`
                //    aparecen en 1 solo archivo de 9.405 — el suyo — y el
                //    addon no las cita ni en spawn_rules, ni en scripts, ni
                //    en structures.)
                //
                // ⚠️ Deliberadamente NO se usa un catálogo de entidades vanilla:
                // enumerarlas obliga a mantener una lista a mano con cada
                // actualización del juego (armadillo, breeze, bogged, camel,
                // creaking, happy_ghast…). Las listas a mano de este archivo ya
                // se quedaron cortas una vez y provocaron ~1.000 errores
                // falsos (ver `VANILLA_GEOMETRY_PREFIXES`). La evidencia de si
                // una entidad "existe en el juego" está en el propio pack, no
                // en una tabla que caduca.
                if (bpEntityIds.isNotEmpty() && ident != null && ident !in bpEntityIds) {
                    val cita = citadas[ident]
                    if (cita == null) {
                        warnings.add(
                            "$ident → client_entity huérfano: no hay definición en BP y " +
                                "ningún archivo del pack lo usa (tampoco aparecería con el " +
                                "addon original; contenido muerto del origen)"
                        )
                    } else {
                        problems.add("sin definición en BP (lo usa '$cita')")
                    }
                }

                // (2) Geometría por identificador (no por nombre de archivo).
                // BedrockRefs descarta alias ("Geometry.default"), Molang
                // ("q.is_baby ? …") y mapas de huesos, que antes llegaban aquí
                // como si fueran ids y generaban "modelos faltantes" falsos.
                val geoRefs = BedrockRefs.geometryIds(desc.opt("geometry"))
                for (geoRef in geoRefs) {
                    if (geoRef !in geometryIds && !VanillaAssets.isVanillaGeometry(geoRef)) {
                        problems.add("geometría '$geoRef' no existe en models/")
                        if (missingModels.none { it.contains(geoRef) }) {
                            missingModels.add("models/entity/$geoRef (por identificador)")
                        }
                    }
                }

                // (3) Texturas reales, excluyendo vanilla y placeholders Molang
                desc.optJSONObject("textures")?.let { texObj ->
                    texObj.keys().forEach { part ->
                        val tex = texObj.opt(part) as? String ?: return@forEach
                        if (tex.startsWith("Array.") || tex.startsWith("variable.") ||
                            tex.startsWith("query.") || tex.startsWith("Texture.")
                        ) return@forEach
                        if (!tex.startsWith("textures/")) return@forEach
                        if (VanillaAssets.isVanillaTexture(tex)) return@forEach
                        if (idx.has("$tex.png") || idx.has("$tex.tga") ||
                            idx.has("$tex.jpg")
                        ) return@forEach
                        problems.add("textura '$tex' no existe")
                        if (tex !in missingTextures) missingTextures.add("$tex.png")
                    }
                }

                // (4) Render controllers, excluyendo vanilla.
                // El id está en la CLAVE de los objetos condicionales
                // ({"controller.render.x":"q.variant > 0"}); `optString` los
                // serializaba y los comparaba como si fueran nombres → 161
                // entidades "incompletas" falsas en el reporte de Better MC.
                val rcRefs = BedrockRefs.renderControllerIds(desc.opt("render_controllers"))
                for (rc in rcRefs) {
                    if (VanillaAssets.isVanillaRenderController(rc)) continue
                    if (rc !in rcIds && rcIds.none {
                            it.startsWith("$rc.") || rc.startsWith("$it.")
                        }
                    ) {
                        problems.add("render controller '$rc' no existe")
                    }
                }

                // (5) Materiales con namespace; sin namespace = vanilla
                desc.optJSONObject("materials")?.let { matObj ->
                    matObj.keys().forEach { part ->
                        val mat = matObj.opt(part) as? String ?: return@forEach
                        if (mat.contains(':') && mat !in customMaterials) {
                            problems.add("material custom '$mat' no existe")
                        }
                    }
                }

                if (problems.isNotEmpty() && ident != null) {
                    val entry = "$ident → ${problems.joinToString("; ")}"
                    if (reported.add(entry)) incompleteEntities.add(entry)
                }
            }
    }

    private val TEXTURE_EXTENSIONS = listOf(".png", ".tga", ".jpg")

    /**
     * Busca una textura O(1) en el índice (RP fusionado y luego addons originales)
     * y la copia al destino con fastCopy (FileChannel.transferFrom, REGLA 4).
     */
    /**
     * ¿La textura referenciada existe en el índice, con o sin extensión?
     * Bedrock referencia las texturas SIN extensión, así que hay que probar los
     * formatos soportados antes de darla por ausente.
     */
    private fun indexHasTexture(idx: PackIndex, texturePath: String): Boolean {
        val clean = texturePath.trim().replace("\\", "/").trimStart('/')
        if (idx.has(clean)) return true
        if (TEXTURE_EXTENSIONS.any { idx.has("$clean$it") }) return true
        val withoutExt = TEXTURE_EXTENSIONS.firstOrNull { clean.endsWith(it) }
            ?.let { clean.removeSuffix(it) }
        return withoutExt != null && withoutExt != clean && idx.has(withoutExt)
    }

    /**
     * Copia la textura a la RUTA REFERENCIADA, no a `textures/entity` fijo.
     *
     * Antes: `textures/blocks/dirt` se copiaba a `textures/entity/dirt.png`; la
     * referencia seguía rota y el healer la contaba como reparada. El reporte de
     * Better MC lo mostraba como "7 texturas copiadas" que no arreglaban nada.
     */
    private fun searchAndCopyTextureToPath(texturePath: String, rpDir: File, idx: PackIndex): Boolean {
        val clean = texturePath.trim().replace("\\", "/").trimStart('/').removePrefix("./")
            .let { if (it.startsWith("textures/")) it else "textures/$it" }
        val stem = TEXTURE_EXTENSIONS.firstOrNull { clean.endsWith(it) }
            ?.let { clean.removeSuffix(it) } ?: clean
        val dest = File(rpDir, "$stem.png")
        if (dest.isFile) return true

        val baseName = stem.substringAfterLast('/')
        for (ext in TEXTURE_EXTENSIONS) {
            val found = idx.findByName("$baseName$ext") ?: continue
            dest.parentFile?.mkdirs()
            FileUtils.fastCopy(found, dest)
            idx.markCopiedInRp(dest)
            logFile { "Textura copiada a su ruta real: ${found.invariantSeparatorsPath} -> $stem.png" }
            return true
        }
        return false
    }

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
            // ⭐ FASE 5: si el archivo encontrado YA es el destino, copiarlo lo
            // truncaría a 0 bytes (ver FastCopy). Ya está en su sitio: basta con
            // marcarlo como presente.
            if (runCatching { found.canonicalFile == destFile.canonicalFile }
                    .getOrDefault(false)
            ) {
                idx.markCopiedInRp(destFile)
                return true
            }
            FileUtils.fastCopy(found, destFile)
            idx.markCopiedInRp(destFile)
            logFile { "Archivo encontrado en índice: ${found.invariantSeparatorsPath} -> ${destFile.name}" }
            return true
        }
        return false
    }
}
