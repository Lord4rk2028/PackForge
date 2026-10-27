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
 * Tests de [BedrockCriticalFilesMerger.mergeFlipbookTextures].
 *
 * Las texturas animadas (agua, lava, portales, bloques animados) se definen en
 * `textures/flipbook_textures.json`. Si dos addons animan la MISMA textura y se
 * fusionan a ciegas, el juego muestra una textura congelada o con la velocidad
 * del otro addon sin que nadie se entere.
 */
class FlipbookTexturesMergeTest {

    private val roots = mutableListOf<File>()

    @Before
    fun setUp() {
        ConflictRegistry.clear()
    }

    @After
    fun cleanUp() {
        roots.forEach { it.deleteRecursively() }
        ConflictRegistry.clear()
    }

    private fun tempDir(): File =
        Files.createTempDirectory("pf_flip_").toFile().also { roots += it }

    private fun resourcePack(name: String, flipbookJson: String): File {
        val root = File(tempDir(), name).apply { mkdirs() }
        val textures = File(root, "textures").apply { mkdirs() }
        File(textures, "flipbook_textures.json").writeText(flipbookJson.trimIndent())
        return root
    }

    private fun merge(vararg packs: File): JSONObject {
        val dest = tempDir()
        BedrockCriticalFilesMerger.mergeFlipbookTextures(packs.toList(), dest)
        val output = File(dest, "textures/flipbook_textures.json")
        assertTrue("Debe escribirse flipbook_textures.json", output.exists())
        return JSONObject(output.readText())
    }

    private fun entries(merged: JSONObject): List<JSONObject> {
        val arr = merged.getJSONArray("flipbook_textures")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    @Test
    fun mergesDistinctTexturesFromBothPacks() {
        val first = resourcePack(
            "rp_a",
            """{"flipbook_textures":[{"flipbook_texture":"textures/blocks/water_still","ticks_per_frame":2}]}"""
        )
        val second = resourcePack(
            "rp_b",
            """{"flipbook_textures":[{"flipbook_texture":"textures/blocks/lava_still","ticks_per_frame":4}]}"""
        )

        val merged = entries(merge(first, second))

        assertEquals(2, merged.size)
        assertTrue(merged.any { it.getString("flipbook_texture").endsWith("water_still") })
        assertTrue(merged.any { it.getString("flipbook_texture").endsWith("lava_still") })
    }

    @Test
    fun duplicateTextureKeepsFirstAndReportsConflict() {
        val first = resourcePack(
            "rp_a",
            """{"flipbook_textures":[{"flipbook_texture":"textures/blocks/portal","ticks_per_frame":3}]}"""
        )
        val second = resourcePack(
            "rp_b",
            """{"flipbook_textures":[{"flipbook_texture":"textures/blocks/portal","ticks_per_frame":8}]}"""
        )

        val merged = entries(merge(first, second))

        assertEquals("Solo debe quedar una entrada", 1, merged.size)
        assertEquals("Se conserva la del primer addon", 3, merged.first().getInt("ticks_per_frame"))

        val flipbookConflicts = ConflictRegistry.conflicts.value
            .filter { it.conflictType == "FLIPBOOK_DUPLICATE" }
        assertEquals("Debe registrarse el conflicto de animación", 1, flipbookConflicts.size)
        assertTrue(flipbookConflicts.first().description.contains("ticks_per_frame"))
    }

    @Test
    fun normalizesPathBeforeDedup() {
        val first = resourcePack(
            "rp_a",
            """{"flipbook_textures":[{"flipbook_texture":"./textures/blocks/water","ticks_per_frame":2}]}"""
        )
        val second = resourcePack(
            "rp_b",
            """{"flipbook_textures":[{"flipbook_texture":"textures/blocks/water","ticks_per_frame":2}]}"""
        )

        val merged = entries(merge(first, second))

        assertEquals("La misma textura no debe duplicarse por './'", 1, merged.size)
        assertTrue(
            "Sin diferencias reales no debe haber conflicto",
            ConflictRegistry.conflicts.value.none { it.conflictType == "FLIPBOOK_DUPLICATE" }
        )
    }

    @Test
    fun supportsRootArrayFormat() {
        val objectFormat = resourcePack(
            "rp_a",
            """{"flipbook_textures":[{"flipbook_texture":"textures/blocks/water","ticks_per_frame":2}]}"""
        )
        val arrayFormat = resourcePack(
            "rp_b",
            """[{"flipbook_texture":"textures/blocks/fire","ticks_per_frame":1,"blend_frames":false}]"""
        )

        val merged = entries(merge(objectFormat, arrayFormat))

        assertEquals(2, merged.size)
        val fire = merged.first { it.getString("flipbook_texture").endsWith("fire") }
        assertEquals(1, fire.getInt("ticks_per_frame"))
        assertTrue("Los campos extra deben conservarse", fire.has("blend_frames"))
    }

    @Test
    fun atlasTileWithDifferentIndexIsNotDuplicate() {
        val first = resourcePack(
            "rp_a",
            """{"flipbook_textures":[{"atlas_tile":"flame_atlas","atlas_index":0,"ticks_per_frame":2}]}"""
        )
        val second = resourcePack(
            "rp_b",
            """{"flipbook_textures":[{"atlas_tile":"flame_atlas","atlas_index":1,"ticks_per_frame":2}]}"""
        )

        val merged = entries(merge(first, second))

        assertEquals(
            "Mismo atlas con distinto índice son texturas distintas",
            2,
            merged.size
        )
    }

    @Test
    fun writesNothingWhenNoPackHasFlipbooks() {
        val empty = File(tempDir(), "rp_empty").apply { mkdirs() }
        val dest = tempDir()

        BedrockCriticalFilesMerger.mergeFlipbookTextures(listOf(empty), dest)

        assertTrue(
            "Sin flipbooks no debe crearse el archivo",
            !File(dest, "textures/flipbook_textures.json").exists()
        )
    }
}
