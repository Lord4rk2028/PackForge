package com.packforge.app.domain.engine

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * C — Escenarios end-to-end con las ESTRUCTURAS REALES de los addons.
 *
 * Los tres bugs más serios de las últimas rondas los encontraron tests como
 * estos, no la lectura de código: el pipeline se comporta distinto según cómo
 * venga empaquetado el addon.
 */
class MergePipelineEdgeCasesTest {

    private val roots = mutableListOf<File>()

    @After
    fun cleanUp() {
        roots.forEach { it.deleteRecursively() }
    }

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { roots += it }

    // ── Utilidades ───────────────────────────────────────────────────────

    private fun manifest(uuid: String, name: String, type: String) = """
        {"format_version":2,"header":{"name":"$name","description":"desc de $name","uuid":"$uuid",
        "version":[1,0,0],"min_engine_version":[1,20,0]},
        "modules":[{"type":"$type","uuid":"$uuid-m","version":[1,0,0]}]}
    """.trimIndent()

    private fun zipBytes(entries: List<Pair<String, ByteArray>>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            entries.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private fun text(value: String) = value.toByteArray()

    private fun addon(dir: File, fileName: String, entries: List<Pair<String, ByteArray>>): File {
        val file = File(dir, fileName)
        file.writeBytes(zipBytes(entries))
        return file
    }

    private fun merge(
        work: File,
        addons: List<File>,
        names: List<String> = addons.map { it.nameWithoutExtension }
    ): PackForgeOrchestrator.MergeResult = runBlocking {
        PackForgeOrchestrator.mergeAddons(
            addonPaths = addons.map { it.absolutePath },
            outputDir = work.absolutePath,
            addonNames = names,
            customName = "CASE",
            addonIds = addons.map { it.nameWithoutExtension }
        )
    }

    /** Contenido del .mcaddon resultante: ruta relativa → texto. */
    private fun output(zipFile: File): Map<String, String> {
        val result = linkedMapOf<String, String>()
        ZipFile(zipFile).use { zip ->
            zip.entries().toList()
                .filter { !it.isDirectory }
                .forEach { entry ->
                    val name = entry.name.replace('\\', '/')
                    result[name] = zip.getInputStream(entry).use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }
                }
        }
        return result
    }

    private fun findEntry(files: Map<String, String>, suffix: String): Map.Entry<String, String>? =
        files.entries.firstOrNull { it.key.endsWith(suffix) }

    // ── 1. .mcaddon con BP.mcpack + RP.mcpack dentro (caso canónico) ─────

    @Test
    fun mcaddonContainingBpAndRpPacksMergesBoth() {
        val work = tempDir("pf_case1_")
        val srcDir = tempDir("pf_case1_src_")

        val bpPack = zipBytes(
            listOf(
                "manifest.json" to text(manifest("aaaa1111-0000-0000-0000-000000000001", "Inner BP", "data")),
                "items/inner_item.json" to text(
                    """{"format_version":"1.20.10","minecraft:item":{"description":{"identifier":"inner:item"},"components":{"minecraft:icon":"inner"}}}"""
                )
            )
        )
        val rpPack = zipBytes(
            listOf(
                "manifest.json" to text(manifest("aaaa1111-0000-0000-0000-000000000002", "Inner RP", "resources")),
                "textures/blocks/inner.png" to text("PNG-INNER-RP")
            )
        )

        val addon = addon(
            srcDir, "more_tools.mcaddon",
            listOf(
                "BP.mcpack" to bpPack,
                "RP.mcpack" to rpPack
            )
        )

        val result = merge(work, listOf(addon))

        assertTrue("Debe fusionar: ${result.errorMessage}", result.success)
        val files = output(File(result.outputPath!!))

        assertNotNull(
            "El BP del .mcpack anidado debe llegar: ${files.keys}",
            findEntry(files, "items/inner_item.json")
        )
        assertNotNull(
            "El RP del .mcpack anidado debe llegar: ${files.keys}",
            findEntry(files, "textures/blocks/inner.png")
        )
    }

    // ── 2. Varios packs con interfaz (ui/) ───────────────────────────────

    @Test
    fun uiScreensFromSeveralPacksAreAllRegistered() {
        val work = tempDir("pf_case2_")
        val srcDir = tempDir("pf_case2_src_")

        val packA = addon(
            srcDir, "a.mcaddon",
            listOf(
                "rp/manifest.json" to text(manifest("bbbb2222-0000-0000-0000-000000000001", "A", "resources")),
                "rp/ui/_ui_defs.json" to text("""{"ui_defs":["ui/hud_screen.json"]}"""),
                "rp/ui/hud_screen.json" to text("""{"namespace":"hud_a"}""")
            )
        )
        val packB = addon(
            srcDir, "b.mcaddon",
            listOf(
                "rp/manifest.json" to text(manifest("bbbb2222-0000-0000-0000-000000000002", "B", "resources")),
                "rp/ui/_ui_defs.json" to text("""{"ui_defs":["ui/mana_screen.json"]}"""),
                "rp/ui/mana_screen.json" to text("""{"namespace":"mana_b"}""")
            )
        )

        val result = merge(work, listOf(packA, packB))

        assertTrue("Debe fusionar: ${result.errorMessage}", result.success)
        val files = output(File(result.outputPath!!))

        val defs = findEntry(files, "ui/_ui_defs.json")
        assertNotNull("Debe existir _ui_defs.json fusionado: ${files.keys}", defs)
        val defsList = JSONObject(defs!!.value).getJSONArray("ui_defs")
        val declared = (0 until defsList.length()).map { defsList.getString(it) }

        assertTrue("Debe registrar la pantalla de A: $declared", declared.any { it.endsWith("hud_screen.json") })
        assertTrue("Debe registrar la pantalla de B: $declared", declared.any { it.endsWith("mana_screen.json") })

        assertNotNull("La pantalla de A debe estar", findEntry(files, "ui/hud_screen.json"))
        assertNotNull("La pantalla de B debe estar", findEntry(files, "ui/mana_screen.json"))
    }

