package com.packforge.app.domain.engine

import android.util.Log
import com.packforge.app.domain.model.ConflictSeverity
import com.packforge.app.domain.model.MergeConflict
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Registro centralizado de TODOS los conflictos detectados durante la fusión.
 * Cada punto del pipeline (JsonDeepMerger, fusión de archivos no-JSON,
 * manifest/UUIDs, extractor de addons) registra aquí sus advertencias con su
 * severidad. La pantalla de Conflictos muestra todos ellos.
 */
object ConflictRegistry {
    private val _conflicts = MutableStateFlow<List<MergeConflict>>(emptyList())
    val conflicts: StateFlow<List<MergeConflict>> = _conflicts.asStateFlow()

    /** Añade un MergeConflict ya construido. */
    fun addConflict(conflict: MergeConflict) {
        _conflicts.value = _conflicts.value + conflict
        Log.i("PackForge_Conflict", "Registrado: ${conflict.severity.name} ${conflict.conflictType} @ ${conflict.filePath}")
    }

    /**
     * Registra un conflicto con todos sus datos y severidad.
     * Uso en todo el pipeline de fusión.
     */
    fun logConflict(
        severity: ConflictSeverity,
        type: String,
        file: String,
        addon1: String,
        addon2: String,
        resolution: String = MergeConflict.RESOLUTION_KEEP_SOURCE,
        description: String = ""
    ) {
        addConflict(
            MergeConflict(
                filePath = file,
                conflictType = type,
                sourceAddon = addon1,
                targetAddon = addon2,
                resolution = resolution,
                severity = severity,
                description = description
            )
        )
    }

    /** Vacía el registro (al iniciar una nueva fusión/exportación o limpiar). */
    fun clear() {
        _conflicts.value = emptyList()
    }

    /** Cantidad total de conflictos registrados. */
    fun size(): Int = _conflicts.value.size
}