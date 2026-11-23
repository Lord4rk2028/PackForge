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
    private val prioritySourceIndex: ThreadLocal<Int> = ThreadLocal.withInitial { Int.MAX_VALUE }
    private val priorityWinnerIndex: ThreadLocal<Int> = ThreadLocal.withInitial { Int.MAX_VALUE }
    private val currentSourceAddon: ThreadLocal<String> = ThreadLocal.withInitial { "" }
    private val currentTargetAddon: ThreadLocal<String> = ThreadLocal.withInitial { "" }
    private val currentFilePath: ThreadLocal<String> = ThreadLocal.withInitial { "" }
    private val currentJsonPath: ThreadLocal<String> = ThreadLocal.withInitial { "" }

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
    fun deepMerge(base: JSONObject, toMerge: JSONObject, isComponents: Boolean = false, filePath: String = "", jsonPath: String = ""): JSONObject {
        val prevFile = currentFilePath.get()
        val prevJson = currentJsonPath.get()
        if (filePath.isNotEmpty()) currentFilePath.set(filePath)
        if (jsonPath.isNotEmpty()) currentJsonPath.set(jsonPath)
        try {
            return deepMergeInternal(base, toMerge, isComponents)
        } finally {
            currentFilePath.set(prevFile)
            currentJsonPath.set(prevJson)
        }
    }

    private fun deepMergeInternal(base: JSONObject, toMerge: JSONObject, isComponents: Boolean = false): JSONObject {
        // ⚡ ATAJO: sin claves en común no hay nada que resolver recursivamente,
        // pero SÍ hay que aportar las claves nuevas del segundo JSON.
        // Antes se devolvía `base` tal cual, y eso descartaba en silencio todo
        // lo que solo traía el segundo addon (p. ej. un componente
        // `minecraft:durability` dentro de "components") — contenido perdido.
        var hasOverlap = false
        val mergeKeys = toMerge.keys()
        while (mergeKeys.hasNext() && !hasOverlap) {
            if (base.has(mergeKeys.next().sanitizeKey())) hasOverlap = true
        }
        if (!hasOverlap) {
            val union = JSONObject(base.toString())
            toMerge.keys().forEach { key ->
                val cleanKey = key.sanitizeKey()
                if (!union.has(cleanKey)) {
                    union.put(cleanKey, cleanJsonValue(toMerge.get(key)))
                }
            }
            return union
        }

        val result = JSONObject(base.toString())

        toMerge.keys().forEach { key ->
            val cleanKey = key.sanitizeKey()
            val baseValue = result.opt(cleanKey)
            val mergeValue = cleanJsonValue(toMerge.get(key))

            when {
                // ⚠️ SÓLO se aplica la fusión semántica de componentes cuando YA estamos
                // dentro de un objeto "components" (isComponents = true). Antes se activaba
                // para CUALQUIER clave raíz que empezara por "minecraft:" (p.ej. el propio
                // "minecraft:item", "minecraft:entity", "minecraft:client_entity"), lo que
                // enviaba el contenedor raíz a BedrockComponentMerger: éste trata
                // "description"/"components" como campos planos y SOBRESCRIBÍA el objeto
                // "components" completo del addon base, perdiendo sus componentes
                // (max_stack_size, behaviors, etc.) al fusionar dos addons con el mismo
                // archivo. Ahora los contenedores raíz caen en la rama recursiva normal.
                isComponents && cleanKey.startsWith("minecraft:") && baseValue is JSONObject && mergeValue is JSONObject -> {
                    result.put(cleanKey, BedrockComponentMerger.mergeComponents(baseValue, mergeValue))
                }
                
                cleanKey == "components" && baseValue is JSONObject && mergeValue is JSONObject -> {
                    // ⭐ FASE 4: se propaga el breadcrumb para que los conflictos
                    // anidados digan su ruta completa (/minecraft:entity/components/…).
                    val prevPath = currentJsonPath.get()
                    currentJsonPath.set("${jsonPathActual}$cleanKey/")
                    try {
                        result.put(cleanKey, deepMergeInternal(baseValue, mergeValue, true))
                    } finally {
                        currentJsonPath.set(prevPath)
                    }
                }

                baseValue is JSONObject && mergeValue is JSONObject -> {
                    val prevPath = currentJsonPath.get()
                    currentJsonPath.set("${jsonPathActual}$cleanKey/")
                    try {
                        result.put(cleanKey, deepMergeInternal(baseValue, mergeValue, isComponents))
                    } finally {
                        currentJsonPath.set(prevPath)
                    }
                }

                baseValue is JSONArray && mergeValue is JSONArray -> {
                    // Unión por igualdad estructural (concatArrays): en animaciones,
                    // render controllers y attachables los arrays son POSICIONALES
                    // (bones[], arrays.textures[], materials). Concatenar a ciegas
                    // duplicaba entradas y desplazaba los índices, y el juego usaba
                    // el hueso o la textura EQUIVOCADA.
                    result.put(cleanKey, concatArrays(baseValue, mergeValue))
                }

                else -> {
                    val winnerIdx = priorityWinnerIndex.get() ?: Int.MAX_VALUE
                    val sourceHasLowerPriority = (prioritySourceIndex.get() ?: Int.MAX_VALUE) > winnerIdx && winnerIdx != Int.MAX_VALUE

                    // ⭐ FASE 4: si el valor entrante es IDÉNTICO al que ya estaba,
                    // no hay nada que resolver. Antes se registraba un conflicto
                    // igualmente, y eso fue la causa de 17.313 de los 18.379
                    // "conflictos" del reporte real de Better MC (94,2% de ruido):
                    // dos addons que declaran el mismo format_version o el mismo
                    // `identifier` no colisionan, coinciden.
                    val identical = baseValue != null && canonicalOf(baseValue) == canonicalOf(mergeValue)
                    if (identical) {
                        if (!sourceHasLowerPriority) result.put(cleanKey, mergeValue)
                        return@forEach
                    }

                    if (baseValue != null && mergeValue !is JSONObject && mergeValue !is JSONArray) {
                        val conflictType = when {
                            // ⭐ FASE 4: distinción semántica. Sobrescribir un
                            // `identifier` rompe la entidad/bloque; cambiar una
                            // versión de formato es rutina.
                            cleanKey == "identifier" || cleanKey.endsWith("/identifier") ->
                                "IDENTIFIER_OVERWRITE"
                            cleanKey == "format_version" -> "VERSION_OVERWRITE"
                            cleanKey.contains("item") -> "ITEM_OVERWRITE"
                            cleanKey.contains("entity") -> "ENTITY_OVERWRITE"
                            cleanKey.contains("texture") -> "TEXTURE_OVERWRITE"
                            cleanKey.contains("recipe") -> "RECIPE_OVERWRITE"
                            else -> "PRIMITIVE_OVERWRITE"
                        }

                        val conflict = MergeConflict(
                            id = java.util.UUID.randomUUID().toString(),
                            filePath = currentFilePath.get() ?: "",
                            conflictType = conflictType,
                            sourceAddon = currentSourceAddon.get() ?: "",
                            targetAddon = currentTargetAddon.get() ?: "",
                            severity = severityFor(conflictType),
                            description = if (sourceHasLowerPriority)
                                "Colisión de clave '$cleanKey': ganador (índice $winnerIdx) prevaleció"
                            else
                                "Colisión de clave '$cleanKey': el valor se sobrescribe.",
                            resolved = false,
                            resolution = null,
                            jsonPath = "$jsonPathActual$cleanKey"
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
     * Fusiona los bloques de eventos de entidades Bedrock (`events`) apilando
     * acciones colisionadas en una estructura `"sequence": [ ... ]` en lugar de
     * sobrescribirlas.
     *
     * Permite que múltiples addons que modifican eventos estándar (como
     * `minecraft:entity_spawned`, `minecraft:entity_born`, etc.) ejecuten sus
     * acciones `add`/`remove` de `component_groups` en conjunto.
     */
    fun mergeBedrockEvents(baseEvents: JSONObject, incomingEvents: JSONObject): JSONObject {
        val merged = JSONObject(baseEvents.toString())
        incomingEvents.keys().forEach { eventName ->
            val cleanEventName = eventName.sanitizeKey()
            if (!merged.has(cleanEventName)) {
                merged.put(cleanEventName, cleanJsonValue(incomingEvents.get(eventName)))
            } else {
                val baseEvObj = merged.optJSONObject(cleanEventName)
                val incomingEvObj = cleanJsonValue(incomingEvents.get(eventName)) as? JSONObject

                if (baseEvObj != null && incomingEvObj != null) {
                    val stackedEvent = stackEventObjects(baseEvObj, incomingEvObj)
                    merged.put(cleanEventName, stackedEvent)
                } else {
                    merged.put(cleanEventName, cleanJsonValue(incomingEvents.get(eventName)))
                }
            }
        }
        return merged
    }

    /**
     * Apila dos objetos de evento Bedrock convirtiendo sus bloques en una `sequence`.
     */
    private fun stackEventObjects(ev1: JSONObject, ev2: JSONObject): JSONObject {
        if (canonicalOf(ev1) == canonicalOf(ev2)) return ev1

        val blocks1 = extractEventSequenceBlocks(ev1)
        val blocks2 = extractEventSequenceBlocks(ev2)

        val combinedBlocks = JSONArray()
        val seen = LinkedHashSet<String>()

        for (b in blocks1) {
            val key = canonicalOf(b)
            if (seen.add(key)) combinedBlocks.put(b)
        }
        for (b in blocks2) {
            val key = canonicalOf(b)
            if (seen.add(key)) combinedBlocks.put(b)
        }

        if (combinedBlocks.length() == 1) {
            return combinedBlocks.getJSONObject(0)
        }

        return JSONObject().put("sequence", combinedBlocks)
    }

    private fun extractEventSequenceBlocks(evObj: JSONObject): List<JSONObject> {
        val seq = evObj.optJSONArray("sequence")
        if (seq != null && seq.length() > 0) {
            val list = mutableListOf<JSONObject>()
            for (i in 0 until seq.length()) {
                val item = seq.optJSONObject(i)
                if (item != null) list.add(item)
            }
            return list
        }
        return listOf(JSONObject(evObj.toString()))
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
            // B6 (auditoría CORE): escritura indentada para diagnóstico legible
            // (consistente con deepMergeStrings que ya usa toString(4)).
            outFile.writeText(json.toString(4))
        }
    }

    private fun concatArrays(array1: JSONArray, array2: JSONArray): JSONArray {
        // C5 (compatibilidad absoluta): antes concatenaba a ciegas, así que
        // fusionar dos packs con las mismas entradas duplicaba eventos
        // (minecraft:client_entity duplicado, etc.) → Bedrock ejecutaba dos
        // veces lo mismo. Ahora se hace UNION por igualdad estructural.
        //
        // ⭐ FASE 4/5 (fix de regresión): la unión por igualdad NO se aplica a los
        // NÚMEROS.
        //
        // Hay dos casos legítimos y opuestos, y ambos aparecen en packs reales:
        //
        //  · `bones: ["huesoA","huesoB"]` + `["huesoA","huesoC"]` → el array es un
        //    conjunto de identificadores. Repetir "huesoA" desplaza los índices y
        //    el juego anima el hueso EQUIVOCADO. Hay que deduplicar.
        //
        //  · `frames: [0,0,1,1]` + `[1,2]` → el array es POSICIONAL y las
        //    repeticiones son el dato: cada entrada es la duración de un fotograma.
        //    Deduplicar convierte [0,0,1,1] en [0,1] y la animación se reproduce al
        //    doble de velocidad, en silencio.
        //
        // Regla: strings y objetos/arrays se unen por igualdad (son identidades);
        // los números se conservan SIEMPRE (son posiciones o medidas).
        // ⭐ TICKET-CORE-006 — VECTORES GEOMÉTRICOS: nunca se concatenan.
        //
        // Un `position`/`rotation`/`scale` de animación de Bedrock es SIEMPRE un
        // vector de 3 componentes `[x, y, z]`. Concatenar dos vectores de 3 da
        // uno de 6, y el motor descarta el bloque entero: el hueso se queda en
        // su pivote original.
        //
        // Medido con Just-Health-Indicator + SimpleEconomy: `hpi_root.position`
        // salía `[0, "(variable.hpi_y ?? 0.0)", 0, 0, 0]` (5), así que el HUD no
        // subía a y=14.4 y `hpi_heart`/`hpi_c1..c3`, aún sin desplazamiento,
        // quedaban TODOS apilados en `[0,0,0]` (el corazón y los números
        // encima unos de otros, a la altura de las patas).
        //
        // Si ambos lados son vectores válidos de 3, el valor es único y gana la
        // prioridad; no hay nada que "unir" porque no son identidades.
        val bothAre3dVectors = array1.length() == 3 && array2.length() == 3
        if (bothAre3dVectors) {
            val winnerIdx = priorityWinnerIndex.get() ?: Int.MAX_VALUE
            val sourceHasLowerPriority =
                (prioritySourceIndex.get() ?: Int.MAX_VALUE) > winnerIdx &&
                    winnerIdx != Int.MAX_VALUE
            return if (sourceHasLowerPriority) {
                cleanJsonArray(array1)
            } else {
                cleanJsonArray(array2)
            }
        }

        val result = JSONArray()
        val seenIdentities = LinkedHashSet<String>()
        for (i in 0 until array1.length()) {
            val v = cleanJsonValue(array1.get(i))
            result.put(v)
            if (v !is Number) seenIdentities.add(canonicalOf(v))
        }
        for (i in 0 until array2.length()) {
            val v = cleanJsonValue(array2.get(i))
            if (v is Number) {
                // Posición/medida: se conserva siempre, incluso repetida.
                result.put(v)
            } else if (seenIdentities.add(canonicalOf(v))) {
                result.put(v)
            }
        }
        return result
    }

    /**
     * Igualdad ESTRUCTURAL entre dos valores JSON (el orden de claves no cuenta).
     *
     * Se usa para distinguir “el segundo addon redefine lo mismo” (no hay
     * conflicto) de “el segundo addon redefine otra cosa y se pierde” (sí lo
     * hay) en los catálogos que resuelven colisiones por clave
     * (`terrain_texture`, `item_texture`, `blocks.json`). Comparar con
     * `toString()` daría falsos conflictos porque el orden de claves de
     * `JSONObject` no es estable.
     */
    fun equivalentlyEqual(a: Any?, b: Any?): Boolean = canonicalOf(a) == canonicalOf(b)

    /** Clave de igualdad estructural para deduplicar arrays fusionados. */
    private fun canonicalOf(value: Any?): String = when (value) {
        is JSONObject -> {
            val keys = value.keys().asSequence().toList().sorted()
            keys.joinToString("|", "{", "}") { k -> "$k=${canonicalOf(value.opt(k))}" }
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonicalOf(value.get(it)) }
        else -> value.toString()
    }

    /**
     * ⭐ FASE 4: la ruta JSON donde vive el conflicto, terminando en `/`.
     *
     * Se deriva del [currentJsonPath] (breadcrumb que van dejando las llamadas
     * recursivas). Sin esto el reporte mostraba `MEDIUM @ ` sin decir DÓNDE
     * estaba el choque, y el usuario tenía que abrir el JSON a buscarlo.
     */
    private val jsonPathActual: String
        get() = currentJsonPath.get()?.takeIf { it.isNotEmpty() } ?: "/"

    /**
     * ⭐ FASE 4: severidad según QUÉ se sobrescribe, no una constante.
     *
     * Todo era MEDIUM, así que perder el `identifier` de una entidad (que la
     * deja huérfana) pesaba lo mismo que un `format_version`. Ahora el reporte
     * puede priorizar lo que de verdad rompe el modpack.
     */
    private fun severityFor(conflictType: String): com.packforge.app.domain.model.ConflictSeverity =
        when (conflictType) {
            "IDENTIFIER_OVERWRITE", "ENTITY_OVERWRITE" ->
                com.packforge.app.domain.model.ConflictSeverity.HIGH
            "VERSION_OVERWRITE" ->
                com.packforge.app.domain.model.ConflictSeverity.LOW
            else ->
                com.packforge.app.domain.model.ConflictSeverity.MEDIUM
        }

    // C0 (compatibilidad absoluta): la limpieza anterior usaba [\p{C}\p{Z}],
    // pero \p{Z} incluye el ESPACIO normal → "Iron Sword" se convertía en
    // "IronSword" (nombres, lore y descripciones corruptos en partida).
    // Ahora solo se eliminan controles/invisibles (Cc+Cf: ZWSP, BOM, etc.)
    // y se recortan los bordes con trim(); los espacios INTERIORES se conservan.
    private fun String.sanitize(): String = this.replace(Regex("[\\p{Cc}\\p{Cf}]"), "").trim()
    private fun String.sanitizeKey(): String = this.replace(Regex("[\\p{Cc}\\p{Cf}]"), "").trim()

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
            // ⭐ FASE 4/5: la limpieza puede COLAPSAR dos claves distintas en una
            // ("a" y "a\u200B" → "a"). Antes la segunda pisaba a la primera en
            // silencio y el contenido se perdía sin dejar rastro.
            //
            // Ahora: gana la PRIMERA (orden de declaración, igual que Bedrock) y
            // la colisión se registra, salvo que el valor sea idéntico —en ese
            // caso no hay nada perdido y reportarlo sería ruido.
            val existing = if (cleaned.has(cleanKey)) cleaned.get(cleanKey) else null
            if (existing != null) {
                if (canonicalOf(existing) != canonicalOf(cleanValue)) {
                    ConflictRegistry.logConflict(
                        severity = com.packforge.app.domain.model.ConflictSeverity.MEDIUM,
                        type = "KEY_COLLISION_AFTER_SANITIZE",
                        file = currentFilePath.get() ?: "",
                        addon1 = currentSourceAddon.get() ?: "",
                        addon2 = currentTargetAddon.get() ?: "",
                        description = "Dos claves distintas ('$key' y otra equivalente) " +
                            "se normalizaron a '$cleanKey': se conserva el primer valor."
                    )
                }
                return@forEach
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
                file = currentFilePath.get() ?: "",
                addon1 = currentSourceAddon.get() ?: "",
                addon2 = currentTargetAddon.get() ?: "",
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
