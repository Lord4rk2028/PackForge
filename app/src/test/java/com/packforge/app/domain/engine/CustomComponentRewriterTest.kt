package com.packforge.app.domain.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Tests de [CustomComponentRewriter].
 *
 * En Bedrock, registrar dos veces el mismo `registerCustomComponent('x', …)`
 * es un error fatal: no basta con "que gane uno", hay que renombrar el del
 * addon perdedor y sincronizar sus JSON de bloques/ítems.
 */
class CustomComponentRewriterTest {

    private val roots = mutableListOf<File>()

    @After
    fun cleanUp() {
        roots.forEach { it.deleteRecursively() }
    }

    private fun tempDir(): File =
        Files.createTempDirectory("pf_ccr_").toFile().also { roots += it }

    private fun pack(
        dirName: String,
        script: String,
        itemJson: String? = null,
        uuid: String
    ): File {
        val root = File(tempDir(), dirName).apply { mkdirs() }
        File(root, "manifest.json").writeText(
            """
            {
              "format_version": 2,
              "header": { "name": "$dirName", "uuid": "$uuid", "version": [1,0,0], "min_engine_version": [1,20,0] },
              "modules": [ { "type": "script", "language": "javascript", "uuid": "$uuid-m", "entry": "main.js", "version": [1,0,0] } ]
            }
            """.trimIndent()
        )
        File(root, "main.js").writeText(script)
        if (itemJson != null) {
            val items = File(root, "items").apply { mkdirs() }
            File(items, "custom_item.json").writeText(itemJson)
        }
        return root
    }

    @Test
    fun detectsDuplicateComponentsBetweenPacks() {
        val first = pack(
            "pack_a",
            """registerCustomComponent('forge:weapon', {})""",
            uuid = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        )
        val second = pack(
            "pack_b",
            """registerCustomComponent('forge:weapon', {})""",
            uuid = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        )

        val duplicates = ScriptFlowAnalyzer.findDuplicateCustomComponents(
            listOf(first.absolutePath, second.absolutePath)
        )

        // Un dueño por pack: el componente está registrado en dos addons.
        assertEquals(2, duplicates.size)
        assertTrue(duplicates.all { it.componentName == "forge:weapon" })
        assertEquals(2, duplicates.map { it.packPath }.distinct().size)
    }

    @Test
    fun onlyLoserPackIsRewritten() {
        val winner = pack(
            "pack_a",
            """registerCustomComponent('forge:weapon', { onUse() {} })""",
            uuid = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        )
        val loser = pack(
            "pack_b",
            """registerCustomComponent('forge:weapon', { onAttack() {} })""",
            uuid = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        )

        val result = CustomComponentRewriter.rewrite(
            listOf(winner.absolutePath, loser.absolutePath)
        )

        assertEquals(1, result.renames.size)
        val newName = result.renames.getValue("forge:weapon")
        assertTrue("El nuevo nombre debe llevar sufijo estable", newName.startsWith("forge:weapon_pf"))

        // El ganador (primero en la lista) conserva el nombre original.
        val winnerScript = File(winner, "main.js").readText()
        assertTrue(winnerScript.contains("registerCustomComponent('forge:weapon'"))
        assertFalse(winnerScript.contains(newName))

        // El perdedor queda renombrado.
        val loserScript = File(loser, "main.js").readText()
        assertTrue(loserScript.contains(newName))
        assertFalse(loserScript.contains("registerCustomComponent('forge:weapon'"))
        assertEquals(1, result.totalRewrites)
    }

    @Test
    fun itemJsonReferenceIsSynchronized() {
        val winner = pack(
            "pack_a",
            """registerCustomComponent('forge:weapon', {})""",
            uuid = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        )
        val loser = pack(
            "pack_b",
            """registerCustomComponent('forge:weapon', {})""",
            itemJson = """{"minecraft:item":{"description":{"identifier":"pf:sword"},"components":{"minecraft:custom_component":"forge:weapon"}}}""",
            uuid = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        )

        val result = CustomComponentRewriter.rewrite(
            listOf(winner.absolutePath, loser.absolutePath)
        )
        val newName = result.renames.getValue("forge:weapon")

        val itemText = File(loser, "items/custom_item.json").readText()
        assertTrue("El JSON debe apuntar al componente renombrado", itemText.contains(newName))
    }

    @Test
    fun rewriteIsIdempotent() {
        val winner = pack(
            "pack_a",
            """registerCustomComponent('forge:weapon', {})""",
            uuid = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        )
        val loser = pack(
            "pack_b",
            """registerCustomComponent('forge:weapon', {})""",
            uuid = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        )

        val first = CustomComponentRewriter.rewrite(
            listOf(winner.absolutePath, loser.absolutePath)
        )
        val second = CustomComponentRewriter.rewrite(
            listOf(winner.absolutePath, loser.absolutePath)
        )

        assertTrue(first.totalRewrites > 0)
        // Tras el primer renombrado ya no hay colisión: la segunda pasada no
        // debe encadenar sufijos ni volver a tocar el archivo.
        assertEquals(0, second.totalRewrites)
        assertTrue(second.renames.isEmpty())

        val loserScript = File(loser, "main.js").readText()
        assertEquals(
            "No debe duplicarse el sufijo",
            1,
            Regex("""_pf[0-9a-f]{4}""").findAll(loserScript).count()
        )
    }

    @Test
    fun noDuplicatesReturnsEmptyResult() {
        val only = pack(
            "pack_a",
            """registerCustomComponent('forge:weapon', {})""",
            uuid = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        )

        val result = CustomComponentRewriter.rewrite(listOf(only.absolutePath))

        assertEquals(0, result.totalRewrites)
        assertTrue(result.renames.isEmpty())
        assertTrue(result.affectedFiles.isEmpty())
    }
}
