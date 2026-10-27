package com.packforge.app.domain.engine

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * P1: archivos AGREGADOS que no tenían fusión dedicada y se sobrescribían
 * (ganaba el último addon), más el guard que impide que una fusión declarada
 * vuelva a quedarse sin conectar.
 */
class AggregateFilesMergeTest {

    private val roots = mutableListOf<File>()

    @Before
    fun setUp() {
        ConflictRegistry.clear()
        MergePipelineGuard.reset()
    }

    @After
    fun cleanUp() {
        roots.forEach { it.deleteRecursively() }
        ConflictRegistry.clear()
        MergePipelineGuard.reset()
    }

    private fun tempDir(): File =
        Files.createTempDirectory("pf_agg_").toFile().also { roots += it }

    private fun pack(name: String, files: Map<String, String>): File {
        val root = File(tempDir(), name).apply { mkdirs() }
        files.forEach { (rel, content) ->
            val file = File(root, rel)
            file.parentFile?.mkdirs()
            file.writeText(content.trimIndent())
        }
        return root
    }

    // ── biomes_client.json ───────────────────────────────────────────────

    @Test
    fun biomesClientKeepsBiomesFromEveryAddon() {
        val first = pack(
            "rp_a",
            mapOf("biomes_client.json" to """{"biomes":{"custom:alpha":{"water_surface_color":"#111111"}}}""")
        )
        val second = pack(
            "rp_b",
            mapOf("biomes_client.json" to """{"biomes":{"custom:beta":{"water_surface_color":"#222222"}}}""")
        )
        val dest = tempDir()

        BedrockCriticalFilesMerger.mergeBiomesClient(listOf(first, second), dest)

        val biomes = JSONObject(File(dest, "biomes_client.json").readText()).getJSONObject("biomes")
        assertTrue("El bioma del primer addon no debe perderse", biomes.has("custom:alpha"))
        assertTrue("El bioma del segundo addon debe estar", biomes.has("custom:beta"))
    }

    @Test
    fun biomeDuplicatedWithDifferentConfigKeepsFirstAndReports() {
        val first = pack(
            "rp_a",
            mapOf("biomes_client.json" to """{"biomes":{"custom:alpha":{"water_surface_color":"#111111"}}}""")
        )
        val second = pack(
            "rp_b",
            mapOf("biomes_client.json" to """{"biomes":{"custom:alpha":{"water_surface_color":"#999999"}}}""")
        )
        val dest = tempDir()

        BedrockCriticalFilesMerger.mergeBiomesClient(listOf(first, second), dest)

        val biomes = JSONObject(File(dest, "biomes_client.json").readText()).getJSONObject("biomes")
        assertEquals(
            "No se renombra el bioma (su nombre es una referencia): se conserva el primero",
            "#111111",
            biomes.getJSONObject("custom:alpha").getString("water_surface_color")
        )
        assertTrue(
            "El conflicto de bioma debe registrarse",
            ConflictRegistry.conflicts.value.any { it.conflictType == "BIOME_CLIENT_DUPLICATE" }
        )
    }

    @Test
    fun identicalBiomeConfigIsNotReported() {
        val body = """{"biomes":{"custom:alpha":{"water_surface_color":"#111111"}}}"""
        val first = pack("rp_a", mapOf("biomes_client.json" to body))
        val second = pack("rp_b", mapOf("biomes_client.json" to body))
        val dest = tempDir()

        BedrockCriticalFilesMerger.mergeBiomesClient(listOf(first, second), dest)

        assertFalse(
            "Configuración idéntica no es un conflicto",
            ConflictRegistry.conflicts.value.any { it.conflictType == "BIOME_CLIENT_DUPLICATE" }
        )
    }

    // ── font/*.json ──────────────────────────────────────────────────────

    @Test
    fun fontGlyphPagesAreUnitedWithoutDuplicates() {
        val first = pack("rp_a", mapOf("font/glyph_E1.json" to """["0xE100","0xE101"]"""))
        val second = pack("rp_b", mapOf("font/glyph_E1.json" to """["0xE101","0xE102"]"""))
        val dest = tempDir()

        BedrockCriticalFilesMerger.mergeFontGlyphMaps(listOf(first, second), dest)

        val glyphs = org.json.JSONArray(File(dest, "font/glyph_E1.json").readText())
        val values = (0 until glyphs.length()).map { glyphs.getString(it) }
        assertEquals("Los glifos de ambos addons deben sobrevivir", 3, values.size)
        assertEquals(listOf("0xE100", "0xE101", "0xE102"), values)
    }

