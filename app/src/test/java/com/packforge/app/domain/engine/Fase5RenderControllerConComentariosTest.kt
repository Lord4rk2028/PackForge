package com.packforge.app.domain.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * ⭐ FASE 5 — REGRESIÓN: 64 "render controller ausente" que el reporte original
 * NO tenía.
 *
 * El `render_controllers/tznvrmdz.json` real (11.565 bytes) trae comentarios `//`
 * y es justamente el que define `controller.render.cc.common` y
 * `controller.render.cc.ignore_lighting`. La validación del ZIP final lo leía con
 * `JSONObject` (estricto), el parseo fallaba, `runCatching` devolvía `null` y sus
 * 64 render controllers no entraban en el índice `animRcIds`. Después, cada
 * entidad que los referenciaba se reportaba como "render controller ausente".
 *
 * Es el mismo fallo de la causa raíz de los materiales (`MergeReferenceValidator`),
 * en un segundo módulo: un `null` silencioso al leer un archivo hace que TODO su
 * contenido se reporte como perdido.
 *
 * Este test reproduce el caso completo: un ZIP con un render controller que trae
 * comentarios, definido y referenciado, no debe producir ningún aviso.
 */
class Fase5RenderControllerConComentariosTest {

    @Test
    fun `un render controller con comentarios no se reporta como ausente`() {
        val dir = Files.createTempDirectory("pf_rc_comentarios").toFile()
        val zipFile = File(dir, "pack.mcaddon")

        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            fun escribir(nombre: String, contenido: String) {
                zip.putNextEntry(ZipEntry(nombre))
                zip.write(contenido.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            // El render controller, CON comentarios como el archivo real.
            escribir(
                "RP/render_controllers/rc.json",
                """
                {
                  "format_version": "1.10.0",
                  "render_controllers": {
                    // el archivo real de CoreCraft trae este comentario
                    "controller.render.cc.common": {
                      "geometry": "Geometry.default"
                    }
                    /* y también comentarios de bloque */
                  }
                }
                """.trimIndent()
            )

            // La geometría que usa.
            escribir(
                "RP/models/entity/test.geo.json",
                """
                {
                  "format_version": "1.12.0",
                  "minecraft:geometry": [
                    { "description": { "identifier": "geometry.test" } }
                  ]
                }
                """.trimIndent()
            )

            // La entidad que lo referencia.
            escribir(
                "RP/entity/test.entity.json",
                """
                {
                  "format_version": "1.10.0",
                  "minecraft:client_entity": {
                    "description": {
                      "identifier": "cc:test",
                      "geometry": { "default": "geometry.test" },
                      "render_controllers": ["controller.render.cc.common"]
                    }
                  }
                }
                """.trimIndent()
            )
        }

        val errores = EntityDependencyResolver.validateMerge(zipFile.absolutePath)

        assertTrue(
            "el render controller está definido (con comentarios) y referenciado: " +
                "no puede salir como ausente. Errores: $errores",
            errores.none { it.contains("controller.render.cc.common") }
        )
    }

    @Test
    fun `un render controller realmente ausente sigue detectandose`() {
        val dir = Files.createTempDirectory("pf_rc_ausente").toFile()
        val zipFile = File(dir, "pack.mcaddon")

        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            fun escribir(nombre: String, contenido: String) {
                zip.putNextEntry(ZipEntry(nombre))
                zip.write(contenido.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            escribir(
                "RP/models/entity/test.geo.json",
                """
                {
                  "format_version": "1.12.0",
                  "minecraft:geometry": [
                    { "description": { "identifier": "geometry.test" } }
                  ]
                }
                """.trimIndent()
            )

            // La entidad referencia un RC que NADIE define.
            escribir(
                "RP/entity/test.entity.json",
                """
                {
                  "format_version": "1.10.0",
                  "minecraft:client_entity": {
                    "description": {
                      "identifier": "cc:test",
                      "geometry": { "default": "geometry.test" },
                      "render_controllers": ["controller.render.cc.inexistente"]
                    }
                  }
                }
                """.trimIndent()
            )
        }

        val errores = EntityDependencyResolver.validateMerge(zipFile.absolutePath)

        assertTrue(
            "un render controller que nadie define debe seguir detectándose",
            errores.any { it.contains("controller.render.cc.inexistente") }
        )
    }
}
