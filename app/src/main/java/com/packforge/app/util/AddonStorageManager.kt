package com.packforge.app.util

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.packforge.app.data.PackForgeDatabase
import com.packforge.app.domain.model.Addon
import java.io.File
import java.security.MessageDigest

/**
 * Resumen del espacio que PackForge ocupa en almacenamiento interno.
 *
 * Todos los tamaños están en bytes.
 */
data class StorageSnapshot(
    val addonsBytes: Long = 0L,
    val addonsCount: Int = 0,
    val exportsBytes: Long = 0L,
    val exportsCount: Int = 0,
    val modpackExportsBytes: Long = 0L,
    val modpackExportsCount: Int = 0,
    val reportsBytes: Long = 0L,
    val reportsCount: Int = 0,
    val iconsBytes: Long = 0L,
    val iconsCount: Int = 0
) {
    val totalBytes: Long
        get() = addonsBytes + exportsBytes + modpackExportsBytes + reportsBytes + iconsBytes

    val totalFiles: Int
        get() = addonsCount + exportsCount + modpackExportsCount + reportsCount + iconsCount
}

/** Resultado de una pasada de limpieza. */
data class CleanupReport(
    val duplicatesRemoved: Int = 0,
    val duplicatesBytes: Long = 0L,
    val orphansRemoved: Int = 0,
    val orphansBytes: Long = 0L,
    val artifactsRemoved: Int = 0,
    val artifactsBytes: Long = 0L,
    val referencesRewritten: Int = 0
) {
    val totalFilesRemoved: Int
        get() = duplicatesRemoved + orphansRemoved + artifactsRemoved

    val totalBytesFreed: Long
        get() = duplicatesBytes + orphansBytes + artifactsBytes

    val changed: Boolean
        get() = totalFilesRemoved > 0
}

/**
 * Gestor del almacenamiento interno de PackForge.
 *
 * Problema que resuelve: [com.packforge.app.domain.engine.AddonParser] guardaba una
 * copia completa del addon por CADA importación, con un UUID nuevo en el nombre. Como
 * nada borraba esas copias (ni al quitar un addon del editor, ni al reemplazarlo por una
 * actualización), el directorio `files/addons` acumulaba duplicados byte a byte sin
 * límite: 270 archivos y 2.2 GB para 8 modpacks, de los que ~73 % eran copias repetidas.
 *
 * Estrategia:
 *  - [findIdenticalAddon] permite reutilizar un archivo ya guardado en vez de copiarlo otra vez.
 *  - [removeOrphanAddons] borra lo que ya no referencia ningún modpack (barato: sin hashing).
 *  - [deduplicateAddons] elimina copias idénticas reescribiendo antes las referencias de la BD
 *    (caro: hay que leer los archivos, se ejecuta una sola vez).
 *  - [pruneOldArtifacts] limita los .mcaddon/fusion_report internos, que también se copian a Descargas.
 *
 * Regla de seguridad: NUNCA se borra un archivo antes de reescribir las referencias que
 * apuntan a él, y nunca se borra nada fuera de `filesDir`.
 */
object AddonStorageManager {

    private const val TAG = "AddonStorage"

    /** Días que se conservan los artefactos internos (exportaciones e informes). */
    const val ARTIFACT_KEEP_DAYS = 3

    /** Artefactos más recientes que se conservan siempre, aunque superen la antigüedad. */
    const val ARTIFACT_KEEP_NEWEST = 2

    private val gson = Gson()
    private val addonListType = object : TypeToken<List<Addon>>() {}.type

    // ─────────────────────────────── Directorios ───────────────────────────────

    fun addonsDir(context: Context) = File(context.filesDir, "addons")
    fun exportsDir(context: Context) = File(context.filesDir, "exports")
    fun modpackExportsDir(context: Context) = File(context.filesDir, "modpack_exports")
    fun reportsDir(context: Context) = File(context.filesDir, "reports")
    fun addonIconsDir(context: Context) = File(context.filesDir, "addon_icons")
    fun modpackIconsDir(context: Context) = File(context.filesDir, "modpack_icons")

    // ─────────────────────────────── Medición ───────────────────────────────

    fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        dir.walkBottomUp().forEach { if (it.isFile) total += it.length() }
        return total
    }

    fun countFiles(dir: File): Int {
        if (!dir.exists()) return 0
        var count = 0
        dir.walkBottomUp().forEach { if (it.isFile) count++ }
        return count
    }

    fun snapshot(context: Context): StorageSnapshot {
        val addons = addonsDir(context)
        val exports = exportsDir(context)
        val modpackExports = modpackExportsDir(context)
        val reports = reportsDir(context)
        val icons = addonIconsDir(context)
        val modpackIcons = modpackIconsDir(context)
        return StorageSnapshot(
            addonsBytes = dirSize(addons),
            addonsCount = countFiles(addons),
            exportsBytes = dirSize(exports),
            exportsCount = countFiles(exports),
            modpackExportsBytes = dirSize(modpackExports),
            modpackExportsCount = countFiles(modpackExports),
            reportsBytes = dirSize(reports),
            reportsCount = countFiles(reports),
            iconsBytes = dirSize(icons) + dirSize(modpackIcons),
            iconsCount = countFiles(icons) + countFiles(modpackIcons)
        )
    }

    // ─────────────────────────────── Hashing ───────────────────────────────

    fun sha256(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Throwable) {
        null
    }

    // ─────────────────────── Deduplicación en importación ───────────────────────

    /**
     * Busca un addon ya guardado cuyo contenido sea idéntico al que se acaba de leer.
     *
     * Se filtra primero por nombre original y tamaño (comparaciones gratis) y solo se
     * calcula el hash de los candidatos que coinciden en ambos. Tras una limpieza queda
     * como mucho un candidato por contenido, así que en la práctica se hashea un archivo.
     *
     * @return el archivo reutilizable, o `null` si hay que guardar una copia nueva.
     */
    fun findIdenticalAddon(dir: File, fileName: String, size: Long, hash: String): File? {
        val suffix = "_$fileName"
        // ⚠️ SE EXCLUYEN LOS ARCHIVOS ".incoming_": el archivo que se acaba de
        // escribir para esta misma importación también termina en "_$fileName",
        // tiene el mismo tamaño y el mismo SHA-256, así que se encontraba A SÍ
        // MISMO como "copia ya guardada". Entonces el llamador borraba
        // `incoming` (creyendo que era un duplicado) y quedaba apuntando a un
        // archivo RECIÉN BORRADO: la extracción fallaba, `parseFromUri`
        // devolvía null y la importación no hacía nada, sin ningún error.
        //
        // Afectaba a TODA importación de un addon que aún no estuviera guardado
        // (el caso normal): archivos del dispositivo, compartir desde terceros,
        // abrir con PackForge y las descargas de las 3 fuentes web.
        val candidates = dir.listFiles { f ->
            f.isFile &&
                !f.name.startsWith(INCOMING_PREFIX) &&
                f.name.endsWith(suffix)
        } ?: return null
        for (candidate in candidates) {
            if (candidate.length() != size) continue
            if (candidate.name.length <= suffix.length) continue
            if (sha256(candidate) == hash) return candidate
        }
        return null
    }

    // ─────────────────────────────── Referencias ───────────────────────────────

    /**
     * Rutas de archivos de addon referenciadas por TODOS los modpacks guardados.
     *
     * Se leen las filas completas (con `addonsJson`), por eso se llama solo desde IO.
     */
    suspend fun referencedAddonPaths(db: PackForgeDatabase): Set<String> {
        val referenced = mutableSetOf<String>()
        val packs = try {
            db.savedModpackDao().getAllFull()
        } catch (_: Throwable) {
            return referenced
        }
        for (pack in packs) {
            for (addon in decodeAddons(pack.addonsJson)) {
                if (addon.sourceFilePath.isNotBlank()) {
                    referenced.add(absolutePathOf(addon.sourceFilePath))
                }
            }
        }
        return referenced
    }

    private fun absolutePathOf(path: String): String = try {
        File(path).absolutePath
    } catch (_: Throwable) {
        path
    }

    private fun decodeAddons(json: String): List<Addon> {
        if (json.isBlank()) return emptyList()
        return try {
            gson.fromJson<List<Addon>>(json, addonListType) ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** ¿Sigue algún modpack (o la sesión abierta) usando este archivo? */
    suspend fun isPathStillReferenced(
        db: PackForgeDatabase?,
        path: String,
        sessionPaths: Collection<String>
    ): Boolean {
        if (path.isBlank()) return false
        val absolute = absolutePathOf(path)
        for (sessionPath in sessionPaths) {
            if (sessionPath.isNotBlank() && absolutePathOf(sessionPath) == absolute) return true
        }
        val database = db ?: return false
        return referencedAddonPaths(database).contains(absolute)
    }

    /**
     * Borra el archivo de un addon solo si ya nadie lo referencia.
     *
     * Antes de borrar comprueba dos cosas: que la ruta esté dentro de `filesDir`
     * (nunca tocar nada de fuera de la app) y que no quede ninguna referencia.
     * Si hay dudas no borra: el mantenimiento lo recogerá más adelante.
     */
    suspend fun deleteAddonFileIfUnreferenced(
        context: Context,
        db: PackForgeDatabase?,
        path: String,
        sessionPaths: Collection<String> = emptyList()
    ): Boolean {
        if (path.isBlank()) return false
        val file = try {
            File(path)
        } catch (_: Throwable) {
            return false
        }
        if (!file.isFile) return false
        // Seguridad: solo se borra dentro del almacenamiento privado de la app.
        if (!file.absolutePath.startsWith(context.filesDir.absolutePath)) return false
        if (isPathStillReferenced(db, path, sessionPaths)) return false
        val deleted = runCatching { file.delete() }.getOrDefault(false)
        if (deleted) PackForgeLog.d(TAG, "Addon liberado: ${file.name}")
        return deleted
    }

    // ─────────────────────────────── Limpieza barata ───────────────────────────────

    /**
     * Borra los addons que ya no referencia ningún modpack.
     *
     * No calcula hashes: solo compara rutas, así que es barato y se puede ejecutar en
     * cada arranque. Es lo que evita que la basura se acumule entre limpiezas profundas.
     */
    suspend fun removeOrphanAddons(
        context: Context,
        db: PackForgeDatabase,
        keepExtra: Collection<String> = emptyList()
    ): Pair<Int, Long> {
        val dir = addonsDir(context)
        val files = dir.listFiles { f -> f.isFile } ?: return 0 to 0L
        if (files.isEmpty()) return 0 to 0L

        val referenced = referencedAddonPaths(db).toMutableSet()
        for (extra in keepExtra) {
            if (extra.isNotBlank()) referenced.add(absolutePathOf(extra))
        }

        var count = 0
        var bytes = 0L
        for (file in files) {
            // Los archivos temporales de importación interrumpida también se recogen.
            val isStaleTemp = file.name.startsWith(INCOMING_PREFIX)
            if (!isStaleTemp && referenced.contains(file.absolutePath)) continue
            val length = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) {
                count++
                bytes += length
            }
        }
        if (count > 0) PackForgeLog.d(TAG, "Huérfanos borrados: $count ($bytes bytes)")
        return count to bytes
    }

    /** Prefijo de los archivos en curso de importación (se limpian si quedan colgados). */
    const val INCOMING_PREFIX = ".incoming_"

    /**
     * Limita las exportaciones e informes guardados internamente.
     *
     * Son copias de trabajo: el resultado final se copia a Descargas, así que conservar
     * meses de versiones intermedias era el segundo mayor desperdicio (≈460 MB).
     */
    fun pruneOldArtifacts(
        context: Context,
        olderThanDays: Int = ARTIFACT_KEEP_DAYS,
        keepNewest: Int = ARTIFACT_KEEP_NEWEST
    ): Pair<Int, Long> {
        val cutoff = System.currentTimeMillis() - olderThanDays * 24L * 60L * 60L * 1000L
        var count = 0
        var bytes = 0L
        val dirs = listOf(exportsDir(context), modpackExportsDir(context), reportsDir(context))
        for (dir in dirs) {
            val files = dir.listFiles { f -> f.isFile }?.sortedByDescending { it.lastModified() } ?: continue
            for (file in files.drop(keepNewest)) {
                if (file.lastModified() >= cutoff) continue
                val length = file.length()
                if (runCatching { file.delete() }.getOrDefault(false)) {
                    count++
                    bytes += length
                }
            }
        }
        if (count > 0) PackForgeLog.d(TAG, "Artefactos antiguos borrados: $count ($bytes bytes)")
        return count to bytes
    }

    // ─────────────────────────────── Deduplicación profunda ───────────────────────────────

    /**
     * Elimina las copias idénticas de addons y reescribe las referencias de los modpacks
     * para que apunten al archivo que se conserva.
     *
     * Es costoso (hay que leer los archivos para compararlos), así que se ejecuta una sola
     * vez. Orden obligatorio: primero reescribir la BD, después borrar. Si se borrara antes,
     * los modpacks quedarían apuntando a archivos inexistentes.
     *
     * Optimización: dos archivos solo pueden ser idénticos si pesan lo mismo, así que se
     * agrupa por tamaño y solo se hashean los grupos con más de un archivo.
     */
    suspend fun deduplicateAddons(
        context: Context,
        db: PackForgeDatabase,
        keepExtra: Collection<String> = emptyList()
    ): CleanupReport {
        val dir = addonsDir(context)
        val files = dir.listFiles { f -> f.isFile }?.filter { !it.name.startsWith(INCOMING_PREFIX) }
            ?: return CleanupReport()
        if (files.size < 2) return CleanupReport()

        val bySize = files.groupBy { it.length() }
        val remap = mutableMapOf<String, String>()

        for (group in bySize.values) {
            if (group.size < 2) continue
            val byHash = mutableMapOf<String, MutableList<File>>()
            for (file in group) {
                val hash = sha256(file) ?: continue
                val bucket = byHash.getOrPut(hash) { mutableListOf() }
                bucket.add(file)
            }
            for (duplicates in byHash.values) {
                if (duplicates.size < 2) continue
                // La copia más reciente se conserva: las importaciones nuevas sustituyen a las viejas.
                val canonical = duplicates.maxByOrNull { it.lastModified() } ?: continue
                for (duplicate in duplicates) {
                    if (duplicate.absolutePath == canonical.absolutePath) continue
                    remap[duplicate.absolutePath] = canonical.absolutePath
                }
            }
        }

        if (remap.isEmpty()) return CleanupReport()

        // 1) Reescribir referencias ANTES de borrar nada.
        val rewritten = rewriteReferences(db, remap)

        // 2) Borrar los duplicados, que ya no referencia nadie.
        var count = 0
        var bytes = 0L
        val keep = keepExtra.map { absolutePathOf(it) }.toSet()
        for (path in remap.keys) {
            if (path in keep) continue
            val file = File(path)
            val length = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) {
                count++
                bytes += length
            }
        }
        PackForgeLog.d(
            TAG,
            "Deduplicación: $count copias borradas ($bytes bytes), $rewritten modpacks actualizados"
        )
        return CleanupReport(
            duplicatesRemoved = count,
            duplicatesBytes = bytes,
            referencesRewritten = rewritten
        )
    }

    /** Reescribe `sourceFilePath` en todos los modpacks afectados por [remap]. */
    private suspend fun rewriteReferences(db: PackForgeDatabase, remap: Map<String, String>): Int {
        if (remap.isEmpty()) return 0
        val packs = try {
            db.savedModpackDao().getAllFull()
        } catch (_: Throwable) {
            return 0
        }
        var changed = 0
        for (pack in packs) {
            if (pack.addonsJson.isBlank()) continue
            // Evita deserializar/serializar filas que no contienen ninguna ruta afectada.
            var containsAny = false
            for (oldPath in remap.keys) {
                if (pack.addonsJson.contains(oldPath)) {
                    containsAny = true
                    break
                }
            }
            if (!containsAny) continue

            val addons = decodeAddons(pack.addonsJson)
            if (addons.isEmpty()) continue
            var touched = false
            val updated = addons.map { addon ->
                val target = remap[addon.sourceFilePath]
                if (target != null) {
                    touched = true
                    addon.copy(sourceFilePath = target)
                } else {
                    addon
                }
            }
            if (!touched) continue
            try {
                db.savedModpackDao().insert(pack.copy(addonsJson = gson.toJson(updated)))
                changed++
            } catch (_: Throwable) {
                // Si una fila no se puede reescribir, sus duplicados no se borrarán
                // (la referencia sigue apuntando al original), así que no hay pérdida.
            }
        }
        return changed
    }

    // ─────────────────────────────── Mantenimiento completo ───────────────────────────────

    /**
     * Limpieza barata: huérfanos + artefactos antiguos. Pensada para cada arranque.
     */
    suspend fun runQuickCleanup(
        context: Context,
        db: PackForgeDatabase,
        sessionPaths: Collection<String> = emptyList()
    ): CleanupReport {
        val (orphanCount, orphanBytes) = removeOrphanAddons(context, db, sessionPaths)
        val (artifactCount, artifactBytes) = pruneOldArtifacts(context)
        return CleanupReport(
            orphansRemoved = orphanCount,
            orphansBytes = orphanBytes,
            artifactsRemoved = artifactCount,
            artifactsBytes = artifactBytes
        )
    }

    /**
     * Limpieza completa: deduplica además las copias idénticas.
     *
     * Costosa (lee los archivos), por eso se ejecuta una sola vez y desde IO.
     */
    suspend fun runDeepCleanup(
        context: Context,
        db: PackForgeDatabase,
        sessionPaths: Collection<String> = emptyList()
    ): CleanupReport {
        val dedup = deduplicateAddons(context, db, sessionPaths)
        val quick = runQuickCleanup(context, db, sessionPaths)
        return CleanupReport(
            duplicatesRemoved = dedup.duplicatesRemoved,
            duplicatesBytes = dedup.duplicatesBytes,
            orphansRemoved = quick.orphansRemoved,
            orphansBytes = quick.orphansBytes,
            artifactsRemoved = quick.artifactsRemoved,
            artifactsBytes = quick.artifactsBytes,
            referencesRewritten = dedup.referencesRewritten
        )
    }
}
