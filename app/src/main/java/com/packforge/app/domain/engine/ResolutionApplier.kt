package com.packforge.app.domain.engine

import com.packforge.app.util.PackForgeLog
import java.io.File

/**
 * ═══════════════════════════════════════════════════════════════════════
 * RESOLUTION APPLIER — hace efectiva tu decisión, archivo por archivo
 * ═══════════════════════════════════════════════════════════════════════
 *
 * La fusión copia en orden: el último addon se queda con los archivos que
 * chocan. Cuando el usuario elige explícitamente un ganador en la pantalla de
 * Conflictos, esa decisión se traducía solo a un reordenado global de addons.
 * Con **varios** conflictos resueltos a la vez, un mismo orden no puede
 * satisfacerlos todos.
 *
 * Este paso, que corre al final de la fusión, aplica cada decisión de forma
 * quirúrgica: copia el archivo del ganador elegido sobre el resultado, para ese
 * archivo concreto y sin tocar nada más.
 *
 * ### Qué NO se aplica aquí (deliberadamente)
 *  - **Rutas derivadas de un identificador** (contienen `:` como
 *    `loot_tables/mypack:chest.json`): el nombre real del archivo puede no
 *    coincidir con el identificador. Esos casos los cubre el orden de addons
 *    (AddonOrderResolver) y los mergers dedicados.
 *  - **Archivos con fusión dedicada** (`sounds.json`, `sound_definitions.json`,
 *    `blocks.json`, `biomes_client.json`, `ui/`, `texts/`, `font/`, `materials/`
 *    …): copiar uno entero desharía la fusión semántica.
 */
object ResolutionApplier {

    private const val TAG = "PackForge_Resolution"

    /** Archivos cuya fusión es semántica: copiarlos enteros la rompería. */
    private val DEDICATED_MERGE_FILES = setOf(
        "manifest.json",
        "sounds.json",
        "sound_definitions.json",
        "blocks.json",
        "biomes_client.json",
        "textures_list.json",
        "flipbook_textures.json",
        "terrain_texture.json",
        "item_texture.json",
        "player.json",
        "player.entity.json"
    )

    /** Carpetas con fusión dedicada. */
    private val DEDICATED_MERGE_PREFIXES = listOf(
        "ui/", "texts/", "font/", "biomes/", "worldgen/", "materials/"
    )

    /**
     * ¿Se puede honrar la decisión copiando el archivo entero del ganador?
     */
    fun isResolvableByFileCopy(affectedFile: String): Boolean {
        if (affectedFile.isBlank()) return false
        // Ruta derivada de un identificador (p. ej. "loot_tables/ns:tabla.json").
        if (affectedFile.contains(':')) return false

        val normalized = affectedFile.replace('\\', '/').removePrefix("/")
        val fileName = normalized.substringAfterLast('/')
        if (fileName in DEDICATED_MERGE_FILES) return false
        return DEDICATED_MERGE_PREFIXES.none { normalized.startsWith(it) }
    }

    /**
     * @param applied cuántos archivos se sustituyeron por el del ganador
     * @param notFound rutas que no se pudieron localizar (solo informativo)
     */
    data class Result(
        val applied: Int,
        val notFound: List<String>
    )

    /**
     * Copia el archivo del ganador sobre el resultado fusionado.
     *
     * @param fileWinners ruta relativa → id del addon ganador
     * @param addonDirById id del addon → directorio donde se extrajo
     */
    fun apply(
        fileWinners: Map<String, String>,
        addonDirById: Map<String, String>,
        mergedBpDir: File,
        mergedRpDir: File
    ): Result {
        if (fileWinners.isEmpty()) return Result(0, emptyList())

        var applied = 0
        val notFound = mutableListOf<String>()

        fileWinners.forEach { (affectedFile, winnerId) ->
            if (!isResolvableByFileCopy(affectedFile)) return@forEach
            val normalized = affectedFile.replace('\\', '/').removePrefix("/")

            val sourceRoot = addonDirById[winnerId]?.let { File(it) }
            if (sourceRoot == null || !sourceRoot.isDirectory) {
                notFound += normalized
                return@forEach
            }

            val source = findInAddonTree(sourceRoot, normalized)
            if (source == null) {
                notFound += normalized
                return@forEach
            }

            // El archivo vive en el BP o en el RP fusionado: se sustituye donde exista.
            val destination = when {
                File(mergedBpDir, normalized).isFile -> File(mergedBpDir, normalized)
                File(mergedRpDir, normalized).isFile -> File(mergedRpDir, normalized)
                else -> null
            }
            if (destination == null) {
                notFound += normalized
                return@forEach
            }

            try {
                source.copyTo(destination, overwrite = true)
                applied++
                PackForgeLog.d(
                    TAG,
                    "🎯 Decisión aplicada: '$normalized' ← ${sourceRoot.name}"
                )
            } catch (e: Exception) {
                PackForgeLog.e(TAG, "No se pudo aplicar la decisión en '$normalized': ${e.message}")
                notFound += normalized
            }
        }

        if (applied > 0) {
            PackForgeLog.d(TAG, "✅ $applied archivo(s) sustituido(s) por la decisión del usuario")
        }
        if (notFound.isNotEmpty()) {
            PackForgeLog.d(TAG, "ℹ️ Sin aplicar (${notFound.size}): ${notFound.distinct().joinToString()}")
        }
        return Result(applied, notFound.distinct())
    }

    /**
     * Busca el archivo en el árbol del addon. El addon extraído puede tener
     * subcarpetas (`bp/`, `rp/`) o el contenido en la raíz, así que además del
     * camino directo se compara por sufijo.
     */
    private fun findInAddonTree(addonRoot: File, relPath: String): File? {
        val direct = File(addonRoot, relPath)
        if (direct.isFile) return direct

        val suffix = "/$relPath"
        return addonRoot.walkTopDown()
            .firstOrNull { it.isFile && it.invariantSeparatorsPath.endsWith(suffix) }
    }
}
