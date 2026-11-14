package com.packforge.app.domain.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * ═══════════════════════════════════════════════════════════════════════
 * NORMALIZADOR ÚNICO DE REFERENCIAS BEDROCK
 * ═══════════════════════════════════════════════════════════════════════
 *
 * El reporte de fusión de Better MC demostró que cada validador del motor
 * implementaba su propio criterio para decidir qué es una "referencia":
 *
 *  - `PackForgeValidator` hacía `arr.optString(i)` sobre `render_controllers`,
 *    donde Bedrock admite objetos condicionales
 *    (`{"controller.render.x": "q.variant > 0"}`) ⇒ serializaba el objeto y lo
 *    comparaba con los ids: 131 archivos reportados como "no existe" cuando el
 *    id SÍ existía.
 *  - `PackForgeHealer` hacía `optString` sobre el valor de `geometry`, que puede
 *    ser un mapa de `bone_visibility` ⇒ "modelos faltantes" como
 *    `{"bot_ne":true,...}`.
 *  - Nadie filtraba Molang ⇒ `q.is_baby ? Geometry.baby : Geometry.default`
 *    se buscaba como nombre de archivo.
 *  - Nadie distinguía un ID real de un ALIAS de la propia entidad
 *    (`Geometry.default`, `Texture.default`, `Material.default`).
 *
 * Este objeto es la única respuesta a "¿esto es un id/ruta o es un dato?".
 * Regla: ante la duda, NO es una referencia (preferimos no reportar un problema
 * real antes que inundar el reporte de falsos positivos; el cierre real de
 * referencias se hace contra el catálogo vanilla y el índice del pack).
 */
object BedrockRefs {

    /** Prefijos propios de Molang que jamás son identificadores ni rutas. */
    private val MOLANG_PREFIXES = listOf(
        "q.", "v.", "math.", "variable.", "query.", "temp.", "context.",
        "array.", "!variable.", "!query.", "(",
        // ⭐ FASE 5: `material.<slot>` en render controllers es el ACCESOR Molang
        // al mapa `materials` de la propia entidad (`"materials": [{"*":
        // "material.outline"}]` → el material llamado `outline` en la definición
        // que usa el RC). Es el mismo alias que `Material.default` con mayúscula
        // (que ya filtraba isAlias), pero CoreCraft lo escribe en minúscula y se
        // colaba como "material inexistente". El material REAL al que apunta vive
        // en el mapa de la entidad y se valida allí.
        //
        // Ojo: `geometry.` NO se añade aquí a propósito — `geometry.humanoid` sí
        // es un identificador real de geometría vanilla.
        "material."
    )

    /** Operadores de Molang: si aparecen, el valor es una expresión. */
    private val MOLANG_OPERATORS = listOf("==", "!=", "&&", "||", ">=", "<=", " ? ", "?")

    /**
     * ¿El texto es una expresión Molang (o cualquier cosa que no puede ser un
     * id/ruta)? Un identificador o ruta de Bedrock nunca contiene espacios.
     */
    fun isExpression(raw: String): Boolean {
        val v = raw.trim()
        if (v.isEmpty()) return true
        if (v.any { it.isWhitespace() }) return true
        if (v.startsWith("(") || v.startsWith("!") || v.startsWith("-")) return true
        if (MOLANG_OPERATORS.any { v.contains(it) }) return true
        val lower = v.lowercase()
        return MOLANG_PREFIXES.any { lower.startsWith(it) }
    }

    /**
     * Alias interno de la entidad/atachable: `Geometry.default`, `Texture.default`,
     * `Material.default`. Se resuelven contra el mapa `geometry`/`textures`/
     * `materials` de la PROPIA definición, así que nunca son ids del pack.
     *
     * Se distingue por la mayúscula inicial: en Bedrock los ids van en minúscula
     * (`geometry.default` es una geometría vanilla real) y los alias en CamelCase.
     */
    fun isAlias(raw: String): Boolean {
        val v = raw.trim()
        return v.startsWith("Geometry.") || v.startsWith("Texture.") ||
            v.startsWith("Material.") || v.startsWith("Array.")
    }

