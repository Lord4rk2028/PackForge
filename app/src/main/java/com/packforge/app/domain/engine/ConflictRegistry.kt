package com.packforge.app.domain.engine

import android.util.Log
import com.packforge.app.domain.model.ConflictSeverity
import com.packforge.app.domain.model.MergeConflict
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Registro centralizado de TODOS los conflictos detectados durante la fusión.
 * Cada punto del pipeline (JsonDeepMerger, fusión de archivos no-JSON,
 * manifest/UUIDs, extractor de addons) registra aquí sus advertencias con su
 * severidad. La pantalla de Conflictos muestra todos ellos.
 * 
 * Única fuente de verdad para conflictos de fusión (MergeConflict).
 * El estado 'resolved' y 'resolution' persisten hasta una nueva fusión/exportación.
 */
object ConflictRegistry {
    private val _conflicts = MutableStateFlow<List<MergeConflict>>(emptyList())
    val conflicts: StateFlow<List<MergeConflict>> = _conflicts.asStateFlow()

    /** Añade un MergeConflict ya construido (con ID generado). */
    fun addConflict(conflict: MergeConflict) {
        _conflicts.value = _conflicts.value + conflict
        Log.i("PackForge_Conflict", "Registrado: ${conflict.severity.name} ${conflict.conflictType} @ ${conflict.filePath}")
    }

    /**
     * Registra un conflicto con todos sus datos y severidad.
     * Uso en todo el pipeline de fusión.
     * Genera un ID único para cada conflicto.
     */
    fun logConflict(
        severity: ConflictSeverity,
        type: String,
        file: String,
        addon1: String,
        addon2: String,
        description: String = ""
    ) {
        addConflict(
            MergeConflict(
                id = UUID.randomUUID().toString(),
                filePath = file,
                conflictType = type,
                sourceAddon = addon1,
                targetAddon = addon2,
                severity = severity,
                description = description,
                resolved = false,
                resolution = null
            )
        )
    }

    /** Resuelve un conflicto marcándolo como resuelto con la resolución dada. */
    fun resolveConflict(id: String, resolution: String) {
        _conflicts.update { list ->
            list.map { conflict ->
                if (conflict.id == id) {
                    Log.d("PackForge_Conflict", "✅ Conflicto $id resuelto como $resolution")
                    conflict.copy(resolved = true, resolution = resolution)
                } else {
                    conflict
                }
            }
        }
    }

    /** Vacía el registro (al iniciar una nueva fusión/exportación o limpiar). */
    fun clear() {
        _conflicts.value = emptyList()
    }

    /** Cantidad total de conflictos registrados. */
    fun size(): Int = _conflicts.value.size
    
    /** Cantidad de conflictos resueltos. */
    fun resolvedCount(): Int = _conflicts.value.count { it.resolved }
    
    /** Cantidad de conflictos pendientes. */
    fun pendingCount(): Int = _conflicts.value.count { !it.resolved }
}