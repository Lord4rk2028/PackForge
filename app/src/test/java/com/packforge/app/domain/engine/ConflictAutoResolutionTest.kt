package com.packforge.app.domain.engine

import com.packforge.app.domain.model.Addon
import com.packforge.app.domain.model.AddonType
import com.packforge.app.domain.model.ConflictResolution
import com.packforge.app.domain.model.ConflictType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Auto-resoluciÃ³n por prioridad.
 *
 * La fusiÃ³n copia los addons en orden sobrescribiendo, asÃ­ que la prioridad
 * mÃ¡s alta gana. Con un ganador Ãºnico el conflicto es determinista y no debe
 * pedirle nada al usuario; con empate sÃ­.
 */
class ConflictAutoResolutionTest {

    private fun addon(
        id: String,
        name: String,
        priority: Int,
        lootTableIdentifiers: List<String> = listOf("mypack:chest"),
        manifestUuid: String = ""
    ) = Addon(
        id = id,
        name = name,
        fileName = "$name.mcaddon",
        type = AddonType.BEHAVIOR_AND_RESOURCE,
        version = "1.0.0",
        sizeBytes = 0,
        enabled = true,
        priority = priority,
        lootTableIdentifiers = lootTableIdentifiers,
        manifestUuid = manifestUuid,
        rawManifest = ""
    )

    @Test
    fun uniqueHigherPriorityResolvesAutomatically() {
        val low = addon("low", "Alfa", priority = 0)
        val high = addon("high", "Beta", priority = 1)

        val loot = ConflictEngine.analyze(listOf(low, high))
            .filter { it.type == ConflictType.LOOT_TABLE }

        assertEquals(1, loot.size)
        assertTrue("Debe marcarse como auto-resuelto", loot.first().isAutoResolved)
        assertEquals(ConflictResolution.FIRST_WINS, loot.first().resolution)
        assertEquals("Gana la prioridad mÃ¡s alta", "high", loot.first().winnerAddonId)
    }

    @Test
    fun tiedPriorityStaysUnresolved() {
        val a = addon("a", "Alfa", priority = 3)
        val b = addon("b", "Beta", priority = 3)

        val loot = ConflictEngine.analyze(listOf(a, b))
            .filter { it.type == ConflictType.LOOT_TABLE }

        assertEquals(1, loot.size)
        assertFalse("Con empate no debe auto-resolverse", loot.first().isAutoResolved)
        assertEquals(ConflictResolution.UNRESOLVED, loot.first().resolution)
        assertNull(loot.first().winnerAddonId)
    }

    @Test
    fun manifestUuidConflictIsNeverAutoResolved() {
        val low = addon("low", "Alfa", priority = 0, manifestUuid = "same-uuid")
        val high = addon("high", "Beta", priority = 1, manifestUuid = "same-uuid")

        val uuidConflicts = ConflictEngine.analyze(listOf(low, high))
            .filter { it.type == ConflictType.MANIFEST_UUID }

        assertTrue(uuidConflicts.isNotEmpty())
        assertTrue(
            "El UUID duplicado exige regenerar identificadores, no elegir ganador",
            uuidConflicts.none { it.isAutoResolved }
        )
        assertEquals(ConflictResolution.UNRESOLVED, uuidConflicts.first().resolution)
    }

    @Test
    fun userResolutionWinsOverAutoResolution() {
        val low = addon("low", "Alfa", priority = 0)
        val high = addon("high", "Beta", priority = 1)

        val autoResolved = ConflictEngine.analyze(listOf(low, high))
            .first { it.type == ConflictType.LOOT_TABLE }

        // El usuario decide lo contrario de lo que harÃ­a la prioridad.
        val overridden = ConflictEngine.analyze(
            listOf(low, high),
            resolutions = mapOf(autoResolved.id to "low")
        ).first { it.type == ConflictType.LOOT_TABLE }

        assertEquals("low", overridden.winnerAddonId)
        assertFalse("La decisiÃ³n del usuario no es automÃ¡tica", overridden.isAutoResolved)
    }

    @Test
    fun autoResolvedConflictDoesNotReduceCompatibilityScoreTwice() {
        val low = addon("low", "Alfa", priority = 0)
        val high = addon("high", "Beta", priority = 1)

        // Aunque se auto-resuelva, sigue siendo un solapamiento real: el score
        // lo penaliza (no se oculta el hecho), pero deja de requerir acciÃ³n.
        val score = ConflictEngine.getCompatibilityScore(listOf(low, high))
        assertTrue("El solapamiento debe penalizar el score", score < 100)
    }
}

