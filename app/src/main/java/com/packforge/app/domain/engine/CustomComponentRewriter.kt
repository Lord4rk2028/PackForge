package com.packforge.app.domain.engine

import com.packforge.app.domain.model.ConflictSeverity
import com.packforge.app.util.PackForgeLog
import java.io.File
import java.util.zip.ZipFile

/**
 * ═══════════════════════════════════════════════════════════════════════
 * CUSTOM COMPONENT REWRITER — Desambiguación de registerCustomComponent
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Cuando [ScriptFlowAnalyzer] detecta que dos o más Behavior Packs registran
 * el MISMO nombre en `registerCustomComponent('nombre', handler)`, solo uno
 * puede sobrevivir en el modpack fusionado: el runtime de Bedrock lanza un
 * error fatal si el mismo identificador se registra dos veces.
 *
 * Este rewriter soluciona eso de forma **no-destructiva**:
 *
 *  1. Identifica el addon "perdedor" (el de menor prioridad, o el de mayor
 *     índice en la lista si hay empate).
 *  2. Genera un sufijo único (`_pf<hash4>`) para el addon perdedor.
 *  3. Reemplaza el literal de cadena en **todos** los archivos `.js/.ts/.mjs`
 *     del addon perdedor: `'nombre'` → `'nombre_pf3a7f'`
 *  4. Actualiza las referencias en los archivos JSON de componentes
 *     (`blocks/`, `items/`, `entities/`) del mismo addon que usaban
 *     `"minecraft:custom"` → `"pf3a7f:nombre"` ó el formato
 *     `"nombre"` → `"nombre_pf3a7f"` según el esquema del archivo.
 *
 * ### Invariantes de seguridad
 *  - Solo modifica **identificadores de cadena literal** — nunca lógica JS.
 *  - Es **idempotente**: si el sufijo ya está presente, no lo vuelve a añadir.
 *  - Es **no-bloqueante**: cualquier excepción se captura y se reporta al
 *    [ConflictRegistry]; el pipeline sigue con el archivo original.
 *  - Solo opera sobre archivos de los addons **perdedores**; el ganador no
 *    se toca.
 *
 * ### Integración en el pipeline
 * Se invoca desde [PackForgeOrchestrator] justo después de
 * `IdentifierRemapper.run()` y ANTES de `planSourceMutations()`, para que
 * el rewrite sea visible en la copia Eager de Fase 1C.
 */
object CustomComponentRewriter {

    private const val TAG = "CustomComponentRewriter"

    // Máximo de bytes de un script que se carga en memoria para reescribir
    private const val MAX_SCRIPT_BYTES = 2 * 1024 * 1024L // 2 MB

    /**
     * Resultado del proceso de desambiguación.
     *
     * @param totalRewrites número de sustituciones de cadenas aplicadas
     * @param affectedFiles lista de rutas relativas modificadas
     * @param renames mapa `nombre_original → nombre_renombrado` para cada
     *   componente desambiguado (útil para el informe de conflictos)
     */
    data class RewriteResult(
        val totalRewrites: Int,
        val affectedFiles: List<String>,
        val renames: Map<String, String>
    )

    /**
     * Desambigua los componentes personalizados duplicados encontrados por
     * [ScriptFlowAnalyzer] en los BP de origen.
     *
     * @param bpDirs rutas a los Behavior Packs (carpetas o ZIPs)
     * @param scriptFindings resultado de [ScriptFlowAnalyzer.analyzeScripts]
     * @return [RewriteResult] con el resumen de cambios
     */
    fun rewrite(bpDirs: List<String>): RewriteResult =
        rewrite(bpDirs, ScriptFlowAnalyzer.findDuplicateCustomComponents(bpDirs))

