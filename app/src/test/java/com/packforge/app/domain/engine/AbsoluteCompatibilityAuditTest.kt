package com.packforge.app.domain.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Cobertura de los ajustes finos de "compatibilidad absoluta":
 *  - decisiones del usuario para loot tables/recetas con identificador,
 *  - decisiones del usuario por PANTALLA de UI,
 *  - auditoría del merge que podía perder datos en silencio.
 */
class AbsoluteCompatibilityAuditTest {

    private val roots = mutableListOf<File>()

    @Before
    fun setUp() {
        ConflictRegistry.clear()
        JsonDeepMerger.clearConflicts()
    }

    @After
    fun cleanUp() {
        roots.forEach { it.deleteRecursively() }
        ConflictRegistry.clear()
        JsonDeepMerger.clearConflicts()
    }

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { roots += it }

    private fun pack(name: String, files: Map<String, String>): File {
        val root = File(tempDir("pf_audit_"), name).apply { mkdirs() }
        files.forEach { (rel, content) ->
            val file = File(root, rel)
            file.parentFile?.mkdirs()
            file.writeText(content)
        }
        return root
    }

    // ── 1. Loot tables / recetas con identificador ───────────────────────

    @Test
    fun namespacedLootTableIdentifierResolvesToTheRealFile() {
        // affectedFile para una loot table es "loot_tables/<id>.json"; con un id
        // con namespace hay que probar también el archivo sin namespace.
        val winner = pack(
            "winner",
            mapOf("loot_tables/chest.json" to """{"pools":[]}""")
        )
        val mergedBp = File(tempDir("merged_bp_"), "").apply { mkdirs() }
        File(mergedBp, "loot_tables").mkdirs()
        File(mergedBp, "loot_tables/chest.json").writeText("""{"pools":[{"loser":true}]}""")
        val mergedRp = File(tempDir("merged_rp_"), "").apply { mkdirs() }

        val result = ResolutionApplier.apply(
            fileWinners = mapOf("loot_tables/mypack:chest.json" to "w"),
            addonDirById = mapOf("w" to winner.absolutePath),
            mergedBpDir = mergedBp,
            mergedRpDir = mergedRp
        )

        assertEquals("Debe aplicar la decisión: ${result.notFound}", 1, result.applied)
        assertTrue(
            "El contenido debe ser el del ganador",
            File(mergedBp, "loot_tables/chest.json").readText().contains("pools")
        )
        assertFalse(
            "No debe quedar el del perdedor",
            File(mergedBp, "loot_tables/chest.json").readText().contains("loser")
        )
    }

    @Test
    fun duplicatedFolderPrefixIsCollapsed() {
        // El identificador puede incluir ya la carpeta y el detector añade otra.
        assertEquals(
            "loot_tables/entities/zombie.json",
            ResolutionApplier.normalizeAffectedPath("loot_tables/loot_tables/entities/zombie.json")
        )
        assertEquals(
            "recipes/x.json",
            ResolutionApplier.normalizeAffectedPath("recipes/recipes/x.json")
        )
    }

    @Test
    fun lootTablesAndRecipesAreResolvableButNamespacedParticlesAreNot() {
        assertTrue(ResolutionApplier.isResolvableByFileCopy("loot_tables/entities/zombie.json"))
        assertTrue(ResolutionApplier.isResolvableByFileCopy("loot_tables/mypack:chest.json"))
        assertTrue(ResolutionApplier.isResolvableByFileCopy("recipes/mypack:sword.json"))
        // Las partículas se desambiguan en el origen; copiar el archivo pelearía.
        assertFalse(ResolutionApplier.isResolvableByFileCopy("particles/mypack:dust.json"))
        // Los materiales se fusionan aparte.
        assertFalse(ResolutionApplier.isResolvableByFileCopy("materials/custom:glow.material"))
    }

    // ── 2. UI: decisión por pantalla ─────────────────────────────────────

    @Test
    fun uiScreensCanHaveAChosenWinnerButAggregateUiFilesCannot() {
        assertTrue(
            "Una pantalla sí admite ganador",
            ResolutionApplier.isResolvableByFileCopy("ui/hud_screen.json")
        )
        assertFalse(
            "El registro de pantallas es agregado: nunca 'gana uno'",
            ResolutionApplier.isResolvableByFileCopy("ui/_ui_defs.json")
        )
        assertFalse(
            "Las variables globales son agregadas",
            ResolutionApplier.isResolvableByFileCopy("ui/_global_variables.json")
        )
    }

    @Test
    fun chosenUiScreenReplacesTheMergedOne() {
        val winner = pack("winner", mapOf("rp/ui/hud_screen.json" to """{"hud":"ganador"}"""))
        val mergedRp = File(tempDir("merged_rp_ui_"), "").apply { mkdirs() }
        File(mergedRp, "ui").mkdirs()
        File(mergedRp, "ui/hud_screen.json").writeText("""{"hud":"fusionado"}""")
        val mergedBp = File(tempDir("merged_bp_ui_"), "").apply { mkdirs() }

        val result = ResolutionApplier.apply(
            fileWinners = mapOf("ui/hud_screen.json" to "w"),
            addonDirById = mapOf("w" to winner.absolutePath),
            mergedBpDir = mergedBp,
            mergedRpDir = mergedRp
        )

        assertEquals(1, result.applied)
        assertEquals(
            """{"hud":"ganador"}""",
            File(mergedRp, "ui/hud_screen.json").readText()
        )
    }

    // ── 4. Auditoría: no perder datos en silencio ────────────────────────

    @Test
    fun repeatedPrimitivesInArraysSurviveTheMerge() {
        // "frames":[0,0,1,1] controla la duración de cada fotograma: deduplicar
        // los primitivos aceleraba la animación sin avisar.
        val base = JSONObject().apply {
            put("frames", JSONArray(listOf(0, 0, 1, 1)))
        }
        val extra = JSONObject().apply {
            put("frames", JSONArray(listOf(1, 2)))
        }

        val merged = JsonDeepMerger.deepMerge(base, extra)
        val frames = merged.getJSONArray("frames")

        val values = (0 until frames.length()).map { frames.getInt(it) }
        assertEquals("Los repetidos deben conservarse", listOf(0, 0, 1, 1, 1, 2), values)
    }

    @Test
    fun identicalObjectsAreStillDeduplicated() {
        val shared = JSONObject().apply { put("name", "texture.default") }
        val base = JSONObject().apply {
            put("materials", JSONArray().apply { put(JSONObject(shared.toString())) })
        }
        val extra = JSONObject().apply {
            put("materials", JSONArray().apply { put(JSONObject(shared.toString())) })
        }

        val merged = JsonDeepMerger.deepMerge(base, extra)

        assertEquals(
            "Un objeto idéntico repetido sí es redundante",
            1,
            merged.getJSONArray("materials").length()
        )
    }

    @Test
    fun collidingKeysAfterCleaningKeepTheFirstAndReportIt() {
        // "a" y "a\u200B" (espacio de ancho cero) se normalizan a "a": antes una
        // pisaba a la otra sin dejar rastro.
        val json = JSONObject().apply {
            put("a", "primero")
            put("a\u200B", "segundo")
        }

        val cleaned = JsonDeepMerger.cleanJsonObject(json)

        assertEquals("primero", cleaned.getString("a"))
        val collisions = ConflictRegistry.conflicts.value
            .filter { it.conflictType == "KEY_COLLISION_AFTER_SANITIZE" }
        assertEquals("La colisión debe reportarse", 1, collisions.size)
    }
}
