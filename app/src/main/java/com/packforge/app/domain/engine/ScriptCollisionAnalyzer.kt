package com.packforge.app.domain.engine

import java.io.File
import java.util.Locale

/**
 * ═══════════════════════════════════════════════════════════════════════
 * ANALIZADOR DE COLISIONES DE SCRIPTS (Script API @minecraft/server)
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Cada addon conserva su propio archivo de entrada importado como MÓDULO ES
 * (ver mergeScripts), por lo que los scopes ya están aislados y NO puede
 * ocurrir "Variable already defined" entre addons.
 *
 * Este analizador detecta los riesgos REALES restantes:
 *  1. Declaraciones globales duplicadas DENTRO del mismo bundle concatenado
 *     (main.js combinado) — sí provocaría error de redeclaración.
 *  2. Suscripciones masivas al MISMO evento del mundo por varios addons
 *     (orden de ejecución no determinista → comportamiento contradictorio).
 *
 * No bloquea la exportación; emite hallazgos para el reporte.
 */
object ScriptCollisionAnalyzer {

    private val TOP_LEVEL_DECL = Regex(
        """^\s*(?:export\s+)?(?:const|let|var|function|class)\s+([A-Za-z_$][\w$]*)""",
        RegexOption.MULTILINE
    )
    private val EVENT_SUBSCRIPTION = Regex(
        """world\.(?:afterEvents|beforeEvents)\.(\w+)"""
    )

    /**
     * Analiza la carpeta scripts/ fusionada.
     * @return lista de hallazgos legibles (una línea por hallazgo).
     */
    fun analyze(scriptsDir: File?): List<String> {
        val findings = mutableListOf<String>()
        if (scriptsDir == null || !scriptsDir.isDirectory) return findings

        val jsFiles = scriptsDir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase(Locale.ROOT) in SCRIPT_EXTENSIONS }
            .toList()

        // 1) Duplicados de declaraciones top-level dentro del MISMO archivo
        jsFiles.forEach { file ->
            val text = try { file.readText() } catch (_: Exception) { return@forEach }
            val seen = mutableMapOf<String, Int>()
            TOP_LEVEL_DECL.findAll(text).forEach { m ->
                val name = m.groupValues[1]
                seen[name] = (seen[name] ?: 0) + 1
            }
            seen.filterValues { it > 1 }.forEach { (name, count) ->
                if (!isLikelyScopedRepeat(name, count, text)) {
                    findings += "⚠️ Scripts: '$name' declarado $count veces en ${file.relativeTo(scriptsDir).path} — riesgo 'already defined'"
                }
            }
        }

        // 2) Mapa evento → nº de archivos que se suscriben a él
        val eventFiles = linkedMapOf<String, MutableSet<String>>()
        jsFiles.forEach { file ->
            val text = try { file.readText() } catch (_: Exception) { return@forEach }
            EVENT_SUBSCRIPTION.findAll(text).map { it.groupValues[1] }.distinct().forEach { ev ->
                eventFiles.getOrPut(ev) { mutableSetOf() }.add(file.name)
            }
        }
        eventFiles.filterValues { it.size >= 3 }.forEach { (ev, files) ->
            findings += "ℹ️ Scripts: ${files.size} addons escuchan world.*.${ev} — verifica que no alteren el mismo estado"
        }

        return findings
    }

    /** Evita falsos positivos de nombres repetidos dentro de funciones anidadas distintas. */
    private fun isLikelyScopedRepeat(name: String, count: Int, text: String): Boolean {
        val decls = Regex("""(?:const|let|var|function|class)\s+${Regex.escape(name)}\b""")
            .findAll(text).toList()
        if (decls.size < count) return true
        // Heurística: si alguna declaración está indentada (>0 espacios), es scope local.
        return decls.any { it.value.startsWith(" ") || it.value.startsWith("\t") }
    }
}
