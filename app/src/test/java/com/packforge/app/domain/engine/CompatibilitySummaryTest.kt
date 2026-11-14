package com.packforge.app.domain.engine

import com.packforge.app.domain.model.ConflictSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D — El veredicto de compatibilidad debe resumir TODAS las validaciones del
 * pipeline en un semáforo, para no tener que leer miles de líneas de log.
 */
class CompatibilitySummaryTest {

    @Test
    fun cleanMergeIsOptimal() {
        val report = CompatibilitySummary.build(
            CompatibilitySummary.Inputs(
                checkedReferences = 12,
                appliedDecisions = 2
            )
        )

        assertEquals(CompatibilitySummary.Level.OPTIMAL, report.level)
        assertTrue("Debe mencionar lo verificado", report.headline.contains("12"))
        assertTrue("Debe mencionar las decisiones aplicadas", report.headline.contains("2"))
        assertTrue(report.details.isEmpty())
    }

    @Test
    fun onlyWarningsGiveAttention() {
        val report = CompatibilitySummary.build(
            CompatibilitySummary.Inputs(
                conflictSeverities = listOf(ConflictSeverity.MEDIUM, ConflictSeverity.LOW)
            )
        )

        assertEquals(CompatibilitySummary.Level.ATTENTION, report.level)
        assertTrue(report.details.any { it.contains("media") })
    }

    @Test
    fun brokenReferencesAreProblems() {
        val report = CompatibilitySummary.build(
            CompatibilitySummary.Inputs(
                brokenReferences = listOf(
                    MergeReferenceValidator.BrokenReference("partícula", "custom:ghost", "entity/a.json")
                )
            )
        )

        assertEquals(CompatibilitySummary.Level.PROBLEMS, report.level)
        assertTrue(report.details.any { it.contains("referencia") })
    }

    @Test
    fun missingMergesAreProblemsEvenWithoutConflicts() {
        // El fallo histórico: una fusión declarada sin ejecutar significa
        // pérdida de contenido, y debe pesar más que cualquier aviso.
        val report = CompatibilitySummary.build(
            CompatibilitySummary.Inputs(missingMerges = listOf("mergeParticles"))
        )

        assertEquals(CompatibilitySummary.Level.PROBLEMS, report.level)
        assertTrue(report.details.any { it.contains("mergeParticles") })
    }

    @Test
    fun criticalConflictDominatesOverNotices() {
        val report = CompatibilitySummary.build(
            CompatibilitySummary.Inputs(
                conflictSeverities = listOf(
                    ConflictSeverity.CRITICAL,
                    ConflictSeverity.LOW,
                    ConflictSeverity.LOW
                )
            )
        )

        assertEquals(CompatibilitySummary.Level.PROBLEMS, report.level)
        assertTrue(report.headline.contains("problema"))
    }

    @Test
    fun missingAssetsAndIncompleteEntitiesAreProblems() {
        val report = CompatibilitySummary.build(
            CompatibilitySummary.Inputs(missingAssets = 3, incompleteEntities = 1)
        )

        assertEquals(CompatibilitySummary.Level.PROBLEMS, report.level)
        assertTrue(report.details.any { it.contains("textura") })
        assertTrue(report.details.any { it.contains("entidad") })
    }

    @Test
    fun detailsAreCappedSoTheReportStaysReadable() {
        val report = CompatibilitySummary.build(
            CompatibilitySummary.Inputs(
                brokenReferences = (1..40).map {
                    MergeReferenceValidator.BrokenReference("partícula", "custom:p$it", "e/$it.json")
                }
            )
        )

        assertTrue("No debe volcar 40 líneas", report.details.size <= 12)
    }

    @Test
    fun formatStartsWithTheSemaphore() {
        val optimal = CompatibilitySummary.build(CompatibilitySummary.Inputs())
        assertTrue(optimal.format().startsWith("🟢"))

        val problems = CompatibilitySummary.build(
            CompatibilitySummary.Inputs(missingMerges = listOf("mergeUi"))
        )
        assertTrue(problems.format().startsWith("🔴"))
        assertTrue(problems.format().contains("mergeUi"))
    }
}
