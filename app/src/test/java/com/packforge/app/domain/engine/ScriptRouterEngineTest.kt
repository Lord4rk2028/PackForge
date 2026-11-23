package com.packforge.app.domain.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ScriptRouterEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testCompareSemver_StandardAndPrereleases() {
        assertTrue(ScriptRouterEngine.compareSemver("2.6.0", "1.16.0") > 0)
        assertTrue(ScriptRouterEngine.compareSemver("1.16.0", "1.8.0") > 0)
        assertTrue(ScriptRouterEngine.compareSemver("1.8.0", "2.6.0") < 0)
        assertTrue(ScriptRouterEngine.compareSemver("1.8.0", "1.8.0") == 0)
        assertTrue(ScriptRouterEngine.compareSemver("1.3.0", "1.3.0-beta") > 0)
        assertTrue(ScriptRouterEngine.compareSemver("1.3.0-beta.2", "1.3.0-beta.1") > 0)
        assertTrue(ScriptRouterEngine.compareSemver("2.0.0", "1.21.50") > 0)
    }

    @Test
    fun testExtractDependencyVersion_StringAndArrayFormats() {
        val stringDep = JSONObject().apply {
            put("module_name", "@minecraft/server")
            put("version", "1.16.0")
        }
        val arrayDep = JSONObject().apply {
            put("module_name", "@minecraft/server")
            put("version", JSONArray(listOf(2, 6, 0)))
        }

        assertEquals("1.16.0", ScriptRouterEngine.extractDependencyVersion(stringDep))
        assertEquals("2.6.0", ScriptRouterEngine.extractDependencyVersion(arrayDep))
    }

    @Test
    fun testScriptRouterEngine_RoutesMultipleAddonsAndCreatesMasterBootstrapper() {
        val bpDir1 = tempFolder.newFolder("combat_bp")
        val bpDir2 = tempFolder.newFolder("magic_bp")
        val mergedBpDir = tempFolder.newFolder("merged_bp")

        // BP 1 Manifest & Scripts
        File(bpDir1, "manifest.json").writeText("""
            {
              "format_version": 2,
              "header": { "name": "Combat Addon", "uuid": "c0010000-0000-0000-0000-000000000001", "version": [1,0,0] },
              "modules": [
                { "type": "script", "language": "javascript", "uuid": "c0010000-0000-0000-0000-000000000002", "version": [1,0,0], "entry": "scripts/combat_main.js" }
              ]
            }
        """.trimIndent())
        val bp1Scripts = File(bpDir1, "scripts").also { it.mkdirs() }
        File(bp1Scripts, "combat_main.js").writeText("console.log('Combat script loaded');")
        File(bp1Scripts, "helper.js").writeText("export const damage = 10;")

        // BP 2 Manifest & Scripts
        File(bpDir2, "manifest.json").writeText("""
            {
              "format_version": 2,
              "header": { "name": "Magic Addon", "uuid": "m0020000-0000-0000-0000-000000000001", "version": [1,0,0] },
              "modules": [
                { "type": "script", "language": "javascript", "uuid": "m0020000-0000-0000-0000-000000000002", "version": [1,0,0], "entry": "scripts/magic_main.js" }
              ]
            }
        """.trimIndent())
        val bp2Scripts = File(bpDir2, "scripts").also { it.mkdirs() }
        File(bp2Scripts, "magic_main.js").writeText("console.log('Magic script loaded');")

        // Executing router
        val result = ScriptRouterEngine.routeAndBuildBootstrapper(listOf(bpDir1, bpDir2), mergedBpDir)

        assertEquals(2, result.routedCount)
        val bootstrapper = result.bootstrapperFile
        assertNotNull(bootstrapper)
        assertTrue(bootstrapper!!.exists())

        // Verify master bootstrapper content: imports ESTÃTICOS (Bedrock no
        // documenta import() dinÃ¡mico entre archivos del pack).
        val masterContent = bootstrapper.readText()
        assertTrue(masterContent.contains("import \"./modules/"))
        assertTrue(masterContent.contains("combat_main.js"))
        assertTrue(masterContent.contains("magic_main.js"))

        // Verify manifest.json in mergedBpDir
        val mergedManifestFile = File(mergedBpDir, "manifest.json")
        assertTrue(mergedManifestFile.exists())
        val mergedManifest = JSONObject(mergedManifestFile.readText())

        val modules = mergedManifest.getJSONArray("modules")
        var hasScriptModule = false
        for (i in 0 until modules.length()) {
            val mod = modules.getJSONObject(i)
            if (mod.getString("type") == "script") {
                hasScriptModule = true
                assertEquals("scripts/main.js", mod.getString("entry"))
            }
        }
        assertTrue(hasScriptModule)

        // Verify dependencies contain @minecraft/server
        val dependencies = mergedManifest.getJSONArray("dependencies")
        var hasMcServerDep = false
        for (i in 0 until dependencies.length()) {
            val dep = dependencies.getJSONObject(i)
            if (dep.optString("module_name") == "@minecraft/server") {
                hasMcServerDep = true
            }
        }
        assertTrue(hasMcServerDep)
    }

    @Test
    fun testRealWorldAddons_SimpleEconomyAndGravestoneAndResourcePack() {
        val economyBpDir = tempFolder.newFolder("SimpleEconomy_BP")
        val rpOnlyDir = tempFolder.newFolder("Just-Health-Indicator_RP")
        val gravestoneBpDir = tempFolder.newFolder("simple-gravestone_BP")
        val mergedBpDir = tempFolder.newFolder("merged_real_bp")

        // 1. SimpleEconomy: format_version 2, @minecraft/server 1.16.0, @minecraft/server-ui 1.3.0
        // Multiple intra-module files: main.js, jobs.js, politics.js, shops.js
        File(economyBpDir, "manifest.json").writeText("""
            {
              "format_version": 2,
              "header": { "name": "EconomyBP", "uuid": "e0000000-0000-0000-0000-000000000001", "version": [2,10,0] },
              "modules": [
                { "type": "script", "language": "javascript", "uuid": "e0000000-0000-0000-0000-000000000002", "version": [2,10,0], "entry": "scripts/main.js" }
              ],
              "dependencies": [
                { "module_name": "@minecraft/server", "version": "1.16.0" },
                { "module_name": "@minecraft/server-ui", "version": "1.3.0" }
              ]
            }
        """.trimIndent())
        val econScripts = File(economyBpDir, "scripts").also { it.mkdirs() }
        File(econScripts, "main.js").writeText("""
            import { world, system } from "@minecraft/server";
            import { ActionFormData } from "@minecraft/server-ui";
            import { initJobs } from "./jobs.js";
            import { initPolitics } from "./politics.js";
            import { initShops } from "./shops.js";
            initJobs();
            initPolitics();
            initShops();
        """.trimIndent())
        File(econScripts, "jobs.js").writeText("export function initJobs() { return 'jobs'; }")
        File(econScripts, "politics.js").writeText("export function initPolitics() { return 'politics'; }")
        File(econScripts, "shops.js").writeText("export function initShops() { return 'shops'; }")

        // 2. Just-Health-Indicator: RP ONLY, no scripts, format_version 2
        File(rpOnlyDir, "manifest.json").writeText("""
            {
              "format_version": 2,
              "header": { "name": "Just-Health-Indicator", "uuid": "h0000000-0000-0000-0000-000000000001", "version": [1,0,0] },
              "modules": [
                { "type": "resources", "uuid": "h0000000-0000-0000-0000-000000000002", "version": [1,0,0] }
              ]
            }
        """.trimIndent())

        // 3. simple-gravestone: format_version 3, @minecraft/server 2.6.0, entry scripts/index.js
        File(gravestoneBpDir, "manifest.json").writeText("""
            {
              "format_version": 3,
              "header": { "name": "simple-gravestone_BP", "uuid": "g0000000-0000-0000-0000-000000000001", "version": "1.1.0" },
              "modules": [
                { "type": "script", "language": "javascript", "uuid": "g0000000-0000-0000-0000-000000000002", "version": "1.1.0", "entry": "scripts/index.js" }
              ],
              "dependencies": [
                { "module_name": "@minecraft/server", "version": "2.6.0" }
              ]
            }
        """.trimIndent())
        val gravestoneScripts = File(gravestoneBpDir, "scripts").also { it.mkdirs() }
        File(gravestoneScripts, "index.js").writeText("""
            import { world } from "@minecraft/server";
            world.afterEvents.entityDie.subscribe((ev) => {
                console.warn('Entity died, spawning gravestone');
            });
        """.trimIndent())

        // Execute routeAndBuildBootstrapper with all 3 addons
        val result = ScriptRouterEngine.routeAndBuildBootstrapper(
            listOf(economyBpDir, rpOnlyDir, gravestoneBpDir),
            mergedBpDir
        )

        // 1. Check routed count: Only Economy and Gravestone have scripts (RP-only is ignored)
        assertEquals(2, result.routedCount)

        // 2. Check harmonized dependencies:
        // @minecraft/server must be 2.6.0 (highest between 1.16.0 and 2.6.0)
        //
        // â­ TICKET-CORE-007: @minecraft/server-ui NO se queda en su mÃ¡ximo
        // (1.3.0). Como server sube a major 2 (gravestone usa beforeEvents.startup
        // y blockComponentRegistry), server-ui 1.3.0 â€”que exige server 1.xâ€” se
        // eleva al major de server. Con la mezcla 2.6.0 + 1.3.0 el motor lanzaba
        // "version conflict for module [@minecraft/server]" y NO creaba contexto:
        // ningÃºn script arrancaba (ni la tumba, ni la UI del banco).
        assertEquals("2.6.0", result.libraryDeps["@minecraft/server"])
        assertEquals("2.0.0", result.libraryDeps["@minecraft/server-ui"])

        // 3. Check intra-module files of SimpleEconomy: all preserved intact!
        val econModuleDir = File(mergedBpDir, "scripts/modules/simpleeconomy_1")
        assertTrue(File(econModuleDir, "main.js").exists())
        assertTrue(File(econModuleDir, "jobs.js").exists())
        assertTrue(File(econModuleDir, "politics.js").exists())
        assertTrue(File(econModuleDir, "shops.js").exists())

        // 4. Check gravestone script located at index.js
        val gravestoneModuleDir = File(mergedBpDir, "scripts/modules/simple_gravestone_3")
        assertTrue(File(gravestoneModuleDir, "index.js").exists())

        // 5. Check master bootstrapper: imports ESTÃTICOS (no import() dinÃ¡mico,
        // que Bedrock no documenta y el pack real probado no ejecutÃ³ ningÃºn script).
        val bootstrapperText = result.bootstrapperFile!!.readText()
        assertTrue(bootstrapperText.contains("import \"./modules/simpleeconomy_1/main.js\";"))
        assertTrue(bootstrapperText.contains("import \"./modules/simple_gravestone_3/index.js\";"))
        assertFalse(bootstrapperText.contains("health_indicator")) // RP-only pack must not be referenced

        // 6. Check merged manifest.json dependencies
        val mergedManifest = JSONObject(File(mergedBpDir, "manifest.json").readText())
        val deps = mergedManifest.getJSONArray("dependencies")
        var mcServerVer: String? = null
        var mcUiVer: String? = null
        for (i in 0 until deps.length()) {
            val dep = deps.getJSONObject(i)
            when (dep.optString("module_name")) {
                "@minecraft/server" -> mcServerVer = dep.getString("version")
                "@minecraft/server-ui" -> mcUiVer = dep.getString("version")
            }
        }
        assertEquals("2.6.0", mcServerVer)
        assertEquals("2.0.0", mcUiVer) // alineado al major de server (TICKET-CORE-007)
    }

    @Test
    fun testUndeclaredCodeImports_AutomaticallyDetectedAndInjected() {
        val bpDir = tempFolder.newFolder("ui_script_bp")
        val mergedBpDir = tempFolder.newFolder("merged_ui_bp")

        // Manifest declares only @minecraft/server, but code imports @minecraft/server-ui
        File(bpDir, "manifest.json").writeText("""
            {
              "format_version": 2,
              "header": { "name": "UI Script", "uuid": "u0000000-0000-0000-0000-000000000001", "version": [1,0,0] },
              "modules": [
                { "type": "script", "language": "javascript", "uuid": "u0000000-0000-0000-0000-000000000002", "version": [1,0,0], "entry": "scripts/main.js" }
              ],
              "dependencies": [
                { "module_name": "@minecraft/server", "version": "1.12.0" }
              ]
            }
        """.trimIndent())
        val scripts = File(bpDir, "scripts").also { it.mkdirs() }
        File(scripts, "main.js").writeText("""
            import { ActionFormData, ModalFormData } from "@minecraft/server-ui";
            console.log('UI script loaded');
        """.trimIndent())

        val result = ScriptRouterEngine.routeAndBuildBootstrapper(listOf(bpDir), mergedBpDir)
        assertEquals(1, result.routedCount)

        // @minecraft/server-ui should have been detected from code and injected
        assertTrue(result.libraryDeps.containsKey("@minecraft/server-ui"))
        assertEquals("1.3.0", result.libraryDeps["@minecraft/server-ui"])

        val mergedManifest = JSONObject(File(mergedBpDir, "manifest.json").readText())
        val deps = mergedManifest.getJSONArray("dependencies")
        var foundUi = false
        for (i in 0 until deps.length()) {
            val dep = deps.getJSONObject(i)
            if (dep.optString("module_name") == "@minecraft/server-ui") {
                foundUi = true
                assertEquals("1.3.0", dep.getString("version"))
            }
        }
        assertTrue(foundUi)
    }

    /**
     * 3.7 — Dos addons que piden majors distintos de `@minecraft/server` no
     * pueden convivir en UN Behavior Pack. Se reporta con atribución; exportar
     * dos BPs (multi-BP) todavía no está implementado.
     */
    @Test
    fun apiMajorConflictIsReportedWithTheAddonThatNeedsTheOtherMajor() {
        ConflictRegistry.clear()
        val viejo = tempFolder.newFolder("api_viejo")
        val nuevo = tempFolder.newFolder("api_nuevo")
        fun manifiesto(dir: File, version: String) {
            File(dir, "manifest.json").writeText(
                """
                {
                  "format_version": 2,
                  "header": { "name": "x", "uuid": "00000000-0000-0000-0000-000000000001", "version": [1,0,0] },
                  "modules": [ { "type": "data", "uuid": "00000000-0000-0000-0000-000000000002", "version": [1,0,0] } ],
                  "dependencies": [ { "module_name": "@minecraft/server", "version": "$version" } ]
                }
                """.trimIndent()
            )
        }
        manifiesto(viejo, "1.16.0")
        manifiesto(nuevo, "2.6.0")

        ScriptRouterEngine.reportApiMajorConflicts(
            listOf(viejo, nuevo),
            mapOf("@minecraft/server" to "2.6.0")
        )

        val conflictos = ConflictRegistry.conflicts.value
            .filter { it.conflictType == "SCRIPT_API_MAJOR_CONFLICT" }
        assertEquals("Solo el addon que pide 1.x queda fuera", 1, conflictos.size)
        assertEquals("api_viejo", conflictos.first().sourceAddon)
        assertTrue(conflictos.first().description.contains("1.16.0"))
        assertTrue(conflictos.first().description.contains("2.6.0"))
        ConflictRegistry.clear()
    }

    @Test
    fun sameApiMajorIsNotReported() {
        ConflictRegistry.clear()
        val a = tempFolder.newFolder("api_a")
        fun manifiesto(dir: File, version: String) {
            File(dir, "manifest.json").writeText(
                """
                {
                  "format_version": 2,
                  "header": { "name": "x", "uuid": "00000000-0000-0000-0000-000000000001", "version": [1,0,0] },
                  "modules": [ { "type": "data", "uuid": "00000000-0000-0000-0000-000000000002", "version": [1,0,0] } ],
                  "dependencies": [ { "module_name": "@minecraft/server", "version": "$version" } ]
                }
                """.trimIndent()
            )
        }
        manifiesto(a, "2.0.0")

        ScriptRouterEngine.reportApiMajorConflicts(
            listOf(a),
            mapOf("@minecraft/server" to "2.6.0")
        )

        assertTrue(
            "Un minor mayor dentro del mismo major no es conflicto",
            ConflictRegistry.conflicts.value.none { it.conflictType == "SCRIPT_API_MAJOR_CONFLICT" }
        )
        ConflictRegistry.clear()
    }
}
