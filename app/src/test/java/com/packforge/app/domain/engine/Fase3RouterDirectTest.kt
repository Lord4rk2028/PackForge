package com.packforge.app.domain.engine

import org.junit.Test
import java.io.File
import java.nio.file.Files

/** FASE 3: llamar al router directamente y ver si lanza. */
class Fase3RouterDirectTest {

    private val b = "C:/Users/HP/Downloads/Script Router PF/"

    private fun extraer(archivo: File, prefijo: String): File {
        val dir = Files.createTempDirectory("pf_rp_").toFile()
        java.util.zip.ZipFile(archivo).use { z ->
            for (e in z.entries().toList()) {
                if (e.isDirectory) continue
                if (!e.name.startsWith(prefijo)) continue
                val rel = e.name.removePrefix(prefijo)
                val out = File(dir, rel)
                out.parentFile?.mkdirs()
                z.getInputStream(e).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            }
        }
        return dir
    }

    @Test
    fun `router directo con economy y gravestone`() {
        val econ = File(b + "SimpleEconomy-v2.10.0.mcaddon")
        val grav = File(b + "simple-gravestone 1.1.0.mcaddon")
        org.junit.Assume.assumeTrue("faltan addons", econ.isFile && grav.isFile)

        val econBp = extraer(econ, "EconomyBP/")
        val gravBp = extraer(grav, "simple-gravestone_BP/")
        println("ROUTER | econBp scripts=" + File(econBp, "scripts").isDirectory)
        println("ROUTER | gravBp scripts=" + File(gravBp, "scripts").isDirectory)

        val merged = Files.createTempDirectory("pf_merged_").toFile()
        val res = runCatching {
            ScriptRouterEngine.routeAndBuildBootstrapper(listOf(econBp, gravBp), merged)
        }
        res.onFailure { e ->
            println("ROUTER | EXCEPCION: " + e::class.java.name + ": " + e.message)
            e.stackTrace.take(6).forEach { println("ROUTER |   at " + it) }
        }
        res.onSuccess { r ->
            println("ROUTER | ok routed=" + r.routedCount + " entries=" + r.entryPoints)
            println("ROUTER | issues=" + r.issues)
            println("ROUTER | skipped=" + r.skippedAddons)
        }
        org.junit.Assert.assertTrue("el router no debe lanzar", res.isSuccess)
    }
}