    @Test
    fun separateFontPagesAreBothWritten() {
        val first = pack("rp_a", mapOf("font/glyph_E1.json" to """["0xE100"]"""))
        val second = pack("rp_b", mapOf("font/glyph_E2.json" to """["0xE200"]"""))
        val dest = tempDir()

        BedrockCriticalFilesMerger.mergeFontGlyphMaps(listOf(first, second), dest)

        assertTrue(File(dest, "font/glyph_E1.json").isFile)
        assertTrue(File(dest, "font/glyph_E2.json").isFile)
    }

    // ── textures/textures_list.json ──────────────────────────────────────

    @Test
    fun texturesListIsUnitedWithoutDuplicates() {
        val first = pack(
            "rp_a",
            mapOf("textures/textures_list.json" to """["textures/blocks/a","textures/blocks/b"]""")
        )
        val second = pack(
            "rp_b",
            mapOf("textures/textures_list.json" to """["textures/blocks/b","textures/blocks/c"]""")
        )
        val dest = tempDir()

        BedrockCriticalFilesMerger.mergeTexturesList(listOf(first, second), dest)

        val list = org.json.JSONArray(File(dest, "textures/textures_list.json").readText())
        val values = (0 until list.length()).map { list.getString(it) }
        assertEquals(3, values.size)
        assertEquals(listOf("textures/blocks/a", "textures/blocks/b", "textures/blocks/c"), values)
    }

    @Test
    fun filesAbsentFromAllPacksProduceNothing() {
        val empty = pack("rp_a", mapOf("manifest.json" to """{"format_version":2}"""))
        val dest = tempDir()

        assertEquals(0, BedrockCriticalFilesMerger.mergeBiomesClient(listOf(empty), dest))
        assertEquals(0, BedrockCriticalFilesMerger.mergeFontGlyphMaps(listOf(empty), dest))
        assertEquals(0, BedrockCriticalFilesMerger.mergeTexturesList(listOf(empty), dest))
        assertFalse(File(dest, "biomes_client.json").exists())
        assertFalse(File(dest, "textures/textures_list.json").exists())
    }

    // ── GUARD ────────────────────────────────────────────────────────────

    @Test
    fun guardReportsDeclaredMergesThatNeverRan() {
        // Reproduce EXACTAMENTE el fallo histórico: todo el pipeline declarado
        // menos las dos fusiones que se habían quedado huérfanas.
        MergePipelineGuard.reset()
        MergePipelineGuard.REQUIRED_MERGES
            .filterNot { it == "mergeSoundDefinitions" || it == "deduplicateParticleIdentifiers" }
            .forEach { MergePipelineGuard.registerInvoked(it) }

        val missing = MergePipelineGuard.findMissingMerges()

        assertTrue(missing.contains("mergeSoundDefinitions"))
        assertTrue(missing.contains("deduplicateParticleIdentifiers"))
        assertEquals(2, missing.size)
    }

    @Test
    fun guardReportsMergesThatRanWithoutBeingDeclared() {
        MergePipelineGuard.reset()
        MergePipelineGuard.registerInvoked("mergeTerrainTexture")
        MergePipelineGuard.registerInvoked("fusionInventada")

        assertEquals(listOf("fusionInventada"), MergePipelineGuard.findUndeclaredMerges())
    }

    @Test
    fun guardIsCleanWhenEverythingRuns() {
        MergePipelineGuard.reset()
        MergePipelineGuard.REQUIRED_MERGES.forEach { MergePipelineGuard.registerInvoked(it) }

        assertTrue(MergePipelineGuard.findMissingMerges().isEmpty())
        assertTrue(MergePipelineGuard.findUndeclaredMerges().isEmpty())
    }

    @Test
    fun soundAndParticleMergesAreDeclaredInTheGuard() {
        // Candado de regresión: estas dos se quedaron sin conectar una vez.
        assertTrue(MergePipelineGuard.REQUIRED_MERGES.contains("mergeSoundDefinitions"))
        // Las partículas se desambiguan ahora en el origen, antes de copiar.
        assertTrue(MergePipelineGuard.REQUIRED_MERGES.contains("deduplicateParticleIdentifiers"))
        assertTrue(MergePipelineGuard.REQUIRED_MERGES.contains("mergeBiomesClient"))
        assertTrue(MergePipelineGuard.REQUIRED_MERGES.contains("mergeFontGlyphMaps"))
        assertTrue(MergePipelineGuard.REQUIRED_MERGES.contains("mergeTexturesList"))
    }
}
