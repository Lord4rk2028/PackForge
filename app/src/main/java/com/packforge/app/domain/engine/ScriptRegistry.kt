package com.packforge.app.domain.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

/**
 * ═══════════════════════════════════════════════════════════════════════
 * SCRIPT REGISTRY — descubre los módulos de script de cada Behavior Pack
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Acepta tanto **carpetas** ya extraídas como **archivos ZIP** (`.mcpack`,
 * `.mcaddon`, `.zip`), que es el formato en el que PackForge guarda los addons
 * importados. Así la puerta de compatibilidad puede evaluar los scripts antes
 * de fusionar, sin necesidad de extraer nada a disco.
 *
 * De cada manifest.json se leen los módulos de tipo `script` y su `entry`.
 * Del contenido solo se guarda el **texto del entry point** (lo mínimo para
 * analizar importaciones y registros) y la **lista de rutas** `.js`/`.ts` del
 * pack, que se usa para comprobar si una importación existe de verdad.
 */
object ScriptRegistry {

    /** Extensiones consideradas código de script. */
    private val SCRIPT_EXTENSIONS = setOf("js", "ts", "mjs")

    /**
     * Un script registrado de un Behavior Pack.
     *
     * @property scriptId identificador único (uuid del pack + entry point)
     * @property metadata datos declarados en el manifest
     * @property manifestJson manifest completo, para análisis adicionales
     * @property sourceText contenido del entry point
     * @property entryFile archivo real del entry (solo si la fuente es carpeta)
     * @property packPath ruta del addon del que proviene (carpeta o zip)
     * @property entryPath ruta del entry relativa a la raíz del pack
     * @property allScripts rutas relativas de todos los .js/.ts del pack
     * @property allScriptSources mapa de ruta relativa -> contenido de código de todos los scripts
     */
    data class RegisteredScript(
        val scriptId: String,
        val metadata: ScriptMetadata,
        val manifestJson: JSONObject,
        val sourceText: String,
        val entryFile: File?,
        val packPath: String,
        val entryPath: String,
        val allScripts: Set<String>,
        val allScriptSources: Map<String, String> = emptyMap()
    )

    /**
     * Carga los scripts de todos los Behavior Packs indicados.
     *
     * Los packs sin módulos de script se ignoran silenciosamente: la mayoría de
     * addons no llevan código y no deben aportar ruido al análisis.
     *
     * @param bpDirs rutas de Behavior Packs (carpetas o ZIPs)
     * @param rpDirs rutas de Resource Packs; se aceptan por compatibilidad de
     *   firma, pero los scripts viven en los Behavior Packs.
     * @return mapa `scriptId -> RegisteredScript` en orden de aparición
     */
    fun loadScriptsFromBPs(
        bpDirs: List<String>,
        @Suppress("UNUSED_PARAMETER") rpDirs: List<String> = emptyList()
    ): Map<String, RegisteredScript> {
        val result = LinkedHashMap<String, RegisteredScript>()

        bpDirs.asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { path ->
                val file = File(path)
                if (!file.exists()) return@forEach

                val loaded = try {
                    if (file.isDirectory) loadFromDirectory(file)
                    else if (isZip(file)) loadFromZip(file)
                    else emptyList()
                } catch (_: Exception) {
                    // Un pack corrupto no debe tumbar el análisis completo.
                    emptyList()
                }

                loaded.forEach { script ->
                    // Si dos packs declaran el mismo scriptId, se conserva el
                    // primero pero se desambigua para no perder el segundo.
                    var id = script.scriptId
                    var suffix = 2
                    while (result.containsKey(id)) {
                        id = "${script.scriptId}#$suffix"
                        suffix++
                    }
                    result[id] = if (id == script.scriptId) script else script.copy(scriptId = id)
                }
            }

        return result
    }

    // ── Carga desde carpeta ──────────────────────────────────────────────

