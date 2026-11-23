package com.packforge.app.domain.engine

import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * Medición del coste de parseo del índice (TICKET-CORE-009).
 *
 * No es una regresión: sirve para decidir con datos si merece la pena atacar
 * `LenientJson`. Imprime tiempos y no falla por lentitud.
 */
class DiagnosticoCosteParseoTest {

    private fun medir(etiqueta: String, veces: Int, bloque: () -> Unit) {
        // Calentamiento para no medir la compilación JIT.
        repeat(3) { bloque() }
        val t0 = System.nanoTime()
        repeat(veces) { bloque() }
        val ms = (System.nanoTime() - t0) / 1_000_000.0 / veces
        println("PARSEO | %-34s %8.2f ms/archivo".format(etiqueta, ms))
    }

    @Test
    fun `coste de LenientJson frente a org json`() {
        val addon = TestFixtures.addon("OriginsBE-v1.5.6.mcaddon")
        org.junit.Assume.assumeTrue(TestFixtures.describe("OriginsBE-v1.5.6.mcaddon"), addon != null)

        // Extraer un .geo.json real del addon.
        val dir = java.nio.file.Files.createTempDirectory("pf_bench_").toFile()
        var objetivo: File? = null
        java.util.zip.ZipFile(addon!!).use { z ->
            for (e in z.entries().toList()) {
                if (e.isDirectory || !e.name.endsWith(".geo.json")) continue
                val out = File(dir, File(e.name).name)
                z.getInputStream(e).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                objetivo = out
                break
            }
        }
        val file = objetivo
        org.junit.Assume.assumeTrue("sin .geo.json en el addon", file != null)

        val texto = file!!.readText()
        println("PARSEO | muestra=${file.name} bytes=${texto.length}")

        medir("LenientJson.parseObject", 10) {
            LenientJson.parseObject(texto)
        }
        medir("LenientJson + cleanJsonObject", 10) {
            LenientJson.parseObject(texto)?.let { JsonDeepMerger.cleanJsonObject(it) }
        }
        medir("org.json JSONObject(texto)", 10) {
            JSONObject(texto)
        }
        medir("hash MD5 del texto", 10) {
            java.security.MessageDigest.getInstance("MD5")
                .digest(texto.toByteArray(Charsets.UTF_8))
        }
    }
}