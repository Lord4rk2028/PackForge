package com.packforge.app.domain.engine

import com.packforge.app.domain.model.ConflictSeverity
import java.io.File

/**
 * ═══════════════════════════════════════════════════════════════════════
 * SCRIPT FLOW ANALYZER — compatibilidad de scripts entre Behavior Packs
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Complementa a [ScriptCollisionAnalyzer] (que trabaja sobre la carpeta YA
 * fusionada) evaluando los packs de ORIGEN antes de fusionar:
 *
 *  1. Entry point inexistente o vacío → bloqueo (el script no arrancaría).
 *  2. Importaciones relativas que no existen en el pack → advertencia.
 *  3. Ciclos de importación entre addons → bloqueo (bucle en tiempo de carga).
 *  4. Varios packs aportando el MISMO entry point → advertencia (colisión al
 *     fusionar; el pipeline renombra, pero conviene informarlo).
 *  5. Propiedades dinámicas compartidas → advertencia (estado compartido).
 *  6. Registro duplicado del mismo componente personalizado → advertencia
 *     (el bloqueo real lo emite [BedrockCompatibilityAnalyzer]).
 *
 * El resultado es una [ScriptGateDecision] que la UI usa para avisar antes de
 * exportar. Solo los bloqueos CRITICAL impiden exportar.
 */
object ScriptFlowAnalyzer {

    /** Resultado del análisis de un único script. */
    data class ScriptAnalysisResult(
        val scriptId: String,
        val isValid: Boolean,
        val blockers: List<ScriptBlocker>,
        val warnings: List<ScriptWarning>,
        val compatibilityScore: Double
    )

    // ── Patrones de análisis sobre el código fuente ──────────────────────
    // Cubre las tres formas habituales de importar en Bedrock/ESM:
    //   import { x } from './m.js'  |  import './m.js'  |  require('./m.js')
    private val IMPORT_REGEX = Regex(
        """(?:from\s*|import\s*\(\s*|require\s*\(\s*|import\s*)['"]([^'"\n]+)['"]"""
    )
    private val DYNAMIC_PROPERTY_REGEX = Regex(
        """(?:set|get)DynamicProperty\s*\(\s*['"]([^'"]+)['"]"""
    )
    private val CUSTOM_COMPONENT_REGEX = Regex(
        """registerCustomComponent\s*\(\s*['"]([^'"]+)['"]"""
    )
    private val BEFORE_EVENTS_REGEX = Regex(
        """world\.beforeEvents\.([a-zA-Z0-9_$]+)\.subscribe"""
    )
    private val GLOBAL_THIS_REGEX = Regex(
        """globalThis\.([a-zA-Z0-9_$]+)\s*="""
    )

    // ── API pública ──────────────────────────────────────────────────────

    /**
     * Analiza todos los scripts y devuelve los hallazgos agregados.
     *
     * @param bpDirs rutas de Behavior Packs (carpetas o ZIPs)
     * @param rpDirs rutas de Resource Packs (no contienen scripts; se acepta
     *   por simetría con [ExportCompatibilityGate])
     */
    fun analyzeScripts(
        bpDirs: List<String>,
        rpDirs: List<String> = emptyList()
    ): ScriptFindings {
        val scripts = ScriptRegistry.loadScriptsFromBPs(bpDirs, rpDirs)
        if (scripts.isEmpty()) {
            return ScriptFindings(
                totalScripts = 0,
                validScripts = 0,
                invalidScripts = 0,
                totalBlockers = 0,
                totalWarnings = 0,
                riskLevel = ScriptRiskLevel.SAFE,
                averageCompatibilityScore = 1.0,
                byScript = emptyMap()
            )
        }

        val analysis = performScriptFlowAnalysis(scripts)

        val blockers = analysis.values.sumOf { it.blockers.size }
        val warnings = analysis.values.sumOf { it.warnings.size }
        val valid = analysis.values.count { it.isValid }

        return ScriptFindings(
            totalScripts = analysis.size,
            validScripts = valid,
            invalidScripts = analysis.size - valid,
            totalBlockers = blockers,
            totalWarnings = warnings,
            riskLevel = riskLevelOf(analysis.values.flatMap { it.blockers }),
            averageCompatibilityScore = averageScore(analysis.values),
            byScript = analysis.mapValues { (_, result) -> decisionFor(result) }
        )
    }

