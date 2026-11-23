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

    // ── Catálogos por clave: perder una textura ya no es silencioso ──────

    @Test
    fun terrainTextureCollisionIsReported() {
        val first = pack(
            "rp_a",
            mapOf(
                "textures/terrain_texture.json" to
                    """{"texture_data":{"custom:block":{"textures":"textures/blocks/a"}}}"""
            )
        )
        val second = pack(
            "rp_b",
            mapOf(
                "textures/terrain_texture.json" to
                    """{"texture_data":{"custom:block":{"textures":"textures/blocks/b"}}}"""
            )
        )
        val dest = tempDir()

        BedrockCriticalFilesMerger.mergeTerrainTexture(listOf(first, second), dest)

        val data = JSONObject(File(dest, "textures/terrain_texture.json").readText())
            .getJSONObject("texture_data")
        assertEquals(
            "Se conserva el primero (comportamiento previo)",
            "textures/blocks/a",
            data.getJSONObject("custom:block").getString("textures")
        )
        val conflicto = ConflictRegistry.conflicts.value
            .firstOrNull { it.conflictType == "TEXTURE_OVERWRITE" && it.filePath.endsWith("terrain_texture.json") }
        assertTrue("La textura perdida debe reportarse", conflicto != null)
        assertEquals(
            "Una clave custom es MEDIUM (textura equivocada en el juego)",
            com.packforge.app.domain.model.ConflictSeverity.MEDIUM,
            conflicto!!.severity
        )
        assertEquals("rp_a", conflicto.sourceAddon)
        assertEquals("rp_b", conflicto.targetAddon)
    }

    @Test
    fun identicalTextureEntryIsNotReported() {
        val body = """{"texture_data":{"custom:block":{"textures":"textures/blocks/a"}}}"""
        val first = pack("rp_a", mapOf("textures/terrain_texture.json" to body))
        val second = pack("rp_b", mapOf("textures/terrain_texture.json" to body))
        val dest = tempDir()

        BedrockCriticalFilesMerger.mergeTerrainTexture(listOf(first, second), dest)

        assertFalse(
            "El mismo mapeo no es conflicto (regla 8)",
            ConflictRegistry.conflicts.value.any { it.conflictType == "TEXTURE_OVERWRITE" }
        )
    }

    @Test
    fun vanillaTextureOverrideIsReportedAsNotice() {
        val first = pack(
            "rp_a",
            mapOf("textures/item_texture.json" to """{"texture_data":{"stick":{"textures":"textures/items/a"}}}""")
        )
        val second = pack(
            "rp_b",
            mapOf("textures/item_texture.json" to """{"texture_data":{"stick":{"textures":"textures/items/b"}}}""")
        )
        val dest = tempDir()

        BedrockCriticalFilesMerger.mergeItemTexture(listOf(first, second), dest)

        val conflicto = ConflictRegistry.conflicts.value
            .firstOrNull { it.conflictType == "TEXTURE_OVERWRITE" }
        assertTrue("El override de textura vanilla debe verse", conflicto != null)
        assertEquals(
            "Un override vanilla es aviso, no bloqueo",
            com.packforge.app.domain.model.ConflictSeverity.LOW,
            conflicto!!.severity
        )
    }

    @Test
    fun blocksJsonCollisionIsReported() {
        val first = pack(
            "rp_a",
            mapOf("blocks.json" to """{"format_version":[1,1,0],"custom:block":{"textures":"a"}}""")
        )
        val second = pack(
            "rp_b",
            mapOf("blocks.json" to """{"format_version":[1,1,0],"custom:block":{"textures":"b"}}""")
        )
        val dest = tempDir()

        BedrockCriticalFilesMerger.mergeBlocksJson(listOf(first, second), dest)

        val conflicto = ConflictRegistry.conflicts.value
            .firstOrNull { it.conflictType == "BLOCK_RENDER_OVERWRITE" }
        assertTrue("El mapeo de bloque perdido debe reportarse", conflicto != null)
        assertEquals("blocks.json", conflicto!!.filePath)
        assertTrue(conflicto.description.contains("custom:block"))
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

    // ── Geometría: duplicado con contenido distinto ──────────────────────

    @Test
    fun duplicatedGeometryWithDifferentMeshIsReported() {
        fun geo(hueso: String) =
            """{"format_version":"1.12.0","minecraft:geometry":[{"description":{"identifier":"geometry.mob"},"bones":[{"name":"$hueso"}]}]}"""

        val first = pack("rp_a", mapOf("models/entity/mob.geo.json" to geo("hueso_a")))
        val second = pack("rp_b", mapOf("models/entity/mob.geo.json" to geo("hueso_b")))
        val dest = tempDir()

        BedrockCriticalFilesMerger.mergeGeometryFiles(listOf(first, second), dest)

        val geoArr = JSONObject(File(dest, "models/entity/mob.geo.json").readText())
            .getJSONArray("minecraft:geometry")
        assertEquals("Se conserva una sola definición", 1, geoArr.length())
        assertEquals(
            "Se mantiene la primera malla (el resolver aliasa la otra si hace falta)",
            "hueso_a",
            geoArr.getJSONObject(0).getJSONArray("bones").getJSONObject(0).getString("name")
        )

        val conflicto = ConflictRegistry.conflicts.value
            .firstOrNull { it.conflictType == "GEOMETRY_IDENTIFIER_COLLISION" }
        assertTrue("La malla perdida debe reportarse, no solo ir a logcat", conflicto != null)
        assertEquals("rp_a", conflicto!!.sourceAddon)
        assertEquals("rp_b", conflicto.targetAddon)
        assertEquals(
            "models/entity/mob.geo.json",
            conflicto.filePath.replace('\\', '/')
        )
        assertTrue(conflicto.description.contains("geometry.mob"))
    }

    @Test
    fun identicalGeometryIsNotReported() {
        val geo =
            """{"format_version":"1.12.0","minecraft:geometry":[{"description":{"identifier":"geometry.mob"},"bones":[{"name":"hueso"}]}]}"""
        val first = pack("rp_a", mapOf("models/entity/mob.geo.json" to geo))
        val second = pack("rp_b", mapOf("models/entity/mob.geo.json" to geo))
        val dest = tempDir()

        BedrockCriticalFilesMerger.mergeGeometryFiles(listOf(first, second), dest)

        assertFalse(
            "La misma malla repetida no es conflicto (regla 8)",
            ConflictRegistry.conflicts.value.any { it.conflictType == "GEOMETRY_IDENTIFIER_COLLISION" }
        )
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
