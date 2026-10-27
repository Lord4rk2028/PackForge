package com.packforge.app.domain.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * P2: la decisión del usuario debe aplicarse **archivo por archivo** al final
 * de la fusión, sin romper los archivos que tienen fusión semántica dedicada.
 */
class ResolutionApplierTest {

    private val roots = mutableListOf<File>()

    @After
    fun cleanUp() {
        roots.forEach { it.deleteRecursively() }
    }

    private fun tempDir(): File =
        Files.createTempDirectory("pf_res_").toFile().also { roots += it }

    private fun addon(name: String, files: Map<String, String>): File {
        val root = File(tempDir(), name).apply { mkdirs() }
        files.forEach { (rel, content) ->
            val file = File(root, rel)
            file.parentFile?.mkdirs()
            file.writeText(content)
        }
        return root
    }

    private fun mergedTree(files: Map<String, String>): File {
        val root = tempDir().apply { mkdirs() }
        files.forEach { (rel, content) ->
            val file = File(root, rel)
            file.parentFile?.mkdirs()
            file.writeText(content)
        }
        return root
    }

    @Test
    fun winnerFileReplacesTheMergedOne() {
        val winner = addon("addon_a", mapOf("textures/blocks/stone.png" to "TEXTURA-DEL-GANADOR"))
        val loser = addon("addon_b", mapOf("textures/blocks/stone.png" to "TEXTURA-DEL-PERDEDOR"))
        // El árbol fusionado se quedó con la del perdedor (se copió después).
        val mergedRp = mergedTree(mapOf("textures/blocks/stone.png" to "TEXTURA-DEL-PERDEDOR"))
        val mergedBp = tempDir().apply { mkdirs() }

        val result = ResolutionApplier.apply(
            fileWinners = mapOf("textures/blocks/stone.png" to "a"),
            addonDirById = mapOf("a" to winner.absolutePath, "b" to loser.absolutePath),
            mergedBpDir = mergedBp,
            mergedRpDir = mergedRp
        )

        assertEquals(1, result.applied)
        assertEquals(
            "Debe quedar la textura del ganador elegido",
            "TEXTURA-DEL-GANADOR",
            File(mergedRp, "textures/blocks/stone.png").readText()
        )
    }

    @Test
    fun fileInsideAddonSubfolderIsFound() {
        // El addon extraído suele tener subcarpetas (rp/, bp/).
        val winner = addon("addon_a", mapOf("rp/textures/blocks/dirt.png" to "GANADOR"))
        val mergedRp = mergedTree(mapOf("textures/blocks/dirt.png" to "PERDEDOR"))
        val mergedBp = tempDir().apply { mkdirs() }

        val result = ResolutionApplier.apply(
            fileWinners = mapOf("textures/blocks/dirt.png" to "a"),
            addonDirById = mapOf("a" to winner.absolutePath),
            mergedBpDir = mergedBp,
            mergedRpDir = mergedRp
        )

        assertEquals("Debe encontrarlo por sufijo dentro de rp/", 1, result.applied)
        assertEquals("GANADOR", File(mergedRp, "textures/blocks/dirt.png").readText())
    }

    @Test
    fun dedicatedMergeFilesAreNeverReplacedWholesale() {
        // Copiar uno de estos enteros desharía la fusión semántica.
        val dedicated = listOf(
            "sounds.json",
            "sound_definitions.json",
            "blocks.json",
            "biomes_client.json",
            "textures/terrain_texture.json",
            "textures/item_texture.json",
            "textures/flipbook_textures.json",
            "textures/textures_list.json",
            "ui/hud_screen.json",
            "texts/en_US.lang",
            "font/glyph_E1.json",
            "materials/entity.material",
            "manifest.json",
            "player.entity.json"
        )
        dedicated.forEach { path ->
            assertFalse(
                "No debe aplicarse por copia: $path",
                ResolutionApplier.isResolvableByFileCopy(path)
            )
        }
    }

