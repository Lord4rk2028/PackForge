package com.packforge.app.domain.engine

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * FASE 0 (biseccion), nivel pipeline.
 *
 * Fusiona SOLO Just-Health-Indicator. Con un unico addon no hay cruce
 * entre addons posible, asi que si el vector crece aqui, el corruptor
 * esta en el camino generico; si sale 3, el dano lo produce el merge
 * con los otros addons.
 */
class Fase0BisectPipelineTest {

    private val jhi = File("C:/Users/HP/Downloads/Script Router PF/Just-Health-Indicator.mcpack")

    private fun vectorEnPack(pack: File): Pair<Int, String> {
        ZipFile(pack).use { z ->
            val e = z.entries().toList().firstOrNull {
                !it.isDirectory && it.name.endsWith("animations/hpi_text.animation.json")
            } ?: return -1 to "no encontrado"
            val texto = z.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) }
            val bones = JSONObject(texto)
                .getJSONObject("animations")
                .getJSONObject("animation.hpi.display")
                .getJSONObject("bones")
            val arr = bones.getJSONObject("hpi_root").opt("position") as? JSONArray
            return (arr?.length() ?: -1) to e.name
        }
    }

    @Test
    fun `fusionar solo Just-Health-Indicator no rompe el vector`() {
        org.junit.Assume.assumeTrue("mcpack no disponible", jhi.isFile)
        val work = Files.createTempDirectory("pf_solo_").toFile()
        val r = runBlocking {
            PackForgeOrchestrator.mergeAddons(
                addonPaths = listOf(jhi.absolutePath),
                outputDir = work.absolutePath,
                addonNames = listOf("Just-Health-Indicator"),
                customName = "SoloJHI",
                addonIds = listOf("jhi")
            )
        }
        println("BISECT | success=" + r.success + " err=" + r.errorMessage)
        val pack = r.outputPath?.let { File(it) }
        org.junit.Assert.assertNotNull("debe salir un .mcaddon", pack)
        val (n, nombre) = vectorEnPack(pack!!)
        println("BISECT | SOLO JHI -> " + nombre + " hpi_root.position = " + n)
        assertEquals("con un solo addon el vector debe seguir en 3, dio " + n, 3, n)
    }
}
