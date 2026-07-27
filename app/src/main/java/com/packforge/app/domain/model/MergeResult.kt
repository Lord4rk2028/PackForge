package com.packforge.app.domain.model

import java.io.File

data class MergeResult(
    val outputFile: File? = null,
    val conflicts: List<String> = emptyList(),
    val errorMessage: String? = null
)

enum class ConflictStrategy {
    KEEP_FIRST,
    KEEP_LAST,
    LOG_AND_SKIP
}