    @Test
    fun identifierDerivedPathsAreSkipped() {
        // El nombre del archivo real no tiene por qué coincidir con el id.
        assertFalse(ResolutionApplier.isResolvableByFileCopy("loot_tables/mypack:chest.json"))
        assertFalse(ResolutionApplier.isResolvableByFileCopy("particles/mypack:dust.json"))
    }

    @Test
    fun plainAssetPathsAreAllowed() {
        assertTrue(ResolutionApplier.isResolvableByFileCopy("textures/blocks/stone.png"))
        assertTrue(ResolutionApplier.isResolvableByFileCopy("models/entity/cube.geo.json"))
        assertTrue(ResolutionApplier.isResolvableByFileCopy("sounds/custom/hit.ogg"))
        assertTrue(ResolutionApplier.isResolvableByFileCopy("particles/dust.json"))
    }

    @Test
    fun missingSourceIsReportedWithoutTouchingAnything() {
        val mergedRp = mergedTree(mapOf("textures/blocks/stone.png" to "ORIGINAL"))
        val mergedBp = tempDir().apply { mkdirs() }
        val emptyAddon = tempDir().apply { mkdirs() }

        val result = ResolutionApplier.apply(
            fileWinners = mapOf("textures/blocks/stone.png" to "ghost"),
            addonDirById = mapOf("ghost" to emptyAddon.absolutePath),
            mergedBpDir = mergedBp,
            mergedRpDir = mergedRp
        )

        assertEquals(0, result.applied)
        assertEquals(listOf("textures/blocks/stone.png"), result.notFound)
        assertEquals(
            "El archivo fusionado no debe alterarse",
            "ORIGINAL",
            File(mergedRp, "textures/blocks/stone.png").readText()
        )
    }

    @Test
    fun fileIsReplacedInTheBehaviorPackWhenItLivesThere() {
        val winner = addon("addon_a", mapOf("entities/zombie.json" to "GANADOR"))
        val mergedBp = mergedTree(mapOf("entities/zombie.json" to "PERDEDOR"))
        val mergedRp = tempDir().apply { mkdirs() }

        val result = ResolutionApplier.apply(
            fileWinners = mapOf("entities/zombie.json" to "a"),
            addonDirById = mapOf("a" to winner.absolutePath),
            mergedBpDir = mergedBp,
            mergedRpDir = mergedRp
        )

        assertEquals(1, result.applied)
        assertEquals("GANADOR", File(mergedBp, "entities/zombie.json").readText())
    }

    @Test
    fun emptyWinnersIsANoOp() {
        val mergedRp = tempDir().apply { mkdirs() }
        val mergedBp = tempDir().apply { mkdirs() }

        val result = ResolutionApplier.apply(
            fileWinners = emptyMap(),
            addonDirById = emptyMap(),
            mergedBpDir = mergedBp,
            mergedRpDir = mergedRp
        )

        assertEquals(0, result.applied)
        assertTrue(result.notFound.isEmpty())
    }

    @Test
    fun multipleWinnersAreAppliedIndependently() {
        // Dos decisiones contradictorias en cuanto a orden: solo se pueden
        // cumplir aplicándolas archivo por archivo.
        val first = addon("addon_a", mapOf("textures/blocks/a.png" to "A-GANA"))
        val second = addon("addon_b", mapOf("textures/blocks/b.png" to "B-GANA"))
        val mergedRp = mergedTree(
            mapOf("textures/blocks/a.png" to "A-PIERDE", "textures/blocks/b.png" to "B-PIERDE")
        )
        val mergedBp = tempDir().apply { mkdirs() }

        val result = ResolutionApplier.apply(
            fileWinners = mapOf(
                "textures/blocks/a.png" to "a",
                "textures/blocks/b.png" to "b"
            ),
            addonDirById = mapOf("a" to first.absolutePath, "b" to second.absolutePath),
            mergedBpDir = mergedBp,
            mergedRpDir = mergedRp
        )

        assertEquals(2, result.applied)
        assertEquals("A-GANA", File(mergedRp, "textures/blocks/a.png").readText())
        assertEquals("B-GANA", File(mergedRp, "textures/blocks/b.png").readText())
    }
}
