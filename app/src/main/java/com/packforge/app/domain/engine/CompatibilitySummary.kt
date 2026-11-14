package com.packforge.app.domain.engine

import com.packforge.app.domain.model.ConflictSeverity

/**
 * ═══════════════════════════════════════════════════════════════════════
 * COMPATIBILITY SUMMARY — un semáforo único para el resultado de la fusión
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Todas las validaciones del pipeline existían, pero cada una escribía en el
 * log por su cuenta: conflictos registrados, fusiones no ejecutadas, referencias
 * rotas, texturas/modelos que faltan, reparaciones del healer… Para saber si el
 * modpack salió bien había que leer miles de líneas.
 *
 * Este objeto agrega TODO en un veredicto con semáforo, pensado para que el
 * usuario vea de un vistazo si el modpack está listo, merece revisión o tiene
 * problemas reales.
 */
object CompatibilitySummary {

    /** Veredicto global. */
    enum class Level(val emoji: String, val label: String) {
        OPTIMAL("🟢", "Compatible"),
        ATTENTION("🟡", "Revisar"),
        PROBLEMS("🔴", "Con problemas")
    }

    data class Report(
        val level: Level,
        val headline: String,
        val details: List<String>
    ) {
        /** Texto de varias líneas para el informe de fusión. */
        fun format(): String = buildString {
            appendLine("${level.emoji} COMPATIBILIDAD: ${level.label} — $headline")
            details.forEach { appendLine("   • $it") }
        }
    }

    /**
     * Entradas del veredicto. Todas opcionales: el resumen se construye con lo
     * que el pipeline tenga disponible en ese punto.
     */
    data class Inputs(
        /** Fusiones declaradas que no llegaron a ejecutarse (riesgo de pérdida). */
        val missingMerges: List<String> = emptyList(),
        /** Referencias rotas detectadas tras fusionar. */
        val brokenReferences: List<MergeReferenceValidator.BrokenReference> = emptyList(),
        /** Referencias verificadas correctamente (para el mensaje positivo). */
        val checkedReferences: Int = 0,
        /** Conflictos registrados durante la fusión, con su severidad. */
        val conflictSeverities: List<ConflictSeverity> = emptyList(),
        /** Texturas/modelos que faltaban y el healer no pudo recuperar. */
        val missingAssets: Int = 0,
        /** Entidades incompletas detectadas. */
        val incompleteEntities: Int = 0,
        /** Avisos sueltos de la validación. */
        val warnings: List<String> = emptyList(),
        /** Archivos sustituidos según las decisiones del usuario. */
        val appliedDecisions: Int = 0
    )

    fun build(inputs: Inputs): Report {
        val criticalConflicts = inputs.conflictSeverities.count { it == ConflictSeverity.CRITICAL }
        val highConflicts = inputs.conflictSeverities.count { it == ConflictSeverity.HIGH }
        val mediumConflicts = inputs.conflictSeverities.count { it == ConflictSeverity.MEDIUM }
        val lowConflicts = inputs.conflictSeverities.count {
            it == ConflictSeverity.LOW || it == ConflictSeverity.WARNING
        }

        // ── Problemas reales: algo puede no funcionar en el juego ──
        val blockers = buildList {
            if (inputs.missingMerges.isNotEmpty()) {
                add("${inputs.missingMerges.size} fusión(es) no ejecutada(s): ${inputs.missingMerges.joinToString()}")
            }
            if (inputs.brokenReferences.isNotEmpty()) {
                add("${inputs.brokenReferences.size} referencia(s) rota(s) (partículas, animaciones, materiales o componentes)")
            }
            if (criticalConflicts > 0) add("$criticalConflicts conflicto(s) crítico(s) sin resolver")
            if (inputs.missingAssets > 0) add("${inputs.missingAssets} archivo(s) de textura/modelo ausente(s)")
            if (inputs.incompleteEntities > 0) add("${inputs.incompleteEntities} entidad(es) incompleta(s)")
        }

        // ── Avisos: conviene mirarlos, pero el modpack es usable ──
        val notices = buildList {
            if (highConflicts > 0) add("$highConflicts conflicto(s) de severidad alta")
            if (mediumConflicts > 0) add("$mediumConflicts conflicto(s) de severidad media")
            if (lowConflicts > 0) add("$lowConflicts aviso(s) menor(es)")
            addAll(inputs.warnings.take(5))
        }

        val level = when {
            blockers.isNotEmpty() -> Level.PROBLEMS
            notices.isNotEmpty() -> Level.ATTENTION
            else -> Level.OPTIMAL
        }

        val headline = when (level) {
            Level.PROBLEMS -> "El modpack se generó, pero hay ${blockers.size} problema(s) que pueden notarse en el juego."
            Level.ATTENTION -> "El modpack está listo; hay ${notices.size} punto(s) que conviene revisar."
            Level.OPTIMAL -> buildString {
                append("Sin problemas detectados")
                if (inputs.checkedReferences > 0) {
                    append(" · ${inputs.checkedReferences} referencia(s) verificada(s)")
                }
                if (inputs.appliedDecisions > 0) {
                    append(" · ${inputs.appliedDecisions} archivo(s) según tus decisiones")
                }
            }
        }

        // En el detalle se muestran primero los problemas, luego los avisos.
        val details = (blockers + notices).take(12)

        return Report(level = level, headline = headline, details = details)
    }
}
