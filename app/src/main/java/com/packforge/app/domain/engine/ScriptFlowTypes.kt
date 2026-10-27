package com.packforge.app.domain.engine

import com.packforge.app.domain.model.ConflictSeverity

/**
 * ═══════════════════════════════════════════════════════════════════════
 * TIPOS DEL ANÁLISIS DE SCRIPTS (Script API @minecraft/server)
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Modelo de datos para evaluar si los scripts de varios Behavior Packs
 * pueden convivir en un modpack fusionado sin romper en tiempo de ejecución.
 *
 * Los enums declaran sus etiquetas en el constructor: antes las variantes
 * pasaban argumentos (`SAFE("✅ Safe")`) a un enum sin constructor, lo que
 * generaba "Too many arguments for constructor()" y rompía la compilación.
 */

// ── Riesgo global del conjunto de scripts ────────────────────────────────
enum class ScriptRiskLevel(val display: String) {
    SAFE("✅ Seguro"),
    WARNING("⚠️ Precaución"),
    BLOCKED("❌ Bloqueado");

    companion object {
        /** Traduce una severidad de conflicto a un nivel de riesgo de scripts. */
        fun fromSeverity(severity: ConflictSeverity): ScriptRiskLevel = when (severity) {
            ConflictSeverity.CRITICAL -> BLOCKED
            ConflictSeverity.HIGH, ConflictSeverity.WARNING -> WARNING
            else -> SAFE
        }
    }
}

// ── Tipo de conflicto específico de scripts ──────────────────────────────
enum class ScriptConflictType(val description: String) {
    ENTRY_INVALID("Punto de entrada de script inválido o inexistente"),
    DEPENDENCY_MISSING("Dependencia importada no encontrada"),
    DEPENDENCY_VERSION("Versión de dependencia incompatible"),
    NAMESPACE_COLLISION("Colisión de namespace entre scripts"),
    VERSION_INCOMPATIBLE("Versión del script incompatible con el motor"),
    EXECUTION_ORDER("Posible conflicto de orden de ejecución"),
    IMPORT_CYCLE("Ciclo de importaciones entre scripts"),
    CUSTOM_COMPONENT("Registro duplicado de componente personalizado"),
    DYNAMIC_PROPERTY("Propiedad dinámica compartida entre scripts"),
    IDENTIFIER_COLLISION("Colisión de identificadores en JSON/assets"),
    SERVER_ONLY_MODULE("Módulo de servidor no soportado en cliente Bedrock"),
    LEGACY_SCRIPT_API("Módulo obsoleto no compatible con Bedrock moderno"),
    API_TRACK_MISMATCH("Conflicto entre rama Beta y Estable de Script API"),
    BEFORE_EVENTS_CONFLICT("Múltiples addons interceptando el mismo beforeEvent"),
    GLOBAL_POLLUTION("Colisión en el ámbito global (globalThis)")
}

// ── Tipos avanzados de inspección de scripts ─────────────────────────────
data class ScriptModuleDependency(
    val name: String,
    val version: String,
    val isBeta: Boolean,
    val isServerOnly: Boolean,
    val isLegacy: Boolean
)

enum class CustomComponentKind(val display: String) {
    BLOCK("Bloque"),
    ITEM("Ítem"),
    GENERIC("Genérico")
}

data class CustomComponentInfo(
    val name: String,
    val kind: CustomComponentKind,
    val sourceFile: String,
    val line: Int = 0
)

// ── Bloqueo: impide exportar con seguridad ───────────────────────────────
/**
 * Un bloqueo es un problema no negociable que impide ejecutar los scripts con
 * seguridad. A diferencia de las advertencias, requiere acción del usuario.
 */
data class ScriptBlocker(
    val scriptId: String,
    val conflictType: ScriptConflictType,
    val severity: ConflictSeverity,
    val description: String,
    val suggestion: String,
    val affectedScript: String? = null
)

