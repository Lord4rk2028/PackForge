package com.packforge.app.domain.engine

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class IdentifierRemapperTest {

    private val tempRoots = mutableListOf<File>()

    /**
     * `IdentifierRemapper.run` pasó a ser `suspend` (su callback de progreso
     * también), pero este test seguía invocándolo de forma directa, así que el
     * módulo de tests no compilaba. Este envoltorio mantiene los tests como
     * funciones normales de JUnit, que no admiten `suspend`.
     */
    private fun remap(
        behaviorDirs: List<File>,
        resourceDirs: List<File>
    ): List<IdentifierRemapper.RemapEntry> =
        runBlocking { IdentifierRemapper.run(behaviorDirs, resourceDirs) }

    @After
    fun tearDown() {
        tempRoots.forEach { it.deleteRecursively() }
        tempRoots.clear()
        AddonOwnerRegistry.clear()
        ConflictRegistry.clear()
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

    private fun blockFile(dir: File, id: String): File {
        val f = File(dir, "blocks/shiny.json").apply { parentFile.mkdirs() }
        f.writeText(
            """
            {
              "format_version": "1.20.10",
              "minecraft:block": {
                "description": { "identifier": "$id" },
                "components": {}
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

    private fun manifestFile(dir: File, uuid: String) {
        val f = File(dir, "manifest.json")
        f.writeText(
            """
            {"format_version":2,"header":{"name":"pack","description":"bp","uuid":"$uuid",
            "version":[1,0,0],"min_engine_version":[1,20,0]},
            "modules":[{"type":"data","uuid":"$uuid-m","version":[1,0,0]}]}
            """.trimIndent()
        )
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

        val report = remap(listOf(dirA, dirB), emptyList())

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

        remap(listOf(dirA, dirB), emptyList())

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

        val report = remap(listOf(dirA, dirB), emptyList())

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

        val report = remap(listOf(bp), listOf(rp))

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

        val report = remap(listOf(dirA, dirB, dirC), emptyList())

        assertEquals("Los dos perdedores se renombran", 2, report.size)
        assertEquals("Cada renombrado debe ser único", report.size, report.map { it.newId }.distinct().size)
        // El ganador queda intacto.
        assertTrue(File(dirA, "entities/sword.entity.json").readText().contains("tools:tool"))
    }

    @Test
    fun renamingABlockAlsoRewritesItsKeysInTheCatalogs() {
        // El id del bloque no solo vive en `description.identifier`: es CLAVE en
        // `blocks.json` y en `texture_data`. Si no se reescribe, el bloque del
        // perdedor queda renombrado pero sin mapeo de textura (bloque “?”).
        val bpA = tempDir("keys_bp_a")
        val bpB = tempDir("keys_bp_b")
        val rpA = tempDir("keys_rp_a")
        val rpB = tempDir("keys_rp_b")
        blockFile(bpA, "shiny:block")
        blockFile(bpB, "shiny:block")

        fun catalogs(dir: File, textura: String) {
            File(dir, "blocks.json").writeText(
                """{"format_version":[1,1,0],"shiny:block":{"textures":"$textura","sound":"stone"}}"""
            )
            File(dir, "textures/terrain_texture.json").apply { parentFile?.mkdirs() }.writeText(
                """{"texture_data":{"shiny:block":{"textures":"textures/blocks/$textura"}}}"""
            )
        }
        catalogs(rpA, "shiny_a")
        catalogs(rpB, "shiny_b")

        AddonOwnerRegistry.register(bpA, "a", "Addon A")
        AddonOwnerRegistry.register(rpA, "a", "Addon A")
        AddonOwnerRegistry.register(bpB, "b", "Addon B")
        AddonOwnerRegistry.register(rpB, "b", "Addon B")

        val report = remap(listOf(bpA, bpB), listOf(rpA, rpB))
        val nuevo = report.firstOrNull { it.oldId == "shiny:block" }?.newId
        assertNotNull("El bloque debe renombrarse", nuevo)

        val blocksB = JSONObject(File(rpB, "blocks.json").readText())
        assertTrue("blocks.json del perdedor debe usar la clave nueva: $blocksB", blocksB.has(nuevo))
        assertFalse("La clave vieja no puede quedar en blocks.json", blocksB.has("shiny:block"))

        val atlasB = JSONObject(File(rpB, "textures/terrain_texture.json").readText())
            .getJSONObject("texture_data")
        assertTrue("texture_data del perdedor debe usar la clave nueva", atlasB.has(nuevo))
        assertFalse("La clave vieja no puede quedar en texture_data", atlasB.has("shiny:block"))

        // El ganador no se toca, ni en valores ni en claves.
        val blocksA = JSONObject(File(rpA, "blocks.json").readText())
        assertTrue("blocks.json del ganador intacto", blocksA.has("shiny:block"))
        assertFalse("El ganador no debe recibir el id nuevo", blocksA.has(nuevo))
    }
    // ── 3.11: estabilidad de los tokens `pf:<hex>` ────────────────────────

    /**
     * El token del id renombrado tiene que ser el MISMO aunque el addon se
     * extraiga en otra carpeta (`extracted_<epochMs>_…`). Antes salía de
     * `ownerKey.hashCode()` y esa clave era la ruta extraída.
     */
    @Test
    fun renamedTokenIsStableAcrossDifferentExtractionDirectories() {
        fun tokenEn(nombreA: String, nombreB: String): String {
            val root = tempDir("run_${System.nanoTime()}")
            val a = File(root, nombreA).apply { mkdirs() }
            val b = File(root, nombreB).apply { mkdirs() }
            manifestFile(a, "11111111-1111-1111-1111-111111111111")
            manifestFile(b, "22222222-2222-2222-2222-222222222222")
            entityFile(a, "tools:sword")
            entityFile(b, "tools:sword")
            // Como hace el orquestador: semilla derivada del manifiesto.
            AddonOwnerRegistry.register(a, "addon_0", "A", AddonOwnerRegistry.stableSeed(listOf(a), "A"))
            AddonOwnerRegistry.register(b, "addon_1", "B", AddonOwnerRegistry.stableSeed(listOf(b), "B"))

            val report = remap(listOf(a, b), emptyList())
            AddonOwnerRegistry.clear()
            return report.single().newId
        }

        assertEquals(
            "El token no puede depender de la ruta de extracción",
            tokenEn("extracted_111_first", "extracted_222_second"),
            tokenEn("extracted_999_first", "extracted_888_second")
        )
    }

    @Test
    fun differentAddonsProduceDifferentTokens() {
        fun tokenDelPerdedor(uuidPerdedor: String): String {
            val root = tempDir("run_${System.nanoTime()}")
            val a = File(root, "extracted_1_win").apply { mkdirs() }
            val b = File(root, "extracted_2_lose").apply { mkdirs() }
            manifestFile(a, "33333333-3333-3333-3333-333333333333")
            manifestFile(b, uuidPerdedor)
            entityFile(a, "tools:sword")
            entityFile(b, "tools:sword")
            AddonOwnerRegistry.register(a, "addon_0", "A", AddonOwnerRegistry.stableSeed(listOf(a), "A"))
            AddonOwnerRegistry.register(b, "addon_1", "B", AddonOwnerRegistry.stableSeed(listOf(b), "B"))

            val report = remap(listOf(a, b), emptyList())
            AddonOwnerRegistry.clear()
            return report.single().newId
        }

        assertNotEquals(
            tokenDelPerdedor("44444444-4444-4444-4444-444444444444"),
            tokenDelPerdedor("55555555-5555-5555-5555-555555555555")
        )
    }
}
