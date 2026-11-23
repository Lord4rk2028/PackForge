package com.packforge.app.domain.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * FASE 0 (biseccion): quien corrompe los vectores de animacion.
 *
 * Llama a mergeAnimations con las animaciones REALES de
 * Just-Health-Indicator, 1, 2 y 3 veces, y mide el vector.
 * La ley de crecimiento dice que paso lo fabrica.
 */
class Fase0BisectAnimationsTest {

    private val src = File("C:/Users/HP/Downloads/Script Router PF/Just-Health-Indicator.mcpack")

    private fun rpDeJhi(): File {
        val dir = Files.createTempDirectory("pf_jhi_rp_").toFile()
        java.util.zip.ZipFile(src).use { z ->
            for (e in z.entries().toList()) {
                if (e.isDirectory) continue
                if (!e.name.startsWith("Just Health Indicator/")) continue
                val rel = e.name.removePrefix("Just Health Indicator/")
                val out = File(dir, rel)
                out.parentFile?.mkdirs()
                z.getInputStream(e).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            }
        }
        return dir
    }

    private fun lenPos(dest: File, bone: String): Int {
        val f = File(dest, "animations/hpi_text.animation.json")
        val bones = JSONObject(f.readText(Charsets.UTF_8))
            .getJSONObject("animations")
            .getJSONObject("animation.hpi.display")
            .getJSONObject("bones")
        val arr = bones.getJSONObject(bone).opt("position") as? JSONArray
        return arr?.length() ?: -1
    }

    @Test
    fun `una sola pasada deja el vector en tres`() {
        org.junit.Assume.assumeTrue("mcpack no disponible", src.isFile)
        val rp = rpDeJhi()
        val dest = Files.createTempDirectory("pf_dest1_").toFile()
        BedrockCriticalFilesMerger.mergeAnimations(listOf(rp), dest)
        val n = lenPos(dest, "hpi_root")
        println("BISECT | 1 pasada -> hpi_root.position = " + n)
        assertEquals("una pasada debe dejar 3, dejo " + n, 3, n)
    }

    @Test
    fun `dos pasadas del mismo addon dejan el vector en tres`() {
        org.junit.Assume.assumeTrue("mcpack no disponible", src.isFile)
        val rp = rpDeJhi()
        val dest = Files.createTempDirectory("pf_dest2_").toFile()
        BedrockCriticalFilesMerger.mergeAnimations(listOf(rp, rp), dest)
        val n = lenPos(dest, "hpi_root")
        println("BISECT | 2 pasadas -> hpi_root.position = " + n)
        assertEquals("el mismo addon dos veces debe dar 3, dio " + n, 3, n)
    }

    @Test
    fun `tres pasadas del mismo addon dejan el vector en tres`() {
        org.junit.Assume.assumeTrue("mcpack no disponible", src.isFile)
        val rp = rpDeJhi()
        val dest = Files.createTempDirectory("pf_dest3_").toFile()
        BedrockCriticalFilesMerger.mergeAnimations(listOf(rp, rp, rp), dest)
        val n = lenPos(dest, "hpi_root")
        println("BISECT | 3 pasadas -> hpi_root.position = " + n)
        assertEquals("el mismo addon tres veces debe dar 3, dio " + n, 3, n)
    }
}
