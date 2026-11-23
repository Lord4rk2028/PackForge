package com.packforge.app.domain.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * ═══════════════════════════════════════════════════════════════════════
 * REWRITER COMPARTIDO DE VALORES JSON (única fuente de verdad)
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Sustituye valores string EXACTOS en todo un árbol JSONObject/JSONArray con
 * UNA sola política de normalización para todo el pipeline PackForge:
 *
 *   1. Coincidencia exacta (O(1)).
 *   2. Fallback trim-aware (O(1)): si la clave del mapa difiere del valor solo
 *      por espacios extremos, también se reemplaza — el valor NUEVO siempre es
 *      la forma limpia, así que esto REPARA referencias sucias en lugar de
 *      dejarlas obsoletas (consistente con JsonDeepMerger.cleanJsonObject).
 *
 * Consumidores: IdentifierRemapper (IDs renombrados), ResourcePathRegistry
 * (rutas de recursos con alias), fusiones de sonido/partículas y alias de
 * geometría en EntityDependencyResolver. Ninguna otra clase debe implementar
 * caminadores propios: cualquier cambio de semántica se hace AQUÍ y se
 * propaga a todas las fases.
 */
object JsonValueRewriter {

    /**
     * Reemplaza en [root] todo valor string que coincida (exacto o por trim)
     * con una clave de [renames], por el valor correspondiente.
     * @return true si se produjo al menos un reemplazo.
     */
    fun replaceValues(root: Any?, renames: Map<String, String>): Boolean {
        if (root == null || renames.isEmpty()) return false
        // Índice normalizado: primera asignación gana ante claves que colisionan por trim.
        val normalized = HashMap<String, String>(renames.size)
        renames.forEach { (k, v) ->
            val key = k.trim()
            if (key.isNotEmpty()) normalized.putIfAbsent(key, v)
        }
        return walk(root, renames, normalized)
    }

    /**
     * Reemplaza CLAVES exactas en los objetos que [esCatalogo] identifique.
     *
     * No es un barrido de claves: una clave JSON puede ser cualquier cosa
     * (nombres de componentes, huesos, eventos) y renombrarla a ciegas rompería
     * el archivo. Solo se aplica donde el llamador demuestra que la clave ES un
     * identificador, es decir, catálogos indexados por id:
     *
     *  · `blocks.json` (raíz) → id de bloque
     *  · `texture_data` de `terrain_texture.json` / `item_texture.json`
     *  · `entity_sounds.entities` de `sounds.json` → id de entidad
     *
     * Motivo: [replaceValues] no toca claves, así que al renombrar el bloque de
     * un addon perdedor su entrada en `blocks.json` se quedaba con el id viejo
     * y el bloque renombrado perdía su mapeo de textura (síntoma: bloque “?”).
     *
     * Si al renombrar la clave destino ya existe se conserva la primera
     * aparición, igual que el resto del pipeline (y el choque lo reporta quien
     * fusiona el catálogo, no este rewriter).
     *
     * @param esCatalogo recibe la ruta del objeto (`"texture_data"`,
     *        `"entity_sounds/entities"`, `""` para la raíz) y decide si sus
     *        claves son identificadores.
     * @return true si se renombró al menos una clave.
     */
    fun replaceKeys(
        root: JSONObject,
        renames: Map<String, String>,
        esCatalogo: (String) -> Boolean
    ): Boolean {
        if (renames.isEmpty()) return false
        val normalized = HashMap<String, String>(renames.size)
        renames.forEach { (k, v) ->
            val key = k.trim()
            if (key.isNotEmpty()) normalized.putIfAbsent(key, v)
        }
        return walkKeys(root, "", renames, normalized, esCatalogo)
    }

    private fun walkKeys(
        node: Any?,
        path: String,
        exact: Map<String, String>,
        normalized: Map<String, String>,
        esCatalogo: (String) -> Boolean
    ): Boolean {
        var changed = false
        when (node) {
            is JSONObject -> {
                // Primero se recorren los valores: la ruta de un hijo se forma
                // con la clave ORIGINAL (el catálogo se renombra al final).
                val keys = node.keys().asSequence().toList()
                for (key in keys) {
                    val value = node.get(key)
                    if (value is JSONObject || value is JSONArray) {
                        val childPath = if (path.isEmpty()) key else "$path/$key"
                        changed = walkKeys(value, childPath, exact, normalized, esCatalogo) || changed
                    }
                }
                if (esCatalogo(path)) {
                    val renombrado = rebuildCatalogObject(node, exact, normalized)
                    if (renombrado != null) {
                        // Se vacía y se rellena el MISMO objeto para que el padre
                        // conserve su referencia; JSONObject no admite reemplazar
                        // el nodo desde aquí.
                        val pares = renombrado.keys().asSequence().toList()
                        node.keys().asSequence().toList().forEach { node.remove(it) }
                        pares.forEach { node.put(it, renombrado.get(it)) }
                        changed = true
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    val value = node.get(i)
                    if (value is JSONObject || value is JSONArray) {
                        changed = walkKeys(value, "$path[]", exact, normalized, esCatalogo) || changed
                    }
                }
            }
        }
        return changed
    }

    /** Devuelve una copia con las claves renombradas, o null si no cambió nada. */
    private fun rebuildCatalogObject(
        node: JSONObject,
        exact: Map<String, String>,
        normalized: Map<String, String>
    ): JSONObject? {
        val keys = node.keys().asSequence().toList()
        var changed = false
        val resultado = JSONObject()
        for (key in keys) {
            val destino = lookup(key, exact, normalized) ?: key
            if (destino != key) changed = true
            if (!resultado.has(destino)) resultado.put(destino, node.get(key))
        }
        return if (changed) resultado else null
    }

    private fun walk(
        node: Any?,
        exact: Map<String, String>,
        normalized: Map<String, String>
    ): Boolean {
        var changed = false
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    when (val value = node.get(key)) {
                        is String -> lookup(value, exact, normalized)?.let {
                            node.put(key, it)
                            changed = true
                        }
                        is JSONObject, is JSONArray -> changed = walk(value, exact, normalized) || changed
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    when (val value = node.get(i)) {
                        is String -> lookup(value, exact, normalized)?.let {
                            node.put(i, it)
                            changed = true
                        }
                        is JSONObject, is JSONArray -> changed = walk(value, exact, normalized) || changed
                    }
                }
            }
        }
        return changed
    }

    private fun lookup(
        value: String,
        exact: Map<String, String>,
        normalized: Map<String, String>
    ): String? {
        exact[value]?.let { return it }
        if (value != value.trim()) {
            normalized[value.trim()]?.let { return it }
        }
        return null
    }
}
