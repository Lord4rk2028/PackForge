package com.packforge.app.domain.engine

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * ⭐ FASE 5 — LA CLAVE DE UN MATERIAL NO ES SU NOMBRE REFERENCIABLE.
 *
 * En Bedrock, `materials/entity.material` declara cada material con la sintaxis
 * `nombre:padre` — es herencia del material del motor:
 *
 * ```json
 * { "materials": {
 *     "glow:entity_alphablend":              { "+states": ["Blending"] },
 *     "cc_entity_enchant:entity_static":     { "+defines": ["USE_UV_ANIM"] }
 * } }
 * ```
 *
 * Pero el `client_entity` lo referencia por **el nombre solo**:
 *
 * ```json
 * "materials": { "default": "glow" }
 * ```
 *
 * El validador indexaba la clave COMPLETA (`glow:entity_alphablend`) y luego
 * comparaba contra `glow`; como no coincidía, reportaba "material custom no
 * existe" aunque estuviera definido dos líneas más arriba. En el reporte real de
 * Better MC eso producía decenas de falsos `cc_outline`, `cc_entity_enchant` y
 * `glow`.
 *
 * Además, hay packs que guardan los materiales en `.json` en vez de `.material`:
 * el resource pack vanilla de Mojang NO tiene carpeta `materials/`, así que
 * ambas extensiones son legítimas.
 */
class Fase5MaterialHeredadoTest {

    private lateinit var rp: File

    @Before
    fun setUp() {
        rp = Files.createTempDirectory("pf_material_heredado").toFile()
        File(rp, "materials").mkdirs()
    }

    @After
    fun tearDown() {
        rp.deleteRecursively()
    }

    private fun validar(): MergeReferenceValidator.Report =
        MergeReferenceValidator.validate(bpDir = null, rpDir = rp, sourceDirs = emptyList())

    private fun escribirEntidad(identifier: String, material: String) {
        val dir = File(rp, "entity")
        dir.mkdirs()
        File(dir, "$identifier.json").writeText(
            """
            {
              "format_version": "1.10.0",
              "minecraft:client_entity": {
                "description": {
                  "identifier": "cc:prueba",
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

    private fun escribirMaterial(contenido: String, nombre: String = "entity.material") {
        File(rp, "materials/$nombre").writeText(contenido, Charsets.UTF_8)
    }

    private fun rotosDeMaterial(): List<String> =
        validar().broken.filter { it.kind == "material" }.map { it.identifier }

    @Test
    fun `un material declarado como nombre padre y referenciado por el nombre NO es rotura`() {
        escribirMaterial(
            """
            { "materials": {
                "version": "1.0.0",
                "glow:entity_alphablend": { "+states": ["Blending"] }
            } }
            """.trimIndent()
        )
        escribirEntidad("a", "glow")

        assertFalse(
            "'glow' está definido en entity.material: no puede salir como referencia rota",
            rotosDeMaterial().contains("glow")
        )
    }

    @Test
    fun `los materiales con namespace del pack real dejan de reportarse`() {
        escribirMaterial(
            """
            { "materials": {
                "version": "1.0.0",
                "cc_outline:entity_static": { "+states": ["InvertCulling"] },
                "cc_entity_enchant:entity_static": { "+defines": ["USE_UV_ANIM"] },
                "glow:entity_alphablend": { "+states": ["Blending"] }
            } }
            """.trimIndent()
        )
        List(3) { i -> escribirEntidad("e$i", "glow") }

        val rotos = rotosDeMaterial()
        listOf("glow", "cc_outline", "cc_entity_enchant").forEach {
            assertFalse("no debería estar roto: $it", rotos.contains(it))
        }
    }

    @Test
    fun `un material realmente ausente sigue reportandose`() {
        escribirMaterial("""{ "materials": { "version": "1.0.0", "otro:x": {} } }""")
        escribirEntidad("b", "material_inventado")

        assertTrue(
            "un material que no está definido debe seguir detectándose",
            rotosDeMaterial().contains("material_inventado")
        )
    }

    @Test
    fun `los materiales en un archivo json tambien cuentan`() {
        // El resource pack vanilla no tiene materials/, pero hay packs que sí
        // usan .json; ignorarlos dejaría sus materiales sin indexar.
        escribirMaterial(
            """{ "materials": { "version": "1.0.0", "mi_mat:entity_static": {} } }""",
            nombre = "custom.json"
        )
        escribirEntidad("c", "mi_mat")

        assertFalse(
            "un materials/*.json debe indexarse igual que un .material",
            rotosDeMaterial().contains("mi_mat")
        )
    }

    /**
     * ⭐ FASE 5 — `material.<slot>` en un render controller NO es un material.
     *
     * El `render_controllers/7911eazu.json` real de CoreCraft declara:
     *
     * ```json
     * "materials": [{ "*": "material.outline" }]
     * ```
     *
     * Es el accesor Molang al mapa `materials` de la PROPIA entidad — el mismo
     * alias que `Material.default` (mayúscula, ya filtrado), pero escrito en
     * minúscula. El validador lo tomaba por un identificador de material y lo
     * reportaba como inexistente (`material 'material.outline'`), igual con
     * `material.glow`.
     */
    @Test
    fun `el accesor molang material punto slot no se valida como material`() {
        File(rp, "render_controllers").mkdirs()
        File(rp, "render_controllers/rc.json").writeText(
            """
            {
              "format_version": "1.20.30",
              "render_controllers": {
                "controller.render.cc.item.outline": {
                  "geometry": "Geometry.default",
                  "materials": [{ "*": "material.outline" }],
                  "textures": ["texture.outline"]
                },
                "controller.render.cc.item.glow": {
                  "geometry": "Geometry.default",
                  "materials": [{ "*": "material.glow" }],
                  "textures": ["texture.glow"]
                }
              }
            }
            """.trimIndent(),
            Charsets.UTF_8
        )

        val rotos = rotosDeMaterial()
        assertFalse(
            "'material.outline' es un alias Molang al mapa de la entidad, no un material",
            rotos.contains("material.outline")
        )
        assertFalse(
            "'material.glow' es un alias Molang al mapa de la entidad, no un material",
            rotos.contains("material.glow")
        )
    }
}
