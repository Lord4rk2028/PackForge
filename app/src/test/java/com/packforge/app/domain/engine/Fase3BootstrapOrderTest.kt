package com.packforge.app.domain.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * FASE 3: el main.js usa imports ESTATICOS (el patron documentado por Mojang),
 * y el addon base va ANTES que los addons que dependen de el.
 */
class Fase3BootstrapOrderTest {

    private val b = "C:/Users/HP/Downloads/Script Router PF/"

    @Test
    fun `main js usa imports estaticos y ordena base antes que sus DLC`() {
        val addons = listOf(
            File(b + "Just-Health-Indicator.mcpack"),
            File(b + "simple-gravestone 1.1.0.mcaddon"),
            File(b + "SimpleEconomy-v2.10.0.mcaddon")
        )
        org.junit.Assume.assumeTrue("faltan addons", addons.all { it.isFile })
        val work = Files.createTempDirectory("pf_boot_").toFile()
        val r = runBlocking {
            PackForgeOrchestrator.mergeAddons(
                addonPaths = addons.map { it.absolutePath },
                outputDir = work.absolutePath,
                addonNames = listOf("JHI", "Grav", "Econ"),
                customName = "OrdenBoot",
                addonIds = listOf("jhi", "grav", "econ")
            )
        }
        val pack = r.outputPath?.let { File(it) }
        org.junit.Assert.assertNotNull("debe salir mcaddon: " + r.errorMessage, pack)

        val main = ZipFile(pack!!).use { z ->
            val e = z.entries().toList().firstOrNull { it.name.endsWith("/scripts/main.js") }
            org.junit.Assert.assertNotNull("debe existir scripts/main.js", e)
            z.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) }
        }
        main.lines().forEach { if (it.isNotBlank()) println("BOOT | " + it) }

        // imports estaticos: el patron documentado por Mojang para multipack
        assertTrue("debe usar import estatico: " + main, main.contains("import \"./modules/"))
        assertFalse("no debe usar import() dinamico", main.contains("import("))
        assertFalse("no debe quedar la cola de promesas", main.contains("__pfPendiente"))

        // orden: el base de economy antes que sus DLC (si estos están).
        // Los ids de modulo varian (economy_2 en el mcpack plano,
        // nested_simpleeconomy_2 + ..._crimedlc_3 + ..._financedlc_4 cuando el
        // addon trae los DLC anidados), asi que se localizan por subcadena.
        val posBase = main.indexOf("simpleeconomy_")
            .let { if (it in 0..main.length) it else main.indexOf("economy_") }
        val posCrime = main.indexOf("crimedlc")
        val posFin = main.indexOf("financedlc")
        assertTrue("debe aparecer el base de economy: " + main, posBase > 0)
        if (posCrime > 0) assertTrue("base antes que crimedlc", posBase < posCrime)
        if (posFin > 0) assertTrue("base antes que financedlc", posBase < posFin)
    }
}
