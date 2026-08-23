package com.packforge.app.domain.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * ═══════════════════════════════════════════════════════════════════════
 * GENERADOR DE REPORTE DE FUSIÓN (Validación Post-Fusión)
 * ═══════════════════════════════════════════════════════════════════════
 *
 * 1. validateJsonSyntax: recorre TODO el pack fusionado parseando cada .json;
 *    cualquier archivo inválido (coma faltante, tipo incorrecto tras un
 *    reemplazo) se reporta con ruta y mensaje legible.
 * 2. build/writeToFile: consolida renombres de IDs, aliases de recursos,
 *    renombres de sonido, hallazgos de scripts y validación en
 *    `fusion_report.txt` junto al .mcaddon final.
 */
object MergeReportGenerator {

    private const val TAG = "PackForge_Report"

    /** Valida sintaxis JSON de todo el directorio. @return líneas de error (vacío = OK). */
    fun validateJsonSyntax(root: File): List<String> {
        val errors = mutableListOf<String>()
        if (!root.isDirectory) return errors
        root.walkTopDown().filter { it.isFile && it.extension.equals("json", true) }.forEach { file ->
            try {
                JSONObject(file.readText(StandardCharsets.UTF_8))
            } catch (first: Exception) {
                // Segundo intento tolerante: algunos archivos legacy usan BOM/NaN.
                try {
                    val cleaned = file.readText(StandardCharsets.UTF_8).trimStart('\uFEFF')
                    JSONObject(cleaned)
                } catch (second: Exception) {
                    errors += "❌ JSON inválido: ${file.relativeTo(root).path} (${second.message ?: "sintaxis"})"
                }
            }
        }
        if (errors.isNotEmpty()) {
            com.packforge.app.util.PackForgeLog.w(TAG, "Validación JSON: ${errors.size} archivo(s) inválido(s)")
        }
        return errors
    }

    data class Inputs(
        val idRenames: List<IdentifierRemapper.RemapEntry> = emptyList(),
        val resourceAliases: List<String> = emptyList(),
        val soundRenames: Map<String, String> = emptyMap(),
        val scriptFindings: List<String> = emptyList(),
        val syntaxErrors: List<String> = emptyList(),
        val validationResult: PackForgeValidator.ValidationResult? = null,
        val totalJsonsMerged: Int = 0,
        val elapsedSeconds: Double = 0.0
    )

    fun build(inputs: Inputs): List<String> {
        val lines = mutableListOf<String>()
        lines += "══════════════ PACKFORGE · REPORTE DE FUSIÓN INTELIGENTE ══════════════"
        lines += "JSONs procesados: ${inputs.totalJsonsMerged}   |   Tiempo total: ${"%.1f".format(inputs.elapsedSeconds)}s"
        lines += ""

        lines += "── 1. RESOLUCIÓN DE IDENTIFICADORES ──"
        if (inputs.idRenames.isEmpty()) {
            lines += "✅ Sin colisiones de IDs entre addons."
        } else {
            inputs.idRenames.forEach { r ->
                lines += "🔁 Renombrado ID: ${r.oldId} → ${r.newId} (${r.file})"
            }
        }
        lines += ""

        lines += "── 2. RECURSOS (texturas / sonidos / partículas) ──"
        if (inputs.resourceAliases.isEmpty() && inputs.soundRenames.isEmpty()) {
            lines += "✅ Sin colisiones de rutas de recursos."
        } else {
            inputs.resourceAliases.forEach { lines += "🖼️ Recurso con alias: $it" }
            inputs.soundRenames.forEach { (old, new) -> lines += "🔊 Sonido renombrado: $old → $new" }
        }
        lines += ""

        lines += "── 3. SCRIPTS ──"
        if (inputs.scriptFindings.isEmpty()) lines += "✅ Sin colisiones detectadas en scripts/."
        else inputs.scriptFindings.forEach { lines += it }
        lines += ""

        lines += "── 4. VALIDACIÓN POST-FUSIÓN ──"
        if (inputs.syntaxErrors.isEmpty()) lines += "✅ Todos los JSON del paquete son sintácticamente válidos."
        else inputs.syntaxErrors.forEach { lines += it }
        inputs.validationResult?.let { vr ->
            lines += "Texturas faltantes: ${vr.missingTextures.size} | Modelos faltantes: ${vr.missingModels.size} " +
                "| Referencias reparadas: ${vr.fixedReferences} | Claves .lang añadidas: ${vr.langKeysAdded.values.sum()}"
            vr.warnings.take(10).forEach { lines += "⚠️ $it" }
        }
        lines += "═══════════════════════════════════════════════════════════════════════"
        return lines
    }

    fun writeToFile(lines: List<String>, outFile: File): Boolean {
        return try {
            outFile.parentFile?.mkdirs()
            OutputStreamWriter(FileOutputStream(outFile), StandardCharsets.UTF_8).use { writer ->
                lines.forEach { writer.write(it); writer.write("\n") }
            }
            com.packforge.app.util.PackForgeLog.d(TAG, "📄 Reporte escrito: ${outFile.absolutePath}")
            true
        } catch (e: Exception) {
            com.packforge.app.util.PackForgeLog.e(TAG, "No se pudo escribir el reporte: ${e.message}")
            false
        }
    }
}
