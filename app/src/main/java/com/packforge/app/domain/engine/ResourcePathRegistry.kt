package com.packforge.app.domain.engine

import com.packforge.app.util.PackForgeLog
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * ═══════════════════════════════════════════════════════════════════════
 * REGISTRO DE RUTAS DE RECURSOS (Resource Path Registry)
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Evita que dos addons se pisen archivos binarios con la MISMA ruta pero
 * contenido DISTINTO (ej. ambos definen textures/blocks/dirt.png).
 *
 * Estrategia por fuente (addon), en orden de prioridad:
 *  1. Se pre-escanean TODOS los archivos elegibles de la fuente y se calcula
 *     su hash MD5.
 *  2. Para cada ruta ya registrada:
 *       - hash IGUAL  → el archivo es idéntico, NO se renombra (dedupe gratis).
 *       - hash DISTINTO → se asigna un alias único `nombre_pf<hex6>.<ext>` y
 *         la fuente usará esa nueva ruta física.
 *  3. Se devuelve un mapa de renombres (ruta completa Y forma sin extensión,
 *     porque terrain_texture/item_texture referencian texturas SIN extensión)
 *     que mergePackType aplica sobre los JSON de ESA fuente antes de copiarlos.
 *
 * NO se tocan: manifest.json, pack_icon.png, scripts/, texts/ y ningún .json
 * (Bedrock vincula entidades/items/bloques POR RUTA de carpeta; renombrarlos
 * rompería el auto-binding).
 */
class ResourcePathRegistry {

    /** ruta -> hash comprometido (primer addon que la usa gana la ruta canónica). */
    private val committed = LinkedHashMap<String, String>()

    /** Historial legible para el reporte final. */
    val aliasLog = mutableListOf<String>()

    /**
     * Pre-planifica una fuente completa contra el registro y COMPROMITE los hashes.
     * @return mapa rutaAntigua → rutaNueva (incluye variantes sin extensión para
     *         imágenes). Vacío si la fuente no necesita renombres.
     */
    fun planAndCommit(sourceRoot: File): Map<String, String> {
        val renames = LinkedHashMap<String, String>()
        if (!sourceRoot.isDirectory) return renames

        sourceRoot.walkTopDown().filter { it.isFile }.forEach { file ->
            val rel = file.relativeTo(sourceRoot).path.replace("\\", "/")
            if (!isEligible(rel)) return@forEach

            val hash = try { md5(file) } catch (e: Exception) { return@forEach }
            val existing = committed[rel]

            when {
                existing == null -> committed[rel] = hash
                existing == hash -> Unit // idéntico: dedupe, sin renombre
                else -> {
                    val alias = buildAlias(rel, hash)
                    committed[alias] = hash
                    renames[rel] = alias
                    // Variante sin extensión (referencias lógicas de atlas Bedrock)
                    val dot = rel.lastIndexOf('.')
                    if (dot > 0 && rel.substringAfterLast('.').lowercase(Locale.ROOT) in
                        setOf("png", "jpg", "jpeg", "tga", "webp", "bmp", "gif")
                    ) {
                        renames[rel.substring(0, dot)] = alias.substring(0, alias.lastIndexOf('.'))
                    }
                    aliasLog += "$rel → $alias (contenido distinto)"
                    PackForgeLog.d(TAG, "🖼️ Alias de recurso: $rel → $alias")
                }
            }
        }
        return renames
    }

    companion object {
        private const val TAG = "PackForge_ResourceReg"

        /** Extensiones binarias elegibles para aliasing. */
        private val BINARY_EXTS = setOf(
            "png", "jpg", "jpeg", "tga", "wav", "ogg", "fsb", "fsh", "vsh",
            "hgt", "material", "bmp", "gif", "webp", "mp3", "m4a"
        )

        /** Rutas/carpetas que nunca se renombran. */
        private fun isEligible(relPath: String): Boolean {
            if (relPath == "manifest.json" || relPath == "pack_icon.png") return false
            if (relPath.startsWith("scripts/") || relPath.startsWith("texts/")) return false
            val ext = relPath.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return ext in BINARY_EXTS
        }

        /** Aplica los renombres de ruta sobre un JSONObject YA parseado (valores exactos).
         *  Delegado al rewriter compartido: política de normalización única del pipeline. */
        fun applyRenames(node: Any, renames: Map<String, String>): Boolean =
            JsonValueRewriter.replaceValues(node, renames)

        private fun buildAlias(rel: String, hash: String): String {
            val dot = rel.lastIndexOf('.')
            val stem = if (dot > 0) rel.substring(0, dot) else rel
            val ext = if (dot > 0) rel.substring(dot) else ""
            return "${stem}_pf${hash.substring(0, 6)}$ext"
        }

        private fun md5(file: File): String {
            val digest = MessageDigest.getInstance("MD5")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
