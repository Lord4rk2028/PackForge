package com.packforge.app.domain.engine

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TICKET-CORE-008 - REGRESION: el catalogo vanilla debe estar disponible.
 *
 * Los mobs vanilla salieron INVISIBLES en el juego porque el catalogo de
 * 2.223 ids solo se cargaba desde PackForgeApplication (Android). Generando
 * el pack en un test JVM el catalogo quedaba vacio, `controller.render.
 * armadillo.v2` (variante que SOLO figura en el catalogo) se clasificaba como
 * no-vanilla, EntityDependencyResolver lo borraba como referencia colgante y,
 * sin render controller del modelo, el mob no se dibujaba.
 *
 * Estos asserts fijan que el catalogo se autocarga bajo demanda y que las
 * variantes vanilla que solo viven en el catalogo se reconocen.
 */
class Fase4CatalogoVanillaTest {

    @Test
    fun `el catalogo vanilla se autocarga sin depender de Android`() {
        assertTrue(
            "el catalogo debe cargarse desde el classpath (catalogEntryCount=" +
                VanillaAssets.catalogEntryCount + ")",
            VanillaAssets.isCatalogReady
        )
    }

    @Test
    fun `las variantes vanilla que solo estan en el catalogo se reconocen`() {
        assertTrue(
            "controller.render.armadillo.v2 es vanilla y no debe neutralizarse",
            VanillaAssets.isVanillaRenderController("controller.render.armadillo.v2")
        )
        assertTrue(
            "controller.render.witch es vanilla",
            VanillaAssets.isVanillaRenderController("controller.render.witch")
        )
    }

    @Test
    fun `lo que no es vanilla sigue detectandose como tal`() {
        assertTrue(
            "los render controllers del HUD son del addon, no vanilla",
            !VanillaAssets.isVanillaRenderController("controller.render.hpi.heart")
        )
        assertTrue(
            "las geometrias del HUD son del addon, no vanilla",
            !VanillaAssets.isVanillaGeometry("geometry.hpi.heart")
        )
    }
}
