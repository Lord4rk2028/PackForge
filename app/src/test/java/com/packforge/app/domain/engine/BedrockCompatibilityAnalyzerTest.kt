package com.packforge.app.domain.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BedrockCompatibilityAnalyzerTest {
    private val roots = mutableListOf<File>()

    @After
    fun cleanUp() {
        roots.forEach { it.deleteRecursively() }
    }

    private fun behaviorPack(uuid: String, entry: String = "scripts/main.js", code: String): File {
        val root = Files.createTempDirectory("pf_compat_").toFile().also { roots += it }
        File(root, "manifest.json").writeText(
            """{"format_version":2,"header":{"uuid":"$uuid"},"modules":[{"type":"script","entry":"$entry"}]}"""
        )
        File(root, "scripts/main.js").apply {
            parentFile.mkdirs()
            writeText(code)
        }
        return root
    }

    @Test
    fun duplicateCustomComponentBlocksExport() {
        val first = behaviorPack("11111111-1111-1111-1111-111111111111", code =
            "registry.registerCustomComponent('forge:weapon', {})")
        val second = behaviorPack("22222222-2222-2222-2222-222222222222", code =
            "registry.registerCustomComponent('forge:weapon', {})")

        val findings = BedrockCompatibilityAnalyzer.analyze(listOf(first.path, second.path))

        assertTrue(findings.any { it.type == "CUSTOM_COMPONENT_DUPLICATE" && it.blocksExport })
    }

    @Test
    fun sharedDynamicPropertyIsReportedButDoesNotBlockExport() {
        val first = behaviorPack("11111111-1111-1111-1111-111111111111", code =
            "world.setDynamicProperty('shared:light', 1)")
        val second = behaviorPack("22222222-2222-2222-2222-222222222222", code =
            "world.getDynamicProperty('shared:light')")

        val findings = BedrockCompatibilityAnalyzer.analyze(listOf(first.path, second.path))

        assertEquals(1, findings.count { it.type == "DYNAMIC_PROPERTY_SHARED" })
        assertTrue(findings.none { it.type == "DYNAMIC_PROPERTY_SHARED" && it.blocksExport })
    }

    @Test
    fun missingScriptEntryBlocksExport() {
        val pack = behaviorPack(
            "11111111-1111-1111-1111-111111111111",
            entry = "scripts/missing.js",
            code = "export {}"
        )

        val findings = BedrockCompatibilityAnalyzer.analyze(listOf(pack.path))

        assertTrue(findings.any { it.type == "SCRIPT_ENTRY_INVALID" && it.blocksExport })
    }
}
