package com.packforge.app.domain.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tests de [ScriptRegistry] y [ScriptFlowAnalyzer].
 *
 * Cubren los riesgos reales que buscamos neutralizar antes de fusionar:
 * entry point inexistente, ciclos de importación, entry points duplicados
 * entre addons y packs sin scripts.
 */
class ScriptFlowAnalyzerTest {

    private val roots = mutableListOf<File>()

    @After
    fun cleanUp() {
        roots.forEach { it.deleteRecursively() }
    }

    // ── Utilidades ───────────────────────────────────────────────────────

    private fun tempDir(): File =
        Files.createTempDirectory("pf_scripts_").toFile().also { roots += it }

    private fun manifestJson(
        uuid: String,
        entry: String,
        version: String = "1.0.0",
        extraDependencies: String = ""
    ): String = """
        {
          "format_version": 2,
          "header": {
            "name": "Test Pack",
            "description": "pack de prueba",
            "uuid": "$uuid",
            "version": [1, 0, 0],
            "min_engine_version": [1, 20, 0]
          },
          "modules": [
            { "type": "script", "language": "javascript", "uuid": "${uuid}-mod", "entry": "$entry", "version": [1, 0, 0] }
          ]$extraDependencies
        }
    """.trimIndent()

    /** Crea un Behavior Pack en carpeta con su manifest y archivos de script. */
    private fun pack(
        dirName: String,
        entry: String,
        sources: Map<String, String>,
        uuid: String = "11111111-1111-1111-1111-111111111111",
        extraDependencies: String = ""
    ): File {
        val root = File(tempDir(), dirName).apply { mkdirs() }
        File(root, "manifest.json").writeText(manifestJson(uuid, entry, extraDependencies = extraDependencies))
        sources.forEach { (path, content) ->
            val file = File(root, path)
            file.parentFile?.mkdirs()
            file.writeText(content)
        }
        return root
    }

    // ── Registry ─────────────────────────────────────────────────────────

    @Test
    fun registryReadsScriptModuleFromDirectory() {
        val root = pack("bp", "scripts/main.js", mapOf("scripts/main.js" to "console.log('hi')"))

        val scripts = ScriptRegistry.loadScriptsFromBPs(listOf(root.absolutePath))

        assertEquals(1, scripts.size)
        val script = scripts.values.first()
        assertEquals("scripts/main.js", script.entryPath)
        assertTrue(script.sourceText.contains("console.log"))
    }

    @Test
    fun registryIgnoresPacksWithoutScriptModules() {
        val root = File(tempDir(), "rp").apply { mkdirs() }
        File(root, "manifest.json").writeText(
            """{"format_version":2,"header":{"uuid":"x","version":[1,0,0]},"modules":[{"type":"resources","uuid":"y"}]}"""
        )

        assertTrue(ScriptRegistry.loadScriptsFromBPs(listOf(root.absolutePath)).isEmpty())
    }

    @Test
    fun registryReadsScriptsFromZip() {
        val zipFile = File(tempDir(), "addon.mcpack")
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(
                manifestJson("22222222-2222-2222-2222-222222222222", "scripts/main.js")
                    .toByteArray()
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("scripts/main.js"))
            zip.write("import { world } from '@minecraft/server'".toByteArray())
            zip.closeEntry()
        }

        val scripts = ScriptRegistry.loadScriptsFromBPs(listOf(zipFile.absolutePath))

