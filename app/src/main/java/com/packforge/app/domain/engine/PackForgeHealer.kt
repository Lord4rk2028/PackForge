package com.packforge.app.domain.engine

import com.packforge.app.domain.model.ConflictSeverity
import com.packforge.app.util.PackForgeLog
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

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

    /**
     * Analiza los packs fusionados y retorna un reporte de health.
     * NO modifica archivos — solo detecta y reporta.
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
        bpDir.walkTopDown().filter { it.isFile && it.extension.equals("json", true) }.forEach { file ->
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
        rcDir.walkTopDown().filter { it.isFile && it.extension.equals("json", true) }.forEach { file ->
            try {
                val json = JSONObject(file.readText(StandardCharsets.UTF_8))
                json.optJSONObject("render_controllers")?.keys()?.forEachRemaining { rcKey ->
                    val rcDef = json.optJSONObject("render_controllers")?.opt(rcKey)
                    if (rcDef is JSONObject) {
                        val arrays = rcDef.opt("arrays")
                        if (arrays is JSONObject) {
                            arrays.optJSONObject("textures")?.keys()?.forEachRemaining { texKey ->
                                val texVal = arrays.optJSONObject("textures")?.optString(texKey) ?: return@forEachRemaining
                                checkTextureReference(texVal, file.name, inventory, fuzzy, unresolved)
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
        entityDir.walkTopDown().filter { it.isFile && it.extension.equals("json", true) }.forEach { file ->
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

    private fun checkTextureReference(texRef: String, fileName: String, inventory: Set<String>, fuzzy: MutableList<FuzzyMatch>, unresolved: MutableList<UnresolvedRef>) {
        // Normalize: quitar prefijo "textures/" si existe
        val normalized = texRef.trim().removePrefix("textures/")
        val stem = normalized.substringBeforeLast('.')
        val ext = normalized.substringAfterLast('.', "png")

        // Buscar exacto (con o sin extensión)
        if (inventory.contains(stem) || inventory.contains("$stem.$ext") || inventory.contains(texRef)) return

        // Fuzzy match
        val bestMatch = inventory.maxByOrNull { levenshteinSimilarity(stem, it.substringBeforeLast('.')) }
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
        texDir.walkTopDown().filter { it.isFile }.forEach { file ->
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
}
