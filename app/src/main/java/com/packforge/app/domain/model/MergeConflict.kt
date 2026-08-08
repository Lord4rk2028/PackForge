package com.packforge.app.domain.model

data class MergeConflict(
    val filePath: String,
    val conflictType: String,
    val sourceAddon: String,
    val targetAddon: String,
    val resolution: String = "UNRESOLVED",
    val severity: ConflictSeverity = ConflictSeverity.MEDIUM,
    val description: String = ""
) {
    companion object {
        const val RESOLUTION_KEEP_SOURCE = "KEEP_SOURCE"
        const val RESOLUTION_KEEP_TARGET = "KEEP_TARGET"
        const val RESOLUTION_MERGE = "MERGE"
    }
}