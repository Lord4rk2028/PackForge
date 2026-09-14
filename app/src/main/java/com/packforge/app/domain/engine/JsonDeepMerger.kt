package com.packforge.app.domain.engine

import android.util.Log
import com.packforge.app.util.PackForgeLog
import com.packforge.app.domain.model.MergeConflict
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

object JsonDeepMerger {
    private const val TAG = "PackForge"
    private const val CONFLICT_TAG = "PackForge_Conflict"
    
    /**
     * Conflictos detectados durante la fusión.
     *
     * IMPORTANTE: el orquestador ejecuta las fases de fusión en PARALELO
     * (`async` sobre Dispatchers.IO), por lo que varios hilos añaden aquí a la vez.
     * Debe ser una colección concurrente: con un `mutableListOf` plano el
     * `add()` concurrente corrompe la lista (ConcurrentModificationException)
     * o pierde conflictos silenciosamente, y un conflicto perdido implica
     * dos addons con el mismo ID en el .mcaddon final -> Minecraft crashea.
     *
     * ConcurrentLinkedQueue: `add` es lock-free y el iterador es weak-consistent,
     * así que iterar mientras otro hilo escribe es seguro (a diferencia de
     * Collections.synchronizedList, que exige sincronizar el forEach a mano).
     */
    val mergeConflicts: ConcurrentLinkedQueue<MergeConflict> = ConcurrentLinkedQueue()
    
    /**
     * Contexto de la fusión en curso (qué addon es el origen, cuál el destino y
     * qué archivo se está fusionando).
     *
     * Se guarda en ThreadLocal y NO en campos normales: el orquestador fusiona
     * varios addons en paralelo, así que dos hilos escribían estos mismos campos
     * a la vez. El resultado era que un conflicto terminaba atribuido al addon
     * equivocado (o con el archivo de otro hilo), haciendo inútil el reporte.
     * Con ThreadLocal cada hilo ve su propio contexto.
     */
    private val currentSourceAddon = ThreadLocal.withInitial { "" }
    private val currentTargetAddon = ThreadLocal.withInitial { "" }
    private val currentFilePath = ThreadLocal.withInitial { "" }

    private val prioritySourceIndex = ThreadLocal.withInitial { Int.MAX_VALUE }
    private val priorityWinnerIndex = ThreadLocal.withInitial { Int.MAX_VALUE }

    fun setMergeContext(sourceAddon: String, targetAddon: String, filePath: String) {
        currentSourceAddon.set(sourceAddon)
        currentTargetAddon.set(targetAddon)
        currentFilePath.set(filePath)
    }

    fun setPriorityContext(sourceIndex: Int, winnerIndex: Int) {
        prioritySourceIndex.set(sourceIndex)
        priorityWinnerIndex.set(winnerIndex)
    }

    fun clearPriorityContext() {
        prioritySourceIndex.set(Int.MAX_VALUE)
        priorityWinnerIndex.set(Int.MAX_VALUE)
    }

    fun clearConflicts() {
        mergeConflicts.clear()
        ConflictRegistry.clear()
        clearPriorityContext()
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

        toMerge.keys().forEach { key ->
            val cleanKey = key.sanitizeKey()
            val baseValue = result.opt(cleanKey)
            val mergeValue = cleanJsonValue(toMerge.get(key))

            when {
                (isComponents || cleanKey.startsWith("minecraft:")) && baseValue is JSONObject && mergeValue is JSONObject -> {
                    result.put(cleanKey, BedrockComponentMerger.mergeComponents(baseValue, mergeValue))
                }
                
                cleanKey == "components" && baseValue is JSONObject && mergeValue is JSONObject -> {
                    result.put(cleanKey, deepMerge(baseValue, mergeValue, true))
                }

                baseValue is JSONObject && mergeValue is JSONObject -> {
                    result.put(cleanKey, deepMerge(baseValue, mergeValue, isComponents))
                }

                baseValue is JSONArray && mergeValue is JSONArray -> {
                    result.put(cleanKey, concatArrays(baseValue, mergeValue))
                }

                else -> {
                    val winnerIdx = priorityWinnerIndex.get()
                    val sourceHasLowerPriority = prioritySourceIndex.get() > winnerIdx && winnerIdx != Int.MAX_VALUE
                    if (baseValue != null && mergeValue !is JSONObject && mergeValue !is JSONArray) {
                        val conflictType = when {
                            cleanKey.contains("item") -> "ITEM_OVERWRITE"
                            cleanKey.contains("entity") -> "ENTITY_OVERWRITE"
                            cleanKey.contains("texture") -> "TEXTURE_OVERWRITE"
                            cleanKey.contains("recipe") -> "RECIPE_OVERWRITE"
                            else -> "PRIMITIVE_OVERWRITE"
                        }

                        val conflict = MergeConflict(
                            id = java.util.UUID.randomUUID().toString(),
                            filePath = currentFilePath.get(),
                            conflictType = conflictType,
                            sourceAddon = currentSourceAddon.get(),
                            targetAddon = currentTargetAddon.get(),
                            severity = com.packforge.app.domain.model.ConflictSeverity.MEDIUM,
                            description = if (sourceHasLowerPriority)
                                "Colisión de clave '$cleanKey': ganador (índice $winnerIdx) prevaleció"
                            else
                                "Colisión de clave '$cleanKey': el valor se sobrescribe.",
                            resolved = false,
                            resolution = null
                        )
                        mergeConflicts.add(conflict)
                        ConflictRegistry.addConflict(conflict)
                    }
                    if (!sourceHasLowerPriority) {
                        result.put(cleanKey, mergeValue)
                    }
                }
            }
        }

        return result
    }

