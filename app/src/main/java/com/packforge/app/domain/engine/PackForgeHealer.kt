package com.packforge.app.domain.engine

import org.json.JSONArray
import com.packforge.app.domain.model.ConflictSeverity
import com.packforge.app.util.FileUtils
import com.packforge.app.util.PackForgeLog
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
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
    /** El healer es diagnóstico: nunca debe retener la exportación por miles de avisos similares. */
    private const val MAX_REFERENCE_REPORTS = 100

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
        /** Geometría placeholder generada (caja 8x8x8) para entidades sin geometría en ningún addon. */
        val placeholdersGenerated: Int = 0,
        val stillMissingTextures: List<String> = emptyList(),
        val stillMissingModels: List<String> = emptyList()
    ) {
        val totalFixed get() = texturesCopied + modelsCopied
        val hasRemainingIssues get() = stillMissingTextures.isNotEmpty() || stillMissingModels.isNotEmpty()
        val summary get() = "Healer.repair: ${texturesCopied} textura(s) copiada(s), " +
            "${modelsCopied} modelo(s) copiado(s) [$placeholdersGenerated placeholder(s) de geometría], " +
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

        try {
            // ── REPARAR TEXTURAS ──
            val textureRefs = collectReferencedTextures(mergedRpDir)
            val textureInventory = collectTextureInventory(mergedRpDir)

            for (texRef in textureRefs) {
                if (texRef in textureInventory) continue

                val stem = texRef.removePrefix("textures/").substringBeforeLast('.')
                var copied = false

                for (ext in TEXTURE_EXTENSIONS) {
                    val fileName = "$stem$ext"
                    val found = findFileInAddons(fileName, originalAddonPaths)
                    if (found != null) {
                        val destDir = File(mergedRpDir, "textures/entity")
                        destDir.mkdirs()
                        val destFile = File(destDir, "$stem.png")
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

            for (modelRef in modelRefs) {
                if (modelRef in modelInventory) continue

                val fileName = modelRef.substringAfterLast('/')
                var copied = false

                for (ext in MODEL_EXTENSIONS) {
                    val candidate = if (fileName.endsWith(ext)) fileName else "$fileName$ext"
                    val found = findFileInAddons(candidate, originalAddonPaths)
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
                if (!copied && fileName.startsWith("geometry.")) {
                    val destDir = File(mergedRpDir, "models/entity")
                    if (writePlaceholderGeometry(fileName.removeSuffix(".json"), destDir)) {
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
                when (value) {
                    is String -> checkTextureReference(value, "terrain_texture.json", inventory, fuzzy, unresolved)
                    is JSONObject -> value.optString("Texture", "").takeIf { it.isNotBlank() }
                        ?.let { checkTextureReference(it, "terrain_texture.json", inventory, fuzzy, unresolved) }
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
                val entry = json.optJSONObject("texture_data")?.optJSONObject(key) ?: return@forEachRemaining
                entry.optString("textures", "").takeIf { it.isNotBlank() }
                    ?.let { checkTextureReference(it, "item_texture.json", inventory, fuzzy, unresolved) }
            }
        } catch (e: Exception) {
            PackForgeLog.w(TAG, "No se pudo analizar item_texture.json: ${e.message}")
        }
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
                            val texVal = textures.optString(texKey) ?: return@forEachRemaining
                            if (texVal.contains('/')) {
                                checkTextureReference(texVal, file.name, inventory, fuzzy, unresolved)
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
        val stem = normalized.substringBeforeLast('.')
        val ext = normalized.substringAfterLast('.', "png")

        // Buscar exacto (con o sin extensión)
        if (inventory.contains(stem) || inventory.contains("$stem.$ext") || inventory.contains(texRef)) return

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
            val stem = rel.substringBeforeLast('.')
            textures.add(stem)
            textures.add(rel)
        }
        return textures
    }

    /**
     * Levenshtein similarity: 0.0 (totalmente distinto) a 1.0 (idéntico).
     */
    private fun levenshteinSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0

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
        return 1.0 - dp[lenA][lenB].toDouble() / maxLen
    }
    /** Extensiones de textura soportadas por Bedrock. */
    private val TEXTURE_EXTENSIONS = listOf(".png", ".tga", ".jpg")
    /** Extensiones de geometría/modelo soportadas. */
    private val MODEL_EXTENSIONS = listOf(".geo.json", ".json")

    /**
     * Busca un archivo por nombre en todos los addons originales (ZIP o directorio).
     * Retorna el primer File encontrado, o null si no existe en ninguno.
     */
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
    private fun extractTextureRefs(obj: Any, refs: MutableSet<String>) {
        when (obj) {
            is JSONObject -> {
                obj.keys().forEach { key ->
                    val value = obj.opt(key)
                    if (key.equals("texture", ignoreCase = true) || key.equals("texture_name", ignoreCase = true)) {
                        if (value is String && value.startsWith("textures/")) {
                            refs.add(value)
                        }
                    } else if (key.equals("textures", ignoreCase = true)) {
                        if (value is JSONObject) {
                            value.keys().forEach { texKey ->
                                val texValue = value.opt(texKey)
                                if (texValue is String) {
                                    refs.add(if (texValue.startsWith("textures/")) texValue else "textures/" + texValue)
                                }
                            }
                        }
                    } else {
                        extractTextureRefs(value, refs)
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

    private fun extractModelRefs(obj: Any, refs: MutableSet<String>) {
        when (obj) {
            is JSONObject -> {
                obj.keys().forEach { key ->
                    val value = obj.opt(key)
                    if (key.equals("geometry", ignoreCase = true)) {
                        if (value is String) {
                            refs.add(if (value.startsWith("geometry.")) "models/entity/" + value + ".json" else value)
                        } else if (value is JSONObject) {
                            value.keys().forEach { geoKey ->
                                val geoValue = value.optString(geoKey).ifEmpty { null }
                                if (geoValue != null) {
                                    refs.add(if (geoValue.startsWith("geometry.")) "models/entity/" + geoValue + ".json" else geoValue)
                                }
                            }
                        }
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

}




