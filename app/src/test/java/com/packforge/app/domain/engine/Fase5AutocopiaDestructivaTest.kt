package com.packforge.app.domain.engine

import com.packforge.app.util.FileUtils
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * ⭐ FASE 5 — AUTOCOPIA DESTRUCTIVA (pérdida de datos real)
 *
 * El bug más grave medido en toda la investigación. Cadena completa:
 *
 *  1. `PackForgeValidator` recorre los JSON de la carpeta `entity/`.
 *  2. Por cada entidad pide `identifierIndex.resolve(geometryId)`.
 *  3. La geometría YA está en `models/entity`, así que el índice devuelve ESE
 *     MISMO archivo.
 *  4. El código hacía `geoFile.copyTo(File(modelsEntityDir, geoFile.name),
 *     overwrite = true)`: origen == destino.
 *  5. `File.copyTo(overwrite = true)` BORRA el destino antes de copiar. Al
 *     borrarse a sí mismo, lanza NoSuchFileException.
 *  6. El `catch` del bucle se traga la excepción: pérdida SILENCIOSA.
 *
 * Traza real del pipeline (core-craft + Sift + MTA):
 *   tras healer.repair: 155 modelos de entidad
 *   tras validador:      83 modelos de entidad   → 72 archivos aniquilados
 *
 * Consecuencia en el .mcaddon entregado: 25 identificadores REALES ausentes
 * (`geometry.cc.pharaoh`, `geometry.cc.soldier`, `geometry.cc.meerkat`,
 * `geometry.cc.deer`, `geometry.cc.villager_baby`…). El reporte los listaba
 * como "no existe en models/", ¡porque la propia app los acababa de borrar!
 */
class Fase5AutocopiaDestructivaTest {

    private fun tempDir(): File =
        Files.createTempDirectory("pf_autocopia_").toFile().also { it.mkdirs() }

    @Test
    fun `fastCopy no destruye el archivo cuando origen y destino son el mismo`() {
        val dir = tempDir()
        val f = File(dir, "geometria.json")
        val contenido = """{"format_version":"1.12.0","minecraft:geometry":[{"description":{"identifier":"geometry.cc.soldier"}}]}"""
        f.writeText(contenido)

        FileUtils.fastCopy(f, f)

        assertTrue("el archivo no debe desaparecer", f.exists())
        assertEquals(
            "el contenido debe quedar intacto (antes quedaba VACÍO: se truncaba)",
            contenido, f.readText()
        )
    }

    @Test
    fun `fastCopy sigue copiando cuando origen y destino son distintos`() {
        val dir = tempDir()
        val src = File(dir, "origen.json")
        val dest = File(dir, "sub/destino.json")
        src.writeText("""{"a":1}""")

        FileUtils.fastCopy(src, dest)

        assertEquals("""{"a":1}""", dest.readText())
    }

    @Test
    fun `fastCopy reconoce el mismo archivo por ruta relativa`() {
        val dir = tempDir()
        val f = File(dir, "x.json")
        f.writeText("""{"clave":"valor"}""")

        // La misma ruta expresada de otra forma NO debe saltarse la guarda por error.
        val alias = File(dir.path + File.separator + "." + File.separator + "x.json")
        FileUtils.fastCopy(f, alias)

        assertEquals("""{"clave":"valor"}""", f.readText())
    }

    @Test
    fun `fastCopy sobrescribe correctamente un destino preexistente distinto`() {
        val dir = tempDir()
        val src = File(dir, "bueno.json")
        val dest = File(dir, "viejo.json")
        src.writeText("""{"nuevo":true}""")
        dest.writeText("""{"viejo":true}""")

        FileUtils.fastCopy(src, dest)

        assertEquals("""{"nuevo":true}""", dest.readText())
        assertEquals("""{"nuevo":true}""", src.readText())
    }

    /**
     * Reproduce el escenario EXACTO del validador sobre un pack mínimo: una
     * entidad cuya geometría ya vive en la carpeta de modelos de entidad. Antes
     * de la corrección, la geometría quedaba borrada.
     */
    @Test
    fun `validar una entidad cuya geometria ya esta en su sitio no la borra`() {
        val rp = tempDir()
        File(rp, "entity").mkdirs()
        File(rp, "models/entity").mkdirs()

        val geometria = File(rp, "models/entity/2jm1s73o.json")
        val geoTexto = JSONObject()
            .put("format_version", "1.12.0")
            .put(
                "minecraft:geometry",
                org.json.JSONArray().put(
                    JSONObject().put(
                        "description",
                        JSONObject().put("identifier", "geometry.cc.meerkat")
                    )
                )
            )
            .toString()
        geometria.writeText(geoTexto)

        File(rp, "entity/mob.json").writeText(
            JSONObject()
                .put("format_version", "1.10.0")
                .put(
                    "minecraft:client_entity",
                    JSONObject().put(
                        "description",
                        JSONObject()
                            .put("identifier", "cc:meerkat")
                            .put("geometry", JSONObject().put("default", "geometry.cc.meerkat"))
                    )
                )
                .toString()
        )

        val index = BedrockIdentifierIndex.build(
            bpFiles = emptyList(),
            rpFiles = listOf(rp)
        )
        val resuelto = index.resolve("geometry.cc.meerkat")
        assertTrue("el indice debe encontrar la geometria", resuelto != null)

        // El destino que usaba el validador: models/entity/<nombre>.
        val dest = File(rp, "models/entity/${resuelto!!.name}")

        // ANTES: geoFile.copyTo(dest, overwrite = true) → borraba el archivo.
        FileUtils.fastCopy(resuelto, dest)

        assertTrue(
            "la geometria NO debe desaparecer tras la validacion",
            geometria.exists()
        )
        assertTrue(
            "la geometria no debe quedar vacia",
            geometria.readText().contains("geometry.cc.meerkat")
        )
    }
}