    /**
     * Decisión para la puerta de exportación. Es la que consume la UI.
     */
    fun getGateDecision(
        bpDirs: List<String> = emptyList(),
        rpDirs: List<String> = emptyList()
    ): ScriptGateDecision {
        val scripts = ScriptRegistry.loadScriptsFromBPs(bpDirs, rpDirs)
        if (scripts.isEmpty()) return ScriptGateDecision.safe()

        val analysis = performScriptFlowAnalysis(scripts)
        val allBlockers = analysis.values.flatMap { it.blockers }
        val allWarnings = analysis.values.flatMap { it.warnings }
        val avgScore = averageScore(analysis.values) * 100.0

        val action = when {
            allBlockers.any { it.conflictType == ScriptConflictType.ENTRY_INVALID } ->
                RecommendedAction.FIX_ISSUES
            allBlockers.any { it.conflictType == ScriptConflictType.IMPORT_CYCLE } ->
                RecommendedAction.FIX_ISSUES
            allBlockers.isNotEmpty() -> RecommendedAction.FIX_ISSUES
            else -> RecommendedAction.EXECUTE
        }

        return ScriptGateDecision(
            canExecute = allBlockers.none { it.severity == ConflictSeverity.CRITICAL },
            riskLevel = riskLevelOf(allBlockers),
            compatibilityScore = avgScore / 100.0,
            blockers = allBlockers,
            warnings = allWarnings,
            summary = buildSummary(allBlockers.size, allWarnings.size, avgScore, scripts.size),
            overallScore = avgScore,
            recommendedAction = action
        )
    }

    // ── Análisis ─────────────────────────────────────────────────────────

