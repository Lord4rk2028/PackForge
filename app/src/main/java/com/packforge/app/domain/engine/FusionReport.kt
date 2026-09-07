package com.packforge.app.domain.engine

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

        // 1. Issues de la fusión
        if (issues.isEmpty()) {
            sb.appendLine("✅ FUSIÓN COMPLETADA SIN ERRORES CRÍTICOS")
            sb.appendLine("   El modpack se generó correctamente.")
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
        val conflicts = ConflictRegistry.conflicts.value
        sb.appendLine("------------------------------------------")
        if (conflicts.isEmpty()) {
            sb.appendLine("ℹ️ CONFLICTOS DE FUSIÓN: 0")
        } else {
            sb.appendLine("ℹ️ CONFLICTOS DE FUSIÓN: ${conflicts.size} (${ConflictRegistry.pendingCount()} pendientes, ${ConflictRegistry.resolvedCount()} resueltos)")
            conflicts.filter { !it.resolved }.forEach { c ->
                sb.appendLine("   • ${c.severity} @ ${c.filePath}")
                sb.appendLine("     ${c.description.ifEmpty { "${c.conflictType}: ${c.sourceAddon} vs ${c.targetAddon}" }}")
            }
        }

        // 3. Pie de página
        sb.appendLine("------------------------------------------")
        sb.appendLine("Generado por PackForge")

        return sb.toString()
    }

    fun clear() { issues.clear() }
}