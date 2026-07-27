package com.packforge.app.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class Addon(
    val id: String,
    val name: String,
    val fileName: String,
    val type: AddonType,
    val version: String,
    val sizeBytes: Long,
    val enabled: Boolean = true,
    val priority: Int = 0,
    val files: List<String> = emptyList(),
    val behaviorFiles: List<String> = emptyList(),
    val resourceFiles: List<String> = emptyList(),
    val entityIdentifiers: List<String> = emptyList(),
    val itemIdentifiers: List<String> = emptyList(),
    val recipeIdentifiers: List<String> = emptyList(),
    val hasScripts: Boolean = false,
    val minEngineVersion: List<Int> = listOf(1, 20, 0),
    val manifestUuid: String = "",
    val rawManifest: String = "",
    val iconPath: String? = null,
    val sourceFilePath: String = "" // Ruta al archivo guardado en almacenamiento interno
)

enum class AddonType(val displayName: String, val description: String) {
    BEHAVIOR_AND_RESOURCE("Behavior + Resource", "Modifica comportamiento y texturas"),
    BEHAVIOR_ONLY("Behavior Pack", "Solo modifica comportamiento del juego"),
    RESOURCE_ONLY("Resource Pack", "Solo modifica texturas y sonidos"),
    UNKNOWN("Desconocido", "Tipo no detectado")
}