    private fun performScriptFlowAnalysis(
        scripts: Map<String, ScriptRegistry.RegisteredScript>
    ): Map<String, ScriptAnalysisResult> {
        // Entry points repetidos entre packs distintos.
        val entryOwners = scripts.values
            .groupBy { it.entryPath }
            .filterValues { group -> group.map { it.packPath }.distinct().size > 1 }

        val results = LinkedHashMap<String, ScriptAnalysisResult>()

        scripts.forEach { (scriptId, script) ->
            val blockers = mutableListOf<ScriptBlocker>()
            val warnings = mutableListOf<ScriptWarning>()

            checkEntryPoint(script, blockers, warnings)
            checkModuleDependencies(script, blockers, warnings)
            checkRelativeImports(script, warnings)
            checkDuplicateEntry(script, entryOwners, warnings)

            val sharedProps = sharedDynamicProperties(scripts)
            sharedProps[scriptId]?.let { props ->
                props.forEach { prop ->
                    warnings += ScriptWarning(
                        scriptId = scriptId,
                        conflictType = ScriptConflictType.DYNAMIC_PROPERTY,
                        severity = ConflictSeverity.LOW,
                        description = "Propiedad dinámica '$prop' compartida con otros scripts",
                        suggestion = "Verifica que el estado compartido no cause comportamientos contradictorios"
                    )
                }
            }

            val sharedComponents = sharedCustomComponents(scripts)
            sharedComponents[scriptId]?.let { components ->
                components.forEach { component ->
                    blockers += ScriptBlocker(
                        scriptId = scriptId,
                        conflictType = ScriptConflictType.CUSTOM_COMPONENT,
                        severity = ConflictSeverity.CRITICAL,
                        description = "Componente personalizado '$component' registrado por varios addons. Bedrock rechaza duplicados.",
                        suggestion = "Ambos addons registran el mismo componente. Renómbralo o desactiva uno de los addons."
                    )
                }
            }

            val sharedEvents = sharedBeforeEvents(scripts)
            sharedEvents[scriptId]?.let { events ->
                events.forEach { ev ->
                    warnings += ScriptWarning(
                        scriptId = scriptId,
                        conflictType = ScriptConflictType.BEFORE_EVENTS_CONFLICT,
                        severity = ConflictSeverity.WARNING,
                        description = "El addon intercepta 'world.beforeEvents.$ev', compartido con otros addons",
                        suggestion = "Si un addon cancela el evento (event.cancel = true), bloqueará las acciones de los otros addons."
                    )
                }
            }

            val sharedGlobals = sharedGlobalThis(scripts)
            sharedGlobals[scriptId]?.let { props ->
                props.forEach { prop ->
                    warnings += ScriptWarning(
                        scriptId = scriptId,
                        conflictType = ScriptConflictType.GLOBAL_POLLUTION,
                        severity = ConflictSeverity.LOW,
                        description = "Propiedad global 'globalThis.$prop' definida por más de un addon",
                        suggestion = "Verifica que ambos addons no sobreescriban datos incompatibles en el ámbito global."
                    )
                }
            }

            results[scriptId] = ScriptAnalysisResult(
                scriptId = scriptId,
                isValid = blockers.isEmpty(),
                blockers = blockers,
                warnings = warnings,
                compatibilityScore = scoreOf(blockers.size, warnings.size)
            )
        }

        // Ciclos de importación (necesitan el grafo completo).
        detectImportCycles(scripts).forEach { cycle ->
            val first = cycle.firstOrNull() ?: return@forEach
            val existing = results[first] ?: return@forEach
            val blocker = ScriptBlocker(
                scriptId = first,
                conflictType = ScriptConflictType.IMPORT_CYCLE,
                severity = ConflictSeverity.CRITICAL,
                description = "Ciclo de importación detectado: ${cycle.joinToString(" → ")}",
                suggestion = "Rompe el ciclo extrayendo el código común a un módulo aparte",
                affectedScript = cycle.getOrNull(1)
            )
            results[first] = existing.copy(
                isValid = false,
                blockers = existing.blockers + blocker,
                compatibilityScore = scoreOf(
                    existing.blockers.size + 1,
                    existing.warnings.size
                )
            )
        }

        // Detección de incompatibilidades de rama (Beta vs Estable) de módulos @minecraft/*
        checkTrackMismatches(scripts, results)

        // Hallazgos del analizador de colisiones sobre packs que son carpetas.
        collectCollisionFindings(scripts).forEach { (scriptId, message) ->
            val existing = results[scriptId] ?: return@forEach
            val warning = ScriptWarning(
                scriptId = scriptId,
                conflictType = ScriptConflictType.EXECUTION_ORDER,
                severity = ConflictSeverity.LOW,
                description = message,
                suggestion = "Revisa las suscripciones a eventos y las declaraciones globales del pack"
            )
            results[scriptId] = existing.copy(
                warnings = existing.warnings + warning,
                compatibilityScore = scoreOf(existing.blockers.size, existing.warnings.size + 1)
            )
        }

        return results
    }

    /** 1. El entry point debe existir y tener contenido. */
    private fun checkEntryPoint(
        script: ScriptRegistry.RegisteredScript,
        blockers: MutableList<ScriptBlocker>,
        warnings: MutableList<ScriptWarning>
    ) {
        val file = script.entryFile
        val exists = file?.isFile == true || script.sourceText.isNotEmpty()
        if (!exists) {
            blockers += ScriptBlocker(
                scriptId = script.scriptId,
                conflictType = ScriptConflictType.ENTRY_INVALID,
                severity = ConflictSeverity.CRITICAL,
                description = "El entry point '${script.entryPath}' no existe en el pack",
                suggestion = "Corrige la ruta 'entry' del módulo script en manifest.json",
                affectedScript = null
            )
            return
        }

        if (script.sourceText.isBlank()) {
            warnings += ScriptWarning(
                scriptId = script.scriptId,
                conflictType = ScriptConflictType.ENTRY_INVALID,
                severity = ConflictSeverity.WARNING,
                description = "El entry point '${script.entryPath}' está vacío",
                suggestion = "Comprueba que el script tenga contenido antes de exportar"
            )
        }
    }

