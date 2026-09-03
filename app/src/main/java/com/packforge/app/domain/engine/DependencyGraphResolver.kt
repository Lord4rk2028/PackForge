package com.packforge.app.domain.engine

import com.packforge.app.domain.model.ConflictSeverity
import com.packforge.app.util.PackForgeLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * ═══════════════════════════════════════════════════════════════════════
 * RESOLUTOR DE DEPENDENCIAS POR GRAFO
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Sustituye la copia estática por una dinámica basada en referencias reales:
 *
 *   FASE 1  Escanea TODOS los addons de entrada y construye [DependencyIndex].
 *   FASE 2  Siembra los ids YA presentes en el pack fusionado (no recopia).
 *   FASE 3  Para cada archivo crítico del destino (entity/items) extrae sus
 *           referencias y ejecuta resolveAndCopyDependencies() de forma
 *           RECURSIVA (un render controller copiado puede requerir texturas,
 *           que a su vez…).
 *   FASE 4  Si queda algún CriticalError (referencia no vanilla sin origen),
 *           el llamador ABORTA: borra temporales y NO genera el .mcaddon.
 *
 * Agnóstico al renombrado de IDs: se ejecuta DESPUÉS de IdentifierRemapper,
 * por lo que índice y referencias ya usan los ids finales (pf_xxx incluidos).
 */
object DependencyGraphResolver {

    private const val TAG = "PackForge_Graph"

    // ── TIPOS DE RECURSO ───────────────────────────────────────────────────

    enum class ResType(val label: String) {
        GEOMETRY("geometría"),
        ANIMATION("animación"),
        ANIM_CONTROLLER("animation controller"),
        RENDER_CONTROLLER("render controller"),
        PARTICLE("partícula"),
        SOUND_DEF("definición de sonido"),
        FILE_PATH("archivo"),
        SPAWN_RULES("spawn rules"),
        BIOME("bioma"),
        FEATURE_RULES("feature rules"),
        MATERIAL("material"),
        MUSIC_DEFINITION("music definition");
    }

    /** Entrada del índice: quién ofrece qué y desde dónde. */
    data class Entry(
        val identifier: String,
        val type: ResType,
        val sourceFile: File,
        val addonOrigin: String,
        /** Raiz del addon origen. */
        val sourceRoot: File,
        /** Subárbol JSON para recursos definidos dentro de un archivo compartido (sound_definitions). */
        val payload: JSONObject? = null
    )

    /** Referencia detectada en un JSON del destino. */
    data class Ref(val type: ResType, val id: String)

    /** Referencia irrecuperable y NO vanilla. */
    data class CriticalError(val type: ResType, val id: String, val requester: String)

    data class GraphResult(
        val criticalErrors: List<CriticalError>,
        val copiedByType: Map<ResType, Int>,
        val notes: List<String>
    )

    // ── ÍNDICE (HashMap en memoria) ────────────────────────────────────────

    class DependencyIndex {
        val byType: HashMap<ResType, HashMap<String, MutableList<Entry>>> = HashMap()
        /** Rutas físicas relativas (textures/, sounds/, scripts/, loot_tables/…) → archivo. */
        val rawPaths: HashMap<String, Entry> = HashMap()

        fun put(entry: Entry) {
            byType.getOrPut(entry.type) { HashMap() }.getOrPut(entry.identifier) { mutableListOf() }.add(entry)
        }

        fun get(type: ResType, id: String): List<Entry> = byType[type]?.get(id).orEmpty()

        fun putRaw(relPath: String, entry: Entry) { rawPaths[relPath.lowercase(Locale.ROOT)] = entry }
        fun getRaw(relPath: String): Entry? = rawPaths[relPath.lowercase(Locale.ROOT)]
    }

    // ── LISTA BLANCA VANILLA ───────────────────────────────────────────────

    object VanillaResources {

        private val ENTITIES = setOf(
            "agent", "allay", "area_effect_cloud", "armor_stand", "arrow", "axolotl", "bat", "bee",
            "blaze", "bogged", "breeze", "cat", "cave_spider", "chest_minecart", "chicken", "cod",
            "command_block_minecart", "cow", "creaking", "creeper", "dolphin", "donkey", "dragon_fireball",
            "drowned", "egg", "elder_guardian", "enderman", "endermite", "ender_dragon", "evocation_fang",
            "evocation_illager", "eye_of_ender_signal", "fox", "frog", "ghast", "glow_squid", "goat",
            "guardian", "hoglin", "hopper_minecart", "horse", "husk", "iron_golem", "lightning_bolt",
            "lingering_potion", "llama", "llama_spit", "magma_cube", "minecart", "mooshroom", "mule",
            "npc", "ocelot", "panda", "parrot", "phantom", "pig", "piglin", "piglin_brute", "pillager",
            "player", "polar_bear", "pufferfish", "rabbit", "ravager", "salmon", "sheep", "shulker",
            "shulker_bullet", "silverfish", "skeleton", "skeleton_horse", "slime", "small_fireball",
            "sniffer", "snowball", "snow_golem", "spider", "splash_potion", "squid", "stray", "strider",
            "tadpole", "thrown_trident", "tnt", "tnt_minecart", "trader_llama", "tripod_camera", "tropicalfish",
            "turtle", "vex", "villager", "villager_v2", "vindicator", "wandering_trader", "warden",
            "witch", "wither", "wither_skeleton", "wither_skull", "wolf", "xp_bottle", "xp_orb",
            "zoglin", "zombie", "zombie_horse", "zombie_pigman", "zombie_villager", "zombie_villager_v2",
            "armadillo", "creaking_transient", "wind_charge_projectile"
        )

