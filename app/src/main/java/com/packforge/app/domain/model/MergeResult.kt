package com.packforge.app.domain.model

import java.io.File
import com.packforge.app.domain.engine.PackForgeValidator

data class MergeResult(
    val outputFile: File? = null,
    val conflicts: List<String> = emptyList(),
    val errorMessage: String? = null,
    val validationResult: PackForgeValidator.ValidationResult? = null
)

enum class ConflictStrategy {
    KEEP_FIRST,
    KEEP_LAST,
    LOG_AND_SKIP
}
