package com.packforge.app.domain.engine

import com.packforge.app.domain.model.Addon
import com.packforge.app.domain.model.ConflictSeverity

/**
 * Umbrales y reglas para decidir si un modpack puede exportarse a .mcaddon.
 *
 * La "Compatibilidad Absoluta" no significa que no existan conflictos, sino que
 * PackForge puede detectarlos, resolverlos o advertir al usuario antes de que el
 * paquete llegue a Minecraft Bedrock.
 */
object ExportCompatibilityGate {

    /** Puntaje mínimo para exportar sin advertencias (95-100). */
    const val SCORE_OPTIMAL = 95

    /** Puntaje mínimo para exportar con advertencias (70-94). */
    const val SCORE_ACCEPTABLE = 70

    /** Puntaje mínimo para exportar bajo riesgo (40-69). */
    const val SCORE_RISKY = 40

    /** Puntaje por debajo del cual se bloquea la exportación (0-39). */
    const val SCORE_BLOCKED = 40

    /**
     * Resultado de la evaluación de compatibilidad para exportación.
     */
    data class ExportDecision(
        /** Si se permite exportar el modpack. */
        val canExport: Boolean,
        /** Puntaje de compatibilidad (0-100). */
        val score: Int,
        /** Nivel de riesgo del modpack. */
        val level: ExportLevel,
        /** Lista de bloqueos críticos que impiden la exportación. */
        val blockers: List<String>,
        /** Lista de advertencias que el usuario debe revisar. */
        val warnings: List<String>,
        /** Resumen legible para la UI. */
        val summary: String
    ) {
        val hasBlockers get() = blockers.isNotEmpty()
    }

    /**
     * Niveles de riesgo de exportación.
     */
    enum class ExportLevel {
        OPTIMAL("Óptimo", "El modpack está listo para exportar sin problemas."),
        ACCEPTABLE("Aceptable", "Hay conflictos menores resueltos o desestimados. Exportar con precaución."),
        RISKY("Riesgoso", "Conflictos importantes sin resolver. El modpack puede tener errores en el juego."),
        BLOCKED("Bloqueado", "Conflictos críticos impiden la exportación. Resuélvalos antes de continuar.");

        val label: String
        val description: String

        constructor(label: String, description: String) {
            this.label = label
            this.description = description
        }
    }

    /**
     * Evalúa si los addons dados pueden exportarse como un modpack válido.
     *
     * @param addons Lista de addons activos (filtrados por enabled).
     * @param conflicts Conflictos detectados por [ConflictEngine.analyze].
     * @return Una [ExportDecision] con la decisión y detalles.
     */
    fun evaluate(
        addons: List<Addon>,
        conflicts: List<com.packforge.app.domain.model.Conflict>
    ): ExportDecision {
        val score = ConflictEngine.getCompatibilityScore(addons)
        val blockers = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val criticalConflicts = conflicts.filter { it.severity == ConflictSeverity.CRITICAL && it.resolution == com.packforge.app.domain.model.ConflictResolution.UNRESOLVED }
        val highConflicts = conflicts.filter { it.severity == ConflictSeverity.HIGH && it.resolution == com.packforge.app.domain.model.ConflictResolution.UNRESOLVED }

        // ── REGLAS DE BLOQUEO ──
        if (criticalConflicts.isNotEmpty()) {
            criticalConflicts.forEach { c ->
                blockers.add("CRÍTICO: ${c.title} — ${c.description}")
            }
        }

        // Si hay muchos conflictos HIGH sin resolver, bloquear también
        if (highConflicts.size >= 5) {
            blockers.add("Demasiados conflictos de alta severidad sin resolver (${highConflicts.size}). " +
                "Resuelva al menos algunos antes de exportar.")
        }

        // ── DETERMINAR NIVEL ──
        val level = when {
            blockers.isNotEmpty() || score < SCORE_BLOCKED -> ExportLevel.BLOCKED
            score >= SCORE_OPTIMAL -> ExportLevel.OPTIMAL
            score >= SCORE_ACCEPTABLE -> ExportLevel.ACCEPTABLE
            score >= SCORE_RISKY -> ExportLevel.RISKY
            else -> ExportLevel.BLOCKED
        }

        // ── ADVERTENCIAS ──
        if (level == ExportLevel.RISKY) {
            warnings.add("El puntaje de compatibilidad es bajo ($score/100). " +
                "El modpack puede tener texturas, entidades o comportamientos rotos en el juego.")
        }
        if (highConflicts.isNotEmpty()) {
            warnings.add("${highConflicts.size} conflicto(s) de alta severidad sin resolver. " +
                "Revíselos en la pantalla de Conflictos antes de exportar.")
        }

        val canExport = level != ExportLevel.BLOCKED

        val summary = when (level) {
            ExportLevel.OPTIMAL -> "Compatibilidad óptima ($score/100). Listo para exportar."
            ExportLevel.ACCEPTABLE -> "Compatibilidad aceptable ($score/100). Exportar con advertencias."
            ExportLevel.RISKY -> "Compatibilidad riesgosa ($score/100). Puede haber errores en el juego."
            ExportLevel.BLOCKED -> "Exportación bloqueada ($score/100). Conflictos críticos sin resolver."
        }

        return ExportDecision(
            canExport = canExport,
            score = score,
            level = level,
            blockers = blockers,
            warnings = warnings,
            summary = summary
        )
    }

    /**
     * Versión de conveniencia que calcula conflictos internamente usando [ConflictEngine].
     */
    fun evaluate(addons: List<Addon>): ExportDecision {
        val conflicts = ConflictEngine.analyze(addons)
        return evaluate(addons, conflicts)
    }
}