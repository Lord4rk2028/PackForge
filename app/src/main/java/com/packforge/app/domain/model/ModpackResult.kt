package com.packforge.app.domain.model

data class ModpackResult(
    val success: Boolean,
    val addonCount: Int,
    val unresolvedConflicts: Int,
    val criticalUnresolved: Int,
    val outputFileName: String
)
