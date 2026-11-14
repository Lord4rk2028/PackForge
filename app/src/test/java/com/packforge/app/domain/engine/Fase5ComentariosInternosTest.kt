package com.packforge.app.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⭐ FASE 5 — COMENTARIOS INTERNOS (pérdida de datos real)
 *
 * Bedrock acepta comentarios de linea y de bloque dentro de sus JSON. El parser
 * tolerante solo los recortaba al PRINCIPIO del archivo, así que un comentario
 * INTERNO hacía fallar el parseo. Consecuencia medida con core-craft:
 *
 *   1. El decifrador daba el archivo por "roto sin contraparte".
 *   2. Lo renombraba a `.bak` para que Bedrock no se quejara.
 *   3. El empaquetado EXCLUYE los `.bak` → el archivo salía del .mcaddon.
 *
 * Se perdieron 69 archivos REALES (59 geometrías en models/entity, 9 entidades
 * —`cc:siren`, `cc:hermit`…— y un render_controllers con 23 controladores,
 * incluidos `controller.render.cc.common` e `controller.render.cc.ignore_lighting`
 * que luego el validador reportaba como "no existe").
 */
class Fase5ComentariosInternosTest {

    private val conComentarioLinea = """
        {
          "format_version": "1.10.0",
          "minecraft:client_entity": {
            "description": {
              "identifier": "cc:siren",
              // plane only rolls in the air
              "materials": { "default": "entity_alphatest" }
            }
          }
        }
    """.trimIndent()

    private val conComentarioBloque = """
        {
          "format_version": "1.20.30",
          /* cabecera generada por el exportador
             que ocupa varias lineas */
          "render_controllers": {
            "controller.render.cc.common": { "geometry": "Geometry.default" }
          }
        }
    """.trimIndent()

    private val conComentarioAlFinal = """
        {
          "format_version": "1.10.0",
          "minecraft:client_entity": { "description": { "identifier": "cc:x" } }
        }
        // nota final del autor
    """.trimIndent()

    @Test
    fun `un comentario de linea INTERNO no rompe el parseo`() {
        val json = LenientJson.parseObject(conComentarioLinea)
        assertNotNull(
            "El comentario interno NO debe invalidar el JSON: si falla, el archivo " +
                "acaba en .bak y desaparece del .mcaddon",
            json
        )
        assertEquals(
            "cc:siren",
            json!!.getJSONObject("minecraft:client_entity")
                .getJSONObject("description").getString("identifier")
        )
    }

    @Test
    fun `un comentario de bloque INTERNO no rompe el parseo`() {
        val json = LenientJson.parseObject(conComentarioBloque)
        assertNotNull("El comentario de bloque interno debe tolerarse", json)
        assertTrue(
            json!!.getJSONObject("render_controllers")
                .has("controller.render.cc.common")
        )
    }

    @Test
    fun `un comentario al final tampoco rompe`() {
        assertNotNull(LenientJson.parseObject(conComentarioAlFinal))
    }

    @Test
    fun `siguen tolerandose los comentarios iniciales`() {
        // Cabecera `//bridge-file-version` que escribe Bridge.
        val bridge = """
            //bridge-file-version: #0
            { "format_version": "1.10.0", "minecraft:client_entity": { "description": {} } }
        """.trimIndent()
        assertNotNull(LenientJson.parseObject(bridge))
    }

    @Test
    fun `un JSON genuinamente roto sigue devolviendo null`() {
        // El arreglo NO debe volver permisivo el parser: si de verdad está roto,
        // tiene que seguir detectándose (y por tanto neutralizándose).
        //
        // Nota: la coma final ("{ \"a\": 1, }") SÍ se acepta a propósito — es una
        // liberalidad deliberada del parser para packs reales, no un fallo.
        assertNull(LenientJson.parseObject("""{ "a": }"""))
        assertNull(LenientJson.parseObject("""no es json en absoluto"""))
        assertNull(LenientJson.parseObject("""{ "a": 1 """))
    }

    @Test
    fun `las barras dentro de un string no se confunden con un comentario`() {
        // "textures/entity/x" lleva '/' y no debe tratarse como inicio de comentario.
        val json = LenientJson.parseObject(
            """{ "t": "textures/entity/siren", "u": "https://ejemplo/x" }"""
        )
        assertNotNull(json)
        assertEquals("textures/entity/siren", json!!.getString("t"))
        assertEquals("https://ejemplo/x", json.getString("u"))
    }
}
