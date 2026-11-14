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

    /**
     * Marca que la lista de roturas se cortó por [MAX_BROKEN_REPORTED].
     *
     * Existe para que el total del reporte no mienta: antes se añadía un
     * `BrokenReference("…")` y el resumen contaba `broken.size`, así que un pack
     * con 40 roturas listadas y muchas más sin listar mostraba "ROTAS: 41".
     */
    private const val TRUNCATION_MARKER = "…"

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
        /** Roturas reales (el sentinel de truncado no cuenta como referencia). */
        val brokenCount: Int get() = broken.count { it.identifier != TRUNCATION_MARKER }

        /** Nº de referencias distintas afectadas, útil para el resumen. */
        val distinctBrokenIds: Int get() = broken.filter { it.identifier != TRUNCATION_MARKER }
            .map { "${it.kind}|${it.identifier}" }.distinct().size

        val isClean: Boolean get() = brokenCount == 0

        /** ¿La lista quedó cortada por el tope de reporte? */
        val isTruncated: Boolean get() = broken.any { it.identifier == TRUNCATION_MARKER }

        val summary: String
            get() = when {
                isClean -> "Referencias verificadas: $checkedReferences (sin roturas)"
                // ⭐ FASE 5: antes el resumen decía "ROTAS: 41" cuando había 40
                // reales + el sentinel de truncado, y no avisaba de que había
                // más sin listar. Esa cifra se lee como el total del pack.
                isTruncated ->
                    "Referencias verificadas: $checkedReferences · ROTAS: $brokenCount " +
                        "(${distinctBrokenIds} identificadores distintos; hay MÁS sin listar)"
                else ->
                    "Referencias verificadas: $checkedReferences · ROTAS: $brokenCount " +
                        "(${distinctBrokenIds} identificadores distintos)"
            }
    }

    private val CUSTOM_COMPONENT_REGEX =
        Regex("""registerCustomComponent\s*\(\s*['"]([^'"]+)['"]""")

    /**
     * Valida las referencias del modpack YA fusionado.
     *
     * @param bpDir Behavior Pack combinado (puede ser null)
     * @param rpDir Resource Pack combinado (puede ser null)
     * @param sourceDirs Directorios de origen de los addons (para índice unificado)
     */
    fun validate(bpDir: File?, rpDir: File?, sourceDirs: List<File> = emptyList()): Report {
        // Índice unificado para animaciones y render controllers (evita re-escanear)
        val unifiedIndex = GeometryAnimRCIndex.build(sourceDirs, rpDir)
        
        val particleIds = collectParticleIdentifiers(rpDir)
        // Usar IDs del pack fusionado (mergedAnimationIds/mergedRenderControllerIds)
        // en lugar de sourceDirs, porque el test valida el pack YA fusionado
        val animationIds = unifiedIndex.mergedAnimationIds
        val materialIds = collectMaterialIdentifiers(rpDir)
        val registeredComponents = collectRegisteredComponents(bpDir)

        val broken = mutableListOf<BrokenReference>()
        var checked = 0

        fun check(kind: String, identifier: String, known: Set<String>, from: String) {
            if (identifier.isBlank()) return
            if (isVanillaOrEngine(identifier)) return
            // ⭐ FASE 5: los materiales de fábrica de Bedrock se referencian SIN
            // namespace ('boat', 'villager', 'entity_alphatest'…). No viven en
            // la carpeta materials/ del pack, así que sin este filtro salían como
            // REFERENCIA_ROTA. Eran la mayoría de las 41 del reporte de Better MC.
            if (kind == "material" && VanillaAssets.isVanillaMaterial(identifier)) return
            checked++
            if (identifier !in known) {
                if (broken.size < MAX_BROKEN_REPORTED) {
                    broken += BrokenReference(kind, identifier, from)
                } else if (broken.size == MAX_BROKEN_REPORTED) {
                    broken += BrokenReference(kind, TRUNCATION_MARKER, from)
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
            // Nota: render_controllers se validan en EntityDependencyResolver,
            // no aquí (evita falsos positivos al extraer claves de definición).
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
            .filter { it.identifier != TRUNCATION_MARKER }
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
            ?.filter {
                it.isFile && (it.name.endsWith(".material", ignoreCase = true) ||
                    it.name.endsWith(".json", ignoreCase = true))
            }
            ?.forEach { file ->
                val json = readJson(file) ?: return@forEach
                json.optJSONObject("materials")?.keys()?.forEach { key ->
                    if (key.isBlank() || key == "version") return@forEach
                    // ⭐ FASE 5 (falso positivo real, visible en Better MC):
                    // en `materials/*.material` la CLAVE es
                    // `nombre:padre` (`glow:entity_alphablend`,
                    // `cc_entity_enchant:entity_static`), pero el
                    // `client_entity` referencia **solo el nombre**
                    // (`"default": "glow"`). Comparar la clave completa contra
                    // la referencia hacía que todo material heredado del pack se
                    // reportara como inexistente: `glow`, `cc_outline`,
                    // `cc_entity_enchant`… salían como "material custom no
                    // existe" aunque estuvieran definidos dos líneas más arriba.
                    ids += key
                    val nombre = key.substringBefore(':')
                    if (nombre.isNotBlank()) ids += nombre
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

    /**
     * Vanilla del juego, material del motor, alias o Molang: nunca es una
     * referencia rota.
     *
     * Antes solo filtraba `minecraft:` y `Material.`, así que
     * `animation.common.look_at_target` y `controller.animation.iron_golem.move`
     * (ambas VANILLA) se publicaban como REFERENCIA_ROTA de severidad MEDIUM.
     */
    private fun isVanillaOrEngine(identifier: String): Boolean =
        identifier.startsWith("minecraft:") ||
            identifier.startsWith("Material.") ||
            BedrockRefs.isAlias(identifier) ||
            BedrockRefs.isExpression(identifier) ||
            VanillaAssets.isVanillaReference(identifier)

    /**
     * Lee un JSON del pack tolerando lo que Bedrock tolera.
     *
     * ⭐ FASE 5 — CAUSA RAÍZ de los 41 materiales "inexistentes".
     *
     * `JSONObject(texto)` es JSON **estricto** y rechaza los comentarios `//`,
     * que Bedrock sí admite en cualquier posición. El `materials/entity.material`
     * real de CoreCraft los trae:
     *
     * ```json
     * "cc_test:charged_creeper": {
     *     // "depthFunc": "Always",
     *     // ^ Uncomment if you want the outline to be
     *     //   rendered behind the block.
     * }
     * ```
     *
     * Con el parseo estricto el archivo ENTERO fallaba y devolvía `null`, así que
     * **ninguno** de sus 10 materiales se indexaba y todos se reportaban como
     * ausentes (`glow`, `cc_outline`, `cc_entity_enchant`,
     * `outline_base_enchant`…). No era un problema de nombres ni de herencia:
     * era que el archivo nunca se llegaba a leer.
     *
     * `LenientJson` acepta comentarios y comas finales, igual que el juego.
     */
    private fun readJson(file: File): JSONObject? = try {
        val text = file.readText(Charsets.UTF_8)
        val parsed = LenientJson.parseObject(text)
            ?: JSONObject(text)
        JsonDeepMerger.cleanJsonObject(parsed)
    } catch (_: Exception) {
        null
    }

    private fun relativeTo(root: File?, file: File): String =
        if (root == null) file.name
        else runCatching { file.relativeTo(root).invariantSeparatorsPath }.getOrDefault(file.name)
}
