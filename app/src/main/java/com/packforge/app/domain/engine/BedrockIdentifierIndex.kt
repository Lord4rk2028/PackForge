package com.packforge.app.domain.engine

import com.packforge.app.util.PackForgeLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Índice de identificadores Bedrock para resolución de dependencias entre archivos.
 * Escanea geometrías, animaciones, controladores de render y definiciones de entidad.
 */
class BedrockIdentifierIndex(private val identifierMap: Map<String, List<File>>) {

    val totalIdentifiers: Int = identifierMap.size

    companion object {
        private const val TAG = "BedrockIdentifierIndex"

        // Lista blanca de geometrías vanilla de Minecraft
        private val VANILLA_GEOMETRIES = setOf(
            "geometry.chicken", "geometry.pig", "geometry.zombie", "geometry.creeper", "geometry.skeleton",
            "geometry.spider", "geometry.cow", "geometry.sheep", "geometry.villager", "geometry.iron_golem",
            "geometry.wolf", "geometry.boat", "geometry.minecart", "geometry.arrow", "geometry.item",
            "geometry.xp_orb", "geometry.phantom", "geometry.drowned", "geometry.fish", "geometry.turtle",
            "geometry.panda", "geometry.ravager", "geometry.wandering_trader", "geometry.llama",
            "geometry.polarbear", "geometry.stray", "geometry.husk", "geometry.evocation_fang", "geometry.vex",
            "geometry.shulker", "geometry.enderman", "geometry.endermite", "geometry.silverfish",
            "geometry.blaze", "geometry.magma_cube", "geometry.guardian", "geometry.elder_guardian",
            "geometry.dragon", "geometry.wither", "geometry.ghast", "geometry.pig_zombie", "geometry.snow_golem",
            "geometry.ocean", "geometry.rabbit", "geometry.bat", "geometry.witch", "geometry.slime",
            "geometry.horse", "geometry.donkey", "geometry.mule", "geometry.skeleton_horse", "geometry.zombie_horse",
            "geometry.parrot", "geometry.dolphin", "geometry.cat", "geometry.fox", "geometry.bee",
            "geometry.piglin", "geometry.piglin_brute", "geometry.hoglin", "geometry.strider", "geometry.axolotl",
            "geometry.glow_squid", "geometry.goat", "geometry.allay", "geometry.frog", "geometry.tadpole",
            "geometry.warden", "geometry.sniffer", "geometry.armadillo", "geometry.breeze", "geometry.bogged"
        )

        private val VANILLA_MOB_NAMES = setOf(
            "chicken", "pig", "zombie", "creeper", "skeleton", "spider", "cow", "sheep",
            "villager", "iron_golem", "wolf", "boat", "minecart", "arrow", "item",
            "xp_orb", "phantom", "drowned", "fish", "turtle", "panda", "ravager",
            "wandering_trader", "llama", "polarbear", "stray", "husk", "evocation_fang",
            "vex", "shulker", "enderman", "endermite", "silverfish", "blaze", "magma_cube",
            "guardian", "elder_guardian", "dragon", "wither", "ghast", "pig_zombie",
            "snow_golem", "ocean", "rabbit", "bat", "witch", "slime", "horse", "donkey",
            "mule", "skeleton_horse", "zombie_horse", "parrot", "dolphin", "cat", "fox",
            "bee", "piglin", "piglin_brute", "hoglin", "strider", "axolotl", "glow_squid",
            "goat", "allay", "frog", "tadpole", "warden", "sniffer", "armadillo", "breeze",
            "bogged", "humanoid", "player", "armor", "default"
        )

        fun isVanilla(identifier: String): Boolean {
            val clean = identifier.trim()
            if (VANILLA_GEOMETRIES.contains(clean)) return true
            if (clean.startsWith("geometry.humanoid")) return true

            if (clean.startsWith("animation.")) {
                val parts = clean.split(".")
                if (parts.size >= 2 && VANILLA_MOB_NAMES.contains(parts[1])) {
                    return true
                }
            }

            if (clean.startsWith("controller.render.")) {
                val suffix = clean.removePrefix("controller.render.")
                val mainPart = suffix.split(".").firstOrNull() ?: ""
                if (VANILLA_MOB_NAMES.contains(mainPart) || suffix == "default" || suffix == "armor") {
                    return true
                }
            }

            return false
        }

        fun build(bpFiles: List<File>, rpFiles: List<File>): BedrockIdentifierIndex {
            val map = mutableMapOf<String, MutableList<File>>()

            val allDirs = (bpFiles + rpFiles).distinct()
            allDirs.forEach { dir ->
                if (!dir.exists()) return@forEach
                // ⭐ OPTIMIZACIÓN: Usar DirIndexCache en lugar de walkTopDown()
                val cachedFiles = DirIndexCache.index(dir).jsonFiles
                for (file in cachedFiles) {
                    try {
                        val normalizedPath = file.invariantSeparatorsPath.lowercase()
                        val content = file.readText(Charsets.UTF_8)
                        if (content.isBlank()) continue
                        val json = JSONObject(content)

                        // 1. Geometrías: models/entity/*.geo.json y models/blocks/*.geo.json
                        if (file.name.endsWith(".geo.json", ignoreCase = true) ||
                            normalizedPath.contains("models/entity/") ||
                            normalizedPath.contains("models/blocks/")
                        ) {
                            // Formato moderno (1.12+): "minecraft:geometry"
                            val modernGeo = json.opt("minecraft:geometry")
                            when (modernGeo) {
                                is JSONArray -> {
                                    for (i in 0 until modernGeo.length()) {
                                        val geoObj = modernGeo.optJSONObject(i)
                                        val id = geoObj?.optJSONObject("description")?.optString("identifier")
                                            ?: geoObj?.optString("identifier")
                                        if (!id.isNullOrBlank()) {
                                            map.getOrPut(id.trim()) { mutableListOf() }.add(file)
                                        }
                                    }
                                }
                                is JSONObject -> {
                                    val id = modernGeo.optJSONObject("description")?.optString("identifier")
                                        ?: modernGeo.optString("identifier")
                                    if (!id.isNullOrBlank()) {
                                        map.getOrPut(id.trim()) { mutableListOf() }.add(file)
                                    }
                                }
                            }
                            // Formato clásico (1.8): claves raíz como "geometry.xxx"
                            json.keys().forEach { key ->
                                if (key.startsWith("geometry.") && !key.equals("format_version", ignoreCase = true)) {
                                    map.getOrPut(key.trim()) { mutableListOf() }.add(file)
                                }
                            }
                        }

                        // 2. Definiciones de Entidad: entity/*.entity.json
                        if (file.name.endsWith(".entity.json", ignoreCase = true) ||
                            normalizedPath.contains("entity/")
                        ) {
                            val clientEntityDesc = json.optJSONObject("minecraft:client_entity")?.optJSONObject("description")
                            val desc = clientEntityDesc ?: json.optJSONObject("description")
                            val id = desc?.optString("identifier")
                            if (!id.isNullOrBlank()) {
                                map.getOrPut(id.trim()) { mutableListOf() }.add(file)
                            }
                        }

                        // 3. Animaciones: animations/*.json
                        if (normalizedPath.contains("animations/")) {
                            json.optJSONObject("animations")?.keys()?.forEach { animId ->
                                if (animId.isNotBlank()) {
                                    map.getOrPut(animId.trim()) { mutableListOf() }.add(file)
                                }
                            }
                        }

                        // 4. Animation Controllers: animation_controllers/*.json
                        if (normalizedPath.contains("animation_controllers/")) {
                            json.optJSONObject("animation_controllers")?.keys()?.forEach { ctrlId ->
                                if (ctrlId.isNotBlank()) {
                                    map.getOrPut(ctrlId.trim()) { mutableListOf() }.add(file)
                                }
                            }
                        }

                        // 5. Render Controllers: render_controllers/*.json
                        if (normalizedPath.contains("render_controllers/")) {
                            json.optJSONObject("render_controllers")?.keys()?.forEach { rcId ->
                                if (rcId.isNotBlank()) {
                                    map.getOrPut(rcId.trim()) { mutableListOf() }.add(file)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        PackForgeLog.w(TAG, "JSON corrupto ignorado: ${file.path} (${e.message})")
                    }
                }
            }
            return BedrockIdentifierIndex(map)
        }
    }

    /**
     * Resuelve un identificador al archivo físico original.
     * Si es vanilla, devuelve null.
     * Si no existe y no es vanilla, registra warning y devuelve null.
     */
    fun resolve(identifier: String): File? {
        val clean = identifier.trim()
        val found = identifierMap[clean]?.firstOrNull()
        if (found != null) {
            PackForgeLog.d(TAG, "🔧 Resolviendo geometría: $clean → encontrado en ${found.name}")
            return found
        }
        if (isVanilla(clean)) {
            return null
        }
        PackForgeLog.w(TAG, "❌ Identificador no encontrado: $clean (no es vanilla ni está en addons)")
        return null
    }

    fun contains(identifier: String): Boolean = identifierMap.containsKey(identifier.trim())

    fun getFiles(identifier: String): List<File> = identifierMap[identifier.trim()] ?: emptyList()

    fun getAllIdentifiers(): Set<String> = identifierMap.keys
}