    private fun loadFromDirectory(root: File): List<RegisteredScript> {
        val manifestFile = findManifestInDirectory(root) ?: return emptyList()
        val packRoot = manifestFile.parentFile ?: root
        val manifest = readJson(manifestFile.readText()) ?: return emptyList()

        val allScripts = packRoot.walkTopDown()
            .filter { it.isFile && it.extension.lowercase(Locale.ROOT) in SCRIPT_EXTENSIONS }
            .map { it.relativeTo(packRoot).invariantSeparatorsPath }
            .toSet()

        val allSources = mutableMapOf<String, String>()
        allScripts.forEach { rel ->
            val f = File(packRoot, rel)
            if (f.isFile && f.length() < 2_000_000) {
                try {
                    allSources[rel] = f.readText(Charsets.UTF_8)
                } catch (_: Exception) {}
            }
        }

        return buildScripts(
            manifest = manifest,
            packPath = root.absolutePath,
            allScripts = allScripts,
            allScriptSources = allSources
        ) { entry ->
            val entryFile = File(packRoot, entry)
            val text = allSources[entry] ?: try {
                if (entryFile.isFile) entryFile.readText(Charsets.UTF_8) else ""
            } catch (_: Exception) {
                ""
            }
            text to entryFile
        }
    }

    /** Busca `manifest.json` en la raíz y, si no está, un nivel más abajo. */
    private fun findManifestInDirectory(root: File): File? {
        val direct = File(root, "manifest.json")
        if (direct.isFile) return direct
        return root.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir -> File(dir, "manifest.json").takeIf { it.isFile } }
            ?.firstOrNull()
    }

    // ── Carga desde ZIP ──────────────────────────────────────────────────

    private fun isZip(file: File): Boolean =
        file.extension.lowercase(Locale.ROOT) in setOf("zip", "mcpack", "mcaddon")

    private fun loadFromZip(zipFile: File): List<RegisteredScript> {
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries().toList()
            val manifestEntry = entries
                .filter { !it.isDirectory && it.name.substringAfterLast('/') == "manifest.json" }
                // El manifest raíz (menos segmentos) tiene prioridad.
                .minByOrNull { it.name.count { c -> c == '/' } }
                ?: return emptyList()

            val manifestText = zip.getInputStream(manifestEntry).use {
                it.readBytes().toString(Charsets.UTF_8)
            }
            val manifest = readJson(manifestText) ?: return emptyList()

            val prefix = manifestEntry.name.removeSuffix("manifest.json")

            val allScripts = entries.asSequence()
                .filter { !it.isDirectory }
                .map { it.name }
                .filter { it.startsWith(prefix) }
                .map { it.removePrefix(prefix) }
                .filter { it.substringAfterLast('.', "").lowercase(Locale.ROOT) in SCRIPT_EXTENSIONS }
                .toSet()

            val allSources = mutableMapOf<String, String>()
            allScripts.forEach { rel ->
                val fullName = prefix + rel
                val zipEntry = zip.getEntry(fullName)
                    ?: zip.getEntry(fullName.replace('\\', '/'))
                if (zipEntry != null && zipEntry.size < 2_000_000) {
                    try {
                        allSources[rel] = zip.getInputStream(zipEntry).use {
                            it.readBytes().toString(Charsets.UTF_8)
                        }
                    } catch (_: Exception) {}
                }
            }

            return buildScripts(
                manifest = manifest,
                packPath = zipFile.absolutePath,
                allScripts = allScripts,
                allScriptSources = allSources
            ) { entry ->
                val text = allSources[entry] ?: run {
                    val fullName = prefix + entry
                    val zipEntry = zip.getEntry(fullName)
                        ?: zip.getEntry(fullName.replace('\\', '/'))
                    if (zipEntry != null) {
                        try {
                            zip.getInputStream(zipEntry).use { it.readBytes().toString(Charsets.UTF_8) }
                        } catch (_: Exception) { "" }
                    } else ""
                }
                text to null
            }
        }
    }

    // ── Construcción de RegisteredScript a partir del manifest ────────────

    private fun buildScripts(
        manifest: JSONObject,
        packPath: String,
        allScripts: Set<String>,
        allScriptSources: Map<String, String> = emptyMap(),
        readEntry: (String) -> Pair<String, File?>
    ): List<RegisteredScript> {
        val modules = manifest.optJSONArray("modules") ?: return emptyList()
        val header = manifest.optJSONObject("header")

        val packUuid = header?.optString("uuid").orEmpty().ifBlank { packPath }
        val headerVersion = versionToString(header?.opt("version"))
        val minEngine = versionToString(header?.opt("min_engine_version"))
            .ifBlank { "1.20.0" }
        val authors = parseAuthors(manifest.optJSONObject("metadata"))

        val rawDeps = manifest.optJSONArray("dependencies") ?: header?.optJSONArray("dependencies")
        val dependencies = parseDependencies(rawDeps)
        val moduleDependencies = parseModuleDependencies(rawDeps)

        val scripts = mutableListOf<RegisteredScript>()

        for (index in 0 until modules.length()) {
            val module = modules.optJSONObject(index) ?: continue
            if (!isScriptModule(module)) continue

            val entry = module.optString("entry").trim()
            if (entry.isBlank()) continue

            val normalizedEntry = entry.replace('\\', '/').removePrefix("./")
            val (sourceText, entryFile) = readEntry(normalizedEntry)

            val moduleVersion = versionToString(module.opt("version")).ifBlank { headerVersion }
            val scriptId = "$packUuid::$normalizedEntry"

            val metadata = ScriptMetadata(
                scriptId = scriptId,
                version = moduleVersion,
                author = authors.firstOrNull().orEmpty(),
                dependencies = dependencies,
                executionConditions = ExecutionConditions(
                    minEngineVersion = minEngine
                ),
                conflictPolicies = ConflictPolicies(),
                entryPoint = entry,
                resolvedEntryPoint = normalizedEntry,
                namespace = null,
                tags = emptyList(),
                moduleDependencies = moduleDependencies
            )

            scripts += RegisteredScript(
                scriptId = scriptId,
                metadata = metadata,
                manifestJson = manifest,
                sourceText = sourceText,
                entryFile = entryFile,
                packPath = packPath,
                entryPath = normalizedEntry,
                allScripts = allScripts,
                allScriptSources = allScriptSources
            )
        }

        return scripts
    }

    /** Un módulo es de script si su `type` es `script` (o variantes conocidas). */
    private fun isScriptModule(module: JSONObject): Boolean {
        val type = module.optString("type").lowercase(Locale.ROOT)
        return type == "script" || type == "javascript" || type == "scripts"
    }

    private fun parseAuthors(metadata: JSONObject?): List<String> {
        val authors = metadata?.optJSONArray("authors") ?: return emptyList()
        return (0 until authors.length()).mapNotNull { i ->
            when (val value = authors.opt(i)) {
                is String -> value.takeIf { it.isNotBlank() }
                is JSONObject -> value.optString("name").takeIf { s -> s.isNotBlank() }
                else -> null
            }
        }
    }

    private fun parseDependencies(deps: JSONArray?): List<String> {
        if (deps == null) return emptyList()
        return (0 until deps.length()).mapNotNull { i ->
            val obj = deps.optJSONObject(i) ?: return@mapNotNull null
            val id = obj.optString("module_name").takeIf { it.isNotBlank() }
                ?: obj.optString("uuid").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val version = versionToString(obj.opt("version"))
            if (version.isBlank()) id else "$id@$version"
        }
    }

    private fun parseModuleDependencies(deps: JSONArray?): List<ScriptModuleDependency> {
        if (deps == null) return emptyList()
        val list = mutableListOf<ScriptModuleDependency>()
        for (i in 0 until deps.length()) {
            val obj = deps.optJSONObject(i) ?: continue
            val name = obj.optString("module_name").ifBlank { obj.optString("uuid") }
            if (name.isBlank()) continue
            val version = versionToString(obj.opt("version"))
            val isBeta = version.contains("beta", ignoreCase = true)
            val isServerOnly = name == "@minecraft/server-net" || name == "@minecraft/server-admin"
            val isLegacy = name == "mojang-minecraft" || name == "mojang-gametest" || name == "mojang-minecraft-ui"
            list.add(
                ScriptModuleDependency(
                    name = name,
                    version = version,
                    isBeta = isBeta,
                    isServerOnly = isServerOnly,
                    isLegacy = isLegacy
                )
            )
        }
        return list
    }

    /** Convierte `[1,20,10]`, `"1.20.10"` o `{...}` en una cadena legible. */
    private fun versionToString(value: Any?): String = when (value) {
        null -> ""
        is String -> value.trim()
        is JSONArray -> (0 until value.length())
            .mapNotNull { i -> value.opt(i)?.toString() }
            .joinToString(".")
        else -> value.toString()
    }

    private fun readJson(text: String): JSONObject? = try {
        JSONObject(text)
    } catch (_: Exception) {
        null
    }
}
