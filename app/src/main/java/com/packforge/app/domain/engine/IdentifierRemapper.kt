package com.packforge.app.domain.engine

import com.packforge.app.util.PackForgeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

/**
 * ═══════════════════════════════════════════════════════════════════════
 * RENAMESPACER "PackForge Composer"
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Hace compatibles dos o más addons que definen el MISMO identificador
 * (entity / item / block / recipe). El identificador de los que colisionan
 * se renombra con un namespace único de PackForge (ej. `ns:sword` →
 * `pf_a3f2:sword`) y se reescriben TODAS las referencias internas de ese
 * addon (otros JSON y .lang), de modo que ambos coexisten en el modpack
 * final en lugar de aniquilarse ("uno gana").
 *
 * Limitaciones conocidas (se evita activamente romper):
 *  - NO se renombran ids del namespace `minecraft:` (override vanilla):
 *    renombrarlos rompería la paridad con el juego.
 *  - Si dos addons modifican la MISMA entidad VANILLA (ambos setean
 *    `minecraft:cow` con componentes distintos) NO se resuelve aquí
 *    (eso pertenece a la fusión por componente, Idea #1).
 *  - Referencias CRUZADAS entre addons que comparten el id (raro) pueden
 *    quedar huérfanas; se advierte en log.
 */
object IdentifierRemapper {
    private const val TAG = "PackForge_Remap"
    private const val PF_NAMESPACE = "pf"

    /**
     * Hilos para el escaneo de identificadores declarados.
     *
     * El trabajo es I/O de lectura independiente por archivo y está limitado por
     * la latencia de apertura (antivirus incluido), no por CPU: por eso conviene
     * repartirlo. Se acota a 8 para no saturar discos mecánicos ni el escáner del
     * antivirus con demasiadas aperturas concurrentes.
     */
    private val PARALELISMO: Int =
        Runtime.getRuntime().availableProcessors().coerceIn(2, 8)

    /** Un identificador renombrado. */
    data class RemapEntry(
        val oldId: String,
        val newId: String,
        val ownerKey: String,
        val file: String
    )

    /** Claves raíz de archivos que DECLARAN un identificador. */
    private val DECLARING_KEYS = setOf(
        "minecraft:entity",
        "minecraft:item",
        "minecraft:block",
        "minecraft:client_entity",
        "minecraft:attachable",
        "particle_effect",
        "minecraft:recipe_shaped",
        "minecraft:recipe_shapeless",
        "minecraft:recipe_furnace",
        "minecraft:recipe_brewing_mix",
        "minecraft:recipe_potion",
        "minecraft:recipe_container_mix",
        "minecraft:biome",
        "minecraft:feature",
        "minecraft:feature_rules",
        "minecraft:spawn_rules",
        "minecraft:material",
        // B9 (auditoría CORE): minecraft:fog — dos addons con el mismo fog id
        // en fogs/ se pisaban ("uno gana") sin pasar por el renamespacer.
        "minecraft:fog",
        "minecraft:music_definition",
        "minecraft:music",
        "minecraft:sounds",
        "minecraft:particle",
        // 3.8 — familias que declaran identifier y no estaban: dos addons con
        // `cameras/`/`dimensions/`/`item_catalog/` del mismo id duplicaban la
        // definición (Bedrock descarta una en silencio).
        "minecraft:camera_preset",
        "minecraft:dimension",
        "minecraft:item_catalog"
    )

