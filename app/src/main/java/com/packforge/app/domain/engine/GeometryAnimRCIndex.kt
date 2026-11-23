package com.packforge.app.domain.engine

import com.packforge.app.util.PackForgeLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Índice unificado de geometrías, animaciones y render controllers.
 *
 * Evita escanear los mismos archivos 3 veces (EntityDependencyResolver,
 * MergeReferenceValidator, BedrockCriticalFilesMerger).
 *
 * Se construye en UNA pasada usando DirIndexCache y clasifica por contenido
 * (duck typing), no por extensión de archivo ni ruta de directorio.
 */
object GeometryAnimRCIndex {

    private const val TAG = "GeometryAnimRCIndex"

    /**
     * ⭐ TICKET-CORE-009 — Solo estos ficheros pueden declarar lo que el índice busca.
     *
     * `build` parsea cada archivo con `LenientJson`, escrito en Kotlin puro; en
     * el runtime ART del teléfono va ~10-50x más lento que en el JVM de
     * escritorio. Antes se parseaban TODOS los `.json` del pack —bloques,
     * recetas, loot tables, partículas, textos, sonidos— cuando las claves que
     * el índice extrae (`minecraft:geometry`, `animations`,
     * `render_controllers`) solo pueden aparecer aquí.
     *
     * Medido con OriginsBE + ShulkerHead en JVM: el índice se llevaba
     * **9.949 ms de los 10,8 s** de la Fase 3D, y en el móvil del usuario esa
     * misma fase tardó **1020 s** (92 % de la fusión entera). Filtrar por
     * directorio elimina ~90 % del parseo sin cambiar el resultado: la
     * convención de Bedrock es que las definiciones viven en `models/`,
     * `animations/`, `animation_controllers/` y `render_controllers/`, y los
     * sufijos cubren los addons que las colocan en otro sitio.
     */
    private fun puedeDeclararRecursos(rel: String): Boolean {
        val r = rel.lowercase()
        if (r.contains("models/")) return true
        if (r.contains("animations/")) return true
        if (r.contains("animation_controllers/")) return true
        if (r.contains("render_controllers/")) return true
        // Convenciones de nombre: un .geo.json puede estar en cualquier carpeta.
        if (r.endsWith(".geo.json")) return true
        if (r.endsWith(".animation.json")) return true
        if (r.endsWith(".animation_controllers.json")) return true
        if (r.endsWith(".render_controllers.json")) return true
        return false
    }

    /** Entrada indexada: archivo + hash + texto ya leído. */
    data class IndexedFile(
        val root: File,
        val file: File,
        val relativePath: String,
        val hash: String,
        val text: String,
        val json: JSONObject
    )

    /** Resultado del índice para consumo por los resolvers. */
    data class IndexResult(
        /** geometry identifier → lista de candidatos (archivos fuente) */
        val geometryById: Map<String, List<IndexedFile>>,
        /** animation identifier → lista de candidatos */
        val animationById: Map<String, List<IndexedFile>>,
        /** render_controller identifier → lista de candidatos */
        val renderControllerById: Map<String, List<IndexedFile>>,
        /** geometry identifier → hash del archivo en el pack fusionado */
        val mergedGeometryHashById: Map<String, String>,
        /** geometry identifier → ruta relativa en el pack fusionado */
        val mergedGeometryPathById: Map<String, String>,
        /** animation identifiers presentes en el pack fusionado */
        val mergedAnimationIds: Set<String>,
        /** Todos los archivos .json indexados (para mergeGeometryFiles que necesita ruta relativa) */
        val allIndexedFiles: List<IndexedFile>
    )

