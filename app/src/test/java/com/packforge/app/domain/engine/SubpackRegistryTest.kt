package com.packforge.app.domain.engine

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 3.5 — `manifest.header.subpacks`: variantes por nivel de memoria.
 *
 * Antes el motor reconstruía los manifiestos SIN `subpacks`, así que las
 * carpetas de variantes seguían en el `.mcaddon` pero el juego nunca las
 * cargaba. Además, dos addons con el mismo `folder_name` se pisaban.
 */
class SubpackRegistryTest {

    private val roots = mutableListOf<File>()

    @Before
    fun setUp() {
        ConflictRegistry.clear()
        SubpackRegistry.clear()
    }

    @After
    fun cleanUp() {
        roots.forEach { it.deleteRecursively() }
        ConflictRegistry.clear()
        SubpackRegistry.clear()
    }

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { roots += it }

    private fun pack(name: String, subpacks: String, uuid: String = "uuid-$name"): File {
        val root = File(tempDir("pf_subpack_"), name).apply { mkdirs() }
        File(root, "manifest.json").writeText(
            """
            {"format_version":2,"header":{"name":"$name RP","description":"rp","uuid":"$uuid",
            "version":[1,0,0],"min_engine_version":[1,21,0],"subpacks":$subpacks},
            "modules":[{"type":"resources","uuid":"$uuid-m","version":[1,0,0]}]}
            """.trimIndent()
        )
        return root
    }

    private fun manifiesto(dir: File) = File(dir, "manifest.json")

    private fun renombres(name: String, component: String, tier: Int) =
        """[{"folder_name":"$name","name":"$component","memory_tier":$tier}]"""

    // ── Carpeta repetida ─────────────────────────────────────────────────

    @Test
    fun sameFolderFromTwoAddonsIsRenamedAndBothEntriesSurvive() {
        val a = pack("addon_a", renombres("subpack_1", "Baja", 0), uuid = "uuid-a")
        val b = pack("addon_b", renombres("subpack_1", "Baja", 0), uuid = "uuid-b")

        SubpackRegistry.plan(listOf(a, b))

        assertEquals(
            "El primero conserva la carpeta original",
            "subpack_1/textures/blocks/x.png",
            SubpackRegistry.renameFor(a, "subpack_1/textures/blocks/x.png")
        )
        val renombradaB = SubpackRegistry.renameFor(b, "subpack_1/textures/blocks/x.png")
        assertTrue("El segundo debe ir a una carpeta propia: $renombradaB", renombradaB.startsWith("subpack_1_pf"))
        assertTrue(renombradaB.endsWith("/textures/blocks/x.png"))

        // El manifiesto fusionado conserva las dos variantes, cada una con su carpeta.
        val fusionado = ManifestGenerator.buildMergedRpManifest(
            listOf(manifiesto(a), manifiesto(b)),
            packName = "Fusionado"
        )
        val subs = fusionado.getJSONObject("header").getJSONArray("subpacks")
        assertEquals(2, subs.length())
        val carpetas = (0 until subs.length()).map { subs.getJSONObject(it).getString("folder_name") }
        assertEquals("Las carpetas no pueden colisionar: $carpetas", 2, carpetas.distinct().size)
        assertEquals("subpack_1", carpetas[0])
        assertEquals(renombradaB.substringBefore('/'), carpetas[1])
        assertEquals("Baja", subs.getJSONObject(1).getString("name"))
        assertEquals(0, subs.getJSONObject(1).getInt("memory_tier"))
    }

    @Test
    fun renameIsStableForTheSameAddon() {
        val a = pack("addon_a", renombres("subpack_1", "Baja", 0), uuid = "uuid-a")
        val b = pack("addon_b", renombres("subpack_1", "Baja", 0), uuid = "uuid-b")

        SubpackRegistry.plan(listOf(a, b))
        val primera = SubpackRegistry.renameFor(b, "subpack_1/a.png")

        SubpackRegistry.clear()
        SubpackRegistry.plan(listOf(a, b))
        val segunda = SubpackRegistry.renameFor(b, "subpack_1/a.png")

        assertEquals("El token no puede cambiar entre exportaciones", primera, segunda)
    }

