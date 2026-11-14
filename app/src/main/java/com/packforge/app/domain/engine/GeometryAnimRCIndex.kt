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
                trabajos.add(Trabajo(root, file, file.relativeTo(root).path.replace("\\", "/"), false))
            }
        }
        val hayFusionado = mergedRpDir != null && mergedRpDir.isDirectory
        if (hayFusionado) {
            for (file in DirIndexCache.index(mergedRpDir!!).jsonFiles) {
                trabajos.add(Trabajo(mergedRpDir!!, file, file.relativeTo(mergedRpDir).path.replace("\\", "/"), true))
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
        val text = t.file.readText(StandardCharsets.UTF_8)
        val json = LenientJson.parseObject(text)
            ?.let { JsonDeepMerger.cleanJsonObject(it) }
            ?: return null
        val hash = md5(text.toByteArray(StandardCharsets.UTF_8))
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

    private fun md5(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5")
        digest.update(bytes)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}