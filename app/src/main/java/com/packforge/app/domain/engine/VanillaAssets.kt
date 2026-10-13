package com.packforge.app.domain.engine

/**
 * ═══════════════════════════════════════════════════════════════════════
 * TICKET-CORE-003 — LÍNEA BASE VANILLA (assets resueltos por el juego)
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Los Resource Packs de Bedrock son OVERLAYS sobre los assets vanilla que
 * Mojang ya incluye en el juego. Un addon NUNCA empaqueta esos assets, así
 * que referenciarlos NO es un error: el juego los resuelve en runtime.
 *
 * Antes de este catálogo, `validateMerge` reportaba cada referencia vanilla
 * como "no empaquetada": 64 falsos positivos en un modpack de 2 addons.
 *
 * REGLA DE COINCIDENCIA: por PREFIJO EXACTO DE SEGMENTO, nunca por substring.
 *   OK  "textures/entity/zombie/heal"  -> vanilla (prefijo "textures/entity/zombie/")
 *   NO  "textures/entity/zombieboss"  -> custom (no es segmento completo)
 *   NO  "textures/miscthing"          -> custom (falta "/" en el prefijo)
 */
object VanillaAssets {

    // ── GEOMETRÍAS ──────────────────────────────────────────────────────

    /** Geometrías vanilla por identificador EXACTO. */
    private val VANILLA_GEOMETRIES = setOf(
        "geometry.default", "geometry.humanoid", "geometry.humanoid.custom",
        "geometry.humanoid.eyes", "geometry.humanoid.leggings",
        "geometry.humanoid.arms", "geometry.humanoid.hat",
        "geometry.cape", "geometry.player", "geometry.player.cape",
        "geometry.player.head", "geometry.pig", "geometry.pig.sad",
        "geometry.cow", "geometry.cow.warm", "geometry.sheep", "geometry.sheep.fur",
        "geometry.chicken", "geometry.fox", "geometry.wolf", "geometry.villager",
        "geometry.villager.v2", "geometry.zombie", "geometry.zombie.v2",
        "geometry.zombie.king", "geometry.skeleton", "geometry.creeper",
        "geometry.spider", "geometry.enderman", "geometry.silverfish",
        "geometry.slime", "geometry.magma_cube", "geometry.blaze",
        "geometry.wither", "geometry.dragon", "geometry.guardian",
        "geometry.guardian.elder", "geometry.squid", "geometry.bat",
        "geometry.wither_skeleton", "geometry.piglin", "geometry.piglin_brute",
        "geometry.hoglin", "geometry.zoglin", "geometry.strider",
        "geometry.axolotl", "geometry.ender_dragon", "geometry.shulker",
        "geometry.vex", "geometry.llama", "geometry.trader", "geometry.vindicator",
        "geometry.evoker", "geometry.pillager", "geometry.ravager",
        "geometry.frog", "geometry.tadpole", "geometry.warden",
        "geometry.allay", "geometry.camel", "geometry.breeze", "geometry.sniffer",
        "geometry.armadillo", "geometry.bogged", "geometry.boat",
        "geometry.boat.pirate", "geometry.boat.wooden", "geometry.minecart",
        "geometry.horse", "geometry.horse.wooden", "geometry.donkey",
        "geometry.mule", "geometry.skeleton_horse", "geometry.zombie_horse",
        "geometry.pig.head", "geometry.item", "geometry.item_frame",
        "geometry.leash_knot", "geometry.firework_rocket",
        "geometry.fishing_bobber", "geometry.arrow", "geometry.trident",
        "geometry.eye_of_ender", "geometry.fireball", "geometry.small_fireball",
        "geometry.egg", "geometry.end_crystal", "geometry.ender_chest",
        "geometry.potion", "geometry.thrown_trident"
    )

    /** Geometrías vanilla por FAMILIA (prefijo de segmento). */
    private val VANILLA_GEOMETRY_PREFIXES = listOf(
        "geometry.humanoid.", "geometry.player.", "geometry.pig.",
        "geometry.cow.", "geometry.sheep.", "geometry.chicken.",
        "geometry.fox.", "geometry.wolf.", "geometry.villager.",
        "geometry.zombie.", "geometry.skeleton.", "geometry.creeper.",
        "geometry.spider.", "geometry.enderman.", "geometry.silverfish.",
        "geometry.slime.", "geometry.magma_cube.", "geometry.blaze.",
        "geometry.wither.", "geometry.dragon.", "geometry.guardian.",
        "geometry.boat.", "geometry.horse.", "geometry.item."
    )

