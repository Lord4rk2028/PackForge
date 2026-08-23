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
            result.put(key, merge.get(key)) // Sobrescribir propiedades internas o fusionar si fuera necesario
        }
        return result
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
