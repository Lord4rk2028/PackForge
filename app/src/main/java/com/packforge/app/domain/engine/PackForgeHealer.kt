package com.packforge.app.domain.engine

import org.json.JSONArray
import com.packforge.app.domain.model.ConflictSeverity
import com.packforge.app.util.FileUtils
import com.packforge.app.util.PackForgeLog
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * PACKFORGE HEALER
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Capa POST-MERGE NO BLOQUEANTE que:
 * 1. Escanea texturas/sounds/particles referenciadas en JSONs del pack fusionado
 * 2. Para cada referencia rota → fuzzy-match contra inventario real
 * 3. Si match >= 0.85 → FuzzyMatch (WARN, sugerencia, NO reescribe)
 * 4. Si match < 0.85 → UnresolvedRef (HIGH, pero NO aborta)
 * 5. Retorna HealReport para logging + ConflictRegistry
 *
 * Filosofía: el healer NUNCA reescribe JSONs ni genera stubs. Solo reporta.
 * El usuario decide si corregir manualmente.
 */
object PackForgeHealer {
    private const val TAG = "PackForge_Healer"
    private const val FUZZY_THRESHOLD = 0.85
    /** Umbral para auto-reparar referencias rotas sin intervención del usuario (>= 95% de similitud). */
    private const val AUTO_REPAIR_THRESHOLD = 0.95
    /** El healer es diagnóstico: nunca debe retener la exportación por miles de avisos similares. */
    private const val MAX_REFERENCE_REPORTS = 100

    // ⭐ OPTIMIZACIÓN: Caches para Levenshtein - evita cómputos redundantes
    private val levenshteinSimilarityCache = ConcurrentHashMap<String, Double>()
    private val levenshteinDistanceCache = ConcurrentHashMap<String, Int>()

    data class FuzzyMatch(
        val missingRef: String,
        val candidate: String,
        val score: Double,
        val file: String
    )

    data class UnresolvedRef(
        val missingRef: String,
        val file: String,
        val reason: String
    )

    data class HealReport(
        val fuzzyMatches: List<FuzzyMatch>,
        val unresolved: List<UnresolvedRef>,
        val notes: List<String>
    ) {
        val hasIssues get() = fuzzyMatches.isNotEmpty() || unresolved.isNotEmpty()
        val summary get() = "Healer: ${fuzzyMatches.size} fuzzy-match(es), ${unresolved.size} referencia(s) irrecuperable(es)"
    }

    /** Resultado de una operación de reparación automática. */
    data class RepairResult(
        val texturesCopied: Int = 0,
        val modelsCopied: Int = 0,
        /** Referencias de textura auto-reparadas por fuzzy-match >= 0.95. */
        val autoRepairedReferences: Int = 0,
        /** Geometría placeholder generada (caja 8x8x8) para entidades sin geometría en ningún addon. */
        val placeholdersGenerated: Int = 0,
        /**
         * Placeholders de una fusión anterior retirados a `_packforge/` porque
         * duplicaban un identificador que el pack ya define con un modelo real.
         */
        val stalePlaceholdersRemoved: Int = 0,
        val stillMissingTextures: List<String> = emptyList(),
        val stillMissingModels: List<String> = emptyList()
    ) {
        val totalFixed get() = texturesCopied + modelsCopied + autoRepairedReferences
        val hasRemainingIssues get() = stillMissingTextures.isNotEmpty() || stillMissingModels.isNotEmpty()
        val summary get() = "Healer.repair: ${texturesCopied} textura(s) copiada(s), " +
            "${modelsCopied} modelo(s) copiado(s) [$placeholdersGenerated placeholder(s) de geometría], " +
            "${stalePlaceholdersRemoved} placeholder(s) obsoleto(s) retirado(s), " +
            "${autoRepairedReferences} referencia(s) auto-reparada(s), " +
            "${stillMissingTextures.size + stillMissingModels.size} aún faltante(s)"
    }

