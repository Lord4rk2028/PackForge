package com.packforge.app.domain.engine

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * ⭐ FASE 5 — UN PUNTO EN EL NOMBRE NO ES UNA EXTENSIÓN.
 *
 * El reporte del `.mcaddon` real de Better MC listaba como rotas texturas que
 * **sí estaban empaquetadas**, en 30 entidades de CoreCraft:
 *
 * ```
 * sin textura en ningún addon: textures/cc/custom_villager/levels/base_lvl.1
 * sin textura en ningún addon: textures/cc/custom_villager/levels/base_lvl.2
 * ```
 *
 * Y en el ZIP: `RP_PackForge/textures/cc/custom_villager/levels/base_lvl.1.png`.
 *
 * La causa era doble y las dos mitades usaban `substringBeforeLast('.')`, que
 * asume que todo lo que sigue al último punto es la extensión:
 *
 * 1. **El inventario** (`collectTextureInventory`) guardaba `…/base_lvl` en vez
 *    de `…/base_lvl.1` — el `.1` se perdía como si fuera un sufijo de formato.
 * 2. **La comprobación** (`checkTextureReference`) buscaba con el mismo recorte,
 *    así que ni siquiera podía acertar contra un inventario correcto.
 *
 * Bedrock referencia las texturas SIN extensión (`…/base_lvl.1`) y el archivo la
 * lleva (`…/base_lvl.1.png`), así que la única forma correcta de emparejarlas es
 * quitar/añadir las extensiones de imagen REALES por sufijo, nunca adivinar por
 * el último punto. Esto afecta a cualquier textura con puntos en el nombre:
 * `base_lvl.1..5`, `outfit.0`, `sword.icon`…
 */
class Fase5TexturaConPuntoEnNombreTest {

    private lateinit var rp: File

    @Before
    fun setUp() {
        rp = Files.createTempDirectory("pf_textura_punto").toFile()
        File(rp, "textures/cc/custom_villager/levels").mkdirs()
    }

    @After
    fun tearDown() {
        rp.deleteRecursively()
    }

    /** Crea el PNG real y una entidad que lo referencia SIN extensión. */
    private fun escenario(nombreArchivo: String, referencia: String) {
        File(rp, "textures/cc/custom_villager/levels/$nombreArchivo")
            .writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))

        val dir = File(rp, "entity")
        dir.mkdirs()
        File(dir, "npc.json").writeText(
            """
            {
              "format_version": "1.10.0",
              "minecraft:client_entity": {
                "description": {
                  "identifier": "cc:npc_prueba",
                  "materials": { "default": "entity_alphatest" },
                  "textures": { "default": "$referencia" },
                  "geometry": { "default": "geometry.humanoid" },
                  "render_controllers": ["controller.render.default"]
                }
              }
            }
            """.trimIndent(),
            Charsets.UTF_8
        )
    }

    private fun faltantes(): List<String> {
        val res = PackForgeHealer.repair(
            mergedBpDir = rp,
            mergedRpDir = rp,
            originalAddonPaths = emptyList()
        )
        return res.stillMissingTextures
    }

    @Test
    fun `una textura con punto en el nombre no se reporta como ausente`() {
        escenario(
            nombreArchivo = "base_lvl.1.png",
            referencia = "textures/cc/custom_villager/levels/base_lvl.1"
        )

        assertTrue(
            "base_lvl.1.png está en el pack: no puede salir como textura ausente",
            faltantes().none { it.contains("base_lvl.1") }
        )
    }

    @Test
    fun `el caso real de las 30 entidades de core-craft`() {
        // Los 5 niveles que usa cc:big_foot_quest_npc y compañía.
        for (n in 1..5) {
            escenario(
                nombreArchivo = "base_lvl.$n.png",
                referencia = "textures/cc/custom_villager/levels/base_lvl.$n"
            )
        }
        File(rp, "entity/npc.json").delete()

        val dir = File(rp, "entity")
        File(dir, "npc.json").writeText(
            """
            {
              "format_version": "1.10.0",
              "minecraft:client_entity": {
                "description": {
                  "identifier": "cc:big_foot_quest_npc",
                  "materials": { "default": "entity_alphatest" },
                  "textures": {
                    "level_1": "textures/cc/custom_villager/levels/base_lvl.1",
                    "level_2": "textures/cc/custom_villager/levels/base_lvl.2",
                    "level_3": "textures/cc/custom_villager/levels/base_lvl.3",
                    "level_4": "textures/cc/custom_villager/levels/base_lvl.4",
                    "level_5": "textures/cc/custom_villager/levels/base_lvl.5"
                  },
                  "geometry": { "default": "geometry.humanoid" },
                  "render_controllers": ["controller.render.default"]
                }
              }
            }
            """.trimIndent(),
            Charsets.UTF_8
        )

        assertTrue(
            "ninguno de los 5 niveles puede reportarse ausente",
            faltantes().none { it.contains("base_lvl") }
        )
    }

    @Test
    fun `una textura realmente ausente sigue detectandose`() {
        // Sin crear el PNG: la referencia debe seguir reportándose.
        escenario(
            nombreArchivo = "otra.png",
            referencia = "textures/cc/custom_villager/levels/base_lvl.9"
        )

        assertTrue(
            "base_lvl.9 no existe: debe seguir detectándose como ausente",
            faltantes().any { it.contains("base_lvl.9") }
        )
    }
}
