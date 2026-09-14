package com.packforge.app.domain.engine

import com.packforge.app.util.PackForgeLog
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
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
 *
 * ══ REGISTRO GLOBAL DE MUTACIONES ══
 * Cuando fastMergeAllSources detecta una colisión y renombra un archivo
 * binario/recurso, registra el par (original → mutada) en el registro
 * global. Antes del empaquetado final en ZIP, se recorre recursivamente
 * el directorio fusionado y se actualizan TODAS las cadenas de texto en
 * JSONs que coincidan con el mapa de mutación, usando JsonValueRewriter.
 * Esto garantiza que las referencias internas (terrain_texture, item_texture,
 * entity textures, etc.) apunten a los nombres efectivos de los archivos.
 */
class ResourcePathRegistry {

    /** ruta -> hash comprometido (primer addon que la usa gana la ruta canónica). */
    private val committed = LinkedHashMap<String, String>()

    /** Historial legible para el reporte final. */
    val aliasLog = mutableListOf<String>()

    /**
     * Registro GLOBAL de mutaciones de rutas durante la sesión de fusión.
     * Clave: ruta relativa original (ej: "textures/entity/steve.png")
     * Valor: ruta relativa mutada efectiva (ej: "textures/entity/steve_pf_174000.png")
     *
     * Incluye tanto la forma CON extensión como SIN extensión, porque
     * Bedrock referencia texturas sin extensión en terrain_texture.json,
     * item_texture.json y en las definiciones de entidad (description.textures).
     */
    private val globalMutationRegistry = mutableMapOf<String, String>()

    /**
     * Registra una mutación de ruta cuando fastMergeAllSources renombra
     * un archivo para evitar colisión de sobreescritura.
     *
     * @param originalRelPath Ruta relativa original (ej: "textures/blocks/dirt.png")
     * @param mutatedRelPath Ruta efectiva tras el renombre (ej: "textures/blocks/dirt_pf_174000.png")
     */
    fun registerMutation(originalRelPath: String, mutatedRelPath: String) {
        globalMutationRegistry[originalRelPath] = mutatedRelPath
        // Registrar también la variante sin extensión (referencias de atlas Bedrock)
        val origDot = originalRelPath.lastIndexOf('.')
        val mutDot = mutatedRelPath.lastIndexOf('.')
        if (origDot > 0 && mutDot > 0) {
            val origNoExt = originalRelPath.substring(0, origDot)
            val mutNoExt = mutatedRelPath.substring(0, mutDot)
            if (origNoExt != mutNoExt) {
                globalMutationRegistry[origNoExt] = mutNoExt
            }
        }
        PackForgeLog.d(TAG, "📝 Mutación registrada: $originalRelPath → $mutatedRelPath")
    }

    /**
     * Devuelve el mapa global de mutaciones para uso externo (reportes, etc).
     */
    fun getGlobalRewriteMap(): Map<String, String> = globalMutationRegistry.toMap()

    /**
     * Indica si hay mutaciones registradas.
     */
    fun hasGlobalMutations(): Boolean = globalMutationRegistry.isNotEmpty()

    /**
     * Limpia el registro global de mutaciones (llamar al inicio de cada sesión de fusión).
     */
    fun clearGlobalMutations() {
        globalMutationRegistry.clear()
    }

    /**
     * ══ APLICACIÓN DE MUTACIONES GLOBALES A DIRECTORIOS ══
     *
     * Recorre recursivamente todos los archivos JSON en [rootDirs] y aplica
     * el mapa global de mutaciones para actualizar internamente todas las
     * cadenas de texto correspondientes a rutas de recursos renombradas.
     *
     * Política Zero-Excess I/O: cada archivo JSON se lee UNA sola vez en
     * memoria (String), se analiza y reescribe si hubo reemplazos. No se
     * realizan múltiples operaciones de disco sobre el mismo archivo.
     *
     * @return Número total de archivos JSON reescritos con mutaciones aplicadas.
     */
    fun applyGlobalRewrites(rootDirs: List<File>): Int {
        if (globalMutationRegistry.isEmpty()) return 0
        val rewriteMap = globalMutationRegistry.toMap()
        var rewritten = 0

        for (rootDir in rootDirs) {
            if (!rootDir.isDirectory) continue
            val cachedFiles = DirIndexCache.index(rootDir).jsonFiles
            for (file in cachedFiles) {
                try {
                    // Zero-Excess I/O: leer UNA vez en memoria, analizar, escribir solo si cambió
                    val text = file.readText(StandardCharsets.UTF_8)
                    val json = JSONObject(text)
                    val changed = JsonValueRewriter.replaceValues(json, rewriteMap)
                    if (changed) {
                        OutputStreamWriter(FileOutputStream(file), StandardCharsets.UTF_8).use { writer ->
                            writer.write(json.toString())
                        }
                        rewritten++
                    }
                } catch (_: Exception) {
                    // JSONs malformados o binarios disfrazados de .json se ignoran silenciosamente
                }
            }
        }
        if (rewritten > 0) {
            PackForgeLog.d(TAG, "🔄 Global rewrites aplicados: $rewritten archivos actualizados con ${rewriteMap.size} mutaciones")
        }
        return rewritten
    }

    /**
     * Pre-planifica una fuente completa contra el registro y COMPROMITE los hashes.
     * @return mapa rutaAntigua → rutaNueva (incluye variantes sin extensión para
     *         imágenes). Vacío si la fuente no necesita renombres.
     */
    fun planAndCommit(sourceRoot: File): Map<String, String> {
        val renames = LinkedHashMap<String, String>()
        if (!sourceRoot.isDirectory) return renames

        // ⭐ OPTIMIZACIÓN: Usar DirIndexCache en lugar de walkTopDown()
        val cachedFiles = DirIndexCache.index(sourceRoot).allFiles
        for (file in cachedFiles) {
            val rel = file.relativeTo(sourceRoot).path.replace("\\", "/")
            if (!isEligible(rel)) continue

            val hash = try { md5(file) } catch (e: Exception) { continue }
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