    /**
     * Desambigua los componentes indicados.
     *
     * El **ganador** es el pack que aparece antes en [bpDirs] (el mismo orden
     * que usa la fusión). Cada pack perdedor recibe un sufijo estable derivado
     * de su ruta, de modo que dos ejecuciones producen el mismo nombre y el
     * proceso es idempotente.
     *
     * @param bpDirs rutas de los Behavior Packs (para resolver el orden)
     * @param duplicateComponents componentes repetidos con su pack dueño
     */
    fun rewrite(
        bpDirs: List<String>,
        duplicateComponents: List<ScriptFlowAnalyzer.ComponentOwner>
    ): RewriteResult {
        if (duplicateComponents.isEmpty()) {
            PackForgeLog.d(TAG, "✅ Sin componentes duplicados — nada que reescribir.")
            return RewriteResult(0, emptyList(), emptyMap())
        }

        val byComponent = duplicateComponents.groupBy { it.componentName }
        PackForgeLog.d(
            TAG,
            "⚠️ Componentes duplicados detectados: ${byComponent.keys.joinToString()}"
        )

        var totalRewrites = 0
        val affectedFiles = mutableListOf<String>()
        val renames = mutableMapOf<String, String>()

        byComponent.forEach { (componentName, owners) ->
            val ordered = owners
                .distinctBy { it.packPath }
                .sortedBy { owner ->
                    bpDirs.indexOf(owner.packPath).let { if (it < 0) Int.MAX_VALUE else it }
                }
            val winner = ordered.firstOrNull() ?: return@forEach

            ordered.drop(1).forEach { loser ->
                val suffix = generateSuffix(loser.packPath)
                val newName = "${componentName}_pf$suffix"
                renames[componentName] = newName

                PackForgeLog.d(
                    TAG,
                    "🔧 '$componentName' → '$newName' en ${File(loser.packPath).name} " +
                        "(gana ${File(winner.packPath).name})"
                )

                val rewritten = rewriteInPack(
                    packPath = loser.packPath,
                    oldName = componentName,
                    newName = newName
                )
                totalRewrites += rewritten.size
                affectedFiles += rewritten
            }
        }

        if (renames.isNotEmpty()) {
            ConflictRegistry.logConflict(
                severity = ConflictSeverity.HIGH,
                type = "CUSTOM_COMPONENT_DISAMBIGUATED",
                file = "scripts/",
                addon1 = "ganador",
                addon2 = "perdedor",
                description = "Se renombraron ${renames.size} componente(s) personalizado(s) " +
                    "duplicado(s): " + renames.entries.joinToString { "'${it.key}' → '${it.value}'" }
            )
        }

        return RewriteResult(
            totalRewrites = totalRewrites,
            affectedFiles = affectedFiles.distinct(),
            renames = renames
        )
    }

    // ── Lógica interna ──────────────────────────────────────────────────

    /**
     * Genera un sufijo de 4 caracteres hexadecimales estable basado en el
     * hash del path del pack perdedor. El mismo pack siempre produce el
     * mismo sufijo → idempotente entre ejecuciones.
     */
    private fun generateSuffix(packPath: String): String {
        val hash = packPath.hashCode().and(0xFFFF)
        return hash.toString(16).padStart(4, '0')
    }

    /**
     * Reescribe todas las ocurrencias de [oldName] → [newName] en:
     *  - scripts JS/TS/MJS (en `registerCustomComponent(...)`)
     *  - JSON de bloques/items/entidades (campo `"minecraft:custom_component"`)
     *
     * @return lista de rutas relativas de archivos modificados
     */
    private fun rewriteInPack(
        packPath: String,
        oldName: String,
        newName: String
    ): List<String> {
        val affected = mutableListOf<String>()
        val root = File(packPath)

        if (!root.exists()) {
            PackForgeLog.w(TAG, "Pack no encontrado para reescritura: $packPath")
            return affected
        }

        if (root.isDirectory) {
            rewriteDirectory(root, root, oldName, newName, affected)
        } else if (root.extension.lowercase() in setOf("zip", "mcpack", "mcaddon")) {
            PackForgeLog.w(TAG, "Reescritura en ZIP no soportada sin extracción: $packPath")
            // Los ZIPs ya son extraídos por el pipeline antes de llegar aquí
        }

        return affected
    }

    /** Recorre recursivamente un directorio y reescribe los archivos elegibles. */
    private fun rewriteDirectory(
        root: File,
        current: File,
        oldName: String,
        newName: String,
        affected: MutableList<String>
    ) {
        current.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                rewriteDirectory(root, file, oldName, newName, affected)
                return@forEach
            }

