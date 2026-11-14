package com.packforge.app.domain.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⭐ FASE 5 — MATERIALES DEL MOTOR: la lista escrita a mano se quedaba corta.
 *
 * El reporte del `.mcaddon` real de Better MC acusaba a decenas de entidades de
 * CoreCraft de referenciar materiales "que no existen en el modpack":
 *
 * ```
 * El material 'iron_golem' se referencia en 'entity/echo_golem2.entity.json' pero no existe
 * El material 'snowball'   se referencia en 'entity/vyj0x1wd.json' pero no existe
 * El material 'arrow'      se referencia en 'entity/rtg8163n.json' pero no existe
 * ```
 *
 * Los tres SON materiales de fábrica. Se verificó contra el paquete oficial de
 * Mojang (`bedrock-samples-v1.26.50.4-full`): aparecen en las `client_entity`
 * de las entidades vanilla `entity/iron_golem.json`, `entity/snowball.json` y
 * `entity/arrow.json`. El resource pack vanilla **no tiene carpeta `materials/`**,
 * así que cualquier material citado ahí es del motor y lo resuelve el juego.
 *
 * La lista escrita a mano tenía 67 entradas y le faltaban 68. Extrayendo la
 * lista de los 180 archivos de entidades de Mojang salen **135 materiales
 * reales**. Este test fija los que provocaron los falsos positivos.
 */
class Fase5MaterialesMotorTest {

    @Test
    fun `los materiales vanilla que el reporte acusaba de faltar se reconocen`() {
        // Los tres casos exactos del reporte de Better MC.
        listOf("iron_golem", "snowball", "arrow").forEach {
            assertTrue(
                "'$it' aparece en las entidades vanilla de Mojang: es del motor, no un material ausente",
                VanillaAssets.isVanillaMaterial(it)
            )
        }
    }

    /**
     * Los 3 materiales que quedaron sin explicar tras las dos primeras pasadas y
     * que se verificaron contra el `materials/entity.material` del JUEGO
     * instalado (`…/resource_packs/vanilla/materials`). Los tres están definidos
     * ahí con estas relaciones:
     *
     *   `entity_custom:entity`
     *   `opaque_block:entity_static`
     *   `entity_alphatest_change_color_glint:entity_change_color`
     *
     * Antes los tenía por "contenido roto del addon" porque no aparecían en los
     * samples de Mojang ni en su documentación. **Era un error de mi
     * diagnóstico**: los samples no traen la carpeta `materials/`, así que su
     * ausencia ahí no probaba nada. Es la lección de método de esta fase: la
     * ausencia de evidencia solo es concluyente si la fuente es completa.
     */
    @Test
    fun `los tres materiales que parecian contenido roto son del motor`() {
        listOf(
            "entity_custom",
            "opaque_block",
            "entity_alphatest_change_color_glint"
        ).forEach {
            assertTrue(
                "'$it' está definido en el materials/entity.material del juego: es del motor",
                VanillaAssets.isVanillaMaterial(it)
            )
        }
    }

    @Test
    fun `las familias del motor que solo se ven en el archivo del juego`() {
        // Ninguna entidad vanilla de los samples usa estos, pero el motor los
        // define: sin la tabla completa del juego seguían saliendo como rotos.
        listOf(
            "opaque_block_color", "opaque_block_color_uv2", "alpha_block",
            "alpha_block_color", "entity_change_color", "entity_change_color_glint",
            "entity_glint", "entity_alphatest_glint_item", "mob_head",
            "mob_head_glint", "pattern", "pattern_glint", "saddle",
            "shulker_box", "trial_spawner", "wolf_armor", "harness",
            "trident_riptide", "trident_glint", "shield_glint",
            "nautilus_armor", "piston_arm", "map_marker", "map_decoration",
            "moving_block", "moving_block_blend", "terrain_base",
            "terrain_blend", "ui_base", "ui_text", "wireframe",
            "selection_box", "shadow_front", "water_hole", "portal_base"
        ).forEach {
            assertTrue("debería ser material del motor: $it", VanillaAssets.isVanillaMaterial(it))
        }
    }

    @Test
    fun `materiales del motor de entidades vanilla comunes se reconocen`() {
        listOf(
            "boat", "villager", "player", "armor_stand", "horse", "minecart",
            "guardian", "guardian_ghost", "spider", "spider_invisible",
            "skeleton", "zombie", "zombie_villager", "witch", "vex",
            "blaze_head", "wither_skull", "snow_golem", "snow_golem_pumpkin",
            "llama", "fireball", "shulker_bullet", "llama_spit",
            "fang", "egg", "ender_pearl", "experience_orb", "fireworks_rocket",
            "leash_knot", "armor", "banner", "bed", "bell",
            "shield", "trident", "sign", "chest_boat", "saddle", "harness"
        ).forEach {
            assertTrue("debería ser material del motor: $it", VanillaAssets.isVanillaMaterial(it))
        }
    }

