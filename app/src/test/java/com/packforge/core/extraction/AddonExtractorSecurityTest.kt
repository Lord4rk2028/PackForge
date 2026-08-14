package com.packforge.core.extraction

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AddonExtractorSecurityTest {

    private val tempRoots = mutableListOf<File>()

    @After
    fun tearDown() {
        tempRoots.forEach { it.deleteRecursively() }
        tempRoots.clear()
    }

    private fun tempDir(name: String): File {
        val dir = Files.createTempDirectory("pf_sec_$name").toFile()
        tempRoots.add(dir)
        return dir
    }

    private data class ZipEntrySpec(val name: String, val content: ByteArray = ByteArray(0))

    private fun createZip(zipFile: File, entries: List<ZipEntrySpec>) {
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            entries.forEach { spec ->
                zos.putNextEntry(ZipEntry(spec.name))
                zos.write(spec.content)
                zos.closeEntry()
            }
        }
    }

    // ── ZIP SLIP ─────────────────────────────────────────────────────

    @Test
    fun zipSlipRelativeTraversal_isRejectedAndNothingEscapes() {
        val root = tempDir("slip")
        val zip = File(root, "attacker.zip")
        createZip(zip, listOf(ZipEntrySpec("../evil.sh", byteArrayOf(1, 2, 3))))
        val dest = File(root, "out")

        val thrown = runCatching {
            AddonExtractor.extractAddon(zip.absolutePath, dest.absolutePath)
        }.exceptionOrNull()

        assertTrue("Debe lanzar SecurityException", thrown is SecurityException)
        assertFalse("No debe escribirse fuera del destino", File(root, "evil.sh").exists())
        assertFalse("El destino parcial debe limpiarse", dest.exists())
    }

    @Test
    fun zipSlipDeepTraversal_isRejected() {
        val root = tempDir("deep")
        val zip = File(root, "attacker.zip")
        createZip(zip, listOf(ZipEntrySpec("../../../../etc/evil.txt", ByteArray(4))))
        val dest = File(root, "out")

        val thrown = runCatching {
            AddonExtractor.extractAddon(zip.absolutePath, dest.absolutePath)
        }.exceptionOrNull()

        assertTrue("Debe lanzar SecurityException", thrown is SecurityException)
        assertFalse("No debe escribirse el archivo lateral", File(root, "evil.txt").exists())
    }

    @Test
    fun absolutePathEntry_neverEscapes() {
        val root = tempDir("abs")
        val zip = File(root, "attacker.zip")
        createZip(zip, listOf(ZipEntrySpec("/tmp/pf_evil_marker.txt", ByteArray(2))))
        val dest = File(root, "out")

        val thrown = runCatching {
            AddonExtractor.extractAddon(zip.absolutePath, dest.absolutePath)
        }.exceptionOrNull()

        val absorbedInside = File(dest, "tmp/pf_evil_marker.txt").exists()
        assertTrue(
            "Debe rechazar la ruta absoluta o dejarla dentro del destino (nunca fuera)",
            thrown is SecurityException || absorbedInside
        )
    }

    @Test
    fun zipSlipWindowsSeparators_neverEscapes() {
        val root = tempDir("win")
        val zip = File(root, "attacker.zip")
        createZip(zip, listOf(ZipEntrySpec("..\\..\\evil.bat", ByteArray(2))))
        val dest = File(root, "out")

        val thrown = runCatching {
            AddonExtractor.extractAddon(zip.absolutePath, dest.absolutePath)
        }.exceptionOrNull()

        val absorbedInside = File(root, "out").walkTopDown().any { it.name == "evil.bat" }
        assertTrue(
            "Los separadores \\ no deben escribir fuera del destino",
            thrown is SecurityException || absorbedInside
        )
    }

    // ── LÍMITES ──────────────────────────────────────────────────────

    @Test
    fun tooManyEntries_isRejected() {
        val root = tempDir("entries")
        val zip = File(root, "big.zip")
        val entries = (0 until 1_000_001).map { ZipEntrySpec("f$it.txt", ByteArray(0)) }
        createZip(zip, entries)
        val dest = File(root, "out")

        val thrown = runCatching {
            AddonExtractor.extractAddon(zip.absolutePath, dest.absolutePath)
        }.exceptionOrNull()

        assertTrue("Debe lanzar excepción por nº de entradas", thrown is IllegalStateException)
    }

    // ── REGRESIÓN: extracción normal ──────────────────────────────────

    @Test
    fun normalAddon_stillExtractsCorrectly() {
        val root = tempDir("normal")
        val zip = File(root, "addon.mcpack")
        createZip(
            zip,
            listOf(
                ZipEntrySpec("manifest.json", "{\"format_version\":2}".toByteArray()),
                ZipEntrySpec("BP/entities/cool.entity.json", "{}".toByteArray()),
                ZipEntrySpec("BP/textures/a.png", ByteArray(4)),
                ZipEntrySpec("texts/es_ES.lang", "x=1".toByteArray())
            )
        )
        val dest = File(root, "out")

        val result = AddonExtractor.extractAddon(zip.absolutePath, dest.absolutePath)

        assertEquals(dest.absolutePath, result)
        assertTrue(File(dest, "manifest.json").exists())
        assertTrue(File(dest, "BP/entities/cool.entity.json").exists())
        assertTrue(File(dest, "texts/es_ES.lang").exists())
    }
}
