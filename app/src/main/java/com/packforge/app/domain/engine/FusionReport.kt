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

    fun generateReport(): String {
        return issues.joinToString("\n\n") { 
            "[\] Addon: \ | File: \\nTech: \\nUser: \" 
        }
    }
    
    fun clear() { issues.clear() }
}