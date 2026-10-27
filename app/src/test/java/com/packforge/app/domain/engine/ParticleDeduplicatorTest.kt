package com.packforge.app.domain.engine

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * P2: la desambiguación de partículas debe ocurrir **en el addon perdedor y
 * antes de copiar**. Antes se hacía sobre el árbol ya fusionado, donde el
 * renombrado también alcanzaba al addon ganador (sus entidades acababan usando
 * la partícula del otro) y llegaba a colapsar ambas definiciones en una.
 */
class ParticleDeduplicatorTest {

    private val roots = mutableListOf<File>()

    @Before
    fun setUp() {
        DirIndexCache.clear()
    }

    @After
    fun cleanUp() {
        roots.forEach { it.deleteRecursively() }
        DirIndexCache.clear()
    }

    private fun tempDir(): File =
        Files.createTempDirectory("pf_particles_").toFile().also { roots += it }

    /** Addon con una partícula y un archivo de entidad que la referencia. */
    private fun addon(name: String, identifier: String): File {
        val root = File(tempDir(), name).apply { mkdirs() }

        val particles = File(root, "rp/particles").apply { mkdirs() }
        File(particles, "effect.json").writeText(
            """{"format_version":"1.10.0","particle_effect":{"description":{"identifier":"$identifier"},"components":{}}}"""
        )

        val entities = File(root, "rp/entity").apply { mkdirs() }
        File(entities, "ref.json").writeText(
            """{"minecraft:client_entity":{"description":{"identifier":"$name:entity","particle_effect":"$identifier"}}}"""
        )
        return root
    }

    private fun identifierOf(addonRoot: File): String =
        JSONObject(File(addonRoot, "rp/particles/effect.json").readText())
            .getJSONObject("particle_effect")
            .getJSONObject("description")
            .getString("identifier")

    private fun referenceOf(addonRoot: File): String =
        JSONObject(File(addonRoot, "rp/entity/ref.json").readText())
            .getJSONObject("minecraft:client_entity")
            .getJSONObject("description")
            .getString("particle_effect")

    @Test
    fun loserIsRenamedAndWinnerKeepsOriginalIdentifier() {
        val winner = addon("addon_a", "custom:dust")
        val loser = addon("addon_b", "custom:dust")

        val result = ParticleDeduplicator.deduplicate(listOf(winner, loser))

        assertEquals("Solo el perdedor se renombra", 1, result.renames)
        assertEquals("El ganador conserva el identificador", "custom:dust", identifierOf(winner))
        assertTrue(
            "El perdedor recibe sufijo estable",
            identifierOf(loser).startsWith("custom:dust_pf")
        )
    }

    @Test
    fun winnerReferencesAreNeverTouched() {
        val winner = addon("addon_a", "custom:dust")
        val loser = addon("addon_b", "custom:dust")
        val before = File(winner, "rp/entity/ref.json").readText()

        ParticleDeduplicator.deduplicate(listOf(winner, loser))

        assertEquals(
            "El archivo del ganador debe quedar byte a byte igual",
            before,
            File(winner, "rp/entity/ref.json").readText()
        )
        assertEquals("custom:dust", referenceOf(winner))
    }

    @Test
    fun loserOwnReferencesFollowTheRename() {
        val winner = addon("addon_a", "custom:dust")
        val loser = addon("addon_b", "custom:dust")

        ParticleDeduplicator.deduplicate(listOf(winner, loser))

        val newId = identifierOf(loser)
        assertEquals(
            "Las referencias propias del perdedor deben apuntar a su partícula",
            newId,
            referenceOf(loser)
        )
    }

    @Test
    fun vanillaParticlesAreNeverRenamed() {
        val first = addon("addon_a", "minecraft:basic_smoke_particle")
        val second = addon("addon_b", "minecraft:basic_smoke_particle")

        val result = ParticleDeduplicator.deduplicate(listOf(first, second))

        assertEquals("Vanilla no se desambigua", 0, result.renames)
        assertEquals("minecraft:basic_smoke_particle", identifierOf(first))
        assertEquals("minecraft:basic_smoke_particle", identifierOf(second))
    }

    @Test
    fun distinctIdentifiersAreUntouched() {
        val first = addon("addon_a", "custom:dust")
        val second = addon("addon_b", "custom:spark")

        val result = ParticleDeduplicator.deduplicate(listOf(first, second))

        assertEquals(0, result.renames)
        assertEquals("custom:dust", identifierOf(first))
        assertEquals("custom:spark", identifierOf(second))
    }

    @Test
    fun deduplicationIsIdempotent() {
        val winner = addon("addon_a", "custom:dust")
        val loser = addon("addon_b", "custom:dust")

        ParticleDeduplicator.deduplicate(listOf(winner, loser))
        val secondRun = ParticleDeduplicator.deduplicate(listOf(winner, loser))

        assertEquals("La segunda pasada no debe renombrar de nuevo", 0, secondRun.renames)
        assertEquals("custom:dust", identifierOf(winner))
        assertEquals(
            "No se encadena el sufijo",
            1,
            Regex("""_pf[0-9a-f]{4}""").findAll(identifierOf(loser)).count()
        )
    }

    @Test
    fun threeAddonsKeepOneOriginalAndRenameTheRest() {
        val first = addon("addon_a", "custom:dust")
        val second = addon("addon_b", "custom:dust")
        val third = addon("addon_c", "custom:dust")

        val result = ParticleDeduplicator.deduplicate(listOf(first, second, third))

        assertEquals(2, result.renames)
        assertEquals("custom:dust", identifierOf(first))
        val ids = listOf(identifierOf(first), identifierOf(second), identifierOf(third))
        assertEquals("Los tres identificadores deben ser distintos", 3, ids.distinct().size)
    }
}
