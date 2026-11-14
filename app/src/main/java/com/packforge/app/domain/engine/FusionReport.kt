package com.packforge.app.domain.engine

import com.packforge.app.domain.model.ConflictSeverity
import com.packforge.app.domain.model.MergeConflict

data class FusionIssue(
    val addonName: String,
    val failedFile: String,
    val technicalReason: String,
    val userReason: String,
    val severity: Severity
) {
    enum class Severity { RECOVERABLE, FATAL }
}

object FusionReportBuilder {
    /** Tope de conflictos distintos volcados en el reporte (agrupados). */
    private const val MAX_CONFLICT_LINES = 80
    private val issues = mutableListOf<FusionIssue>()

    fun addIssue(addonName: String, failedFile: String, tech: String, user: String, sev: FusionIssue.Severity) {
        issues.add(FusionIssue(addonName, failedFile, tech, user, sev))
    }

    fun hasFatal(): Boolean = issues.any { it.severity == FusionIssue.Severity.FATAL }

    /**
     * Genera el reporte de fusión COMPLETO.
     * Incluye:
     *  1. Los issues/errores capturados (si los hay)
     *  2. Un resumen de conflictos registrados en ConflictRegistry
     *  3. Si no hay nada, un mensaje claro de "sin errores"
     * Esto evita que fusion_report.txt salga VACÍO.
     */
    fun generateReport(): String {
        val sb = StringBuilder()
        sb.appendLine("════════════════════════════════════════════")
        sb.appendLine("📦 PACKFORGE - REPORTE DE FUSIÓN")
        sb.appendLine("════════════════════════════════════════════")
        sb.appendLine()

        // 1. Issues de la fusión + VERACIDAD: un "sin errores críticos" es
        //    falso si quedan hallazgos HIGH sin resolver en el ConflictRegistry
        //    (integridad, referencias ZIP, modelos/texturas faltantes...).
        val highPendientes = ConflictRegistry.conflicts.value.count {
            !it.resolved && it.severity == ConflictSeverity.HIGH
        }
        if (issues.isEmpty() && highPendientes == 0) {
            sb.appendLine("✅ FUSIÓN COMPLETADA SIN ERRORES CRÍTICOS")
            sb.appendLine("   El modpack se generó correctamente.")
        } else if (issues.isEmpty()) {
            sb.appendLine("⚠️ FUSIÓN COMPLETADA CON $highPendientes HALLAZGOS CRÍTICOS SIN RESOLVER")
            sb.appendLine("   El .mcaddon se generó, pero hay hallazgos HIGH en el listado de")
            sb.appendLine("   conflictos de abajo y en la sección 6 del reporte.")
        } else {
            sb.appendLine("⚠️ ISSUES DETECTADOS (${issues.size}):")
            sb.appendLine("------------------------------------------")
            issues.forEach { issue ->
                sb.appendLine("[${issue.severity}] Addon: ${issue.addonName} | Archivo: ${issue.failedFile}")
                sb.appendLine("   • Técnico: ${issue.technicalReason}")
                sb.appendLine("   • Usuario: ${issue.userReason}")
                sb.appendLine()
            }
        }

        // 2. Resumen de conflictos
        //
        // ⭐ FASE 4: antes se volcaba UNO POR UNO (`• MEDIUM @ `) y el reporte de
        // Better MC terminaba con 18.379 líneas, enterrando los conflictos reales.
        // Ahora se AGRUPA por (archivo, ruta JSON, tipo, addons): las repeticiones
        // se cuentan en vez de repetirse, y el volcado tiene tope. El total real
        // sigue apareciendo para no maquillar la cifra.
        val conflicts = ConflictRegistry.conflicts.value
        val pendingConflicts = conflicts.filter { !it.resolved }
        sb.appendLine("------------------------------------------")
        if (pendingConflicts.isEmpty()) {
            sb.appendLine("✅ CONFLICTOS DE FUSIÓN: 0")
        } else {
            val grouped = pendingConflicts.groupBy { c ->
                MergeConflict.stableKey(
                    c.filePath, c.jsonPath, c.conflictType, c.sourceAddon, c.targetAddon
                )
            }
            sb.appendLine(
                "⚠️ CONFLICTOS DE FUSIÓN: ${pendingConflicts.size} aviso(s) " +
                    "en ${grouped.size} conflicto(s) distinto(s)"
            )
            val bySeverity = pendingConflicts.groupingBy { it.severity }.eachCount()
            if (bySeverity.isNotEmpty()) {
                sb.appendLine(
                    "   " + bySeverity.entries.sortedByDescending { it.value }
                        .joinToString(" · ") { "${it.value} ${it.key}" }
                )
            }
            if (pendingConflicts.any { it.filePath.isEmpty() }) {
                sb.appendLine(
                    "   ℹ️ ${pendingConflicts.count { it.filePath.isEmpty() }} sin ruta " +
                        "(fusión en memoria sin contexto de archivo)"
                )
            }
            val shown = grouped.entries.take(MAX_CONFLICT_LINES)
            shown.forEach { (_, list) ->
                val c = list.first()
                val where = c.filePath.ifEmpty { "(sin ruta)" }
                val breadcrumb = c.jsonPath.takeIf { it.isNotEmpty() && it != "/" }
                val times = if (list.size > 1) " ×${list.size}" else ""
                sb.appendLine("   • ${c.severity} @ $where$times")
                if (breadcrumb != null) sb.appendLine("     ruta: $breadcrumb")
                sb.appendLine(
                    "     ${c.description.ifEmpty { "${c.conflictType}: ${c.sourceAddon} vs ${c.targetAddon}" }}"
                )
            }
            if (grouped.size > shown.size) {
                sb.appendLine("   ... y ${grouped.size - shown.size} conflicto(s) más")
            }
        }

        // 3. Pie de página
        sb.appendLine("------------------------------------------")
        sb.appendLine("Generado por PackForge")

        return sb.toString()
    }

    fun clear() { issues.clear() }
}