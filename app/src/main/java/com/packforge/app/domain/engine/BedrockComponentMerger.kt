package com.packforge.app.domain.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * Fusiona semánticamente componentes de Bedrock (minecraft:*) en lugar de sobrescribirlos.
 */
object BedrockComponentMerger {

    fun mergeComponents(base: JSONObject, merge: JSONObject): JSONObject {
        val result = JSONObject(base.toString()) // Clonar base

        merge.keys().forEach { componentName ->
            if (componentName.startsWith("minecraft:")) {
                // Fusión especial para componentes conocidos
                if (result.has(componentName)) {
                    val baseComp = result.get(componentName)
                    val mergeComp = merge.get(componentName)
                    
                    if (baseComp is JSONObject && mergeComp is JSONObject) {
                        result.put(componentName, mergeComponentObjects(baseComp, mergeComp))
                    } else if (baseComp is JSONArray && mergeComp is JSONArray) {
                        result.put(componentName, mergeComponentArrays(baseComp, mergeComp))
                    } else {
                        // Caso desconocido o simple: sobrescribir
                        result.put(componentName, mergeComp)
                    }
                } else {
                    result.put(componentName, merge.get(componentName))
                }
            } else {
                // No es componente de minecraft, tratar como campo normal
                result.put(componentName, merge.get(componentName))
            }
        }
        return result
    }

    private fun mergeComponentObjects(base: JSONObject, merge: JSONObject): JSONObject {
        val result = JSONObject(base.toString())
        merge.keys().forEach { key ->
            val baseVal = result.opt(key)
            val mergeVal = merge.get(key)
            result.put(key, deepMergeValues(baseVal, mergeVal))
        }
        return result
    }

    /**
     * Deep merge recursivo de dos valores JSON.
     * - JSONObject + JSONObject  → fusiona recursivamente (ambos sobreviven)
     * - JSONArray   + JSONArray   → combina evitando duplicados
     * - Cualquier otra combinación → el valor de [merge] gana (sobreescritura)
     */
    private fun deepMergeValues(base: Any?, merge: Any?): Any? {
        if (base == null) return merge
        if (merge == null) return base
        return when {
            base is JSONObject && merge is JSONObject -> mergeComponentObjects(base, merge)
            base is JSONArray && merge is JSONArray -> mergeComponentArrays(base, merge)
            else -> merge
        }
    }

    private fun mergeComponentArrays(base: JSONArray, merge: JSONArray): JSONArray {
        // Para arrays de componentes (ej: behavior.move), concatenar o combinar
        val result = JSONArray()
        for (i in 0 until base.length()) result.put(base.get(i))
        for (i in 0 until merge.length()) {
            // Evitar duplicados simples
            val item = merge.get(i)
            var found = false
            for (j in 0 until base.length()) {
                if (base.get(j).toString() == item.toString()) {
                    found = true
                    break
                }
            }
            if (!found) result.put(item)
        }
        return result
    }
}
