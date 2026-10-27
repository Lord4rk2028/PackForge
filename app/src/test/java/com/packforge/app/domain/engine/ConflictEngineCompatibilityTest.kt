package com.packforge.app.domain.engine

import com.packforge.app.domain.model.Addon
import com.packforge.app.domain.model.AddonType
import com.packforge.app.domain.model.ConflictSeverity
import com.packforge.app.domain.model.ConflictType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConflictEngineCompatibilityTest {

    private fun addon(
        id: String,
        name: String,
        entityIdentifiers: List<String> = emptyList(),
        itemIdentifiers: List<String> = emptyList(),
        recipeIdentifiers: List<String> = emptyList(),
        lootTableIdentifiers: List<String> = emptyList(),
        particleIdentifiers: List<String> = emptyList(),
        materialInstanceIdentifiers: List<String> = emptyList(),
        minEngineVersion: List<Int> = listOf(1, 20, 0),
        manifestUuid: String = "",
        files: List<String> = emptyList(),
        behaviorFiles: List<String> = emptyList(),
        resourceFiles: List<String> = emptyList(),
        sourceFilePath: String = "",
        enabled: Boolean = true
    ): Addon =
        Addon(
            id = id,
            name = name,
            fileName = "$name.mcaddon",
            type = AddonType.BEHAVIOR_AND_RESOURCE,
            version = "1.0.0",
            sizeBytes = 0,
            enabled = enabled,
            priority = 0,
            files = files,
            behaviorFiles = behaviorFiles,
            resourceFiles = resourceFiles,
            entityIdentifiers = entityIdentifiers,
            itemIdentifiers = itemIdentifiers,
            recipeIdentifiers = recipeIdentifiers,
            lootTableIdentifiers = lootTableIdentifiers,
            particleIdentifiers = particleIdentifiers,
            materialInstanceIdentifiers = materialInstanceIdentifiers,
            minEngineVersion = minEngineVersion,
            manifestUuid = manifestUuid,
            rawManifest = "",
            sourceFilePath = sourceFilePath
        )

    /** Crea un addon con manifest.json real en un directorio temporal (para tests de UUID). */
    private fun addonWithManifest(
        id: String,
        name: String,
        uuid: String,
        lootTableIdentifiers: List<String> = emptyList(),
        particleIdentifiers: List<String> = emptyList(),
        materialInstanceIdentifiers: List<String> = emptyList(),
        minEngineVersion: List<Int> = listOf(1, 20, 0)
    ): Addon {
        val tempRoot = File(System.getProperty("java.io.tmpdir"))
        val dir = File(tempRoot, "pf_test_$id")
        dir.mkdirs()
        File(dir, "manifest.json").writeText(
            """{"manifest_version":1,"header":{"name":"$name","uuid":"$uuid","version":[1,0,0]},"modules":[{"type":"script","language":"javascript","entry":"main.js"}]}"""
        )
        return addon(
            id = id,
            name = name,
            lootTableIdentifiers = lootTableIdentifiers,
            particleIdentifiers = particleIdentifiers,
            materialInstanceIdentifiers = materialInstanceIdentifiers,
            minEngineVersion = minEngineVersion,
            manifestUuid = uuid,
            sourceFilePath = dir.path
        )
    }

    // â”€â”€ LOOT TABLES â”€â”€
    @Test
    fun detectLootTableConflicts_duplicateIdentifier_reportsHigh() {
        val a = addon("a", "Addon A", lootTableIdentifiers = listOf("mypack:chest"))
        val b = addon("b", "Addon B", lootTableIdentifiers = listOf("mypack:chest"))

        val conflicts = ConflictEngine.analyze(listOf(a, b))
        val lootConflicts = conflicts.filter { it.type == ConflictType.LOOT_TABLE }

        assertTrue("Debe detectar al menos 1 conflicto de loot table. Conflictos totales: ${conflicts.size} (${conflicts.map { it.type.name }.joinToString(",")})", lootConflicts.isNotEmpty())
        assertEquals("Severidad debe ser HIGH", ConflictSeverity.HIGH, lootConflicts.first().severity)
    }

    @Test
    fun detectLootTableConflicts_noConflict_returnsEmpty() {
        val a = addon("a", "Addon A", lootTableIdentifiers = listOf("mypack:chest"))
        val b = addon("b", "Addon B", lootTableIdentifiers = listOf("minecraft:barrel"))

        val conflicts = ConflictEngine.analyze(listOf(a, b))
        val lootConflicts = conflicts.filter { it.type == ConflictType.LOOT_TABLE }

        assertEquals("No debe haber conflictos de loot table", 0, lootConflicts.size)
    }

    // â”€â”€ PARTÃCULAS â”€â”€
    @Test
    fun detectParticleConflicts_duplicateIdentifier_reportsMedium() {
        val a = addon("a", "Addon A", particleIdentifiers = listOf("mypack:magic_dust"))
        val b = addon("b", "Addon B", particleIdentifiers = listOf("mypack:magic_dust"))

        val conflicts = ConflictEngine.analyze(listOf(a, b))
        val particleConflicts = conflicts.filter { it.type == ConflictType.PARTICLE_EFFECT }

        assertTrue("Debe detectar al menos 1 conflicto de partÃ­cula. Conflictos totales: ${conflicts.size} (${conflicts.map { it.type.name }.joinToString(",")})", particleConflicts.isNotEmpty())
        assertEquals("Severidad debe ser MEDIUM", ConflictSeverity.MEDIUM, particleConflicts.first().severity)
    }

    @Test
    fun detectParticleConflicts_noConflict_returnsEmpty() {
        val a = addon("a", "Addon A", particleIdentifiers = listOf("mypack:magic_dust"))
        val b = addon("b", "Addon B", particleIdentifiers = listOf("minecraft:sparkle"))

        val conflicts = ConflictEngine.analyze(listOf(a, b))
        val particleConflicts = conflicts.filter { it.type == ConflictType.PARTICLE_EFFECT }

        assertEquals("No debe haber conflictos de partÃ­cula", 0, particleConflicts.size)
    }

    // â”€â”€ MATERIAL INSTANCES â”€â”€
    @Test
    fun detectMaterialInstanceConflicts_duplicateIdentifier_reportsHigh() {
        val a = addon("a", "Addon A", materialInstanceIdentifiers = listOf("custom_stone"))
        val b = addon("b", "Addon B", materialInstanceIdentifiers = listOf("custom_stone"))

        val conflicts = ConflictEngine.analyze(listOf(a, b))
        val materialConflicts = conflicts.filter { it.type == ConflictType.MATERIAL_INSTANCE }

        assertTrue("Debe detectar al menos 1 conflicto de material. Conflictos totales: ${conflicts.size} (${conflicts.map { it.type.name }.joinToString(",")})", materialConflicts.isNotEmpty())
        assertEquals("Severidad debe ser HIGH", ConflictSeverity.HIGH, materialConflicts.first().severity)
    }

    @Test
    fun detectMaterialInstanceConflicts_noConflict_returnsEmpty() {
        val a = addon("a", "Addon A", materialInstanceIdentifiers = listOf("custom_stone"))
        val b = addon("b", "Addon B", materialInstanceIdentifiers = listOf("custom_wood"))

        val conflicts = ConflictEngine.analyze(listOf(a, b))
        val materialConflicts = conflicts.filter { it.type == ConflictType.MATERIAL_INSTANCE }

        assertEquals("No debe haber conflictos de material", 0, materialConflicts.size)
    }

    // â”€â”€ VERSIÃ“N DE FORMATO â”€â”€
    // `min_engine_version` es la versiÃ³n MÃNIMA que necesita el addon, no
    // "para quÃ© versiÃ³n se diseÃ±Ã³": un pack de 1.16 corre en 1.21. Solo se
    // avisa cuando la brecha es realmente grande (â‰¥ 8 versiones menores) o
    // cuando cambia la versiÃ³n mayor.
    @Test
    fun detectFormatVersionConflicts_hugeGap_reportsMedium() {
        // 1.9 â†’ 1.17 = 8 versiones menores: brecha grande de verdad.
        val oldAddon = addon("old", "Addon Viejo", minEngineVersion = listOf(1, 9, 0))
        val newAddon = addon("new", "Addon Nuevo", minEngineVersion = listOf(1, 17, 0))

        val conflicts = ConflictEngine.analyze(listOf(oldAddon, newAddon))
        val formatConflicts = conflicts.filter { it.type == ConflictType.FORMAT_VERSION_INCOMPATIBLE }

        assertTrue(
            "Debe detectar la brecha grande. Conflictos: ${conflicts.map { it.type.name }}",
            formatConflicts.isNotEmpty()
        )
        assertEquals(ConflictSeverity.MEDIUM, formatConflicts.first().severity)
        // Un Ãºnico conflicto para todo el modpack, con los dos extremos.
        assertEquals(1, formatConflicts.size)
        assertEquals(2, formatConflicts.first().affectedAddonIds.size)
    }

    @Test
    fun detectFormatVersionConflicts_moderateGap_noConflict() {
        // 1.16 â†’ 1.21 (5 versiones menores) es convivencia normal: NO avisa.
        val a = addon("a", "Addon 1.16", minEngineVersion = listOf(1, 16, 0))
        val b = addon("b", "Addon 1.21", minEngineVersion = listOf(1, 21, 0))

        val formatConflicts = ConflictEngine.analyze(listOf(a, b))
            .filter { it.type == ConflictType.FORMAT_VERSION_INCOMPATIBLE }

        assertEquals(
            "1.16 y 1.21 conviven sin conflicto de formato",
            0,
            formatConflicts.size
        )
    }

    @Test
    fun detectFormatVersionConflicts_smallGap_noConflict() {
        val a = addon("a", "Addon A", minEngineVersion = listOf(1, 20, 0))
        val b = addon("b", "Addon B", minEngineVersion = listOf(1, 20, 1))

        val conflicts = ConflictEngine.analyze(listOf(a, b))
        val formatConflicts = conflicts.filter { it.type == ConflictType.FORMAT_VERSION_INCOMPATIBLE }

        assertEquals("No debe haber conflicto de versiÃ³n con gap pequeÃ±o",
            0, formatConflicts.size)
    }

    @Test
    fun detectFormatVersionConflicts_majorChange_reportsConflict() {
        // Cambio de versiÃ³n mayor: formato distinto con seguridad.
        val a = addon("a", "Addon 1.x", minEngineVersion = listOf(1, 21, 0))
        val b = addon("b", "Addon 2.x", minEngineVersion = listOf(2, 0, 0))

        val formatConflicts = ConflictEngine.analyze(listOf(a, b))
            .filter { it.type == ConflictType.FORMAT_VERSION_INCOMPATIBLE }

        assertEquals("Un salto de versiÃ³n mayor siempre se reporta", 1, formatConflicts.size)
    }

    // â”€â”€ PUNTAJE DE COMPATIBILIDAD â”€â”€
    @Test
    fun getCompatibilityScore_noConflicts_returns100() {
        val a = addon("a", "Addon A")
        val b = addon("b", "Addon B")

        val score = ConflictEngine.getCompatibilityScore(listOf(a, b))
        assertEquals("Sin conflictos debe dar 100", 100, score)
    }

    @Test
    fun getCompatibilityScore_criticalConflict_reducesScore() {
        val a = addonWithManifest("a", "Addon A", "same-uuid")
        val b = addonWithManifest("b", "Addon B", "same-uuid")

        val score = ConflictEngine.getCompatibilityScore(listOf(a, b))
        assertTrue("Con UUID duplicado el score debe ser menor a 100, obtuvo $score", score < 100)
        assertTrue("El score no debe ser negativo", score >= 0)
    }

    @Test
    fun getCompatibilityScore_singleAddon_returns100() {
        val a = addon("a", "Addon A")

        val score = ConflictEngine.getCompatibilityScore(listOf(a))
        assertEquals("Con un solo addon el score debe ser 100", 100, score)
    }
}
