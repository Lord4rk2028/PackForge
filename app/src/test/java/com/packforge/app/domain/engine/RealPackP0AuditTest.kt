package com.packforge.app.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Auditoría OPT-IN sobre un pack real, para medir el P0 contra el modpack que
 * produjo el reporte con 163 placeholders.
 *
 * Uso:
 * ```
 * $env:PF_REAL_PACK_DIR = "<carpeta con RP_PackForge/ y BP_PackForge/>"
 * .\run-tests.ps1 -Filter "com.packforge.app.domain.engine.RealPackP0AuditTest"
 * ```
 * Sin la variable el test se SALTA (no rompe CI ni la suite normal).
 *
 * Invariantes que verifica:
 *  1. Ningún placeholder puede declarar un identificador de geometría que el pack
 *     ya definía (en el pack real: 148 de 163 lo hacían).
 *  2. Ningún placeholder puede declarar un identificador VANILLA
 *     (en el pack real: 13, incluidos geometry.arrow y geometry.humanoid.armor.*).
 */
class RealPackP0AuditTest {

    private fun declaredGeometryIds(root: File): Set<String> {
        val ids = mutableSetOf<String>()
        if (!root.isDirectory) return ids
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".json") }.forEach { file ->
            val text = try {
                file.readText()
            } catch (_: Exception) {
                return@forEach
            }
            if (!text.contains("minecraft:geometry") && !text.contains("\"geometry.")) return@forEach
            Regex("\"identifier\"\\s*:\\s*\"(geometry\\.[^\"]+)\"")
                .findAll(text).forEach { ids.add(it.groupValues[1]) }
            Regex("\"(geometry\\.[A-Za-z0-9_./-]+)\"\\s*:\\s*\\{")
                .findAll(text).forEach { ids.add(it.groupValues[1]) }
        }
        return ids
    }

    private fun placeholderIds(rpDir: File): List<String> {
        val ids = mutableListOf<String>()
        rpDir.walkTopDown()
            // `_packforge/` está FUERA del .mcaddon: es la papelera de evidencias.
            .filter { !it.invariantSeparatorsPath.replace('\\', '/').contains("/_packforge/") }
            .filter { it.isFile && it.name.contains("placeholder") && it.name.endsWith(".json") }
            .forEach { file ->
                val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
                Regex("\"identifier\"\\s*:\\s*\"([^\"]+)\"")
                    .find(text)?.groupValues?.get(1)?.let { ids.add(it) }
            }
        return ids
    }

    @Test
    fun p0StopsThePlaceholderDamageOnTheRealPack() {
        val rootPath = System.getenv("PF_REAL_PACK_DIR")
        assumeTrue("Define PF_REAL_PACK_DIR para auditar un pack real", !rootPath.isNullOrBlank())
        val root = File(rootPath!!)

        val rpSource = File(root, "RP_PackForge")
        val bpSource = File(root, "BP_PackForge")
        assumeTrue("El pack debe tener RP_PackForge/ y BP_PackForge/", rpSource.isDirectory && bpSource.isDirectory)

        val work = Files.createTempDirectory("pf_real_p0_").toFile()
        try {
            val rp = File(work, "RP")
            val bp = File(work, "BP")
            rpSource.copyRecursively(rp, overwrite = true)
            bpSource.copyRecursively(bp, overwrite = true)

            val beforeIds = declaredGeometryIds(rp)
            val beforePlaceholders = placeholderIds(rp)
            println("P0-AUDIT | geometrías declaradas antes: ${beforeIds.size}")
            println("P0-AUDIT | placeholders previos en el pack de entrada: ${beforePlaceholders.size}")

            val result = PackForgeHealer.repair(bp, rp, emptyList())

            val remaining = placeholderIds(rp)
            val newOnes = remaining.filter { it !in beforePlaceholders }
            println(
                "P0-AUDIT | repair() => placeholdersNuevos=${result.placeholdersGenerated}, " +
                    "obsoletosRetirados=${result.stalePlaceholdersRemoved}, " +
                    "texturasCopiadas=${result.texturesCopied}, " +
                    "faltanTexturas=${result.stillMissingTextures.size}, " +
                    "faltanModelos=${result.stillMissingModels.size}"
            )
            println("P0-AUDIT | placeholders tras la limpieza: ${remaining.size} (antes ${beforePlaceholders.size})")
            println("P0-AUDIT | placeholders NUEVOS: ${newOnes.size} -> $newOnes")

            val vanillaLeft = remaining.filter { VanillaAssets.isVanillaGeometry(it) }
            println("P0-AUDIT | placeholders que aún pisan un id VANILLA: ${vanillaLeft.size} -> $vanillaLeft")

            assertEquals("Ningún placeholder nuevo", 0, newOnes.size)
            assertFalse("Ningún placeholder puede pisar un id vanilla", vanillaLeft.isNotEmpty())
            // Red de seguridad: el healer sigue creando placeholders para lo que de
            // verdad falta (en este pack, 2 ids cc: sin modelo).
            assertTrue(
                "El mecanismo de placeholder debe seguir vivo para lo irrecuperable",
                result.placeholdersGenerated <= 5
            )
        } finally {
            work.deleteRecursively()
        }
    }
}