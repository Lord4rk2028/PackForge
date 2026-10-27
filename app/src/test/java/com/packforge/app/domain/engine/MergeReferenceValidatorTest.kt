package com.packforge.app.domain.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * P3: validador de las referencias que la propia fusión manipula
 * (partículas, animaciones, materiales y componentes personalizados).
 *
 * Un renombrado mal hecho deja una referencia colgando y el fallo es invisible
 * hasta que se abre el mundo; esto lo detecta antes de empaquetar.
 */
class MergeReferenceValidatorTest {

    private val roots = mutableListOf<File>()

    @Before
    fun setUp() {
        ConflictRegistry.clear()
    }

    @After
    fun cleanUp() {
        roots.forEach { it.deleteRecursively() }
        ConflictRegistry.clear()
    }

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { roots += it }

    private fun mergedBp(files: Map<String, String> = emptyMap()): File {
        val root = tempDir("pf_bp_").apply { mkdirs() }
        files.forEach { (rel, content) ->
            val file = File(root, rel)
            file.parentFile?.mkdirs()
            file.writeText(content)
        }
        return root
    }

    private fun mergedRp(files: Map<String, String> = emptyMap()): File {
        val root = tempDir("pf_rp_").apply { mkdirs() }
        files.forEach { (rel, content) ->
            val file = File(root, rel)
            file.parentFile?.mkdirs()
            file.writeText(content)
        }
        return root
    }

    private fun particleFile(identifier: String) =
        """{"format_version":"1.10.0","particle_effect":{"description":{"identifier":"$identifier"},"components":{}}}"""

    private fun entityReferencingParticle(identifier: String) =
        """{"minecraft:client_entity":{"description":{"identifier":"x:entity","particle_effect":"$identifier"}}}"""

    // ── Partículas ───────────────────────────────────────────────────────

    @Test
    fun existingParticleReferenceIsClean() {
        val rp = mergedRp(
            mapOf(
                "particles/dust.json" to particleFile("custom:dust"),
                "entity/thing.json" to entityReferencingParticle("custom:dust")
            )
        )
        val bp = mergedBp()

        val report = MergeReferenceValidator.validate(bp, rp)

        assertTrue("No debería haber roturas: ${report.broken}", report.isClean)
        assertEquals(1, report.checkedReferences)
    }

    @Test
    fun missingParticleIsReportedWithItsFile() {
        val rp = mergedRp(
            mapOf("entity/thing.json" to entityReferencingParticle("custom:ghost"))
        )
        val bp = mergedBp()

        val report = MergeReferenceValidator.validate(bp, rp)

        assertFalse(report.isClean)
        assertEquals(1, report.broken.size)
        assertEquals("partícula", report.broken.first().kind)
        assertEquals("custom:ghost", report.broken.first().identifier)
        assertTrue(report.broken.first().referencingFile.endsWith("thing.json"))
    }

    @Test
    fun vanillaParticleReferenceIsNeverReported() {
        val rp = mergedRp(
            mapOf("entity/thing.json" to entityReferencingParticle("minecraft:basic_smoke_particle"))
        )

        val report = MergeReferenceValidator.validate(mergedBp(), rp)

        assertTrue("Vanilla es del juego, no una referencia rota", report.isClean)
        assertEquals("Ni siquiera se cuenta", 0, report.checkedReferences)
    }

    // ── Animaciones ──────────────────────────────────────────────────────

    @Test
    fun animationReferencesAreValidated() {
        val rp = mergedRp(
            mapOf(
                "animations/walk.json" to """{"format_version":"1.8.0","animations":{"animation.x.walk":{"loop":true}}}""",
                "entity/thing.json" to
                    """{"minecraft:client_entity":{"description":{"identifier":"x:e","animations":{"walk":"animation.x.walk","miss":"animation.x.missing"}}}}"""
            )
        )

        val report = MergeReferenceValidator.validate(mergedBp(), rp)

        assertEquals(2, report.checkedReferences)
        assertEquals(1, report.broken.size)
        assertEquals("animación", report.broken.first().kind)
        assertEquals("animation.x.missing", report.broken.first().identifier)
    }

