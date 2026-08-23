package com.packforge.app.domain.engine

import com.packforge.app.domain.model.ConflictSeverity
import com.packforge.app.util.PackForgeLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile

/**
 * ═══════════════════════════════════════════════════════════════════════
 * RESOLVEDOR DE DEPENDENCIAS DE ENTIDADES (Anti-"Mob Invisible")
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Fase DIRS (resolve): recorre cada entidad cliente del pack fusionado y
 * garantiza que sus referencias existan en el destino:
 *
 *   description.geometry            → identificador interno de un .geo.json
 *                                     (acepta formato string Y objeto {"default": …})
 *   description.textures{ruta}      → PNG en la ruta declarada (con validación
 *                                     de contención de sandbox)
 *   description.animations{id}      → id bajo "animations" en archivos de animación
 *   description.render_controllers  → ids del ARRAY (strings u objetos condicionales)
 *
 * Reglas de conflicto (basadas en CONTENIDO/hash, no en nombres de archivo):
 *  - Geometría ausente en destino → se localiza por identifier en los RP
 *    origen y se copia preservando su ruta relativa.
 *  - El pack YA contiene el id pero con hash distinto al requerido:
 *      · si alguna fuente coincide con el hash fusionado → se crean alias
 *        `_pf<hash6>` para cada variante restante y se registra conflicto
 *        HIGH (sin rewire ciego: la mayoría ya renderiza la variante del pack).
 *      · si NINGUNA fuente coincide (deepmerge mutó el archivo) → se restaura
 *        la variante canónica de la primera fuente para todo el pack.
 *  - Animaciones/RC sombreados → hermano `_pf<hash6>` (binding es por id).
 *
 * Fase ZIP (validateMerge): revalida el paquete FINAL leyendo las entradas
 * del .mcaddon y devuelve errores legibles si quedó alguna referencia rota.
 */
object EntityDependencyResolver {

