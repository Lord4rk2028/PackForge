package com.packforge.app.domain.engine

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Estas dos fusiones existían en el motor pero **nunca se llamaban** desde el
 * orquestador: el copiado genérico las sobrescribía y se perdía contenido.
 * Los tests fijan el contrato para que no vuelvan a quedar desconectadas.
 */
class SoundAndParticleMergeTest {

    private val roots = mutableListOf<File>()

    @Before
    fun setUp() {
        DirIndexCache.clear()
        ConflictRegistry.clear()
    }

    @After
    fun cleanUp() {
        roots.forEach { it.deleteRecursively() }
        DirIndexCache.clear()
        ConflictRegistry.clear()
    }

    private fun tempDir(): File =
        Files.createTempDirectory("pf_merge_").toFile().also { roots += it }

    private fun rp(name: String, soundDefinitions: String? = null): File {
        val root = File(tempDir(), name).apply { mkdirs() }
        if (soundDefinitions != null) {
            File(root, "sound_definitions.json").writeText(soundDefinitions.trimIndent())
        }
        return root
    }

    private fun particlePack(name: String, fileName: String, identifier: String): File {
        val root = File(tempDir(), name).apply { mkdirs() }
        val dir = File(root, "particles").apply { mkdirs() }
        File(dir, fileName).writeText(
            """{"format_version":"1.10.0","particle_effect":{"description":{"identifier":"$identifier"},"components":{}}}"""
        )
        return root
    }

    // ── SOUND DEFINITIONS ────────────────────────────────────────────────

    @Test
    fun soundDefinitionsKeepEveryAddonSounds() {
        val first = rp(
            "rp_a",
            """{"format_version":1,"sound.alpha":{"category":"block","sounds":[{"name":"sounds/alpha"}]}}"""
        )
        val second = rp(
            "rp_b",
            """{"format_version":1,"sound.beta":{"category":"block","sounds":[{"name":"sounds/beta"}]}}"""
        )
        val dest = tempDir()

        val renames = BedrockCriticalFilesMerger.mergeSoundDefinitions(listOf(first, second), dest)

        val merged = JSONObject(File(dest, "sound_definitions.json").readText())
        assertTrue("El sonido del primer addon NO debe perderse", merged.has("sound.alpha"))
        assertTrue("El sonido del segundo addon debe estar", merged.has("sound.beta"))
        assertTrue("Sin duplicados no debe haber renombres", renames.isEmpty())
    }

    @Test
    fun identicalDuplicateSoundIsDedupedWithoutRename() {
        val body = """{"format_version":1,"sound.shared":{"category":"block","sounds":[{"name":"sounds/x"}]}}"""
        val first = rp("rp_a", body)
        val second = rp("rp_b", body)
        val dest = tempDir()

        val renames = BedrockCriticalFilesMerger.mergeSoundDefinitions(listOf(first, second), dest)

        val merged = JSONObject(File(dest, "sound_definitions.json").readText())
        assertTrue(merged.has("sound.shared"))
        assertEquals("La definición idéntica se deduplica", 2, merged.length()) // format_version + sound.shared
        assertTrue(renames.isEmpty())
    }

    @Test
    fun conflictingSoundIsAliasedAndReported() {
        val first = rp(
            "rp_a",
            """{"format_version":1,"sound.hit":{"category":"block","sounds":[{"name":"sounds/a"}]}}"""
        )
        val second = rp(
            "rp_b",
            """{"format_version":1,"sound.hit":{"category":"player","sounds":[{"name":"sounds/b"}]}}"""
        )
        val dest = tempDir()

        val renames = BedrockCriticalFilesMerger.mergeSoundDefinitions(listOf(first, second), dest)

        val merged = JSONObject(File(dest, "sound_definitions.json").readText())
        assertTrue("El original se conserva", merged.has("sound.hit"))
        assertEquals("El duplicado distinto se aliasa", 1, renames.size)
        val alias = renames.getValue("sound.hit")
        assertTrue("El alias debe existir en el JSON", merged.has(alias))
        assertNotEquals("sound.hit", alias)
        assertEquals(
            "El alias conserva el contenido del segundo addon",
            "player",
            merged.getJSONObject(alias).getString("category")
        )
    }

    // ── PARTICLE EFFECTS ─────────────────────────────────────────────────

    @Test
    fun duplicateParticleIdentifierIsRenamed() {
        val first = particlePack("rp_a", "a.json", "custom:dust")
        val second = particlePack("rp_b", "b.json", "custom:dust")
        val mergedRp = tempDir().apply { mkdirs() }
        val mergedBp = tempDir().apply { mkdirs() }

        BedrockCriticalFilesMerger.mergeParticles(listOf(first, second), mergedRp, mergedBp)

        val identifiers = File(mergedRp, "particles").listFiles()
            ?.filter { it.extension.equals("json", true) }
            ?.map { file ->
                JSONObject(file.readText())
                    .getJSONObject("particle_effect")
                    .getJSONObject("description")
                    .getString("identifier")
            }
            .orEmpty()

        assertEquals("Ambos efectos deben llegar al modpack", 2, identifiers.size)
        assertEquals("Solo uno conserva el identificador original", 1, identifiers.count { it == "custom:dust" })
        assertEquals(
            "El duplicado se renombra con sufijo estable",
            1,
            identifiers.count { it.startsWith("custom:dust_pf") }
        )
    }

    @Test
    fun vanillaParticleIdentifiersAreNeverRenamed() {
        val first = particlePack("rp_a", "a.json", "minecraft:basic_smoke_particle")
        val second = particlePack("rp_b", "b.json", "minecraft:basic_smoke_particle")
        val mergedRp = tempDir().apply { mkdirs() }
        val mergedBp = tempDir().apply { mkdirs() }

        BedrockCriticalFilesMerger.mergeParticles(listOf(first, second), mergedRp, mergedBp)

        val identifiers = File(mergedRp, "particles").listFiles()
            ?.filter { it.extension.equals("json", true) }
            ?.map { file ->
                JSONObject(file.readText())
                    .getJSONObject("particle_effect")
                    .getJSONObject("description")
                    .getString("identifier")
            }
            .orEmpty()

        assertEquals(
            "Las partículas vanilla no se renombran jamás",
            2,
            identifiers.count { it == "minecraft:basic_smoke_particle" }
        )
    }

    @Test
    fun distinctParticleIdentifiersAreUntouched() {
        val first = particlePack("rp_a", "a.json", "custom:dust")
        val second = particlePack("rp_b", "b.json", "custom:spark")
        val mergedRp = tempDir().apply { mkdirs() }
        val mergedBp = tempDir().apply { mkdirs() }

        BedrockCriticalFilesMerger.mergeParticles(listOf(first, second), mergedRp, mergedBp)

        val identifiers = File(mergedRp, "particles").listFiles()
            ?.filter { it.extension.equals("json", true) }
            ?.map { file ->
                JSONObject(file.readText())
                    .getJSONObject("particle_effect")
                    .getJSONObject("description")
                    .getString("identifier")
            }
            .orEmpty()

        assertTrue(identifiers.contains("custom:dust"))
        assertTrue(identifiers.contains("custom:spark"))
        assertTrue(
            "Sin colisión no debe aparecer ningún sufijo",
            identifiers.none { it.contains("_pf") }
        )
    }
}