    fun isVanillaGeometry(id: String): Boolean {
        val clean = id.trim()
        if (clean.isEmpty()) return false
        if (clean in VANILLA_GEOMETRIES) return true
        return VANILLA_GEOMETRY_PREFIXES.any { clean.startsWith(it) }
    }

    // ── RENDER CONTROLLERS ─────────────────────────────────────────────

    private val VANILLA_RENDER_CONTROLLERS = setOf(
        "controller.render.default", "controller.render.player",
        "controller.render.horse", "controller.render.pig",
        "controller.render.cow", "controller.render.sheep",
        "controller.render.chicken", "controller.render.fox",
        "controller.render.wolf", "controller.render.villager",
        "controller.render.villager_v2", "controller.render.zombie",
        "controller.render.zombie_v2", "controller.render.skeleton",
        "controller.render.creeper", "controller.render.spider",
        "controller.render.enderman", "controller.render.silverfish",
        "controller.render.slime", "controller.render.magma_cube",
        "controller.render.blaze", "controller.render.wither",
        "controller.render.dragon", "controller.render.guardian",
        "controller.render.guardian_elder", "controller.render.squid",
        "controller.render.bat", "controller.render.wither_skeleton",
        "controller.render.piglin", "controller.render.piglin_brute",
        "controller.render.hoglin", "controller.render.zoglin",
        "controller.render.strider", "controller.render.axolotl",
        "controller.render.shulker", "controller.render.vex",
        "controller.render.llama", "controller.render.trader",
        "controller.render.vindicator", "controller.render.evoker",
        "controller.render.pillager", "controller.render.ravager",
        "controller.render.frog", "controller.render.tadpole",
        "controller.render.warden", "controller.render.allay",
        "controller.render.camel", "controller.render.breeze",
        "controller.render.sniffer", "controller.render.armadillo",
        "controller.render.bogged", "controller.render.boat",
        "controller.render.minecart", "controller.render.horse",
        "controller.render.item", "controller.render.item_frame",
        "controller.render.leash_knot", "controller.render.firework_rocket",
        "controller.render.fishing_bobber", "controller.render.arrow",
        "controller.render.trident", "controller.render.eye_of_ender",
        "controller.render.fireball", "controller.render.egg",
        "controller.render.end_crystal", "controller.render.potion",
        "controller.render.thrown_trident", "controller.render.ender_chest",
        "controller.render.npc", "controller.render.seagull",
        "controller.render.tropicalfish", "controller.render.cat"
    )

    private val VANILLA_RENDER_CONTROLLER_PREFIXES = listOf(
        "controller.render.player.", "controller.render.humanoid.",
        "controller.render.item.", "controller.render.horse.",
        "controller.render.boat.", "controller.render.pig.",
        "controller.render.cow.", "controller.render.sheep.",
        "controller.render.zombie.", "controller.render.villager."
    )

    fun isVanillaRenderController(id: String): Boolean {
        val clean = id.trim()
        if (clean.isEmpty()) return false
        if (clean in VANILLA_RENDER_CONTROLLERS) return true
        return VANILLA_RENDER_CONTROLLER_PREFIXES.any { clean.startsWith(it) }
    }

    // ── ANIMACIONES ────────────────────────────────────────────────────

