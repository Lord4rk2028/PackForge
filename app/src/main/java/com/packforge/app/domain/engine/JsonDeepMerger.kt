package com.packforge.app.domain.engine

import android.util.Log
import com.packforge.app.domain.model.MergeConflict
import org.json.JSONArray
import org.json.JSONObject

object JsonDeepMerger {
    private const val TAG = "PackForge"
    private const val CONFLICT_TAG = "PackForge_Conflict"
    
    // Shared list to store all merge conflicts
    val mergeConflicts = mutableListOf<MergeConflict>()
    
    // Current addon names for conflict tracking
    private var currentSourceAddon = ""
    private var currentTargetAddon = ""
    private var currentFilePath = ""
    
    fun setMergeContext(sourceAddon: String, targetAddon: String, filePath: String) {
        currentSourceAddon = sourceAddon
        currentTargetAddon = targetAddon
        currentFilePath = filePath
    }
    
    fun clearConflicts() {
        mergeConflicts.clear()
        ConflictRegistry.clear()
    }

    /**
     * Realiza una fusión profunda (Deep Merge) de dos objetos JSON.
     * 
     * @param base El JSON base (se mantiene si no hay conflicto)
     * @param toMerge El JSON a fusionar (tiene prioridad en colisiones)
     * @return El resultado de la fusión profunda
     */
    fun deepMerge(base: JSONObject, toMerge: JSONObject): JSONObject {
        val result = JSONObject(base.toString())
        
        toMerge.keys().forEach { key ->
            val baseValue = result.opt(key)
            val mergeValue = toMerge.get(key)
            
            when {
                // Ambos son objetos -> fusión recursiva
                baseValue is JSONObject && mergeValue is JSONObject -> {
                    result.put(key, deepMerge(baseValue, mergeValue))
                }
                
                // Ambos son arrays -> concatenación
                baseValue is JSONArray && mergeValue is JSONArray -> {
                    result.put(key, concatArrays(baseValue, mergeValue))
                }
                
                // Colisión de tipos primitivos o tipos diferentes -> sobrescribir con Log
                else -> {
                    if (baseValue != null && mergeValue !is JSONObject && mergeValue !is JSONArray) {
                        // Determine conflict type based on key
                        val conflictType = when {
                            key.contains("item") -> "ITEM_OVERWRITE"
                            key.contains("entity") -> "ENTITY_OVERWRITE"
                            key.contains("texture") -> "TEXTURE_OVERWRITE"
                            key.contains("recipe") -> "RECIPE_OVERWRITE"
                            else -> "PRIMITIVE_OVERWRITE"
                        }

                        // Severidad según el tipo de dato sobrescrito
                        val severity = when (conflictType) {
                            "ENTITY_OVERWRITE" -> com.packforge.app.domain.model.ConflictSeverity.HIGH
                            "ITEM_OVERWRITE" -> com.packforge.app.domain.model.ConflictSeverity.HIGH
                            "TEXTURE_OVERWRITE" -> com.packforge.app.domain.model.ConflictSeverity.MEDIUM
                            "RECIPE_OVERWRITE" -> com.packforge.app.domain.model.ConflictSeverity.MEDIUM
                            else -> com.packforge.app.domain.model.ConflictSeverity.MEDIUM
                        }

                        val conflict = MergeConflict(
                            filePath = currentFilePath,
                            conflictType = conflictType,
                            sourceAddon = currentSourceAddon,
                            targetAddon = currentTargetAddon,
                            resolution = MergeConflict.RESOLUTION_KEEP_SOURCE,
                            severity = severity,
                            description = "Colisión de clave '$key': el valor se sobrescribe."
                        )
                        mergeConflicts.add(conflict)
                        // Registrar TODO en el registro central (aunque la clave ya exista)
                        ConflictRegistry.addConflict(conflict)

                        Log.w(TAG, "Colisión de clave primitiva: $key. Sobrescribiendo valor.")
                        Log.w(CONFLICT_TAG, "🔴 ${severity.name} Conflicto detectado: $conflict")
                    }
                    result.put(key, mergeValue)
                }
            }
        }
        
        return result
    }

    /**
     * Concatena dos JSONArrays.
     * 
     * @param array1 Primer array
     * @param array2 Segundo array (se añade al final)
     * @return Nuevo array con los elementos concatenados
     */
    private fun concatArrays(array1: JSONArray, array2: JSONArray): JSONArray {
        val result = JSONArray()
        
        // Añadir elementos del primer array
        for (i in 0 until array1.length()) {
            result.put(array1.get(i))
        }
        
        // Añadir elementos del segundo array
        for (i in 0 until array2.length()) {
            result.put(array2.get(i))
        }
        
        return result
    }

    /**
     * Detecta si una clave parece ser un identificador de namespace de Minecraft
     * (ej. "my_mod:custom_sword") y registra un warning si hay colisión.
     * 
     * @param key La clave a verificar
     */
    fun checkNamespaceCollision(key: String) {
        if (key.contains(":")) {
            Log.w(TAG, "Colisión de identificador (namespace): $key. El segundo addon sobrescribe al primero.")
            ConflictRegistry.logConflict(
                severity = com.packforge.app.domain.model.ConflictSeverity.MEDIUM,
                type = "NAMESPACE_COLLISION",
                file = currentFilePath,
                addon1 = currentSourceAddon,
                addon2 = currentTargetAddon,
                description = "Identificador de namespace '$key' sobrescrito por el segundo addon."
            )
        }
    }

    /**
     * Realiza una fusión profunda de dos strings JSON.
     * 
     * @param baseJson String JSON base
     * @param mergeJson String JSON a fusionar
     * @return String JSON resultante de la fusión
     */
    fun deepMergeStrings(baseJson: String, mergeJson: String): String {
        return try {
            val baseObj = JSONObject(baseJson)
            val mergeObj = JSONObject(mergeJson)
            deepMerge(baseObj, mergeObj).toString(4)
        } catch (e: Exception) {
            Log.e(TAG, "Error al fusionar JSON: ${e.message}", e)
            baseJson // Retornar el original si hay error
        }
    }
}
