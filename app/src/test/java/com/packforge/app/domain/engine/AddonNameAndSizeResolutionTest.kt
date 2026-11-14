package com.packforge.app.domain.engine

import com.packforge.app.util.MinecraftTextSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Suite de pruebas general de resolución de nombres, sanitización de símbolos §
 * y límites de extracción para TODO TIPO de Addons (individuales, complejos,
 * con claves de traducción pack.name, con subcarpetas y con códigos de formato §).
 */
class AddonNameAndSizeResolutionTest {

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile()

    private fun createZipAddon(
        fileName: String,
        manifestContent: String,
        langContent: String? = null,
        subFolder: String? = null
    ): File {
        val file = File(tempDir("pf_test_addon_"), fileName)
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            val prefix = if (subFolder != null) "$subFolder/" else ""
            put("${prefix}manifest.json", manifestContent)
            if (langContent != null) {
                put("${prefix}texts/en_US.lang", langContent)
            }
        }
        return file
    }

    @Test
    fun testExplicitNameWithMinecraftSectionSymbolsIsCleaned() {
        val manifest = """
            {"format_version":2,"header":{"name":"§c§lSuper §sWeapon §pAddon","description":"desc","uuid":"11111111-1111-1111-1111-111111111111","version":[1,0,0]}}
        """.trimIndent()
        val file = createZipAddon("Super_Weapon.mcaddon", manifest)

        val workDir = tempDir("pf_extract_")
        AddonExtractor.extractAddon(file.absolutePath, workDir.absolutePath)
        val info = AddonExtractor.resolveAddonInfo(workDir, file)

        assertEquals("Super Weapon Addon", info.displayName)
        assertFalse("No debe conservar símbolos §", info.displayName.contains("§"))
        workDir.deleteRecursively()
        file.delete()
    }

    @Test
    fun testPackNameTranslationKeyIsResolvedFromLang() {
        val manifest = """
            {"format_version":2,"header":{"name":"pack.name","description":"pack.description","uuid":"22222222-2222-2222-2222-222222222222","version":[1,0,0]}}
        """.trimIndent()
        val lang = "pack.name=§eMagic §bSpells\npack.description=Magic Spells Description\n"
        val file = createZipAddon("magic_spells.mcaddon", manifest, lang)

        val workDir = tempDir("pf_extract_")
        AddonExtractor.extractAddon(file.absolutePath, workDir.absolutePath)
        val info = AddonExtractor.resolveAddonInfo(workDir, file)

        assertEquals("Magic Spells", info.displayName)
        assertFalse("Nunca debe mostrar pack.name", MinecraftTextSanitizer.isTranslationKey(info.displayName))
        workDir.deleteRecursively()
        file.delete()
    }

    @Test
    fun testPackNameTranslationKeyFallsBackToCleanFileNameWhenLangIsMissing() {
        val manifest = """
            {"format_version":2,"header":{"name":"pack.name","description":"pack.description","uuid":"33333333-3333-3333-3333-333333333333","version":[1,0,0]}}
        """.trimIndent()
        val file = createZipAddon("Dungeon_Dragons_v2.mcaddon", manifest)

        val workDir = tempDir("pf_extract_")
        AddonExtractor.extractAddon(file.absolutePath, workDir.absolutePath)
        val info = AddonExtractor.resolveAddonInfo(workDir, file)

        assertEquals("Dungeon Dragons v2", info.displayName)
        assertFalse("Nunca debe mostrar pack.name", MinecraftTextSanitizer.isTranslationKey(info.displayName))
        workDir.deleteRecursively()
        file.delete()
    }

    @Test
    fun testSubFolderAddonResolvesNameCorrectly() {
        val manifest = """
            {"format_version":2,"header":{"name":"pack.name","description":"desc","uuid":"44444444-4444-4444-4444-444444444444","version":[1,0,0]}}
        """.trimIndent()
        val file = createZipAddon("CustomPack.mcaddon", manifest, subFolder = "MyAwesomePack_BP")

        val workDir = tempDir("pf_extract_")
        AddonExtractor.extractAddon(file.absolutePath, workDir.absolutePath)
        val info = AddonExtractor.resolveAddonInfo(workDir, file)

        assertEquals("MyAwesomePack", info.displayName)
        workDir.deleteRecursively()
        file.delete()
    }

    @Test
    fun testScanExternalAddonsInDownloadsIfPresent() {
        val downloadsDir = File("C:/Users/HP/Downloads")
        if (!downloadsDir.exists() || !downloadsDir.isDirectory) return

        val addonsInDownloads = downloadsDir.listFiles()?.filter {
            it.isFile && (it.name.endsWith(".mcaddon", ignoreCase = true) || it.name.endsWith(".mcpack", ignoreCase = true))
        }.orEmpty()

        addonsInDownloads.forEach { addonFile ->
            val workDir = tempDir("pf_scan_")
            val extracted = AddonExtractor.extractAddon(addonFile.absolutePath, workDir.absolutePath)
            assertNotNull("El addon ${addonFile.name} debe extraerse correctamente", extracted)

            val info = AddonExtractor.resolveAddonInfo(workDir, addonFile)
            assertFalse(
                "El addon ${addonFile.name} no debe tener 'pack.name' como nombre",
                MinecraftTextSanitizer.isTranslationKey(info.displayName)
            )
            assertFalse(
                "El addon ${addonFile.name} no debe contener símbolos §",
                info.displayName.contains("§")
            )
            workDir.deleteRecursively()
        }
    }
}