    /**
     * Detecta colisiones de identificadores entre addons, renombra los que
     * chocan y reescribe sus referencias. Devuelve el reporte de renombres.
     *
     * @param behaviorDirs directorios BP por addon (cada uno = raíz de un pack)
     * @param resourceDirs directorios RP por addon
     */
    suspend fun run(
        behaviorDirs: List<File>,
        resourceDirs: List<File>,
        progress: (suspend (String) -> Unit)? = null
    ): List<RemapEntry> {
        val allDirs = behaviorDirs + resourceDirs
        if (allDirs.size < 2) return emptyList()

        return try {
            // ── 1) Agrupar directorios por addon original ──────────────
            // Un addon BOTH aparece como 2 carpetas (separated_bp_TS, separated_rp_TS)
            // con el MISMO timestamp; ambos pertenecen al mismo addon.
            val dirsByOwner = LinkedHashMap<String, MutableList<File>>()
            allDirs.forEach { dir ->
                // Sin repetir rutas: un addon BOTH cuyas subcarpetas no existen
                // aporta la MISMA carpeta como BP y como RP. Antes eso no podía
                // pasar porque la separación creaba dos carpetas distintas; ahora
                // escanearla o reescribirla dos veces sería trabajo duplicado.
                val lista = dirsByOwner.getOrPut(ownerKey(dir)) { mutableListOf() }
                if (lista.none { it.absolutePath == dir.absolutePath }) lista.add(dir)
            }

            if (dirsByOwner.size < 2) return emptyList()

            // ── 2) Recolectar identificadores DECLARADOS por cada addon ──
            // ⚠️ SOLO se escanean los BEHAVIOR packs. Un addon declara el mismo
            // identificador en su BP (`minecraft:entity`) y en su RP
            // (`minecraft:client_entity` / `minecraft:attachable`); si se
            // escaneasen los dos, el RP "colisionaría consigo mismo" y el
            // renamespacer renombraría el id del RP a `pf:xxxx_*` dejando el BP
            // con el id original. Eso rompía el vínculo BP↔RP (entidades
            // invisibles, texturas ausentes) y generaba miles de renombrados
            // inútiles. El RP SIGUE reescribiéndose como referencia (paso 4).
            val declaringOwners = LinkedHashMap<String, MutableList<File>>()
            behaviorDirs.forEach { dir ->
                val lista = declaringOwners.getOrPut(ownerKey(dir)) { mutableListOf() }
                if (lista.none { it.absolutePath == dir.absolutePath }) lista.add(dir)
            }
            val declaredByOwner = LinkedHashMap<String, MutableMap<String, File>>()
            declaringOwners.forEach { (owner, dirs) ->
                progress?.invoke("Analizando identificadores de ${shortOwner(owner)}...")
                dirs.forEach { dir ->
                    collectDeclaredIds(dir)?.forEach { (id, file) ->
                        declaredByOwner.getOrPut(owner) { mutableMapOf() }[id] = file
                    }
                }
            }

            // ── 3) Detectar colisiones y planear renombres ─────────────
            val idsByValue = LinkedHashMap<String, MutableList<String>>() // id -> owners
            declaredByOwner.forEach { (owner, ids) ->
                ids.keys.forEach { id ->
                    idsByValue.getOrPut(id) { mutableListOf() }.add(owner)
                }
            }

            val remapByOwner = LinkedHashMap<String, MutableList<Pair<String, String>>>() // owner -> (old,new)
            val newIdsInUse = mutableSetOf<String>()

            idsByValue.forEach { (id, owners) ->
                if (owners.size < 2) return@forEach
                if (id.substringBefore(':') == "minecraft") return@forEach // no tocar vanilla
                if (id.startsWith("$PF_NAMESPACE:")) return@forEach // ya es nuestro

                // B4 (auditoría CORE): ganador DETERMINISTA = el owner menor en
                // orden lexicográfico. ANTES era owners.first() (orden de llegada
                // al pipeline), así que dos ejecuciones sobre los mismos addons
                // podían dar ganadores distintos → resultado no reproducible.
                // El orden lexicográfico es estable porque ownerKey deriva del
                // timestamp de separación (BOTH agrupa BP+RP) o de la ruta.
                val sortedOwners = owners.sorted()
                val winner = sortedOwners.first()
                sortedOwners.drop(1).forEachIndexed { i, loser ->
                    val name = sanitizeIdName(id.substringAfter(':', id))
                    var newId = "$PF_NAMESPACE:${tokenFor(loser)}_$name"
                    while (newIdsInUse.contains(newId)) {
                        newId = "$PF_NAMESPACE:${tokenFor(loser)}${i}_$name"
                    }
                    newIdsInUse.add(newId)
                    remapByOwner.getOrPut(loser) { mutableListOf() }.add(id to newId)

                    PackForgeLog.d(TAG, "💥 Colisión en '$id' (addons: ${owners.joinToString(", ") { shortOwner(it) }})")
                    PackForgeLog.d(TAG, "   → '$id' renombrado a '$newId' (addon: ${shortOwner(loser)})")
                }
            }

            if (remapByOwner.isEmpty()) {
                PackForgeLog.d(TAG, "✅ Sin colisiones de identificadores detectadas entre addons.")
                return emptyList()
            }

            progress?.invoke("Renombrando ${remapByOwner.values.sumOf { it.size }} identificadores en colisión...")

            // ── 4) Aplicar renombres por addon (solo en SUS directorios) ─
            val report = mutableListOf<RemapEntry>()
            remapByOwner.forEach { (owner, remaps) ->
                val ownerDirs = dirsByOwner[owner].orEmpty()
                remaps.forEach { (oldId, newId) ->
                    val declaredFile = declaredByOwner[owner]?.get(oldId)
                    ownerDirs.forEach { dir ->
                        rewriteIdentifiersInDir(dir, oldId, newId)
                        renameCollidingFiles(dir, oldId, newId)
                    }
                    report.add(
                        RemapEntry(
                            oldId = oldId,
                            newId = newId,
                            ownerKey = owner,
                            file = declaredFile?.relativeOrName() ?: ""
                        )
                    )
                    PackForgeLog.d(TAG, "🔁 ${shortOwner(owner)}: $oldId → $newId")
                }
            }

            PackForgeLog.d(TAG, "═══════════════ RENAMESPACER ═══════════════")
            PackForgeLog.d(TAG, "🔁 Total de identificadores renombrados: ${report.size}")
            report.forEach { PackForgeLog.d(TAG, "   $it") }
            PackForgeLog.d(TAG, "═══════════════════════════════════════════")
            report
        } catch (e: Exception) {
            // Fail-open: si algo falla, el export continúa sin renombres.
            PackForgeLog.e(TAG, "❌ Error en renamespacer (se continúa sin renombrar): ${e.message}", e)
            emptyList()
        }
    }

