package com.packforge.app.domain.engine

import com.packforge.app.domain.model.ConflictSeverity
import org.json.JSONObject
import java.io.File

/** Extensiones de script reconocidas por TODO el pipeline PackForge (única fuente de verdad). */
val SCRIPT_EXTENSIONS = setOf("js", "mjs", "ts", "mcs")

/**
 * Static preflight for behavior packs that contain Script API code.
 *
 * It deliberately reports only objective runtime hazards. It does not attempt to
 * rewrite arbitrary JavaScript: changing a creator's program can silently change
 * gameplay, which is worse than reporting an incompatibility.
 */
object BedrockCompatibilityAnalyzer {

    data class Finding(
        val severity: ConflictSeverity,
        val type: String,
        val file: String,
        val source: String,
        val target: String = "",
        val description: String,
        val blocksExport: Boolean = false
    )

    private data class ScriptPack(
        val id: String,
        val directory: File,
        val manifest: JSONObject
    )

    private val customComponentPattern = Regex(
        """registerCustomComponent\s*\(\s*['"]([^'"]+)['"]"""
    )
    private val dynamicPropertyPattern = Regex(
        """(?:setDynamicProperty|getDynamicProperty)\s*\(\s*['"]([^'"]+)['"]"""
    )
    private val localImportPattern = Regex(
        """(?:from\s*|import\s*\(|import\s*)['"](\.{1,2}/[^'"]+)['"]"""
    )

    fun analyze(
        behaviorPackDirs: List<String>,
        resourcePackDirs: List<String> = emptyList()
    ): List<Finding> {
        val packs = behaviorPackDirs.mapIndexedNotNull { index, path ->
            val directory = File(path)
            val manifestFile = File(directory, "manifest.json")
            if (!manifestFile.isFile) return@mapIndexedNotNull null
            try {
                val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
                val uuid = manifest.optJSONObject("header")?.optString("uuid", "").orEmpty()
                ScriptPack(uuid.ifBlank { "behavior-pack-${index + 1}" }, directory, manifest)
            } catch (_: Exception) {
                null
            }
        }

        val findings = mutableListOf<Finding>()
        val componentOwners = mutableMapOf<String, String>()
        val propertyOwners = mutableMapOf<String, String>()
        val moduleTracks = mutableMapOf<String, MutableSet<String>>()

        packs.forEach { pack ->
            val scriptModules = pack.manifest.optJSONArray("modules")
            for (i in 0 until (scriptModules?.length() ?: 0)) {
                val module = scriptModules?.optJSONObject(i) ?: continue
                if (module.optString("type") != "script") continue
                val entry = module.optString("entry", "")
                val resolvedEntry = resolveScriptEntry(pack.directory, entry)
                if (resolvedEntry == null) {
                    findings += Finding(
                        ConflictSeverity.CRITICAL,
                        "SCRIPT_ENTRY_INVALID",
                        "manifest.json",
                        pack.id,
                        description = "El módulo script declara '$entry', pero no se encontró un archivo JavaScript equivalente dentro del Behavior Pack.",
                        blocksExport = true
                    )
                }
            }

            val dependencies = pack.manifest.optJSONArray("dependencies")
            for (i in 0 until (dependencies?.length() ?: 0)) {
                val dependency = dependencies?.optJSONObject(i) ?: continue
                val name = dependency.optString("module_name", "")
                if (!name.startsWith("@minecraft/")) continue
                val version = dependency.opt("version")?.toString().orEmpty()
                val track = if (version.contains("beta", ignoreCase = true)) "beta" else "stable"
                moduleTracks.getOrPut(name) { mutableSetOf() }.add(track)
                if (name == "@minecraft/server-net" || name == "@minecraft/server-admin") {
                    findings += Finding(
                        ConflictSeverity.CRITICAL,
                        "SERVER_ONLY_SCRIPT_MODULE",
                        "manifest.json",
                        pack.id,
                        description = "$name solo funciona en Bedrock Dedicated Server; no puede ejecutarse en Minecraft Android normal.",
                        blocksExport = true
                    )
                }
            }

            pack.directory.walkTopDown().filter { it.isFile && it.extension.equals("js", true) }.forEach { script ->
                val relativeFile = script.relativeTo(pack.directory).invariantSeparatorsPath
                val code = try { script.readText(Charsets.UTF_8) } catch (_: Exception) { return@forEach }
                customComponentPattern.findAll(code).forEach { match ->
                    val component = match.groupValues[1]
                    val previous = componentOwners.putIfAbsent(component, pack.id)
                    if (previous != null && previous != pack.id) {
                        findings += Finding(
                            ConflictSeverity.CRITICAL,
                            "CUSTOM_COMPONENT_DUPLICATE",
                            relativeFile,
                            pack.id,
                            previous,
                            "Ambos addons registran '$component'. Bedrock rechaza el segundo registro del componente.",
                            blocksExport = true
                        )
                    }
                }
                dynamicPropertyPattern.findAll(code).forEach { match ->
                    val property = match.groupValues[1]
                    val previous = propertyOwners.putIfAbsent(property, pack.id)
                    if (previous != null && previous != pack.id) {
                        findings += Finding(
                            ConflictSeverity.WARNING,
                            "DYNAMIC_PROPERTY_SHARED",
                            relativeFile,
                            pack.id,
                            previous,
                            "Ambos addons usan la dynamic property '$property'. Verifica que no guarden formatos de datos distintos.",
                            blocksExport = false
                        )
                    }
                }
                localImportPattern.findAll(code).forEach { match ->
                    val importPath = match.groupValues[1]
                    if (!localModuleExists(pack.directory, script.parentFile!!, importPath)) {
                        findings += Finding(
                            ConflictSeverity.CRITICAL,
                            "SCRIPT_LOCAL_IMPORT_MISSING",
                            relativeFile,
                            pack.id,
                            description = "El import local '$importPath' no existe para $relativeFile. Bedrock no cargará el módulo script.",
                            blocksExport = true
                        )
                    }
                }
            }
        }

        moduleTracks.filterValues { it.size > 1 }.forEach { (module, tracks) ->
            findings += Finding(
                ConflictSeverity.CRITICAL,
                "SCRIPT_API_TRACK_MISMATCH",
                "manifest.json",
                module,
                description = "Se mezclan dependencias ${tracks.joinToString(" y ")} para $module. Las APIs beta no garantizan compatibilidad con stable.",
                blocksExport = true
            )
        }

        // player.json controls the shared player skeleton and animation graph. Two
        // arbitrary definitions cannot be safely deep-merged: order and overrides
        // change the meaning of the graph, which is exactly what breaks animated
        // weapons when combined with player-animation packs.
        val playerJsonOwners = resourcePackDirs.mapIndexedNotNull { index, path ->
            val file = File(path, "player.json")
            file.takeIf { it.isFile }?.let { "resource-pack-${index + 1}" to it }
        }
        if (playerJsonOwners.size > 1) {
            val owners = playerJsonOwners.map { it.first }
            findings += Finding(
                ConflictSeverity.CRITICAL,
                "PLAYER_ANIMATION_OVERRIDE",
                "player.json",
                owners.first(),
                owners.drop(1).joinToString(),
                "Varios resource packs reemplazan player.json. Las animaciones de jugador y armas no pueden preservarse automáticamente.",
                blocksExport = true
            )
        }
        return findings.distinctBy { listOf(it.type, it.file, it.source, it.target, it.description) }
    }