    /**
     * Construye el índice unificado escaneando UNA sola vez todos los JSONs
     * de los directorios de origen (para fuentes) y del pack fusionado (para estado actual).
     */
    /**
     * Construye el índice unificado escaneando los JSONs de origen (para fuentes)
     * y del pack fusionado (para estado actual).
     *
     * ⭐ RENDIMIENTO (medido con ADB en móvil): este parseo era el cuello. En el
     * Xiaomi, la Fase 3D tardaba **439 s** procesando solo 15 entidades — todo el
     * tiempo se iba aquí, antes de la primera entidad — mientras que en PC eran
     * ~8 s. La causa no es I/O sino **CPU**: `LenientJson` está escrito en Kotlin
     * puro y el runtime ART lo ejecuta ~10-50× más lento que el JVM de escritorio.
     * El teléfono tiene 8 núcleos pero el bucle corría en uno solo.
     *
     * Se divide en dos fases:
     *   1. LEER + PARSEAR + hashear en paralelo (la parte cara, sin estado compartido).
     *   2. REGISTRAR en los mapas en SECuencia y en orden original, para que el
     *      resultado sea idéntico al anterior (los resolvers dependen del orden).
     */
    fun build(sourceDirs: List<File>, mergedRpDir: File? = null): IndexResult {
        val geometryById = LinkedHashMap<String, MutableList<IndexedFile>>()
        val animationById = LinkedHashMap<String, MutableList<IndexedFile>>()
        val renderControllerById = LinkedHashMap<String, MutableList<IndexedFile>>()
        val allFiles = mutableListOf<IndexedFile>()

        // ── Fase 1: recopilar archivos a parsear (origen + fusionado) ──
        val trabajos = ArrayList<Trabajo>()

        for (root in sourceDirs) {
            if (!root.isDirectory) continue
            for (file in DirIndexCache.index(root).jsonFiles) {
                val rel = file.relativeTo(root).path.replace("\\", "/")
                if (!puedeDeclararRecursos(rel)) continue
                trabajos.add(Trabajo(root, file, rel, false))
            }
        }
        val rpDir = mergedRpDir
        if (rpDir != null && rpDir.isDirectory) {
            for (file in DirIndexCache.index(rpDir).jsonFiles) {
                val rel = file.relativeTo(rpDir).path.replace("\\", "/")
                if (!puedeDeclararRecursos(rel)) continue
                trabajos.add(Trabajo(rpDir, file, rel, true))
            }
        }

        // Parsear en paralelo. MessageDigest NO es thread-safe: cada hilo crea el suyo.
        val resultados = arrayOfNulls<Parsed>(trabajos.size)
        val hilos = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(hilos)
        try {
            val siguiente = java.util.concurrent.atomic.AtomicInteger(0)
            val tareas = (1..hilos).map {
                java.util.concurrent.Callable<Unit> {
                    while (true) {
                        val i = siguiente.getAndIncrement()
                        if (i >= trabajos.size) break
                        resultados[i] = parsear(trabajos[i])
                    }
                }
            }
            pool.invokeAll(tareas)
        } finally {
            pool.shutdown()
        }

        // ── Fase 2: registrar en orden original (determinista) ──
        val mergedGeometryHashById = LinkedHashMap<String, String>()
        val mergedGeometryPathById = LinkedHashMap<String, String>()
        val mergedAnimationIds = LinkedHashSet<String>()

        for (p in resultados) {
            if (p == null) continue
            val t = p.trabajo
            if (t.esFusionado) {
                p.json.optJSONArray("minecraft:geometry")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val id = arr.optJSONObject(i)?.optJSONObject("description")
                            ?.optString("identifier")?.trim()
                        if (!id.isNullOrBlank() && !mergedGeometryHashById.contains(id)) {
                            mergedGeometryHashById[id] = p.hash
                            mergedGeometryPathById[id] = t.rel
                        }
                    }
                }
                p.json.optJSONObject("animations")?.keys()?.forEach { mergedAnimationIds.add(it.trim()) }
                p.json.optJSONObject("animation_controllers")?.keys()?.forEach { mergedAnimationIds.add(it.trim()) }
            } else {
                registrar(p.indexed, t.rel, geometryById, animationById, renderControllerById, allFiles)
            }
        }

        PackForgeLog.d(TAG, "Índice unificado: ${geometryById.size} geometrías, ${animationById.size} animaciones, ${renderControllerById.size} RCs, ${mergedGeometryHashById.size} geometrías fusionadas, ${mergedAnimationIds.size} animaciones fusionadas")

        return IndexResult(
            geometryById = geometryById.mapValues { (_, v) -> v.toList() },
            animationById = animationById.mapValues { (_, v) -> v.toList() },
            renderControllerById = renderControllerById.mapValues { (_, v) -> v.toList() },
            mergedGeometryHashById = mergedGeometryHashById,
            mergedGeometryPathById = mergedGeometryPathById,
            mergedAnimationIds = mergedAnimationIds,
            allIndexedFiles = allFiles
        )
    }

    /** Resultado intermedio del parseo paralelo. */
    private class Parsed(val trabajo: Trabajo, val hash: String, val json: JSONObject, val indexed: IndexedFile)

    /** Datos de un archivo a parsear (fase 1). */
    private class Trabajo(val root: File, val file: File, val rel: String, val esFusionado: Boolean)

    /** Lee, parsea y hashea UN archivo. Sin estado compartido: seguro en paralelo. */
    private fun parsear(t: Trabajo): Parsed? = try {
        // Un solo readBytes: se reutiliza para el hash y para el texto, en vez
        // de leer el archivo y volver a materializar sus bytes para el MD5.
        val bytes = t.file.readBytes()
        val text = String(bytes, StandardCharsets.UTF_8)
        // ⭐ VÍA RÁPIDA (TICKET-CORE-009): el parser estricto de org.json es
        // ~3.3x más rápido que `LenientJson` (medido: 0.90 vs 3.00 ms para un
        // .geo.json de 3.4 KB) y en ART la diferencia se multiplica. Se usa
        // cuando el archivo es JSON válido —lo normal— y se cae a `LenientJson`
        // solo cuando el estricto falla, que es justo el caso para el que
        // existe (comentarios, comas finales, homógrafos).
        //
        // Única diferencia posible: con CLAVES DUPLICADAS el orden de
        // precedencia puede variar respecto al parser laxo. No afecta a la
        // extracción de identificadores, que es lo único que se lee aquí.
        val parseado: JSONObject? = try {
            JSONObject(text)
        } catch (_: Exception) {
            LenientJson.parseObject(text)
        }
        val json = parseado?.let { JsonDeepMerger.cleanJsonObject(it) } ?: return null
        val hash = md5(bytes)
        Parsed(t, hash, json, IndexedFile(t.root, t.file, t.rel, hash, text, json))
    } catch (_: Exception) {
        null
    }

    /** Registra un archivo de ORIGEN ya parseado en los mapas de candidatos. */
    private fun registrar(
        indexed: IndexedFile,
        rel: String,
        geometryById: MutableMap<String, MutableList<IndexedFile>>,
        animationById: MutableMap<String, MutableList<IndexedFile>>,
        renderControllerById: MutableMap<String, MutableList<IndexedFile>>,
        allFiles: MutableList<IndexedFile>
    ) {
        allFiles.add(indexed)
        val json = indexed.json
        json.optJSONArray("minecraft:geometry")?.let { arr ->
            for (i in 0 until arr.length()) {
                val id = arr.optJSONObject(i)?.optJSONObject("description")
                    ?.optString("identifier")?.trim()
                if (!id.isNullOrBlank()) {
                    geometryById.getOrPut(id) { mutableListOf() }.add(indexed)
                }
            }
        }
        json.optJSONObject("animations")?.keys()?.forEach { key ->
            val id = key.trim()
            if (id.isNotBlank()) animationById.getOrPut(id) { mutableListOf() }.add(indexed)
        }
        json.optJSONObject("animation_controllers")?.keys()?.forEach { key ->
            val id = key.trim()
            if (id.isNotBlank()) animationById.getOrPut(id) { mutableListOf() }.add(indexed)
        }
        json.optJSONObject("render_controllers")?.keys()?.forEach { key ->
            val id = key.trim()
            if (id.isNotBlank()) renderControllerById.getOrPut(id) { mutableListOf() }.add(indexed)
        }
    }

    /**
     * Digest reutilizable por hilo: `MessageDigest.getInstance` es caro y
     * `MessageDigest` NO es thread-safe, así que uno por hilo y se resetea.
     */
    private val md5Digests = ThreadLocal.withInitial { MessageDigest.getInstance("MD5") }

    private fun md5(bytes: ByteArray): String {
        val digest = md5Digests.get() ?: MessageDigest.getInstance("MD5")
        digest.reset()
        digest.update(bytes)
        val out = digest.digest()
        // Hexadecimal a mano: `"%02x".format(it)` por byte disparaba 16
        // formateos de cadena por archivo.
        val hex = CharArray(out.size * 2)
        val digitos = "0123456789abcdef"
        for (i in out.indices) {
            val v = out[i].toInt() and 0xFF
            hex[i * 2] = digitos[v ushr 4]
            hex[i * 2 + 1] = digitos[v and 0x0F]
        }
        return String(hex)
    }
}