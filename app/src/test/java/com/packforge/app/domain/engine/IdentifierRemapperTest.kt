package com.packforge.app.domain.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class IdentifierRemapperTest {

    private val tempRoots = mutableListOf<File>()

    @After
    fun tearDown() {
        tempRoots.forEach { it.deleteRecursively() }
        tempRoots.clear()
    }

    // ── HELPERS ─────────────────────────────────────────────────────────

    private fun tempDir(name: String): File {
        val dir = Files.createTempDirectory("pf_remap_$name").toFile()
        tempRoots.add(dir)
        return dir
    }

    private fun entityFile(dir: File, id: String): File {
        val f = File(dir, "entities/sword.entity.json").apply { parentFile.mkdirs() }
        f.writeText(
            """
            {
              "format_version": "1.16.0",
              "minecraft:entity": {
                "description": { "identifier": "$id" },
                "components": { "minecraft:health": { "value": 20 } }
              }
            }
            """.trimIndent()
        )
        return f
    }

    private fun langFile(dir: File, id: String): File {
        val f = File(dir, "texts/es_ES.lang").apply { parentFile.mkdirs() }
        f.writeText("entity.$id.name=Espada x\nitem.$id.name=Espada x\n")
        return f
    }

    private fun clientEntity(dir: File, id: String): File {
        val f = File(dir, "entity/sword.client.json").apply { parentFile.mkdirs() }
        f.writeText(
            """
            {
              "format_version": "1.10.0",
              "minecraft:client_entity": {
                "description": { "identifier": "$id", "geometry": { "default": "geometry.sword" } }
              }
            }
            """.trimIndent()
        )
        return f
    }

    // ── TESTS ───────────────────────────────────────────────────────────

    @Test
    fun twoAddonsWithSameCustomId_remapsLoserAndRewritesItsFiles() {
        // Addon A y Addon B definen el MISMO id custom.
        val dirA = tempDir("addon_a")
        val dirB = tempDir("addon_b")
        entityFile(dirA, "tools:sword")
        entityFile(dirB, "tools:sword")
        langFile(dirA, "tools:sword")
        langFile(dirB, "tools:sword")

        val report = IdentifierRemapper.run(listOf(dirA, dirB), emptyList())

        // El ganador (A) NO se toca...
        val fileA = File(dirA, "entities/sword.entity.json")
        assertTrue("El addon ganador no debe renombrarse", fileA.readText().contains("tools:sword"))

        // ...y el perdedor (B) se renombra + reescribe sus referencias.
        assertEquals("Debe haber 1 rename", 1, report.size)
        assertEquals("tools:sword", report[0].oldId)
        assertTrue(
            "Nuevo id debe usar namespace pf: ${report[0].newId}",
            report[0].newId.matches(Regex("^pf:[0-9a-f]{6}_sword$"))
        )

        // El entity de B fue RENOMBRADO para no chocar con A.
        val entityB = dirB.walkTopDown()
            .first { it.parentFile?.name == "entities" && it.extension.equals("json", ignoreCase = true) }
        assertFalse(
            "El id original debe desaparecer del addon perdedor",
            entityB.readText().contains("tools:sword")
        )
        assertTrue("El nuevo id debe estar en el entity B", entityB.readText().contains(report[0].newId))
        assertTrue("La referencia .lang debe reescribirse", File(dirB, "texts/es_ES.lang").readText().contains(report[0].newId))
        assertFalse("El .lang del ganador no se reescribe", File(dirA, "texts/es_ES.lang").readText().contains(report[0].newId))
    }

    @Test
    fun collidingFilePathsAreRenamedToCoexist() {
        val dirA = tempDir("addon_a")
        val dirB = tempDir("addon_b")
        entityFile(dirA, "tools:sword")
        entityFile(dirB, "tools:sword")

        IdentifierRemapper.run(listOf(dirA, dirB), emptyList())

        // Tras el renombre, B ya NO pisa la ruta de A en la fusión.
        val remainingB = dirB.walkTopDown()
            .filter { it.parentFile?.name == "entities" && it.extension.equals("json", ignoreCase = true) }
            .toList()
        assertTrue("B debe conservar su entity (renombrado)", remainingB.isNotEmpty())
        remainingB.forEach { f ->
            assertFalse("La ruta de B no debe chocar con la de A", f.name == "sword.entity.json")
        }
        // Y A conserva la ruta original.
        assertTrue(File(dirA, "entities/sword.entity.json").exists())
    }

    @Test
    fun vanillaIdentifiersAreNeverRemapped() {
        val dirA = tempDir("addon_a")
        val dirB = tempDir("addon_b")
        entityFile(dirA, "minecraft:sword")
        entityFile(dirB, "minecraft:sword")

        val report = IdentifierRemapper.run(listOf(dirA, dirB), emptyList())

        assertTrue("Los ids vanilla no se renombran (evita romper la paridad)", report.isEmpty())
        assertTrue(File(dirA, "entities/sword.entity.json").readText().contains("minecraft:sword"))
        assertTrue(File(dirB, "entities/sword.entity.json").readText().contains("minecraft:sword"))
    }

    @Test
    fun sameAddonBpAndRpAreGrouped_NoFalseCollision() {
        // Un addon BOTH aparece como separated_bp_TS + separated_rp_TS (mismo TS).
        val ts = System.currentTimeMillis()
        val root = tempDir("sep")
        val bp = File(root, "separated_bp_$ts").apply { mkdirs() }
        val rp = File(root, "separated_rp_$ts").apply { mkdirs() }
        entityFile(bp, "tools:golem")
        clientEntity(rp, "tools:golem")

        val report = IdentifierRemapper.run(listOf(bp), listOf(rp))

        assertTrue("El BP y el RP del MISMO addon NO deben considerarse colisión", report.isEmpty())
        assertTrue(File(bp, "entities/sword.entity.json").readText().contains("tools:golem"))
        assertTrue(File(rp, "entity/sword.client.json").readText().contains("tools:golem"))
    }

    @Test
    fun threeAddonsWithSameId_getsTwoUniqueRemaps() {
        val dirA = tempDir("addon_a")
        val dirB = tempDir("addon_b")
        val dirC = tempDir("addon_c")
        entityFile(dirA, "tools:tool")
        entityFile(dirB, "tools:tool")
        entityFile(dirC, "tools:tool")

        val report = IdentifierRemapper.run(listOf(dirA, dirB, dirC), emptyList())

        assertEquals("Los dos perdedores se renombran", 2, report.size)
        assertEquals("Cada renombrado debe ser único", report.size, report.map { it.newId }.distinct().size)
        // El ganador queda intacto.
        assertTrue(File(dirA, "entities/sword.entity.json").readText().contains("tools:tool"))
    }
}