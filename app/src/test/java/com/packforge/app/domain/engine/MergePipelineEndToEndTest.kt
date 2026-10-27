package com.packforge.app.domain.engine

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * P4: prueba END-TO-END del pipeline completo de fusión.
 *
 * Los tests unitarios validan cada pieza por separado; este comprueba que
 * **todo junto** produce un modpack coherente con tres addons que solapan en
 * textura, partícula, sonido y bioma — el escenario que rompía en silencio
 * antes (contenido que se sobrescribía y desaparecía).
 */
class MergePipelineEndToEndTest {

    private val roots = mutableListOf<File>()

    @After
    fun cleanUp() {
        roots.forEach { it.deleteRecursively() }
    }

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { roots += it }

    // ── Construcción de addons (.mcaddon con subcarpetas bp/ y rp/) ──────

    private fun bpManifest(uuid: String, name: String) = """
        {"format_version":2,"header":{"name":"$name BP","description":"bp","uuid":"$uuid",
        "version":[1,0,0],"min_engine_version":[1,20,0]},
        "modules":[{"type":"data","uuid":"$uuid-m","version":[1,0,0]}]}
    """.trimIndent()

    private fun rpManifest(uuid: String, name: String) = """
        {"format_version":2,"header":{"name":"$name RP","description":"rp","uuid":"$uuid",
        "version":[1,0,0],"min_engine_version":[1,20,0]},
        "modules":[{"type":"resources","uuid":"$uuid-m","version":[1,0,0]}]}
    """.trimIndent()

    private fun particle(identifier: String, texture: String) = """
        {"format_version":"1.10.0","particle_effect":{"description":{"identifier":"$identifier",
        "basic_render_parameters":{"material":"particles_alpha","texture":"$texture"}},
        "components":{"minecraft:particle_lifetime_expression":{"max_lifetime":1.0}}}}
    """.trimIndent()