    /**
     * Repara texturas y modelos faltantes copiándolos de los addons originales.
     *
     * Para cada referencia rota detectada, busca el archivo en los addons
     * originales (por nombre de archivo) y, si lo encuentra, lo copia a
     * la ruta correcta en el RP/BP fusionado.
     *
     * Los archivos que no se encuentren en ningún addon original se reportan
     * como aún faltantes.
     */
    fun repair(
        mergedBpDir: File,
        mergedRpDir: File,
        originalAddonPaths: List<String>
    ): RepairResult {
        val texturesCopied = mutableSetOf<String>()
        val modelsCopied = mutableSetOf<String>()
        val stillMissingTextures = mutableListOf<String>()
        val stillMissingModels = mutableListOf<String>()
        var placeholderCount = 0
        var staleRemoved = 0

        // ⭐ OPTIMIZACIÓN: Construir índice unificado UNA sola vez para búsquedas O(1)
        // Evita re-abrir ZIPs y walkTopDown() por cada referencia faltante.
        val unifiedAddonIndex = buildUnifiedAddonIndex(originalAddonPaths)

        try {
            // ── REPARAR TEXTURAS ──
            val textureRefs = collectReferencedTextures(mergedRpDir)
            val textureInventory = collectTextureInventory(mergedRpDir)

            for (texRef in textureRefs) {
                // Vanilla: lo resuelve el juego. Sin este filtro el reporte de
                // Better MC listaba wool_colored_white, hunger_effect_full,
                // bucket_water y particle/soul como "sin textura en ningún addon".
                if (VanillaAssets.isVanillaTexture(texRef)) continue
                // Alias de la propia entidad ("Texture.default") y Molang no son rutas.
                if (BedrockRefs.isAlias(texRef) || BedrockRefs.isExpression(texRef)) continue

                // Normalizar: quitar prefijo "textures/" para comparar con el inventario
                // del disco (que almacena rutas relativas sin el prefijo "textures/").
                val normalizedTexRef = texRef.removePrefix("textures/")
                if (normalizedTexRef in textureInventory) continue

                val stemPath = normalizedTexRef.substringBeforeLast('.')
                val stem = stemPath.substringAfterLast('/')

                // Destino = LA RUTA REFERENCIADA. Antes se copiaba siempre a
                // textures/entity/<stem>.png: "textures/blocks/dirt" quedaba como
                // textures/entity/dirt.png, la referencia seguía rota y el healer
                // la contaba como reparada.
                val destFile = File(mergedRpDir, "textures/$stemPath.png")
                if (destFile.isFile) continue

                var copied = false
                for (ext in TEXTURE_EXTENSIONS) {
                    val found = findFileInAddons("$stem$ext", unifiedAddonIndex)
                    if (found != null) {
                        destFile.parentFile?.mkdirs()
                        FileUtils.fastCopy(found, destFile)
                        texturesCopied.add(texRef)
                        copied = true
                        PackForgeLog.d(TAG, "🔧 Textura reparada: $texRef <- ${found.name}")
                        break
                    }
                }

                if (!copied) {
                    stillMissingTextures.add(texRef)
                    PackForgeLog.w(TAG, "❌ Textura aún faltante: $texRef (no existe en ningún addon original)")
                }
            }

            // ── REPARAR MODELOS/GEOMETRÍA ──
            val modelRefs = collectReferencedModels(mergedBpDir, mergedRpDir)
            val modelInventory = collectModelInventory(mergedRpDir)
            // Identificadores de geometría YA definidos en el pack (por contenido,
            // no por nombre de archivo): CoreCraft guarda sus modelos como
            // "2jm1s73o.json", así que un inventario por nombre nunca los veía y
            // el healer generaba 163 placeholders que DUPLICABAN 148 ids reales.
            val declaredGeometryIds = collectDeclaredGeometryIds(mergedRpDir, includePlaceholders = true)
            val realGeometryIds = collectDeclaredGeometryIds(mergedRpDir, includePlaceholders = false)

            // Placeholders heredados de una fusión anterior que duplican un modelo
            // real: se retiran a _packforge/ (fuera del .mcaddon) para que el juego
            // no tenga dos definiciones del mismo identificador.
            staleRemoved = removeStalePlaceholders(mergedRpDir, realGeometryIds)

            for (modelRef in modelRefs) {
                val geometryId = BedrockRefs.geometryId(modelRef)
                    ?: modelRef.substringBefore(".json").takeIf { it.startsWith("geometry.") }
                    ?: continue

                // 1) Ya existe en el pack: nunca generar un placeholder encima.
                if (geometryId in declaredGeometryIds || geometryId in modelInventory) continue
                // 2) Vanilla: la definición la pone el juego. Un placeholder aquí
                //    SOBRESCRIBE el modelo vanilla (geometry.humanoid.armor.*,
                //    geometry.arrow, geometry.item_sprite…): rompe TODO el juego.
                if (VanillaAssets.isVanillaGeometry(geometryId)) continue

                val fileName = "$geometryId.json"
                var copied = false

                for (ext in MODEL_EXTENSIONS) {
                    val candidate = if (fileName.endsWith(ext)) fileName else "$fileName$ext"
                    val found = findFileInAddons(candidate, unifiedAddonIndex)
                    if (found != null) {
                        val destDir = File(mergedRpDir, "models/entity")
                        destDir.mkdirs()
                        val destFile = File(destDir, fileName)
                        FileUtils.fastCopy(found, destFile)
                        modelsCopied.add(modelRef)
                        copied = true
                        PackForgeLog.d(TAG, "🔧 Modelo reparado: $modelRef <- ${found.name}")
                        break
                    }
                }

                // Último recurso: geometría irrecuperable → placeholder de caja
                // 8x8x8 con ese identificador, para que la entidad SPAWNEE y sea
                // visible/colisionable en vez de no renderizarse. Contenido
                // inventado EXPLÍCITO: se reporta como placeholder, nunca como
                // una restauración.
                if (!copied) {
                    val destDir = File(mergedRpDir, "models/entity")
                    if (writePlaceholderGeometry(geometryId, destDir)) {
                        modelsCopied.add(modelRef)
                        placeholderCount++
                        copied = true
                        PackForgeLog.w(TAG, "📦 Placeholder de geometría: $modelRef (caja 8x8x8)")
                    }
                }

                if (!copied) {
                    stillMissingModels.add(modelRef)
                    PackForgeLog.w(TAG, "❌ Modelo aún faltante: $modelRef (no existe en ningún addon original)")
                }
            }

        } catch (e: Exception) {
            PackForgeLog.e(TAG, "Error durante reparación del healer: ${e.message}")
        }

        return RepairResult(
            texturesCopied = texturesCopied.size,
            modelsCopied = modelsCopied.size,
            placeholdersGenerated = placeholderCount,
            stalePlaceholdersRemoved = staleRemoved,
            stillMissingTextures = stillMissingTextures.toList(),
            stillMissingModels = stillMissingModels.toList()
        )
    }

    /**
     * Analiza los packs fusionados y retorna un reporte de health.
     * NO modifica archivos — solo detecta y reporta.
     * (Para corregir lo que sea recuperable, ver [repair].)
     */
    fun heal(mergedBpDir: File, mergedRpDir: File): HealReport {
        val fuzzyMatches = mutableListOf<FuzzyMatch>()
        val unresolved = mutableListOf<UnresolvedRef>()
        val notes = mutableListOf<String>()

        try {
            // Recolectar inventario real de texturas
            val textureInventory = collectTextureInventory(mergedRpDir)

            // 1. Escanear terrain_texture.json → texture_up, texture_down, etc.
            checkTerrainTexture(mergedRpDir, textureInventory, fuzzyMatches, unresolved)

            // 2. Escanear item_texture.json → items.png
            checkItemTexture(mergedRpDir, textureInventory, fuzzyMatches, unresolved)

            // 3. Escanear material_instances en BP blocks
            checkMaterialInstances(mergedBpDir, mergedRpDir, textureInventory, fuzzyMatches, unresolved)

            // 4. Escanear render_controllers → textures/ references
            checkRenderControllers(mergedRpDir, textureInventory, fuzzyMatches, unresolved)

            // 5. Escanear entities → geometry + textures
            checkEntityTextures(mergedRpDir, textureInventory, fuzzyMatches, unresolved)

            // 6. Escanear sound_definitions → audio files
            checkSoundDefinitions(mergedRpDir, fuzzyMatches, unresolved)

        } catch (e: Exception) {
            PackForgeLog.e(TAG, "Error durante análisis del healer: ${e.message}")
            notes.add("Error durante análisis: ${e.message}")
        }

        val report = HealReport(fuzzyMatches, unresolved, notes)

        // Logging
        PackForgeLog.d(TAG, "═══════════════════════════════════════")
        PackForgeLog.d(TAG, "💊 ${report.summary}")
        fuzzyMatches.forEach { fm ->
            PackForgeLog.w(TAG, "  🔍 Fuzzy: '${fm.missingRef}' → candidato '${fm.candidate}' (${"%.0f".format(fm.score * 100)}%) en ${fm.file}")
        }
        unresolved.forEach { ur ->
            PackForgeLog.e(TAG, "  ❌ Irrecuperable: '${ur.missingRef}' en ${ur.file}: ${ur.reason}")
        }
        PackForgeLog.d(TAG, "═══════════════════════════════════════")

        // Registrar en ConflictRegistry para UI
        fuzzyMatches.forEach { fm ->
            ConflictRegistry.logConflict(
                severity = ConflictSeverity.LOW,
                type = "HEALER_FUZZY",
                file = fm.file,
                addon1 = "pack fusionado",
                addon2 = "-",
                description = "Referencia rota '${fm.missingRef}' → candidato '${fm.candidate}' (${"%.0f".format(fm.score * 100)}%)"
            )
        }
        unresolved.forEach { ur ->
            ConflictRegistry.logConflict(
                severity = ConflictSeverity.HIGH,
                type = "HEALER_UNRESOLVED",
                file = ur.file,
                addon1 = "pack fusionado",
                addon2 = "-",
                description = "Referencia '${ur.missingRef}' sin origen (${ur.reason})"
            )
        }

        return report
    }

