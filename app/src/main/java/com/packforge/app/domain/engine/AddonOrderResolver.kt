package com.packforge.app.domain.engine

import com.packforge.app.domain.model.Addon
import com.packforge.app.domain.model.Conflict
import com.packforge.app.domain.model.ConflictResolution

/**
 * ═══════════════════════════════════════════════════════════════════════
 * ADDON ORDER RESOLVER — respeta los ganadores elegidos por el usuario
 * ═══════════════════════════════════════════════════════════════════════
 *
 * La fusión copia los addons **en orden y sobrescribiendo**, así que el
 * resultado real siempre lo decide el orden (el último gana). Antes, cuando el
 * usuario elegía un ganador distinto en la pantalla de Conflictos, esa decisión
 * **no se aplicaba**: se mostraba como resuelta pero el archivo fusionado seguía
 * quedándose con el contenido del addon que estaba más al final.
 *
 * Este resolver traduce las decisiones del usuario a un orden de fusión:
 * si el usuario dice que gana X, X pasa al final. El orden es estable: los
 * addons sin restricciones conservan su posición relativa.
 *
 * No toca las auto-resoluciones (las decide la prioridad y ya se cumplen) ni
 * los conflictos descartados.
 */
object AddonOrderResolver {

    /**
     * @param ordered addons en el orden en que deben fusionarse
     * @param reordered true si el orden cambió respecto al de entrada
     * @param warnings avisos (p. ej. decisiones contradictorias entre sí)
     */
    data class OrderResult(
        val ordered: List<Addon>,
        val reordered: Boolean,
        val warnings: List<String>
    )

    /**
     * Reordena [addons] para que cada ganador elegido explícitamente quede
     * después de todos los addons con los que compite.
     */
    fun resolveOrder(
        addons: List<Addon>,
        conflicts: List<Conflict>
    ): OrderResult {
        if (addons.size < 2) return OrderResult(addons, false, emptyList())

        val knownIds = addons.map { it.id }.toSet()

        // ganador → addons que deben copiarse ANTES que él
        val mustPrecede = mutableMapOf<String, MutableSet<String>>()
        conflicts.forEach { conflict ->
            val winner = conflict.winnerAddonId ?: return@forEach
            if (conflict.isAutoResolved) return@forEach
            if (conflict.resolution == ConflictResolution.DISMISSED) return@forEach
            if (winner !in knownIds) return@forEach

            conflict.affectedAddonIds
                .filter { it != winner && it in knownIds }
                .forEach { other ->
                    mustPrecede.getOrPut(winner) { mutableSetOf() }.add(other)
                }
        }

        if (mustPrecede.isEmpty()) return OrderResult(addons, false, emptyList())

        val remaining = addons.toMutableList()
        val ordered = mutableListOf<Addon>()
        val warnings = mutableListOf<String>()

        while (remaining.isNotEmpty()) {
            val remainingIds = remaining.map { it.id }.toSet()
            val candidate = remaining.firstOrNull { addon ->
                mustPrecede[addon.id].orEmpty().none { predecessor ->
                    predecessor in remainingIds
                }
            }

            if (candidate == null) {
                // Ciclo de decisiones: A gana a B y B gana a A. No hay orden
                // posible, así que se conserva el resto tal cual y se avisa.
                warnings += "Hay decisiones de conflicto contradictorias entre sí; " +
                    "se conserva el orden actual para los addons afectados."
                ordered += remaining
                break
            }

            ordered += candidate
            remaining.remove(candidate)
        }

        val reordered = ordered.map { it.id } != addons.map { it.id }
        return OrderResult(ordered, reordered, warnings)
    }
}
