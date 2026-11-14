package com.packforge.app.domain.engine

import com.packforge.app.domain.model.ConflictSeverity
import com.packforge.app.util.PackForgeLog
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.supervisorScope
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
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

    // ⭐ OPTIMIZACIÓN: Cache para safeResolve - evita resoluciones redundantes
    private val safeResolveCache = ConcurrentHashMap<String, File?>()

    // ⭐ USA GeometryAnimRCIndex.IndexedFile en lugar de Candidate propia
    typealias Candidate = GeometryAnimRCIndex.IndexedFile

    /** Índice del pack fusionado construido en UNA pasada. */
    private class MergedIndex {
        val geoIds = mutableSetOf<String>()
        val geoHashById = mutableMapOf<String, String>()
        val geoPathById = mutableMapOf<String, String>()
        val animRcIds = mutableSetOf<String>()
    }

    // ⭐ AHORA USA GeometryAnimRCIndex (índice unificado)
    // Las funciones indexGeometries, indexAnimationIds, indexRenderControllerIds,
    // underSegment, indexJsonIds, scanMerged se eliminaron — están en GeometryAnimRCIndex.

    // ── EXTRACTORES TOLERANTES A FORMATOS DE MOJANG ────────────────────────

    /**
     * geometry puede ser string ("geometry.x") U objeto moderno
     * {"default": "geometry.x"} (con mapas de huesos o Molang dentro, que NO son
     * referencias). Todo el criterio vive en [BedrockRefs] para que ningún
     * validador vuelva a inventarse el suyo.
     */
    private fun readGeometryId(desc: JSONObject): String? =
        BedrockRefs.geometryIds(desc.opt("geometry")).firstOrNull()

    /** render_controllers es JSONArray (string u objeto condicional); objeto legacy tolerado. */
    private fun readRenderControllerIds(desc: JSONObject): List<String> =
        BedrockRefs.renderControllerIds(desc.opt("render_controllers"))

    private fun readAnimationIds(desc: JSONObject): List<String> =
        BedrockRefs.animationIds(desc)

    // ── SANDBOX ────────────────────────────────────────────────────────────

    /**
     * Resuelve una ruta de referencia Bedrock contra [base] respetando el
     * sandbox (sin escapes fuera del directorio ni rutas absolutas).
     *
     * Bedrock referencia las texturas SIN extensión ("textures/entity/steve")
     * mientras el archivo real es "textures/entity/steve.png". Por eso, si la
     * ruta literal no existe, se prueban las extensiones de imagen soportadas
     * antes de darla por ausente (mismo criterio que validateMerge, pero aquí en
     * disco en lugar del ZIP).
     *
     * @return el File real que existe, o la ruta literal si está dentro del
     *   sandbox pero aún no existe (para que el llamante pueda crearlo).
     */
    private fun safeResolve(base: File, rel: String): File? {
        val cacheKey = "${base.absolutePath}::${rel}"
        return safeResolveCache.computeIfAbsent(cacheKey) {
            // CÓDIGO EXACTO MISMO QUE ORIGINAL - sin cambios visibles
            val clean = rel.replace("\\", "/").trimStart('/')
            if (clean.isBlank()) null
            else if (clean.split('/').any { it == ".." }) null
            else if (clean.contains(':')) null
            else {
                val sandbox = base.canonicalFile.path + File.separator

                fun inSandbox(f: File): Boolean =
                    f.canonicalFile.path.startsWith(sandbox)

                val literal = File(base, clean)
                if (inSandbox(literal)) {
                    if (literal.exists()) literal
                    else {
                        // La referencia no trae extensión: probar los formatos de Bedrock.
                        val lastSlash = clean.lastIndexOf('/')
                        val lastDot = clean.lastIndexOf('.')
                        if (lastDot <= lastSlash + 1) {
                            for (ext in SUPPORTED_TEX_EXTS) {
                                val candidate = File(base, "$clean.$ext")
                                if (inSandbox(candidate) && candidate.exists()) return@computeIfAbsent candidate
                            }
                        }
                        // Ruta válida dentro del sandbox pero aún ausente: el llamante decide.
                        literal
                    }
                } else {
                    null
                }
            }
        }
    }

    // ── FASE DIRS: RESOLUCIÓN + RECUPERACIÓN ──────────────────────────────

