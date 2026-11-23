package com.packforge.app.domain.engine

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 3.8 — Familias declarativas que no tenían fusión dedicada:
 * `item_catalog/`, `cameras/`, `dimensions/`, `worldgen/feature_rules/` y
 * `trading/`.
 *
 * Antes se copiaban por ruta: el mismo identifier en dos nombres de archivo
 * quedaba DUPLICADO en el pack (Bedrock descarta uno en silencio) y dos addons
 * con el mismo nombre de archivo se pisaban.
 */
class DeclarativeFamiliesMergeTest {

    private val roots = mutableListOf<File>()

    @Before
    fun setUp() {
        ConflictRegistry.clear()
        DirIndexCache.clear()
    }

    @After
    fun cleanUp() {
        roots.forEach { it.deleteRecursively() }
        ConflictRegistry.clear()
        DirIndexCache.clear()
    }

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { roots += it }

    private fun pack(name: String, files: Map<String, String>): File {
        val root = File(tempDir("pf_decl_"), name).apply { mkdirs() }
        files.forEach { (rel, content) ->
            val file = File(root, rel)
            file.parentFile?.mkdirs()
            file.writeText(content.trimIndent())
        }
        return root
    }

    private fun camera(id: String, extra: String = ""): String =
        """{"format_version":"1.20.60","minecraft:camera_preset":{"identifier":"$id","inherit_from":"free"$extra}}"""

    private fun archivos(dest: File, sub: String): List<String> =
        File(dest, sub).walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(dest).path.replace('\\', '/') }
            .sorted()
            .toList()

    private fun fusionar(dirs: List<File>, dest: File) = runBlocking {
        BedrockCriticalFilesMerger.mergeDeclarativeFamilies(dirs, dest)
    }

    @Test
    fun sameFilenameWithDifferentIdentifiersIsSeparated() {
        val a = pack("bp_a", mapOf("cameras/camera.json" to camera("alpha:cam")))
        val b = pack("bp_b", mapOf("cameras/camera.json" to camera("beta:cam")))
        val dest = tempDir("dest")

        fusionar(listOf(a, b), dest)

        val salidas = archivos(dest, "cameras")
        assertEquals("Cada cámara debe tener su archivo: $salidas", 2, salidas.size)
        val contenidos = salidas.map { File(dest, it).readText() }
        assertTrue(contenidos.any { it.contains("alpha:cam") })
        assertTrue(contenidos.any { it.contains("beta:cam") })
        assertTrue(
            "Uno de los dos debe separarse con _pf: $salidas",
            salidas.any { it.contains("_pf") }
        )
    }

    @Test
    fun sameIdentifierInTwoFilesIsConsolidatedAndStaleCopyDeleted() {
        // Simula la Fase 1: la copia eager ya dejó AMBOS archivos en el destino.
        val a = pack("bp_a", mapOf("cameras/a.json" to camera("shared:cam", ""","player_effects":false""")))
        val b = pack("bp_b", mapOf("cameras/b.json" to camera("shared:cam", ""","align_target_and_camera_forward":true""")))
        val dest = tempDir("dest")
        File(dest, "cameras").mkdirs()
        File(a, "cameras/a.json").copyTo(File(dest, "cameras/a.json"))
        File(b, "cameras/b.json").copyTo(File(dest, "cameras/b.json"))

        fusionar(listOf(a, b), dest)

        val salidas = archivos(dest, "cameras")
        assertEquals("El identifier debe quedar en UN archivo: $salidas", 1, salidas.size)
        assertFalse(
            "La copia obsoleta del segundo nombre debe borrarse",
            File(dest, "cameras/b.json").exists()
        )
        val json = JSONObject(File(dest, "cameras/a.json").readText())
        val preset = json.getJSONObject("minecraft:camera_preset")
        assertTrue("Se conservan los campos del primero", preset.has("player_effects"))
        assertTrue("Y los del segundo", preset.has("align_target_and_camera_forward"))

        assertTrue(
            "La fusión del mismo id debe reportarse",
            ConflictRegistry.conflicts.value.any { it.conflictType == "ENTITY_IDENTIFIER_MERGED" }
        )
    }

    @Test
    fun featureRulesAreRoutedByIdentifier() {
        fun regla(id: String) =
            """{"format_version":"1.13.0","minecraft:feature_rules":{"description":{"identifier":"$id"},"conditions":{"placement_pass":"surface_pass"},"distribution":{"iterations":1,"coordinate_eval_order":"xzy"}}}"""
        val a = pack("bp_a", mapOf("worldgen/feature_rules/oro.json" to regla("tools:oro")))
        val b = pack("bp_b", mapOf("worldgen/feature_rules/otro.json" to regla("tools:oro")))
        val dest = tempDir("dest")

        fusionar(listOf(a, b), dest)

        val salidas = archivos(dest, "worldgen/feature_rules")
        assertEquals("Una regla por identifier: $salidas", 1, salidas.size)
        assertTrue(File(dest, salidas.first()).readText().contains("tools:oro"))
    }

    // ── trading/ (sin identifier: se referencia por ruta) ───────────────

    private fun tabla(compra: String) =
        """{"format_version":1,"minecraft:trade_table":{"display_name":"Tabla","trades":[{"wants":[{"item":"$compra","quantity":1}],"gives":[{"item":"minecraft:emerald","quantity":1}]}]}}"""

    @Test
    fun tradingTablesFromTwoAddonsAreMergedAndReported() {
        val a = pack("bp_a", mapOf("trading/campesino.json" to tabla("minecraft:wheat")))
        val b = pack("bp_b", mapOf("trading/campesino.json" to tabla("minecraft:carrot")))
        val dest = tempDir("dest")

        BedrockCriticalFilesMerger.mergeTradingTables(listOf(a, b), dest)

        val json = JSONObject(File(dest, "trading/campesino.json").readText())
        val trades = json.getJSONObject("minecraft:trade_table").getJSONArray("trades")
        assertEquals("Se combinan las listas de trades", 2, trades.length())

        val conflicto = ConflictRegistry.conflicts.value
            .firstOrNull { it.conflictType == "TRADING_TABLE_MERGED" }
        assertNotNull("La tabla fusionada debe reportarse", conflicto)
        assertEquals("bp_a", conflicto!!.sourceAddon)
        assertEquals("bp_b", conflicto.targetAddon)
    }

    @Test
    fun identicalTradingTablesAreNotReported() {
        val a = pack("bp_a", mapOf("trading/campesino.json" to tabla("minecraft:wheat")))
        val b = pack("bp_b", mapOf("trading/campesino.json" to tabla("minecraft:wheat")))
        val dest = tempDir("dest")

        BedrockCriticalFilesMerger.mergeTradingTables(listOf(a, b), dest)

        assertFalse(
            "Contenido idéntico no es conflicto (regla 8)",
            ConflictRegistry.conflicts.value.any { it.conflictType == "TRADING_TABLE_MERGED" }
        )
    }
}
