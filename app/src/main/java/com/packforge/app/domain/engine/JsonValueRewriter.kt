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
