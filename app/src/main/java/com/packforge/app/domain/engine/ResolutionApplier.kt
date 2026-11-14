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

    /** Carpetas con fusión dedicada (una decisión por archivo NO aplica). */
    private val DEDICATED_MERGE_PREFIXES = listOf(
        "texts/", "font/", "biomes/", "worldgen/", "materials/"
    )

    /**
     * Dentro de `ui/` el único contenido agregado son estos dos archivos; el
     * resto son PANTALLAS, y una pantalla sí admite "gana este addon": es
     * justamente lo que el usuario decide cuando elige un ganador en un
     * conflicto de `ui/hud_screen.json`.
     */
    private val UI_AGGREGATE_FILES = setOf("_ui_defs.json", "_global_variables.json")

    /**
     * Carpetas donde un archivo = un identificador (decisión por archivo válida).
     *
     * Los identificadores con namespace solo se aceptan aquí: en `particles/`
     * un id con namespace NO es una ruta, y además esas colisiones ya se
     * resuelven renombrando en el origen antes de copiar.
     */
    private val IDENTIFIER_FOLDERS = listOf("loot_tables/", "recipes/")

    /**
     * Normaliza la ruta de un conflicto.
     *
     * Los detectores construyen `affectedFile` como `"loot_tables/$identifier.json"`,
     * y el identificador puede ya incluir la carpeta (`loot_tables/entities/zombie.json`),
     * así que el prefijo aparece duplicado. Se colapsa aquí.
     */
    fun normalizeAffectedPath(affectedFile: String): String {
        var path = affectedFile.replace('\\', '/').trim().removePrefix("/")
        IDENTIFIER_FOLDERS.forEach { prefix ->
            while (path.startsWith(prefix + prefix)) {
                path = path.removePrefix(prefix)
            }
        }
        return path
    }

    /**
     * ¿Se puede honrar la decisión copiando el archivo entero del ganador?
     */
    fun isResolvableByFileCopy(affectedFile: String): Boolean {
        if (affectedFile.isBlank()) return false
        return isResolvableNormalized(normalizeAffectedPath(affectedFile))
    }

    private fun isResolvableNormalized(normalized: String): Boolean {
        val fileName = normalized.substringAfterLast('/')
        if (fileName in DEDICATED_MERGE_FILES) return false
        if (normalized.startsWith("ui/") && fileName in UI_AGGREGATE_FILES) return false
        if (DEDICATED_MERGE_PREFIXES.any { normalized.startsWith(it) }) return false

        // Ruta derivada de un identificador: solo tiene sentido en carpetas
        // donde "un archivo = un identificador".
        if (normalized.contains(':')) {
            return IDENTIFIER_FOLDERS.any { normalized.startsWith(it) }
        }
        return true
    }

    /**
     * Rutas candidatas para localizar el archivo.
     *
     * Un identificador con namespace (`mypack:chest`) suele corresponder a un
     * archivo sin namespace (`loot_tables/chest.json`), así que se prueban
     * ambas formas en lugar de rendirse.
     */
    fun candidatePaths(affectedFile: String): List<String> {
        val normalized = normalizeAffectedPath(affectedFile)
        val candidates = mutableListOf(normalized)

        val lastSlash = normalized.lastIndexOf('/')
        val lastColon = normalized.lastIndexOf(':')
        if (lastColon > lastSlash) {
            val stripped = normalized.substring(0, lastSlash + 1) + normalized.substring(lastColon + 1)
            if (stripped.substringAfterLast('/').isNotBlank()) candidates += stripped
        }
        return candidates.distinct()
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
            val candidates = candidatePaths(affectedFile)

            val sourceRoot = addonDirById[winnerId]?.let { File(it) }
            if (sourceRoot == null || !sourceRoot.isDirectory) {
                notFound += candidates.first()
                return@forEach
            }

            // Se prueba cada candidato: el archivo del ganador y el del
            // resultado tienen que existir en la MISMA forma de ruta.
            val match = candidates.firstNotNullOfOrNull { candidate ->
                val source = findInAddonTree(sourceRoot, candidate) ?: return@firstNotNullOfOrNull null
                val destination = when {
                    File(mergedBpDir, candidate).isFile -> File(mergedBpDir, candidate)
                    File(mergedRpDir, candidate).isFile -> File(mergedRpDir, candidate)
                    else -> null
                } ?: return@firstNotNullOfOrNull null
                source to destination
            }

            if (match == null) {
                notFound += candidates.first()
                return@forEach
            }

            try {
                match.first.copyTo(match.second, overwrite = true)
                applied++
                PackForgeLog.d(
                    TAG,
                    "🎯 Decisión aplicada: '${match.second.relativeTo(mergedRpDir.parentFile ?: mergedRpDir).invariantSeparatorsPath}' ← ${sourceRoot.name}"
                )
            } catch (e: Exception) {
                PackForgeLog.e(TAG, "No se pudo aplicar la decisión en '$affectedFile': ${e.message}")
                notFound += candidates.first()
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
