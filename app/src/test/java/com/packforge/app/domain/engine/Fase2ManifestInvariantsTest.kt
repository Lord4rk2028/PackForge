package com.packforge.app.domain.engine

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * FASE 2 - INVARIANTES DE MANIFIESTO Y SCRIPTS (verificables sin el juego).
 *
 * Sintoma 2 y 3: gravestone no genera tumba y economy no abre ninguna UI.
 * Ambos packs SE CARGAN (los bloques aparecen) pero su script nunca corre.
 *
 * La causa candidata es que las dependencies por UUID citan headers que la
 * fusion regenero. Bedrock exige que TODA dependencia por UUID exista
 * dentro del .mcaddon; si no, el pack queda sin inicializar.
 *
 * Este test lo comprueba sobre el pack real:
 *   I1. ninguna dependency por UUID apunta a un header ausente;
 *   I2. el RP depende del BP fusionado;
 *   I3. cada addon con scripts tiene su entry dentro de modules/;
 *   I4. no quedan manifiestos sueltos (manifest_pf*.json).
 */
class Fase2ManifestInvariantsTest {

    private val b = "C:/Users/HP/Downloads/Script Router PF/"

    private fun todosLosHeaders(names: List<String>, textos: Map<String, String>): Set<String> {
        val out = mutableSetOf<String>()
        for (n in names) {
            if (!n.endsWith("manifest.json")) continue
            val t = textos[n] ?: continue
            val uuid = runCatching {
                JSONObject(t).getJSONObject("header").optString("uuid")
            }.getOrNull()
            if (!uuid.isNullOrBlank()) out.add(uuid.lowercase())
        }
        return out
    }

    @Test
    fun `el pack fusionado no deja dependencias colgando`() {
        val addons = listOf(
            File(b + "Just-Health-Indicator.mcpack"),
            File(b + "simple-gravestone 1.1.0.mcaddon"),
            File(b + "SimpleEconomy-v2.10.0.mcaddon")
        )
        org.junit.Assume.assumeTrue("faltan addons", addons.all { it.isFile })

        val work = Files.createTempDirectory("pf_mf_").toFile()
        val r = runBlocking {
            PackForgeOrchestrator.mergeAddons(
                addonPaths = addons.map { it.absolutePath },
                outputDir = work.absolutePath,
                addonNames = listOf("JHI", "Gravestone", "Economy"),
                customName = "ManifestInv",
                addonIds = listOf("jhi", "grav", "econ")
            )
        }
        val pack = r.outputPath?.let { File(it) }
        org.junit.Assert.assertNotNull("debe salir mcaddon: " + r.errorMessage, pack)

        val textos = mutableMapOf<String, String>()
        val todosNombres = mutableListOf<String>()
        ZipFile(pack!!).use { z ->
            for (e in z.entries().toList()) {
                if (e.isDirectory) continue
                val txt = z.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) }
                textos[e.name] = txt
                todosNombres.add(e.name)
            }
        }
        val nombres = todosNombres.toList()
        val headers = todosLosHeaders(nombres, textos)
        println("MANIF | headers en el pack = " + headers.size)

        // I1: toda dependency por UUID debe existir
        val colgando = mutableListOf<String>()
        for ((n, t) in textos) {
            if (!n.endsWith("manifest.json")) continue
            val deps = runCatching { JSONObject(t).optJSONArray("dependencies") }.getOrNull() ?: continue
            for (i in 0 until deps.length()) {
                val dep = deps.optJSONObject(i) ?: continue
                val uuid = dep.optString("uuid", "").lowercase()
                if (uuid.isBlank()) continue
                if (uuid !in headers) colgando.add(n + " -> " + uuid)
            }
        }
        println("MANIF | dependencias colgando = " + colgando.size)
        colgando.forEach { println("MANIF |   DANGLING " + it) }
        assertTrue("Toda dependency por UUID debe existir en el pack: " + colgando, colgando.isEmpty())

        println("MANIF | --- BP manifest ---")
        textos.entries.firstOrNull { it.key == "BP_PackForge/manifest.json" }?.let { println(it.value) }
        println("MANIF | --- entradas BP ---")
        nombres.filter { it.startsWith("BP_PackForge/") }.take(40).forEach { println("MANIF |   " + it) }
        println("MANIF | total BP = " + nombres.count { it.startsWith("BP_PackForge/") })
        println("MANIF | --- TODOS los ficheros con 'script' en la ruta ---")
        nombres.filter { it.lowercase().contains("script") }.forEach { println("MANIF |   " + it) }
        // I4: manifiestos sueltos
        val sueltos = nombres.filter { it.contains("manifest_pf") }
        println("MANIF | manifest_pf sueltos = " + sueltos.size)
        sueltos.forEach { println("MANIF |   SUELTO " + it) }

        // I2: el RP debe depender del BP fusionado
        val rp = textos.entries.firstOrNull { it.key == "RP_PackForge/manifest.json" }?.value
        val bp = textos.entries.firstOrNull { it.key == "BP_PackForge/manifest.json" }?.value
        if (rp != null && bp != null) {
            val bpUuid = JSONObject(bp).getJSONObject("header").getString("uuid").lowercase()
            val rpDeps = JSONObject(rp).optJSONArray("dependencies")
            var depende = false
            if (rpDeps != null) {
                for (i in 0 until rpDeps.length()) {
                    if (rpDeps.optJSONObject(i)?.optString("uuid", "")?.lowercase() == bpUuid) depende = true
                }
            }
            println("MANIF | RP depende del BP = " + depende)
            assertTrue("el RP debe depender del BP fusionado", depende)
        }

        // I5: la raiz scripts/ solo contiene main.js y modules/ (sin copias planas)
        val planos = nombres.filter { n ->
            n.startsWith("BP_PackForge/scripts/") &&
                !n.startsWith("BP_PackForge/scripts/modules/") &&
                !n.endsWith("/scripts/main.js")
        }
        println("MANIF | scripts planos en la raiz = " + planos.size)
        planos.forEach { println("MANIF |   PLANO " + it) }
        assertTrue("no debe haber copias planas de scripts: " + planos, planos.isEmpty())

        // I4b: sin manifiestos sueltos dentro del zip
        assertEquals("no debe haber manifest_pf en el paquete: " + sueltos, 0, sueltos.size)

        // I3: cada addon con scripts tiene su entry en modules/
        val modulos = nombres.filter { it.contains("/scripts/modules/") }
        println("MANIF | ficheros en modules/ = " + modulos.size)
        val conEntry = modulos.filter { it.endsWith("/main.js") || it.endsWith("/index.js") }
        println("MANIF | entradas candidatas = " + conEntry.size)
        conEntry.forEach { println("MANIF |   ENTRY " + it) }
        assertTrue("debe haber entry por addon con scripts", conEntry.size >= 2)

        // main.js debe cargar cada uno
        val main = textos.entries.firstOrNull {
            it.key.contains("/scripts/main.js")
        }?.value ?: ""
        println("MANIF | main.js cargas = " + Regex("__pfLoadModule").findAll(main).count())
        assertTrue("main.js debe cargar los modulos", main.contains("modules/"))
    }
}