    /** Devuelve el id si el valor puede serlo; `null` si es alias, Molang o basura. */
    fun idOrNull(raw: String?): String? {
        if (raw == null) return null
        val v = raw.trim()
        if (v.isEmpty()) return null
        if (isAlias(v) || isExpression(v)) return null
        return v
    }

    /** Id de geometría: solo `geometry.*`, nunca un alias ni una expresión. */
    fun geometryId(raw: String?): String? =
        idOrNull(raw)?.takeIf { it.startsWith("geometry.") }

    /**
     * Ids de geometría de un valor `geometry`:
     *  - String  → el id.
     *  - JSONArray → cada elemento.
     *  - JSONObject (mapa alias→id, o `minecraft:geometry` de bloque con
     *    `identifier` + `bone_visibility`) → solo los VALORES que son ids; los
     *    mapas de huesos (`{"bot_ne":true,...}`) se ignoran.
     */
    fun geometryIds(value: Any?): List<String> = when (value) {
        null -> emptyList()
        is String -> listOfNotNull(geometryId(value))
        is JSONObject -> value.keys().asSequence()
            .mapNotNull { key -> geometryIds(value.opt(key)).firstOrNull() }
            .toList()
        is JSONArray -> (0 until value.length()).flatMap { geometryIds(value.opt(it)) }
        else -> emptyList()
    }

    /**
     * Ids de render controller. Admite las tres formas de Bedrock:
     *  - `"controller.render.x"`.
     *  - `["controller.render.x", {"controller.render.y": "q.variant > 0"}]`.
     *  - `{"controller.render.x": {...}}` (mapa legacy).
     * En los objetos, el ID es la CLAVE; el valor es una condición Molang.
     */
    fun renderControllerIds(value: Any?): List<String> = when (value) {
        null -> emptyList()
        is String -> listOfNotNull(idOrNull(value))
        is JSONArray -> (0 until value.length()).flatMap { renderControllerIds(value.opt(it)) }
        is JSONObject -> value.keys().asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        else -> emptyList()
    }

    /**
     * Ids de animación declarados en el mapa `animations` de un client_entity:
     * el valor puede ser un id o un objeto `{ "animation.x": "..." }`.
     */
    fun animationIds(desc: JSONObject?): List<String> {
        val map = desc?.optJSONObject("animations") ?: return emptyList()
        val out = mutableListOf<String>()
        map.keys().forEach { key ->
            when (val value = map.opt(key)) {
                is String -> idOrNull(value)?.let { out += it }
                is JSONObject -> value.keys().forEach { k -> idOrNull(k)?.let { out += it } }
                is JSONArray -> for (i in 0 until value.length()) {
                    (value.opt(i) as? String)?.let { idOrNull(it)?.let { id -> out += id } }
                }
                else -> Unit
            }
        }
        return out
    }

    /**
     * Rutas de textura de un valor `texture`/`textures` de render controller o
     * client_entity. Se descartan alias (`Texture.default`) y Molang.
     */
    fun texturePaths(value: Any?): List<String> = when (value) {
        null -> emptyList()
        is String -> pathOrNull(value)?.let { listOf(it) } ?: emptyList()
        is JSONArray -> (0 until value.length()).flatMap { texturePaths(value.opt(it)) }
        is JSONObject -> value.keys().asSequence().flatMap { texturePaths(value.opt(it)) }.toList()
        else -> emptyList()
    }

    /** Ruta de textura normalizada (sin extensión, sin barra inicial). */
    fun pathOrNull(raw: String?): String? {
        val v = idOrNull(raw) ?: return null
        if (!v.contains('/')) return null
        if (!v.startsWith("textures/")) return null
        return v
    }
}