package com.packforge.app.domain.engine

import com.packforge.app.domain.model.ConflictSeverity
import com.packforge.app.util.PackForgeLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * ═══════════════════════════════════════════════════════════════════════
 * MERGE REFERENCE VALIDATOR — referencias cruzadas tras fusionar
 * ═══════════════════════════════════════════════════════════════════════
 *
 * `PackForgeValidator` ya valida texturas, modelos, entidades, ítems, bloques,
 * render controllers, sonidos y traducciones. Este validador cubre el hueco
 * restante: los **identificadores que la propia fusión manipula**, donde un
 * renombrado mal hecho deja una referencia colgando y el fallo es invisible
 * hasta que se abre el mundo.
 *
 * Comprueba:
 *  1. `particle_effect` referenciado → existe una partícula con ese identificador
 *     (crítico ahora que la fusión renombra duplicados).
 *  2. `animations` de las entidades cliente → existe en `animations/`.
 *  3. `materials` de render controllers/attachables → existe en `materials/`.
 *  4. `minecraft:custom_component` de bloques/ítems → algún script lo registra
 *     con `registerCustomComponent` (crítico tras desambiguar duplicados).
 *
 * Se ignoran los identificadores vanilla (`minecraft:*`) y los materiales del
 * motor (`Material.*`): son del juego, no del modpack.
 */
object MergeReferenceValidator {

    private const val TAG = "PackForge_RefValidator"
    private const val MAX_BROKEN_REPORTED = 40
    private const val MAX_FILES_SCANNED = 8000

    /** Referencia rota encontrada en el modpack fusionado. */
    data class BrokenReference(
        val kind: String,
        val identifier: String,
        val referencingFile: String
    )

    data class Report(
        val checkedReferences: Int,
        val broken: List<BrokenReference>
    ) {
        val isClean: Boolean get() = broken.isEmpty()
        val summary: String
            get() = if (isClean) {
                "Referencias verificadas: $checkedReferences (sin roturas)"
            } else {
                "Referencias verificadas: $checkedReferences · ROTAS: ${broken.size}"
            }
    }

    private val CUSTOM_COMPONENT_REGEX =
        Regex("""registerCustomComponent\s*\(\s*['"]([^'"]+)['"]""")

    /**
     * Valida las referencias del modpack YA fusionado.
     *
     * @param bpDir Behavior Pack combinado (puede ser null)
     * @param rpDir Resource Pack combinado (puede ser null)
     */
    fun validate(bpDir: File?, rpDir: File?): Report {
        val particleIds = collectParticleIdentifiers(rpDir)
        val animationIds = collectRootKeys(rpDir, "animations", "animations", ".json")
        val materialIds = collectMaterialIdentifiers(rpDir)
        val registeredComponents = collectRegisteredComponents(bpDir)

        val broken = mutableListOf<BrokenReference>()
        var checked = 0

        fun check(kind: String, identifier: String, known: Set<String>, from: String) {
            if (identifier.isBlank()) return
            if (isVanillaOrEngine(identifier)) return
            checked++
            if (identifier !in known) {
                if (broken.size < MAX_BROKEN_REPORTED) {
                    broken += BrokenReference(kind, identifier, from)
                } else if (broken.size == MAX_BROKEN_REPORTED) {
                    broken += BrokenReference(kind, "…", from)
                }
            }
        }

        // ── Referencias en el Resource Pack ──
        rpReferenceFiles(rpDir).forEach { file ->
            val json = readJson(file) ?: return@forEach
            val relative = relativeTo(rpDir, file)

            collectValuesOfKey(json, "particle_effect").forEach { value ->
                check("partícula", value, particleIds, relative)
            }
            collectValuesOfKey(json, "animations").forEach { value ->
                // Los client_entity declaran un mapa nombre→identificador.
                check("animación", value, animationIds, relative)
            }
            collectValuesOfKey(json, "materials").forEach { value ->
                check("material", value, materialIds, relative)
            }
        }

        // ── Referencias en el Behavior Pack ──
        bpReferenceFiles(bpDir).forEach { file ->
            val json = readJson(file) ?: return@forEach
            val relative = relativeTo(bpDir, file)

            collectValuesOfKey(json, "particle_effect").forEach { value ->
                check("partícula", value, particleIds, relative)
            }
            collectValuesOfKey(json, "minecraft:custom_component").forEach { value ->
                check("componente personalizado", value, registeredComponents, relative)
            }
        }

        val report = Report(checked, broken)
        if (report.isClean) {
            PackForgeLog.d(TAG, "✅ ${report.summary}")
        } else {
            PackForgeLog.w(TAG, "⚠️ ${report.summary}")
            broken.take(MAX_BROKEN_REPORTED).forEach {
                PackForgeLog.w(TAG, "   • ${it.kind} '${it.identifier}' ← ${it.referencingFile}")
            }
        }
        return report
    }

    /** Publica las roturas en el registro de conflictos para que se vean en la UI. */
    fun publish(report: Report) {
        report.broken
            .filter { it.identifier != "…" }
            .forEach { broken ->
                ConflictRegistry.logConflict(
                    severity = ConflictSeverity.MEDIUM,
                    type = "REFERENCIA_ROTA",
                    file = broken.referencingFile,
                    addon1 = "modpack fusionado",
                    addon2 = "-",
                    description = "El ${broken.kind} '${broken.identifier}' se referencia en " +
                        "'${broken.referencingFile}' pero no existe en el modpack."
                )
            }
    }

    // ── Recolección de definiciones ──────────────────────────────────────