// ── Advertencia: no impide exportar ──────────────────────────────────────
/**
 * Una advertencia es un problema que puede afectar al comportamiento pero no
 * impide la ejecución. El usuario puede decidir continuar.
 */
data class ScriptWarning(
    val scriptId: String,
    val conflictType: ScriptConflictType,
    val severity: ConflictSeverity,
    val description: String,
    val suggestion: String
)

// ── Metadatos de un script registrado ────────────────────────────────────
data class ScriptMetadata(
    val scriptId: String,
    val version: String,
    val author: String,
    val dependencies: List<String>,
    val executionConditions: ExecutionConditions,
    val conflictPolicies: ConflictPolicies,
    val entryPoint: String,
    val resolvedEntryPoint: String,
    val namespace: String?,
    val tags: List<String>,
    val moduleDependencies: List<ScriptModuleDependency> = emptyList(),
    val registeredCustomComponents: List<CustomComponentInfo> = emptyList(),
    val dynamicPropertiesUsed: List<String> = emptyList(),
    val subscribedBeforeEvents: List<String> = emptyList(),
    val subscribedAfterEvents: List<String> = emptyList(),
    val globalVariables: List<String> = emptyList()
)

data class ExecutionConditions(
    val requiresExperiments: Boolean = false,
    val requiresBeta: Boolean = false,
    val compatibleGameVersions: List<String> = emptyList(),
    val minEngineVersion: String = "1.20.0"
)

data class ConflictPolicies(
    val onVersionMismatch: PolicyOption = PolicyOption.WARN,
    val onNamespaceCollision: PolicyOption = PolicyOption.BLOCK,
    val onDependencyMissing: PolicyOption = PolicyOption.BLOCK,
    val onIdentifierCollision: PolicyOption = PolicyOption.BLOCK
)

enum class PolicyOption(val label: String) {
    BLOCK("Bloquear la ejecución"),
    WARN("Mostrar advertencia y permitir"),
    IGNORE("Ignorar en silencio"),
    ASK_USER("Preguntar al usuario")
}

enum class RecommendedAction(val label: String) {
    EXECUTE("Exportar de todos modos"),
    FIX_ISSUES("Corregir los problemas detectados"),
    MODIFY_SCRIPTS("Modificar los scripts"),
    CANCEL("Cancelar la operación"),
    CONSULT_DOCS("Consultar la documentación")
}

// ── Decisión de la puerta de scripts ─────────────────────────────────────
/**
 * Decisión de la ScriptGate tras analizar todos los scripts.
 * Espeja la estructura de [ExportCompatibilityGate] para integrarse en la UI.
 */
data class ScriptGateDecision(
    val canExecute: Boolean,
    val riskLevel: ScriptRiskLevel,
    val compatibilityScore: Double,
    val blockers: List<ScriptBlocker>,
    val warnings: List<ScriptWarning>,
    val summary: String,
    val overallScore: Double,
    val recommendedAction: RecommendedAction
) {
    companion object {
        /** Decisión neutra: vale como valor inicial mientras se analiza en IO. */
        fun safe(): ScriptGateDecision = ScriptGateDecision(
            canExecute = true,
            riskLevel = ScriptRiskLevel.SAFE,
            compatibilityScore = 1.0,
            blockers = emptyList(),
            warnings = emptyList(),
            summary = "Sin scripts analizados",
            overallScore = 100.0,
            recommendedAction = RecommendedAction.EXECUTE
        )
    }
}

// ── Hallazgos agregados ──────────────────────────────────────────────────
data class ScriptFindings(
    val totalScripts: Int,
    val validScripts: Int,
    val invalidScripts: Int,
    val totalBlockers: Int,
    val totalWarnings: Int,
    val riskLevel: ScriptRiskLevel,
    val averageCompatibilityScore: Double,
    val byScript: Map<String, ScriptGateDecision>
)
