package com.packforge.app.domain.engine

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * ⭐ FASE 5 — LA CAUSA RAÍZ DE LOS 41 MATERIALES "INEXISTENTES".
 *
 * Después de arreglar la herencia (`nombre:padre`) y ampliar la lista del motor,
 * el reporte real seguía mostrando 41 materiales rotos — y entre ellos estaban
 * `glow`, `cc_outline`, `cc_entity_enchant` y `outline_base_enchant`, que **sí
 * figuran en `RP_PackForge/materials/entity.material`**.
 *
 * El motivo no era ni el nombre ni la herencia: **el archivo nunca se leía**.
 * `readJson` usaba `JSONObject(texto)`, que es JSON estricto y rechaza los
 * comentarios `//` — pero Bedrock los admite en cualquier posición, y el
 * `entity.material` real de CoreCraft los trae:
 *
 * ```json
 * "cc_test:charged_creeper": {
 *     // "depthFunc": "Always",
 *     // ^ Uncomment if you want the outline to be
 *     //   rendered behind the block.
 * }
 * ```
 *
 * Al fallar el parseo, `readJson` devolvía `null` y **ninguno** de los 10
 * materiales del archivo se indexaba: todos se reportaban ausentes de golpe. Es
 * el mismo fallo que ya se documentó para `item_texture.json` de CoreCraft
 * (que abre con `// cooking`), pero aquí afectaba a los materiales.
 *
 * La lección: un `null` silencioso al leer un archivo del pack hace que TODO su
 * contenido se reporte como perdido, y el mensaje que ve el usuario culpa al
 * contenido en vez de al lector.
 */
class Fase5MaterialConComentariosTest {

    private lateinit var rp: File

    @Before
    fun setUp() {
        rp = Files.createTempDirectory("pf_material_comentarios").toFile()
        File(rp, "materials").mkdirs()
        File(rp, "entity").mkdirs()
    }

    @After
    fun tearDown() {
        rp.deleteRecursively()
    }

    private fun entidad(material: String, id: String = "cc:prueba") {
        File(rp, "entity/${id.replace(':', '_')}.json").writeText(
            """
            {
              "format_version": "1.10.0",
              "minecraft:client_entity": {
                "description": {
                  "identifier": "$id",
                  "materials": { "default": "$material" },
                  "geometry": { "default": "geometry.humanoid" },
                  "render_controllers": ["controller.render.default"]
                }
              }
            }
            """.trimIndent(),
            Charsets.UTF_8
        )
    }

    private fun rotos(): List<String> =
        MergeReferenceValidator.validate(null, rp, emptyList())
            .broken.filter { it.kind == "material" }.map { it.identifier }

    @Test
    fun `un material file con comentarios de linea se sigue leyendo`() {
        // Reproduce el entity.material REAL de CoreCraft.
        File(rp, "materials/entity.material").writeText(
            """
            {
              "materials": {
                "version": "1.0.0",
                "cc_test:charged_creeper": {
                    // "depthFunc": "Always",
                    // ^ Uncomment if you want the outline to be
                    //   rendered behind the block.
                },
                "glow:entity_alphablend": {
                    "+states": [ "Blending" ]
                },
                "cc_entity_enchant:entity_static": {
                    "+defines": [ "USE_UV_ANIM" ],
                    /* bloque comentado
                       con varias lineas */
                    "+states": [ "Blending" ]
                },
                "cc_outline:entity_static": {
                    "+states": ["InvertCulling"]
                }
              }
            }
            """.trimIndent(),
            Charsets.UTF_8
        )
        entidad("glow", "cc:a")
        entidad("cc_entity_enchant", "cc:b")
        entidad("cc_outline", "cc:c")

        val r = rotos()
        listOf("glow", "cc_entity_enchant", "cc_outline").forEach {
            assertFalse(
                "'$it' está en entity.material (que ahora sí se lee): no puede salir roto",
                r.contains(it)
            )
        }
    }

    @Test
    fun `el fallo real el archivo entero se pierde no solo una clave`() {
        // Sin el arreglo, CUALQUIER material del archivo salía roto porque
        // readJson devolvía null. Este test fija que basta UN comentario para
        // tirar todo el archivo si el parseo es estricto.
        File(rp, "materials/entity.material").writeText(
            """
            {
              // comentario al principio del archivo
              "materials": {
                "version": "1.0.0",
                "mat_uno:entity_static": {},
                "mat_dos:entity_static": {},
                "mat_tres:entity_static": {}
              }
            }
            """.trimIndent(),
            Charsets.UTF_8
        )
        entidad("mat_uno", "cc:1")
        entidad("mat_dos", "cc:2")
        entidad("mat_tres", "cc:3")

        val r = rotos()
        listOf("mat_uno", "mat_dos", "mat_tres").forEach {
            assertFalse("un solo comentario no puede invalidar los 3 materiales", r.contains(it))
        }
    }

    @Test
    fun `las comas finales que Bedrock tolera tampoco rompen la lectura`() {
        File(rp, "materials/entity.material").writeText(
            """
            {
              "materials": {
                "version": "1.0.0",
                "alpha:entity_static": { },
              },
            }
            """.trimIndent(),
            Charsets.UTF_8
        )
        entidad("alpha", "cc:alpha")

        assertFalse("una coma final no debe invalidar el archivo", rotos().contains("alpha"))
    }

    @Test
    fun `un material ausente sigue reportandose aunque el archivo tenga comentarios`() {
        File(rp, "materials/entity.material").writeText(
            """
            {
              "materials": {
                "version": "1.0.0",
                // solo este material está definido
                "definido:entity_static": {}
              }
            }
            """.trimIndent(),
            Charsets.UTF_8
        )
        entidad("definido", "cc:ok")
        entidad("no_definido", "cc:bad")

        val r = rotos()
        assertFalse("el definido está presente", r.contains("definido"))
        assertTrue("el ausente debe seguir detectándose", r.contains("no_definido"))
    }
}