        private const val HUMANOID_FAMILY = "humanoid"

        private val GEOMETRY_EXACT = setOf(
            "geometry.player", "geometry.humanoid.custom", "geometry.humanoid.customSlim",
            "geometry.armor_stand", "geometry.item_sprite", "geometry.block", "geometry.flat_item",
            "geometry.chicken_v2", "geometry.zombie_v2", "geometry.villager_v2", "geometry.wolf_v2"
        )

        private val GEOMETRY_PREFIXES = listOf(
            "geometry.humanoid", "geometry.armor.", "geometry.zombie", "geometry.skeleton",
            "geometry.villager", "geometry.piglin", "geometry.llama", "geometry.horse",
            "geometry.boat", "geometry.minecart", "geometry.arrow", "geometry.parrot",
            "geometry.tropicalfish", "geometry.evoker", "geometry.vindicator", "geometry.phantom"
        )

        private val ANIMATION_PREFIXES = listOf(
            "animation.humanoid", "animation.villager", "animation.villager_v2", "animation.armor",
            "animation.biped", "animation.creeper", "animation.zombie", "animation.skeleton",
            "animation.parrot", "animation.look_at_target", "animation.base_pose",
            "animation.ghast", "animation.blaze", "animation.general", "animation.enderman",
            "animation.spider", "animation.quadruped", "animation.irongolem", "animation.guardian",
            "animation.shulker", "animation.slime", "animation.magmacube", "animation.wither",
            "animation.dragon", "animation.agent", "animation.armor_stand", "animation.player"
        )

        private val RENDER_CONTROLLERS = setOf(
            "controller.render.default", "controller.render.item_sprite", "controller.render.block",
            "controller.render.armor_stand", "controller.render.player.first_person",
            "controller.render.player.third_person", "controller.render.locator", "controller.render.ui_hurt"
        )

        /** Carpetas de texturas abrumadoramente vanilla: si no está en ningún pack, es del juego. */
        private val VANILLA_TEXTURE_DIRS = listOf(
            "textures/block/", "textures/items/", "textures/item/", "textures/environment/",
            "textures/particle/", "textures/ui/", "textures/painting/", "textures/misc/",
            "textures/entity/"
        )

        fun isVanilla(type: ResType, id: String): Boolean {
            if (id.startsWith("minecraft:")) return true
            return when (type) {
                ResType.GEOMETRY -> {
                    val body = id.removePrefix("geometry.")
                    val base = body.substringBefore(".v").substringBefore(".custom")
                    id in GEOMETRY_EXACT ||
                        base in ENTITIES || body in ENTITIES ||
                        GEOMETRY_PREFIXES.any { id.startsWith(it) } ||
                        (body.contains('.') && body.substringBefore('.') in ENTITIES)
                }
                ResType.ANIMATION -> ANIMATION_PREFIXES.any { id.startsWith(it) } ||
                    id.substringAfter("animation.", "").substringBefore('.') in ENTITIES
                ResType.ANIM_CONTROLLER -> id.startsWith("controller.animation.")
                ResType.RENDER_CONTROLLER -> {
                    id in RENDER_CONTROLLERS ||
                        id.substringAfter("controller.render.", "").substringBefore('.') in ENTITIES
                }
                ResType.PARTICLE -> false // las partículas vanilla SIEMPRE traen namespace minecraft:
                ResType.SOUND_DEF -> id.startsWith("mob.") || id.startsWith("block.") ||
                    id.startsWith("item.") || id.startsWith("ambient.") || id.startsWith("random.")
                ResType.FILE_PATH -> {
                    val lower = id.lowercase(Locale.ROOT)
                    if (VANILLA_TEXTURE_DIRS.any { lower.startsWith(it) }) {
                        // Texturas de entidades vanilla viven en el juego; las custom NO.
                        val stem = lower.substringAfterLast('/').substringBefore('.')
                        stem in ENTITIES
                    } else lower.startsWith("loot_tables/entities/")
                }
                ResType.SPAWN_RULES -> false // spawn_rules custom siempre son externas
                ResType.BIOME -> false // biomes custom siempre son externos
                ResType.FEATURE_RULES -> false // feature_rules custom siempre son externos
                ResType.MATERIAL -> id.startsWith("entity.") || id.startsWith("block.") ||
                    id.startsWith("item.") || id.startsWith("env.") // materiales vanilla
                ResType.MUSIC_DEFINITION -> id.startsWith("minecraft:") // music def vanilla
            }
        }
    }