    // ── DETECCIÓN ─────────────────────────────────────────────────────────

    /**
     * Recorre los ficheros de un pack recogiendo los identificadores DECLARADOS.
     * Devuelve map id → fichero que lo declara (ultima declaración gana).
     *
     * ⭐ RENDIMIENTO (medido con el reporte de fases): esta pasada era el
     * **40% de la fusión** (545 s de 1368 s en Better MC). El motivo es que el
     * filtro barato H1 tiene que leer el texto COMPLETO de cada JSON del BP para
     * comprobar si contiene la palabra "identifier": son miles de aperturas de
     * archivo, y el antivirus de Windows cobra por apertura, no por byte.
     *
     * La lectura de cada archivo es independiente de las demás, así que se
     * reparte entre hilos. El resultado se fusiona después **en el orden
     * original del índice**, de modo que la política "última declaración gana"
     * sigue siendo determinista y reproducible (si cada hilo escribiera
     * directo al mapa, el ganador dependería de qué hilo terminase antes).
     */
    private suspend fun collectDeclaredIds(packDir: File): Map<String, File>? {
        val result = mutableMapOf<String, File>()
        try {
            // ⭐ OPTIMIZACIÓN: Usar DirIndexCache en lugar de walkTopDown()
            val cachedFiles = DirIndexCache.index(packDir).jsonFiles
            if (cachedFiles.isEmpty()) return null

            val total = cachedFiles.size
            val siguiente = AtomicInteger(0)
            val parciales = withContext(Dispatchers.IO) {
                (1..PARALELISMO).map {
                    async(Dispatchers.IO) {
                        val lote = ArrayList<Pair<Int, Pair<String, File>>>(128)
                        while (true) {
                            val i = siguiente.getAndIncrement()
                            if (i >= total) break
                            val file = cachedFiles[i]
                            // Filtro barato H1: un JSON que declara un
                            // identificador SIEMPRE contiene el texto
                            // "identifier"; sin él ni se lee ni se parsea.
                            val preview = try {
                                file.readText(StandardCharsets.UTF_8)
                            } catch (e: Exception) {
                                continue
                            }
                            if (!preview.contains("identifier")) continue
                            val id = readDeclaredIdentifier(file, preview) ?: continue
                            // Evitar renombrar manifest.json por accidente
                            if (file.name.equals("manifest.json", true)) continue
                            lote.add(i to (id to file))
                        }
                        lote
                    }
                }.awaitAll()
            }

            // Fusión determinista en el orden del índice original.
            val porIndice = arrayOfNulls<Pair<String, File>>(total)
            parciales.forEach { lote -> lote.forEach { (i, par) -> porIndice[i] = par } }
            for (par in porIndice) {
                if (par != null) result[par.first] = par.second
            }
        } catch (e: Exception) {
            PackForgeLog.e(TAG, "Error escaneando ${packDir.name}: ${e.message}")
        }
        return result.ifEmpty { null }
    }

