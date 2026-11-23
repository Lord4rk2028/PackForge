package com.packforge.app.domain.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 3.11 — La SEMILLA del addon (de la que salen los tokens `_pf<hex>`) debe
 * derivarse de los UUID de sus manifiestos, nunca de la ruta extraída
 * (`extracted_<epochMs>_…`) ni de la posición en la lista de importación.
 */
class AddonOwnerRegistryTest {

    private val roots = mutableListOf<File>()

    @Before
    fun setUp() = AddonOwnerRegistry.clear()

    @After
    fun cleanUp() {
        roots.forEach { it.deleteRecursively() }
        AddonOwnerRegistry.clear()
    }

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { roots += it }

    private fun pack(name: String, uuid: String?): File {
        val root = File(tempDir("pf_owner_"), name).apply { mkdirs() }
        File(root, "textures").mkdirs()
        if (uuid != null) {
            File(root, "manifest.json").writeText(
                """
                {"format_version":2,"header":{"name":"$name","description":"rp",
                "uuid":"$uuid","version":[1,0,0],"min_engine_version":[1,20,0]},
                "modules":[{"type":"resources","uuid":"$uuid-m","version":[1,0,0]}]}
                """.trimIndent()
            )
        }
        return root
    }

    @Test
    fun seedDoesNotDependOnTheExtractionPath() {
        val mismaA = pack("extracted_1700000000000_addon", "11111111-1111-1111-1111-111111111111")
        val mismaB = pack("extracted_1900000000000_addon", "11111111-1111-1111-1111-111111111111")

        assertEquals(
            "El mismo addon extraído en otra ruta debe dar la misma semilla",
            AddonOwnerRegistry.stableSeed(listOf(mismaA), "addon"),
            AddonOwnerRegistry.stableSeed(listOf(mismaB), "addon")
        )
    }

    @Test
    fun seedIgnoresTheCaseOfTheManifestUuid() {
        val a = pack("a", "AAAAAAAA-1111-1111-1111-111111111111")
        val b = pack("b", "aaaaaaaa-1111-1111-1111-111111111111")

        assertEquals(
            "Un UUID en mayúsculas es el mismo addon",
            AddonOwnerRegistry.stableSeed(listOf(a), "a"),
            AddonOwnerRegistry.stableSeed(listOf(b), "b")
        )
    }

    @Test
    fun differentAddonsGetDifferentSeeds() {
        val a = pack("extracted_1_alpha", "11111111-1111-1111-1111-111111111111")
        val b = pack("extracted_2_beta", "22222222-2222-2222-2222-222222222222")

        assertNotEquals(
            AddonOwnerRegistry.stableSeed(listOf(a), "alpha"),
            AddonOwnerRegistry.stableSeed(listOf(b), "beta")
        )
    }

    @Test
    fun seedOfBothRootsOfTheSameAddonIsShared() {
        // Un `.mcaddon` con BP y RP: las dos raíces forman UN addon y comparten
        // semilla, para que el token sea el mismo en ambos packs.
        val bp = pack("extracted_1_pack", "33333333-3333-3333-3333-333333333333")
        val rp = pack("extracted_1_pack", "44444444-4444-4444-4444-444444444444")
        val semilla = AddonOwnerRegistry.stableSeed(listOf(bp, rp), "pack")

        AddonOwnerRegistry.register(bp, "both_0", "pack", semilla)
        AddonOwnerRegistry.register(rp, "both_0", "pack", semilla)

        assertEquals(semilla, AddonOwnerRegistry.seedOf("both_0"))
        assertEquals("pack", AddonOwnerRegistry.labelOf("both_0"))
    }

    @Test
    fun registerKeepsTheFirstSeedForAnOwner() {
        val bp = pack("bp", "55555555-5555-5555-5555-555555555555")
        val rp = pack("rp", "66666666-6666-6666-6666-666666666666")

        AddonOwnerRegistry.register(bp, "both_0", "pack", "seed_primera")
        AddonOwnerRegistry.register(rp, "both_0", "pack", "seed_segunda")

        assertEquals("seed_primera", AddonOwnerRegistry.seedOf("both_0"))
    }

    @Test
    fun withoutManifestTheSeedFallsBackToTheNameWithoutItsStamp() {
        val a = pack("extracted_1700000000000_sin_manifiesto", null)
        val b = pack("extracted_1900000000000_sin_manifiesto", null)

        assertEquals(
            "Sin manifiesto, el nombre visible sigue siendo estable",
            AddonOwnerRegistry.stableSeed(listOf(a), "extracted_1700000000000_sin_manifiesto"),
            AddonOwnerRegistry.stableSeed(listOf(b), "extracted_1900000000000_sin_manifiesto")
        )
    }

    @Test
    fun unregisteredOwnerHasNoSeedOrLabel() {
        assertNull(AddonOwnerRegistry.seedOf("addon_99"))
        assertNull(AddonOwnerRegistry.labelOf("addon_99"))
        assertNull(AddonOwnerRegistry.ownerOf(File(tempDir("pf_sin_registro_"), "x")))
    }
}