    private fun addon(
        fileName: String,
        uuid: String,
        name: String,
        bpFiles: Map<String, String> = emptyMap(),
        rpFiles: Map<String, String> = emptyMap()
    ): File {
        val file = File(tempDir("pf_e2e_"), fileName)
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
            put("bp/manifest.json", bpManifest(uuid, name))
            bpFiles.forEach { (rel, content) -> put("bp/$rel", content) }
            put("rp/manifest.json", rpManifest("$uuid-rp", name))
            rpFiles.forEach { (rel, content) -> put("rp/$rel", content) }
        }
        return file
    }

    // ── Utilidades para leer el resultado ────────────────────────────────

    private fun entryText(zip: ZipFile, suffix: String): String? {
        val entry = zip.entries().toList().firstOrNull {
            !it.isDirectory && it.name.replace('\\', '/').endsWith(suffix)
        } ?: return null
        return zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun entryNames(zip: ZipFile): List<String> =
        zip.entries().toList().filter { !it.isDirectory }.map { it.name.replace('\\', '/') }

    @Test
    fun threeOverlappingAddonsProduceACoherentModpack() = runBlocking {
        val work = tempDir("pf_work_")

        val addonA = addon(
            fileName = "a.mcaddon",
            uuid = "11111111-1111-1111-1111-111111111111",
            name = "Alpha",
            bpFiles = mapOf(
                "items/a_item.json" to
                    """{"format_version":"1.20.10","minecraft:item":{"description":{"identifier":"alpha:item"},"components":{"minecraft:icon":"alpha_icon"}}}"""
            ),
            rpFiles = mapOf(
                "particles/dust.json" to particle("custom:dust", "textures/particle/dust_a"),
                "sound_definitions.json" to
                    """{"format_version":1,"sound.shared":{"category":"block","sounds":[{"name":"sounds/alpha"}]}}""",
                "biomes_client.json" to
                    """{"biomes":{"custom:alpha":{"water_surface_color":"#111111"}}}""",
                "textures/blocks/shared.png" to "PNG-A",
                "textures/blocks/only_a.png" to "PNG-ONLY-A"
            )
        )

        val addonB = addon(
            fileName = "b.mcaddon",
            uuid = "22222222-2222-2222-2222-222222222222",
            name = "Beta",
            bpFiles = mapOf(
                "scripts/main.js" to "registerCustomComponent('forge:weapon', {})",
                "items/b_item.json" to
                    """{"format_version":"1.20.10","minecraft:item":{"description":{"identifier":"beta:item"},"components":{"minecraft:custom_component":"forge:weapon"}}}"""
            ),
            rpFiles = mapOf(
                // MISMA partícula, definición distinta → debe desambiguarse.
                "particles/dust.json" to particle("custom:dust", "textures/particle/dust_b"),
                // MISMO sonido con cuerpo distinto → alias, sin perder ninguno.
                "sound_definitions.json" to
                    """{"format_version":1,"sound.shared":{"category":"player","sounds":[{"name":"sounds/beta"}]},"sound.beta_only":{"category":"block","sounds":[{"name":"sounds/beta_only"}]}}""",
                // MISMO bioma con config distinta + uno nuevo.
                "biomes_client.json" to
                    """{"biomes":{"custom:alpha":{"water_surface_color":"#999999"},"custom:beta":{"water_surface_color":"#222222"}}}""",
                "textures/blocks/shared.png" to "PNG-B"
            )
        )

        val addonC = addon(
            fileName = "c.mcaddon",
            uuid = "33333333-3333-3333-3333-333333333333",
            name = "Gamma",
            bpFiles = mapOf(
                "items/c_item.json" to
                    """{"format_version":"1.20.10","minecraft:item":{"description":{"identifier":"gamma:item"},"components":{"minecraft:icon":"gamma_icon"}}}"""
            ),
            rpFiles = mapOf(
                "particles/spark.json" to particle("custom:spark", "textures/particle/spark"),
                "sound_definitions.json" to
                    """{"format_version":1,"sound.gamma_only":{"category":"block","sounds":[{"name":"sounds/gamma"}]}}""",
                "textures/blocks/only_c.png" to "PNG-ONLY-C"
            )
        )

        val result = PackForgeOrchestrator.mergeAddons(
            addonPaths = listOf(addonA.absolutePath, addonB.absolutePath, addonC.absolutePath),
            outputDir = work.absolutePath,
            addonNames = listOf("Alpha", "Beta", "Gamma"),
            customName = "E2E_Modpack",
            customAuthor = "Tester",
            customVersion = "1.2.3",
            customDescription = "Prueba end-to-end",
            addonIds = listOf("a", "b", "c")
        )

        assertTrue(
            "La fusión debe terminar bien: ${result.errorMessage}",
            result.success
        )
        val output = result.outputPath?.let { File(it) }
        assertNotNull("Debe producirse un .mcaddon", output)
        assertTrue("El archivo debe existir", output!!.isFile)

        ZipFile(output).use { zip ->
            val names = entryNames(zip)

            // 1. Nada se pierde: cada addon aporta su textura exclusiva.
            assertTrue(
                "Falta la textura exclusiva de Alpha: $names",
                names.any { it.endsWith("textures/blocks/only_a.png") }
            )
            assertTrue(
                "Falta la textura exclusiva de Gamma",
                names.any { it.endsWith("textures/blocks/only_c.png") }
            )

            // 2. Sonidos: los tres addons aportan, y el duplicado se aliasa.
            val sounds = JSONObject(entryText(zip, "sound_definitions.json")!!)
            assertTrue("Falta sound.beta_only", sounds.has("sound.beta_only"))
            assertTrue("Falta sound.gamma_only", sounds.has("sound.gamma_only"))
            assertTrue(
                "El sonido idéntico en nombre debe conservar el original",
                sounds.has("sound.shared")
            )
            val soundAlias = sounds.keys().asSequence()
                .firstOrNull { it.startsWith("sound.shared_pf") }
            assertNotNull("El sonido conflictivo debe tener alias", soundAlias)

            // 3. Biomas: los dos conviven (el duplicado conserva el primero).
            val biomes = JSONObject(entryText(zip, "biomes_client.json")!!)
                .getJSONObject("biomes")
            assertTrue("Falta custom:alpha", biomes.has("custom:alpha"))
            assertTrue("Falta custom:beta", biomes.has("custom:beta"))

            // 4. Partículas: las tres definiciones sobreviven con ids distintos.
            val particleIdentifiers = names
                .filter { it.contains("/particles/") && it.endsWith(".json") }
                .mapNotNull { name ->
                    val text = zip.getInputStream(zip.getEntry(name)).use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }
                    runCatching {
                        JSONObject(text).getJSONObject("particle_effect")
                            .getJSONObject("description").getString("identifier")
                    }.getOrNull()
                }
            assertEquals("Deben llegar las 3 partículas: $particleIdentifiers", 3, particleIdentifiers.size)
            assertEquals("Los identificadores deben ser únicos", 3, particleIdentifiers.distinct().size)
            assertTrue(particleIdentifiers.contains("custom:dust"))
            assertTrue(particleIdentifiers.contains("custom:spark"))
            assertTrue(
                "El duplicado debe llevar sufijo de desambiguación",
                particleIdentifiers.any { it.startsWith("custom:dust_pf") }
            )

            // 5. Manifiestos con los metadatos del usuario (antes el RP salía
            //    siempre como "PackForge Modpack" y sin autor).
            val rpManifest = JSONObject(
                zip.getInputStream(
                    zip.getEntry(
                        names.first {
                            it.endsWith("manifest.json") &&
                                it.lowercase().contains("rp_")
                        }
                    )
                ).use { it.readBytes().toString(Charsets.UTF_8) }
            )
            assertEquals("E2E_Modpack", rpManifest.getJSONObject("header").getString("name"))
            assertEquals(
                "Prueba end-to-end",
                rpManifest.getJSONObject("header").getString("description")
            )
            assertEquals(
                "Tester",
                rpManifest.getJSONObject("metadata").getJSONArray("authors").getString(0)
            )
        }
    }

    @Test
    fun mergedModpackHasNoBrokenReferences() = runBlocking {
        val work = tempDir("pf_work2_")

        val addonA = addon(
            fileName = "a.mcaddon",
            uuid = "aaaaaaaa-1111-1111-1111-111111111111",
            name = "Alpha",
            bpFiles = mapOf(
                "scripts/main.js" to "registerCustomComponent('forge:weapon', {})",
                "items/sword.json" to
                    """{"format_version":"1.20.10","minecraft:item":{"description":{"identifier":"alpha:sword"},"components":{"minecraft:custom_component":"forge:weapon","minecraft:particle_effect":"custom:dust"}}}"""
            ),
            rpFiles = mapOf(
                "particles/dust.json" to particle("custom:dust", "textures/particle/dust"),
                "entity/thing.json" to
                    """{"minecraft:client_entity":{"description":{"identifier":"alpha:thing","particle_effect":"custom:dust","animations":{}}}}"""
            )
        )

        val result = PackForgeOrchestrator.mergeAddons(
            addonPaths = listOf(addonA.absolutePath),
            outputDir = work.absolutePath,
            addonNames = listOf("Alpha"),
            customName = "E2E_Refs",
            addonIds = listOf("a")
        )

        assertTrue("La fusión debe terminar bien: ${result.errorMessage}", result.success)

        // Se extrae el resultado y se valida con el propio validador del motor.
        val extractDir = tempDir("pf_extract_")
        ZipFile(File(result.outputPath!!)).use { zip ->
            zip.entries().toList().forEach { entry ->
                if (entry.isDirectory) return@forEach
                val target = File(extractDir, entry.name)
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }

        val bpDir = extractDir.walkTopDown().firstOrNull {
            it.isDirectory && it.parentFile == extractDir && File(it, "manifest.json").isFile
        }
        val rpDir = extractDir.walkTopDown()
            .filter { it.isDirectory && File(it, "manifest.json").isFile }
            .firstOrNull { it != bpDir }

        val report = MergeReferenceValidator.validate(bpDir, rpDir)

        assertTrue(
            "No deben quedar referencias rotas: ${report.broken}",
            report.isClean
        )
        assertTrue(
            "Debe haber verificado al menos la partícula y el componente",
            report.checkedReferences >= 2
        )
    }
}