    private const val TAG = "PackForge_DepResolver"
    private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "tga", "webp")
    private const val MAX_REPORTED = 30

    /** Candidato de fuente: texto ya leído UNA vez (evita relecturas) + hash. */
    private class Candidate(val root: File, val file: File, val hash: String, val text: String)

    /** Índice del pack fusionado construido en UNA pasada. */
    private class MergedIndex {
        val geoIds = mutableSetOf<String>()
        val geoHashById = mutableMapOf<String, String>()
        val geoPathById = mutableMapOf<String, String>()
        val animRcIds = mutableSetOf<String>()
    }

    // ── ÍNDICES POR CONTENIDO (una lectura por archivo) ───────────────────

    private fun indexGeometries(dirs: List<File>): Map<String, List<Candidate>> =
        indexJsonIds(dirs, { rel -> rel.endsWith(".geo.json", true) }) { _, json ->
            json.optJSONArray("minecraft:geometry")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.optJSONObject("description")
                        ?.optString("identifier")?.trim()?.takeIf { it.isNotBlank() }
                }
            }.orEmpty()
        }

    private fun indexAnimationIds(dirs: List<File>): Map<String, List<Candidate>> =
        indexJsonIds(dirs, { rel -> underSegment(rel, "animations") || underSegment(rel, "animation_controllers") }) { _, json ->
            buildList {
                json.optJSONObject("animations")?.keys()?.forEach { add(it.trim()) }
                json.optJSONObject("animation_controllers")?.keys()?.forEach { add(it.trim()) }
            }
        }

    private fun indexRenderControllerIds(dirs: List<File>): Map<String, List<Candidate>> =
        indexJsonIds(dirs, { rel -> underSegment(rel, "render_controllers") }) { _, json ->
            json.optJSONObject("render_controllers")?.keys()?.asSequence()
                ?.map { it.trim() }?.toList().orEmpty()
        }

    private fun underSegment(rel: String, segment: String): Boolean =
        rel.startsWith("$segment/") || rel.contains("/$segment/")

    private fun indexJsonIds(
        dirs: List<File>,
        pathMatcher: (String) -> Boolean,
        extractor: (File, JSONObject) -> List<String>
    ): Map<String, List<Candidate>> {
        val index = LinkedHashMap<String, MutableList<Candidate>>()
        for (root in dirs) {
            if (!root.isDirectory) continue
            root.walkTopDown().filter { it.isFile && it.extension.equals("json", true) }.forEach { file ->
                val rel = file.relativeTo(root).path.replace("\\", "/")
                if (!pathMatcher(rel)) return@forEach
                try {
                    val bytes = file.readBytes() // UNA sola lectura: parseo + hash desde el mismo buffer
                    val text = String(bytes, StandardCharsets.UTF_8)
                    val json = JSONObject(text)
                    val hash = md5(bytes)
                    extractor(root, json).forEach { id ->
                        if (id.isNotBlank()) {
                            index.getOrPut(id) { mutableListOf() }.add(Candidate(root, file, hash, text))
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        return index
    }

    private fun scanMerged(mergedRpDir: File): MergedIndex {
        val idx = MergedIndex()
        mergedRpDir.walkTopDown().filter { it.isFile && it.extension.equals("json", true) }.forEach { file ->
            val rel = file.relativeTo(mergedRpDir).path.replace("\\", "/")
            try {
                val json = JSONObject(file.readText(StandardCharsets.UTF_8))
                if (rel.endsWith(".geo.json", true)) {
                    json.optJSONArray("minecraft:geometry")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val id = arr.optJSONObject(i)?.optJSONObject("description")
                                ?.optString("identifier")?.trim()
                            if (!id.isNullOrBlank() && !idx.geoIds.contains(id)) {
                                idx.geoIds.add(id)
                                idx.geoHashById[id] = md5(file.readBytes())
                                idx.geoPathById[id] = rel
                            }
                        }
                    }
                }
                if (underSegment(rel, "animations") || underSegment(rel, "animation_controllers") ||
                    underSegment(rel, "render_controllers")
                ) {
                    json.optJSONObject("animations")?.keys()?.forEach { idx.animRcIds.add(it.trim()) }
                    json.optJSONObject("animation_controllers")?.keys()?.forEach { idx.animRcIds.add(it.trim()) }
                    json.optJSONObject("render_controllers")?.keys()?.forEach { idx.animRcIds.add(it.trim()) }
                }
            } catch (_: Exception) {}
        }
        return idx
    }

    // ── EXTRACTORES TOLERANTES A FORMATOS DE MOJANG ────────────────────────

    /** geometry puede ser string ("geometry.x") U objeto moderno {"default": "geometry.x"}. */
    private fun readGeometryId(desc: JSONObject): String? = when (val g = desc.opt("geometry")) {
        is String -> g.trim().takeIf { it.isNotEmpty() }
        is JSONObject -> {
            val def = g.optString("default").trim()
            (def.ifBlank {
                g.keys().asSequence().map { g.optString(it).trim() }
                    .firstOrNull { it.isNotEmpty() } ?: ""
            }).takeIf { it.isNotEmpty() }
        }
        else -> null
    }

    /** render_controllers es JSONArray (string u objeto condicional); objeto legacy tolerado. */
    private fun readRenderControllerIds(desc: JSONObject): List<String> = when (val rc = desc.opt("render_controllers")) {
        is JSONArray -> buildList {
            for (i in 0 until rc.length()) {
                when (val v = rc.opt(i)) {
                    is String -> add(v.trim())
                    is JSONObject -> v.keys().forEach { add(it.trim()) }
                }
            }
        }.filter { it.isNotEmpty() }
        is JSONObject -> rc.keys().asSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        else -> emptyList()
    }

    private fun readAnimationIds(desc: JSONObject): List<String> {
        val map = desc.optJSONObject("animations") ?: return emptyList()
        return map.keys().asSequence().mapNotNull { shortName ->
            map.optString(shortName).trim().takeIf { it.isNotEmpty() }
        }.toList()
    }

    // ── SANDBOX ────────────────────────────────────────────────────────────

    /** Resuelve rel dentro de base rechazando traversal (".."), absolutos y unidades de disco. */
    private fun safeResolve(base: File, rel: String): File? {
        val clean = rel.replace("\\", "/").trimStart('/')
        if (clean.isBlank()) return null
        if (clean.split('/').any { it == ".." }) return null
        if (clean.contains(':')) return null
        val f = File(base, clean)
        return if (f.canonicalFile.path.startsWith(base.canonicalFile.path + File.separator)) f else null
    }

    // ── FASE DIRS: RESOLUCIÓN + RECUPERACIÓN ──────────────────────────────

    /**
     * @return notas legibles para el reporte de fusión.
     */
    fun resolve(rpDirs: List<File>, mergedRpDir: File): List<String> {
        if (!mergedRpDir.isDirectory) return emptyList()

        val geoIndex = indexGeometries(rpDirs)
        val animIndex = indexAnimationIds(rpDirs)
        val rcIndex = indexRenderControllerIds(rpDirs)
        val merged = scanMerged(mergedRpDir)

        var fixedGeo = 0; var fixedTex = 0; var fixedAnim = 0; var fixedRc = 0
        val notes = mutableListOf<String>()
        val missing = mutableListOf<String>()

        // Índice basename→archivo construido UNA vez (lazy) para el fallback de texturas.
        val texByBasename by lazy {
            val map = HashMap<String, File>()
            for (root in rpDirs) {
                if (!root.isDirectory) continue
                root.walkTopDown().filter {
                    it.isFile && it.extension.lowercase(Locale.ROOT) in IMAGE_EXTS
                }.forEach { map.putIfAbsent(it.name.lowercase(Locale.ROOT), it) }
            }
            map
        }
        val texLookupMemo = HashMap<String, File?>()

        val entityRoot = File(mergedRpDir, "entity")
        if (!entityRoot.isDirectory) return listOf("Sin carpeta entity/ en el RP fusionado.")

        entityRoot.walkTopDown().filter { it.isFile && it.extension.equals("json", true) }.forEach { file ->
            try {
                val json = JSONObject(file.readText(StandardCharsets.UTF_8))
                val desc = json.optJSONObject("minecraft:client_entity")?.optJSONObject("description")
                    ?: return@forEach

                // ── 1) GEOMETRÍA (string u objeto {"default": …}) ──
                val geoId = readGeometryId(desc)
                if (geoId != null) {
                    val mergedHash = merged.geoHashById[geoId]
                    val candidates = geoIndex[geoId].orEmpty()
                    when {
                        candidates.isEmpty() ->
                            missing += "geometría '$geoId' requerida por ${file.name}"

                        mergedHash == null -> {
                            // Faltaba por completo: copiar preservando ruta relativa.
                            val cand = candidates.first()
                            safeResolve(mergedRpDir, cand.file.relativeTo(cand.root).path.replace("\\", "/"))
                                ?.let { dest ->
                                    dest.parentFile?.mkdirs()
                                    cand.file.copyTo(dest, overwrite = true)
                                    merged.geoIds.add(geoId)
                                    merged.geoHashById[geoId] = cand.hash
                                    merged.geoPathById[geoId] = dest.relativeTo(mergedRpDir).path.replace("\\", "/")
                                    fixedGeo++
                                    PackForgeLog.d(TAG, "🦴 Geometría recuperada: $geoId ← ${dest.name}")
                                }
                        }

                        candidates.any { it.hash == mergedHash } &&
                            candidates.map { it.hash }.distinct().size > 1 -> {
                            // El pack ya sirve una variante correcta; crear alias para las OTRAS
                            // variantes y registrar conflicto visible (sin rewire ciego).
                            candidates.filter { it.hash != mergedHash }.distinctBy { it.hash }
                                .forEach { cand ->
                                    val aliasId = "${geoId}_pf${cand.hash.substring(0, 6)}"
                                    if (!merged.geoIds.contains(aliasId)) {
                                        writeAliasedGeometry(cand, geoId, aliasId, mergedRpDir)
                                        merged.geoIds.add(aliasId)
                                        merged.geoHashById[aliasId] = cand.hash
                                        notes += "🔀 Geometría '$geoId': variante alternativa disponible como '$aliasId' (contenido distinto entre addons)"
                                        PackForgeLog.w(TAG, "🦴 Variante de geometría aliased: $geoId → $aliasId")
                                    }
                                }
                            ConflictRegistry.logConflict(
                                severity = ConflictSeverity.HIGH,
                                type = "GEOMETRY_VARIANT_CONFLICT",
                                file = file.name,
                                addon1 = "pack fusionado",
                                addon2 = "-",
                                description = "Geometría '$geoId' difiere entre addons; alias creados. Si algún mob se ve con modelo equivocado, rewirea su entidad al alias correspondiente."
                            )
                        }

                        candidates.none { it.hash == mergedHash } -> {
                            // El contenido fusionado no coincide con NINGUNA fuente
                            // (deepmerge lo mutó): restaurar la variante canónica.
                            val cand = candidates.first()
                            merged.geoPathById[geoId]?.let { relPath ->
                                safeResolve(mergedRpDir, relPath)?.let { dest ->
                                    OutputStreamWriter(FileOutputStream(dest), StandardCharsets.UTF_8).use {
                                        it.write(cand.text)
                                    }
                                    merged.geoHashById[geoId] = cand.hash
                                    fixedGeo++
                                    PackForgeLog.w(TAG, "🦴 Geometría '$geoId' mutada por fusión; restaurada desde ${cand.file.name}")
                                }
                            }
                        }

                        else -> Unit // id presente con hash coincidente: nada que hacer
                    }
                }

                // ── 2) TEXTURAS (contención de sandbox + índice basename + memo) ──
                desc.optJSONObject("textures")?.let { texMap ->
                    texMap.keys().forEach { key ->
                        val rawPath = texMap.optString(key)
                        if (rawPath.isBlank()) return@forEach

                        val cached = texLookupMemo[rawPath]
                        if (cached == FILE_INSECURE_SENTINEL) {
                            missing += "textura '$rawPath' (ruta insegura) requerida por ${file.name}"
                            return@forEach
                        }

                        val dest = safeResolve(mergedRpDir, rawPath)
                        if (dest == null) {
                            missing += "textura '$rawPath' (ruta insegura) requerida por ${file.name}"
                            texLookupMemo[rawPath] = FILE_INSECURE_SENTINEL
                            return@forEach
                        }
                        if (!dest.exists()) {
                            val source = resolveTextureSource(rpDirs, rawPath, texByBasename)
                            if (source != null) {
                                dest.parentFile?.mkdirs()
                                source.copyTo(dest, overwrite = true)
                                fixedTex++
                                PackForgeLog.d(TAG, "🖼️ Textura recuperada: $rawPath ← ${source.name}")
                            } else {
                                texLookupMemo[rawPath] = FILE_INSECURE_SENTINEL // miss conocido: no repetir lookup
                                missing += "textura '$rawPath' requerida por ${file.name}"
                            }
                        }
                    }
                }

                // ── 3) ANIMACIONES ──
                readAnimationIds(desc).forEach { animId ->
                    if (merged.animRcIds.contains(animId)) return@forEach
                    val candidates = animIndex[animId].orEmpty()
                    if (candidates.isEmpty()) {
                        missing += "animación '$animId' requerida por ${file.name}"
                    } else {
                        recoverById(candidates.first(), mergedRpDir, merged, animId)
                        fixedAnim++
                    }
                }

                // ── 4) RENDER CONTROLLERS (array u objeto legacy) ──
                readRenderControllerIds(desc).forEach { rcId ->
                    if (rcId.isBlank() || merged.animRcIds.contains(rcId)) return@forEach
                    val candidates = rcIndex[rcId].orEmpty()
                    if (candidates.isEmpty()) {
                        missing += "render controller '$rcId' requerido por ${file.name}"
                    } else {
                        recoverById(candidates.first(), mergedRpDir, merged, rcId)
                        fixedRc++
                    }
                }

                // Reescritura solo si el contenido cambió realmente (evita tocar intactos).
                val current = file.readText(StandardCharsets.UTF_8)
                val serialized = json.toString()
                if (current != serialized) {
                    OutputStreamWriter(FileOutputStream(file), StandardCharsets.UTF_8).use {
                        it.write(serialized)
                    }
                }
            } catch (e: Exception) {
                PackForgeLog.e(TAG, "Error resolviendo dependencias de ${file.name}: ${e.message}")
            }
        }

        missing.take(MAX_REPORTED).forEach { m ->
            PackForgeLog.e(TAG, "❌ Dependencia irrecuperable: $m")
            ConflictRegistry.logConflict(
                severity = ConflictSeverity.HIGH,
                type = "MISSING_DEPENDENCY",
                file = "entity/",
                addon1 = "pack fusionado",
                addon2 = "-",
                description = "Referencia rota tras fusión: $m"
            )
        }

        PackForgeLog.d(
            TAG,
            "=== Resumen dependencias === geo+$fixedGeo tex+$fixedTex anim+$fixedAnim rc+$fixedRc | faltantes=${missing.size}"
        )
        notes.add(0, "Geometrías recuperadas/restauradas: $fixedGeo | Texturas: $fixedTex | Animaciones: $fixedAnim | Render controllers: $fixedRc")
        if (missing.isNotEmpty()) notes.add("Referencias irrecuperables: ${missing.size} (ver conflictos MISSING_DEPENDENCY)")
        return notes
    }

    private val FILE_INSECURE_SENTINEL = File("__insecure__")

    /** Fuente para una textura: ruta exacta contenida → luego índice basename O(1). */
    private fun resolveTextureSource(
        rpDirs: List<File>,
        rawPath: String,
        texByBasename: Map<String, File>
    ): File? {
        for (root in rpDirs) {
            val exact = safeResolve(root, rawPath)
            if (exact != null && exact.isFile) return exact
        }
        val baseName = rawPath.substringAfterLast('/').lowercase(Locale.ROOT)
        return texByBasename[baseName]
    }

    /** Escribe variante alias del geo con identifier reescrito vía rewriter compartido
     *  (trim-aware: cubre ids con espacios accidentales en fuentes crudas). */
    private fun writeAliasedGeometry(cand: Candidate, oldId: String, newId: String, mergedRpDir: File) {
        try {
            val conflictedJson = JSONObject(cand.text)
            JsonValueRewriter.replaceValues(conflictedJson, mapOf(oldId.trim() to newId))
            val origRel = cand.file.relativeTo(cand.root).path.replace("\\", "/")
            val dot = origRel.lastIndexOf('.')
            val destRel = (if (dot > 0) origRel.substring(0, dot) else origRel) +
                "_pf${cand.hash.substring(0, 6)}.geo.json"
            val dest = safeResolve(mergedRpDir, destRel) ?: return
            dest.parentFile?.mkdirs()
            OutputStreamWriter(FileOutputStream(dest), StandardCharsets.UTF_8).use { it.write(conflictedJson.toString()) }
        } catch (e: Exception) {
            PackForgeLog.e(TAG, "No se pudo crear alias de geometría '${cand.file.name}': ${e.message}")
        }
    }

    /** Copia el archivo candidato (texto ya leído); si existe en destino usa hermano _pf. */
    private fun recoverById(cand: Candidate, mergedRpDir: File, merged: MergedIndex, id: String) {
        try {
            val rel = cand.file.relativeTo(cand.root).path.replace("\\", "/")
            val dot = rel.lastIndexOf('.')
            var destRel = rel
            var dest = safeResolve(mergedRpDir, destRel)
            if (dest != null && dest.exists()) {
                destRel = (if (dot > 0) rel.substring(0, dot) else rel) +
                    "_pf${cand.hash.substring(0, 6)}" + (if (dot > 0) rel.substring(dot) else "")
                dest = safeResolve(mergedRpDir, destRel)
            }
            if (dest == null) return
            dest.parentFile?.mkdirs()
            OutputStreamWriter(FileOutputStream(dest), StandardCharsets.UTF_8).use { it.write(cand.text) }

            // Registrar TODOS los ids que aporta este archivo.
            runCatching {
                val j = JSONObject(cand.text)
                j.optJSONObject("animations")?.keys()?.forEach { merged.animRcIds.add(it.trim()) }
                j.optJSONObject("animation_controllers")?.keys()?.forEach { merged.animRcIds.add(it.trim()) }
                j.optJSONObject("render_controllers")?.keys()?.forEach { merged.animRcIds.add(it.trim()) }
                j.optJSONArray("minecraft:geometry")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.optJSONObject("description")?.optString("identifier")
                            ?.trim()?.takeIf { it.isNotBlank() }?.let { merged.geoIds.add(it) }
                    }
                }
            }
            merged.animRcIds.add(id)
            PackForgeLog.d(TAG, "🔗 Dependencia recuperada '$id' ← ${dest.relativeTo(mergedRpDir).path}")
        } catch (e: Exception) {
            PackForgeLog.e(TAG, "No se pudo recuperar '$id': ${e.message}")
        }
    }

    // ── FASE ZIP: VALIDACIÓN DEL PAQUETE FINAL (validateMerge) ────────────

    /**
     * Abre el .mcaddon terminado y verifica que cada entidad cliente referencie
     * geometrías/texturas/anims/RCs que existan físicamente como entradas del ZIP.
     * @return líneas de error (vacío = válido).
     */
    fun validateMerge(outputPath: String): List<String> {
        val errors = mutableListOf<String>()
        try {
            ZipFile(File(outputPath)).use { zip ->
                val entries = zip.entries().toList()
                val entryNames = entries.map { it.name.replace("\\", "/") }
                val entrySet = HashSet(entryNames)
                val suffixMemo = HashMap<String, Boolean>() // memo por ruta única

                fun zipHasTexture(folderPrefix: String, path: String): Boolean {
                    suffixMemo[path]?.let { return it }
                    val result = entrySet.contains("$folderPrefix/$path") ||
                        entryNames.any { it.endsWith("/$path") }
                    suffixMemo[path] = result
                    return result
                }

                val geoIds = mutableSetOf<String>()
                val animRcIds = mutableSetOf<String>()
                entries.filter { it.name.endsWith(".geo.json", true) }.forEach { e ->
                    runCatching { JSONObject(zip.getInputStream(e).bufferedReader().readText()) }.getOrNull()
                        ?.let { j ->
                            j.optJSONArray("minecraft:geometry")?.let { arr ->
                                for (i in 0 until arr.length()) {
                                    arr.optJSONObject(i)?.optJSONObject("description")
                                        ?.optString("identifier")?.trim()?.let { geoIds.add(it) }
                                }
                            }
                        }
                }
                entries.filter { e ->
                    val n = e.name.lowercase(Locale.ROOT)
                    (underSegment(n, "animations") || underSegment(n, "animation_controllers") ||
                        underSegment(n, "render_controllers")) && n.endsWith(".json")
                }.forEach { e ->
                    runCatching { JSONObject(zip.getInputStream(e).bufferedReader().readText()) }.getOrNull()
                        ?.let { j ->
                            j.optJSONObject("animations")?.keys()?.forEach { animRcIds.add(it.trim()) }
                            j.optJSONObject("animation_controllers")?.keys()?.forEach { animRcIds.add(it.trim()) }
                            j.optJSONObject("render_controllers")?.keys()?.forEach { animRcIds.add(it.trim()) }
                        }
                }

                entries.filter { e ->
                    val n = e.name.replace("\\", "/")
                    (n.contains("/entity/") || n.contains("/entities/")) &&
                        n.endsWith(".json", true) && !n.endsWith(".geo.json", true)
                }.forEach { e ->
                    val entityPath = e.name.replace("\\", "/")
                    runCatching { JSONObject(zip.getInputStream(e).bufferedReader().readText()) }.onSuccess { j ->
                        val desc = j.optJSONObject("minecraft:client_entity")?.optJSONObject("description")
                            ?: return@onSuccess
                        val folderPrefix = entityPath.substringBefore('/', missingDelimiterValue = "")
                        val shortName = entityPath.substringAfterLast('/')

                        readGeometryId(desc)?.let { g ->
                            if (g !in geoIds) errors += "❌ [ZIP] $shortName: geometría '$g' no existe en el paquete"
                        }
                        desc.optJSONObject("textures")?.let { t ->
                            t.keys().forEach { k ->
                                val p = t.optString(k).replace("\\", "/").trimStart('/')
                                if (p.isNotBlank() && !zipHasTexture(folderPrefix, p)) {
                                    errors += "❌ [ZIP] $shortName: textura '$p' no está empaquetada"
                                }
                            }
                        }
                        readAnimationIds(desc).forEach { id ->
                            if (id !in animRcIds) errors += "❌ [ZIP] $shortName: animación '$id' ausente"
                        }
                        readRenderControllerIds(desc).forEach { rc ->
                            if (rc !in animRcIds) errors += "❌ [ZIP] $shortName: render controller '$rc' ausente"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            errors += "❌ [ZIP] No se pudo validar ${File(outputPath).name}: ${e.message}"
        }
        if (errors.isNotEmpty()) {
            PackForgeLog.e(TAG, "validateMerge: ${errors.size} referencia(s) rota(s) en el paquete final")
            errors.take(MAX_REPORTED).forEach { PackForgeLog.e(TAG, it) }
        } else {
            PackForgeLog.d(TAG, "✅ validateMerge: todas las referencias de entidades existen en el paquete")
        }
        return errors
    }

    // ── HELPERS ───────────────────────────────────────────────────────────

    private fun md5(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5")
        digest.update(bytes)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
