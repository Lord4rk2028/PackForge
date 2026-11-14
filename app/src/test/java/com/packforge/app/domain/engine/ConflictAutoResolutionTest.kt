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
    fun tiedPriorityIsResolvedByFusionOrder() {
        // ⚠️ CAMBIO DELIBERADO (auditoría de exportación bloqueada).
        //
        // Antes, un empate de prioridad dejaba el conflicto SIN resolver
        // ("no hay criterio objetivo"). En la práctica TODOS los addons entran
        // con prioridad 0, así que cada identificador o archivo compartido
        // quedaba abierto y el modpack se bloqueaba a las 5 colisiones (25/100)
        // aunque la fusión sí lo resuelve.
        //
        // Sí hay criterio: el pipeline copia los addons en orden y el último
        // gana, de forma determinista. `ranked` es estable, así que ranked[0]
        // ES el que sobrevive; usarlo como desempate refleja lo que de verdad
        // hará la fusión.
        val a = addon("a", "Alfa", priority = 3)
        val b = addon("b", "Beta", priority = 3)

        val loot = ConflictEngine.analyze(listOf(a, b))
            .filter { it.type == ConflictType.LOOT_TABLE }

        assertEquals(1, loot.size)
        assertTrue("Con empate se desempata por orden de fusión", loot.first().isAutoResolved)
        assertEquals(ConflictResolution.FIRST_WINS, loot.first().resolution)
        assertEquals("Gana el que se copia al final", "a", loot.first().winnerAddonId)
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
    fun autoResolvedConflictsDoNotPenalizeTheScore() {
        // CAMBIO DELIBERADO: lo ya resuelto no penaliza. Antes se penalizaba
        // igual, así que un modpack con TODOS los solapamientos resueltos seguía
        // con nota pésima y el gate bloqueaba la exportación (caso real:
        // Origins + OTG Backpack -> 25/100 con el paquete fusionado válido).
        val low = addon("low", "Alfa", priority = 0)
        val high = addon("high", "Beta", priority = 1)

        val conflictos = ConflictEngine.analyze(listOf(low, high))
        assertTrue("deberia detectar solapamientos", conflictos.isNotEmpty())
        assertTrue(
            "con prioridades distintas todos deben quedar auto-resueltos",
            conflictos.all { it.isAutoResolved }
        )
        assertEquals(
            "lo resuelto no penaliza",
            100,
            ConflictEngine.scoreOfConflicts(conflictos)
        )
    }

    @Test
    fun unresolvedConflictsStillPenalizeTheScore() {
        // MANIFEST_UUID nunca se auto-resuelve: exige regenerar UUIDs, no elegir
        // ganador. Ese si debe bajar la nota.
        val a = addon("a", "Alfa", priority = 0, manifestUuid = "same-uuid")
        val b = addon("b", "Beta", priority = 1, manifestUuid = "same-uuid")

        val conflictos = ConflictEngine.analyze(listOf(a, b))
        val abiertos = conflictos.filter { !it.isAutoResolved }
        assertTrue("debe haber conflictos abiertos", abiertos.isNotEmpty())
        assertTrue(
            "los conflictos abiertos si penalizan el score",
            ConflictEngine.scoreOfConflicts(conflictos) < 100
        )
    }
}

