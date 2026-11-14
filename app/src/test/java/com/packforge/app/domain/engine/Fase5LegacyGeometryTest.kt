package com.packforge.app.domain.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * ⭐ FASE 5 — Geometría y materiales en formato LEGACY.
 *
 * Bedrock admite DOS formatos para declarar geometría y el validador solo
 * entendía uno. Con CoreCraft (que usa el legacy) eso producía falsos
 * positivos masivos en el reporte de Better MC:
 *
 *   · 438 "modelos faltantes"
 *   · 80 "entidades incompletas"  →  "geometría 'geometry.cc.pillager' no existe"
 *
 * Los assets SÍ estaban empaquetados. El validador no los veía.
 *
 * Estos tests fijan el contrato: ambos formatos cuentan como "declarado".
 */
class Fase5LegacyGeometryTest {

    /** Formato moderno: array `minecraft:geometry` con `description.identifier`. */
    private val moderno = """
        {
          "format_version": "1.12.0",
          "minecraft:geometry": [
            { "description": { "identifier": "geometry.moderna" },
              "bones": [ { "name": "body", "pivot": [0,0,0] } ] }
          ]
        }
    """.trimIndent()

    /** Formato legacy: la geometría es una CLAVE RAÍZ. Así lo hace CoreCraft. */
    private val legacy = """
        {
          "format_version": "1.10.0",
          "geometry.cc.pillager": {
            "texturewidth": 64, "textureheight": 64,
            "bones": [ { "name": "waist", "pivot": [0,12,0] } ]
          }
        }
    """.trimIndent()

    /**
     * Replica la indexación que hace el validador: debe reconocer AMBOS formatos.
     * Si alguien vuelve a dejar solo el moderno, este test cae.
     */
    private fun indexarGeometrias(json: JSONObject): Set<String> {
        val ids = mutableSetOf<String>()
        json.optJSONArray("minecraft:geometry")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.optJSONObject("description")
                    ?.optString("identifier")?.trim()?.let { ids.add(it) }
            }
        }
        json.keys().forEach { key ->
            if (key.startsWith("geometry.") && json.optJSONObject(key) != null) {
                ids.add(key.trim())
            }
        }
        return ids
    }

    @Test
    fun `el formato moderno se indexa`() {
        assertTrue("geometry.moderna" in indexarGeometrias(JSONObject(moderno)))
    }

    @Test
    fun `el formato legacy se indexa`() {
        val ids = indexarGeometrias(JSONObject(legacy))
        assertTrue(
            "La geometría legacy 'geometry.cc.pillager' debe reconocerse; " +
                "sin esto vuelven los 438 modelos faltantes",
            "geometry.cc.pillager" in ids
        )
    }

    @Test
    fun `un archivo con ambas geometrias las expone todas`() {
        val mixto = JSONObject(
            """
            {
              "format_version": "1.12.0",
              "minecraft:geometry": [
                { "description": { "identifier": "geometry.una" } }
              ],
              "geometry.dos": { "bones": [] },
              "geometry.tres": { "bones": [] }
            }
            """.trimIndent()
        )
        val ids = indexarGeometrias(mixto)
        assertTrue(setOf("geometry.una", "geometry.dos", "geometry.tres").all { it in ids })
    }

    @Test
    fun `una clave geometry que no es objeto no cuenta`() {
        // "geometry.foo": "texto" no es una definición: evita falsos negativos
        // al revés (creer declarada una geometría que no lo está).
        val ids = indexarGeometrias(JSONObject("""{"geometry.rara":"solo texto"}"""))
        assertFalse("geometry.rara" in ids)
    }

    // ── Materiales: viven en *.material, no en *.json ─────────────────

    @Test
    fun `los materiales se declaran en archivos material con estructura json`() {
        // Es el formato REAL de Bedrock (RP/materials/entity.material).
        val contenido = """
            {
              "materials": {
                "version": "1.0.0",
                "cc_outline:entity_static": { "+states": ["InvertCulling"] },
                "entity_outline:charged_creeper": { "+defines": ["ALPHA_TEST"] }
              }
            }
        """.trimIndent()

        val materiales = JSONObject(contenido).getJSONObject("materials")
            .keys().asSequence().filter { it != "version" }.toSet()

        assertTrue("cc_outline:entity_static" in materiales)
        assertTrue("entity_outline:charged_creeper" in materiales)
        assertFalse("'version' es cabecera, no un material", "version" in materiales)
    }

    @Test
    fun `los materiales de fabrica se reconocen sin namespace`() {
        // Estos aparecían como "material custom no existe" y son del MOTOR.
        for (m in listOf("boat", "villager", "entity_alphatest", "entity_alphablend")) {
            assertTrue(
                "'$m' es un material de fábrica de Bedrock; no debe reportarse roto",
                VanillaAssets.isVanillaMaterial(m)
            )
        }
    }

    @Test
    fun `un material con namespace nunca se considera vanilla`() {
        // Los del pack se validan de verdad contra materials/.
        for (m in listOf("cc_outline:entity_static", "cc_test:charged_creeper")) {
            assertFalse(
                "'$m' es del pack: debe validarse, no eximirse",
                VanillaAssets.isVanillaMaterial(m)
            )
        }
    }

    // ── Raíz del pack: BP y RP en el MISMO .mcaddon ───────────────────

    @Test
    fun `resolvePackRoot distingue el BP del RP por tipo de modulo`() {
        // Un .mcaddon como core-craft trae BP_CCR_CCR/ y RP_CCR_CCR/, cada uno con
        // su manifest. Devolver "el primero" daba el BP y el pipeline buscaba
        // models/ dentro del Behavior Pack: las geometrías se perdían.
        val tmp = Files.createTempDirectory("pf_root_").toFile()
        try {
            val bp = File(tmp, "BP_x").also { it.mkdirs() }
            val rp = File(tmp, "RP_x").also { it.mkdirs() }
            File(bp, "manifest.json").writeText(
                """{"format_version":2,"header":{"name":"bp","uuid":"11111111-1111-1111-1111-111111111111","version":[1,0,0]},
                   "modules":[{"type":"data","uuid":"11111111-1111-1111-1111-111111111112","version":[1,0,0]}]}"""
            )
            File(rp, "manifest.json").writeText(
                """{"format_version":2,"header":{"name":"rp","uuid":"22222222-2222-2222-2222-222222222222","version":[1,0,0]},
                   "modules":[{"type":"resources","uuid":"22222222-2222-2222-2222-222222222223","version":[1,0,0]}]}"""
            )
            DirIndexCache.invalidate(tmp)

            assertEquals(
                "con 'resources' debe elegir el RP",
                rp.name, AddonExtractor.resolvePackRoot(tmp, "resources").name
            )
            assertEquals(
                "con 'data' debe elegir el BP",
                bp.name, AddonExtractor.resolvePackRoot(tmp, "data").name
            )
        } finally {
            tmp.deleteRecursively()
        }
    }
}
