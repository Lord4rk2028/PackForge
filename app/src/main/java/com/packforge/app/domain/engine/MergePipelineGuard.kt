package com.packforge.app.domain.engine

/**
 * ═══════════════════════════════════════════════════════════════════════
 * MERGE PIPELINE GUARD — ninguna fusión declarada puede quedar sin ejecutar
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Motivo: `mergeSoundDefinitions` y `mergeParticles` estuvieron **definidas y
 * documentadas como parte del pipeline** pero nunca se invocaban. El copiado
 * genérico las sobrescribía y se perdían los sonidos y las partículas de todos
 * los addons menos el último. Las listas `CRITICAL_DIRS*` del orquestador
 * afirmaban que esas fusiones existían, así que la documentación mentía y el
 * fallo era invisible.
 *
 * Este guard convierte esa documentación en una **comprobación ejecutable**:
 *  - [REQUIRED_MERGES] es la lista única de fusiones dedicadas.
 *  - Cada tarea del pipeline se registra al arrancar con [registerInvoked].
 *  - Al terminar, [findMissingMerges] devuelve las declaradas que no corrieron
 *    (pérdida potencial de contenido) y [findUndeclaredMerges] las que corrieron
 *    sin estar declaradas (deriva de la lista).
 *
 * Así, añadir una fusión al pipeline sin declararla —o declararla sin
 * conectarla— deja de ser un fallo silencioso.
 */
object MergePipelineGuard {

    /** Fusiones dedicadas que deben ejecutarse en CADA exportación. */
    val REQUIRED_MERGES: Set<String> = setOf(
        // Fase previa a la copia (mutan los addons de origen)
        "deduplicateParticleIdentifiers",
        // Resource Pack
        "mergeTerrainTexture",
        "mergeItemTexture",
        "mergeBlocksJson",
        "mergeLangFiles",
        "mergeFlipbookTextures",
        "mergeGeometryFiles",
        "mergeRenderControllers",
        "mergeAnimations",
        "mergeSoundsJson",
        "mergeSoundDefinitions",
        "mergeEntityDefinitions",
        "mergeEntityTextures",
        "mergeAttachables",
        "mergeBiomesClient",
        "mergeFontGlyphMaps",
        "mergeTexturesList",
        "mergeUi",
        // Behavior Pack
        "mergePlayerEntity",
        "mergeLootTables",
        "mergeRecipes"
    )

    private val invoked: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    /** Prepara el guard para una nueva fusión. */
    fun reset() {
        invoked.clear()
    }

    /** Marca una fusión como ejecutada (lo llama el pipeline). */
    fun registerInvoked(name: String) {
        invoked.add(name)
    }

    /** Fusiones DECLARADAS que no llegaron a ejecutarse → pérdida potencial. */
    fun findMissingMerges(): List<String> =
        REQUIRED_MERGES.filterNot { it in invoked }.sorted()

    /** Fusiones que corrieron sin estar declaradas → la lista quedó desfasada. */
    fun findUndeclaredMerges(): List<String> =
        invoked.filterNot { it in REQUIRED_MERGES }.sorted()

    /** Fusiones ejecutadas en la última corrida (para diagnóstico/tests). */
    fun invokedMerges(): Set<String> = invoked.toSet()
}
