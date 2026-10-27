package com.packforge.app.domain.engine

import com.packforge.app.domain.model.ConflictSeverity
import com.packforge.app.util.PackForgeLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * ═══════════════════════════════════════════════════════════════════════
 * BEDROCK UI MERGER — Fusión semántica de interfaces JSON UI de Bedrock
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Resuelve la pérdida silenciosa de interfaces personalizadas (HUD, pantallas
 * de juego, barras de mana/stamina, paneles táctiles) cuando varios addons
 * aportan contenido en la carpeta `ui/`:
 *
 *  1. `ui/_ui_defs.json`:
 *     Combina las listas de pantallas declaradas (`ui_defs`), asegurando que
 *     todas las pantallas personalizadas de todos los addons se registren en
 *     el cliente Bedrock sin duplicados.
 *
 *  2. `ui/_global_variables.json`:
 *     Combina todas las variables globales `$variable`, detectando colisiones
 *     de valor entre addons.
 *
 *  3. `ui/hud_screen.json` y pantallas compartidas:
 *     - Preserva y unifica definiciones de controles raíz (`"custom_bar": { ... }`).
 *     - Combina los arrays de `"modifications"` (evita que un addon sobreescriba
 *       los widgets insertados por el otro en `"controls"` o `"root_panel"`).
 *     - Combina los arrays directos de `"controls"` sin perder widgets hijos.
 *
 *  4. Archivos UI exclusivos de cada addon:
 *     Se copian directamente asegurando su inclusión en `_ui_defs.json`.
 */
object BedrockUiMerger {
    private const val TAG = "PackForge_UiMerger"

    data class UiMergeReport(
        val mergedFiles: List<String>,
        val modifiedScreens: List<String>,
        val globalVariablesCount: Int,
        val uiDefsCount: Int,
        val issues: List<String>
    )

    /**
     * Fusiona las carpetas `ui/` de todos los Resource Packs proporcionados
     * en el directorio del RP combinado.
     *
     * @param rpDirs Lista de directorios raíz de cada Resource Pack
     * @param mergedRpDir Directorio del Resource Pack combinado
     * @return [UiMergeReport] con el resumen de la fusión
     */
    fun mergeUi(rpDirs: List<File>, mergedRpDir: File): UiMergeReport {
        val uiDirsWithAddon = rpDirs.mapNotNull { rpDir ->
            val uiDir = File(rpDir, "ui")
            if (uiDir.isDirectory) rpDir to uiDir else null
        }

        if (uiDirsWithAddon.isEmpty()) {
            return UiMergeReport(
                mergedFiles = emptyList(),
                modifiedScreens = emptyList(),
                globalVariablesCount = 0,
                uiDefsCount = 0,
                issues = emptyList()
            )
        }

        PackForgeLog.d(TAG, "Iniciando fusión de UI para ${uiDirsWithAddon.size} addon(s)...")

        val targetUiDir = File(mergedRpDir, "ui").apply { mkdirs() }
        val mergedFiles = mutableListOf<String>()
        val modifiedScreens = mutableListOf<String>()
        val issues = mutableListOf<String>()

        // 1. Fusionar ui/_ui_defs.json
        val uiDefsCount = mergeUiDefs(uiDirsWithAddon, targetUiDir, issues)
        if (uiDefsCount > 0) mergedFiles.add("ui/_ui_defs.json")

        // 2. Fusionar ui/_global_variables.json
        val globalVarsCount = mergeGlobalVariables(uiDirsWithAddon, targetUiDir, issues)
        if (globalVarsCount > 0) mergedFiles.add("ui/_global_variables.json")

        // 3. Fusionar pantallas y archivos individuales (ej. hud_screen.json, etc.)
        mergeScreenFiles(uiDirsWithAddon, targetUiDir, mergedFiles, modifiedScreens, issues)

        PackForgeLog.d(
            TAG,
            "Fusión UI completada: ${mergedFiles.size} archivos, ${modifiedScreens.size} pantallas modificadas, " +
                "$uiDefsCount defs registradas, $globalVarsCount variables globales."
        )

        return UiMergeReport(
            mergedFiles = mergedFiles,
            modifiedScreens = modifiedScreens,
            globalVariablesCount = globalVarsCount,
            uiDefsCount = uiDefsCount,
            issues = issues
        )
    }