        assertEquals(1, scripts.size)
        assertTrue(scripts.values.first().sourceText.contains("@minecraft/server"))
    }

    // ── Analyzer ─────────────────────────────────────────────────────────

    @Test
    fun packWithoutScriptsIsSafe() {
        val decision = ScriptFlowAnalyzer.getGateDecision(bpDirs = emptyList())

        assertTrue(decision.canExecute)
        assertEquals(ScriptRiskLevel.SAFE, decision.riskLevel)
        assertEquals(RecommendedAction.EXECUTE, decision.recommendedAction)
    }

    @Test
    fun missingEntryPointBlocksExport() {
        val root = File(tempDir(), "bp_missing").apply { mkdirs() }
        File(root, "manifest.json").writeText(
            manifestJson("33333333-3333-3333-3333-333333333333", "scripts/missing.js")
        )

        val decision = ScriptFlowAnalyzer.getGateDecision(listOf(root.absolutePath))

        assertFalse(decision.canExecute)
        assertEquals(ScriptRiskLevel.BLOCKED, decision.riskLevel)
        assertTrue(
            decision.blockers.any { it.conflictType == ScriptConflictType.ENTRY_INVALID }
        )
        assertEquals(RecommendedAction.FIX_ISSUES, decision.recommendedAction)
    }

    @Test
    fun importCycleBetweenPacksIsDetected() {
        // Dos packs cuyos entry points se importan mutuamente → ciclo.
        val first = pack(
            "bp_a", "a.js",
            mapOf("a.js" to "import './b.js'\nexport const a = 1"),
            uuid = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        )
        val second = pack(
            "bp_b", "b.js",
            mapOf("b.js" to "import './a.js'\nexport const b = 2"),
            uuid = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        )

        val decision = ScriptFlowAnalyzer.getGateDecision(
            listOf(first.absolutePath, second.absolutePath)
        )

        assertTrue(
            decision.blockers.any { it.conflictType == ScriptConflictType.IMPORT_CYCLE }
        )
        assertFalse(decision.canExecute)
    }

    @Test
    fun duplicateEntryBetweenPacksIsReportedAsWarningOnly() {
        val first = pack(
            "bp_1", "scripts/main.js",
            mapOf("scripts/main.js" to "console.log(1)"),
            uuid = "cccccccc-cccc-cccc-cccc-cccccccccccc"
        )
        val second = pack(
            "bp_2", "scripts/main.js",
            mapOf("scripts/main.js" to "console.log(2)"),
            uuid = "dddddddd-dddd-dddd-dddd-dddddddddddd"
        )

        val decision = ScriptFlowAnalyzer.getGateDecision(
            listOf(first.absolutePath, second.absolutePath)
        )

        assertTrue(
            decision.warnings.any {
                it.conflictType == ScriptConflictType.IDENTIFIER_COLLISION
            }
        )
        // El pipeline separa los entry points al fusionar: no debe bloquear.
        assertTrue(decision.canExecute)
    }

    @Test
    fun missingRelativeImportIsWarned() {
        val root = pack(
            "bp_import", "scripts/main.js",
            mapOf("scripts/main.js" to "import './nope.js'")
        )

        val decision = ScriptFlowAnalyzer.getGateDecision(listOf(root.absolutePath))

        assertTrue(
            decision.warnings.any { it.conflictType == ScriptConflictType.DEPENDENCY_MISSING }
        )
        assertTrue(decision.canExecute)
    }

    @Test
    fun existingRelativeImportIsNotWarned() {
        val root = pack(
            "bp_ok", "scripts/main.js",
            mapOf(
                "scripts/main.js" to "import './util.js'",
                "scripts/util.js" to "export const x = 1"
            )
        )

        val decision = ScriptFlowAnalyzer.getGateDecision(listOf(root.absolutePath))

        assertFalse(
            decision.warnings.any { it.conflictType == ScriptConflictType.DEPENDENCY_MISSING }
        )
        assertTrue(decision.canExecute)
        assertEquals(ScriptRiskLevel.SAFE, decision.riskLevel)
    }

    @Test
    fun analyzeScriptsAggregatesTotals() {
        val root = pack(
            "bp_total", "scripts/main.js",
            mapOf("scripts/main.js" to "console.log('ok')")
        )

        val findings = ScriptFlowAnalyzer.analyzeScripts(listOf(root.absolutePath))

        assertEquals(1, findings.totalScripts)
        assertEquals(1, findings.validScripts)
        assertEquals(0, findings.invalidScripts)
        assertEquals(ScriptRiskLevel.SAFE, findings.riskLevel)
        assertEquals(1, findings.byScript.size)
    }

    @Test
    fun serverOnlyModuleBlocksExport() {
        val serverNetDep = """, "dependencies": [ { "module_name": "@minecraft/server-net", "version": "1.0.0" } ]"""
        val root = pack(
            "bp_server_net", "scripts/main.js",
            mapOf("scripts/main.js" to "console.log('test')"),
            extraDependencies = serverNetDep
        )

        val decision = ScriptFlowAnalyzer.getGateDecision(listOf(root.absolutePath))

        assertFalse(decision.canExecute)
        assertEquals(ScriptRiskLevel.BLOCKED, decision.riskLevel)
        assertTrue(decision.blockers.any { it.conflictType == ScriptConflictType.SERVER_ONLY_MODULE })
    }

    @Test
    fun legacyScriptApiBlocksExport() {
        val legacyDep = """, "dependencies": [ { "module_name": "mojang-minecraft", "version": "1.0.0" } ]"""
        val root = pack(
            "bp_legacy", "scripts/main.js",
            mapOf("scripts/main.js" to "console.log('old')"),
            extraDependencies = legacyDep
        )

        val decision = ScriptFlowAnalyzer.getGateDecision(listOf(root.absolutePath))

        assertFalse(decision.canExecute)
        assertEquals(ScriptRiskLevel.BLOCKED, decision.riskLevel)
        assertTrue(decision.blockers.any { it.conflictType == ScriptConflictType.LEGACY_SCRIPT_API })
    }

    @Test
    fun mixedBetaAndStableTracksBlocksExport() {
        val stableDep = """, "dependencies": [ { "module_name": "@minecraft/server", "version": "1.13.0" } ]"""
        val betaDep = """, "dependencies": [ { "module_name": "@minecraft/server", "version": "1.14.0-beta" } ]"""

        val pack1 = pack(
            "bp_stable", "scripts/main.js",
            mapOf("scripts/main.js" to "console.log('stable')"),
            uuid = "11111111-aaaa-1111-1111-111111111111",
            extraDependencies = stableDep
        )
        val pack2 = pack(
            "bp_beta", "scripts/main.js",
            mapOf("scripts/main.js" to "console.log('beta')"),
            uuid = "22222222-bbbb-2222-2222-222222222222",
            extraDependencies = betaDep
        )

        val decision = ScriptFlowAnalyzer.getGateDecision(listOf(pack1.absolutePath, pack2.absolutePath))

        assertFalse(decision.canExecute)
        assertEquals(ScriptRiskLevel.BLOCKED, decision.riskLevel)
        assertTrue(decision.blockers.any { it.conflictType == ScriptConflictType.API_TRACK_MISMATCH })
    }

    @Test
    fun duplicateCustomComponentBlocksExport() {
        val pack1 = pack(
            "bp_comp1", "scripts/main.js",
            mapOf("scripts/main.js" to "registerCustomComponent('pf:gun', {})"),
            uuid = "33333333-cccc-3333-3333-333333333333"
        )
        val pack2 = pack(
            "bp_comp2", "scripts/main.js",
            mapOf("scripts/main.js" to "registerCustomComponent('pf:gun', {})"),
            uuid = "44444444-dddd-4444-4444-444444444444"
        )

        val decision = ScriptFlowAnalyzer.getGateDecision(listOf(pack1.absolutePath, pack2.absolutePath))

        assertFalse(decision.canExecute)
        assertEquals(ScriptRiskLevel.BLOCKED, decision.riskLevel)
        assertTrue(decision.blockers.any { it.conflictType == ScriptConflictType.CUSTOM_COMPONENT })
    }

    @Test
    fun deepScriptScanningDetectsComponentsInSubmodules() {
        // En pack1, registerCustomComponent está en scripts/sub/weapons.js, no en main.js
        val pack1 = pack(
            "bp_deep1", "scripts/main.js",
            mapOf(
                "scripts/main.js" to "import './sub/weapons.js'",
                "scripts/sub/weapons.js" to "registerCustomComponent('pf:laser', {})"
            ),
            uuid = "55555555-eeee-5555-5555-555555555555"
        )
        val pack2 = pack(
            "bp_deep2", "scripts/main.js",
            mapOf("scripts/main.js" to "registerCustomComponent('pf:laser', {})"),
            uuid = "66666666-ffff-6666-6666-666666666666"
        )

        val decision = ScriptFlowAnalyzer.getGateDecision(listOf(pack1.absolutePath, pack2.absolutePath))

        assertFalse(decision.canExecute)
        assertTrue(decision.blockers.any { it.conflictType == ScriptConflictType.CUSTOM_COMPONENT })
    }

    @Test
    fun beforeEventsSubscriptionGeneratesWarning() {
        val pack1 = pack(
            "bp_ev1", "scripts/main.js",
            mapOf("scripts/main.js" to "world.beforeEvents.itemUse.subscribe(e => {})"),
            uuid = "77777777-1111-7777-7777-777777777777"
        )
        val pack2 = pack(
            "bp_ev2", "scripts/main.js",
            mapOf("scripts/main.js" to "world.beforeEvents.itemUse.subscribe(e => { e.cancel = true; })"),
            uuid = "88888888-2222-8888-8888-888888888888"
        )

        val decision = ScriptFlowAnalyzer.getGateDecision(listOf(pack1.absolutePath, pack2.absolutePath))

        assertTrue(decision.warnings.any { it.conflictType == ScriptConflictType.BEFORE_EVENTS_CONFLICT })
    }

    @Test
    fun globalThisCollisionGeneratesWarning() {
        val pack1 = pack(
            "bp_gt1", "scripts/main.js",
            mapOf("scripts/main.js" to "globalThis.packState = { hp: 100 };"),
            uuid = "99999999-3333-9999-9999-999999999999"
        )
        val pack2 = pack(
            "bp_gt2", "scripts/main.js",
            mapOf("scripts/main.js" to "globalThis.packState = { mana: 50 };"),
            uuid = "00000000-4444-0000-0000-000000000000"
        )

        val decision = ScriptFlowAnalyzer.getGateDecision(listOf(pack1.absolutePath, pack2.absolutePath))

        assertTrue(decision.warnings.any { it.conflictType == ScriptConflictType.GLOBAL_POLLUTION })
    }
}
