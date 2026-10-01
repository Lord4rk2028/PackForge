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
 * ══ REGISTRO DE MUTACIONES POR FUENTE ══
 * Cuando fastMergeAllSources detecta una colisión física y renombra un
 * archivo (ej. `textures/entity/steve.png` → `textures/entity/steve_pf1.png`),
 * el par (original → mutada) se registra ASOCIADO A LA FUENTE que lo produjo.
 *
 * ⚠️ Por qué POR FUENTE y no como mapa global plano: con 3+ addons que colisionan
 * en la misma ruta, un mapa global acumularía primero `steve.png → steve_pf1.png`
 * y después `steve.png → steve_pf2.png`, MACHACANDO el primer par. La reescritura
 * global posterior apuntaría entonces las referencias del 2º addon al archivo
 * del 3º (referencias cruzadas + archivos huérfanos). El registro por fuente
 * conserva la identidad de cada addon y permite reescribir sus JSONs sin ambigüedad.
 *
 * El renombrado físico y la reescritura de referencias quedan acoplados: el mismo
 * mapa `fileRenames` que decide el nombre en disco genera las variantes de
 * referencia (`sin extensión`, `sin prefijo textures/`) que Bedrock usa en
 * terrain_texture.json, item_texture.json y description.textures.
 */
class ResourcePathRegistry {

    /** ruta -> hash comprometido (primer addon que la usa gana la ruta canónica). */
    private val committed = LinkedHashMap<String, String>()

    /** Historial legible para el reporte final. */
    val aliasLog = mutableListOf<String>()

    /**
     * ══ REGISTRO DE MUTACIONES POR FUENTE ══
     *
     * Clave: ruta absoluta del directorio fuente (addon) que origina el renombrado.
     * Valor: mapa rutaOriginal → rutaMutada (solo rutas físicas, sin variantes).
     *
     * Aislar por fuente es lo que hace CORRECTA la reescritura: sin este
     * aislamiento, N colisiones sobre la misma ruta colapsarían en una sola
     * entrada y las referencias de un addon apuntarían al archivo de otro.
     */
    private val sourceMutations = LinkedHashMap<String, MutableMap<String, String>>()

    /**
     * Registra que [sourceRoot] renombra [originalRelPath] a [mutatedRelPath]
     * en el destino por colisión física.
     *
     * @param sourceRoot directorio fuente (addon) dueño del renombrado
     * @param originalRelPath ruta relativa original (ej: "textures/entity/steve.png")
     * @param mutatedRelPath ruta efectiva tras el renombre
     */
    fun registerSourceMutation(
        sourceRoot: File,
        originalRelPath: String,
        mutatedRelPath: String
    ) {
        if (originalRelPath == mutatedRelPath) return
        val map = sourceMutations.getOrPut(sourceRoot.absolutePath) { LinkedHashMap() }
        map[originalRelPath] = mutatedRelPath
        PackForgeLog.d(TAG, "📝 Mutación[${sourceRoot.name}]: $originalRelPath → $mutatedRelPath")
    }

    /** Mapa de mutaciones de una fuente concreta (vacío si no renombró nada). */
    fun mutationsFor(sourceRoot: File): Map<String, String> =
        sourceMutations[sourceRoot.absolutePath]?.toMap() ?: emptyMap()

    /** Mapa de mutaciones de todas las fuentes, aplanado (uso externo/reportes). */
    fun getGlobalRewriteMap(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        sourceMutations.values.forEach { out.putAll(it) }
        return out
    }

    /** Indica si alguna fuente registró mutaciones. */
    fun hasGlobalMutations(): Boolean = sourceMutations.values.any { it.isNotEmpty() }

    /** Número total de archivos renombrados en la sesión. */
    fun totalMutations(): Int = sourceMutations.values.sumOf { it.size }

    /** Limpia el registro de mutaciones (llamar al inicio de cada sesión de fusión). */
    fun clearGlobalMutations() {
        sourceMutations.clear()
    }

