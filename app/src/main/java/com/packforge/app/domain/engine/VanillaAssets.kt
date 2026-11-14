package com.packforge.app.domain.engine

import com.packforge.app.util.PackForgeLog
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.io.InputStreamReader

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

    /** Geometrías vanilla por identificador EXACTO. (mutable para actualización remota) */
    private var VANILLA_GEOMETRIES = mutableSetOf(
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
        "geometry.potion", "geometry.thrown_trident",
        // ⭐ FASE 5: geometría del MOTOR para proyectiles/items en vuelo. Ningún
        // addon de Better MC la define y 5 entidades de CoreCraft la referencian
        // (`cc:slingshot_projectile`, `cc:thrown_banana`, `cc:coconut_projectile`,
        // `cc:peanut_projectile`, `cc:end_stone_slingshot_projectile`). Al no
        // estar en la lista se reportaba como "modelo faltante" y el healer
        // llegaba a generar un placeholder encima de una geometría vanilla.
        "geometry.item_sprite", "geometry.item_sprite_glint",
        "geometry.shield", "geometry.elytra", "geometry.chest",
        "geometry.chest_double", "geometry.chest_left", "geometry.chest_right",
        "geometry.banner", "geometry.bed", "geometry.conduit",
        "geometry.flower_pot", "geometry.lectern", "geometry.shulker_box",
        "geometry.sign", "geometry.hanging_sign", "geometry.bell",
        "geometry.armor_stand", "geometry.armor_stand.armor",
        "geometry.experience_orb", "geometry.snowball", "geometry.llama_spit",
        // ══ Verificados contra samples oficiales v1.26.70 ══
        // El addon Origins usa los identificadores REALES del juego, que no
        // llevan guion bajo: `geometry.llamaspit` (2 modelos vanilla) y
        // `geometry.wither_skull` (1 modelo). Faltaban en el catálogo, así que
        // `custom_llama_spit` / `custom_wither_skull_*` generaban geometrías
        // placeholder y "modelos faltantes".
        "geometry.llamaspit", "geometry.wither_skull",
        "geometry.arrow.skeleton", "geometry.tnt", "geometry.balloon",
        "geometry.heart", "geometry.dragon_fireball", "geometry.wind_charge",
        "geometry.ominous_banner", "geometry.trial_spawner", "geometry.vault"
    )

    /** Geometrías vanilla por FAMILIA (prefijo de segmento). (mutable para actualización remota) */
    private var VANILLA_GEOMETRY_PREFIXES = mutableListOf(
        "geometry.humanoid.", "geometry.player.", "geometry.pig.",
        "geometry.cow.", "geometry.sheep.", "geometry.chicken.",
        "geometry.fox.", "geometry.wolf.", "geometry.villager.",
        "geometry.zombie.", "geometry.skeleton.", "geometry.creeper.",
        "geometry.spider.", "geometry.enderman.", "geometry.silverfish.",
        "geometry.slime.", "geometry.magma_cube.", "geometry.blaze.",
        "geometry.wither.", "geometry.dragon.", "geometry.guardian.",
        "geometry.boat.", "geometry.horse.", "geometry.item.",
        // ⭐ FASE 5: familias del motor con sufijo libre (la lista de ids exactos
        // nunca las cubre todas: `geometry.item_sprite` faltaba y se reportaba
        // como modelo faltante).
        "geometry.item_sprite.", "geometry.armor_stand.", "geometry.chest.",
        "geometry.shulker_box.", "geometry.banner.", "geometry.bed."
    )

    fun isVanillaGeometry(id: String): Boolean {
        val clean = id.trim()
        if (clean.isEmpty()) return false
        if (clean in VANILLA_GEOMETRIES) return true
        return VANILLA_GEOMETRY_PREFIXES.any { clean.startsWith(it) }
    }

    // ── RENDER CONTROLLERS ─────────────────────────────────────────────

    private var VANILLA_RENDER_CONTROLLERS = mutableSetOf(
        // ══ FALTABAN: el reporte los daba por ausentes ══
        // `controller.render.item_sprite` es el render controller vanilla de los
        // proyectiles/ítems lanzados (flechas, huevos, bolas de nieve). Sin esta
        // entrada, cada entidad de proyectil generaba 2 errores falsos.
        "controller.render.item_sprite", "controller.render.block",
        "controller.render.armor_stand", "controller.render.locator",
        "controller.render.ui_hurt", "controller.render.flat_item",
        "controller.render.shadow_only", "controller.render.default",
        "controller.render.player",
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
        "controller.render.tropicalfish", "controller.render.cat",
        // ══ Verificados contra los samples oficiales v1.26.70 ══
        // `custom_llama_spit` y `custom_wither_skull_*` (addon Origins) los
        // referencian y son del juego: `render_controllers/llama_spit.render_controllers.json`
        // y `render_controllers/wither_skull.render_controllers.json`. Faltaban
        // en el catálogo, así que se reportaban como ausentes.
        "controller.render.llama_spit", "controller.render.wither_skull"
    )

    private var VANILLA_RENDER_CONTROLLER_PREFIXES = mutableListOf(
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

    /** Animaciones vanilla por identificador EXACTO. (mutable para actualización remota) */
    private var VANILLA_ANIMATIONS = mutableSetOf<String>()

    /**
     * Las animaciones vanilla son familias por prefijo, no ids cerrados:
     * "animation.player.*" cubre cientos de animaciones que Mojang provee.
     */
    private var VANILLA_ANIMATION_PREFIXES = mutableListOf(
        // ══ Familias de Mojang que FALTABAN ══
        // El reporte de fusión de Better MC tenía 211 líneas
        // "animación 'animation.common.look_at_target' ausente" y 4 de
        // "controller.animation.iron_golem.move": todas vanilla. `animation.common.*`
        // y `animation.actor.*` son las familias compartidas por todos los mobs de
        // Bedrock (look_at_target, billboard…), no ids de un addon.
        "animation.common.", "animation.actor.", "animation.arrow.",
        "animation.armor.", "animation.bow.", "animation.crossbow.",
        "animation.parrot.", "animation.creeper.", "animation.bat.",
        "animation.blaze.", "animation.cod.", "animation.dolphin.",
        "animation.drowned.", "animation.enderman.", "animation.fox.",
        "animation.frog.", "animation.ghast.", "animation.goat.",
        "animation.guardian.", "animation.hoglin.", "animation.horse.",
        "animation.husk.", "animation.llama.", "animation.magma_cube.",
        "animation.panda.", "animation.parrot.", "animation.phantom.",
        "animation.piglin.", "animation.polar_bear.", "animation.rabbit.",
        "animation.ravager.", "animation.salmon.", "animation.sheep.",
        "animation.shulker.", "animation.silverfish.", "animation.skeleton.",
        "animation.slime.", "animation.snow_golem.", "animation.spider.",
        "animation.squid.", "animation.stray.", "animation.strider.",
        "animation.tadpole.", "animation.tropicalfish.", "animation.turtle.",
        "animation.vex.", "animation.villager.", "animation.vindicator.",
        "animation.wandering_trader.", "animation.warden.", "animation.witch.",
        "animation.wither.", "animation.wither_skeleton.", "animation.wolf.",
        "animation.zoglin.", "animation.zombie.", "animation.zombie_pigman.",
        "animation.zombie_villager.",
        // Controladores de animación vanilla (mismo hueco que arriba)
        "controller.animation.iron_golem.", "controller.animation.arrow.",
        "controller.animation.armor_stand.", "controller.animation.bat.",
        "controller.animation.cod.", "controller.animation.drowned.",
        "controller.animation.ghast.", "controller.animation.horse.",
        "controller.animation.local_health.",
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
        "controller.animation.bogged.", "controller.animation.zombie.",
        // ══ Verificados contra samples oficiales v1.26.70 ══
        // Ojo con el guion bajo: `animation.llama_spit.*` NO empieza por
        // `animation.llama.` y `animation.wither_skull.*` NO empieza por
        // `animation.wither.`, así que las familias ya presentes no los cubrían
        // y `custom_llama_spit` / `custom_wither_skull_*` (Origins) los daban por
        // ausentes aunque son del juego (`animations/llama_spit.animation.json`,
        // `animations/wither_skull.animation.json`).
        "animation.llama_spit.", "animation.wither_skull."
    )

    fun isVanillaAnimation(id: String): Boolean {
        val clean = id.trim()
        if (clean.isEmpty()) return false
        // 1) Catálogo real (descargado/empaquetado): ids exactos.
        if (clean in VANILLA_ANIMATIONS) return true
        // 2) Familias por prefijo (cubre variantes que Mojang añade por versión).
        return VANILLA_ANIMATION_PREFIXES.any { clean.startsWith(it) }
    }

    // ── TEXTURAS ───────────────────────────────────────────────────────

    /**
     * Prefijos de textura vanilla por SEGMENTO completo.
     * El trailing "/" impide que "textures/entity/zombieboss" matchee con
     * "textures/entity/zombie/".
     */
    private var VANILLA_TEXTURE_PREFIXES = mutableListOf(
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
        // `textures/entity/chicken` (sin barra) es la textura PLANA de vanilla
        // `textures/entity/chicken.png`, no una carpeta. El player de Origins la
        // referencia así y salía como "sin origen" con un match absurdo.
        "textures/entity/chicken",
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
        // ══ FALTABAN (medido en el reporte de Better MC) ══
        // - boat/: texturas de barca y barca con cofre ("chest_boat_oak").
        // - arrows: la FLECHA usa un solo archivo fuera de su carpeta.
        // - particle/: atlas y sprites de partículas ("textures/particle/soul").
        // - armor/, shield/, chest/, signs/, bed/, banner/, beacon_beam/:
        //   familias completas que ningún addon empaqueta.
        "textures/entity/boat/", "textures/entity/arrow/",
        "textures/entity/arrows", "textures/particle/",
        "textures/entity/armor/", "textures/entity/shield/",
        "textures/entity/chest/", "textures/entity/signs/",
        "textures/entity/bed/", "textures/entity/banner/",
        "textures/entity/beacon_beam/", "textures/entity/lead_knot/",
        "textures/entity/minecart/", "textures/entity/projectiles/",
        "textures/entity/conduit/", "textures/entity/trident/",
        "textures/entity/elytra/", "textures/entity/player/",
        // Directorios vanilla completos
        "textures/items/", "textures/blocks/", "textures/misc/",
        "textures/gui/", "textures/ui/", "textures/hud/",
        "textures/mob_effect/", "textures/environment/",
        "textures/colormap/", "textures/flame/",
        "textures/beacon/", "textures/rail/", "textures/paintings/",
        // ══ Verificados contra samples oficiales v1.26.70 ══
        // `custom_wither_skull_*` referencia `textures/entity/wither_boss/wither_invulnerable`
        // y el player de Origins `textures/entity/illager/evoker`; ambas son
        // texturas del juego (`textures/entity/wither_boss/wither_invulnerable.png`,
        // `textures/entity/illager/evoker.png`). `bat_v2` es la textura del
        // murciélago rediseñado y vive suelta en `textures/entity/`.
        "textures/entity/wither_boss/", "textures/entity/illager/",
        "textures/entity/bat_v2",
        // ⭐ FASE 5 — `textures/models/armor/` es la carpeta de las texturas de
        // armadura del juego (`copper_1`, `chain_1`, `diamond_2`,
        // `netherite_1`…). Es EXCLUSIVAMENTE vanilla: en el resource pack
        // oficial `textures/models/` contiene solo `armor/` (78 archivos), así
        // que marcarla no puede enmascarar una textura de addon con esa ruta.
        // El reporte real de Better MC daba `textures/models/armor/copper_1` y
        // `copper_2` por ausentes aunque el juego las trae de fábrica.
        "textures/models/armor/"
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

    private var VANILLA_SOUND_PREFIXES = mutableListOf(
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
            else -> isVanillaMaterial(clean)
        }
    }

    /**
     * ⭐ FASE 5: materiales del motor, sin namespace.
     *
     * Bedrock trae decenas de materiales de fábrica y muchos se referencian en
     * `render_controllers` SIN namespace: `material 'boat'`, `'villager'`,
     * `'entity_alphatest'`… No están en la carpeta `materials/` porque son del
     * motor, no del pack.
     *
     * Sin esta lista, el validador los declaraba "material custom no existe":
     * en el reporte de Better MC eran la mayoría de las 41 referencias rotas.
     * Un material con namespace (`cc_outline:entity_static`) SÍ es del pack y
     * sigue validándose contra `materials/`.
     */
    fun isVanillaMaterial(id: String): Boolean {
        val clean = id.trim()
        if (clean.isEmpty()) return false
        // Un material con namespace es del pack: nunca vanilla por esta vía.
        if (clean.contains(':')) return false
        return clean in VANILLA_MATERIALS
    }

    /**
     * Materiales del motor de Bedrock (los que se referencian SIN namespace).
     *
     * ⭐ FASE 5 — lista COMPLETA extraída del juego instalado, no escrita a mano.
     *
     * Fuente: los **14 archivos de la carpeta `materials` del resource pack
     * vanilla del propio juego** (`…/resource_packs/vanilla/materials`), que sí
     * trae la tabla completa del motor —a diferencia del paquete de samples de
     * Mojang, que **no incluye esa carpeta** y por eso solo dejaba ver los
     * materiales que las entidades *usan*, no todos los que *existen*.
     * Resultado: **413 materiales reales**.
     *
     * Historia de esta lista, porque explica por qué está derivada y no a mano:
     *
     *  1. Empezó siendo una heurística de 67 entradas. Se quedaba corta: faltaban
     *     `iron_golem`, `snowball` y `arrow`, que aparecen en las entidades
     *     vanilla `entity/iron_golem.json`, `entity/snowball.json` y
     *     `entity/arrow.json`. El reporte de Better MC acusaba de "material no
     *     existe en el modpack" a decenas de entidades de CoreCraft que solo
     *     usaban materiales del juego.
     *  2. Se amplió a 135 extrayéndolos de las entidades vanilla de los samples.
     *     Seguía corta: faltaban `armor_enchanted`, `entity_alphatest_glint`,
     *     `armor_leather_enchanted` (visibles en `attachables/`) y los que
     *     **ninguna entidad vanilla usa** pero el motor define igual.
     *  3. Ahora se extrae de los archivos `.material` del juego: la tabla
     *     completa, incluidos los que nadie referencia.
     *
     * Las tres veces el fallo fue el mismo y es el que el resto de este archivo
     * ya documenta para geometrías y animaciones: **una lista de vanilla escrita
     * a mano siempre se queda corta**. La conclusión de diseño es que estos
     * catálogos deben derivarse de una fuente, y que quedarse corto no es
     * inocuo: produce avisos falsos que mandan al usuario a perseguir contenido
     * que está perfecto.
     *
     * Los nombres con sufijo `_glint`, `_color` o de familia (`terrain_*`,
     * `moving_block_*`, `ui_*`) están todos incluidos literalmente, así que no
     * hace falta búsqueda por prefijo para ellos.
     */
    private val VANILLA_MATERIALS = setOf(
        "agent", "allay", "alpha_block", "alpha_block_color", "animated_texture_blit",
        "armadillo", "armor", "armor_enchanted", "armor_leather", "armor_leather_enchanted",
        "armor_stand", "arrow", "ash", "axolotl", "axolotl_limbs", "banner", "banner_pole",
        "bat", "bat_v2", "beacon_beam", "beacon_beam_transparent", "bed", "bee", "bell",
        "blaze_body", "blaze_head", "block_overlay", "blue_spores", "boat", "bogged",
        "bogged_clothes", "breeze", "breeze_eyes", "breeze_wind", "breeze_wind_charge", "camel",
        "camera", "camera_facing_sprite", "cat", "chalkboard", "charged_creeper", "chest",
        "chest_boat", "chicken", "chicken_cold", "chicken_legs", "clouds", "clownfish", "cod",
        "conduit", "conduit_wind", "copper_golem", "copper_golem_eyes", "copper_golem_flower",
        "cow", "cow_cold", "cracks_overlay", "cracks_overlay_alpha_test",
        "cracks_overlay_tile_entity", "creaking", "creaking_eyes", "creeper", "cushion",
        "debug", "debug_array_texture", "debug_texture", "dolphin", "dragon_head",
        "dragon_head_glint", "drowned", "egg", "elytra", "elytra_glint",
        "enchanting_table_book", "ender_crystal", "ender_dragon", "ender_pearl", "enderman",
        "enderman_invisible", "endermite", "entity", "entity_alphablend",
        "entity_alphablend_nocolor", "entity_alphatest", "entity_alphatest_change_color",
        "entity_alphatest_change_color_glint", "entity_alphatest_glint",
        "entity_alphatest_glint_item", "entity_alphatest_multicolor_tint",
        "entity_alphatest_one_sided", "entity_beam", "entity_beam_additive",
        "entity_change_color", "entity_change_color_glint", "entity_custom",
        "entity_dissolve_layer0", "entity_dissolve_layer1", "entity_emissive",
        "entity_emissive_alpha", "entity_emissive_alpha_one_sided",
        "entity_emissive_layer_alpha_blend", "entity_emissive_layer_alpha_test",
        "entity_flat_color_line", "entity_glint", "entity_lead_base", "entity_loyalty_rope",
        "entity_multitexture", "entity_multitexture_alpha_test",
        "entity_multitexture_alpha_test_color_mask", "entity_multitexture_color_mask",
        "entity_multitexture_masked", "entity_multitexture_multiplicative_blend",
        "entity_nocull", "entity_static", "evoker", "experience_orb", "eye_of_ender_signal",
        "fang", "fireball", "fireworks_rocket", "fishing_hook", "fox", "frog",
        "fullscreen_cube_overlay", "fullscreen_cube_overlay_blend",
        "fullscreen_cube_overlay_opaque", "ghast", "glow_sign_text", "glow_squid", "goat",
        "guardian", "guardian_ghost", "harness", "hoglin", "holo_coord_frame", "holo_hand",
        "holo_hand_pointer", "holo_hud_quad", "hologram", "hologram_hit_effect",
        "hololens_LSRPlane", "hololens_LSRPlaneTextured", "hololens_UICursor",
        "hololens_UICursor_ingame_mainpart", "hololens_UICursor_ingame_pseudoshadow",
        "holoscreen_level_quad", "holoscreen_monoscopic_quad", "holoscreen_stereoscopic_quad",
        "horse", "horse_leather_armor", "husk", "husk_clothes", "im_gui", "iron_golem",
        "item_in_hand", "item_in_hand_entity_alphatest", "item_in_hand_entity_alphatest_color",
        "item_in_hand_entity_alphatest_one_sided_color", "item_in_hand_glint",
        "item_in_hand_multicolor_tint", "leash_knot", "lightning", "lingering_potion_enchanted",
        "living_room", "llama", "llama_spit", "magma_cube", "map", "map_decoration",
        "map_marker", "minecart", "mob_head", "mob_head_glint", "mooshroom",
        "mooshroom_mushrooms", "moving_block", "moving_block_alpha",
        "moving_block_alpha_seasons", "moving_block_alpha_single_side", "moving_block_blend",
        "moving_block_double_side", "moving_block_seasons", "name_tag", "name_tag_alpha_tested",
        "name_tag_depth_tested", "name_tag_depth_tested_with_backface", "name_tag_text",
        "name_tag_text_with_backface", "name_tag_with_backface", "name_text_depth_tested",
        "name_text_depth_tested_with_backface", "nautilus", "nautilus_armor", "npc", "ocelot",
        "on_screen_effect", "opaque_block", "opaque_block_color", "opaque_block_color_uv2",
        "overlay_quad", "overlay_quad_clear", "panda", "parrot", "particles_add",
        "particles_alpha", "particles_base", "particles_blend", "particles_effects",
        "particles_opaque", "particles_random_test", "passthru_postprocess", "pattern",
        "pattern_glint", "phantom", "phantom_invisible", "pig", "pig_v3", "piglin",
        "piglin_brute", "pillager", "piston_arm", "plankton", "player", "player_alphatest",
        "player_animated", "player_spectator", "polar_bear", "portal_base", "precipitation",
        "pufferfish", "rabbit", "rain", "ravager", "reality_frame_cutout",
        "reality_frame_skyfill", "red_spores", "rift_mirror_logo", "rift_mirror_texture",
        "rift_texture_copy_noalpha", "saddle", "salmon", "selection_box", "selection_overlay",
        "selection_overlay_alpha", "selection_overlay_block_entity",
        "selection_overlay_double_sided", "selection_overlay_item", "selection_overlay_level",
        "selection_overlay_opaque", "shadow_back", "shadow_front", "shadow_overlay", "sheep",
        "shield", "shield_glint", "shulker", "shulker_box", "shulker_bullet", "sign",
        "sign_text", "silverfish", "silverfish_layers", "skeleton", "slime", "slime_outer",
        "sniffer", "snow", "snow_golem", "snow_golem_pumpkin", "snowball", "spider",
        "spider_invisible", "splash_potion_enchanted", "squid", "ST_BatchedStandard",
        "ST_Blur_1", "ST_Blur_10", "ST_Blur_11", "ST_Blur_12", "ST_Blur_2", "ST_Blur_3",
        "ST_Blur_4", "ST_Blur_5", "ST_Blur_6", "ST_Blur_7", "ST_Blur_8", "ST_Blur_9",
        "ST_Hairline", "ST_HairlinePath", "ST_LinearGradient2Point",
        "ST_LinearGradient3PointSymmetrical", "ST_LinearGradientFromTexture",
        "ST_LinearGradientMasked2Point", "ST_LinearGradientMasked3PointSymmetrical",
        "ST_LinearGradientMaskedFromTexture", "ST_Path", "ST_RadialGradient2Point",
        "ST_RadialGradient3PointSymmetrical", "ST_RadialGradientFromTexture",
        "ST_RadialGradientMasked2Point", "ST_RadialGradientMasked3PointSymmetrical",
        "ST_RadialGradientMaskedFromTexture", "ST_SimpleTexture", "ST_SimpleTextureMasked",
        "ST_Standard", "ST_StandardCircle", "ST_StandardEllipse", "ST_StandardRRect",
        "ST_StandardStrokeCircle", "ST_StandardStrokeEllipse", "ST_StandardStrokeRRect",
        "ST_StandardTexture", "ST_StandardTextureWithColorMatrix", "ST_Stencil",
        "ST_StencilCircle", "ST_StencilPath", "ST_StencilRRect", "ST_StencilTexture", "ST_Text",
        "ST_TextSDF", "ST_TextStrokeSDF", "ST_TexturesWithColorMix", "ST_YUV2ARGB",
        "ST_YUV2RGB", "stray", "stray_clothes", "strider", "sulfur_cube", "sulfur_cube_outer",
        "tadpole", "terrain_alpha", "terrain_alpha_seasons", "terrain_alpha_single_side",
        "terrain_base", "terrain_blend", "terrain_blend_far", "terrain_blend_no_culling",
        "terrain_doubleside", "terrain_far", "terrain_far_alpha_single_side",
        "terrain_inside_block", "terrain_opaque", "terrain_opaque_seasons",
        "terrain_seasons_far", "terrain_seasons_far_alpha", "trial_spawner", "trident",
        "trident_glint", "trident_riptide", "tropicalfish", "turtle", "ui_banner",
        "ui_banner_pole", "ui_banner_shield", "ui_banner_shield_glint", "ui_base", "ui_conduit",
        "ui_crosshair", "ui_cubemap", "ui_dragon_head", "ui_dragon_head_glint", "ui_fill_color",
        "ui_fill_gradient", "ui_fill_stencil", "ui_grayscale", "ui_grayscale_bilinear",
        "ui_inventory_item_glint", "ui_invert_overlay", "ui_item", "ui_item_glint",
        "ui_item_glint_stencil", "ui_item_multicolor_tint", "ui_item_unglint_stencil",
        "ui_mob_head", "ui_mob_head_glint", "ui_shield", "ui_shield_glint", "ui_skinning_item",
        "ui_text", "ui_text_msdf", "ui_text_smooth", "ui_texture_and_color",
        "ui_texture_and_color_blur", "ui_texture_and_color_blur_additive",
        "ui_texture_and_color_tint", "ui_textured", "ui_textured_and_glcolor",
        "ui_textured_and_glcolor_bilinear_sprite", "ui_textured_and_glcolor_sprite", "vex",
        "villager", "villager_v2", "villager_v2_masked", "vindicator", "wandering_trader",
        "warden", "warden_bioluminescent_layer", "water_hole", "white_ash",
        "windowsmr_holo_hand", "wireframe", "witch", "wither_boss", "wither_boss_armor",
        "wither_skull", "wolf", "wolf_armor", "xp_bottle", "zoglin", "zombie",
        "zombie_villager", "zombie_villager_v2", "zombie_villager_v2_masked"
    )

    /** Texto para los reportes: distingue "vanilla" de "referencia rota real". */
    const val VANILLA_NOTE: String = "asset VANILLA (resuelto por el juego)"

    // ── ACTUALIZACIÓN REMOTA DEL CATÁLOGO ─────────────────────────────────

    /**
     * URL base del catálogo remoto (raw de GitHub). Se puede sobrescribir
     * desde la UI o configuración si se aloja el catálogo en otro sitio.
     */
    const val VANILLA_CATALOG_REMOTE_URL = "https://raw.githubusercontent.com/Lord4rk2028/PackForge/master/vanilla_all_keys_complete.txt"

    /**
     * ══════════════════════════════════════════════════════════════════════
     * CATÁLOGO LOCAL (TICKET-CORE-003 · parte 2)
     * ══════════════════════════════════════════════════════════════════════
     *
     * Las listas de prefijos de arriba son heurísticas escritas a mano y SIEMPRE
     * se quedan cortas: en el reporte de Better MC faltaban `animation.common.*`,
     * `controller.render.item_sprite`, `textures/entity/boat/`… y eso produjo
     * ~1.000 errores falsos.
     *
     * El repo trae el catálogo REAL (`vanilla_all_keys_complete.txt`, extraído de
     * `bedrock-samples/resource_pack`: 638 animaciones, 184 controladores de
     * animación, 214 render controllers, 225 geometrías). Se empaqueta en
     * `assets/` y se carga UNA vez al arrancar el motor.
     */
    private const val VANILLA_CATALOG_ASSET = "vanilla_all_keys_complete.txt"

    /** Evita recargar el catálogo en cada llamada. */
    @Volatile
    private var catalogLoaded = false

    /** Entradas efectivamente cargadas (diagnóstico y tests). */
    @Volatile
    var catalogEntryCount: Int = 0
        private set

    /**
     * Carga el catálogo empaquetado en `assets/`. Idempotente y a prueba de
     * fallos: si el asset no existe (tests JVM, build sin assets) se mantienen
     * las listas heurísticas sin romper nada.
     */
    fun loadBundledCatalog(context: android.content.Context?) {
        if (catalogLoaded) return
        catalogLoaded = true

        // 1) Recurso de classpath: es el MISMO archivo que `assets/` (ver el
        //    sourceSets de app/build.gradle.kts). Cubre los tests JVM, donde no
        //    hay Context, y evita depender del ciclo de vida de Android.
        try {
            val stream = VanillaAssets::class.java.classLoader
                ?.getResourceAsStream(VANILLA_CATALOG_ASSET)
            if (stream != null) {
                stream.use { input ->
                    loadCatalogText(input.bufferedReader(StandardCharsets.UTF_8).readText())
                }
                PackForgeLog.d(
                    "PackForge_VanillaCatalog",
                    "📚 Catálogo vanilla cargado (classpath): $catalogEntryCount entradas"
                )
                return
            }
        } catch (e: Exception) {
            PackForgeLog.w("PackForge_VanillaCatalog", "Classpath sin catálogo: ${e.message}")
        }

        // 2) Asset de la app (camino garantizado en Android).
        if (context == null) return
        try {
            context.assets.open(VANILLA_CATALOG_ASSET).use { input ->
                loadCatalogText(input.bufferedReader(StandardCharsets.UTF_8).readText())
            }
            PackForgeLog.d(
                "PackForge_VanillaCatalog",
                "📚 Catálogo vanilla cargado desde assets: $catalogEntryCount entradas"
            )
        } catch (e: Exception) {
            PackForgeLog.w(
                "PackForge_VanillaCatalog",
                "No se pudo cargar el catálogo vanilla empaquetado: ${e.message}"
            )
        }
    }

    /** Fuerza una recarga (tests o catálogo actualizado en disco). */
    fun reloadBundledCatalog(context: android.content.Context?) {
        catalogLoaded = false
        loadBundledCatalog(context)
    }

    /** Carga el catálogo desde un archivo de texto (tests de escritorio). */
    fun loadCatalogFile(file: File): Int {
        if (!file.isFile) return 0
        return loadCatalogText(file.readText(StandardCharsets.UTF_8))
    }

    /**
     * Fusiona un catálogo en formato "una entrada por línea".
     *
     * El formato de Mojang NO está categorizado: la entrada se clasifica por su
     * FORMA, nunca por descarte. Antes cualquier línea desconocida acababa en
     * `VANILLA_SOUND_PREFIXES` (`:465`), así que descargar el catálogo de 1998
     * ids de bloques/ítems contaminaba los sonidos y no arreglaba nada.
     *
     * @return nº de entradas nuevas añadidas.
     */
    fun loadCatalogText(text: String): Int {
        var added = 0
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue

            // Comentario con categoría explícita opcional: "[geometry] geometry.x".
            val category: String?
            val value: String
            if (line.startsWith("[")) {
                val end = line.indexOf(']')
                if (end > 0) {
                    category = line.substring(1, end).trim().lowercase()
                    value = line.substringAfter(']').trim()
                } else {
                    category = null
                    value = line
                }
            } else {
                category = null
                value = line
            }
            if (value.isEmpty()) continue

            added += when (category) {
                "geometry" -> if (VANILLA_GEOMETRIES.add(value)) 1 else 0
                "geometry_prefix" -> if (VANILLA_GEOMETRY_PREFIXES.add(value)) 1 else 0
                "render_controller" -> if (VANILLA_RENDER_CONTROLLERS.add(value)) 1 else 0
                "render_controller_prefix" -> if (VANILLA_RENDER_CONTROLLER_PREFIXES.add(value)) 1 else 0
                "animation" -> if (VANILLA_ANIMATIONS.add(value)) 1 else 0
                "animation_prefix" -> if (VANILLA_ANIMATION_PREFIXES.add(value)) 1 else 0
                "texture" -> if (VANILLA_TEXTURE_PREFIXES.add(value)) 1 else 0
                "sound" -> if (VANILLA_SOUND_PREFIXES.add(value)) 1 else 0
                else -> when {
                    value.startsWith("controller.render.") ->
                        if (VANILLA_RENDER_CONTROLLERS.add(value)) 1 else 0
                    value.startsWith("controller.animation.") ->
                        if (VANILLA_ANIMATIONS.add(value)) 1 else 0
                    value.startsWith("animation.") ->
                        if (VANILLA_ANIMATIONS.add(value)) 1 else 0
                    value.startsWith("geometry.") ->
                        if (VANILLA_GEOMETRIES.add(value)) 1 else 0
                    value.startsWith("textures/") ->
                        if (VANILLA_TEXTURE_PREFIXES.add(value)) 1 else 0
                    value.startsWith("sounds/") ->
                        if (VANILLA_SOUND_PREFIXES.add(value)) 1 else 0
                    // `minecraft:*` = blocks/items: no se usan para validar
                    // referencias de addons, así que no se guardan como sonido.
                    else -> 0
                }
            }
        }
        catalogEntryCount += added
        return added
    }

    /**
     * Guarda el catálogo local actualizado en disco para persistencia entre sesiones.
     * Se llama tras una actualización remota exitosa.
     */

    /** Timeout de descarga en ms. */
    private const val DOWNLOAD_TIMEOUT_MS = 8000

    /**
     * Intenta descargar el catálogo vanilla remoto y fusionarlo con el local.
     * Si falla (sin red, URL inválida, JSON malformado), se mantiene el catálogo
     * estático embalsamado y se devuelve false.
     *
     * Esta función es idempotente y segura: nunca corrompe el catálogo local.
     *
     * @return true si se actualizó desde remoto, false si se usó el local.
     */
    fun tryUpdateFromRemote(): Boolean {
        return try {
            val url = URL(VANILLA_CATALOG_REMOTE_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                connectTimeout = DOWNLOAD_TIMEOUT_MS
                readTimeout = DOWNLOAD_TIMEOUT_MS
                instanceFollowRedirects = true
            }

            if (connection.responseCode != 200) {
                PackForgeLog.d("PackForge_VanillaRemote", "Catálogo remoto respondió ${connection.responseCode}, usando local")
                false
            } else {
                connection.inputStream.use { input ->
                    val reader = java.io.BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))
                    val text = reader.readText()
                    val added = loadCatalogText(text)
                    if (added > 0) {
                        PackForgeLog.d("PackForge_VanillaRemote", "✅ Catálogo vanilla actualizado desde remoto ($added entradas)")
                        true
                    } else {
                        PackForgeLog.d("PackForge_VanillaRemote", "Catálogo remoto inválido, usando local")
                        false
                    }
                }
            }
        } catch (e: Exception) {
            PackForgeLog.w("PackForge_VanillaRemote", "No se pudo actualizar catálogo remoto: ${e.message}")
            false
        }
    }

    /**
     * Compatibilidad: el catálogo remoto se carga con el MISMO clasificador que
     * el local ([loadCatalogText]). Antes cada línea desconocida caía en
     * `VANILLA_SOUND_PREFIXES`, así que descargar el catálogo de bloques/ítems
     * no aportaba ni una animación y contaminaba los sonidos.
     */
    private fun mergeRemoteCatalog(text: String): Boolean = loadCatalogText(text) > 0

    /**
     * Guarda el catálogo local actualizado en disco para persistencia entre sesiones.
     * Se llama tras una actualización remota exitosa.
     */
    fun persistRemoteCatalog(context: android.content.Context) {
        // Nota: implementación simplificada. En una versión completa se usaría
        // Context.getFilesDir() para guardar el JSON fusionado.
        PackForgeLog.d("PackForge_VanillaRemote", "Persistencia de catálogo remoto diferida (usa Context.getFilesDir())")
    }
}
