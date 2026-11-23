package com.packforge.app.domain.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * FASE 0 - AISLAR EL CORRUPTOR DE VECTORES.
 *
 * deepMerge NO es el corruptor (los tres casos pasan), asi que la
 * corrupcion entra por otro sitio del pipeline. Este segundo bloque
 * reproduce la FUSION REAL de los 3 addons y afirma sobre los bytes
 * que salen: si un vector de animacion no mide 3, falla aqui y senalo
 * el fichero, en vez de descubrirlo en el movil.
 */
class Fase0VectorPreservationTest {

    @Before
    fun reset() {
        JsonDeepMerger.clearConflicts()
        JsonDeepMerger.clearPriorityContext()
    }

    private fun json(text: String): JSONObject = JSONObject(text)

    private fun contar(value: Any?): Int = (value as? JSONArray)?.length() ?: -1

    private fun autoFusionar(text: String, winner: Int = 0): JSONObject {
        JsonDeepMerger.setMergeContext("addon_a", "addon_b", "prueba.json")
        JsonDeepMerger.setPriorityContext(winner, 0)
        return JsonDeepMerger.deepMerge(json(text), json(text))
    }

    @Test
    fun `position de tres no crece al auto fusionar`() {
        val hueso = """
            {
              "position": [0, "(variable.hpi_y ?? 0.0)", 0],
              "rotation": ["query.camera_rotation(0)", "query.body_y_rotation", 0],
              "scale": [1.5, 1.5, 1.5]
            }
        """.trimIndent()

        val r = autoFusionar(hueso)
        assertEquals("position debe ser 3: " + r.get("position"), 3, contar(r.get("position")))
        assertEquals("rotation debe ser 3: " + r.get("rotation"), 3, contar(r.get("rotation")))
        assertEquals("scale debe ser 3: " + r.get("scale"), 3, contar(r.get("scale")))
    }

    @Test
    fun `scale de ceros no crece al auto fusionar`() {
        val hueso = """
            {"hpi_sl": {"scale": [0, 0, 0]}}
        """.trimIndent()

        val r = autoFusionar(hueso)
        val s = r.getJSONObject("hpi_sl").get("scale")
        assertEquals("scale debe ser 3: " + s, 3, contar(s))
    }

    @Test
    fun `array de identidades si se une`() {
        val a = """{"conditions": ["q.life_time > 2", "q.is_on_ground"]}"""
        val b = """{"conditions": ["q.life_time > 2", "q.is_riding"]}"""
        JsonDeepMerger.setMergeContext("addon_a", "addon_b", "prueba.json")
        val r = JsonDeepMerger.deepMerge(json(a), json(b))
        val c = r.getJSONArray("conditions")
        assertEquals("las identidades deben unirse sin duplicar: " + c, 3, c.length())
    }
}
