package com.packforge.app.domain.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Tests exhaustivos para [BedrockUiMerger].
 */
class BedrockUiMergerTest {

    private val tempDirs = mutableListOf<File>()

    @After
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun tempDir(prefix: String): File {
        return Files.createTempDirectory(prefix).toFile().also { tempDirs.add(it) }
    }

    @Test
    fun mergeUiDefs_combinesAllDefsWithoutDuplicates() {
        val root1 = tempDir("rp1")
        val uiDir1 = File(root1, "ui").apply { mkdirs() }
        File(uiDir1, "_ui_defs.json").writeText(
            """
            {
              "ui_defs": [
                "ui/hud_screen.json",
                "ui/mana_screen.json"
              ]
            }
            """.trimIndent()
        )

        val root2 = tempDir("rp2")
        val uiDir2 = File(root2, "ui").apply { mkdirs() }
        File(uiDir2, "_ui_defs.json").writeText(
            """
            {
              "ui_defs": [
                "ui/hud_screen.json",
                "ui/stamina_screen.json"
              ]
            }
            """.trimIndent()
        )
        // Addon 2 también tiene un archivo UI nuevo sin registrar explícitamente en su manifest
        File(uiDir2, "spell_book.json").writeText("""{"namespace": "spells"}""")

        val mergedRp = tempDir("merged_rp")
        val report = BedrockUiMerger.mergeUi(listOf(root1, root2), mergedRp)

        val resultDefsFile = File(mergedRp, "ui/_ui_defs.json")
        assertTrue(resultDefsFile.exists())

        val resultJson = JSONObject(resultDefsFile.readText())
        val arr = resultJson.getJSONArray("ui_defs")

        val defsList = (0 until arr.length()).map { arr.getString(it) }
        assertTrue(defsList.contains("ui/hud_screen.json"))
        assertTrue(defsList.contains("ui/mana_screen.json"))
        assertTrue(defsList.contains("ui/stamina_screen.json"))
        assertTrue(defsList.contains("ui/spell_book.json"))
        // hud_screen no debe duplicarse
        assertEquals(1, defsList.count { it == "ui/hud_screen.json" })
    }

    @Test
    fun mergeGlobalVariables_combinesUniqueVariablesAndHandlesCollision() {
        val root1 = tempDir("rp1")
        val uiDir1 = File(root1, "ui").apply { mkdirs() }
        File(uiDir1, "_global_variables.json").writeText(
            """
            {
              "${'$'}mana_color": [0.2, 0.5, 1.0],
              "${'$'}hud_alpha": 0.8
            }
            """.trimIndent()
        )

        val root2 = tempDir("rp2")
        val uiDir2 = File(root2, "ui").apply { mkdirs() }
        File(uiDir2, "_global_variables.json").writeText(
            """
            {
              "${'$'}stamina_color": [0.1, 0.9, 0.2],
              "${'$'}hud_alpha": 1.0
            }
            """.trimIndent()
        )

        val mergedRp = tempDir("merged_rp")
        val report = BedrockUiMerger.mergeUi(listOf(root1, root2), mergedRp)

        val resultVarsFile = File(mergedRp, "ui/_global_variables.json")
        assertTrue(resultVarsFile.exists())

        val resultJson = JSONObject(resultVarsFile.readText())
        assertTrue(resultJson.has("\$mana_color"))
        assertTrue(resultJson.has("\$stamina_color"))
        assertTrue(resultJson.has("\$hud_alpha"))
        // Base prevalece en colisión
        assertEquals(0.8, resultJson.getDouble("\$hud_alpha"), 0.001)
    }