    /**
     * Fusiona contra el buffer si existe, luego persiste. Esta es la API optimizada en RAM.
     */
    fun mergeIntoBuffer(path: String, newJson: JSONObject, sourceAddon: String, targetAddon: String, isComponents: Boolean = false) {
        setMergeContext(sourceAddon, targetAddon, path)
        val existing = MemoryMergeBuffer.get(path)
        val finalJson = if (existing != null) {
            deepMerge(existing, newJson, isComponents)
        } else {
            newJson
        }
        MemoryMergeBuffer.put(path, finalJson)
        clearPriorityContext()
    }

    /**
     * Vuelca todos los JSONs en el buffer al directorio destino.
     */
    fun flushBufferToDisk(targetDir: File) {
        if (!targetDir.exists()) targetDir.mkdirs()
        val buffer = MemoryMergeBuffer.getAll()
        for ((path, json) in buffer) {
            val outFile = File(targetDir, path)
            outFile.parentFile?.mkdirs()
            outFile.writeText(json.toString())
        }
    }

    private fun concatArrays(array1: JSONArray, array2: JSONArray): JSONArray {
        val result = JSONArray()
        for (i in 0 until array1.length()) {
            result.put(cleanJsonValue(array1.get(i)))
        }
        for (i in 0 until array2.length()) {
            result.put(cleanJsonValue(array2.get(i)))
        }
        return result
    }

    // Limpieza estricta: elimina invisibles Unicode (ZWSP \u200B, BOM, etc.) + control + espacios
    private fun String.sanitize(): String = this.replace(Regex("[\\p{C}\\p{Z}]"), "").trim()
    private fun String.sanitizeKey(): String = this.replace(Regex("[\\p{C}\\p{Z}]"), "").trim()

    fun cleanJsonValue(value: Any?): Any? {
        return when (value) {
            is String -> value.sanitize()
            is JSONObject -> cleanJsonObject(value)
            is JSONArray -> cleanJsonArray(value)
            else -> value
        }
    }

    fun cleanJsonObject(obj: JSONObject): JSONObject {
        val cleaned = JSONObject()
        obj.keys().forEach { key ->
            val cleanKey = key.sanitizeKey()
            val value = obj.get(key)
            val cleanValue = when (value) {
                is String -> value.sanitize()
                is JSONObject -> cleanJsonObject(value)
                is JSONArray -> cleanJsonArray(value)
                else -> value
            }
            cleaned.put(cleanKey, cleanValue)
        }
        return cleaned
    }

    fun cleanJsonArray(arr: JSONArray): JSONArray {
        val cleaned = JSONArray()
        for (i in 0 until arr.length()) {
            val value = arr.get(i)
            val cleanValue = when (value) {
                is String -> value.sanitize()
                is JSONObject -> cleanJsonObject(value)
                is JSONArray -> cleanJsonArray(value)
                else -> value
            }
            cleaned.put(cleanValue)
        }
        return cleaned
    }

    fun checkNamespaceCollision(key: String) {
        if (key.contains(":")) {
            ConflictRegistry.logConflict(
                severity = com.packforge.app.domain.model.ConflictSeverity.MEDIUM,
                type = "NAMESPACE_COLLISION",
                file = currentFilePath.get(),
                addon1 = currentSourceAddon.get(),
                addon2 = currentTargetAddon.get(),
                description = "Identificador de namespace '$key' sobrescrito por el segundo addon."
            )
        }
    }

    fun deepMergeStrings(baseJson: String, mergeJson: String): String {
        return try {
            val baseObj = JSONObject(baseJson)
            val mergeObj = JSONObject(mergeJson)
            deepMerge(baseObj, mergeObj).toString(4)
        } catch (e: Exception) {
            baseJson
        }
    }

    fun strictJsonValidate(jsonText: String): Boolean {
        try {
            val json = JSONObject(jsonText)
            val text = json.toString()
            val badPatterns = listOf(", }", ", ]", "}{")
            for (pattern in badPatterns) {
                if (text.contains(pattern)) {
                    PackForgeLog.e("PackForge_JSON", "Patron JSON sospechoso detectado: $pattern")
                    return false
                }
            }
            val openBraces = text.count { it == '{' }
            val closeBraces = text.count { it == '}' }
            if (openBraces != closeBraces) {
                PackForgeLog.e("PackForge_JSON", "Brackets desbalanceados: $openBraces open, $closeBraces close")
                return false
            }
            val openBrackets = text.count { it == '[' }
            val closeBrackets = text.count { it == ']' }
            if (openBrackets != closeBrackets) {
                PackForgeLog.e("PackForge_JSON", "Corchetes desbalanceados: $openBrackets open, $closeBrackets close")
                return false
            }
            return true
        } catch (e: org.json.JSONException) {
            PackForgeLog.e("PackForge_JSON", "Error de sintaxis JSON: ${e.message}")
            return false
        } catch (e: Exception) {
            PackForgeLog.e("PackForge_JSON", "Error inesperado en validacion: ${e.message}")
            return false
        }
    }
}
