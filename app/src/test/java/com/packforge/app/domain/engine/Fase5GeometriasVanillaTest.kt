package com.packforge.app.domain.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⭐ FASE 5 — GEOMETRÍAS DEL MOTOR (falsos "modelo faltante")
 *
 * Tras arreglar la autocopia destructiva, el reporte de Better MC seguía con
 * **1 modelo faltante**: `geometry.item_sprite`. No lo define NINGÚN addon y lo
 * referencian 5 entidades de CoreCraft (`cc:slingshot_projectile`,
 * `cc:thrown_banana`, `cc:coconut_projectile`, `cc:peanut_projectile`,
 * `cc:end_stone_slingshot_projectile`): es geometría del motor, la de los
 * proyectiles/items en vuelo.
 *
 * Al no estar catalogada, el healer podía llegar a escribir un placeholder
 * ENCIMA de una geometría vanilla, que rompería el modelo para todo el juego.
 */
class Fase5GeometriasVanillaTest {

    @Test
    fun `geometry item_sprite se reconoce como vanilla`() {
        // El caso exacto que quedaba rojo en el reporte real.
        assertTrue(
            "geometry.item_sprite es del motor, no un modelo faltante",
            VanillaAssets.isVanillaGeometry("geometry.item_sprite")
        )
    }

    @Test
    fun `las geometrias del motor mas comunes se reconocen`() {
        listOf(
            "geometry.item_sprite", "geometry.item", "geometry.arrow",
            "geometry.shield", "geometry.elytra", "geometry.chest",
            "geometry.banner", "geometry.bed", "geometry.conduit",
            "geometry.flower_pot", "geometry.lectern", "geometry.shulker_box",
            "geometry.sign", "geometry.bell", "geometry.armor_stand",
            "geometry.experience_orb", "geometry.snowball", "geometry.tnt",
            "geometry.humanoid", "geometry.humanoid.custom",
            "geometry.player", "geometry.cape"
        ).forEach {
            assertTrue("debería ser vanilla: $it", VanillaAssets.isVanillaGeometry(it))
        }
    }

    @Test
    fun `las geometrias propias del addon NO se consideran vanilla`() {
        // Si estas se dieran por vanilla, el addon perdería sus modelos.
        listOf(
            "geometry.cc.pharaoh", "geometry.cc.soldier", "geometry.cc.meerkat",
            "geometry.cc.deer", "geometry.cc.villager_baby", "geometry.cc.empty",
            "geometry.cc.boat_chest", "geometry.my_custom_mob"
        ).forEach {
            assertFalse("NO debería ser vanilla: $it", VanillaAssets.isVanillaGeometry(it))
        }
    }

    @Test
    fun `la comprobacion tolera espacios alrededor`() {
        assertTrue(VanillaAssets.isVanillaGeometry("  geometry.item_sprite  "))
    }

    /**
     * La comparación NO ignora mayúsculas, y eso es DELIBERADO.
     *
     * Bedrock escribe estos identificadores en minúsculas. Si aquí se normalizara
     * a minúsculas, una geometría PROPIA del addon escrita con otra capitalización
     * (`geometry.CC.Meerkat`) pasaría por vanilla: el healer no la restauraría y
     * el validador no la reportaría. Se perdería contenido real en silencio.
     *
     * El principio de todo este pipeline es fail-open: un falso "falta un modelo"
     * es ruido inocente; un falso "esto es vanilla" DESTRUYE contenido. Ante la
     * duda, nunca declarar vanilla.
     */
    @Test
    fun `una geometria con otra capitalizacion NO se declara vanilla`() {
        assertFalse(
            "normalizar mayúsculas convertiría modelos propios en vanilla",
            VanillaAssets.isVanillaGeometry("GEOMETRY.ITEM_SPRITE")
        )
    }
}