    // ── ANÁLISIS POR TIPO ──────────────────────────────────────────────

    private fun checkTerrainTexture(rpDir: File, inventory: Set<String>, fuzzy: MutableList<FuzzyMatch>, unresolved: MutableList<UnresolvedRef>) {
        val ttFile = File(rpDir, "textures/terrain_texture.json")
        if (!ttFile.exists()) return
        try {
            val json = JSONObject(ttFile.readText(StandardCharsets.UTF_8))
            json.optJSONObject("texture_name")?.keys()?.forEachRemaining { key ->
                if (key == "format_version") return@forEachRemaining
                val value = json.optJSONObject("texture_name")?.optString(key) ?: return@forEachRemaining
                checkTextureReference(value, "terrain_texture.json", inventory, fuzzy, unresolved)
            }
            // textures map
            json.optJSONObject("textures")?.keys()?.forEachRemaining { key ->
                val value = json.optJSONObject("textures")?.opt(key) ?: return@forEachRemaining
                textureRefValues(value).forEach { ref ->
                    checkTextureReference(ref, "terrain_texture.json", inventory, fuzzy, unresolved)
                }
            }
        } catch (e: Exception) {
            PackForgeLog.w(TAG, "No se pudo analizar terrain_texture.json: ${e.message}")
        }
    }

    private fun checkItemTexture(rpDir: File, inventory: Set<String>, fuzzy: MutableList<FuzzyMatch>, unresolved: MutableList<UnresolvedRef>) {
        val itFile = File(rpDir, "textures/item_texture.json")
        if (!itFile.exists()) return
        try {
            val json = JSONObject(itFile.readText(StandardCharsets.UTF_8))
            json.optJSONObject("texture_data")?.keys()?.forEachRemaining { key ->
                val entry = json.optJSONObject("texture_data")?.opt(key) ?: return@forEachRemaining
                // `textures` puede ser un ARRAY (variantes). `optString` lo
                // serializaba ("[\"textures/items/x\"]") y el reporte de Better MC
                // lo publicaba como "Referencia '[...]' sin origen": 100 conflictos
                // HIGH de los que 84 apuntaban a texturas que SÍ existían.
                val raw = when (entry) {
                    is JSONObject -> entry.opt("textures")
                    is String -> entry
                    else -> null
                }
                textureRefValues(raw).forEach { ref ->
                    checkTextureReference(ref, "item_texture.json", inventory, fuzzy, unresolved)
                }
            }
        } catch (e: Exception) {
            PackForgeLog.w(TAG, "No se pudo analizar item_texture.json: ${e.message}")
        }
    }

    /**
     * Valores de un campo `textures`/`texture`, que en Bedrock puede ser:
     * string ("textures/items/x"), array de variantes o mapa alias→ruta.
     * Se descartan alias (`Texture.default`) y Molang.
     */
    private fun textureRefValues(value: Any?): List<String> = when (value) {
        null -> emptyList()
        is String -> listOfNotNull(normalizeTextureRef(value))
        is JSONArray -> (0 until value.length()).flatMap { textureRefValues(value.opt(it)) }
        is JSONObject -> value.keys().asSequence()
            .flatMap { textureRefValues(value.opt(it)) }
            .toList()
        else -> emptyList()
    }

    /** Ruta de textura normalizada, o `null` si el valor no es una ruta. */
    private fun normalizeTextureRef(raw: String): String? {
        val v = raw.trim().replace("\\", "/")
        if (v.isEmpty() || !v.contains('/')) return null
        if (BedrockRefs.isAlias(v) || BedrockRefs.isExpression(v)) return null
        return v
    }

    private fun checkMaterialInstances(bpDir: File, rpDir: File, inventory: Set<String>, fuzzy: MutableList<FuzzyMatch>, unresolved: MutableList<UnresolvedRef>) {
        // ⭐ OPTIMIZACIÓN: Usar DirIndexCache en lugar de walkTopDown()
        val cachedFiles = DirIndexCache.index(bpDir).jsonFiles
        for (file in cachedFiles) {
            try {
                val json = JSONObject(file.readText(StandardCharsets.UTF_8))
                checkMaterialInstancesInJson(json, file, rpDir, inventory, fuzzy, unresolved)
            } catch (_: Exception) {}
        }
    }

    private fun checkMaterialInstancesInJson(json: JSONObject, file: File, rpDir: File, inventory: Set<String>, fuzzy: MutableList<FuzzyMatch>, unresolved: MutableList<UnresolvedRef>) {
        json.optJSONObject("minecraft:block")?.optJSONObject("components")
            ?.optJSONObject("minecraft:material_instances")?.let { matInstances ->
                matInstances.keys().forEachRemaining { key ->
                    val entry = matInstances.optJSONObject(key) ?: return@forEachRemaining
                    entry.optString("texture", "").takeIf { it.isNotBlank() }?.let { tex ->
                        // Material instances refieren a terrain_texture keys, no paths directos
                        // Verificar que la key existe en terrain_texture.json
                        val terrainKey = "textures.$tex"
                        // Solo registrar si parece path (contiene /) y no existe
                        if (tex.contains('/') && !texFileExists(rpDir, tex)) {
                            checkTextureReference(tex, file.name, inventory, fuzzy, unresolved)
                        }
                    }
                }
            }
        // Nested blocks.json in BP
        if (json.has("minecraft:block")) {
            val blockId = json.optJSONObject("minecraft:block")?.optJSONObject("description")
                ?.optString("identifier") ?: ""
            json.optJSONObject("minecraft:block")?.optJSONObject("components")
                ?.optJSONObject("minecraft:material_instances")?.let { checkMaterialInstancesInJson(it, file, rpDir, inventory, fuzzy, unresolved) }
        }
    }