    /** 1b. Valida dependencias del módulo declaradas en manifest.json. */
    private fun checkModuleDependencies(
        script: ScriptRegistry.RegisteredScript,
        blockers: MutableList<ScriptBlocker>,
        warnings: MutableList<ScriptWarning>
    ) {
        script.metadata.moduleDependencies.forEach { dep ->
            if (dep.isServerOnly) {
                blockers += ScriptBlocker(
                    scriptId = script.scriptId,
                    conflictType = ScriptConflictType.SERVER_ONLY_MODULE,
                    severity = ConflictSeverity.CRITICAL,
                    description = "El módulo '${dep.name}' solo funciona en Servidor Dedicado (BDS); causa error fatal en Minecraft Android.",
                    suggestion = "Elimina esta dependencia del manifest o utiliza APIs de cliente permitidas.",
                    affectedScript = null
                )
            }
            if (dep.isLegacy) {
                blockers += ScriptBlocker(
                    scriptId = script.scriptId,
                    conflictType = ScriptConflictType.LEGACY_SCRIPT_API,
                    severity = ConflictSeverity.CRITICAL,
                    description = "El módulo '${dep.name}' es una API obsoleta que ya no existe en Bedrock moderno (1.20+).",
                    suggestion = "Actualiza el addon para usar el módulo moderno @minecraft/server.",
                    affectedScript = null
                )
            }
        }
    }

    /** Helper para iterar sobre todos los textos de scripts del pack. */
    private fun getAllScriptTexts(script: ScriptRegistry.RegisteredScript): Sequence<Pair<String, String>> {
        return if (script.allScriptSources.isNotEmpty()) {
            script.allScriptSources.asSequence().map { (path, text) -> path to text }
        } else {
            sequenceOf(script.entryPath to script.sourceText)
        }
    }

    /** 2. Las importaciones relativas deben apuntar a un archivo del pack. */
    private fun checkRelativeImports(
        script: ScriptRegistry.RegisteredScript,
        warnings: MutableList<ScriptWarning>
    ) {
        val missing = mutableSetOf<String>()
        getAllScriptTexts(script).forEach { (filePath, text) ->
            IMPORT_REGEX.findAll(text)
                .map { it.groupValues[1] }
                .filter { it.startsWith(".") }
                .filter { specifier -> !resolvesToPackFile(script, filePath, specifier) }
                .forEach { missing.add(it) }
        }

        missing.forEach { specifier ->
            warnings += ScriptWarning(
                scriptId = script.scriptId,
                conflictType = ScriptConflictType.DEPENDENCY_MISSING,
                severity = ConflictSeverity.WARNING,
                description = "Importación no encontrada: '$specifier'",
                suggestion = "Verifica la ruta relativa de la importación o incluye el archivo faltante"
            )
        }
    }

    /** 3. Varios packs con el mismo entry point tienden a colisionar al fusionar. */
    private fun checkDuplicateEntry(
        script: ScriptRegistry.RegisteredScript,
        entryOwners: Map<String, List<ScriptRegistry.RegisteredScript>>,
        warnings: MutableList<ScriptWarning>
    ) {
        val owners = entryOwners[script.entryPath] ?: return
        val others = owners.filter { it.packPath != script.packPath }
        if (others.isEmpty()) return

        warnings += ScriptWarning(
            scriptId = script.scriptId,
            conflictType = ScriptConflictType.IDENTIFIER_COLLISION,
            severity = ConflictSeverity.LOW,
            description = "El entry point '${script.entryPath}' también lo aporta otro addon",
            suggestion = "La fusión los separa en módulos distintos, pero revisa que no dupliquen lógica"
        )
    }

    /**
     * Resuelve una importación relativa contra los archivos del pack.
     * Acepta la ruta tal cual y con extensiones implícitas de Node/ESM.
     */
    private fun resolvesToPackFile(
        script: ScriptRegistry.RegisteredScript,
        sourceFilePath: String,
        specifier: String
    ): Boolean {
        val baseDir = sourceFilePath.substringBeforeLast('/', "")
        val joined = if (baseDir.isEmpty()) specifier else "$baseDir/$specifier"
        val normalized = normalizePath(joined)

        val candidates = buildList {
            add(normalized)
            add("$normalized.js")
            add("$normalized.ts")
            add("$normalized.mjs")
            add("$normalized/index.js")
        }
        return candidates.any { it in script.allScripts }
    }

