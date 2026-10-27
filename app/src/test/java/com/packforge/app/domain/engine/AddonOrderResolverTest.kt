package com.packforge.app.domain.engine

import com.packforge.app.domain.model.Addon
import com.packforge.app.domain.model.AddonType
import com.packforge.app.domain.model.Conflict
import com.packforge.app.domain.model.ConflictResolution
import com.packforge.app.domain.model.ConflictSeverity
import com.packforge.app.domain.model.ConflictType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La fusión copia en orden y sobrescribe, así que el orden ES el resultado.
 * Si el usuario elige un ganador, el orden debe reflejarlo.
 */
class AddonOrderResolverTest {

    private fun addon(id: String, priority: Int) = Addon(
        id = id,
        name = "Addon $id",
        fileName = "$id.mcaddon",
        type = AddonType.BEHAVIOR_AND_RESOURCE,
        version = "1.0.0",
        sizeBytes = 0,
        priority = priority
    )

    private fun conflict(
        id: String,
        affected: List<String>,
        winner: String?,
        resolution: ConflictResolution = ConflictResolution.FIRST_WINS,
        autoResolved: Boolean = false
    ) = Conflict(
        id = id,
        type = ConflictType.LOOT_TABLE,
        severity = ConflictSeverity.HIGH,
        title = "t",
        description = "d",
        technicalDetail = "td",
        affectedAddonIds = affected,
        affectedFile = "loot_tables/x.json",
        resolution = resolution,
        winnerAddonId = winner,
        isAutoResolved = autoResolved
    )

    @Test
    fun withoutExplicitWinnersOrderIsUntouched() {
        val addons = listOf(addon("a", 0), addon("b", 1))

        val result = AddonOrderResolver.resolveOrder(addons, emptyList())

        assertFalse(result.reordered)
        assertEquals(listOf("a", "b"), result.ordered.map { it.id })
    }

    @Test
    fun chosenWinnerMovesToTheEnd() {
        // El usuario dice que gana "a", pero "a" va primero: la fusión dejaría
        // ganar a "b" y su decisión se ignoraría en silencio.
        val addons = listOf(addon("a", 0), addon("b", 1))
        val conflicts = listOf(conflict("c1", listOf("a", "b"), winner = "a"))

        val result = AddonOrderResolver.resolveOrder(addons, conflicts)

        assertTrue(result.reordered)
        assertEquals(listOf("b", "a"), result.ordered.map { it.id })
        assertEquals("El ganador queda al final", "a", result.ordered.last().id)
    }

    @Test
    fun winnerAlreadyLastDoesNotReorder() {
        val addons = listOf(addon("a", 0), addon("b", 1))
        val conflicts = listOf(conflict("c1", listOf("a", "b"), winner = "b"))

        val result = AddonOrderResolver.resolveOrder(addons, conflicts)

        assertFalse(result.reordered)
        assertEquals(listOf("a", "b"), result.ordered.map { it.id })
    }

    @Test
    fun autoResolvedConflictsDoNotReorder() {
        // Las auto-resoluciones ya siguen la prioridad; reordenar por ellas
        // crearía un bucle (prioridad → orden → prioridad).
        val addons = listOf(addon("a", 0), addon("b", 1))
        val conflicts = listOf(
            conflict("c1", listOf("a", "b"), winner = "a", autoResolved = true)
        )

        val result = AddonOrderResolver.resolveOrder(addons, conflicts)

        assertFalse(result.reordered)
    }

    @Test
    fun dismissedConflictsDoNotReorder() {
        val addons = listOf(addon("a", 0), addon("b", 1))
        val conflicts = listOf(
            conflict(
                "c1",
                listOf("a", "b"),
                winner = "a",
                resolution = ConflictResolution.DISMISSED
            )
        )

        val result = AddonOrderResolver.resolveOrder(addons, conflicts)

        assertFalse(result.reordered)
    }

    @Test
    fun contradictoryDecisionsKeepOrderAndWarn() {
        // "a" gana a "b" y "b" gana a "a": no existe orden válido.
        val addons = listOf(addon("a", 0), addon("b", 1))
        val conflicts = listOf(
            conflict("c1", listOf("a", "b"), winner = "a"),
            conflict("c2", listOf("a", "b"), winner = "b")
        )

        val result = AddonOrderResolver.resolveOrder(addons, conflicts)

        assertTrue("Debe avisar del conflicto de decisiones", result.warnings.isNotEmpty())
        assertEquals(
            "Se conserva el orden original",
            setOf("a", "b"),
            result.ordered.map { it.id }.toSet()
        )
        assertEquals(2, result.ordered.size)
    }

    @Test
    fun threeAddonsChainIsRespected() {
        // El usuario quiere "a" por delante de "b", y "b" por delante de "c"
        // (los ganadores van al final). Orden esperado: c, b, a.
        val addons = listOf(addon("a", 0), addon("b", 1), addon("c", 2))
        val conflicts = listOf(
            conflict("c1", listOf("a", "b"), winner = "a"),
            conflict("c2", listOf("b", "c"), winner = "b")
        )

        val result = AddonOrderResolver.resolveOrder(addons, conflicts)

        assertEquals(listOf("c", "b", "a"), result.ordered.map { it.id })
    }

    @Test
    fun winnerNotPresentIsIgnored() {
        val addons = listOf(addon("a", 0), addon("b", 1))
        val conflicts = listOf(conflict("c1", listOf("a", "b"), winner = "ghost"))

        val result = AddonOrderResolver.resolveOrder(addons, conflicts)

        assertFalse(result.reordered)
        assertEquals(listOf("a", "b"), result.ordered.map { it.id })
    }
}
