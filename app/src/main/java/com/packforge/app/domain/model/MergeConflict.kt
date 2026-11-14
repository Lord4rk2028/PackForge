package com.packforge.app.domain.model

data class MergeConflict(
    val id: String,
    val filePath: String,
    val conflictType: String,
    val sourceAddon: String,
    val targetAddon: String,
    val severity: ConflictSeverity = ConflictSeverity.MEDIUM,
    val description: String = "",
    val resolved: Boolean = false,
    val resolution: String? = null,
    /**
     * Ruta DENTRO del JSON (`/minecraft:entity/components/minecraft:health`).
     *
     * Sin esto un conflicto decía solo `Colisión de clave 'max'` y el usuario no
     * podía saber a qué parte del archivo se refería. Se rellena en el
     * deep-merge, que es el único sitio que conoce la ruta real.
     */
    val jsonPath: String = ""
) {
    companion object {
        const val RESOLUTION_KEEP_SOURCE = "KEEP_SOURCE"
        const val RESOLUTION_KEEP_TARGET = "KEEP_TARGET"
        const val RESOLUTION_MERGE = "MERGE"

        /**
         * Clave ESTABLE para deduplicar conflictos repetidos en el reporte.
         * Los `id` son UUID aleatorios y no sirven para agrupar.
         */
        fun stableKey(
            filePath: String,
            jsonPath: String,
            conflictType: String,
            addonA: String,
            addonB: String
        ): String = "$filePath|$jsonPath|$conflictType|$addonA|$addonB"
    }
}