package com.packforge.app.domain.engine

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Reproduce la llamada REAL a mergeGeometryFiles: los cuatro addons a la vez,
 * con las raíces de Resource Pack resueltas igual que en el orquestador
 * (modulo "resources" del manifiesto).
 *
 * Con core-craft aislado la fusion conserva los 120 modelos de entidad. Este
 * test comprueba si la interaccion entre addons es lo que hace desaparecer 59.
 */
class DiagnosticoMergeGeometryMultiAddonTest {

    private fun contar(root: File): Int = root.walkTopDown().count {
        it.isFile && it.extension.equals("json", true) &&
            it.relativeTo(root).path.replace('\\', '/').contains("models/entity/")
    }

    private fun nombres(root: File): Set<String> = root.walkTopDown().filter {
        it.isFile && it.extension.equals("json", true) &&
            it.relativeTo(root).path.replace('\\', '/').contains("models/entity/")
    }.map { it.name }.toSet()

    @Test
    fun `la fusion con los cuatro addons conserva los modelos de entidad`() {
        val downloads = File(System.getenv("USERPROFILE"), "Downloads")
        val nombresAddon = listOf(
            "core-craft_v1.2.8.mcaddon",
            "The-Sift-WE-0.9-RP.mcpack",
            "The-Sift-WE-0.9-BP.mcpack",
            "BP_RP_MTA_26.3.mcaddon"
        )
        assumeTrue(
            "Faltan addons en Downloads",
            nombresAddon.map { File(downloads, it) }.any { it.isFile }
        )

        val work = Files.createTempDirectory("pf_diag_multi_").toFile()
        try {
            // Construir la lista de raices RP EXACTAMENTE como el orquestador.
            val roots = mutableListOf<Pair<String, File>>()
            nombresAddon.forEachIndexed { i, nombre ->
                val addon = File(downloads, nombre)
                if (!addon.isFile) {
                    println("DIAG-MULTI | $nombre: NO ESTA")
                    return@forEachIndexed
                }
                val ex = File(work, "ex$i").also { it.mkdirs() }
                val extraido = AddonExtractor.extractAddon(addon.absolutePath, ex.absolutePath)
                if (extraido == null) {
                    println("DIAG-MULTI | $nombre: extractAddon = null")
                    return@forEachIndexed
                }
                val rp = AddonExtractor.resolvePackRoot(File(extraido), "resources")
                if (rp == null || !rp.isDirectory) {
                    println("DIAG-MULTI | $nombre: sin raiz RP (resources)")
                    return@forEachIndexed
                }
                println("DIAG-MULTI | $nombre -> rp=${rp.name} modelosEntidad=${contar(rp)}")
                roots += nombre to rp
            }

            val dest = File(work, "merged_rp").also { it.mkdirs() }
            BedrockCriticalFilesMerger.mergeGeometryFiles(roots.map { it.second }, dest)

            val salida = contar(dest)
            val unionFuentes = roots.flatMap { nombres(it.second) }.toSet()
            println("DIAG-MULTI | raices=${roots.size}")
            println("DIAG-MULTI | modelos de entidad: union de fuentes=${unionFuentes.size} destino=$salida")

            val perdidos = unionFuentes - nombres(dest)
            println("DIAG-MULTI | PERDIDOS=${perdidos.size}")
            perdidos.take(10).forEach { println("DIAG-MULTI |   $it") }

            // Si aparece un prefijo tipo RP_CCR_CCR/ en el destino, la raiz
            // resuelta fue el directorio PADRE y no el propio Resource Pack.
            println("DIAG-MULTI | carpetas de primer nivel en destino:")
            dest.listFiles()?.take(10)?.forEach { println("DIAG-MULTI |   ${it.name}/") }
            dest.listFiles()?.filter { it.isDirectory }?.take(5)?.forEach { d1 ->
                d1.listFiles()?.take(5)?.forEach { println("DIAG-MULTI |     ${d1.name}/${it.name}") }
            }

            println("DIAG-MULTI | temporal: ${work.absolutePath}")
        } finally {
            println("DIAG-MULTI | (temporal conservado para inspeccion)")
        }
    }
}
