package com.packforge.app.domain.engine

import org.json.JSONObject

/**
 * ═══════════════════════════════════════════════════════════════════════
 * ADDON IDENTIFIER INDEX — identificadores que declara un addon
 * ═══════════════════════════════════════════════════════════════════════
 *
 * [ConflictEngine] detecta colisiones comparando los identificadores que cada
 * addon aporta. Si esta recolección se deja incompleta, los detectores recorren
 * listas vacías: **el conflicto existe en el modpack pero nunca se reporta**, el
 * usuario no puede decidir y la fusión sobrescribe en silencio.
 *
 * Ese era exactamente el caso de las loot tables, partículas, materiales,
 * definiciones de sonido y animation controllers: `AddonParser` solo recogía
 * entidades, ítems y recetas.
 *
 * Vive en su propio objeto (en lugar de dentro del parser) porque el parser
 * necesita un `Context` de Android y no se puede ejecutar en tests: así la
 * recolección se prueba con el MISMO código que corre en producción.
 */
object AddonIdentifierIndex {

    /** Identificadores declarados por un addon, por tipo. */
    data class Identifiers(
        val entity: List<String> = emptyList(),
        val item: List<String> = emptyList(),
        val recipe: List<String> = emptyList(),
        val lootTable: List<String> = emptyList(),
        val particle: List<String> = emptyList(),
        val materialInstance: List<String> = emptyList(),
        val soundDefinition: List<String> = emptyList(),
        val animationController: List<String> = emptyList()
    )

    /**
     * Acumulador de un addon. Se alimenta archivo a archivo para no retener
     * todos los JSON en memoria (un addon grande puede tener miles).
     */
    class Builder {
        private val entity = mutableListOf<String>()
        private val item = mutableListOf<String>()
        private val recipe = mutableListOf<String>()
        private val lootTable = mutableListOf<String>()
        private val particle = mutableListOf<String>()
        private val materialInstance = mutableListOf<String>()
        private val soundDefinition = mutableListOf<String>()
        private val animationController = mutableListOf<String>()

        /**
         * Analiza una entrada del addon.
         *
         * @param entryName ruta de la entrada dentro del addon (p. ej. `rp/particles/dust.json`)
         * @param json contenido ya parseado, o `null` si no es JSON legible
         */
        fun accept(entryName: String, json: JSONObject?) {
            val lower = entryName.lowercase()

            // Los `.material` son JSON con otra extensión.
            if (lower.endsWith(".material")) {
                if (json == null) return
                rootKeys(json, "materials")
                    .filter { it != "version" }
                    .forEach { materialInstance.add(it) }
                return
            }

            if (!lower.endsWith(".json") || json == null) return

            if (lower.contains("entities/")) {
                json.optJSONObject("minecraft:entity")?.optJSONObject("description")
                    ?.optString("identifier")?.takeIf { it.isNotBlank() }
                    ?.let { entity.add(it) }
            }
            if (lower.contains("items/")) {
                json.optJSONObject("minecraft:item")?.optJSONObject("description")
                    ?.optString("identifier")?.takeIf { it.isNotBlank() }
                    ?.let { item.add(it) }
            }
            if (lower.contains("recipes/")) {
                recipeKeys(json).forEach { recipe.add(it) }
            }
            // En Bedrock el identificador de una loot table ES su ruta. Se
            // conserva desde `loot_tables/` y CON extensión: así el detector
            // construye `loot_tables/<id>.json`, que tras colapsar el prefijo
            // duplicado apunta a una ruta REAL del modpack y la decisión del
            // usuario puede aplicarse copiando ese archivo.
            if (lower.contains("loot_tables/")) {
                val start = entryName.indexOf("loot_tables/", ignoreCase = true)
                if (start >= 0) lootTable.add(entryName.substring(start).removePrefix("./"))
            }
            if (lower.contains("particles/")) {
                json.optJSONObject("particle_effect")?.optJSONObject("description")
                    ?.optString("identifier")?.takeIf { it.isNotBlank() }
                    ?.let { particle.add(it) }
            }
            if (lower.contains("animation_controllers/")) {
                rootKeys(json, "animation_controllers").forEach { animationController.add(it) }
            }
            if (lower.endsWith("sound_definitions.json")) {
                rootKeys(json, null)
                    .filter { it != "format_version" }
                    .forEach { soundDefinition.add(it) }
            }
        }

        fun build(): Identifiers = Identifiers(
            entity = entity.distinct(),
            item = item.distinct(),
            recipe = recipe.distinct(),
            lootTable = lootTable.distinct(),
            particle = particle.distinct(),
            materialInstance = materialInstance.distinct(),
            soundDefinition = soundDefinition.distinct(),
            animationController = animationController.distinct()
        )

        /** Claves de un objeto raíz; `null` usa la raíz misma. */
        private fun rootKeys(json: JSONObject, rootKey: String?): List<String> {
            val container = if (rootKey == null) json else json.optJSONObject(rootKey) ?: return emptyList()
            return container.keys().asSequence().filter { it.isNotBlank() }.toList()
        }

        /** Recetas: las claves del tipo `minecraft:recipe_*` son las recetas. */
        private fun recipeKeys(json: JSONObject): List<String> = json.keys().asSequence()
            .filter { it.startsWith("minecraft:recipe_") }
            .mapNotNull { key ->
                json.optJSONObject(key)?.optJSONObject("description")
                    ?.optString("identifier")?.takeIf { it.isNotBlank() }
            }
            .toList()
    }
}
