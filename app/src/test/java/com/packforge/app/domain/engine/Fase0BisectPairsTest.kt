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
 * FASE 0 (biseccion) por PAREJAS.
 *
 * Con un solo addon el vector queda en 3, asi que el dano lo produce el
 * cruce. Estas dos pruebas dicen CUAL de los otros dos addons lo dispara.
 */
class Fase0BisectPairsTest {

    private val base = "C:/Users/HP/Downloads/Script Router PF/"
    private val jhi = File(base + "Just-Health-Indicator.mcpack")
    private val grav = File(base + "simple-gravestone 1.1.0.mcaddon")
    private val econ = File(base + "SimpleEconomy-v2.10.0.mcaddon")

    private fun medir(pack: File): Int {
        ZipFile(pack).use { z ->
            val e = z.entries().toList().firstOrNull {
                !it.isDirectory && it.name.endsWith("animations/hpi_text.animation.json")
            } ?: return -1
            val t = z.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) }
            val arr = JSONObject(t)
                .getJSONObject("animations")
                .getJSONObject("animation.hpi.display")
                .getJSONObject("bones")
                .getJSONObject("hpi_root").opt("position") as? JSONArray
            return arr?.length() ?: -1
        }
    }

    private fun fusionar(addons: List<File>, nombre: String): Int {
        val work = Files.createTempDirectory("pf_pair_").toFile()
        val r = runBlocking {
            PackForgeOrchestrator.mergeAddons(
                addonPaths = addons.map { it.absolutePath },
                outputDir = work.absolutePath,
                addonNames = addons.map { it.nameWithoutExtension },
                customName = nombre,
                addonIds = addons.mapIndexed { i, _ -> "a" + i }
            )
        }
        val pack = r.outputPath?.let { File(it) } ?: return -2
        return medir(pack)
    }

    @Test
    fun `JHI mas gravestone`() {
        org.junit.Assume.assumeTrue("faltan addons", jhi.isFile && grav.isFile)
        val n = fusionar(listOf(jhi, grav), "JHI_Grav")
        println("BISECT | JHI+gravestone -> hpi_root.position = " + n)
        assertEquals("JHI+gravestone debe seguir en 3, dio " + n, 3, n)
    }

    @Test
    fun `JHI mas economy`() {
        org.junit.Assume.assumeTrue("faltan addons", jhi.isFile && econ.isFile)
        val n = fusionar(listOf(jhi, econ), "JHI_Econ")
        println("BISECT | JHI+economy -> hpi_root.position = " + n)
        assertEquals("JHI+economy debe seguir en 3, dio " + n, 3, n)
    }
}