/**
     * RESUELVE las dependencias de entidades ausentes tras la fusión:
     * geometrías, texturas, animaciones y render controllers.
     *
     * Es el ÚNICO resolutor del pipeline. Sustituye al antiguo
     * `BedrockCriticalFilesMerger.resolveMissingDependencies()`, que quedó sin
     * uso al dejar de construirse el `BedrockIdentifierIndex` masivo y fue
     * eliminado para no mantener dos implementaciones divergentes.
     *
     * Ventajas frente a aquel:
     *  - Clasifica por CONTENIDO (duck typing), no por extensión de archivo:
     *    encuentra geometrías en `.fT.json` igual que en `x.geo.json`.
     *  - Indexa por identificador de Bedrock, no por assumed path.
     *  - Crea alias de variantes si dos addons definen la misma geometría con
     *    contenidos distintos, y restaura la canónica si el deepmerge la mutó.
     *  - Reporta a ConflictRegistry (pantalla de Conflictos), no solo al log.
     *  - USA ÍNDICE UNIFICADO (GeometryAnimRCIndex) para evitar 3x escaneo.
     *
     * @return notas legibles para el reporte de fusión.
     */
    suspend fun resolve(rpDirs: List<File>, mergedRpDir: File): List<String> {
        if (!mergedRpDir.isDirectory) return emptyList()

        // ÍNDICE UNIFICADO: una sola pasada para geometrías, animaciones, RCs
        val unifiedIndex = GeometryAnimRCIndex.build(rpDirs, mergedRpDir)
        val geoIndex = unifiedIndex.geometryById
        val animIndex = unifiedIndex.animationById
        val rcIndex = unifiedIndex.renderControllerById

        // Convertir a MergedIndex para compatibilidad con el resto del código
        val merged = MergedIndex()
        unifiedIndex.mergedGeometryHashById.forEach { (id, hash) ->
            merged.geoHashById[id] = hash
            merged.geoPathById[id] = unifiedIndex.mergedGeometryPathById[id] ?: ""
            merged.geoIds.add(id)
        }
        // Usar animaciones del pack fusionado
        merged.animRcIds.addAll(unifiedIndex.mergedAnimationIds)

        var fixedGeo = 0; var fixedTex = 0; var fixedAnim = 0; var fixedRc = 0
        // Referencias colgantes eliminadas (defecto del addon, ver neutralizarColgantes).
        val neutralizadas = java.util.concurrent.atomic.AtomicInteger(0)
        val notes = Collections.synchronizedList(mutableListOf<String>())
        val missing = Collections.synchronizedList(mutableListOf<String>())

        // Índice basename→archivo construido UNA vez (lazy) para el fallback de texturas.
        val texByBasename by lazy {
            val map = HashMap<String, File>()
            for (root in rpDirs) {
                if (!root.isDirectory) continue
                // ⭐ OPTIMIZACIÓN: Usar DirIndexCache en lugar de walkTopDown()
                val cachedFiles = DirIndexCache.index(root).allFiles
                for (file in cachedFiles) {
                    if (file.extension.lowercase(Locale.ROOT) in IMAGE_EXTS) {
                        map.putIfAbsent(file.name.lowercase(Locale.ROOT), file)
                        // ⭐ FASE 5: indexar también el nombre SIN extensión.
                        map.putIfAbsent(file.nameWithoutExtension.lowercase(Locale.ROOT), file)
                    }
                }
            }
            map
        }
        val texLookupMemo = ConcurrentHashMap<String, File?>()

val entityRoot = File(mergedRpDir, "entity")
        if (!entityRoot.isDirectory) return listOf("Sin carpeta entity/ en el RP fusionado.")

        // ⭐ OPTIMIZACIÓN: Usar DirIndexCache en lugar de walkTopDown()
        val cachedFiles = DirIndexCache.index(entityRoot).jsonFiles

        // ⭐ RENDIMIENTO/DIAGNÓSTICO: contador de avance de la fase.
        //
        // En un móvil se reportó el pipeline parado >30 min en el 78 % (esta
        // fase) sin ninguna señal: la barra no se mueve hasta terminarla y el
        // reporte solo se escribe al final, así que no había forma de saber si
        // avanzaba o estaba colgada. Este latido deja rastro en el log cada 25
        // entidades (y al empezar/terminar), con el total, para poder distinguir
        // "va lento" de "está atascada" y ver en qué número se queda.
        val totalEntidades = cachedFiles.size
        val completadas = java.util.concurrent.atomic.AtomicInteger(0)
        // ⭐ MEMORIA: cupo de entidades simultáneas.
        //
        // Antes se lanzaba UNA corrutina por entidad, cada una leyendo y parseando
        // su JSON. En un pack grande eso multiplica el consumo por el número de
        // entidades a la vez, y en un móvil con poca RAM acaba en swap (medido:
        // 156 MB de la app en swap, 0 % de CPU, horas sin avanzar).
        val permisosEntidad = kotlinx.coroutines.sync.Semaphore(
            PackForgeOrchestrator.LIMITE_ENTIDADES_PARALELAS
        )
        PackForgeLog.d(TAG, "🔎 Fase 3D: iniciando con $totalEntidades entidad(es)")

        // ⭐ OPTIMIZACIÓN: Procesar entidades en PARALELO con coroutines
        // Cada entidad es independiente; el estado compartido (merged, contadores)
        // se protege con synchronized / AtomicInteger / ConcurrentHashMap.
        coroutineScope {
            cachedFiles.map { file ->
                async(Dispatchers.IO) {
                    // ⭐ MEMORIA: se toma un hueco ANTES de leer el archivo, así
                    // que el texto y el JSON de cada entidad solo existen en
                    // memoria mientras dura su turno (máx. LIMITE a la vez).
                    permisosEntidad.acquire()
                    try {
                        val json = readPackJson(file.readText(StandardCharsets.UTF_8)) ?: return@async
                        val desc = json.optJSONObject("minecraft:client_entity")?.optJSONObject("description")
                            ?: return@async

                        // ── 1) GEOMETRÍA (string u objeto {"default": …}) ──
                        val geoId = readGeometryId(desc)
                        // TICKET-CORE-003: assets vanilla los resuelve el juego, no son "rotos".
                        if (geoId != null && !VanillaAssets.isVanillaGeometry(geoId)) {
                            val mergedHash = merged.geoHashById[geoId]
                            val candidates = geoIndex[geoId].orEmpty()
                            when {
                                candidates.isEmpty() ->
                                    missing.add("geometría '$geoId' requerida por ${file.name}")

                                mergedHash == null -> {
                                    // Faltaba por completo: copiar preservando ruta relativa.
                                    val cand = candidates.first()
                                    safeResolve(mergedRpDir, cand.file.relativeTo(cand.root).path.replace("\\", "/"))
                                        ?.let { dest ->
                                            dest.parentFile?.mkdirs()
                                            cand.file.copyTo(dest, overwrite = true)
                                            synchronized(merged) {
                                                merged.geoIds.add(geoId)
                                                merged.geoHashById[geoId] = cand.hash
                                                merged.geoPathById[geoId] = dest.relativeTo(mergedRpDir).path.replace("\\", "/")
                                            }
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
                                            synchronized(merged) {
                                                if (!merged.geoIds.contains(aliasId)) {
                                                    writeAliasedGeometry(cand, geoId, aliasId, mergedRpDir)
                                                    merged.geoIds.add(aliasId)
                                                    merged.geoHashById[aliasId] = cand.hash
                                                    notes.add("🔀 Geometría '$geoId': variante alternativa disponible como '$aliasId' (contenido distinto entre addons)")
                                                    PackForgeLog.w(TAG, "🦴 Variante de geometría aliased: $geoId → $aliasId")
                                                }
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
                                            synchronized(merged) {
                                                merged.geoHashById[geoId] = cand.hash
                                            }
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
                                    missing.add("textura '$rawPath' (ruta insegura) requerida por ${file.name}")
                                    return@forEach
                                }

                                // TICKET-CORE-003: no intentar reparar assets vanilla.
                                if (!VanillaAssets.isVanillaTexture(rawPath)) {
                                    val dest = safeResolve(mergedRpDir, rawPath)
                                    if (dest == null) {
                                        missing.add("textura '$rawPath' (ruta insegura) requerida por ${file.name}")
                                        texLookupMemo[rawPath] = FILE_INSECURE_SENTINEL
                                        return@forEach
                                    }
                                    // ⭐ FASE 5 — ¿ya está, aunque sea con otra extensión?
                                    //
                                    // Bedrock referencia las texturas SIN extensión
                                    // ("textures/cc/custom_villager/levels/base_lvl.1") y el
                                    // archivo empaquetado la lleva ("…/base_lvl.1.png"). Con
                                    // `dest.exists()` a secas, esa comprobación fallaba y la
                                    // textura se contaba como ausente —o se recopiaba encima—
                                    // aunque estuviera en el pack. El reporte de Better MC la
                                    // listaba como "referencia rota tras fusión" en 30
                                    // entidades.
                                    //
                                    // Ojo con el nombre: `base_lvl.1` tiene un punto que NO
                                    // es extensión, así que se prueban las extensiones reales
                                    // por sufijo en lugar de recortar tras el último punto.
                                    val yaPresente = dest.exists() || SUPPORTED_TEX_EXTS.any { ext ->
                                        File(dest.parentFile, "${dest.name}.$ext").isFile
                                    }
                                    if (!yaPresente) {
                                        val source = resolveTextureSource(rpDirs, rawPath, texByBasename)
                                        if (source != null) {
                                            // Conservar la extensión REAL del origen.
                                            val destReal = if (dest.exists() || dest.name.contains('.')) {
                                                dest
                                            } else {
                                                source.extension.takeIf { it.isNotBlank() }
                                                    ?.let { File(dest.parentFile, "${dest.name}.$it") }
                                                    ?: dest
                                            }
                                            destReal.parentFile?.mkdirs()
                                            source.copyTo(destReal, overwrite = true)
                                            fixedTex++
                                            PackForgeLog.d(TAG, "🖼️ Textura recuperada: $rawPath ← ${source.name}")
                                        } else {
                                            texLookupMemo[rawPath] = FILE_INSECURE_SENTINEL
                                            missing.add("textura '$rawPath' requerida por ${file.name}")
                                        }
                                    }
                                }
                            }
                        }

                        // ⭐ Referencias que no existen en NINGÚN addon ni son vanilla. Se anotan
                        // para neutralizarlas al final de este archivo.
                        val animsColgantes = mutableSetOf<String>()
                        val rcsColgantes = mutableSetOf<String>()

                        // ── 3) ANIMACIONES ──
                        readAnimationIds(desc).forEach { animId ->
                            // TICKET-CORE-003: assets vanilla los resuelve el juego en runtime.
                            if (VanillaAssets.isVanillaAnimation(animId)) return@forEach
                            synchronized(merged) {
                                if (merged.animRcIds.contains(animId)) return@forEach
                            }
                            val candidates = animIndex[animId].orEmpty()
                            if (candidates.isEmpty()) {
                                // NO va a `missing`: la referencia se neutraliza más abajo.
                                // Si se contara como irrecuperable, el reporte culparía a la
                                // fusión de un defecto del addon de origen.
                                animsColgantes.add(animId)
                            } else {
                                recoverById(candidates.first(), mergedRpDir, merged, animId)
                                fixedAnim++
                            }
                        }

                        // ── 4) RENDER CONTROLLERS (array u objeto legacy) ──
                        readRenderControllerIds(desc).forEach { rcId ->
                            // TICKET-CORE-003: assets vanilla los resuelve el juego en runtime.
                            if (VanillaAssets.isVanillaRenderController(rcId)) return@forEach
                            if (rcId.isBlank()) return@forEach
                            synchronized(merged) {
                                if (merged.animRcIds.contains(rcId)) return@forEach
                            }
                            val candidates = rcIndex[rcId].orEmpty()
                            if (candidates.isEmpty()) {
                                // Igual que las animaciones: se neutraliza, no se reporta
                                // como pérdida de la fusión.
                                rcsColgantes.add(rcId)
                            } else {
                                recoverById(candidates.first(), mergedRpDir, merged, rcId)
                                fixedRc++
                            }
                        }

                        // ⭐ NEUTRALIZACIÓN de referencias colgantes.
                        //
                        // Hay addons que publican una entidad citando animaciones o
                        // render controllers que NO incluyen en el paquete. Caso real:
                        // `OTG Backpack` trae un `entity/player.entity.json` que
                        // referencia 24 animaciones `5fs_br.*` / `controller.animation.*`
                        // que su propio RP no define (verificado: no existen en ningún
                        // addon ni son vanilla).
                        //
                        // El juego no puede resolverlas —las ignora— pero generaban 53
                        // errores en el reporte que parecían fallos de la fusión. Se
                        // quitan del JSON y se informa una sola vez, porque dejar la
                        // referencia no aporta nada y sí ruido.
                        if (animsColgantes.isNotEmpty() || rcsColgantes.isNotEmpty()) {
                            neutralizadas.addAndGet(
                                neutralizarColgantes(desc, animsColgantes, rcsColgantes)
                            )
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
                    } finally {
                        // ⭐ MEMORIA: se libera SIEMPRE (el try puede salir por
                        // return@async cuando el JSON no es una client_entity).
                        permisosEntidad.release()
                        // Latido: cada 25 entidades (y en la primera y la última)
                        // para que el log diga si la fase avanza y por dónde va.
                        val n = completadas.incrementAndGet()
                        if (n == 1 || n % 25 == 0 || n == totalEntidades) {
                            PackForgeLog.d(TAG, "🔎 Fase 3D: $n/$totalEntidades entidades")
                            println("PF-PERF | Fase 3D: $n/$totalEntidades entidades")
                        }
                    }
                }
            }.awaitAll()
        }

        missing.take(MAX_REPORTED).forEach { m: String ->
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
        if (neutralizadas.get() > 0) {
            notes.add(
                "Referencias colgantes neutralizadas: ${neutralizadas.get()} " +
                    "(el addon de origen cita animaciones/render controllers que no incluye; " +
                    "el juego no podía resolverlas)"
            )
        }
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
                // ⭐ FASE 5: parseo tolerante — el candidato recuperado puede
                // traer comentarios; si fallaba, sus ids no se registraban.
                val j = readPackJson(cand.text) ?: return@runCatching
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

    /**
     * Quita de `description` las referencias a animaciones y render controllers
     * que **no existen en ningún addon ni son vanilla**.
     *
     * No es "borrar contenido": son referencias a IDs inexistentes que el juego
     * no puede resolver e ignora de todos modos. Dejarlas solo producía ruido en
     * el reporte (53 errores por un único `player.entity.json` defectuoso).
     *
     * @return número de referencias eliminadas.
     */
    private fun neutralizarColgantes(
        desc: JSONObject,
        anims: Set<String>,
        rcs: Set<String>
    ): Int {
        var quitadas = 0
        val nombresQuitados = mutableSetOf<String>()

        // `animations` es un mapa nombreCorto → id de animación.
        desc.optJSONObject("animations")?.let { mapa ->
            for (clave in mapa.keys().asSequence().toList()) {
                val valor = mapa.optString(clave, "")
                if (valor.isNotEmpty() && valor in anims) {
                    mapa.remove(clave)
                    nombresQuitados.add(clave)
                    quitadas++
                }
            }
        }

        // `render_controllers` es un array de ids (se conserva el orden del resto).
        var rcQuitados = 0
        desc.optJSONArray("render_controllers")?.let { arr ->
            val limpio = JSONArray()
            for (i in 0 until arr.length()) {
                val v = arr.optString(i, "")
                if (v.isNotEmpty() && v in rcs) {
                    rcQuitados++
                    continue
                }
                limpio.put(arr.opt(i))
            }
            if (rcQuitados > 0) desc.put("render_controllers", limpio)
        }
        quitadas += rcQuitados

        // `scripts.animate` cita las animaciones por NOMBRE CORTO, así que hay que
        // retirar también esas entradas (string o {nombre: condición}).
        if (nombresQuitados.isNotEmpty()) {
            desc.optJSONObject("scripts")?.optJSONArray("animate")?.let { animArr ->
                val limpio = JSONArray()
                for (i in 0 until animArr.length()) {
                    when (val item = animArr.opt(i)) {
                        is String -> if (item !in nombresQuitados) limpio.put(item)
                        is JSONObject -> {
                            val obj = JSONObject()
                            var util = false
                            for (k in item.keys()) {
                                if (k in nombresQuitados) continue
                                obj.put(k, item.get(k))
                                util = true
                            }
                            if (util) limpio.put(obj)
                        }
                        null -> {}
                        else -> limpio.put(item)
                    }
                }
                desc.getJSONObject("scripts").put("animate", limpio)
            }
        }

        return quitadas
    }

    // ══ FASE ZIP: VALIDACIÓN DEL PAQUETE FINAL (validateMerge) ════════════
    //
    // ⚠️ Tolerancia a extensiones: Bedrock referencia las texturas SIN extensión
    // ("textures/entity/steve") mientras que el ZIP guarda el archivo real con su
    // extensión ("textures/entity/steve.png"). Un match estricto reportaba cada mob
    // como "textura ausente" (falsos positivos) aunque el binario estuviera presente.
    //

    /** Extensiones binarias de imagen que Bedrock admite como textura empaquetada. */
    private val SUPPORTED_TEX_EXTS = listOf("png", "jpg", "jpeg", "tga", "webp", "bmp")

    /**
     * Lee un JSON del pack tolerando lo que Bedrock tolera.
     *
     * ⭐ FASE 5 — CAUSA RAÍZ de los 64 "render controller ausente" falsos.
     *
     * `JSONObject(texto)` es JSON **estricto** y rechaza los comentarios `//` y
     * de bloque, que Bedrock admite en cualquier posición. El archivo
     * `render_controllers/tznvrmdz.json` del pack real (11.565 bytes) los trae y
     * es justamente el que define `controller.render.cc.common` y
     * `controller.render.cc.ignore_lighting`.
     *
     * Al fallar el parseo, `runCatching` devolvía `null` y **los 64 render
     * controllers de ese archivo** no entraban en `animRcIds`. Después, al
     * validar las entidades, cada referencia a uno de ellos se reportaba como
     * "render controller ausente" — y eran avisos que el reporte original del
     * usuario NO tenía: los introdujo el endurecimiento de esta validación.
     *
     * Es el mismo fallo ya corregido en [MergeReferenceValidator] para los
     * materiales: un `null` silencioso al leer un archivo hace que **todo su
     * contenido** se reporte como perdido, y el mensaje culpa al contenido en
     * vez de al lector.
     */
    private fun readPackJson(text: String): JSONObject? =
        try {
            LenientJson.parseObject(text) ?: JSONObject(text)
        } catch (_: Exception) {
            null
        }

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
    // Memo por prefijoDeCarpeta|ruta: la misma textura puede consultarse desde
     // entidades en carpetas distintas y la respuesta no siempre coincide.
    val suffixMemo = HashMap<String, Boolean>()

      /**
     * ¿Existe la textura [path] dentro del ZIP, tolerando la ausencia de extensión
    * en la referencia? [path] llega sin extensión desde los JSONs de Bedrock.
         */
          fun zipHasTexture(folderPrefix: String, path: String): Boolean {
          val cleanPath = path.replace("\\", "/").trimStart('/')
      suffixMemo[cleanPath]?.let { return it }

      fun miss(): Boolean {
          suffixMemo[cleanPath] = false
    return false
     }

       // 1) Coincidencia exacta (por si la referencia YA trae extensión).
          if (entrySet.contains("$folderPrefix/$cleanPath") || entrySet.contains(cleanPath)) {
            suffixMemo[cleanPath] = true
       return true
             }

      // 2) La referencia NO trae extensión: probar cada formato válido.
    val lastSlash = cleanPath.lastIndexOf('/')
  val lastDot = cleanPath.lastIndexOf('.')
      val hasExt = lastDot > lastSlash + 1 &&
        cleanPath.substring(lastDot + 1).lowercase(Locale.ROOT) in SUPPORTED_TEX_EXTS

                if (!hasExt) {
       for (ext in SUPPORTED_TEX_EXTS) {
       val withExt = "$cleanPath.$ext"
 if (entrySet.contains("$folderPrefix/$withExt") ||
      entrySet.contains(withExt) ||
   entrySet.contains("textures/$withExt")) {
        suffixMemo[cleanPath] = true
        return true
  }
    }
            }

    // 3) Fallback tolerante: la entrada puede estar en otra subcarpeta o
       //    prefijo. Solo se acepta si es una IMAGEN con extensión soportada:
    //    comparar solo el nombre base aceptaría un .ogg como si fuera textura.
        val cleanNoExt = if (hasExt) cleanPath.substring(0, lastDot) else cleanPath
        val result = entryNames.any { entry ->
  val eDot = entry.lastIndexOf('.')
            if (eDot <= entry.lastIndexOf('/') + 1) return@any false
  if (entry.substring(eDot + 1).lowercase(Locale.ROOT) !in SUPPORTED_TEX_EXTS) return@any false
    entry.substring(0, eDot).endsWith(cleanNoExt)
        }

    suffixMemo[cleanPath] = result
    return result
    }
                val geoIds = mutableSetOf<String>()
                val animRcIds = mutableSetOf<String>()
                // ⭐ DUCK TYPING: Escanear TODOS los .json del ZIP para detectar geometrías,
                // animaciones y render_controllers por contenido raíz, NO por extensión de archivo.
                // Esto permite encontrar archivos ofuscados (ej: .fT.json conteniendo minecraft:geometry).
                entries.filter { it.name.endsWith(".json", true) && !it.isDirectory }.forEach { e ->
                    runCatching { readPackJson(zip.getInputStream(e).bufferedReader().readText()) }.getOrNull()
                        ?.let { j ->
                            // Detectar geometría por contenido
                            j.optJSONArray("minecraft:geometry")?.let { arr ->
                                for (i in 0 until arr.length()) {
                                    arr.optJSONObject(i)?.optJSONObject("description")
                                        ?.optString("identifier")?.trim()?.let { geoIds.add(it) }
                                }
                            }
                            // ⭐ FASE 5 (falso positivo masivo): geometría en formato
                            // LEGACY. Bedrock admite la geometría como CLAVE RAÍZ
                            // ("geometry.cc.pillager": { "bones": [...] }) además del
                            // array moderno. CoreCraft usa el legacy, así que todas
                            // sus geometrías —que SÍ están empaquetadas— no se
                            // indexaban y se reportaban como "no existe en el
                            // paquete": los 438 modelos faltantes del reporte.
                            j.keys().forEach { key ->
                                if (key.startsWith("geometry.") && j.optJSONObject(key) != null) {
                                    geoIds.add(key.trim())
                                }
                            }
                            // Detectar animaciones/render_controllers por contenido
                            j.optJSONObject("animations")?.keys()?.forEach { animRcIds.add(it.trim()) }
                            j.optJSONObject("animation_controllers")?.keys()?.forEach { animRcIds.add(it.trim()) }
                            j.optJSONObject("render_controllers")?.keys()?.forEach { animRcIds.add(it.trim()) }
                            // ⭐ FASE 5: los render_controllers también pueden venir
                            // como clave raíz suelta en el formato legacy.
                            j.keys().forEach { key ->
                                if (key.startsWith("controller.render.") && j.optJSONObject(key) != null) {
                                    animRcIds.add(key.trim())
                                }
                            }
                        }
                }

                entries.filter { e ->
                    val n = e.name.replace("\\", "/")
                    (n.contains("/entity/") || n.contains("/entities/")) &&
                        n.endsWith(".json", true) && !n.endsWith(".geo.json", true)
                }.forEach { e ->
                    val entityPath = e.name.replace("\\", "/")
                    runCatching { readPackJson(zip.getInputStream(e).bufferedReader().readText()) }.onSuccess { j ->
                        val desc = j?.optJSONObject("minecraft:client_entity")?.optJSONObject("description")
                            ?: return@onSuccess
                        val folderPrefix = entityPath.substringBefore('/', missingDelimiterValue = "")
                        val shortName = entityPath.substringAfterLast('/')

                        readGeometryId(desc)?.let { g ->
                            if (g !in geoIds && !VanillaAssets.isVanillaGeometry(g)) errors += "❌ [ZIP] $shortName: geometría '$g' no existe en el paquete"
                        }
                        desc.optJSONObject("textures")?.let { t ->
                            // Solo rutas reales: se descartan alias ("Texture.default")
                            // y Molang. Antes `optString` serializaba arrays/objetos.
                            BedrockRefs.texturePaths(t).forEach { raw ->
                                val p = raw.replace("\\", "/").trimStart('/')
                                if (!VanillaAssets.isVanillaTexture(p) && !zipHasTexture(folderPrefix, p)) {
                                    errors += "❌ [ZIP] $shortName: textura '$p' no está empaquetada"
                                }
                            }
                        }
                        readAnimationIds(desc).forEach { id ->
                            if (id !in animRcIds && !VanillaAssets.isVanillaAnimation(id)) errors += "❌ [ZIP] $shortName: animación '$id' ausente"
                        }
                        readRenderControllerIds(desc).forEach { rc ->
                            if (rc !in animRcIds && !VanillaAssets.isVanillaRenderController(rc)) errors += "❌ [ZIP] $shortName: render controller '$rc' ausente"
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








