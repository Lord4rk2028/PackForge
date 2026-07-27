package com.packforge.app.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class Conflict(
    val id: String,
    val type: ConflictType,
    val severity: ConflictSeverity,
    val title: String,
    val description: String,
    val technicalDetail: String,
    val affectedAddonIds: List<String>,
    val affectedFile: String = "",
    val resolution: ConflictResolution = ConflictResolution.UNRESOLVED,
    val winnerAddonId: String? = null,
    val isDismissed: Boolean = false,
    val canBeDismissed: Boolean = true
)


enum class ConflictSeverity(val label: String, val canDismiss: Boolean) {
    CRITICAL("Crítico", false),
    HIGH("Alto", false),
    MEDIUM("Medio", true),
    LOW("Bajo", true),
    WARNING("Advertencia", true)
}

enum class ConflictResolution {
    UNRESOLVED,
    FIRST_WINS,
    SECOND_WINS,
    MERGE,
    DISMISSED
}
