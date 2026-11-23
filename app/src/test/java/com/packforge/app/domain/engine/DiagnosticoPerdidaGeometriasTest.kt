package com.packforge.app.domain.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Diagnóstico: ¿se PIERDEN geometrías en mergeGeometryFiles?
 *
 * Compara las geometrías declaradas en el core-craft ORIGINAL con las que
 * acaban en el directorio RP fusionado por el propio merger.
 */
class DiagnosticoPerdidaGeometriasTest {

    private fun declaradasEn(zipPath: File): Map<String, String> {
        val out = mutableMapOf<String, String>()
        java.util.zip.ZipFile(zipPath).use { zip ->
            zip.entries().toList().filter { it.name.endsWith(".json") }.forEach { e ->
                val text = zip.getInputStream(e).bufferedReader().readText()
                if (!text.contains("geometry.")) return@forEach
                Regex("\"identifier\"\\s*:\\s*\"(geometry\\.[^\"]+)\"")
                    .findAll(text).forEach { out[it.groupValues[1]] = e.name }
                Regex("\"(geometry\\.[A-Za-z0-9_.\\-]+)\"\\s*:\\s*\\{")
                    .findAll(text).forEach { out[it.groupValues[1]] = e.name }
            }
        }
        return out
    }

    @Test
    fun `cuantas geometrias sobreviven a mergeGeometryFiles`() {
        val srcFound = TestFixtures.addon("core-craft_v1.2.8.mcaddon")
        assumeTrue(TestFixtures.describe("core-craft_v1.2.8.mcaddon"), srcFound != null)
        val src = srcFound!!

        val original = declaradasEn(src)
        println("DIAG | geometrias en el ORIGINAL: ${original.size}")

        // Extraer el addon tal como hace la app
        val work = Files.createTempDirectory("pf_diag_geo_").toFile()
        try {
            val destExtract = File(work, "extract").also { it.mkdirs() }
            val extraidoPath = AddonExtractor.extractAddon(src.absolutePath, destExtract.absolutePath)
                ?: run {
                    println("DIAG | extractAddon devolvio null")
                    return
                }
            println("DIAG | extraido en: $extraidoPath")

            val rpRoot = AddonExtractor.resolvePackRoot(File(extraidoPath))
            println("DIAG | resolvePackRoot devolvio: ${rpRoot.name}")

            // Elegir EXPLÍCITAMENTE la raíz del Resource Pack: la subcarpeta que
            // declara un módulo "resources" en su manifest. resolvePackRoot
            // devuelve el PRIMER manifest.json, que en un .mcaddon con
            // BP_*/ y RP_*/ es el del Behavior Pack.
            val rpReal = File(extraidoPath).walkTopDown()
                .filter { it.isFile && it.name.equals("manifest.json", ignoreCase = true) }
                .mapNotNull { it.parentFile }
                .firstOrNull { dir ->
                    val m = runCatching {
                        JSONObject(File(dir, "manifest.json").readText())
                    }.getOrNull()
                    m?.optJSONArray("modules")?.let { arr ->
                        (0 until arr.length()).any {
                            arr.optJSONObject(it)?.optString("type") == "resources"
                        }
                    } ?: false
                }
            assumeTrue("No se encontro la raiz del RP", rpReal != null)
            println("DIAG | rpReal (por modulo 'resources'): ${rpReal!!.name}")

            val dest = File(work, "merged_rp").also { it.mkdirs() }
            BedrockCriticalFilesMerger.mergeGeometryFiles(listOf(rpReal), dest)

            val fusionado = declaradasEn(dest.let {
                // empaquetar el dir para reusar el lector
                val z = File(work, "merged.zip")
                java.util.zip.ZipOutputStream(z.outputStream()).use { zo ->
                    it.walkTopDown().filter { f -> f.isFile }.forEach { f ->
                        zo.putNextEntry(java.util.zip.ZipEntry(
                            f.relativeTo(it).path.replace("\\", "/")))
                        zo.write(f.readBytes()); zo.closeEntry()
                    }
                }
                z
            })

            println("DIAG | geometrias tras mergeGeometryFiles: ${fusionado.size}")
            val perdidas = original.keys - fusionado.keys
            println("DIAG | PERDIDAS: ${perdidas.size}")
            perdidas.take(20).forEach { println("DIAG |   $it  <- ${original[it]}") }

            assertEquals(
                "mergeGeometryFiles NO debe perder geometrias",
                0, perdidas.size
            )
        } finally {
            work.deleteRecursively()
        }
    }
}
