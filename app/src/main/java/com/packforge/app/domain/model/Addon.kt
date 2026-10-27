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
    val lootTableIdentifiers: List<String> = emptyList(),
    val particleIdentifiers: List<String> = emptyList(),
    val materialInstanceIdentifiers: List<String> = emptyList(),
    val soundDefinitionIdentifiers: List<String> = emptyList(),
    val animationControllerIdentifiers: List<String> = emptyList(),
    val hasPlayerJson: Boolean = false,
    val hasScripts: Boolean = false,
    val minEngineVersion: List<Int> = listOf(1, 20, 0),
    val manifestUuid: String = "",
    val rawManifest: String = "",
    val iconPath: String? = null,
    val sourceFilePath: String = "", // Ruta al archivo guardado en almacenamiento interno
    val sourceUrl: String? = null,   // URL de la página del addon (origen web, si se importó desde WebView interno)
    val sourceSite: String? = null,  // "MCPEDL" | "CurseForge" | "ModBay" (origen)
    val author: String? = null       // Autor/creador del addon (leído del manifest.json)
)

/**
 * Devuelve una copia con los campos no-nulos garantizados no nulos.
 *
 * Gson no respeta ni la nulabilidad ni los valores por defecto de Kotlin: al
 * deserializar un `addonsJson` guardado antes de que existiera un campo nuevo,
 * ese campo queda en `null` aunque su tipo declare `List<String>` o `String`.
 * Iterar o usar esos campos revienta con `NullPointerException` y cerraría la
 * app al abrir/editar un modpack (p. ej. ConflictEngine.detectLootTableConflicts).
 *
 * Se aplica en todos los puntos donde se deserializan addons desde JSON.
 */
@Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
fun Addon.withSafeLists(): Addon = copy(
    name = name ?: "",
    fileName = fileName ?: "",
    version = version ?: "",
    files = files ?: emptyList(),
    behaviorFiles = behaviorFiles ?: emptyList(),
    resourceFiles = resourceFiles ?: emptyList(),
    entityIdentifiers = entityIdentifiers ?: emptyList(),
    itemIdentifiers = itemIdentifiers ?: emptyList(),
    recipeIdentifiers = recipeIdentifiers ?: emptyList(),
    lootTableIdentifiers = lootTableIdentifiers ?: emptyList(),
    particleIdentifiers = particleIdentifiers ?: emptyList(),
    materialInstanceIdentifiers = materialInstanceIdentifiers ?: emptyList(),
    soundDefinitionIdentifiers = soundDefinitionIdentifiers ?: emptyList(),
    animationControllerIdentifiers = animationControllerIdentifiers ?: emptyList(),
    minEngineVersion = minEngineVersion ?: listOf(1, 20, 0),
    manifestUuid = manifestUuid ?: "",
    rawManifest = rawManifest ?: "",
    sourceFilePath = sourceFilePath ?: ""
)

/** Aplica [withSafeLists] a toda la lista. */
fun List<Addon>.withSafeLists(): List<Addon> = map { it.withSafeLists() }

enum class AddonType(val displayName: String, val description: String) {
    BEHAVIOR_AND_RESOURCE("Behavior + Resource", "Modifica comportamiento y texturas"),
    BEHAVIOR_ONLY("Behavior Pack", "Solo modifica comportamiento del juego"),
    RESOURCE_ONLY("Resource Pack", "Solo modifica texturas y sonidos"),
    UNKNOWN("Desconocido", "Tipo no detectado")
}