    // ── EXTRACCIÓN GENÉRICA DE REFERENCIAS ─────────────────────────────────

    private val PATH_EXT = setOf("json", "png", "jpg", "jpeg", "tga", "webp", "ogg", "wav", "fsb", "js", "mjs", "ts", "mcs")

    private val compiledPathRegex = Regex("""^[^\\/:*?"<>|\s]+(/[^\\/:*?"<>|\s]+)+\.[A-Za-z0-9]{2,5}$""")

    private fun collectRefs(node: Any?, out: MutableList<Ref>) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    classify(node.opt(key), key.lowercase(Locale.ROOT), out)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) classify(node.opt(i), "", out)
            }
        }
    }

    private fun classify(value: Any?, parentKey: String, out: MutableList<Ref>) {
        when (value) {
            is String -> classifyString(value, parentKey, out)
            is JSONObject, is JSONArray -> collectRefs(value, out)
        }
    }

    private fun classifyString(v: String, parentKey: String, out: MutableList<Ref>) {
        if (v.length < 4) return
        if (v.startsWith("minecraft:")) return // namespace vanilla: nunca es dependencia externa

        val slash = v.indexOf('/')
        if (slash > 0) {
            val ext = v.substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (ext in PATH_EXT && compiledPathRegex.matches(v)) {
                out.add(Ref(ResType.FILE_PATH, v))
                return
            }
        }

        when {
            v.startsWith("geometry.") -> out.add(Ref(ResType.GEOMETRY, v))
            v.startsWith("animation_controllers.") -> out.add(Ref(ResType.ANIM_CONTROLLER, v))
            v.startsWith("controller.") -> out.add(Ref(ResType.RENDER_CONTROLLER, v))
            v.startsWith("animation.") -> out.add(Ref(ResType.ANIMATION, v))
            v.startsWith("particle.") -> out.add(Ref(ResType.PARTICLE, v))
            v.startsWith("scripts.") || parentKey == "scripts" ->
                out.add(Ref(ResType.FILE_PATH, v))
            parentKey.contains("texture") && v.contains('/') ->
                out.add(Ref(ResType.FILE_PATH, v))
            parentKey.contains("particle") && v.contains(':') ->
                out.add(Ref(ResType.PARTICLE, v))
            parentKey == "biomes" || parentKey == "biome" ->
                out.add(Ref(ResType.BIOME, v))
            parentKey == "feature" || parentKey == "feature_rules" ->
                out.add(Ref(ResType.FEATURE_RULES, v))
            parentKey == "material" || parentKey.contains("material") ->
                out.add(Ref(ResType.MATERIAL, v))
            parentKey == "music" || parentKey == "music_definitions" ->
                out.add(Ref(ResType.MUSIC_DEFINITION, v))
            parentKey == "spawn_rules" || parentKey == "spawn_rule" ->
                out.add(Ref(ResType.SPAWN_RULES, v))
        }
    }

    // ── CONSTRUCCIÓN DEL ÍNDICE (FASE 1) ───────────────────────────────────

    private fun buildIndex(rpDirs: List<File>, bpDirs: List<File>): DependencyIndex {
        val index = DependencyIndex()

        fun addonTag(root: File): String = root.name

        fun scan(root: File, filter: (String) -> Boolean, handler: (File, JSONObject) -> Unit) {
            if (!root.isDirectory) return
            root.walkTopDown().filter { it.isFile && it.extension.equals("json", true) }.forEach { file ->
                val rel = file.relativeTo(root).path.replace("\\", "/")
                if (!filter(rel)) return@forEach
                try {
                    handler(file, JSONObject(file.readText(StandardCharsets.UTF_8)))
                } catch (_: Exception) {}
            }
        }

        for (root in rpDirs) {
            val tag = addonTag(root)
            // Geometrías
            scan(root, { it.endsWith(".geo.json", true) }) { file, json ->
                json.optJSONArray("minecraft:geometry")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.optJSONObject("description")?.optString("identifier")
                            ?.trim()?.takeIf { it.isNotBlank() }
                            ?.let { index.put(Entry(it, ResType.GEOMETRY, file, tag, root)) }
                    }
                }
            }
            // Animaciones + controllers + render controllers + partículas
            scan(root, { under(it, "animations") || under(it, "animation_controllers") || under(it, "render_controllers") || under(it, "particles") }) { file, json ->
                val rel = file.relativeTo(root).path.replace("\\", "/")
                json.optJSONObject("animations")?.keys()?.forEachRemaining { id ->
                    index.put(Entry(id.trim(), ResType.ANIMATION, file, tag, root))
                }
                json.optJSONObject("animation_controllers")?.keys()?.forEachRemaining { id ->
                    index.put(Entry(id.trim(), ResType.ANIM_CONTROLLER, file, tag, root))
                }
                json.optJSONObject("render_controllers")?.keys()?.forEachRemaining { id ->
                    index.put(Entry(id.trim(), ResType.RENDER_CONTROLLER, file, tag, root))
                }
                if (under(rel, "particles")) {
                    json.optJSONObject("particle_effect")?.optJSONObject("description")
                        ?.optString("identifier")?.trim()?.takeIf { it.isNotBlank() }
                        ?.let { index.put(Entry(it, ResType.PARTICLE, file, tag, root)) }
                }
            }
            // Definiciones de sonido (con payload para fusión parcial)
            val sdFile = File(root, "sound_definitions.json")
            if (sdFile.exists()) {
                try {
                    val json = JSONObject(sdFile.readText(StandardCharsets.UTF_8))
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (k == "format_version") continue
                        val payload = json.opt(k)
                        if (payload is JSONObject) index.put(Entry(k.trim(), ResType.SOUND_DEF, sdFile, tag, root, payload))
                    }
                } catch (_: Exception) {}
            }

            // Materials (RP materials/*.material)
            scan(root, { it.contains("materials/") && (it.endsWith(".material", true) || it.endsWith(".entity.material.json", true)) }) { file, json ->
                json.optJSONObject("materials")?.keys()?.forEachRemaining { id ->
                    index.put(Entry(id.trim(), ResType.MATERIAL, file, tag, root))
                }
                json.optJSONObject("description")?.optString("identifier")
                    ?.trim()?.takeIf { it.isNotBlank() }
                    ?.let { index.put(Entry(it, ResType.MATERIAL, file, tag, root)) }
            }

            // Music definitions (RP sounds/music_definitions.json)
            val musicFile = File(root, "sounds/music_definitions.json")
            if (musicFile.exists()) {
                try {
                    val json = JSONObject(musicFile.readText(StandardCharsets.UTF_8))
                    json.keys().forEachRemaining { k ->
                        if (k != "format_version") {
                            index.put(Entry(k.trim(), ResType.MUSIC_DEFINITION, musicFile, tag, root))
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // BP-only resources: spawn_rules, biomes, feature_rules, features
        bpDirs.forEach { root ->
            val tag = addonTag(root)

            // Spawn rules (BP/spawn_rules/*.json)
            scan(root, { it.contains("spawn_rules/") && it.endsWith(".json", true) }) { file, json ->
                json.optJSONObject("minecraft:spawn_rules")?.optJSONObject("description")
                    ?.optString("identifier")?.trim()?.takeIf { it.isNotBlank() }
                    ?.let { index.put(Entry(it, ResType.SPAWN_RULES, file, tag, root)) }
                val fallback = "spawn_rule:${file.nameWithoutExtension}"
                if (index.get(ResType.SPAWN_RULES, fallback).isEmpty()) {
                    index.put(Entry(fallback, ResType.SPAWN_RULES, file, tag, root))
                }
            }

            // Biomes (BP/biomes/*.json)
            scan(root, { it.contains("biomes/") && it.endsWith(".json", true) }) { file, json ->
                json.optJSONObject("minecraft:biome")?.optJSONObject("description")
                    ?.optString("identifier")?.trim()?.takeIf { it.isNotBlank() }
                    ?.let { index.put(Entry(it, ResType.BIOME, file, tag, root)) }
            }

            // Feature rules (BP/feature_rules/*.json)
            scan(root, { it.contains("feature_rules/") && it.endsWith(".json", true) }) { file, json ->
                json.optJSONObject("minecraft:feature_rules")?.optJSONObject("description")
                    ?.optString("identifier")?.trim()?.takeIf { it.isNotBlank() }
                    ?.let { index.put(Entry(it, ResType.FEATURE_RULES, file, tag, root)) }
            }

            // Features (BP/features/*.json)
            scan(root, { it.contains("features/") && !it.contains("feature_rules/") && it.endsWith(".json", true) }) { file, json ->
                json.optJSONObject("minecraft:feature")?.optJSONObject("description")
                    ?.optString("identifier")?.trim()?.takeIf { it.isNotBlank() }
                    ?.let { index.put(Entry(it, ResType.FEATURE_RULES, file, tag, root)) }
            }
        }

        // Índice de rutas físicas crudas (texturas/sonidos/scripts/loot/…) sobre AMBOS lados
        (rpDirs + bpDirs).forEach { root ->
            if (!root.isDirectory) return@forEach
            val tag = addonTag(root)
            root.walkTopDown().filter { it.isFile }.forEach { file ->
                val rel = file.relativeTo(root).path.replace("\\", "/")
                index.putRaw(rel, Entry(rel, ResType.FILE_PATH, file, tag, root))
            }
        }
        return index
    }

    private fun under(rel: String, segment: String): Boolean =
        rel.startsWith("$segment/") || rel.contains("/$segment/")

    // ── ESTADO DE RESOLUCIÓN ───────────────────────────────────────────────

    private class ResolveState(
        val index: DependencyIndex,
        val outputRp: File,
        val outputBp: File,
        val resolvedIds: HashMap<ResType, HashSet<String>>,
        val copiedFiles: HashSet<String>,
        val copiedCount: HashMap<ResType, Int>,
        val critical: MutableList<CriticalError>
    ) {
        fun knows(type: ResType, id: String): Boolean = resolvedIds[type]?.contains(id) == true
        fun learn(type: ResType, vararg ids: String) {
            val set = resolvedIds.getOrPut(type) { HashSet() }
            ids.forEach { set.add(it) }
        }
    }

    // ── CORE: RESOLUCIÓN RECURSIVA ─────────────────────────────────────────

    /**
     * Para cada referencia de [targetJson]:
     *  existe en destino → nada · indexada → copiar + RECURSAR · vanilla → ignorar
     *  · resto → CriticalError con mensaje "Falta X requerido por Y".
     */
    private fun resolveAndCopyDependencies(
        targetJson: JSONObject,
        requesterRel: String,
        state: ResolveState
    ) {
        val refs = mutableListOf<Ref>()
        collectRefs(targetJson, refs)

        for (ref in refs.distinct()) {
            when (ref.type) {
                ResType.FILE_PATH -> resolveFilePath(ref.id, requesterRel, state)
                ResType.SOUND_DEF -> resolveSoundDef(ref.id, requesterRel, state)
                else -> resolveTyped(ref, requesterRel, state, depth = 0)
            }
        }
    }

    private fun resolveTyped(ref: Ref, requester: String, state: ResolveState, depth: Int) {
        if (depth > 6) {
            state.learn(ref.type, ref.id)
            return
        }
        if (state.knows(ref.type, ref.id)) return
        if (VanillaResources.isVanilla(ref.type, ref.id)) { state.learn(ref.type, ref.id); return }

        val candidates = state.index.get(ref.type, ref.id)
        if (candidates.isEmpty()) {
            state.critical.add(CriticalError(ref.type, ref.id, requester))
            return
        }

        val cand = candidates.first()
        val destRoot = outputRootFor(ref.type, state)
        val rel = cand.sourceFile.relativeTo(sourceRootOf(cand)).path.replace("\\", "/")
        val destResult = safeResolve(destRoot, rel)

        when (destResult) {
            is SafeResolveResult.Success -> {
                val dest = destResult.file
                if (dest.exists()) {
                    // El archivo existe pero no declara el id (multi-id parcialmente fusionado): fusionar entrada.
                    mergeIdIntoExisting(dest, cand, ref.type, state)
                } else {
                    dest.parentFile?.mkdirs()
                    OutputStreamWriter(FileOutputStream(dest), StandardCharsets.UTF_8).use {
                        it.write(cand.payload?.toString() ?: readFileQuiet(cand.sourceFile))
                    }
                    state.copiedFiles.add(dest.absolutePath)
                    state.copiedCount[ref.type] = (state.copiedCount[ref.type] ?: 0) + 1
                    PackForgeLog.d(TAG, "🔗 [grafo] ${ref.type.label} '${ref.id}' ← ${dest.relativeTo(destRoot).path}")
                    learnIdsFromPayload(cand, state)

                    // TRANSITIVIDAD: el archivo recién copiado puede requerir más cosas.
                    try {
                        val parsed = JSONObject(cand.payload?.toString() ?: readFileQuiet(cand.sourceFile))
                        resolveAndCopyDependencies(parsed, dest.relativeTo(destRoot).path, state)
                    } catch (_: Exception) {}
                }
            }
            is SafeResolveResult.Failure -> {
                PackForgeLog.w(TAG, "⚠️ Ruta insegura para ${ref.type.label} '${ref.id}': ${destResult.reason}")
                state.critical.add(CriticalError(ref.type, ref.id, "ruta insegura (${destResult.reason}): $requester"))
            }
        }
        state.learn(ref.type, ref.id)
    }

    private fun resolveFilePath(path: String, requester: String, state: ResolveState) {
        val normalized = path.replace("\\", "/").trimStart('/')
        // ¿Ya existe en alguno de los dos roots del destino?
        val existsInRp = when (val r = safeResolve(state.outputRp, normalized)) {
            is SafeResolveResult.Success -> r.file.exists()
            else -> false
        }
        val existsInBp = when (val r = safeResolve(state.outputBp, normalized)) {
            is SafeResolveResult.Success -> r.file.exists()
            else -> false
        }
        if (existsInRp || existsInBp) return

        // Vanilla por carpeta (texturas block/item/environment…, loot de mobs vanilla)
        if (VanillaResources.isVanilla(ResType.FILE_PATH, normalized)) return

        val cand = state.index.getRaw(normalized)
        if (cand == null) {
            // Solo es CRÍTICO si el pack lo necesita para verse bien: texturas/sonidos/scripts/animaciones/controllers/render/partículas/models/attachables.
            val isCriticalKind = normalized.startsWith("textures/") ||
                normalized.startsWith("sounds/") || normalized.startsWith("scripts/") ||
                normalized.startsWith("animations/") || normalized.startsWith("animation_controllers/") ||
                normalized.startsWith("render_controllers/") || normalized.startsWith("models/") ||
                normalized.startsWith("particles/") || normalized.startsWith("attachables/")
            if (isCriticalKind) state.critical.add(CriticalError(ResType.FILE_PATH, normalized, requester))
            return
        }

        val targetRoot = guessRoot(normalized, state)
        when (val destResult = safeResolve(targetRoot, normalized)) {
            is SafeResolveResult.Success -> {
                val dest = destResult.file
                if (!dest.exists()) {
                    dest.parentFile?.mkdirs()
                    cand.sourceFile.copyTo(dest, overwrite = true)
                    state.copiedFiles.add(dest.absolutePath)
                    state.copiedCount[ResType.FILE_PATH] = (state.copiedCount[ResType.FILE_PATH] ?: 0) + 1
                    PackForgeLog.d(TAG, "🖼️ [grafo] archivo '$normalized' ← ${cand.sourceFile.name}")
                }
            }
            is SafeResolveResult.Failure -> {
                PackForgeLog.w(TAG, "⚠️ Ruta insegura para archivo '$normalized': ${destResult.reason}")
                state.critical.add(CriticalError(ResType.FILE_PATH, normalized, "ruta insegura (${destResult.reason}): $requester"))
            }
        }
        state.learn(ResType.FILE_PATH, normalized)
    }

    private fun resolveSoundDef(id: String, requester: String, state: ResolveState) {
        val destFile = when (val r = safeResolve(state.outputRp, "sound_definitions.json")) {
            is SafeResolveResult.Success -> r.file
            else -> return
        }
        val existing = if (destFile.exists()) runCatching { JSONObject(destFile.readText(StandardCharsets.UTF_8)) }.getOrNull() ?: JSONObject() else JSONObject()
        if (existing.has(id)) { state.learn(ResType.SOUND_DEF, id); return }
        if (VanillaResources.isVanilla(ResType.SOUND_DEF, id)) { state.learn(ResType.SOUND_DEF, id); return }

        val cand = state.index.get(ResType.SOUND_DEF, id).firstOrNull()
        if (cand?.payload == null) {
            state.critical.add(CriticalError(ResType.SOUND_DEF, id, requester))
            return
        }

        existing.put(id, cand.payload)
        destFile.parentFile?.mkdirs()
        OutputStreamWriter(FileOutputStream(destFile), StandardCharsets.UTF_8).use { it.write(existing.toString()) }
        state.copiedCount[ResType.SOUND_DEF] = (state.copiedCount[ResType.SOUND_DEF] ?: 0) + 1
        state.learn(ResType.SOUND_DEF, id)
        PackForgeLog.d(TAG, "🔊 [grafo] definición de sonido '$id' añadida")

        // Copiar los archivos .ogg/.wav que esa definición referencia.
        val refs = mutableListOf<Ref>()
        collectRefs(cand.payload, refs)
        refs.filter { it.type == ResType.FILE_PATH }.forEach { resolveFilePath(it.id, "sound:$id", state) }
    }

    private fun mergeIdIntoExisting(dest: File, cand: Entry, type: ResType, state: ResolveState) {
        if (type == ResType.SOUND_DEF) return // manejado por resolveSoundDef
        try {
            val destJson = JSONObject(dest.readText(StandardCharsets.UTF_8))
            val srcJson = JSONObject(cand.payload?.toString() ?: cand.sourceFile.readText(StandardCharsets.UTF_8))
            when (type) {
                ResType.GEOMETRY -> {
                    val dstArr = destJson.optJSONArray("minecraft:geometry") ?: return
                    val srcArr = srcJson.optJSONArray("minecraft:geometry") ?: return
                    val known = HashSet<String>()
                    for (i in 0 until dstArr.length()) {
                        dstArr.optJSONObject(i)?.optJSONObject("description")?.optString("identifier")?.let { known.add(it) }
                    }
                    for (i in 0 until srcArr.length()) {
                        val g = srcArr.optJSONObject(i) ?: continue
                        val gid = g.optJSONObject("description")?.optString("identifier") ?: continue
                        if (!known.contains(gid)) dstArr.put(g)
                    }
                }
                else -> {
                    val key = if (type == ResType.ANIMATION) "animations"
                    else if (type == ResType.ANIM_CONTROLLER) "animation_controllers" else "render_controllers"
                    val dstObj = destJson.optJSONObject(key) ?: return
                    val srcObj = srcJson.optJSONObject(key) ?: return
                    srcObj.keys().forEachRemaining { k -> if (!dstObj.has(k)) dstObj.put(k, srcObj.get(k)) }
                }
            }
            OutputStreamWriter(FileOutputStream(dest), StandardCharsets.UTF_8).use { it.write(destJson.toString()) }
            state.copiedCount[type] = (state.copiedCount[type] ?: 0) + 1
        } catch (_: Exception) {}
    }

    private fun learnIdsFromPayload(cand: Entry, state: ResolveState) {
        try {
            val j = JSONObject(cand.payload?.toString() ?: readFileQuiet(cand.sourceFile))
            j.optJSONObject("animations")?.keys()?.forEachRemaining { state.learn(ResType.ANIMATION, it.trim()) }
            j.optJSONObject("animation_controllers")?.keys()?.forEachRemaining { state.learn(ResType.ANIM_CONTROLLER, it.trim()) }
            j.optJSONObject("render_controllers")?.keys()?.forEachRemaining { state.learn(ResType.RENDER_CONTROLLER, it.trim()) }
            j.optJSONArray("minecraft:geometry")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.optJSONObject("description")?.optString("identifier")
                        ?.trim()?.takeIf { it.isNotBlank() }?.let { state.learn(ResType.GEOMETRY, it) }
                }
            }
            j.optJSONObject("particle_effect")?.optJSONObject("description")?.optString("identifier")
                ?.trim()?.takeIf { it.isNotBlank() }?.let { state.learn(ResType.PARTICLE, it) }
        } catch (_: Exception) {}
    }

    private fun outputRootFor(type: ResType, state: ResolveState): File = when (type) {
        ResType.GEOMETRY -> File(state.outputRp, "models")
        ResType.ANIMATION -> File(state.outputRp, "animations")
        ResType.ANIM_CONTROLLER -> File(state.outputRp, "animation_controllers")
        ResType.RENDER_CONTROLLER -> File(state.outputRp, "render_controllers")
        ResType.PARTICLE -> File(state.outputRp, "particles")
        ResType.SPAWN_RULES -> state.outputBp
        ResType.BIOME -> state.outputBp
        ResType.FEATURE_RULES -> state.outputBp
        ResType.MATERIAL -> state.outputRp
        ResType.MUSIC_DEFINITION -> state.outputRp
        else -> state.outputRp
    }

    private fun guessRoot(normalized: String, state: ResolveState): File =
        if (normalized.startsWith("textures/") || normalized.startsWith("models/") ||
            normalized.startsWith("animations/") || normalized.startsWith("particles/")
        ) state.outputRp else state.outputBp

    private fun sourceRootOf(cand: Entry): File = cand.sourceRoot

    private fun readFileQuiet(f: File): String = runCatching { f.readText(StandardCharsets.UTF_8) }.getOrDefault("{}")

    // ── RESULTADO SEGURO DE RUTA ────────────────────────────────────────────

    sealed class SafeResolveResult {
        data class Success(val file: File) : SafeResolveResult()
        data class Failure(val reason: String) : SafeResolveResult()
    }

    private fun safeResolve(base: File, rel: String): SafeResolveResult {
        val clean = rel.replace("\\", "/").trimStart('/')
        if (clean.isBlank()) return SafeResolveResult.Failure("ruta vacía")
        if (clean.split('/').any { it == ".." }) return SafeResolveResult.Failure("traversia de ruta (..)")
        if (clean.contains(':')) return SafeResolveResult.Failure("carácter dos puntos no permitido")
        val f = File(base, clean)
        return try {
            if (f.canonicalFile.path.startsWith(base.canonicalFile.path + File.separator)) {
                SafeResolveResult.Success(f)
            } else {
                SafeResolveResult.Failure("fuera del sandbox")
            }
        } catch (e: Exception) {
            SafeResolveResult.Failure("error resolviendo: ${e.message}")
        }
    }

    // ── ORQUESTACIÓN PÚBLICA ───────────────────────────────────────────────

    fun run(
        rpDirs: List<File>,
        bpDirs: List<File>,
        outputRp: File,
        outputBp: File
    ): GraphResult {
        // FASE 1: índice global de todos los inputs.
        val index = buildIndex(rpDirs, bpDirs)
        PackForgeLog.d(
            TAG,
            "Índice construido: ${index.byType.entries.sumOf { (_, m) -> m.size }} ids tipados, ${index.rawPaths.size} rutas físicas"
        )

        val state = ResolveState(
            index = index, outputRp = outputRp, outputBp = outputBp,
            resolvedIds = HashMap(), copiedFiles = HashSet(),
            copiedCount = HashMap(), critical = ArrayList()
        )
        // Tipos sin fase de siembra explícita deben existir como conjuntos vacíos.
        ResType.entries.forEach { state.resolvedIds.putIfAbsent(it, HashSet()) }

        // FASE 2: sembrar ids/rutas YA presentes en el destino (no recopiar lo propio).
        seedOutputs(outputRp, state, isBp = false)
        seedOutputs(outputBp, state, isBp = true)

        // FASE 3: resolver cada archivo crítico del destino.
        val targets = mutableListOf<Pair<File, File>>() // file ↔ root
        appendTargets(File(outputRp, "entity"), outputRp, targets)
        appendTargets(File(outputRp, "attachables"), outputRp, targets)
        appendTargets(File(outputRp, "particles"), outputRp, targets)
        appendTargets(File(outputBp, "entities"), outputBp, targets)
        appendTargets(File(outputBp, "items"), outputBp, targets)
        appendTargets(File(outputBp, "blocks"), outputBp, targets)
        appendTargets(File(outputBp, "spawn_rules"), outputBp, targets)
        appendTargets(File(outputBp, "biomes"), outputBp, targets)
        appendTargets(File(outputBp, "feature_rules"), outputBp, targets)

        targets.forEach { (file, root) ->
            try {
                val json = JSONObject(file.readText(StandardCharsets.UTF_8))
                resolveAndCopyDependencies(json, file.relativeTo(root).path.replace("\\", "/"), state)
            } catch (e: Exception) {
                PackForgeLog.e(TAG, "No se pudo resolver ${file.name}: ${e.message}")
            }
        }

        // FASE 4: veredicto.
        val notes = buildList {
            val total = state.copiedCount.values.sum()
            add("Grafo de dependencias: $total archivos resueltos por referencia (" +
                state.copiedCount.entries.filter { it.value > 0 }
                    .joinToString { "${it.key.label}: ${it.value}" } + ")")
        }
        state.critical.forEach { ce ->
            PackForgeLog.e(TAG, "❌ CRÍTICO: falta ${ce.type.label} '${ce.id}' requerido por ${ce.requester}")
            ConflictRegistry.logConflict(
                severity = ConflictSeverity.HIGH,
                type = "MISSING_DEPENDENCY_GRAPH",
                file = ce.requester,
                addon1 = "pack fusionado",
                addon2 = "-",
                description = "Falta ${ce.type.label} '${ce.id}' requerido por ${ce.requester}"
            )
        }
        return GraphResult(state.critical.toList(), state.copiedCount.toMap(), notes)
    }

    private fun appendTargets(dir: File, root: File, out: MutableList<Pair<File, File>>) {
        if (!dir.isDirectory) return
        dir.walkTopDown().filter { it.isFile && it.extension.equals("json", true) }
            .forEach { out.add(it to root) }
    }

    /** Siembra ids y rutas que YA viven en el pack fusionado. */
    private fun seedOutputs(root: File, state: ResolveState, isBp: Boolean) {
        if (!root.isDirectory) return
        root.walkTopDown().filter { it.isFile }.forEach { file ->
            val rel = file.relativeTo(root).path.replace("\\", "/")
            state.learn(ResType.FILE_PATH, rel)
            if (!file.extension.equals("json", true)) return@forEach
            try {
                val j = JSONObject(file.readText(StandardCharsets.UTF_8))
                if (rel.endsWith(".geo.json", true)) {
                    j.optJSONArray("minecraft:geometry")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            arr.optJSONObject(i)?.optJSONObject("description")?.optString("identifier")
                                ?.trim()?.takeIf { it.isNotBlank() }?.let { state.learn(ResType.GEOMETRY, it) }
                        }
                    }
                }
                j.optJSONObject("animations")?.keys()?.forEachRemaining { state.learn(ResType.ANIMATION, it.trim()) }
                j.optJSONObject("animation_controllers")?.keys()?.forEachRemaining { state.learn(ResType.ANIM_CONTROLLER, it.trim()) }
                j.optJSONObject("render_controllers")?.keys()?.forEachRemaining { state.learn(ResType.RENDER_CONTROLLER, it.trim()) }
                j.optJSONObject("particle_effect")?.optJSONObject("description")?.optString("identifier")
                    ?.trim()?.takeIf { it.isNotBlank() }?.let { state.learn(ResType.PARTICLE, it) }
                if (rel == "sound_definitions.json") {
                    j.keys().forEachRemaining { k -> if (k != "format_version") state.learn(ResType.SOUND_DEF, k.trim()) }
                }
            } catch (_: Exception) {}
        }
    }
}
