package com.packforge.app.domain.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets

class ManifestGeneratorTest {

    private fun manifestJson(
        name: String = "Pack",
        mev: Array<Int> = arrayOf(1, 20, 0),
        modules: List<Triple<String, String, JSONArray>> = emptyList(), // type, uuid, version
        dependencies: List<JSONObject> = emptyList(),
        capabilities: List<String> = emptyList()
    ): JSONObject = JSONObject().apply {
        put("format_version", 2)
        put("header", JSONObject().apply {
            put("name", name)
            put("description", "test")
            put("uuid", java.util.UUID.randomUUID().toString())
            put("version", JSONArray(listOf(1, 0, 0)))
            put("min_engine_version", JSONArray(mev.toList()))
            if (capabilities.isNotEmpty()) put("capabilities", JSONArray(capabilities))
        })
        if (modules.isNotEmpty()) put("modules", JSONArray().apply {
            modules.forEach { (t, uuid, ver) -> put(JSONObject().apply {
                put("type", t); put("uuid", uuid); put("version", ver)
            })}
        })
        if (dependencies.isNotEmpty()) put("dependencies", JSONArray().apply {
            dependencies.forEach { put(it) }
        })
    }

    private fun tempManifest(content: JSONObject): File {
        val f = File.createTempFile("manifest_", ".json")
        f.writeText(content.toString(), StandardCharsets.UTF_8)
        f.deleteOnExit()
        return f
    }

    @Test
    fun buildMergedBpManifest_minEngineVersionMax() {
        val low = tempManifest(manifestJson(mev = arrayOf(1, 20, 0)))
        val high = tempManifest(manifestJson(mev = arrayOf(1, 21, 80)))
        val out = ManifestGenerator.buildMergedBpManifest(
            originalBpManifests = listOf(low, high),
            originalRpHeaderUuids = emptySet(),
            newRpHeaderUuid = null,
            packName = "TestPack",
            hasScriptsFolder = false
        )
        val mev = out.getJSONObject("header").getJSONArray("min_engine_version")
        assertEquals(1, mev.getInt(0)); assertEquals(21, mev.getInt(1)); assertEquals(80, mev.getInt(2))
    }

    @Test
    fun buildMergedBpManifest_rpDependencyLinked() {
        val rpUuid = java.util.UUID.randomUUID().toString()
        val bp = tempManifest(manifestJson())
        val out = ManifestGenerator.buildMergedBpManifest(
            originalBpManifests = listOf(bp),
            originalRpHeaderUuids = emptySet(),
            newRpHeaderUuid = rpUuid,
            packName = "Fusion",
            hasScriptsFolder = false
        )
        val deps = out.getJSONArray("dependencies")
        var found = false
        for (i in 0 until deps.length()) if (deps.optJSONObject(i)?.optString("uuid")?.equals(rpUuid, true) == true) found = true
        assertTrue("El BP debe tener dependencia al RP fusionado", found)
    }

    @Test
    fun buildMergedBpManifest_oldRpUuidRewired() {
        val oldRp = java.util.UUID.randomUUID().toString()
        val newRp = java.util.UUID.randomUUID().toString()
        val bp = tempManifest(manifestJson(dependencies = listOf(JSONObject().apply {
            put("uuid", oldRp); put("version", JSONArray(listOf(1, 0, 0)))
        })))
        val out = ManifestGenerator.buildMergedBpManifest(
            originalBpManifests = listOf(bp),
            originalRpHeaderUuids = setOf(oldRp),
            newRpHeaderUuid = newRp,
            packName = "Rewire",
            hasScriptsFolder = false
        )
        val deps = out.getJSONArray("dependencies")
        var hasOld = false; var hasNew = false
        for (i in 0 until deps.length()) {
            val u = deps.optJSONObject(i)?.optString("uuid")?.lowercase()
            if (u == oldRp.lowercase()) hasOld = true
            if (u == newRp.lowercase()) hasNew = true
        }
        assertFalse("El uuid viejo del RP no debe conservarse", hasOld)
        assertTrue("El nuevo uuid del RP debe estar vinculado", hasNew)
    }

    @Test
    fun buildMergedBpManifest_dedupeDepsKeepsHighestVersion() {
        val m1 = tempManifest(manifestJson(dependencies = listOf(JSONObject().apply {
            put("module_name", "@minecraft/server"); put("version", "1.4.0")
        })))
        val m2 = tempManifest(manifestJson(dependencies = listOf(JSONObject().apply {
            put("module_name", "@minecraft/server"); put("version", "2.0.0")
        })))
        val out = ManifestGenerator.buildMergedBpManifest(
            originalBpManifests = listOf(m1, m2),
            originalRpHeaderUuids = emptySet(),
            newRpHeaderUuid = null,
            packName = "Dedupe",
            hasScriptsFolder = false
        )
        val deps = out.getJSONArray("dependencies")
        var ver: JSONArray? = null
        for (i in 0 until deps.length()) {
            val d = deps.optJSONObject(i) ?: continue
            if (d.optString("module_name") == "@minecraft/server") ver = d.optJSONArray("version")
        }
        assertNotNull(ver); assertEquals("2", (ver!!.opt(0) ?: "").toString())
    }

    @Test
    fun generateRpManifest_hasMinEngineVersion() {
        val rp = ManifestGenerator.generateRpManifest("MiMod")
        val header = rp.getJSONObject("header")
        assertTrue(header.has("min_engine_version"))
        val mev = header.getJSONArray("min_engine_version")
        assertTrue(mev.getInt(0) >= 1 && mev.getInt(1) >= 20)
    }

    @Test
    fun writeManifestToFile_isUtf8NoBom() {
        val out = File.createTempFile("manifest_out_", ".json")
        out.deleteOnExit()
        ManifestGenerator.writeManifestToFile("""{"format_version":2}""", out)
        val bytes = out.readBytes()
        assertTrue(bytes.isNotEmpty())
        // BOM es EF BB BF — no debe empezar así
        val hasBom = bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        assertFalse("manifest no debe tener BOM", hasBom)
    }
}