    /**
     * Lee `description.identifier` del JSON si alguna raíz lo declara.
     * @param preview texto ya leído por el filtro barato (evita una segunda lectura).
     */
    private fun readDeclaredIdentifier(file: File, preview: String? = null): String? {
        return try {
            val json = JSONObject(preview ?: file.readText(StandardCharsets.UTF_8))
            for (key in json.keys()) {
                if (key in DECLARING_KEYS) {
                    val nodo = json.getJSONObject(key)
                    // `minecraft:camera_preset` declara el id en la RAIZ, no en
                    // `description`: sin el respaldo sus colisiones no se veian.
                    val id = nodo.optJSONObject("description")
                        ?.optString("identifier")
                        ?.takeIf { it.isNotBlank() }
                        ?: nodo.optString("identifier").takeIf { it.isNotBlank() }
                    if (id != null) return id
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    // ── REESCRITURA ───────────────────────────────────────────────────────

    /**
     * Reescribe el id viejo por el nuevo en todo el pack del addon:
     *  - JSON: barrido recursivo de TODOS los valores string; se reemplaza solo
     *    cuando el valor ES EXACTAMENTE el id (cubre description.identifier,
     *    minecraft:ingredient, result.item, spawn_egg, rideable, leashable,
     *    loot entries, familias, eventos y cualquier referencia cruzada).
     *  - .lang: reemplazo exacto por clave/valor.
     *  - .js/.ts/.mcfunction/.txt: regex con límites de palabra (evita que
     *    "mi_mod:espada" toque "mi_mod:espada_spawn_egg").
     */
    private fun rewriteIdentifiersInDir(dir: File, oldId: String, newId: String) {
        val oldIdRegex = Regex("\\b" + Regex.escape(oldId) + "\\b")

        // ⭐ OPTIMIZACIÓN: Usar DirIndexCache en lugar de walkTopDown()
        val cachedFiles = DirIndexCache.index(dir).allFiles
        for (file in cachedFiles) {
            // El índice cacheado puede quedar obsoleto respecto al disco: los
            // renombrados de `renameCollidingFiles` y los ficheros ya movidos
            // hacían que `readText` fallara con ENOENT y se registrara como
            // ERROR (ruido que ocultaba fallos reales en el log del usuario).
            if (!file.isFile) continue
            val ext = file.extension.lowercase(Locale.ROOT)
            if (ext !in setOf("json", "lang", "mcfunction", "txt") + SCRIPT_EXTENSIONS) continue
            try {
                when (ext) {
                    "json" -> rewriteIdentifiersInJson(dir, file, oldId, newId)
                    "lang" -> rewriteIdentifiersInLang(file, oldId, newId)
                    else -> rewriteIdentifiersWithWordBoundary(file, oldIdRegex, newId)
                }
            } catch (e: java.io.FileNotFoundException) {
                // Fichero renombrado/eliminado por otra pasada: no es un error.
                PackForgeLog.d(TAG, "  · omitido (ya no existe): ${file.name}")
            } catch (e: Exception) {
                PackForgeLog.e(TAG, "No se pudo reescribir ${file.name}: ${e.message}")
            }
        }
    }

    /** Barrido profundo: sustituye TODO valor string igual al id viejo en el JSON completo.
     *  Delegado al rewriter compartido (política trim-aware única del pipeline).
     *
     *  Además reescribe el id cuando aparece como CLAVE en los catálogos que
     *  Bedrock indexa por identificador (`blocks.json`, `texture_data`,
     *  `entity_sounds.entities`). Sin esto, renombrar un bloque dejaba su entrada
     *  de `blocks.json` con el id viejo y el bloque renombrado perdía su mapeo de
     *  textura en el juego (bloque “?”).
     */
    private fun rewriteIdentifiersInJson(root: File, file: File, oldId: String, newId: String) {
        val text = file.readText(StandardCharsets.UTF_8)
        // Fast-path: si el id no aparece como texto, no parsear nada.
        if (!text.contains(oldId)) return
        val json = JSONObject(text)
        val renames = mapOf(oldId to newId)
        var changed = JsonValueRewriter.replaceValues(json, renames)

        val relPath = file.relativeTo(root).path.replace('\\', '/')
        val catalogos = catalogPathsFor(relPath)
        if (catalogos.isNotEmpty()) {
            changed = JsonValueRewriter.replaceKeys(json, renames, catalogos::contains) || changed
        }
        if (changed) {
            file.writeText(json.toString(4), StandardCharsets.UTF_8)
        }
    }

    /**
     * Rutas (dentro del objeto) cuyas claves SON identificadores, por archivo.
     *
     * Lista blanca deliberada: fuera de estos catálogos una clave es un nombre
     * de componente, un hueso o un evento, y renombrarla rompería el archivo.
     */
    private fun catalogPathsFor(relativePath: String): Set<String> = when {
        relativePath.equals("blocks.json", ignoreCase = true) -> setOf("")
        relativePath.equals("textures/terrain_texture.json", ignoreCase = true) ||
            relativePath.equals("textures/item_texture.json", ignoreCase = true) -> setOf("texture_data")
        // `sounds.json` usa el id de entidad como clave de `entity_sounds.entities`.
        relativePath.equals("sounds.json", ignoreCase = true) -> setOf("entity_sounds/entities")
        else -> emptySet()
    }

    /** Reemplaza en .lang con límite de palabra para claves y valores */
    private fun rewriteIdentifiersInLang(file: File, oldId: String, newId: String) {
        val oldIdRegex = Regex("\\b" + Regex.escape(oldId) + "\\b")
        val text = file.readText(StandardCharsets.UTF_8)
        val newText = oldIdRegex.replace(text, newId)
        if (newText != text) {
            file.writeText(newText, StandardCharsets.UTF_8)
        }
    }

    /** Reemplazo con límite de palabra para .js/.ts/.mcfunction/.txt */
    private fun rewriteIdentifiersWithWordBoundary(file: File, regex: Regex, newId: String) {
        val text = file.readText(StandardCharsets.UTF_8)
        val newText = regex.replace(text, newId)
        if (newText != text) {
            file.writeText(newText, StandardCharsets.UTF_8)
        }
    }

    /**
     * Renombra los ficheros que colisionan por ruta:
     *  - ficheros que DECLARAN el id renombrado (entities/, items/, recipes/, entity/<nombre>.client.json)
     *  - ficheros cuyo nombre base coincide con el nombre del id (p.ej. loot_tables/entities/mob.json)
     * para que, tras el renombre, no se pisen mutuamente en el pack fusionado.
     */
    private fun renameCollidingFiles(dir: File, oldId: String, newId: String) {
        val oldName = sanitizeIdName(oldId.substringAfter(':', oldId))
        // ⭐ OPTIMIZACIÓN: Usar DirIndexCache en lugar de walkTopDown()
        val cachedFiles = DirIndexCache.index(dir).jsonFiles
        for (file in cachedFiles) {
            if (file.name.equals("manifest.json", true)) continue
            if (!file.isFile) continue

            // Lectura única + filtro de texto: solo se parsea el JSON cuando el
            // fichero realmente puede declarar el id viejo. Antes se parseaba
            // CADA json del pack por cada id renombrado (O(ids × archivos)).
            val text = try {
                file.readText(StandardCharsets.UTF_8)
            } catch (e: Exception) {
                continue
            }
            val declaresOldId = text.contains("identifier") &&
                readDeclaredIdentifier(file, text) == oldId
            val baseMatches = if (declaresOldId) false else {
                // Sin incluir sufijos de tipo: entity, client, behaviour, etc.
                val stem = file.nameWithoutExtension
                    .substringBefore(".entity")
                    .substringBefore(".client")
                stem == oldName
            }
            if (!declaresOldId && !baseMatches) continue

            val suffix = "_${PF_NAMESPACE}_${sanitizeIdName(oldId.substringAfter(':', oldId))}"
            val newPath = File(file.parentFile, "${file.nameWithoutExtension}${suffix}.json")
            if (newPath.absolutePath != file.absolutePath && !newPath.exists()) {
                file.renameTo(newPath)
                PackForgeLog.d(TAG, "   📄 fichero renombrado: ${file.name} → ${newPath.name}")
            }
        }
    }

    // ── HELPERS ───────────────────────────────────────────────────────────

    /**
     * Clave de addon: agrupa el BP y el RP que pertenecen al MISMO `.mcaddon`.
     *
     * ⭐ RENDIMIENTO (punto 1): antes el orquestador copiaba cada subcarpeta a
     * `separated_bp_<estampa>` / `separated_rp_<estampa>` y esta función deducía
     * la agrupación **parseando esa estampa del nombre**. Esa copia duplicaba el
     * addon entero en disco sin aportar nada, así que ahora el orquestador
     * declara la agrupación en [AddonOwnerRegistry] y se consulta aquí.
     *
     * Se conserva la lectura por nombre como respaldo para que cualquier ruta
     * antigua (o una carpeta que nadie registró) siga agrupándose igual que antes.
     */
    private fun ownerKey(dir: File): String {
        AddonOwnerRegistry.ownerOf(dir)?.let { return it }
        val ts = Regex("separated_(?:bp|rp)_(\\d+)").find(dir.name)?.groupValues?.get(1)
        return if (ts != null) "sep_$ts" else dir.absolutePath
    }

    /**
     * Token `_pf<hex6>` ESTABLE entre exportaciones del mismo addon.
     *
     * Antes salía de `ownerKey.hashCode()`, y esa clave solía ser la ruta
     * extraída (`extracted_<epochMs>_…`) o un índice de importación: el token
     * cambiaba en cada exportación y los identificadores renombrados que ya
     * viven en los mundos (`pf:xxxx_stone`) dejaban de resolverse al
     * reinstalar. Ahora se deriva de la SEMILLA estable del addon (UUID de sus
     * manifiestos, ver [AddonOwnerRegistry.stableSeed]) con SHA-256, igual que
     * el resto de tokens `_pf` del motor.
     */
    private fun tokenFor(ownerKey: String): String {
        val semilla = AddonOwnerRegistry.seedOf(ownerKey) ?: ownerKey
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(semilla.toByteArray(StandardCharsets.UTF_8))
            val sb = StringBuilder(6)
            for (i in 0 until 3) sb.append("%02x".format(digest[i]))
            sb.toString()
        } catch (_: Exception) {
            semilla.hashCode().toUInt().toString(16).padStart(6, '0').substring(0, 6)
        }
    }

    private fun shortOwner(ownerKey: String): String {
        // Los addons registrados por el orquestador ya tienen nombre legible.
        AddonOwnerRegistry.labelOf(ownerKey)?.let { return it }
        if (ownerKey.startsWith("sep_")) return "BOTH(ts=${ownerKey.removePrefix("sep_")})"
        return File(ownerKey).name
    }

    /** Nombre válido de id: minúsculas, alfanumérico + guión bajo. */
    private fun sanitizeIdName(raw: String): String {
        val s = raw.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9_]"), "_")
            .trim('_')
        return s.ifEmpty { "item" }
    }

    private fun File.relativeOrName(): String = name
}