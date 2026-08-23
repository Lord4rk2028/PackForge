package com.packforge.app.domain.engine

import com.packforge.app.util.PackForgeLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale
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
        "minecraft:recipe_shaped",
        "minecraft:recipe_shapeless",
        "minecraft:recipe_furnace",
        "minecraft:recipe_brewing_mix",
        "minecraft:recipe_potion",
        "minecraft:recipe_container_mix"
    )

    /**
     * Detecta colisiones de identificadores entre addons, renombra los que
     * chocan y reescribe sus referencias. Devuelve el reporte de renombres.
     *
     * @param behaviorDirs directorios BP por addon (cada uno = raíz de un pack)
     * @param resourceDirs directorios RP por addon
     */
    fun run(behaviorDirs: List<File>, resourceDirs: List<File>): List<RemapEntry> {
        val allDirs = behaviorDirs + resourceDirs
        if (allDirs.size < 2) return emptyList()

        return try {
            // ── 1) Agrupar directorios por addon original ──────────────
            // Un addon BOTH aparece como 2 carpetas (separated_bp_TS, separated_rp_TS)
            // con el MISMO timestamp; ambos pertenecen al mismo addon.
            val dirsByOwner = LinkedHashMap<String, MutableList<File>>()
            allDirs.forEach { dir ->
                dirsByOwner.getOrPut(ownerKey(dir)) { mutableListOf() }.add(dir)
            }

            if (dirsByOwner.size < 2) return emptyList()

            // ── 2) Recolectar identificadores DECLARADOS por cada addon ─
            // Solo cuentan los declarados en behavior packs (evita que el BP y el
            // RP del MISMO addon se "colisionen" a sí mismos).
            val declaredByOwner = LinkedHashMap<String, MutableMap<String, File>>()
            dirsByOwner.forEach { (owner, dirs) ->
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

                // Ganador = el primer addon en orden de prioridad
                val winner = owners.first()
                owners.drop(1).forEachIndexed { i, loser ->
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
     */
    private fun collectDeclaredIds(packDir: File): Map<String, File>? {
        val result = mutableMapOf<String, File>()
        try {
            packDir.walkTopDown().forEach { file ->
                if (file.isFile && file.extension.equals("json", ignoreCase = true)) {
                    val id = readDeclaredIdentifier(file) ?: return@forEach
                    // Evitar renombrar manifest.json por accidente
                    if (file.name.equals("manifest.json", true)) return@forEach
                    result[id] = file
                }
            }
        } catch (e: Exception) {
            PackForgeLog.e(TAG, "Error escaneando ${packDir.name}: ${e.message}")
        }
        return result.ifEmpty { null }
    }

    /** Lee `description.identifier` del JSON si alguna raíz lo declara. */
    private fun readDeclaredIdentifier(file: File): String? {
        return try {
            val json = JSONObject(file.readText(StandardCharsets.UTF_8))
            for (key in json.keys()) {
                if (key in DECLARING_KEYS) {
                    val id = json.getJSONObject(key)
                        .optJSONObject("description")
                        ?.optString("identifier")
                        ?.takeIf { it.isNotBlank() }
                    if (id != null) return id
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    // ── REESCRITURA ───────────────────────────────────────────────────────

    /** Reemplaza el id viejo por el nuevo SOLO en campos de identificador de JSONs.
     * Para .lang y .mcfunction usa límites de palabra para evitar coincidencias parciales. */
    private fun rewriteIdentifiersInDir(dir: File, oldId: String, newId: String) {
        val oldIdRegex = Regex("\\b" + Regex.escape(oldId) + "\\b")
        
        dir.walkTopDown().forEach { file ->
            if (!file.isFile) return@forEach
            val ext = file.extension.lowercase(Locale.ROOT)
            if (ext !in setOf("json", "lang", "mcfunction", "txt")) return@forEach
            try {
                when (ext) {
                    "json" -> rewriteIdentifiersInJson(file, oldId, newId)
                    "lang" -> rewriteIdentifiersInLang(file, oldId, newId)
                    else -> rewriteIdentifiersWithWordBoundary(file, oldIdRegex, newId)
                }
            } catch (e: Exception) {
                PackForgeLog.e(TAG, "No se pudo reescribir ${file.name}: ${e.message}")
            }
        }
    }

    /** Reescribe IDs en JSON solo en campos conocidos de identificador (description.identifier, etc.) */
    private fun rewriteIdentifiersInJson(file: File, oldId: String, newId: String) {
        val text = file.readText(StandardCharsets.UTF_8)
        val json = JSONObject(text)
        var changed = false
        
        // Claves que contienen identificadores en la estructura de Bedrock
        val idPaths = listOf(
            "description.identifier",
            "minecraft:entity.description.identifier",
            "minecraft:item.description.identifier",
            "minecraft:block.description.identifier",
            "minecraft:client_entity.description.identifier",
            "minecraft:recipe_shaped.key",
            "minecraft:recipe_shapeless.key",
            "minecraft:recipe_furnace.input",
            "minecraft:recipe_brewing_mix.input",
            "minecraft:recipe_potion.input",
            "minecraft:recipe_container_mix.input",
            "minecraft:loot_table.pools.entries.name",
            "minecraft:trade_table.tiers.wants.item",
            "minecraft:spawn_rules.description.identifier"
        )
        
        // Buscar y reemplazar recursivamente en paths conocidos
        idPaths.forEach { path ->
            changed = replaceIdAtPath(json, path.split("."), oldId, newId) || changed
        }
        
        // También buscar en campos "identifier" en cualquier nivel (genérico)
        changed = replaceIdInAnyIdentifierField(json, oldId, newId) || changed
        
        if (changed) {
            file.writeText(JsonDeepMerger.cleanJsonObject(json).toString(4), StandardCharsets.UTF_8)
        }
    }

    /** Reemplaza ID en una ruta específica del JSON (ej: description.identifier) */
    private fun replaceIdAtPath(obj: JSONObject, path: List<String>, oldId: String, newId: String): Boolean {
        var current: Any? = obj
        var changed = false
        
        for (idx in path.indices) {
            val key = path[idx]
            val isLast = idx == path.lastIndex
            
            when (current) {
                is JSONObject -> {
                    if (isLast) {
                        val currentVal = current.optString(key, "")
                        if (currentVal == oldId) {
                            current.put(key, newId)
                            changed = true
                        }
                    } else {
                        current = current.opt(key)
                        if (current == null) return false
                    }
                }
                is JSONArray -> {
                    // Intentar buscar en arrays (como loot tables pools)
                    for (i in 0 until current.length()) {
                        val item = current.opt(i)
                        if (item is JSONObject) {
                            val result = replaceIdAtPath(item, path.subList(idx + 1, path.size), oldId, newId)
                            changed = changed || result
                        }
                    }
                    return changed
                }
                else -> return false
            }
        }
        return changed
    }

    /** Reemplaza cualquier campo llamado "identifier" en cualquier nivel del JSON */
    private fun replaceIdInAnyIdentifierField(obj: Any, oldId: String, newId: String): Boolean {
        var changed = false
        when (obj) {
            is JSONObject -> {
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key == "identifier") {
                        val value = obj.optString(key, "")
                        if (value == oldId) {
                            obj.put(key, newId)
                            changed = true
                        }
                    } else {
                        changed = replaceIdInAnyIdentifierField(obj.get(key), oldId, newId) || changed
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until obj.length()) {
                    changed = replaceIdInAnyIdentifierField(obj.get(i), oldId, newId) || changed
                }
            }
        }
        return changed
    }

    /** Reemplaza en .lang solo líneas completas clave=valor donde el valor o clave coincida exactamente */
    private fun rewriteIdentifiersInLang(file: File, oldId: String, newId: String) {
        val lines = file.readLines(StandardCharsets.UTF_8).map { line ->
            if (line.contains("=") && !line.startsWith("#")) {
                val parts = line.split("=", limit = 2)
                if (parts.size == 2) {
                    val key = parts[0].trim()
                    val value = parts[1].trim()
                    // Reemplazar solo si la clave o valor ES EXACTAMENTE el oldId
                    val newKey = if (key == oldId) newId else key
                    val newValue = if (value == oldId) newId else value
                    "$newKey=$newValue"
                } else line
            } else line
        }
        file.writeText(lines.joinToString("\n"), StandardCharsets.UTF_8)
    }

    /** Reemplazo con límite de palabra para .mcfunction y .txt */
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
        dir.walkTopDown().forEach { file ->
            if (!file.isFile) return@forEach
            if (file.name.equals("manifest.json", true)) return@forEach
            val ext = file.extension.lowercase(Locale.ROOT)
            if (ext != "json") return@forEach

            val declaresOldId = try {
                readDeclaredIdentifier(file) == oldId
            } catch (e: Exception) {
                false
            }
            val baseMatches = if (declaresOldId) false else {
                // Sin incluir sufijos de tipo: entity, client, behaviour, etc.
                val stem = file.nameWithoutExtension
                    .substringBefore(".entity")
                    .substringBefore(".client")
                stem == oldName
            }
            if (!declaresOldId && !baseMatches) return@forEach

            val suffix = "_${PF_NAMESPACE}_${sanitizeIdName(oldId.substringAfter(':', oldId))}"
            val newPath = File(file.parentFile, "${file.nameWithoutExtension}${suffix}.json")
            if (newPath.absolutePath != file.absolutePath && !newPath.exists()) {
                file.renameTo(newPath)
                PackForgeLog.d(TAG, "   📄 fichero renombrado: ${file.name} → ${newPath.name}")
            }
        }
    }

    // ── HELPERS ───────────────────────────────────────────────────────────

    /** Clave de addon: agrupa separated_bp_TS con separated_rp_TS del mismo BOTH. */
    private fun ownerKey(dir: File): String {
        val ts = Regex("separated_(?:bp|rp)_(\\d+)").find(dir.name)?.groupValues?.get(1)
        return if (ts != null) "sep_$ts" else dir.absolutePath
    }

    /** Token estable por addon (hex corto de su clave). */
    private fun tokenFor(ownerKey: String): String {
        return ownerKey.hashCode().toUInt().toString(16).padStart(6, '0').substring(0, 6)
    }

    private fun shortOwner(ownerKey: String): String {
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