    // ── 3. player.entity.json en dos addons ──────────────────────────────

    /**
     * Dos addons personalizando el jugador deben CONSERVARSE ambos.
     *
     * Regresión: `mergePlayerEntity` solo aceptaba archivos con
     * `minecraft:entity` (entidad de servidor), así que los overrides de
     * `minecraft:client_entity` —donde están las animaciones y la geometría—
     * se descartaban y quedaban DOS archivos sin fusionar
     * (`player.entity.json` + `player.entity_pf1.json`), perdiendo uno.
     */
    @Test
    fun playerEntityFromTwoAddonsKeepsBothContributions() {
        val work = tempDir("pf_case3_")
        val srcDir = tempDir("pf_case3_src_")

        val packA = addon(
            srcDir, "a.mcaddon",
            listOf(
                "bp/manifest.json" to text(manifest("cccc3333-0000-0000-0000-000000000001", "A", "data")),
                "bp/player.entity.json" to text(
                    """{"format_version":"1.10.0","minecraft:client_entity":{"description":{"identifier":"minecraft:player","animations":{"walk_a":"animation.a.walk"}}}}"""
                )
            )
        )
        val packB = addon(
            srcDir, "b.mcaddon",
            listOf(
                "bp/manifest.json" to text(manifest("cccc3333-0000-0000-0000-000000000002", "B", "data")),
                "bp/player.entity.json" to text(
                    """{"format_version":"1.10.0","minecraft:client_entity":{"description":{"identifier":"minecraft:player","animations":{"walk_b":"animation.b.walk"}}}}"""
                )
            )
        )

        val result = merge(work, listOf(packA, packB))

        assertTrue("Debe fusionar: ${result.errorMessage}", result.success)
        val files = output(File(result.outputPath!!))

        val player = findEntry(files, "player.entity.json")
        assertNotNull("player.entity.json debe existir en el resultado: ${files.keys}", player)

        // Es JSON válido y conserva la identidad del jugador.
        val json = JSONObject(player!!.value)
        val description = json.optJSONObject("minecraft:client_entity")
            ?.optJSONObject("description")
        assertNotNull("Debe conservar la definición del jugador", description)
        assertEquals("minecraft:player", description!!.optString("identifier"))

        // Lo importante: NO debe perderse la aportación de ninguno de los dos.
        val animations = description.optJSONObject("animations")
        assertNotNull("Debe haber animaciones tras la fusión", animations)
        val keys = animations!!.keys().asSequence().toList()
        assertTrue(
            "Debe conservar la animación del addon A: $keys",
            keys.any { it.contains("a") }
        )
        assertTrue(
            "Debe conservar la animación del addon B: $keys",
            keys.any { it.contains("b") }
        )
    }

    // ── 4. Addon corrupto junto a uno válido ─────────────────────────────

    @Test
    fun corruptAddonDoesNotBreakTheMergeOfTheValidOne() {
        val work = tempDir("pf_case4_")
        val srcDir = tempDir("pf_case4_src_")

        val valid = addon(
            srcDir, "valid.mcaddon",
            listOf(
                "rp/manifest.json" to text(manifest("dddd4444-0000-0000-0000-000000000001", "Valid", "resources")),
                "rp/textures/blocks/valid.png" to text("PNG-VALID")
            )
        )
        // Archivo con extensión de addon pero contenido basura.
        val corrupt = File(srcDir, "corrupt.mcaddon")
        corrupt.writeText("ESTO NO ES UN ZIP")

        val result = merge(work, listOf(corrupt, valid))

        assertTrue(
            "Un addon corrupto no debe tumbar la fusión del válido: ${result.errorMessage}",
            result.success
        )
        val files = output(File(result.outputPath!!))
        assertNotNull(
            "El addon válido debe llegar igualmente: ${files.keys}",
            findEntry(files, "textures/blocks/valid.png")
        )
    }

    @Test
    fun onlyCorruptAddonsFailGracefullyWithoutThrowing() {
        val work = tempDir("pf_case5_")
        val srcDir = tempDir("pf_case5_src_")

        val corrupt = File(srcDir, "corrupt.mcaddon")
        corrupt.writeText("TAMPOCO ES UN ZIP")

        val result = merge(work, listOf(corrupt))

        assertTrue(
            "Debe fallar de forma controlada, no lanzar excepción",
            !result.success
        )
        assertNotNull("Debe explicar el motivo", result.errorMessage)
    }
}