    @Test
    fun `familias del motor con sufijo libre se reconocen`() {
        // Verificados contra los 14 archivos .material del juego instalado.
        // Ojo: nombres plausibles como `outline`, `entity_translucent`,
        // `item_glint` o `entity_cutout` NO existen en el motor; se colaron en
        // una versión anterior de esta lista por analogía y hubo que quitarlos.
        listOf(
            "entity_alphatest", "entity_alphablend", "entity_emissive",
            "entity_emissive_alpha", "entity_static", "entity_multitexture",
            "entity_nocull", "entity_change_color", "entity_glint",
            "entity_alphatest_glint", "entity_alphatest_change_color",
            "entity_alphatest_change_color_glint", "entity_flat_color_line",
            "entity_emissive_layer_alpha_test", "entity_multitexture_color_mask",
            "item_in_hand", "item_in_hand_glint", "item_in_hand_multicolor_tint",
            "terrain_base", "terrain_opaque", "terrain_blend", "terrain_alpha",
            "particles_alpha", "particles_blend", "particles_add",
            "beacon_beam", "beacon_beam_transparent", "selection_box",
            "ui_base", "ui_text", "wireframe", "moving_block"
        ).forEach {
            assertTrue("debería ser familia del motor: $it", VanillaAssets.isVanillaMaterial(it))
        }
    }

    /**
     * Estos nombres **parecen** materiales del motor pero NO lo son: se colaron
     * en una versión anterior de la lista porque los escribí por analogía en vez
     * de extraerlos. `outline`, `entity_translucent`, `item_glint`… ninguno
     * existe en los 14 archivos `.material` del juego.
     *
     * Este test es la defensa contra volver a inventarlos: si alguien añade uno
     * "porque suena bien", aquí se rompe.
     */
    @Test
    fun `los nombres plausibles pero inexistentes NO se dan por vanilla`() {
        listOf(
            "outline", "outline_base", "outline_base_glow", "outline_base_color",
            "outline_glow", "outline_combined", "outline_combined_glow",
            "entity_translucent", "entity_translucent_emissive",
            "entity_cutout", "entity_cutout_no_cull",
            "item_glint", "item_sprite", "water_still",
            "beacon", "enchanting_table", "skull", "item",
            "terrain", "terrain_cutout", "terrain_opaque_cutout",
            "particles_additive", "particles_additive_blend",
            "selection_wireframe", "sun", "moon", "stars",
            "alphatest", "blend", "opaque", "cutout", "nocull", "double_sided"
        ).forEach {
            assertFalse(
                "'$it' NO existe en los .material del juego: no debe darse por vanilla",
                VanillaAssets.isVanillaMaterial(it)
            )
        }
    }

    /**
     * `clouds` es la excepción que confirma la regla: se llama igual que el
     * "obvio" de un material de nube, y esta vez **sí existe** — está en
     * `fancy.material`, junto a `passthru_postprocess`. Lo había metido en la
     * lista de inexistentes por analogía con `sun`, `moon` y `stars`, que no
     * existen. Es el mismo error de siempre en la dirección contraria.
     */
    @Test
    fun `los materiales de otros archivos del motor tambien cuentan`() {
        listOf(
            // fancy.material
            "clouds", "passthru_postprocess",
            // particles.material
            "particles_base", "particles_opaque", "particles_effects",
            "particles_random_test",
            // terrain.material
            "terrain_opaque_seasons", "terrain_doubleside", "terrain_far",
            "terrain_blend_no_culling", "terrain_inside_block",
            // ui.material / ui3D.material
            "ui_texture_and_color", "ui_fill_color", "block_overlay",
            "selection_overlay", "fullscreen_cube_overlay_opaque",
            // shadows.material / portal.material / wireframe.material
            "shadow_front", "shadow_back", "water_hole", "portal_base", "wireframe",
            // cameraFacingSprite.material
            "camera_facing_sprite"
        ).forEach {
            assertTrue("debería ser material del motor: $it", VanillaAssets.isVanillaMaterial(it))
        }
    }

    /**
     * Un material CON namespace (`cc_outline:entity_static`) es del pack, no del
     * motor. Si se diera por vanilla, un material propio roto pasaría sin aviso
     * y la entidad se renderizaría mal sin que nadie lo supiera.
     */
    @Test
    fun `un material con namespace NO se declara vanilla`() {
        listOf(
            "cc_outline:entity_static",
            "cc_entity_enchant:entity_static",
            "glow:entity_alphablend",
            "mi_addon:mi_material"
        ).forEach {
            assertFalse(
                "'$it' lleva namespace: es del pack y debe validarse",
                VanillaAssets.isVanillaMaterial(it)
            )
        }
    }

    @Test
    fun `un material inventado sigue detectandose como ausente`() {
        listOf("material_que_no_existe", "custom_mat_123", "zzz_fake")
            .forEach {
                assertFalse(
                    "'$it' no es del motor: debe seguir reportándose",
                    VanillaAssets.isVanillaMaterial(it)
                )
            }
    }

    @Test
    fun `la comprobacion tolera espacios alrededor`() {
        assertTrue(VanillaAssets.isVanillaMaterial("  iron_golem  "))
        assertFalse(VanillaAssets.isVanillaMaterial("   "))
    }
}