    @Test
    fun mergeScreenObjects_combinesTopLevelControlsAndModifications() {
        val json1 = JSONObject(
            """
            {
              "namespace": "hud",
              "mana_bar": {
                "type": "panel",
                "size": [80, 10]
              },
              "root_panel": {
                "modifications": [
                  {
                    "array_name": "controls",
                    "operation": "insert_back",
                    "value": [
                      { "mana_bar@hud.mana_bar": {} }
                    ]
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val json2 = JSONObject(
            """
            {
              "namespace": "hud",
              "stamina_bar": {
                "type": "panel",
                "size": [80, 10]
              },
              "root_panel": {
                "modifications": [
                  {
                    "array_name": "controls",
                    "operation": "insert_back",
                    "value": [
                      { "stamina_bar@hud.stamina_bar": {} }
                    ]
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val issues = mutableListOf<String>()
        val merged = BedrockUiMerger.mergeScreenObjects(
            base = json1,
            incoming = json2,
            fileName = "hud_screen.json",
            addonName = "Addon2",
            issues = issues
        )

        // Ambos controles deben coexistir en la raíz
        assertTrue(merged.has("mana_bar"))
        assertTrue(merged.has("stamina_bar"))

        // Las modificaciones deben estar unificadas con ambos widgets en value
        val rootPanel = merged.getJSONObject("root_panel")
        val mods = rootPanel.getJSONArray("modifications")
        assertEquals(1, mods.length())

        val mod = mods.getJSONObject(0)
        assertEquals("controls", mod.getString("array_name"))
        assertEquals("insert_back", mod.getString("operation"))

        val values = mod.getJSONArray("value")
        assertEquals(2, values.length())

        val v1 = values.getJSONObject(0)
        val v2 = values.getJSONObject(1)
        assertTrue(v1.has("mana_bar@hud.mana_bar") || v2.has("mana_bar@hud.mana_bar"))
        assertTrue(v1.has("stamina_bar@hud.stamina_bar") || v2.has("stamina_bar@hud.stamina_bar"))
    }

    @Test
    fun mergeControlsArray_combinesDirectControlsWithoutDuplication() {
        val baseArr = JSONArray(
            """
            [
              { "element1@hud.panel": {} },
              { "shared@hud.common": {} }
            ]
            """.trimIndent()
        )

        val incArr = JSONArray(
            """
            [
              { "shared@hud.common": {} },
              { "element2@hud.panel": {} }
            ]
            """.trimIndent()
        )

        val merged = BedrockUiMerger.mergeControlsArray(baseArr, incArr)
        assertEquals(3, merged.length())

        val names = (0 until merged.length()).map {
            merged.getJSONObject(it).keys().next()
        }
        assertTrue(names.contains("element1@hud.panel"))
        assertTrue(names.contains("shared@hud.common"))
        assertTrue(names.contains("element2@hud.panel"))
    }

    @Test
    fun sanitizeJsonComments_stripsCommentsCorrectly() {
        val raw = """
            // Comentario inicial
            {
              /* Comentario en bloque */
              "key": "valor con // no es comentario",
              "another": 123 // fin de linea
            }
        """.trimIndent()

        val sanitized = BedrockUiMerger.sanitizeJsonComments(raw)
        val parsed = JSONObject(sanitized)

        assertEquals("valor con // no es comentario", parsed.getString("key"))
        assertEquals(123, parsed.getInt("another"))
    }

    /**
     * 3.9 — Dos addons que declaran la MISMA variable global con valores
     * distintos: el perdedor conserva su valor bajo `$var_pf<hash>` y sus
     * referencias se reescriben SOLO en su propio UI. Antes solo se reportaba y
     * la pantalla del perdedor se quedaba con el valor ajeno.
     */
    @Test
    fun collidingGlobalVariableIsRenamedForTheLoserAndItsScreensRewritten() {
        val root1 = tempDir("rp1")
        val ui1 = File(root1, "ui").apply { mkdirs() }
        File(ui1, "_global_variables.json").writeText(
            """{"${'$'}hud_alpha": 0.8}"""
        )
        File(ui1, "hud_screen.json").writeText(
            """
            {
              "namespace": "hud",
              "mana": { "type": "label", "color": "${'$'}hud_alpha" }
            }
            """.trimIndent()
        )

        val root2 = tempDir("rp2")
        val ui2 = File(root2, "ui").apply { mkdirs() }
        File(ui2, "_global_variables.json").writeText(
            """{"${'$'}hud_alpha": 1.0}"""
        )
        File(ui2, "hud_screen.json").writeText(
            """
            {
              "namespace": "hud",
              "stamina": { "type": "label", "color": "${'$'}hud_alpha" },
              "otra": { "type": "label", "color": "${'$'}hud_alpha_extra" }
            }
            """.trimIndent()
        )
        // Referencia en un archivo exclusivo del segundo addon (copia directa).
        File(ui2, "spell_book.json").writeText(
            """{"namespace": "spells", "root": {"color": "${'$'}hud_alpha"}}"""
        )

        val mergedRp = tempDir("merged_rp")
        BedrockUiMerger.mergeUi(listOf(root1, root2), mergedRp)

        val vars = JSONObject(File(mergedRp, "ui/_global_variables.json").readText())
        assertEquals("El ganador conserva su valor", 0.8, vars.getDouble("${'$'}hud_alpha"), 0.001)
        val renombrada = vars.keys().asSequence().firstOrNull {
            it.startsWith("${'$'}hud_alpha_pf")
        }
        assertNotNull("El perdedor debe conservar su valor bajo un nombre propio", renombrada)
        assertEquals(1.0, vars.getDouble(renombrada!!), 0.001)

        val hud = JSONObject(File(mergedRp, "ui/hud_screen.json").readText())
        assertEquals(
            "El UI del ganador no se reescribe",
            "${'$'}hud_alpha",
            hud.getJSONObject("mana").getString("color")
        )
        assertEquals(
            "El UI del perdedor apunta a SU variable",
            renombrada,
            hud.getJSONObject("stamina").getString("color")
        )
        assertEquals(
            "Un nombre que empieza igual no se toca",
            "${'$'}hud_alpha_extra",
            hud.getJSONObject("otra").getString("color")
        )

        val spell = JSONObject(File(mergedRp, "ui/spell_book.json").readText())
        assertEquals(
            "La copia directa también se reescribe",
            renombrada,
            spell.getJSONObject("root").getString("color")
        )
        assertTrue(
            "El renombrado debe reportarse",
            ConflictRegistry.conflicts.value.any {
                it.conflictType == "UI_GLOBAL_VARIABLE_RENAMED"
            }
        )
    }
}
