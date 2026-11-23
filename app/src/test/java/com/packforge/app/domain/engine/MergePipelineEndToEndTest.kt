package com.packforge.app.domain.engine

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun rpManifest(uuid: String, name: String, subpacks: String = "") = """
        {"format_version":2,"header":{"name":"$name RP","description":"rp","uuid":"$uuid",
        "version":[1,0,0],"min_engine_version":[1,20,0]${if (subpacks.isNotBlank()) ",\"subpacks\":$subpacks" else ""}},
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
        rpFiles: Map<String, String> = emptyMap(),
        rpSubpacks: String = ""
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
            put("rp/manifest.json", rpManifest("$uuid-rp", name, rpSubpacks))
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

    // ── Binarios y ZIPs anidados ─────────────────────────────────────────

    /**
     * PNG real de 1×1 (válido, no texto): comprueba que el pipeline no corrompe
     * binarios al copiarlos y fusionarlos.
     */
    private val onePixelPng: ByteArray = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg=="
    )

    private fun nestedPack(name: String, uuid: String, textureContent: String): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(rpManifest(uuid, name).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("textures/blocks/nested.png"))
            zip.write(textureContent.toByteArray())
            zip.closeEntry()
        }
        return baos.toByteArray()
    }

    @Test
    fun binaryTexturesAndNestedPacksSurviveTheMerge() = runBlocking {
        val work = tempDir("pf_bin_")

        val addonFile = File(tempDir("pf_bin_src_"), "bin.mcaddon")
        ZipOutputStream(addonFile.outputStream()).use { zip ->
            fun put(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path)); zip.write(content.toByteArray()); zip.closeEntry()
            }
            fun putBytes(path: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
            }

            put("bp/manifest.json", bpManifest("44444444-4444-4444-4444-444444444444", "Bin"))
            put("rp/manifest.json", rpManifest("44444444-4444-4444-4444-444444444445", "Bin"))
            // PNG binario real.
            putBytes("rp/textures/blocks/real.png", onePixelPng)
            // Addon con un .mcpack ANIDADO dentro.
            putBytes(
                "rp/nested/inner.mcpack",
                nestedPack("Inner", "55555555-5555-5555-5555-555555555555", "NESTED-PNG")
            )
        }

        val result = PackForgeOrchestrator.mergeAddons(
            addonPaths = listOf(addonFile.absolutePath),
            outputDir = work.absolutePath,
            addonNames = listOf("Bin"),
            customName = "E2E_Bin",
            addonIds = listOf("bin")
        )

        assertTrue("La fusión debe terminar bien: ${result.errorMessage}", result.success)

        ZipFile(File(result.outputPath!!)).use { zip ->
            val names = entryNames(zip)

            // 1. El PNG llega byte a byte idéntico (sin corromper binarios).
            val pngEntry = names.firstOrNull { it.endsWith("textures/blocks/real.png") }
            assertNotNull("El PNG real debe estar en el modpack: $names", pngEntry)
            val bytes = zip.getInputStream(zip.getEntry(pngEntry!!)).use { it.readBytes() }
            assertTrue(
                "El PNG debe conservarse íntegro (${bytes.size} bytes)",
                bytes.contentEquals(onePixelPng)
            )

            // 2. El contenido del .mcpack anidado también se integra.
            val nestedEntry = names.firstOrNull { it.endsWith("textures/blocks/nested.png") }
            assertNotNull(
                "El contenido del .mcpack anidado debe integrarse en el modpack: $names",
                nestedEntry
            )
            val nestedBytes = zip.getInputStream(zip.getEntry(nestedEntry!!)).use { it.readBytes() }
            assertEquals(
                "El binario del pack anidado no debe alterarse",
                "NESTED-PNG",
                nestedBytes.toString(Charsets.UTF_8)
            )
        }
    }

    /**
     * TICKET-CORE-015 (keys): al renombrar el bloque de un addon perdedor hay
     * que renombrar también su CLAVE en `blocks.json` y en `texture_data`. Si
     * solo se reescriben valores, el bloque renombrado se queda sin mapeo de
     * textura y el juego lo muestra como “?”.
     */
    @Test
    fun renamedBlockKeepsItsCatalogEntries() = runBlocking {
        val work = tempDir("pf_work3_")

        fun blockPack(name: String, uuid: String, textura: String): File = addon(
            fileName = "$name.mcaddon",
            uuid = uuid,
            name = name,
            bpFiles = mapOf(
                "blocks/stone.json" to
                    """{"format_version":"1.20.10","minecraft:block":{"description":{"identifier":"tools:stone"},"components":{"minecraft:material_instances":{"*":{"texture":"$textura"}}}}}"""
            ),
            rpFiles = mapOf(
                "blocks.json" to
                    """{"format_version":[1,1,0],"tools:stone":{"textures":"$textura","sound":"stone"}}""",
                "textures/terrain_texture.json" to
                    """{"resource_pack_name":"x","texture_name":"atlas.terrain","texture_data":{"$textura":{"textures":"textures/blocks/$textura"}}}""",
                "textures/blocks/$textura.png" to "PNG-$textura"
            )
        )

        val result = PackForgeOrchestrator.mergeAddons(
            addonPaths = listOf(
                blockPack("BlockA", "aaaa1111-1111-1111-1111-111111111111", "stone_a").absolutePath,
                blockPack("BlockB", "bbbb2222-2222-2222-2222-222222222222", "stone_b").absolutePath
            ),
            outputDir = work.absolutePath,
            addonNames = listOf("BlockA", "BlockB"),
            customName = "E2E_Blocks",
            addonIds = listOf("block_a", "block_b")
        )

        assertTrue("La fusión debe terminar bien: ${result.errorMessage}", result.success)

        ZipFile(File(result.outputPath!!)).use { zip ->
            val blocks = JSONObject(entryText(zip, "blocks.json")!!)
            assertTrue(
                "El bloque ganador conserva su clave: $blocks",
                blocks.has("tools:stone")
            )
            val claveRenombrada = blocks.keys().asSequence()
                .firstOrNull { it.startsWith("pf:") && it.endsWith("stone") }
            assertNotNull(
                "El bloque del perdedor debe conservar su entrada en blocks.json con la clave renombrada: $blocks",
                claveRenombrada
            )
            assertEquals(
                "La entrada renombrada debe apuntar a su propia textura y no a la del ganador",
                "stone_b",
                blocks.getJSONObject(claveRenombrada!!).getString("textures")
            )

            val atlas = JSONObject(entryText(zip, "textures/terrain_texture.json")!!)
                .getJSONObject("texture_data")
            assertTrue("La textura del ganador debe estar", atlas.has("stone_a"))
            assertTrue("La textura del perdedor debe estar", atlas.has("stone_b"))
        }
    }

    /**
     * 3.5: los `subpacks` deben sobrevivir al manifiesto fusionado (antes se
     * descartaban y el juego ignoraba las carpetas) y las carpetas de dos
     * addons no pueden mezclarse.
     */
    @Test
    fun subpacksFromTwoAddonsSurviveWithDistinctFolders() = runBlocking {
        val work = tempDir("pf_work_subpack_")
        val subpacks =
            """[{"folder_name":"subpack_1","name":"Baja","memory_tier":0}]"""

        val a = addon(
            fileName = "sub_a.mcaddon",
            uuid = "aaaa3333-3333-3333-3333-333333333333",
            name = "SubA",
            rpFiles = mapOf("subpack_1/textures/blocks/only_a.png" to "PNG-SUB-A"),
            rpSubpacks = subpacks
        )
        val b = addon(
            fileName = "sub_b.mcaddon",
            uuid = "bbbb4444-4444-4444-4444-444444444444",
            name = "SubB",
            rpFiles = mapOf("subpack_1/textures/blocks/only_b.png" to "PNG-SUB-B"),
            rpSubpacks = subpacks
        )

        val result = PackForgeOrchestrator.mergeAddons(
            addonPaths = listOf(a.absolutePath, b.absolutePath),
            outputDir = work.absolutePath,
            addonNames = listOf("SubA", "SubB"),
            customName = "E2E_Subpacks",
            addonIds = listOf("sub_a", "sub_b")
        )

        assertTrue("La fusión debe terminar bien: ${result.errorMessage}", result.success)

        ZipFile(File(result.outputPath!!)).use { zip ->
            val names = entryNames(zip)
            val rpManifestPath = names.first {
                it.endsWith("manifest.json") && it.lowercase().contains("rp_")
            }
            val header = JSONObject(entryText(zip, rpManifestPath)!!).getJSONObject("header")
            val subs = header.optJSONArray("subpacks")
            assertNotNull("El manifiesto fusionado debe conservar 'subpacks'", subs)
            assertEquals("Deben llegar las dos variantes", 2, subs!!.length())

            val folders = (0 until subs.length()).map {
                subs.getJSONObject(it).getString("folder_name")
            }
            assertEquals(
                "Las carpetas de subpack no pueden colisionar: $folders",
                2,
                folders.distinct().size
            )
            assertEquals("La primera conserva el nombre original", "subpack_1", folders[0])
            assertTrue(
                "El contenido del primero debe estar en su carpeta: $names",
                names.any { it.endsWith("subpack_1/textures/blocks/only_a.png") }
            )
            assertTrue(
                "El contenido del segundo debe estar en la carpeta renombrada: $names",
                names.any { it.endsWith("${folders[1]}/textures/blocks/only_b.png") }
            )
        }
    }

    /**
     * 3.11 — El token `pf:<hex>` de un identificador renombrado debe ser el
     * MISMO en dos exportaciones de los mismos addons. Antes salía de
     * `ownerKey.hashCode()` y, en un addon de un solo tipo, la clave caía a la
     * ruta extraída (`extracted_<epochMs>_…`): el id cambiaba en cada
     * exportación y lo guardado en un mundo dejaba de resolverse al reinstalar.
     */
    @Test
    fun renamedIdentifierTokensAreStableAcrossExports() = runBlocking {
        fun bpOnly(name: String, uuid: String, id: String): File {
            val file = File(tempDir("pf_stable_src_"), "$name.mcaddon")
            ZipOutputStream(file.outputStream()).use { zip ->
                fun put(path: String, content: String) {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(content.toByteArray())
                    zip.closeEntry()
                }
                put(
                    "manifest.json",
                    """{"format_version":2,"header":{"name":"$name","description":"bp","uuid":"$uuid","version":[1,0,0],"min_engine_version":[1,20,0]},"modules":[{"type":"data","uuid":"$uuid-m","version":[1,0,0]}]}"""
                )
                put(
                    "entities/thing.json",
                    """{"format_version":"1.16.0","minecraft:entity":{"description":{"identifier":"$id"},"components":{}}}"""
                )
            }
            return file
        }

        suspend fun exportar(): String {
            val work = tempDir("pf_stable_out_")
            val a = bpOnly("StableA", "cccc1111-1111-1111-1111-111111111111", "tools:thing")
            val b = bpOnly("StableB", "dddd2222-2222-2222-2222-222222222222", "tools:thing")
            val result = PackForgeOrchestrator.mergeAddons(
                addonPaths = listOf(a.absolutePath, b.absolutePath),
                outputDir = work.absolutePath,
                addonNames = listOf("StableA", "StableB"),
                customName = "E2E_Stable",
                addonIds = listOf("stable_a", "stable_b")
            )
            assertTrue("La fusión debe terminar bien: ${result.errorMessage}", result.success)

            ZipFile(File(result.outputPath!!)).use { zip ->
                val ids = entryNames(zip)
                    .filter { it.contains("/entities/") && it.endsWith(".json") }
                    .mapNotNull { nombre ->
                        val texto = entryText(zip, nombre) ?: return@mapNotNull null
                        runCatching {
                            JSONObject(texto).optJSONObject("minecraft:entity")
                                ?.optJSONObject("description")
                                ?.optString("identifier")
                        }.getOrNull()
                    }
                val renombrado = ids.firstOrNull { it.startsWith("pf:") }
                assertNotNull("El perdedor debe recibir un id pf:: $ids", renombrado)
                return renombrado!!
            }
        }

        val primera = exportar()
        val segunda = exportar()

        assertEquals(
            "El token de desambiguación no puede cambiar entre exportaciones",
            primera,
            segunda
        )
    }

    /**
     * 3.10 — Un addon que depende de OTRO pack por UUID que no va en el
     * `.mcaddon` hacía que Bedrock no cargara NADA. El motor elimina esa
     * dependencia y lo reporta.
     */
    @Test
    fun externalDependencyIsDroppedSoThePackLoads() = runBlocking {
        val work = tempDir("pf_work_deps_")
        val exclusivo = "0e9e9e9e-0000-0000-0000-000000000001"

        val addonFile = File(tempDir("pf_deps_src_"), "deps.mcaddon")
        ZipOutputStream(addonFile.outputStream()).use { zip ->
            fun put(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
            put(
                "manifest.json",
                """
                {"format_version":2,"header":{"name":"Deps","description":"bp",
                "uuid":"0e9e9e9e-0000-0000-0000-0000000000aa","version":[1,0,0],
                "min_engine_version":[1,20,0]},
                "modules":[{"type":"data","uuid":"0e9e9e9e-0000-0000-0000-0000000000bb","version":[1,0,0]}],
                "dependencies":[{"uuid":"$exclusivo","version":[1,0,0]},
                {"module_name":"@minecraft/server","version":"1.16.0"}]}
                """.trimIndent()
            )
            put(
                "entities/thing.json",
                """{"format_version":"1.16.0","minecraft:entity":{"description":{"identifier":"tools:thing"},"components":{}}}"""
            )
        }

        val result = PackForgeOrchestrator.mergeAddons(
            addonPaths = listOf(addonFile.absolutePath),
            outputDir = work.absolutePath,
            addonNames = listOf("Deps"),
            customName = "E2E_Deps",
            addonIds = listOf("deps")
        )

        assertTrue("La fusión debe terminar bien: ${result.errorMessage}", result.success)

        ZipFile(File(result.outputPath!!)).use { zip ->
            val names = entryNames(zip)
            val bpManifestPath = names.first {
                it.endsWith("manifest.json") && it.lowercase().contains("bp_")
            }
            val deps = JSONObject(entryText(zip, bpManifestPath)!!).optJSONArray("dependencies")
            val uuids = (0 until (deps?.length() ?: 0))
                .mapNotNull { deps!!.optJSONObject(it)?.optString("uuid") }
                .map { it.lowercase() }
            assertFalse(
                "La dependencia externa no puede quedar colgando: $uuids",
                uuids.contains(exclusivo.lowercase())
            )
            assertTrue(
                "El módulo de scripting debe conservarse",
                (0 until (deps?.length() ?: 0))
                    .any { deps!!.optJSONObject(it)?.optString("module_name") == "@minecraft/server" }
            )
        }

        assertTrue(
            "La dependencia eliminada debe llegar al reporte",
            ConflictRegistry.conflicts.value.any {
                it.conflictType == "EXTERNAL_DEPENDENCY_DROPPED" &&
                    it.description.contains(exclusivo)
            }
        )
    }
}
