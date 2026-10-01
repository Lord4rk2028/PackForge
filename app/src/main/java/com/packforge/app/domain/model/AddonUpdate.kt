package com.packforge.app.domain.model

import androidx.compose.runtime.Immutable

/**
 * Estado de búsqueda de actualizaciones de UN addon dentro de un modpack.
 *
 * Flujo de vida:
 *   Idle → Checking → UpToDate | UpdateAvailable | NoMatch
 *                        ↓
 *                    Updating → Updated | Failed
 */
@Immutable
data class AddonUpdateState(
    val addonId: String,
    /** Fase actual de la comprobación. */
    val phase: UpdatePhase = UpdatePhase.Idle,
    /** Versión instalada localmente. */
    val localVersion: String = "",
    /** Versión encontrada en la web (la más alta entre las fuentes). */
    val remoteVersion: String = "",
    /** Fuente desde la que se recomienda actualizar (normalmente el origen real). */
    val recommendedSite: String = "",
    /** URL de la página del addon en la fuente recomendada. */
    val recommendedUrl: String = "",
    /** Todas las fuentes donde se halló una versión superior a la local. */
    val matches: List<SourceMatch> = emptyList(),
    /** Texto de error si algo falló. */
    val error: String? = null
) {
    val hasUpdate: Boolean
        get() = phase == UpdatePhase.UpdateAvailable && remoteVersion.isNotBlank()

    val isRecommendedFromOrigin: Boolean
        get() = recommendedSite.isNotBlank()
}

enum class UpdatePhase {
    /** Nunca se ha comprobado. */
    Idle,

    /** Buscando en las fuentes (spinner). */
    Checking,

    /** Comprobado: la versión web es igual o menor que la local. */
    UpToDate,

    /** Comprobado: existe versión superior. */
    UpdateAvailable,

    /** No se encontró el addon en ninguna fuente. */
    NoMatch,

    /** Descargando y reemplazando el addon en la biblioteca. */
    Updating,

    /** Actualización completada correctamente. */
    Updated,

    /** La actualización falló. */
    Failed
}

/**
 * Una versión concreta de un addon encontrada en una web concreta.
 * Se usa para mostrar las "cajitas" horizontales de fuentes alternativas
 * debajo del addon actualizable.
 */
@Immutable
data class SourceMatch(
    /** Clave del sitio: "MCPEDL" | "CurseForge" | "ModBay". */
    val site: String,
    /** Versión publicada en ese sitio. */
    val version: String,
    /** URL directa de la página del addon en ese sitio. */
    val pageUrl: String,
    /** URL del fichero de descarga (.mcaddon/.mcpack), si se pudo resolver. */
    val downloadUrl: String? = null
)

/**
 * Resultado global de comprobar un modpack completo.
 */
@Immutable
data class ModpackUpdateReport(
    val modpackId: String,
    val states: List<AddonUpdateState>,
    val finished: Boolean = false
) {
    val updateableCount: Int get() = states.count { it.hasUpdate }
    val total: Int get() = states.size
}
