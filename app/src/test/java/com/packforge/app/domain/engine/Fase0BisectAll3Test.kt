package com.packforge.app.domain.engine

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * FASE 0 - INVARIANTE DE SALIDA DEL PIPELINE (antirregresion).
 *
 * Fusiona los 3 addons reales con el codigo ACTUAL y exige que NINGUN
 * vector de animacion deje de medir 3. Bedrock descarta un bloque cuyo
 * position/rotation/scale no son [x, y, z], y entonces el hueso se queda
 * en su pivote: eso fue el HUD del mob a la altura de las patas.
 *
 * El corruptor historico fue concatArrays: al fusionar un vector consigo
 * mismo conservaba TODOS los numeros y deduplicaba los strings, asi que
 * [0,"m",0] daba 5, ["a","b",0] daba 4 y [0,0,0] daba 6.
 * Esta prueba fija que no vuelva.
 */
class Fase0BisectAll3Test {

    private val b = "C:/Users/HP/Downloads/Script Router PF/"

    private val VECTORES = setOf("position", "rotation", "scale", "pivot", "origin", "size")

    private fun revisar(obj: Any, ruta: String, out: MutableList<String>) {
        when (obj) {
            is JSONObject -> obj.keys().forEach { k ->
                revisar(obj.opt(k) ?: return@forEach, ruta + "." + k, out)
            }
            is JSONArray -> {
                val ultimo = ruta.substringAfterLast(".")
                if (ultimo in VECTORES && obj.length() != 3) {
                    out.add(ruta + " = " + obj.length())
                }
                for (i in 0 until obj.length()) {
                    revisar(obj.opt(i) ?: continue, ruta + "[" + i + "]", out)
                }
            }
        }
    }

    @Test
    fun `fusion de los tres addons no corrompe ningun vector`() {
        val addons = listOf(
            File(b + "Just-Health-Indicator.mcpack"),
            File(b + "simple-gravestone 1.1.0.mcaddon"),
            File(b + "SimpleEconomy-v2.10.0.mcaddon")
        )
        org.junit.Assume.assumeTrue("faltan addons reales", addons.all { it.isFile })

        val work = Files.createTempDirectory("pf_all3_").toFile()
        val r = runBlocking {
            PackForgeOrchestrator.mergeAddons(
                addonPaths = addons.map { it.absolutePath },
                outputDir = work.absolutePath,
                addonNames = listOf("JHI", "Gravestone", "Economy"),
                customName = "InvarianteVec",
                addonIds = listOf("jhi", "grav", "econ")
            )
        }
        val pack = r.outputPath?.let { File(it) }
        org.junit.Assert.assertNotNull("debe salir mcaddon: " + r.errorMessage, pack)

        val rotos = mutableListOf<String>()
        ZipFile(pack!!).use { z ->
            for (e in z.entries().toList()) {
                if (e.isDirectory) continue
                if (!e.name.endsWith(".json")) continue
                if (!e.name.contains("animation")) continue
                val t = z.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) }
                val raiz = runCatching { JSONObject(t) }.getOrNull() ?: continue
                revisar(raiz, e.name, rotos)
            }
        }
        println("INVARIANTE | vectores invalidos = " + rotos.size)
        rotos.take(20).forEach { println("INVARIANTE |   " + it) }
        assertTrue("Vectores no miden 3 (Bedrock los descarta): " + rotos.take(10), rotos.isEmpty())

        ZipFile(pack).use { z ->
            val e = z.entries().toList().firstOrNull {
                !it.isDirectory && it.name.endsWith("animations/hpi_text.animation.json")
            }
            org.junit.Assert.assertNotNull("debe estar la animacion del HUD", e)
            val t = z.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) }
            val bones = JSONObject(t)
                .getJSONObject("animations")
                .getJSONObject("animation.hpi.display")
                .getJSONObject("bones")
            val pos = bones.getJSONObject("hpi_root").opt("position") as? JSONArray
            println("INVARIANTE | hpi_root.position = " + (pos?.length() ?: -1))
            assertEquals("hpi_root.position debe ser 3", 3, pos?.length() ?: -1)
        }
    }
}
