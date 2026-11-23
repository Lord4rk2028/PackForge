package com.packforge.app.domain.engine

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Aísla si mergeGeometryFiles CONSERVA los archivos de geometría de entidad.
 *
 * Extrae core-craft, localiza la raíz del Resource Pack por el módulo
 * "resources" del manifiesto y ejecuta sólo esa fusión sobre un directorio
 * VACÍO. Cuenta cuántos archivos quedan.
 *
 * La extracción ya está verificada: los 120 llegan a disco. Si aquí quedan
 * menos, el culpable es la fusión de geometrías; si quedan 120, la pérdida
 * ocurre más adelante (copia Eager o empaquetado).
 */
class DiagnosticoMergeGeometryArchivosTest {

    @Test
    fun `cuantos archivos de models entity escribe mergeGeometryFiles`() {
        val srcFound = TestFixtures.addon("core-craft_v1.2.8.mcaddon")
        assumeTrue(TestFixtures.describe("core-craft_v1.2.8.mcaddon"), srcFound != null)
        val src = srcFound!!

        val work = Files.createTempDirectory("pf_diag_mergegeo_").toFile()
        try {
            val destExtract = File(work, "extract").also { it.mkdirs() }
            val extraido = AddonExtractor.extractAddon(src.absolutePath, destExtract.absolutePath)
            assertTrue("extractAddon fallo", extraido != null)

            // Raíz del RP: la subcarpeta cuyo manifiesto declara módulo "resources".
            val rpRoot = File(extraido!!).walkTopDown()
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
            assumeTrue("No se encontro la raiz del RP", rpRoot != null)
            println("DIAG-MG | rpRoot= ${rpRoot!!.name}")

            // Cuántos hay en el origen, ANTES de la fusión.
            val origen = rpRoot.walkTopDown().filter {
                it.isFile && it.extension.equals("json", true) &&
                    it.relativeTo(rpRoot).path.replace('\\', '/').contains("models/entity/")
            }.map { it.name }.toSet()
            println("DIAG-MG | models/entity en el ORIGEN: ${origen.size}")

            val dest = File(work, "merged_rp").also { it.mkdirs() }
            BedrockCriticalFilesMerger.mergeGeometryFiles(listOf(rpRoot), dest)

            val salida = dest.walkTopDown().filter {
                it.isFile && it.extension.equals("json", true) &&
                    it.relativeTo(dest).path.replace('\\', '/').contains("models/entity/")
            }.map { it.name }.toSet()
            println("DIAG-MG | models/entity escritos por mergeGeometryFiles: ${salida.size}")

            val perdidos = origen - salida
            println("DIAG-MG | PERDIDOS: ${perdidos.size}")
            perdidos.take(10).forEach { println("DIAG-MG |   $it") }

            // Volcado completo del árbol para ver dónde acabaron.
            println("DIAG-MG | subcarpetas en destino:")
            dest.walkTopDown().filter { it.isDirectory }.take(12).forEach {
                println("DIAG-MG |   ${it.relativeTo(dest).path}")
            }
        } finally {
            println("DIAG-MG | temporal conservado: ${work.absolutePath}")
        }
    }
}