    /**
     * Las animaciones vanilla son familias por prefijo, no ids cerrados:
     * "animation.player.*" cubre cientos de animaciones que Mojang provee.
     */
    private val VANILLA_ANIMATION_PREFIXES = listOf(
        // Anims vanilla de entidades
        "animation.player.", "animation.humanoid.", "animation.skeleton.",
        "animation.item.", "animation.pig.", "animation.cow.",
        "animation.sheep.", "animation.chicken.", "animation.fox.",
        "animation.wolf.", "animation.villager.", "animation.zombie.",
        "animation.creeper.", "animation.spider.", "animation.enderman.",
        "animation.silverfish.", "animation.slime.", "animation.magma_cube.",
        "animation.blaze.", "animation.wither.", "animation.dragon.",
        "animation.guardian.", "animation.squid.", "animation.bat.",
        "animation.wither_skeleton.", "animation.piglin.", "animation.hoglin.",
        "animation.strider.", "animation.axolotl.", "animation.shulker.",
        "animation.vex.", "animation.llama.", "animation.trader.",
        "animation.vindicator.", "animation.evoker.", "animation.pillager.",
        "animation.ravager.", "animation.frog.", "animation.tadpole.",
        "animation.warden.", "animation.allay.", "animation.camel.",
        "animation.breeze.", "animation.sniffer.", "animation.armadillo.",
        "animation.bogged.", "animation.boat.", "animation.horse.",
        "animation.minecart.", "animation.dolphin.", "animation.panda.",
        "animation.seagull.", "animation.tropicalfish.", "animation.cat.",
        "animation.ghast.", "animation.polar_bear.", "animation.snow_goal",
        "animation.skeleton_horse.", "animation.zombie_horse.",
        // Controllers de animación vanilla
        "controller.animation.player.", "controller.animation.humanoid.",
        "controller.animation.persona.", "controller.animation.item.",
        "controller.animation.fox.", "controller.animation.wolf.",
        "controller.animation.sheep.", "controller.animation.cat.",
        "controller.animation.villager.", "controller.animation.pig.",
        "controller.animation.cow.", "controller.animation.piglin.",
        "controller.animation.hoglin.", "controller.animation.ravager.",
        "controller.animation.spider.", "controller.animation.squid.",
        "controller.animation.boat.", "controller.animation.horse.",
        "controller.animation.seagull.", "controller.animation.tropicalfish.",
        "controller.animation.dolphin.", "controller.animation.panda.",
        "controller.animation.polar_bear.", "controller.animation.strider.",
        "controller.animation.axolotl.", "controller.animation.camel.",
        "controller.animation.frog.", "controller.animation.warden.",
        "controller.animation.allay.", "controller.animation.sniffer.",
        "controller.animation.armadillo.", "controller.animation.wither.",
        "controller.animation.blaze.", "controller.animation.ghast.",
        "controller.animation.dragon.", "controller.animation.enderman.",
        "controller.animation.guardian.", "controller.animation.phantom.",
        "controller.animation.shulker.", "controller.animation.evoker.",
        "controller.animation.pillager.", "controller.animation.vex.",
        "controller.animation.llama.", "controller.animation.trader.",
        "controller.animation.bogged.", "controller.animation.zombie."
    )

    fun isVanillaAnimation(id: String): Boolean {
        val clean = id.trim()
        if (clean.isEmpty()) return false
        return VANILLA_ANIMATION_PREFIXES.any { clean.startsWith(it) }
    }

    // ── TEXTURAS ───────────────────────────────────────────────────────