    private fun checkRenderControllers(rpDir: File, inventory: Set<String>, fuzzy: MutableList<FuzzyMatch>, unresolved: MutableList<UnresolvedRef>) {
        val rcDir = File(rpDir, "render_controllers")
        if (!rcDir.isDirectory) return
        // ⭐ OPTIMIZACIÓN: Usar DirIndexCache en lugar de walkTopDown()
        val cachedFiles = DirIndexCache.index(rcDir).jsonFiles
        for (file in cachedFiles) {
            try {
                val json = JSONObject(file.readText(StandardCharsets.UTF_8))
    json.optJSONObject("render_controllers")?.keys()?.forEachRemaining { rcKey ->
        val rcDef = json.optJSONObject("render_controllers")?.opt(rcKey)
   if (rcDef is JSONObject) {
       val arrays = rcDef.opt("arrays")
          if (arrays is JSONObject) {
   arrays.optJSONObject("textures")?.keys()?.forEachRemaining { texKey ->
    // TICKET-CORE-003 Parte 3: el valor puede ser un ARRAY de KEYS de
         // textura ("texture.destroy_stage_0"), NO una ruta de archivo.
        // Hacer optString() sobre un JSONArray devuelve su toString()
   // y genera basura del tipo: Referencia '["texture.x",...]' sin origen.
   val texVal = arrays.optJSONObject("textures")?.opt(texKey)
      collectTextureCandidates(texVal, file.name, inventory, fuzzy, unresolved)
     }
          }
  }
     }
     } catch (_: Exception) {}
  }
    }

    private fun checkEntityTextures(rpDir: File, inventory: Set<String>, fuzzy: MutableList<FuzzyMatch>, unresolved: MutableList<UnresolvedRef>) {
        val entityDir = File(rpDir, "entity")
        if (!entityDir.isDirectory) return
        val entityFiles = DirIndexCache.index(entityDir).jsonFiles
        for (file in entityFiles) {
            try {
                val json = JSONObject(file.readText(StandardCharsets.UTF_8))
                json.optJSONObject("minecraft:client_entity")?.optJSONObject("description")
                    ?.optJSONObject("textures")?.let { textures ->
                        textures.keys().forEachRemaining { texKey ->
                            // El valor puede ser un array de variantes: `optString`
                            // devolvía "[\"textures/...\"]" y se comparaba como id.
                            textureRefValues(textures.opt(texKey)).forEach { ref ->
                                checkTextureReference(ref, file.name, inventory, fuzzy, unresolved)
                            }
                        }
                    }
            } catch (_: Exception) {}
        }
    }

