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
     * IMPORTANTE: todas las claves y valores String se limpian con trim()
     * para evitar espacios al final (causa de bloques "?" y items "desconocido").
     * 
     * @param base El JSON base (se mantiene si no hay conflicto)
     * @param toMerge El JSON a fusionar (tiene prioridad en colisiones)
     * @return El resultado de la fusión profunda
     */
    fun deepMerge(base: JSONObject, toMerge: JSONObject, isComponents: Boolean = false): JSONObject {
        val result = JSONObject(base.toString())

        // Fusionar claves del merge
        toMerge.keys().forEach { key ->
            val cleanKey = key.trim()
            val baseValue = result.opt(cleanKey)
            val mergeValue = cleanJsonValue(toMerge.get(key))

            when {
                // Si estamos dentro de 'components' o es un componente, usar BedrockComponentMerger
                (isComponents || cleanKey.startsWith("minecraft:")) && baseValue is JSONObject && mergeValue is JSONObject -> {
                    result.put(cleanKey, BedrockComponentMerger.mergeComponents(baseValue, mergeValue))
                }
                
                // Si la clave es "components", marcar recursión especial
                cleanKey == "components" && baseValue is JSONObject && mergeValue is JSONObject -> {
                    result.put(cleanKey, deepMerge(baseValue, mergeValue, true))
                }

                // Ambos son objetos -> fusión recursiva estándar
                baseValue is JSONObject && mergeValue is JSONObject -> {
                    result.put(cleanKey, deepMerge(baseValue, mergeValue, isComponents))
                }

                // Ambos son arrays -> concatenación
                baseValue is JSONArray && mergeValue is JSONArray -> {
                    result.put(cleanKey, concatArrays(baseValue, mergeValue))
                }

                // Colisión de tipos primitivos o tipos diferentes -> sobrescribir con Log
                else -> {
                    if (baseValue != null && mergeValue !is JSONObject && mergeValue !is JSONArray) {
                        // ... (mantener lógica de conflicto)
                        val conflictType = when {
                            cleanKey.contains("item") -> "ITEM_OVERWRITE"
                            cleanKey.contains("entity") -> "ENTITY_OVERWRITE"
                            cleanKey.contains("texture") -> "TEXTURE_OVERWRITE"
                            cleanKey.contains("recipe") -> "RECIPE_OVERWRITE"
                            else -> "PRIMITIVE_OVERWRITE"
                        }
                        
                        // (MANTENER RESTO DE LÓGICA DE CONFLICTO EXISTENTE)
                        val severity = com.packforge.app.domain.model.ConflictSeverity.MEDIUM
                        val conflict = MergeConflict(
                            id = java.util.UUID.randomUUID().toString(),
                            filePath = currentFilePath,
                            conflictType = conflictType,
                            sourceAddon = currentSourceAddon,
                            targetAddon = currentTargetAddon,
                            severity = severity,
                            description = "Colisión de clave '$cleanKey': el valor se sobrescribe.",
                            resolved = false,
                            resolution = null
                        )
                        mergeConflicts.add(conflict)
                        ConflictRegistry.addConflict(conflict)
                        Log.w(TAG, "Colisión de clave primitiva: $cleanKey. Sobrescribiendo valor.")
                    }
                    result.put(cleanKey, mergeValue)
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
            result.put(cleanJsonValue(array1.get(i)))
        }

        // Añadir elementos del segundo array
        for (i in 0 until array2.length()) {
            result.put(cleanJsonValue(array2.get(i)))
        }

        return result
    }

    /**
     * Limpia un valor JSON recursivamente: trims Strings, objetos y arrays.
     * Elimina espacios al final en claves y valores (crítico para Minecraft).
     */
    fun cleanJsonValue(value: Any?): Any? {
        return when (value) {
            is String -> value.trim()
            is JSONObject -> cleanJsonObject(value)
            is JSONArray -> cleanJsonArray(value)
            else -> value
        }
    }

    /**
     * Limpia un JSONObject: trims todas sus claves y valores recursivamente.
     */
    fun cleanJsonObject(obj: JSONObject): JSONObject {
        val cleaned = JSONObject()
        obj.keys().forEach { key ->
            val cleanKey = key.trim()
            val value = obj.get(key)
            val cleanValue = when (value) {
                is String -> value.trim()
                is JSONObject -> cleanJsonObject(value)
                is JSONArray -> cleanJsonArray(value)
                else -> value
            }
            cleaned.put(cleanKey, cleanValue)
        }
        return cleaned
    }

    /**
     * Limpia un JSONArray: trims todos sus elementos recursivamente.
     */
    fun cleanJsonArray(arr: JSONArray): JSONArray {
        val cleaned = JSONArray()
        for (i in 0 until arr.length()) {
            val value = arr.get(i)
            val cleanValue = when (value) {
                is String -> value.trim()
                is JSONObject -> cleanJsonObject(value)
                is JSONArray -> cleanJsonArray(value)
                else -> value
            }
            cleaned.put(cleanValue)
        }
        return cleaned
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