    // ── 1. Fusión de _ui_defs.json ──────────────────────────────────────────

    private fun mergeUiDefs(
        uiDirs: List<Pair<File, File>>,
        targetUiDir: File,
        issues: MutableList<String>
    ): Int {
        val combinedDefs = LinkedHashSet<String>()

        uiDirs.forEach { (rpRoot, uiDir) ->
            val defsFile = File(uiDir, "_ui_defs.json")
            if (defsFile.isFile) {
                try {
                    val json = parseLenient(defsFile.readText(StandardCharsets.UTF_8))
                    val arr = json?.optJSONArray("ui_defs")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val path = arr.optString(i, "").trim().replace('\\', '/')
                            if (path.isNotBlank()) {
                                combinedDefs.add(path)
                            }
                        }
                    }
                } catch (e: Exception) {
                    val msg = "Error parseando _ui_defs.json de ${rpRoot.name}: ${e.message}"
                    PackForgeLog.w(TAG, msg)
                    issues.add(msg)
                }
            }

            // Descubrir cualquier archivo .json en ui/ que no sea _ui_defs.json
            uiDir.listFiles()?.filter { it.isFile && it.name.endsWith(".json", true) }?.forEach { f ->
                if (!f.name.equals("_ui_defs.json", true) && !f.name.equals("_global_variables.json", true)) {
                    val relPath = "ui/${f.name}"
                    combinedDefs.add(relPath)
                }
            }
        }

        if (combinedDefs.isEmpty()) return 0

        val outputJson = JSONObject()
        val jsonArray = JSONArray()
        combinedDefs.forEach { jsonArray.put(it) }
        outputJson.put("ui_defs", jsonArray)

        val outputFile = File(targetUiDir, "_ui_defs.json")
        writeJson(outputFile, outputJson)
        return combinedDefs.size
    }

    // ── 2. Fusión de _global_variables.json ──────────────────────────────────

    private fun mergeGlobalVariables(
        uiDirs: List<Pair<File, File>>,
        targetUiDir: File,
        issues: MutableList<String>
    ): Int {
        val mergedVars = JSONObject()
        val owners = mutableMapOf<String, String>()

        uiDirs.forEach { (rpRoot, uiDir) ->
            val varFile = File(uiDir, "_global_variables.json")
            if (varFile.isFile) {
                try {
                    val json = parseLenient(varFile.readText(StandardCharsets.UTF_8))
                    json?.keys()?.forEach { key ->
                        val cleanKey = key.trim()
                        val value = json.get(key)
                        if (!mergedVars.has(cleanKey)) {
                            mergedVars.put(cleanKey, value)
                            owners[cleanKey] = rpRoot.name
                        } else {
                            val existingVal = mergedVars.get(cleanKey)
                            if (existingVal.toString() != value.toString()) {
                                val prevOwner = owners[cleanKey] ?: "otro addon"
                                val warn = "Colisión en variable global UI '$cleanKey': valor de '$prevOwner' preservado, valor de '${rpRoot.name}' omitido."
                                issues.add(warn)
                                ConflictRegistry.logConflict(
                                    severity = ConflictSeverity.LOW,
                                    type = "UI_GLOBAL_VARIABLE_COLLISION",
                                    file = "ui/_global_variables.json",
                                    addon1 = prevOwner,
                                    addon2 = rpRoot.name,
                                    description = warn
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    val msg = "Error parseando _global_variables.json de ${rpRoot.name}: ${e.message}"
                    PackForgeLog.w(TAG, msg)
                    issues.add(msg)
                }
            }
        }

        if (mergedVars.length() == 0) return 0

        val outputFile = File(targetUiDir, "_global_variables.json")
        writeJson(outputFile, mergedVars)
        return mergedVars.length()
    }

    // ── 3. Fusión de pantallas de UI (hud_screen.json, etc.) ────────────────

    private fun mergeScreenFiles(
        uiDirs: List<Pair<File, File>>,
        targetUiDir: File,
        mergedFiles: MutableList<String>,
        modifiedScreens: MutableList<String>,
        issues: MutableList<String>
    ) {
        // Mapear filename -> lista de (addonName, File)
        val filesByName = LinkedHashMap<String, MutableList<Pair<String, File>>>()

        uiDirs.forEach { (rpRoot, uiDir) ->
            uiDir.listFiles()?.filter { it.isFile && it.name.endsWith(".json", true) }?.forEach { f ->
                val nameLower = f.name.lowercase(Locale.ROOT)
                if (nameLower != "_ui_defs.json" && nameLower != "_global_variables.json") {
                    filesByName.getOrPut(f.name) { mutableListOf() }.add(rpRoot.name to f)
                }
            }
        }

        filesByName.forEach { (fileName, sources) ->
            val targetFile = File(targetUiDir, fileName)

            if (sources.size == 1) {
                // Solo un addon aporta este archivo: copia directa
                val (_, sourceFile) = sources.first()
                try {
                    sourceFile.copyTo(targetFile, overwrite = true)
                    mergedFiles.add("ui/$fileName")
                } catch (e: Exception) {
                    issues.add("Error copiando ui/$fileName: ${e.message}")
                }
            } else {
                // 2 o más addons aportan este archivo: FUSIÓN SEMÁNTICA
                PackForgeLog.d(TAG, "Fusionando semánticamente ui/$fileName de ${sources.size} addons: ${sources.map { it.first }}")
                var mergedJson: JSONObject? = null
                val winnerAddon = sources.first().first

                for ((addonName, sourceFile) in sources) {
                    val rawText = try { sourceFile.readText(StandardCharsets.UTF_8) } catch (_: Exception) { "" }
                    val currentJson = parseLenient(rawText) ?: continue

                    if (mergedJson == null) {
                        mergedJson = currentJson
                    } else {
                        mergedJson = mergeScreenObjects(
                            base = mergedJson,
                            incoming = currentJson,
                            fileName = fileName,
                            addonName = addonName,
                            issues = issues
                        )
                    }
                }

                if (mergedJson != null) {
                    writeJson(targetFile, mergedJson)
                    mergedFiles.add("ui/$fileName")
                    modifiedScreens.add("ui/$fileName")
                    ConflictRegistry.logConflict(
                        severity = ConflictSeverity.LOW,
                        type = "UI_SCREEN_MERGED",
                        file = "ui/$fileName",
                        addon1 = winnerAddon,
                        addon2 = sources.drop(1).joinToString { it.first },
                        description = "Pantalla ui/$fileName fusionada semánticamente entre ${sources.size} addons."
                    )
                }
            }
        }
    }

    /**
     * Fusiona dos árboles de JSON UI para la misma pantalla.
     */
    fun mergeScreenObjects(
        base: JSONObject,
        incoming: JSONObject,
        fileName: String,
        addonName: String,
        issues: MutableList<String>
    ): JSONObject {
        val result = JSONObject(base.toString())

        // 1. Namespace: si base no tiene, tomar incoming
        if (!result.has("namespace") && incoming.has("namespace")) {
            result.put("namespace", incoming.get("namespace"))
        }

        // 2. Fusionar claves de nivel superior (elementos y paneles)
        incoming.keys().forEach { key ->
            if (key == "namespace") return@forEach

            if (!result.has(key)) {
                // Elemento nuevo del segundo addon: agregarlo directamente
                result.put(key, incoming.get(key))
            } else {
                // Clave compartida (ej. "root_panel", "hud_screen", o control del mismo nombre)
                val baseVal = result.get(key)
                val incVal = incoming.get(key)

                if (baseVal is JSONObject && incVal is JSONObject) {
                    result.put(key, mergePanelObjects(baseVal, incVal, "$fileName/$key"))
                } else if (baseVal is JSONArray && incVal is JSONArray) {
                    result.put(key, mergeGeneralArrays(baseVal, incVal))
                } else {
                    // Valor primitivo en conflicto: el base prevalece
                }
            }
        }

        return result
    }

    /**
     * Fusiona dos paneles de UI (como `root_panel` o un control contenedor).
     */
    private fun mergePanelObjects(base: JSONObject, incoming: JSONObject, path: String): JSONObject {
        val merged = JSONObject(base.toString())

        incoming.keys().forEach { prop ->
            when (prop) {
                "modifications" -> {
                    val baseMods = merged.optJSONArray("modifications")
                    val incMods = incoming.optJSONArray("modifications")
                    if (baseMods != null && incMods != null) {
                        merged.put("modifications", mergeModificationsArray(baseMods, incMods))
                    } else if (incMods != null) {
                        merged.put("modifications", incMods)
                    }
                }
                "controls" -> {
                    val baseControls = merged.optJSONArray("controls")
                    val incControls = incoming.optJSONArray("controls")
                    if (baseControls != null && incControls != null) {
                        merged.put("controls", mergeControlsArray(baseControls, incControls))
                    } else if (incControls != null) {
                        merged.put("controls", incControls)
                    }
                }
                "bindings" -> {
                    val baseBindings = merged.optJSONArray("bindings")
                    val incBindings = incoming.optJSONArray("bindings")
                    if (baseBindings != null && incBindings != null) {
                        merged.put("bindings", mergeGeneralArrays(baseBindings, incBindings))
                    } else if (incBindings != null) {
                        merged.put("bindings", incBindings)
                    }
                }
                else -> {
                    if (!merged.has(prop)) {
                        merged.put(prop, incoming.get(prop))
                    } else {
                        val bVal = merged.get(prop)
                        val iVal = incoming.get(prop)
                        if (bVal is JSONObject && iVal is JSONObject) {
                            merged.put(prop, mergePanelObjects(bVal, iVal, "$path/$prop"))
                        }
                    }
                }
            }
        }

        return merged
    }

    /**
     * Fusiona dos arrays de "modifications" en Bedrock JSON UI.
     *
     * Si dos modificaciones apuntan al mismo array_name y operación (ej. insert_back en controls),
     * sus listas de widgets en "value" se combinan de forma unificada.
     */
    fun mergeModificationsArray(baseMods: JSONArray, incMods: JSONArray): JSONArray {
        val result = JSONArray()
        val modificationsMap = mutableMapOf<String, JSONObject>()

        // 1. Cargar modificaciones base
        for (i in 0 until baseMods.length()) {
            val mod = baseMods.optJSONObject(i) ?: continue
            val arrayName = mod.optString("array_name", "controls")
            val op = mod.optString("operation", "insert_back")
            val key = "$arrayName::$op"

            val clone = JSONObject(mod.toString())
            modificationsMap[key] = clone
            result.put(clone)
        }

        // 2. Fusionar modificaciones de incoming
        for (i in 0 until incMods.length()) {
            val incMod = incMods.optJSONObject(i) ?: continue
            val arrayName = incMod.optString("array_name", "controls")
            val op = incMod.optString("operation", "insert_back")
            val key = "$arrayName::$op"

            val existing = modificationsMap[key]
            if (existing != null) {
                // Combinar los elementos del array "value"
                val existingValues = existing.optJSONArray("value")
                val incValues = incMod.optJSONArray("value")

                if (existingValues != null && incValues != null) {
                    val mergedValues = mergeControlsArray(existingValues, incValues)
                    existing.put("value", mergedValues)
                } else if (incValues != null) {
                    existing.put("value", incValues)
                }
            } else {
                val clone = JSONObject(incMod.toString())
                modificationsMap[key] = clone
                result.put(clone)
            }
        }

        return result
    }

    /**
     * Fusiona dos arrays de "controls" en Bedrock JSON UI:
     * `[ { "widget_name@parent_template": {} } ]`
     *
     * Evita duplicados identificando el nombre del control (primera clave de cada objeto).
     */
    fun mergeControlsArray(baseControls: JSONArray, incControls: JSONArray): JSONArray {
        val result = JSONArray()
        val seenControlNames = mutableSetOf<String>()

        for (i in 0 until baseControls.length()) {
            val ctrlObj = baseControls.optJSONObject(i)
            if (ctrlObj != null) {
                val ctrlName = ctrlObj.keys().asSequence().firstOrNull()?.substringBefore('@')?.trim()
                if (ctrlName != null) seenControlNames.add(ctrlName)
                result.put(ctrlObj)
            } else {
                result.put(baseControls.get(i))
            }
        }

        for (i in 0 until incControls.length()) {
            val ctrlObj = incControls.optJSONObject(i)
            if (ctrlObj != null) {
                val ctrlName = ctrlObj.keys().asSequence().firstOrNull()?.substringBefore('@')?.trim()
                if (ctrlName == null || !seenControlNames.contains(ctrlName)) {
                    if (ctrlName != null) seenControlNames.add(ctrlName)
                    result.put(ctrlObj)
                }
            } else {
                result.put(incControls.get(i))
            }
        }

        return result
    }

    /**
     * Fusión genérica de arrays JSON evitando duplicados idénticos en string.
     */
    private fun mergeGeneralArrays(baseArr: JSONArray, incArr: JSONArray): JSONArray {
        val result = JSONArray()
        val seen = mutableSetOf<String>()

        for (i in 0 until baseArr.length()) {
            val item = baseArr.get(i)
            seen.add(item.toString())
            result.put(item)
        }

        for (i in 0 until incArr.length()) {
            val item = incArr.get(i)
            if (!seen.contains(item.toString())) {
                seen.add(item.toString())
                result.put(item)
            }
        }

        return result
    }

    // ── Parseo y escritura segura ──────────────────────────────────────────

    /**
     * Limpia comentarios estilo C (`//...` y `/*...*/`) fuera de literales de cadena.
     */
    fun sanitizeJsonComments(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        val n = raw.length
        var inString = false
        var escape = false

        while (i < n) {
            val c = raw[i]
            if (inString) {
                sb.append(c)
                if (escape) {
                    escape = false
                } else if (c == '\\') {
                    escape = true
                } else if (c == '"') {
                    inString = false
                }
                i++
                continue
            }

            if (c == '"') {
                inString = true
                sb.append(c)
                i++
                continue
            }

            if (c == '/' && i + 1 < n) {
                val next = raw[i + 1]
                if (next == '/') {
                    i += 2
                    while (i < n && raw[i] != '\n' && raw[i] != '\r') {
                        i++
                    }
                    continue
                } else if (next == '*') {
                    i += 2
                    while (i + 1 < n && !(raw[i] == '*' && raw[i + 1] == '/')) {
                        i++
                    }
                    i += 2
                    continue
                }
            }

            sb.append(c)
            i++
        }
        return sb.toString()
    }

    fun parseLenient(text: String): JSONObject? {
        val sanitized = sanitizeJsonComments(text)
        return try {
            JSONObject(sanitized)
        } catch (_: Exception) {
            LenientJson.parseObject(sanitized)
        }
    }

    private fun writeJson(file: File, json: JSONObject) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { fos ->
            OutputStreamWriter(fos, StandardCharsets.UTF_8).use { writer ->
                writer.write(json.toString(2))
                writer.write("\n")
            }
        }
    }
}