    private fun collectParticleIdentifiers(rpDir: File?): Set<String> {
        if (rpDir == null) return emptySet()
        val ids = mutableSetOf<String>()
        jsonFilesIn(File(rpDir, "particles")).forEach { file ->
            val json = readJson(file) ?: return@forEach
            val identifier = json.optJSONObject("particle_effect")
                ?.optJSONObject("description")
                ?.optString("identifier")
                .orEmpty()
            if (identifier.isNotBlank()) ids += identifier
        }
        return ids
    }

    /**
     * Claves de un objeto raíz repartidas por carpetas
     * (p. ej. `{"animations": {"animation.a.walk": {…}}}`).
     */
    private fun collectRootKeys(
        root: File?,
        folder: String,
        rootKey: String,
        extension: String
    ): Set<String> {
        if (root == null) return emptySet()
        val keys = mutableSetOf<String>()
        val dir = File(root, folder)
        if (!dir.isDirectory) return emptySet()
        dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(extension, ignoreCase = true) }
            ?.forEach { file ->
                val json = readJson(file) ?: return@forEach
                json.optJSONObject(rootKey)?.keys()?.forEach { key ->
                    if (key.isNotBlank()) keys += key
                }
            }
        return keys
    }

    /** Los materiales viven en la carpeta `materials` (archivos .material). */
    private fun collectMaterialIdentifiers(rpDir: File?): Set<String> {
        if (rpDir == null) return emptySet()
        val ids = mutableSetOf<String>()
        val dir = File(rpDir, "materials")
        if (!dir.isDirectory) return emptySet()
        dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".material", ignoreCase = true) }
            ?.forEach { file ->
                val json = readJson(file) ?: return@forEach
                json.optJSONObject("materials")?.keys()?.forEach { key ->
                    if (key.isNotBlank() && key != "version") ids += key
                }
            }
        return ids
    }

    /** Componentes registrados por los scripts del BP. */
    private fun collectRegisteredComponents(bpDir: File?): Set<String> {
        if (bpDir == null) return emptySet()
        val dir = File(bpDir, "scripts")
        if (!dir.isDirectory) return emptySet()

        val ids = mutableSetOf<String>()
        dir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase(Locale.ROOT) in setOf("js", "ts", "mjs") }
            .take(MAX_FILES_SCANNED)
            .forEach { file ->
                val text = try {
                    file.readText(Charsets.UTF_8)
                } catch (_: Exception) {
                    return@forEach
                }
                CUSTOM_COMPONENT_REGEX.findAll(text).forEach { match ->
                    ids += match.groupValues[1]
                }
            }
        return ids
    }

    // ── Recorrido de referencias ─────────────────────────────────────────

    /** Archivos del RP que pueden referenciar partículas/animaciones/materiales. */
    private fun rpReferenceFiles(rpDir: File?): List<File> {
        if (rpDir == null) return emptyList()
        // render_controllers es donde se declaran los `materials` que usa cada
        // controlador: sin esta carpeta, un material inexistente no se detectaba.
        return listOf("entity", "attachables", "render_controllers")
            .map { File(rpDir, it) }
            .filter { it.isDirectory }
            .flatMap { jsonFilesIn(it) }
            .take(MAX_FILES_SCANNED)
    }

    /** Archivos del BP que pueden referenciar partículas/componentes. */
    private fun bpReferenceFiles(bpDir: File?): List<File> {
        if (bpDir == null) return emptyList()
        return listOf("items", "blocks", "entities")
            .map { File(bpDir, it) }
            .filter { it.isDirectory }
            .flatMap { jsonFilesIn(it) }
            .take(MAX_FILES_SCANNED)
    }

    private fun jsonFilesIn(dir: File): List<File> =
        if (!dir.isDirectory) emptyList()
        else dir.walkTopDown().filter { it.isFile && it.extension.equals("json", true) }.toList()

    /**
     * Recoge los valores de cadena asociados a [key] en cualquier profundidad.
     *
     * La misma clave aparece con formas distintas según el archivo:
     *  - `"particle_effect": "ns:dust"` (escalar),
     *  - `"animations": { "walk": "animation.x.walk" }` (mapa),
     *  - `"materials": [ { "*": "Material.default" } ]` (lista de mapas).
     */
    private fun collectValuesOfKey(json: Any?, key: String, depth: Int = 0): List<String> {
        if (depth > 12 || json == null) return emptyList()
        val values = mutableListOf<String>()

        when (json) {
            is JSONObject -> {
                json.keys().forEach { currentKey ->
                    val value = json.opt(currentKey)
                    if (currentKey == key) {
                        when (value) {
                            is String -> values += value
                            is JSONObject -> value.keys().forEach { nested ->
                                value.optString(nested).takeIf { it.isNotBlank() }?.let { values += it }
                            }
                            is JSONArray -> {
                                for (i in 0 until value.length()) {
                                    when (val item = value.opt(i)) {
                                        is String -> values += item
                                        is JSONObject -> item.keys().forEach { nested ->
                                            item.optString(nested).takeIf { it.isNotBlank() }
                                                ?.let { values += it }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        values += collectValuesOfKey(value, key, depth + 1)
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until json.length()) {
                    values += collectValuesOfKey(json.opt(i), key, depth + 1)
                }
            }
        }
        return values
    }

    // ── Utilidades ───────────────────────────────────────────────────────

    /** Vanilla del juego o material del motor: nunca es una referencia rota. */
    private fun isVanillaOrEngine(identifier: String): Boolean =
        identifier.startsWith("minecraft:") || identifier.startsWith("Material.")

    private fun readJson(file: File): JSONObject? = try {
        JsonDeepMerger.cleanJsonObject(JSONObject(file.readText(Charsets.UTF_8)))
    } catch (_: Exception) {
        null
    }

    private fun relativeTo(root: File?, file: File): String =
        if (root == null) file.name
        else runCatching { file.relativeTo(root).invariantSeparatorsPath }.getOrDefault(file.name)
}