    // ── Materiales ───────────────────────────────────────────────────────

    @Test
    fun customMaterialIsValidatedButEngineMaterialIsIgnored() {
        val rp = mergedRp(
            mapOf(
                "materials/entity.material" to
                    """{"materials":{"version":"1.0.0","custom:glow":{}}}""",
                "render_controllers/thing.json" to
                    """{"render_controllers":{"controller.render.x":{"materials":[{"*":"custom:glow"},{"*":"custom:nope"},{"*":"Material.default"}]}}}"""
            )
        )

        val report = MergeReferenceValidator.validate(mergedBp(), rp)

        val kinds = report.broken.map { it.kind }.distinct()
        assertEquals(listOf("material"), kinds)
        assertEquals(1, report.broken.size)
        assertEquals("custom:nope", report.broken.first().identifier)
    }

    // ── Componentes personalizados ───────────────────────────────────────

    @Test
    fun customComponentReferenceMustBeRegisteredByAScript() {
        val bp = mergedBp(
            mapOf(
                "scripts/main.js" to "registerCustomComponent('forge:weapon', {})",
                "items/sword.json" to
                    """{"minecraft:item":{"description":{"identifier":"x:sword"},"components":{"minecraft:custom_component":"forge:weapon"}}}""",
                "items/broken.json" to
                    """{"minecraft:item":{"description":{"identifier":"x:bad"},"components":{"minecraft:custom_component":"forge:missing"}}}"""
            )
        )
        val rp = mergedRp()

        val report = MergeReferenceValidator.validate(bp, rp)

        assertEquals(2, report.checkedReferences)
        assertEquals(1, report.broken.size)
        assertEquals("componente personalizado", report.broken.first().kind)
        assertEquals("forge:missing", report.broken.first().identifier)
        assertTrue(report.broken.first().referencingFile.endsWith("broken.json"))
    }

    @Test
    fun componentReferencedFromBlockIsAlsoValidated() {
        val bp = mergedBp(
            mapOf(
                "blocks/thing.json" to
                    """{"minecraft:block":{"description":{"identifier":"x:b"},"components":{"minecraft:custom_component":"forge:ghost"}}}"""
            )
        )

        val report = MergeReferenceValidator.validate(bp, mergedRp())

        assertEquals(1, report.broken.size)
        assertEquals("forge:ghost", report.broken.first().identifier)
    }

    // ── Publicación e informe ────────────────────────────────────────────

    @Test
    fun brokenReferencesArePublishedToTheConflictRegistry() {
        val rp = mergedRp(
            mapOf("entity/thing.json" to entityReferencingParticle("custom:ghost"))
        )

        val report = MergeReferenceValidator.validate(mergedBp(), rp)
        MergeReferenceValidator.publish(report)

        val published = ConflictRegistry.conflicts.value.filter { it.conflictType == "REFERENCIA_ROTA" }
        assertEquals(1, published.size)
        assertTrue(published.first().description.contains("custom:ghost"))
    }

    @Test
    fun summaryReflectsBothStates() {
        val cleanRp = mergedRp(
            mapOf(
                "particles/a.json" to particleFile("custom:a"),
                "entity/e.json" to entityReferencingParticle("custom:a")
            )
        )
        val clean = MergeReferenceValidator.validate(mergedBp(), cleanRp)
        assertTrue(clean.summary.contains("sin roturas"))

        val brokenRp = mergedRp(
            mapOf("entity/e.json" to entityReferencingParticle("custom:missing"))
        )
        val broken = MergeReferenceValidator.validate(mergedBp(), brokenRp)
        assertTrue(broken.summary.contains("ROTAS"))
    }

    @Test
    fun emptyPacksAreClean() {
        val report = MergeReferenceValidator.validate(mergedBp(), mergedRp())

        assertTrue(report.isClean)
        assertEquals(0, report.checkedReferences)
    }
}