    /**
     * Prefijos de textura vanilla por SEGMENTO completo.
     * El trailing "/" impide que "textures/entity/zombieboss" matchee con
     * "textures/entity/zombie/".
     */
    private val VANILLA_TEXTURE_PREFIXES = listOf(
        // Ids vanilla cerrados (no llevan "/" final): requieren corte exacto.
        "textures/entity/steve", "textures/entity/alex",
        "textures/entity/cape_invisible", "textures/entity/empty",
        // Familias de mobs (con "/" final: cualquier subruta es vanilla).
        "textures/entity/zombie/", "textures/entity/skeleton/",
        "textures/entity/creeper/", "textures/entity/spider/",
        "textures/entity/slime/", "textures/entity/enderman/",
        "textures/entity/silverfish/", "textures/entity/blaze/",
        "textures/entity/ghast/", "textures/entity/guardian/",
        "textures/entity/warden/", "textures/entity/piglin/",
        "textures/entity/wither/", "textures/entity/dragon/",
        "textures/entity/drowned/", "textures/entity/husk/",
        "textures/entity/stray/", "textures/entity/witch/",
        "textures/entity/villager/", "textures/entity/pillager/",
        "textures/entity/vindicator/", "textures/entity/evoker/",
        "textures/entity/ravager/", "textures/entity/shulker/",
        "textures/entity/phantom/", "textures/entity/vex/",
        "textures/entity/fox/", "textures/entity/wolf/",
        "textures/entity/cat/", "textures/entity/panda/",
        "textures/entity/bat/", "textures/entity/squid/",
        "textures/entity/llama/", "textures/entity/horse/",
        "textures/entity/donkey/", "textures/entity/mule/",
        "textures/entity/skeleton_horse/", "textures/entity/zombie_horse/",
        "textures/entity/pig/", "textures/entity/cow/",
        "textures/entity/sheep/", "textures/entity/chicken/",
        "textures/entity/magma_cube/", "textures/entity/ender_dragon/",
        "textures/entity/axolotl/", "textures/entity/frog/",
        "textures/entity/camel/", "textures/entity/sniffer/",
        "textures/entity/armadillo/", "textures/entity/breeze/",
        "textures/entity/bogged/", "textures/entity/tadpole/",
        "textures/entity/allay/", "textures/entity/goat/",
        // C4 (compatibilidad absoluta): las entidades AGENTE (education) y
        // MÁSCARA (persona) son vanilla pero faltaban en el catálogo → cada
        // referencia a ellas aparecía como "textura faltante" falsa.
        "textures/entity/agent/", "textures/entity/mask/",
        // Directorios vanilla completos
        "textures/items/", "textures/blocks/", "textures/misc/",
        "textures/gui/", "textures/ui/", "textures/hud/",
        "textures/mob_effect/", "textures/environment/",
        "textures/colormap/", "textures/flame/",
        "textures/beacon/", "textures/rail/", "textures/paintings/"
    )

    fun isVanillaTexture(path: String): Boolean {
        val clean = path.trim().replace('\\', '/').removePrefix("./")
        if (clean.isEmpty()) return false

        return VANILLA_TEXTURE_PREFIXES.any { prefix ->
            if (!clean.startsWith(prefix)) return@any false
            // Prefijo con "/" final: startsWith ya garantiza el corte de segmento.
            if (prefix.endsWith("/")) return@any true
            // Prefijo cerrado: debe terminar ahí o seguir con "." (extensión).
            // Así "textures/entity/steve2" NO matchea con "textures/entity/steve".
            val rest = clean.substring(prefix.length)
            rest.isEmpty() || rest.startsWith(".")
        }
    }

    // ── SONIDOS ────────────────────────────────────────────────────────

    private val VANILLA_SOUND_PREFIXES = listOf(
        "sounds/mob/", "sounds/block/", "sounds/item/", "sounds/note/",
        "sounds/random/", "sounds/game/", "sounds/ambient/",
        "sounds/music/", "sounds/ui/", "sounds/records/",
        "sounds/subtitles/"
    )

    fun isVanillaSound(path: String): Boolean {
        val clean = path.trim().replace('\\', '/').removePrefix("./")
        if (clean.isEmpty()) return false
        return VANILLA_SOUND_PREFIXES.any { clean.startsWith(it) }
    }

    // ── DISPATCHER GLOBAL ──────────────────────────────────────────────

    /**
     * Determina si una referencia arbitraria apunta a un asset vanilla.
     * Cubre geometry/animation/render_controller (ids con punto) y
     * textures/sounds (rutas con barra), decidiendo por la FORMA del valor.
     */
    fun isVanillaReference(ref: String): Boolean {
        val clean = ref.trim()
        if (clean.isEmpty()) return false
        return when {
            clean.startsWith("geometry.") -> isVanillaGeometry(clean)
            clean.startsWith("animation.") -> isVanillaAnimation(clean)
            clean.startsWith("controller.animation.") -> isVanillaAnimation(clean)
            clean.startsWith("controller.render.") -> isVanillaRenderController(clean)
            clean.contains('/') -> isVanillaTexture(clean) || isVanillaSound(clean)
            else -> false
        }
    }

    /** Texto para los reportes: distingue "vanilla" de "referencia rota real". */
    const val VANILLA_NOTE: String = "asset VANILLA (resuelto por el juego)"
}