    /** Normaliza `.` y `..` en una ruta relativa con separador `/`. */
    private fun normalizePath(path: String): String {
        val parts = ArrayDeque<String>()
        path.replace('\\', '/').split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(segment)
            }
        }
        return parts.joinToString("/")
    }

    // ── Análisis entre scripts ───────────────────────────────────────────

    /** scriptId → propiedades dinámicas que comparte con OTROS packs. */
    private fun sharedDynamicProperties(
        scripts: Map<String, ScriptRegistry.RegisteredScript>
    ): Map<String, List<String>> {
        val owners = mutableMapOf<String, MutableList<ScriptRegistry.RegisteredScript>>()
        scripts.values.forEach { script ->
            getAllScriptTexts(script).flatMap { (_, text) ->
                DYNAMIC_PROPERTY_REGEX.findAll(text).map { it.groupValues[1] }
            }.distinct().forEach { prop -> owners.getOrPut(prop) { mutableListOf() }.add(script) }
        }
        return sharedBetweenPacks(owners)
    }

    /** scriptId → componentes personalizados registrados por varios packs. */
    private fun sharedCustomComponents(
        scripts: Map<String, ScriptRegistry.RegisteredScript>
    ): Map<String, List<String>> {
        val owners = mutableMapOf<String, MutableList<ScriptRegistry.RegisteredScript>>()
        scripts.values.forEach { script ->
            getAllScriptTexts(script).flatMap { (_, text) ->
                CUSTOM_COMPONENT_REGEX.findAll(text).map { it.groupValues[1] }
            }.distinct().forEach { component ->
                owners.getOrPut(component) { mutableListOf() }.add(script)
            }
        }
        return sharedBetweenPacks(owners)
    }

    /** scriptId → eventos de cancelación/modificación antes de tiempo que varios packs escuchan. */
    private fun sharedBeforeEvents(
        scripts: Map<String, ScriptRegistry.RegisteredScript>
    ): Map<String, List<String>> {
        val owners = mutableMapOf<String, MutableList<ScriptRegistry.RegisteredScript>>()
        scripts.values.forEach { script ->
            getAllScriptTexts(script).flatMap { (_, text) ->
                BEFORE_EVENTS_REGEX.findAll(text).map { it.groupValues[1] }
            }.distinct().forEach { ev ->
                owners.getOrPut(ev) { mutableListOf() }.add(script)
            }
        }
        return sharedBetweenPacks(owners)
    }

    /** scriptId → asignaciones a globalThis que chocan entre distintos packs. */
    private fun sharedGlobalThis(
        scripts: Map<String, ScriptRegistry.RegisteredScript>
    ): Map<String, List<String>> {
        val owners = mutableMapOf<String, MutableList<ScriptRegistry.RegisteredScript>>()
        scripts.values.forEach { script ->
            getAllScriptTexts(script).flatMap { (_, text) ->
                GLOBAL_THIS_REGEX.findAll(text).map { it.groupValues[1] }
            }.distinct().forEach { prop ->
                owners.getOrPut(prop) { mutableListOf() }.add(script)
            }
        }
        return sharedBetweenPacks(owners)
    }

    /** Comprueba si hay mezcla incompatible de rama Beta y Estable de @minecraft/server. */
    private fun checkTrackMismatches(
        scripts: Map<String, ScriptRegistry.RegisteredScript>,
        results: MutableMap<String, ScriptAnalysisResult>
    ) {
        val modulesByTrack = mutableMapOf<String, MutableMap<String, MutableList<String>>>()
        scripts.values.forEach { script ->
            script.metadata.moduleDependencies.forEach { dep ->
                if (dep.name.startsWith("@minecraft/")) {
                    val track = if (dep.isBeta) "beta" else "stable"
                    modulesByTrack.getOrPut(dep.name) { mutableMapOf() }
                        .getOrPut(track) { mutableListOf() }
                        .add(script.scriptId)
                }
            }
        }

        modulesByTrack.forEach { (moduleName, tracks) ->
            if (tracks.containsKey("beta") && tracks.containsKey("stable")) {
                val betaScripts = tracks["beta"].orEmpty()
                val stableScripts = tracks["stable"].orEmpty()
                (betaScripts + stableScripts).distinct().forEach { id ->
                    val existing = results[id] ?: return@forEach
                    val blocker = ScriptBlocker(
                        scriptId = id,
                        conflictType = ScriptConflictType.API_TRACK_MISMATCH,
                        severity = ConflictSeverity.CRITICAL,
                        description = "Conflicto de versiones en '$moduleName': se detectó mezcla de rama beta y estable entre addons.",
                        suggestion = "Bedrock no permite mezclar versiones beta y estables del mismo módulo. Alinea todos los addons a la misma versión."
                    )
                    results[id] = existing.copy(
                        isValid = false,
                        blockers = existing.blockers + blocker,
                        compatibilityScore = scoreOf(existing.blockers.size + 1, existing.warnings.size)
                    )
                }
            }
        }
    }

    private fun sharedBetweenPacks(
        owners: Map<String, MutableList<ScriptRegistry.RegisteredScript>>
    ): Map<String, List<String>> {
        val result = mutableMapOf<String, MutableList<String>>()
        owners.forEach { (key, scripts) ->
            if (scripts.map { it.packPath }.distinct().size < 2) return@forEach
            scripts.forEach { script ->
                val list = result.getOrPut(script.scriptId) { mutableListOf() }
                if (key !in list) list += key
            }
        }
        return result
    }

    /**
     * Detecta ciclos en el grafo de importaciones **entre entry points**
     * registrados (los ciclos internos de un pack los cubre el bundler).
     */
    private fun detectImportCycles(
        scripts: Map<String, ScriptRegistry.RegisteredScript>
    ): List<List<String>> {
        val byEntry = scripts.values.associateBy { it.entryPath }

        val edges: Map<String, List<String>> = scripts.mapValues { (_, script) ->
            IMPORT_REGEX.findAll(script.sourceText)
                .map { it.groupValues[1] }
                .filter { it.startsWith(".") }
                .map { specifier ->
                    val baseDir = script.entryPath.substringBeforeLast('/', "")
                    val joined = if (baseDir.isEmpty()) specifier else "$baseDir/$specifier"
                    normalizePath(joined)
                }
                .mapNotNull { target ->
                    byEntry[target]?.scriptId
                        ?: byEntry["$target.js"]?.scriptId
                        ?: byEntry["$target/index.js"]?.scriptId
                }
                .distinct()
                .toList()
        }

        val cycles = mutableListOf<List<String>>()
        val state = mutableMapOf<String, Int>() // 0 = sin visitar, 1 = en pila, 2 = hecho
        val stack = mutableListOf<String>()

        fun visit(node: String) {
            state[node] = 1
            stack += node
            edges[node].orEmpty().forEach { next ->
                when (state[next]) {
                    1 -> {
                        val start = stack.indexOf(next)
                        if (start >= 0) cycles += stack.drop(start) + next
                    }
                    0, null -> visit(next)
                }
            }
            stack.removeAt(stack.lastIndex)
            state[node] = 2
        }

        edges.keys.forEach { if (state[it] == null) visit(it) }
        return cycles.distinctBy { cycle -> cycle.sorted().joinToString("|") }
    }

    /** Reutiliza [ScriptCollisionAnalyzer] cuando el pack es una carpeta real. */
    private fun collectCollisionFindings(
        scripts: Map<String, ScriptRegistry.RegisteredScript>
    ): List<Pair<String, String>> {
        val findings = mutableListOf<Pair<String, String>>()

        scripts.values
            .distinctBy { it.packPath }
            .forEach { script ->
                val root = File(script.packPath)
                if (!root.isDirectory) return@forEach
                val messages = try {
                    ScriptCollisionAnalyzer.analyze(root)
                } catch (_: Exception) {
                    emptyList()
                }
                messages.forEach { message -> findings += script.scriptId to message }
            }

        return findings
    }

    // ── Utilidades de agregación ─────────────────────────────────────────

    private fun scoreOf(blockerCount: Int, warningCount: Int): Double =
        (1.0 - blockerCount * 0.5 - warningCount * 0.15).coerceIn(0.0, 1.0)

    private fun averageScore(results: Collection<ScriptAnalysisResult>): Double =
        if (results.isEmpty()) 1.0 else results.sumOf { it.compatibilityScore } / results.size

    private fun riskLevelOf(blockers: List<ScriptBlocker>): ScriptRiskLevel = when {
        blockers.any { it.severity == ConflictSeverity.CRITICAL } -> ScriptRiskLevel.BLOCKED
        blockers.isNotEmpty() -> ScriptRiskLevel.WARNING
        else -> ScriptRiskLevel.SAFE
    }

    private fun decisionFor(result: ScriptAnalysisResult): ScriptGateDecision {
        val score = result.compatibilityScore
        return ScriptGateDecision(
            canExecute = result.blockers.none { it.severity == ConflictSeverity.CRITICAL },
            riskLevel = riskLevelOf(result.blockers),
            compatibilityScore = score,
            blockers = result.blockers,
            warnings = result.warnings,
            summary = buildSummary(
                result.blockers.size,
                result.warnings.size,
                score * 100.0,
                1
            ),
            overallScore = score * 100.0,
            recommendedAction = if (result.blockers.isEmpty()) {
                RecommendedAction.EXECUTE
            } else {
                RecommendedAction.FIX_ISSUES
            }
        )
    }

    private fun buildSummary(
        blockerCount: Int,
        warningCount: Int,
        score: Double,
        scriptCount: Int
    ): String = when {
        blockerCount == 0 && warningCount == 0 ->
            "Compatibilidad de scripts óptima ($scriptCount script(s), ${score.toInt()}/100)"
        blockerCount > 0 && warningCount == 0 ->
            "$blockerCount bloqueo(s) de script y ${score.toInt()}/100"
        blockerCount == 0 ->
            "$warningCount advertencia(s) de script y ${score.toInt()}/100"
        else ->
            "$blockerCount bloqueo(s) y $warningCount advertencia(s) de script (${score.toInt()}/100)"
    }

    /** Etiqueta legible de una lista de bloqueos, para mostrar en la UI. */
    fun describeBlockers(blockers: List<ScriptBlocker>, limit: Int = 4): List<String> =
        blockers.take(limit).map { blocker ->
            "${blocker.conflictType.description}: ${blocker.description}"
        }

    /** Pack que registra un componente personalizado concreto. */
    data class ComponentOwner(
        val componentName: String,
        val scriptId: String,
        val packPath: String
    )

    /**
     * Componentes personalizados registrados por MÁS DE UN pack.
     *
     * Es la entrada de [CustomComponentRewriter]: en Bedrock registrar dos veces
     * el mismo `registerCustomComponent('nombre', …)` es un error fatal, así que
     * hay que desambiguar antes de fusionar.
     */
    fun findDuplicateCustomComponents(
        bpDirs: List<String>,
        rpDirs: List<String> = emptyList()
    ): List<ComponentOwner> {
        val scripts = ScriptRegistry.loadScriptsFromBPs(bpDirs, rpDirs)
        if (scripts.isEmpty()) return emptyList()

        val owners = mutableMapOf<String, MutableList<ComponentOwner>>()
        scripts.values.forEach { script ->
            CUSTOM_COMPONENT_REGEX.findAll(script.sourceText)
                .map { it.groupValues[1] }
                .distinct()
                .forEach { name ->
                    owners.getOrPut(name) { mutableListOf() }
                        .add(ComponentOwner(name, script.scriptId, script.packPath))
                }
        }

        return owners
            .filterValues { list -> list.map { it.packPath }.distinct().size > 1 }
            .values
            .flatten()
            .sortedWith(compareBy({ it.componentName }, { it.packPath }))
    }
}