    private fun checkSoundDefinitions(rpDir: File, fuzzy: MutableList<FuzzyMatch>, unresolved: MutableList<UnresolvedRef>) {
        val sdFile = File(rpDir, "sound_definitions.json")
        if (!sdFile.exists()) return
        try {
            val json = JSONObject(sdFile.readText(StandardCharsets.UTF_8))
            json.keys().forEachRemaining { key ->
                if (key == "format_version") return@forEachRemaining
                val def = json.optJSONObject(key) ?: return@forEachRemaining
                def.optJSONArray("sounds")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val entry = arr.optJSONObject(i) ?: continue
                        val name = entry.optString("name", "")
                        if (name.isNotBlank() && name.contains('/') && !soundFileExists(rpDir, name)) {
                            unresolved.add(UnresolvedRef(name, "sound_definitions.json", "archivo de audio no encontrado"))
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    // ── HELPERS ────────────────────────────────────────────────────────

    /**
 * TICKET-CORE-003 (Partes 1 y 3) — Extrae candidatos de textura de un valor
 * JSON heterogéneo.
 *
 * En `render_controllers.*.arrays.textures.*` el valor puede ser:
 *  - String    → "textures/mob/custom"      (ruta: se valida)
 *  - JSONArray → ["texture.stage_0", ...] (KEYS internas: se ignoran)
 *  - JSONObject→ {"texture": "..."}       (se recorre)
 *
 * Reglas aplicadas en [validateCandidate]:
 *  1. Un elemento sin "/" es una KEY del mapa `textures` del client entity, no
 *     una ruta: ignorarlo evita basura del tipo
 *     Referencia '["texture.destroy_stage_9",...]' sin origen.
 *  2. Se descartan prefijos de placeholder de Molang (texture., variable.,
 *     array., query., geometry., material.), sin importar el caso.
 *  3. Un asset VANILLA no se valida: el juego lo resuelve en runtime.
 */
    private fun collectTextureCandidates(
        raw: Any?,
        fileName: String,
        inventory: Set<String>,
        fuzzy: MutableList<FuzzyMatch>,
        unresolved: MutableList<UnresolvedRef>
    ) {
        when (raw) {
            null -> return
            is String -> validateCandidate(raw, fileName, inventory, fuzzy, unresolved)
            is JSONArray -> {
                for (i in 0 until raw.length()) {
                    when (val element = raw.opt(i)) {
                        is String -> validateCandidate(element, fileName, inventory, fuzzy, unresolved)
                        is JSONObject, is JSONArray ->
                            collectTextureCandidates(element, fileName, inventory, fuzzy, unresolved)
                        else -> Unit
                    }
                }
            }
            is JSONObject -> {
                val keys = raw.keys()
                while (keys.hasNext()) {
                    collectTextureCandidates(raw.opt(keys.next()), fileName, inventory, fuzzy, unresolved)
                }
            }
            else -> Unit
        }
    }

    /** Filtra key interna / placeholder Molang / asset vanilla antes de validar. */
    private fun validateCandidate(
        candidate: String,
        fileName: String,
        inventory: Set<String>,
        fuzzy: MutableList<FuzzyMatch>,
        unresolved: MutableList<UnresolvedRef>
    ) {
        val ref = candidate.trim()
        if (ref.isEmpty()) return

        // Sin "/" es una KEY interna ("texture.destroy_stage_0"), no una ruta.
        if (!ref.contains('/')) return

        // Placeholders y keys de Molang: nunca son rutas de archivo.
        val lower = ref.lowercase()
        if (MOLANG_PREFIXES.any { lower.startsWith(it) }) return

        // TICKET-CORE-003 Parte 1: asset vanilla, lo resuelve el juego.
        if (VanillaAssets.isVanillaTexture(ref)) return

        checkTextureReference(ref, fileName, inventory, fuzzy, unresolved)
    }

    /** Prefijos de keys/placeholder de Molang que nunca son rutas de textura. */
    private val MOLANG_PREFIXES = listOf(
        "texture.", "variable.", "array.", "query.", "geometry.", "material."
    )

    private fun checkTextureReference(texRef: String, fileName: String, inventory: Set<String>, fuzzy: MutableList<FuzzyMatch>, unresolved: MutableList<UnresolvedRef>) {
        // TICKET-CORE-003 Parte 1: un asset vanilla lo resuelve el juego en runtime.
    if (VanillaAssets.isVanillaTexture(texRef)) return
        // Referencias dinámicas de render controllers, no rutas de archivos.
        if (texRef.startsWith("Array.") || texRef.startsWith("variable.") || texRef.startsWith("query.")) return
        if (fuzzy.size + unresolved.size >= MAX_REFERENCE_REPORTS) return
        if (fuzzy.any { it.missingRef == texRef } || unresolved.any { it.missingRef == texRef }) return

        // Normalize: quitar prefijo "textures/" si existe
        val normalized = texRef.trim().removePrefix("textures/")

        // ⭐ FASE 5 — EL ÚLTIMO PUNTO NO SIEMPRE ES LA EXTENSIÓN.
        //
        // `substringBeforeLast('.')` sobre "cc/custom_villager/levels/base_lvl.1"
        // devuelve "…/base_lvl": el `.1` es parte del NOMBRE, no una extensión,
        // así que la búsqueda fallaba y la textura se daba por ausente — aunque
        // `…/base_lvl.1.png` estuviera en el pack (el reporte de Better MC la
        // listaba como rota en 30 entidades).
        //
        // Bedrock referencia las texturas SIN extensión y el archivo la lleva,
        // así que se prueban las extensiones reales por SUFIJO en vez de
        // recortar tras el último punto.
        if (inventory.contains(normalized) || inventory.contains(texRef)) return

        // Con la extensión ya incluida en la referencia.
        val tieneExtReal = TEXTURE_EXTENSIONS.any { normalized.endsWith(it, ignoreCase = true) }
        if (tieneExtReal && inventory.contains(normalized)) return

        // Sin extensión en la referencia: probar cada extensión admitida.
        if (!tieneExtReal) {
            if (TEXTURE_EXTENSIONS.any { inventory.contains("$normalized$it") }) return
        }

        // Compatibilidad: referencias ya con extensión de imagen explícita.
        val stem = normalized.substringBeforeLast('.')
        val ext = normalized.substringAfterLast('.', "png")
        if (inventory.contains(stem) || inventory.contains("$stem.$ext")) return

        // Fuzzy match
        // La similitud es solo una sugerencia visual. Limitar candidatos por longitud
        // evita O(referencias × todas las texturas) en resource packs grandes.
        val bestMatch = inventory.asSequence()
            .filter { abs(it.substringBeforeLast('.').length - stem.length) <= 24 }
            .maxByOrNull { levenshteinSimilarity(stem, it.substringBeforeLast('.')) }
        val score = if (bestMatch != null) levenshteinSimilarity(stem, bestMatch.substringBeforeLast('.')) else 0.0

        if (score >= FUZZY_THRESHOLD) {
            fuzzy.add(FuzzyMatch(texRef, bestMatch!!, score, fileName))
        } else {
            unresolved.add(UnresolvedRef(texRef, fileName, "textura no encontrada (mejor match: ${bestMatch ?: "ninguno"} @ ${"%.0f".format(score * 100)}%)"))
        }
    }

    private fun texFileExists(rpDir: File, texRef: String): Boolean {
        val base = File(rpDir, "textures/$texRef")
        return base.exists() || File("$base.png").exists() || File("$base.tga").exists() || File("$base.jpg").exists()
    }

    private fun soundFileExists(rpDir: File, soundRef: String): Boolean {
        val soundsDir = File(rpDir, "sounds")
        return File(soundsDir, "$soundRef.ogg").exists() ||
            File(soundsDir, "$soundRef.wav").exists() ||
            File(soundsDir, soundRef).exists()
    }

    private fun collectTextureInventory(rpDir: File): Set<String> {
        val textures = mutableSetOf<String>()
        val texDir = File(rpDir, "textures")
        if (!texDir.isDirectory) return textures
        val textureFiles = DirIndexCache.index(texDir).allFiles
        for (file in textureFiles) {
            val rel = file.relativeTo(texDir).path.replace("\\", "/")
            textures.add(rel)
            // ⭐ FASE 5 — quitar la extensión REAL, no lo que sigue al último punto.
            //
            // `substringBeforeLast('.')` sobre "cc/.../base_lvl.1.png" devuelve
            // "cc/.../base_lvl": el `.1` es parte del nombre y se perdía, así que
            // la referencia de Bedrock ("…/base_lvl.1", sin extensión) no
            // encontraba nada y la textura se reportaba ausente aunque estuviera
            // en el pack. Igual ocurría con cualquier nombre que contenga puntos.
            val extReal = TEXTURE_EXTENSIONS.firstOrNull { rel.endsWith(it, ignoreCase = true) }
            if (extReal != null) {
                textures.add(rel.dropLast(extReal.length))
            } else {
                textures.add(rel.substringBeforeLast('.'))
            }
        }
        return textures
    }

    /**
     * Levenshtein similarity: 0.0 (totalmente distinto) a 1.0 (idéntico).
     * ⭐ OPTIMIZACIÓN: Cache transparente para evitar cómputos redundantes.
     */
    private fun levenshteinSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0

        val cacheKey = "$a|$b"
        return levenshteinSimilarityCache.computeIfAbsent(cacheKey) {
            // CÓDIGO DP EXACTO MISMO QUE ORIGINAL
            val lenA = a.length
            val lenB = b.length
            val dp = Array(lenA + 1) { IntArray(lenB + 1) }

            for (i in 0..lenA) dp[i][0] = i
            for (j in 0..lenB) dp[0][j] = j

            for (i in 1..lenA) {
                for (j in 1..lenB) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    dp[i][j] = minOf(
                        dp[i - 1][j] + 1,
                        dp[i][j - 1] + 1,
                        dp[i - 1][j - 1] + cost
                    )
                }
            }

            val maxLen = maxOf(lenA, lenB)
            1.0 - dp[lenA][lenB].toDouble() / maxLen
        }
    }
    /** Extensiones de textura soportadas por Bedrock. */
    private val TEXTURE_EXTENSIONS = listOf(".png", ".tga", ".jpg")
    /** Extensiones de geometría/modelo soportadas. */
    private val MODEL_EXTENSIONS = listOf(".geo.json", ".json")

    /**
     * Construye un índice inverso UNA sola vez: nombre de archivo → File.
     * Evita re-abrir ZIPs y walkTopDown() por cada referencia faltante.
     * Ganancia: de O(addons × referencias) a O(1) por lookup.
     */
    private fun buildUnifiedAddonIndex(addonPaths: List<String>): Map<String, File> {
        val index = mutableMapOf<String, File>()
        for (addonPath in addonPaths) {
            val dir = File(addonPath)
            if (!dir.isDirectory) continue
            // Usar DirIndexCache (3-10x más rápido que walkTopDown)
            val cachedFiles = DirIndexCache.index(dir).allFiles
            for (file in cachedFiles) {
                // Indexar por nombre lowercase para búsqueda directa
                index[file.name.lowercase(Locale.ROOT)] = file
                // Indexar sin extensión para Bedrock-style "base_lvl.1" → "base_lvl.1.png"
                val nameNoExt = file.nameWithoutExtension.lowercase(Locale.ROOT)
                index[nameNoExt] = file
            }
        }
        return index
    }

    /**
     * Búsqueda O(1) usando el índice unificado construido al inicio de repair().
     * Mismo comportamiento que la versión original pero sin I/O redundante.
     */
    private fun findFileInAddons(fileName: String, unifiedIndex: Map<String, File>): File? {
        val key = fileName.lowercase(Locale.ROOT)
        return unifiedIndex[key] ?: unifiedIndex[
            fileName.removeSuffix(".png").removeSuffix(".tga").removeSuffix(".jpg").lowercase(Locale.ROOT)
        ]
    }

    /**
     * Busca un archivo por nombre en todos los addons originales (ZIP o directorio).
     * Retorna el primer File encontrado, o null si no existe en ninguno.
     * @deprecated Usar findFileInAddons(fileName, unifiedIndex) en su lugar
     */
    @Deprecated("Use findFileInAddons with unified index", ReplaceWith("findFileInAddons(fileName, unifiedIndex)"))
    private fun findFileInAddons(fileName: String, addonPaths: List<String>): File? {
        for (addonPath in addonPaths) {
            val addonFile = File(addonPath)
            if (!addonFile.exists()) continue

            if (addonFile.extension.equals("zip", ignoreCase = true) ||
                addonFile.extension.equals("mcpack", ignoreCase = true) ||
                addonFile.extension.equals("mcaddon", ignoreCase = true)) {
                try {
                    java.util.zip.ZipFile(addonFile).use { zip ->
                        val entries = zip.entries()
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            if (!entry.isDirectory && entry.name.endsWith(fileName, ignoreCase = true)) {
                                val tempFile = java.io.File.createTempFile("pf_healer_", fileName)
                                zip.getInputStream(entry).use { input ->
                                    tempFile.outputStream().use { output ->
                                        input.copyTo(output, bufferSize = 16384)
                                    }
                                }
                                return tempFile
                            }
                        }
                    }
                } catch (e: Exception) {
                    PackForgeLog.w(TAG, "Error buscando en ZIP " + addonPath + ": " + e.message)
                }
            } else if (addonFile.isDirectory) {
                val found = addonFile.walkTopDown()
                    .filter { it.isFile && it.name.equals(fileName, ignoreCase = true) }
                    .firstOrNull()
                if (found != null) return found
            }
        }
        return null
    }

    /**
     * Estructura mínima de geometría válida para Bedrock: una caja 8x8x8 con
     * el identificador pedido. No es el modelo original (no existe en ningún
     * addon), solo garantiza que la entidad aparezca en el mundo.
     * `__ID__` se sustituye por el identificador (p.ej. `geometry.carryblock`).
     */
    private val PLACEHOLDER_GEOMETRY_JSON = """{"format_version":"1.12.0","minecraft:geometry":[{"description":{"identifier":"__ID__","texture_width":16,"texture_height":16,"visible_bounds_width":2.0,"visible_bounds_height":2.0,"visible_bounds_offset":[0.0,1.0,0.0]},"bones":[{"name":"root","pivot":[0.0,0.0,0.0],"cubes":[{"origin":[-4.0,0.0,-4.0],"size":[8.0,8.0,8.0],"uv":[0.0,0.0]}]}]}]}"""

    /** Escribe la geometría placeholder si aún no existe. Devuelve si se creó. */
    private fun writePlaceholderGeometry(identifier: String, destDir: File): Boolean = try {
        destDir.mkdirs()
        val dest = File(destDir, "$identifier.placeholder.geo.json")
        if (dest.exists()) {
            false
        } else {
            dest.writeText(PLACEHOLDER_GEOMETRY_JSON.replace("__ID__", identifier), Charsets.UTF_8)
            true
        }
    } catch (e: Exception) {
        PackForgeLog.w(TAG, "No se pudo escribir placeholder de $identifier: ${e.message}")
        false
    }

    /** Recolecta todas las referencias de textura (textures/xxx) usadas en JSONs del RP. */
    private fun collectReferencedTextures(rpDir: File): Set<String> {
        val refs = mutableSetOf<String>()
        if (!rpDir.isDirectory) return refs
        rpDir.walkTopDown().filter { it.isFile && it.extension.equals("json", ignoreCase = true) }.forEach { jsonFile ->
            try {
                val content = jsonFile.readText(Charsets.UTF_8)
                val json = JSONObject(content)
                extractTextureRefs(json, refs)
            } catch (e: Exception) { }
        }
        return refs
    }

    /** Extrae referencias de textura recursivamente de un JSONObject. */
    private fun extractTextureRefs(obj: Any?, refs: MutableSet<String>) {
        when (obj) {
            is JSONObject -> {
                obj.keys().forEach { key ->
                    val value = obj.opt(key)
                    when {
                        // "texture"/"texture_name"/"textures" declaran rutas.
                        key.equals("texture", ignoreCase = true) ||
                            key.equals("texture_name", ignoreCase = true) ||
                            key.equals("textures", ignoreCase = true) -> collectTextureRefValues(value, refs)
                        else -> extractTextureRefs(value, refs)
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until obj.length()) {
                    extractTextureRefs(obj.opt(i), refs)
                }
            }
        }
    }

    /**
     * Normaliza los valores de un campo de textura. Descarta alias
     * ("Texture.default") y Molang, que antes se convertían en rutas
     * ("textures/Texture.default") y luego en "texturas faltantes" falsas.
     */
    private fun collectTextureRefValues(value: Any?, refs: MutableSet<String>) {
        when (value) {
            is String -> {
                val raw = value.trim().replace("\\", "/")
                if (raw.isEmpty() || !raw.contains('/')) return
                if (BedrockRefs.isAlias(raw) || BedrockRefs.isExpression(raw)) return
                refs.add(if (raw.startsWith("textures/")) raw else "textures/$raw")
            }
            is JSONArray -> for (i in 0 until value.length()) collectTextureRefValues(value.opt(i), refs)
            is JSONObject -> value.keys().forEach { collectTextureRefValues(value.opt(it), refs) }
        }
    }

    /** Recolecta referencias de modelos/geometría (models/entity/xxx) usados en entidades BP y RP. */
    private fun collectReferencedModels(bpDir: File, rpDir: File): Set<String> {
        val refs = mutableSetOf<String>()
        listOf(bpDir, rpDir).forEach { dir ->
            if (!dir.isDirectory) return@forEach
            dir.walkTopDown().filter { it.isFile && it.extension.equals("json", ignoreCase = true) }.forEach { jsonFile ->
                try {
                    val content = jsonFile.readText(Charsets.UTF_8)
                    val json = JSONObject(content)
                    extractModelRefs(json, refs)
                } catch (e: Exception) { }
            }
        }
        return refs
    }

    /**
     * Referencias de geometría declaradas en entidades/atachables.
     *
     * Devuelve IDENTIFICADORES (`geometry.x`), no rutas. Todo el criterio
     * (alias, Molang, mapas de `bone_visibility`) vive en [BedrockRefs]: antes
     * `optString` serializaba objetos y el healer buscaba "modelos" llamados
     * `{"bot_ne":true,...}`.
     */
    private fun extractModelRefs(obj: Any?, refs: MutableSet<String>) {
        when (obj) {
            is JSONObject -> {
                obj.keys().forEach { key ->
                    val value = obj.opt(key)
                    if (key.equals("geometry", ignoreCase = true)) {
                        refs.addAll(BedrockRefs.geometryIds(value))
                    } else {
                        extractModelRefs(value, refs)
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until obj.length()) {
                    extractModelRefs(obj.opt(i), refs)
                }
            }
        }
    }

    /**
     * Identificadores de geometría YA definidos en el pack fusionado, leídos del
     * CONTENIDO (no del nombre del archivo).
     *
     * CoreCraft guarda sus modelos con nombres ofuscados (`2jm1s73o.json`), así
     * que el inventario por nombre de archivo no los veía y el healer generaba
     * un placeholder que DUPLICABA el identificador → el juego quedaba con dos
     * definiciones del mismo id y elegía una al azar (mob como caja).
     */
    private fun collectDeclaredGeometryIds(rpDir: File, includePlaceholders: Boolean = true): Set<String> {
        val ids = mutableSetOf<String>()
        val modelsDir = File(rpDir, "models")
        val root = if (modelsDir.isDirectory) modelsDir else rpDir
        if (!root.isDirectory) return ids
        DirIndexCache.index(root).jsonFiles.forEach { file ->
            if (!includePlaceholders && file.name.contains("placeholder")) return@forEach
            try {
                val text = file.readText(Charsets.UTF_8)
                if (!text.contains("minecraft:geometry") && !text.contains("\"geometry.")) return@forEach
                val json = JSONObject(text)
                json.optJSONArray("minecraft:geometry")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.optJSONObject("description")
                            ?.optString("identifier")?.trim()
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { ids.add(it) }
                    }
                }
                // Formato legacy: {"geometry.nombre": { ... }} en la raíz.
                json.keys().forEach { k -> if (k.startsWith("geometry.")) ids.add(k) }
            } catch (_: Exception) {
                // JSON ilegible: lo reporta la auditoría de integridad.
            }
        }
        return ids
    }

    /**
     * Retira a `_packforge/stale_placeholders/` los placeholders de una fusión
     * anterior que son dañinos:
     *
     *  - su identificador YA lo define un modelo real del pack (definición
     *    duplicada: el juego elige una al azar y el mob puede salir como caja), o
     *  - su identificador es VANILLA (`geometry.arrow`,
     *    `geometry.humanoid.armor.*`): el pack estaría sustituyendo el modelo del
     *    propio juego para TODAS las entidades.
     *
     * La carpeta `_packforge/` queda fuera del `.mcaddon` (ver
     * PackForgeOrchestrator.addFolderToZip), así que el juego solo ve el modelo
     * bueno (o el vanilla), y la evidencia sigue en disco para el reporte.
     */
    private fun removeStalePlaceholders(rpDir: File, realGeometryIds: Set<String>): Int {
        val modelsDir = File(rpDir, "models")
        if (!modelsDir.isDirectory) return 0
        val trashDir = File(rpDir, "_packforge/stale_placeholders")
        var moved = 0
        modelsDir.walkTopDown()
            .filter { it.isFile && it.name.contains("placeholder") && it.name.endsWith(".json") }
            .forEach { file ->
                val id = try {
                    JSONObject(file.readText(Charsets.UTF_8))
                        .optJSONArray("minecraft:geometry")
                        ?.optJSONObject(0)?.optJSONObject("description")
                        ?.optString("identifier")?.trim()
                } catch (_: Exception) {
                    null
                }
                if (id.isNullOrEmpty()) return@forEach
                val harmful = id in realGeometryIds || VanillaAssets.isVanillaGeometry(id)
                if (!harmful) return@forEach
                // Sufijo `.bak`: además de quedar fuera del .mcaddon (la carpeta
                // _packforge/ se excluye) tampoco entra en NINGÚN índice JSON, para
                // que su identificador no vuelva por la puerta de atrás.
                val dest = File(trashDir, "${file.name}.bak")
                if (dest.exists()) dest.delete()
                if (file.renameTo(dest) || runCatching { file.copyTo(dest, overwrite = true); file.delete() }.isSuccess) {
                    moved++
                    PackForgeLog.w(
                        TAG,
                        "🧹 Placeholder obsoleto retirado (duplicaba '$id'): ${file.name} -> _packforge/"
                    )
                }
            }
        return moved
    }

    /** Inventario de modelos ya presentes en el RP fusionado. */
    private fun collectModelInventory(rpDir: File): Set<String> {
        val models = mutableSetOf<String>()
        val modelsDir = File(rpDir, "models/entity")
        if (!modelsDir.isDirectory) return models
        modelsDir.walkTopDown().filter { it.isFile }.forEach { file ->
            models.add(file.name)
            models.add(file.name.removeSuffix(".json"))
            models.add("models/entity/" + file.name)
            models.add("models/entity/" + file.name.removeSuffix(".json"))
        }
        return models
    }

    // ═══════════════════════════════════════════════════════════════════
    // AUTO-REPARACIÓN DE REFERENCIAS ROTAS (fuzzy-match >= 0.95)
    // ═══════════════════════════════════════════════════════════════════

    fun autoRepairReferences(
        mergedRpDir: File,
        originalAddonPaths: List<String>
    ): Int {
        if (!mergedRpDir.isDirectory) return 0

        val textureInventory = collectTextureInventory(mergedRpDir)
        val allTextures = collectAllAvailableTextures(originalAddonPaths)
        var repairedCount = 0

        // Usa el índice cacheado; evita walkTopDown completo en cada fusión.
        DirIndexCache.index(mergedRpDir).jsonFiles.forEach { jsonFile ->
            try {
                val originalContent = jsonFile.readText(StandardCharsets.UTF_8)
                val modifiedContent = repairTextureRefsInJson(
                    originalContent,
                    textureInventory,
                    allTextures
                )
                if (modifiedContent != originalContent) {
                    jsonFile.writeText(modifiedContent, StandardCharsets.UTF_8)
                    repairedCount++
                    PackForgeLog.d(TAG, "🔧 Auto-reparado: ${jsonFile.name}")
                }
            } catch (e: Exception) {
                PackForgeLog.w(TAG, "No se pudo auto-reparar ${jsonFile.name}: ${e.message}")
            }
        }
        if (repairedCount > 0) DirIndexCache.invalidate(mergedRpDir)

        return repairedCount
    }

    private fun collectAllAvailableTextures(originalAddonPaths: List<String>): Map<String, String> {
        val textures = hashMapOf<String, String>()
        originalAddonPaths.forEach { rootPath ->
            val dir = File(rootPath)
            if (!dir.isDirectory) return@forEach
            DirIndexCache.index(dir).allFiles
                .asSequence()
                .filter { it.extension.lowercase(Locale.ROOT) in TEXTURE_EXTENSIONS }
                .forEach { file ->
                    textures.putIfAbsent(file.nameWithoutExtension, file.invariantSeparatorsPath)
                }
        }
        return textures
    }

    private fun repairTextureRefsInJson(
        content: String,
        textureInventory: Set<String>,
        allTextures: Map<String, String>
    ): String {
        var modified = false
        var result = content

        val refs = mutableListOf<Pair<String, String>>()
        try {
            extractTextureRefsWithPaths(JSONObject(result), "", refs)
        } catch (e: Exception) {
            return content
        }

        for ((keyPath, texRef) in refs) {
            val normalized = texRef.removePrefix("textures/")
            if (normalized in textureInventory) continue

            val stem = normalized.substringAfterLast('/').substringBeforeLast('.')
            val bestMatch = findBestTextureMatch(stem, allTextures)

            if (bestMatch != null) {
                val (candidateName, _) = bestMatch
                val replacement = if (texRef.startsWith("textures/")) {
                    "textures/$candidateName"
                } else {
                    candidateName
                }
                result = replaceTextureRefAtPath(result, keyPath, replacement)
                modified = true
                PackForgeLog.d(TAG, "  Auto-reparada referencia: '$texRef' → '$replacement'")
            }
        }

        return if (modified) result else content
    }

    private fun extractTextureRefsWithPaths(
        obj: Any?,
        currentPath: String,
        refs: MutableList<Pair<String, String>>
    ) {
        when (obj) {
            is JSONObject -> {
                obj.keys().forEach { key ->
                    val newPath = if (currentPath.isEmpty()) key else "$currentPath.$key"
                    val value = obj.opt(key)
                    when (value) {
                        is String -> {
                            val str = value
                            if (isTextureReference(str)) {
                                refs.add(Pair(newPath, str))
                            }
                        }
                        is JSONObject, is JSONArray -> {
                            extractTextureRefsWithPaths(value, newPath, refs)
                        }
                        else -> Unit
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until obj.length()) {
                    val newPath = "$currentPath[$i]"
                    extractTextureRefsWithPaths(obj.opt(i), newPath, refs)
                }
            }
        }
    }

    private fun isTextureReference(str: String): Boolean {
        val lower = str.lowercase()
        return (lower.contains("texture") || lower.contains("textures/") ||
            lower.endsWith(".png") || lower.endsWith(".tga") || lower.endsWith(".jpg")) &&
            str.length > 2 && str.length < 200
    }

    private fun findBestTextureMatch(
        stem: String,
        allTextures: Map<String, String>
    ): Pair<String, String>? {
        if (allTextures.isEmpty()) return null

        var bestScore = 0.0
        var bestCandidate: String? = null

        allTextures.keys.forEach { candidateName ->
            val score = similarityScore(stem, candidateName)
            if (score > bestScore) {
                bestScore = score
                bestCandidate = candidateName
            }
        }

        return if (bestScore >= AUTO_REPAIR_THRESHOLD && bestCandidate != null) {
            Pair(bestCandidate, allTextures[bestCandidate]!!)
        } else {
            null
        }
    }

    private fun similarityScore(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0

        val aLower = a.lowercase()
        val bLower = b.lowercase()

        if (aLower == bLower) return 1.0

        if (aLower.contains(bLower) || bLower.contains(aLower)) {
            val shorter = if (aLower.length < bLower.length) aLower.length else bLower.length
            val longer = if (aLower.length > bLower.length) aLower.length else bLower.length
            return shorter.toDouble() / longer
        }

        val distance = levenshteinDistance(aLower, bLower)
        val maxLen = maxOf(aLower.length, bLower.length)
        return if (maxLen == 0) 1.0 else 1.0 - (distance.toDouble() / maxLen)
    }

private fun levenshteinDistance(s1: String, s2: String): Int {
        if (s1 == s2) return 0
        if (s1.isEmpty()) return s2.length
        if (s2.isEmpty()) return s1.length

        val cacheKey = "$s1|$s2"
        return levenshteinDistanceCache.computeIfAbsent(cacheKey) {
            // CÓDIGO DP EXACTO MISMO QUE ORIGINAL
            val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
            for (i in 0..s1.length) dp[i][0] = i
            for (j in 0..s2.length) dp[0][j] = j

for (i in 1..s1.length) {
                for (j in 1..s2.length) {
                    val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                    dp[i][j] = minOf(
                        dp[i - 1][j] + 1,
                        dp[i][j - 1] + 1,
                        dp[i - 1][j - 1] + cost
                    )
                }
            }
            dp[s1.length][s2.length]
        }
    }

    private fun replaceTextureRefAtPath(
        json: String,
        keyPath: String,
        replacement: String
    ): String {
        val keys = keyPath.split(".")
        try {
            val obj = JSONObject(json)
            val target = navigateToParent(obj, keys.dropLast(1))
            if (target != null && jsonObjectHasKey(target, keys.last())) {
                target.put(keys.last(), replacement)
                return obj.toString(2)
            }
        } catch (e: Exception) {
            return replaceTextSimple(json, keyPath, replacement)
        }
        return json
    }

    private fun navigateToParent(obj: JSONObject, keys: List<String>): JSONObject? {
        var current: Any? = obj
        for (key in keys) {
            if (current is JSONObject) {
                current = current.opt(key)
            } else {
                return null
            }
        }
        return current as? JSONObject
    }

    private fun jsonObjectHasKey(obj: JSONObject, key: String): Boolean {
        val keysIter = obj.keys()
        while (keysIter.hasNext()) {
            if (keysIter.next() == key) return true
        }
        return false
    }

    private fun replaceTextSimple(json: String, keyPath: String, replacement: String): String {
        val lastKey = keyPath.substringAfterLast(".")
        val regex = Regex("\"$lastKey\"\\s*:\\s*\"[^\"]*\"")
        return regex.replace(json) { match ->
            val oldValue = match.value.substringAfterLast('"').substringBeforeLast('"')
            match.value.replace(oldValue, replacement)
        }
    }
}