    /**
     * ══ REESCRITURA DE REFERENCIAS POR FUENTE ══
     *
     * Para cada fuente con mutaciones, recorre sus JSON UNA sola vez en memoria,
     * aplica [JsonValueRewriter.replaceValues] con todas las variantes de
     * referencia de SUS PROPIOS renombres y escribe solo si el contenido cambió.
     *
     * Se ejecuta ANTES de copiar la fuente al destino, de modo que los JSONs
     * llegan ya coherentes con los nombres físicos mutados y las fases
     * posteriores (merge de críticos, validación) heredan esa coherencia.
     *
     * Política Zero-Excess I/O: una lectura por archivo, escritura condicional,
     * volcado con OutputStreamWriter + StandardCharsets.UTF_8.
     *
     * @return número de archivos JSON reescritos.
     */
    fun applySourceRewrites(sourceRoots: List<File>): Int {
        if (sourceMutations.isEmpty()) return 0
        var rewritten = 0
        // Caché LOCAL de esta pasada: evita releer cada JSON una vez por clave,
        // y se libera al terminar (no arrastra memoria entre fusiones).
        val cache = HashMap<String, String>()

        for (root in sourceRoots) {
            val renames = sourceMutations[root.absolutePath] ?: continue
            if (renames.isEmpty() || !root.isDirectory) continue

            val rewriteMap = buildRewriteVariants(renames)
            for (file in DirIndexCache.index(root).jsonFiles) {
                // Filtro barato: si el texto no contiene ninguna clave, ni se parsea.
                val text = readCached(cache, file)
                if (text.isEmpty() || !rewriteMap.keys.any { text.contains(it) }) continue
                if (rewriteJson(file, text, rewriteMap)) rewritten++
            }
        }
        cache.clear()

        if (rewritten > 0) {
            PackForgeLog.d(
                TAG,
                "🔄 Referencias reescritas: $rewritten JSONs (${totalMutations()} mutaciones)"
            )
        }
        return rewritten
    }

    /**
     * Expande cada ruta física mutada a todas las formas en que Bedrock puede
     * referenciarla:
     *   1. ruta completa con extensión → "textures/entity/steve.png"
     *   2. ruta completa sin extensión → "textures/entity/steve"
     *   3. relativa a textures/ con ext → "entity/steve.png"
     *   4. relativa a textures/ sin ext → "entity/steve"
     *
     * (3) y (4) son necesarias porque terrain_texture.json e item_texture.json
     * referencian el atlas con rutas relativas a `textures/`. Solo se generan
     * para imágenes: en el resto de binarios la forma con extensión es la única
     * referenciada y `lastIndexOf('.')` sin cambio de directorio es válido.
     */
    private fun buildRewriteVariants(renames: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>(renames.size * 4)
        for ((original, mutated) in renames) {
            out[original] = mutated

            val oSlash = original.lastIndexOf('/')
            val oDot = original.lastIndexOf('.')
            // El punto debe estar en el NOMBRE del archivo, no en un directorio.
            if (oDot <= oSlash + 1) continue
            if (original.substring(oDot + 1).lowercase(Locale.ROOT) !in IMAGE_EXTS) continue

            val mDot = mutated.lastIndexOf('.')
            if (mDot <= 0) continue

            // (2) Sin extensión
            out[original.substring(0, oDot)] = mutated.substring(0, mDot)

            // (3)(4) Relativas a textures/
            val oName = original.substring(oSlash + 1)
            val mName = mutated.substring(mutated.lastIndexOf('/', mDot) + 1)
            out[oName] = mName
            val oNameDot = oName.lastIndexOf('.')
            if (oNameDot > 0) out[oName.substring(0, oNameDot)] = mName.substring(0, mName.lastIndexOf('.'))
        }
        return out
    }

    /** Extensiones para las que tiene sentido generar variantes de referencia. */
    private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "tga", "webp", "bmp", "gif")

    /** Lee el texto una sola vez por archivo (caché local a esta pasada). */
    private fun readCached(cache: HashMap<String, String>, file: File): String =
        cache.getOrPut(file.absolutePath) {
            try { file.readText(StandardCharsets.UTF_8) } catch (_: Exception) { "" }
        }

    /** Reescribe un JSON. Devuelve true solo si el contenido cambió en disco. */
    private fun rewriteJson(file: File, text: String, rewriteMap: Map<String, String>): Boolean {
        return try {
            val json = JSONObject(text)
            if (!JsonValueRewriter.replaceValues(json, rewriteMap)) return false
            OutputStreamWriter(FileOutputStream(file), StandardCharsets.UTF_8).use { writer ->
                writer.write(json.toString())
            }
            true
        } catch (_: Exception) {
            // JSONs malformados o binarios disfrazados de .json se ignoran.
            false
        }
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