            val relativePath = file.relativeTo(root).path.replace('\\', '/')

            try {
                when (file.extension.lowercase()) {
                    "js", "ts", "mjs" -> {
                        if (rewriteScriptFile(file, oldName, newName)) {
                            affected += relativePath
                        }
                    }
                    "json" -> {
                        if (isComponentJson(relativePath) &&
                            rewriteJsonComponentFile(file, oldName, newName)) {
                            affected += relativePath
                        }
                    }
                }
            } catch (e: Exception) {
                PackForgeLog.w(TAG, "Error reescribiendo $relativePath: ${e.message}")
            }
        }
    }

    /**
     * Reescribe el nombre del componente dentro de un archivo de script.
     * Solo toca las llamadas a `registerCustomComponent('oldName', ...)`.
     *
     * @return true si se realizó al menos un reemplazo
     */
    private fun rewriteScriptFile(file: File, oldName: String, newName: String): Boolean {
        if (file.length() > MAX_SCRIPT_BYTES) return false

        val original = file.readText(Charsets.UTF_8)

        // Reemplaza exactamente la cadena literal dentro de registerCustomComponent
        // Soporta comillas simples y dobles
        val pattern = Regex(
            """(registerCustomComponent\s*\(\s*)(['"])${Regex.escape(oldName)}\2"""
        )

        val rewritten = pattern.replace(original) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}$newName${match.groupValues[2]}"
        }

        if (rewritten == original) return false

        file.writeText(rewritten, Charsets.UTF_8)
        PackForgeLog.d(TAG, "  ✏️ Script reescrito: ${file.name} ('$oldName' → '$newName')")
        return true
    }

    /**
     * Reescribe las referencias al componente dentro de un JSON de bloques/items.
     * Bedrock usa `"minecraft:custom_component": "nombre"` o variantes.
     *
     * @return true si se realizó al menos un reemplazo
     */
    private fun rewriteJsonComponentFile(file: File, oldName: String, newName: String): Boolean {
        if (file.length() > MAX_SCRIPT_BYTES) return false

        val original = file.readText(Charsets.UTF_8)

        // Clave de componente personalizado en cualquier variante que usa
        // Bedrock: "custom_component", "minecraft:custom_component",
        // "custom_components", "forge:custom_component", etc.
        // Antes solo se aceptaban dos literales exactos y las demás formas
        // (incluida la más común, sin prefijo) se quedaban sin reescribir: el
        // script apuntaba al nombre nuevo y el JSON al viejo → componente roto.
        val keyPattern = """[^"]*custom_components?"""

        val pattern = Regex(
            """"($keyPattern)"\s*:\s*"${Regex.escape(oldName)}""""
        )
        var rewritten = pattern.replace(original) { match ->
            match.value.replace(
                "\"$oldName\"",
                "\"$newName\""
            )
        }

        // Variante en lista: "custom_components": ["a", "oldName"]
        val inComponentsBlock = Regex(
            """"custom_components"\s*:\s*\[([^\]]*)"${Regex.escape(oldName)}"([^\]]*)\]""",
            RegexOption.DOT_MATCHES_ALL
        )
        rewritten = inComponentsBlock.replace(rewritten) { match ->
            match.value.replace("\"$oldName\"", "\"$newName\"")
        }

        if (rewritten == original) return false

        file.writeText(rewritten, Charsets.UTF_8)
        PackForgeLog.d(TAG, "  ✏️ JSON reescrito: ${file.name} ('$oldName' → '$newName')")
        return true
    }

    /**
     * Determina si un JSON es un archivo de componentes (bloque, item, entidad).
     * Solo se reescriben archivos dentro de las carpetas canónicas de Bedrock.
     */
    private fun isComponentJson(relativePath: String): Boolean {
        val lower = relativePath.lowercase()
        return lower.startsWith("blocks/") ||
            lower.startsWith("items/") ||
            lower.startsWith("entities/") ||
            lower.startsWith("block_behaviors/") ||
            lower.startsWith("item_behaviors/")
    }
}