    @Test
    fun differentFoldersKeepTheirNames() {
        val a = pack("addon_a", renombres("low", "Baja", 0))
        val b = pack("addon_b", renombres("high", "Alta", 2))

        SubpackRegistry.plan(listOf(a, b))

        assertEquals("low/a.png", SubpackRegistry.renameFor(a, "low/a.png"))
        assertEquals("high/a.png", SubpackRegistry.renameFor(b, "high/a.png"))
        assertFalse(
            "Carpetas distintas no son un conflicto",
            ConflictRegistry.conflicts.value.any { it.conflictType == "SUBPACK_RENAMED" }
        )
    }

    // ── Niveles de memoria ───────────────────────────────────────────────

    @Test
    fun duplicateMemoryTierIsReported() {
        val a = pack("addon_a", renombres("low", "Baja", 0))
        val b = pack("addon_b", renombres("other_low", "Baja también", 0))

        SubpackRegistry.plan(listOf(a, b))

        val conflicto = ConflictRegistry.conflicts.value
            .firstOrNull { it.conflictType == "SUBPACK_TIER_COLLISION" }
        assertNotNull("Dos subpacks del mismo tier deben verse: Bedrock solo aplica uno", conflicto)
        assertEquals(com.packforge.app.domain.model.ConflictSeverity.MEDIUM, conflicto!!.severity)
        assertTrue(conflicto.description.contains("memory_tier 0"))
    }

    @Test
    fun differentTiersAreNotAConflict() {
        val a = pack("addon_a", renombres("low", "Baja", 0))
        val b = pack("addon_b", renombres("high", "Alta", 2))

        SubpackRegistry.plan(listOf(a, b))

        assertFalse(
            ConflictRegistry.conflicts.value.any { it.conflictType == "SUBPACK_TIER_COLLISION" }
        )
    }

    // ── Entradas inválidas y sin planificar ──────────────────────────────

    @Test
    fun entryWithoutFolderNameIsReportedAndOmitted() {
        val a = pack("addon_a", """[{"name":"Sin carpeta","memory_tier":0}]""")

        SubpackRegistry.plan(listOf(a))

        assertTrue(
            "Una entrada sin folder_name no se puede cargar: debe reportarse",
            ConflictRegistry.conflicts.value.any { it.conflictType == "SUBPACK_INVALID" }
        )
        val fusionado = ManifestGenerator.buildMergedRpManifest(listOf(manifiesto(a)), "Fusionado")
        assertFalse(
            "No puede llegar al manifiesto una variante sin carpeta",
            fusionado.getJSONObject("header").has("subpacks")
        )
    }

    @Test
    fun manifestKeepsSubpacksEvenWithoutPlan() {
        // Un manifiesto sintético (tests, llamadas directas al generador) no
        // pasó por `plan`: la variante debe conservarse igualmente.
        val a = pack("addon_a", renombres("subpack_1", "Baja", 0))

        val fusionado = ManifestGenerator.buildMergedRpManifest(listOf(manifiesto(a)), "Fusionado")

        val subs = fusionado.getJSONObject("header").getJSONArray("subpacks")
        assertEquals(1, subs.length())
        assertEquals("subpack_1", subs.getJSONObject(0).getString("folder_name"))
    }

    @Test
    fun subpacksWithoutFilesAreStillPreserved() {
        // La carpeta puede estar vacía o no existir en el origen: el manifiesto
        // conserva la declaración (Bedrock la ignora si falta, pero el dato no
        // se pierde y el informe puede verlo).
        val a = pack("addon_a", renombres("hd", "Alta", 3))

        SubpackRegistry.plan(listOf(a))

        assertEquals("hd", SubpackRegistry.finalFolderFor(manifiesto(a), "hd"))
        assertEquals(
            "hd/textures/blocks/x.png",
            SubpackRegistry.renameFor(a, "hd/textures/blocks/x.png")
        )
    }
}