    /**
     * Finds an entry point even in common, incorrectly packaged addons. Some
     * creators declare main.js while placing it in scripts/main.js (or script/).
     * The returned value is always the physical path relative to the BP root.
     */
    fun resolveScriptEntry(packRoot: File, declaredEntry: String): String? {
        val entry = declaredEntry.replace('\\', '/').removePrefix("./")
        if (entry.isBlank() || entry.startsWith('/')) return null

        val candidates = linkedSetOf(entry).apply {
            when {
                '/' !in entry -> {
                    add("scripts/$entry")
                    add("script/$entry")
                }
                entry.startsWith("scripts/") -> {
                    add(entry.removePrefix("scripts/"))
                    add("script/${entry.removePrefix("scripts/")}")
                }
                entry.startsWith("script/") -> {
                    add(entry.removePrefix("script/"))
                    add("scripts/${entry.removePrefix("script/")}")
                }
            }
        }
        val rootPath = packRoot.canonicalFile.path + File.separator
        candidates.firstOrNull { candidate ->
            val file = File(packRoot, candidate)
            file.canonicalFile.path.startsWith(rootPath) && file.isFile
        }?.let { return it }

        // Final fallback: only accept a filename match when it is unambiguous.
        val fileName = File(entry).name
        val matches = packRoot.walkTopDown()
            .filter { it.isFile && it.name.equals(fileName, ignoreCase = true) && it.extension.lowercase() in SCRIPT_EXTENSIONS }
            .take(2)
            .toList()
        return matches.singleOrNull()?.relativeTo(packRoot)?.invariantSeparatorsPath
    }

    private fun localModuleExists(packRoot: File, parent: File, importPath: String): Boolean {
        val candidate = File(parent, importPath)
        val root = packRoot.canonicalFile.path + File.separator
        val validLocation = candidate.canonicalFile.path.startsWith(root)
        if (!validLocation) return false
        return candidate.isFile || File("${candidate.path}.js").isFile || File(candidate, "index.js").isFile
    }
}
