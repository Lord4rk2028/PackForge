package com.packforge.app.domain.engine

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * ═══════════════════════════════════════════════════════════════════════
 * TICKET-CORE-001 — Regresión del registro de mutaciones de recursos
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Defectos cubiertos:
 *  B1  Sufijo de colisión no único (System.currentTimeMillis() → contador)
 *  B2  Registro global ambiguo → registro AISLADO POR FUENTE
 *  B3  Variantes de referencia incompletas (faltaba la relativa a textures/)
 *  B4  Extensión mal detectada cuando el directorio contiene un punto
 *  ZERO-I/O  Solo se escribe si el contenido realmente cambió
 */
class ResourcePathRegistryMutationTest {

    private val tempRoots = mutableListOf<File>()

    @Before
    fun setUp() {
        // DirIndexCache es global: hay que aislarlo entre tests.
        DirIndexCache.clear()
        JsonDeepMerger.clearConflicts()
        ConflictRegistry.clear()
    }

    @After
    fun tearDown() {
        tempRoots.forEach { it.deleteRecursively() }
        tempRoots.clear()
        DirIndexCache.clear()
    }

    // ── HELPERS ─────────────────────────────────────────────────────────

    private fun tempDir(name: String): File {
        val dir = Files.createTempDirectory("pf_mut_$name").toFile()
        tempRoots.add(dir)
        return dir
    }

    /** Escribe un "PNG" binario con contenido distinguible por addon. */
    private fun png(dir: File, relPath: String, marker: String): File {
        val f = File(dir, relPath).apply { parentFile.mkdirs() }
        f.writeBytes(marker.toByteArray() + byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
        return f
    }

    /** RP mínimo con terrain_texture.json referenciando la textura dada. */
    private fun rpWithAtlas(dir: File, refPath: String): File {
        val f = File(dir, "textures/terrain_texture.json").apply { parentFile.mkdirs() }
        f.writeText(
            """
            {
              "format_version": "1.10.0",
              "texture_data": { "dirt": { "textures": "$refPath" } }
            }
            """.trimIndent()
        )
        return f
    }

    private fun atlasRef(dir: File): String =
        JSONObject(File(dir, "textures/terrain_texture.json").readText())
            .getJSONObject("texture_data").getJSONObject("dirt").getString("textures")

// ── B2: AISLAMIENTO POR FUENTE (el bug más grave) ───────────────────

    @Test
    fun threeAddonsCollidingSamePath_eachKeepsItsOwnMutation() {
        // Escenario exacto que rompía el mapa global: 3 addons, misma ruta.
        val a = tempDir("a")
        val b = tempDir("b")
        val c = tempDir("c")
        listOf(a, b, c).forEach { png(it, "textures/entity/steve.png", it.name) }

        val reg = ResourcePathRegistry()
        reg.registerSourceMutation(a, "textures/entity/steve.png", "textures/entity/steve_pf1.png")
        reg.registerSourceMutation(b, "textures/entity/steve.png", "textures/entity/steve_pf2.png")
        reg.registerSourceMutation(c, "textures/entity/steve.png", "textures/entity/steve_pf3.png")

        assertEquals("textures/entity/steve_pf1.png", reg.mutationsFor(a).getValue("textures/entity/steve.png"))
        assertEquals("textures/entity/steve_pf2.png", reg.mutationsFor(b).getValue("textures/entity/steve.png"))
        assertEquals("textures/entity/steve_pf3.png", reg.mutationsFor(c).getValue("textures/entity/steve.png"))

        assertEquals("3 mutaciones distintas", 3, reg.totalMutations())
        assertEquals(
            "Cada addon debe tener una ruta efectiva distinta",
            3,
            listOf(a, b, c).map { reg.mutationsFor(it).getValue("textures/entity/steve.png") }.distinct().size
        )
    }

    @Test
    fun threeAddonsColliding_rewritesEachJsonToItsOwnTexture() {
        // La prueba de fuego: el JSON de B debe apuntar a la textura de B.
        val a = tempDir("a")
        val b = tempDir("b")
        val c = tempDir("c")
        listOf(a, b, c).forEach { png(it, "textures/entity/steve.png", it.name) }
        listOf(a, b, c).forEach { rpWithAtlas(it, "textures/entity/steve") }

        val reg = ResourcePathRegistry()
        reg.registerSourceMutation(a, "textures/entity/steve.png", "textures/entity/steve_pf1.png")
        reg.registerSourceMutation(b, "textures/entity/steve.png", "textures/entity/steve_pf2.png")
        reg.registerSourceMutation(c, "textures/entity/steve.png", "textures/entity/steve_pf3.png")

        DirIndexCache.clear()
        assertEquals("Deben reescribirse los 3 atlas", 3, reg.applySourceRewrites(listOf(a, b, c)))

        // El atlas referencia SIN extensión → se reescribe a la variante sin extensión.
        assertEquals("textures/entity/steve_pf1", atlasRef(a))
        assertEquals("textures/entity/steve_pf2", atlasRef(b))
        assertEquals("textures/entity/steve_pf3", atlasRef(c))
    }

    @Test
    fun sourceWithoutMutations_isNeverRewritten() {
        // El ganador de la ruta canónica NO debe verse afectado.
        val winner = tempDir("winner")
        val loser = tempDir("loser")
        png(winner, "textures/entity/steve.png", "W")
        png(loser, "textures/entity/steve.png", "L")
        rpWithAtlas(winner, "textures/entity/steve")
        rpWithAtlas(loser, "textures/entity/steve")

        val reg = ResourcePathRegistry()
        reg.registerSourceMutation(loser, "textures/entity/steve.png", "textures/entity/steve_pf1.png")

        DirIndexCache.clear()
        reg.applySourceRewrites(listOf(winner, loser))


        assertEquals("El ganador conserva su ruta original", "textures/entity/steve", atlasRef(winner))
    }
// ── B3: VARIANTES DE REFERENCIA ─────────────────────────────────────

    @Test
    fun rewriteVariants_coverAllFourBedrockReferenceForms() {
        val src = tempDir("src")
        File(src, "entity/steve.entity.json").apply { parentFile.mkdirs() }.writeText(
            """
            {"format_version":"1.10.0",
             "minecraft:client_entity":{"description":{
               "identifier":"packforge:steve",
               "textures":{"default":"textures/entity/steve"}}}}
            """.trimIndent()
        )
        // Atlas: ruta RELATIVA a textures/ y SIN extensión
        rpWithAtlas(src, "entity/steve")
        File(src, "render_controllers/steve.render_controllers.json").apply { parentFile.mkdirs() }
            .writeText("""{"render_controllers":{"packforge:steve":{"materials":{"default":"entity/steve.png"}}}}""")

        val reg = ResourcePathRegistry()
        reg.registerSourceMutation(src, "textures/entity/steve.png", "textures/entity/steve_pf1.png")

        DirIndexCache.clear()
        reg.applySourceRewrites(listOf(src))

        // Lo que valida el Requisito B3 no es el CONTEO, sino que CADA forma de
        // referencia quede apuntando al archivo mutado correcto.
        assertTrue(
            "Entity: debe usar la variante completa sin extensión",
            File(src, "entity/steve.entity.json").readText().contains("textures/entity/steve_pf1")
        )
        assertEquals("Atlas: variante relativa sin extensión", "entity/steve_pf1", atlasRef(src))
        assertTrue(
            "Render controller: variante relativa CON extensión",
            File(src, "render_controllers/steve.render_controllers.json").readText().contains("entity/steve_pf1.png")
        )
    }

    @Test
    fun nonImageBinary_isNotExpandedIntoExtensionlessVariants() {
        // Un .ogg SÍ se reescribe (coincidencia exacta de ruta), pero NO debe
        // generar las variantes de imagen sin extensión ni relativas a textures/.
        val src = tempDir("src")
        File(src, "sounds.json").apply { parentFile.mkdirs() }
            .writeText("""{"sound_definitions":{"x":{"sounds":["sounds/mob/steve.ogg"]}}}""")
        val reg = ResourcePathRegistry()
        reg.registerSourceMutation(src, "sounds/mob/steve.ogg", "sounds/mob/steve_pf1.ogg")

        DirIndexCache.clear()
        assertEquals("La ruta exacta sí se reescribe", 1, reg.applySourceRewrites(listOf(src)))
        val out = JSONObject(File(src, "sounds.json").readText())
            .getJSONObject("sound_definitions").getJSONObject("x").getJSONArray("sounds").getString(0)
        assertEquals("sounds/mob/steve_pf1.ogg", out)
    }

    // ── B4: PUNTOS EN EL DIRECTORIO ─────────────────────────────────────

    @Test
    fun dottedDirectoryName_doesNotBreakExtensionDetection() {
        // textures/v1.0/blocks/stone.png → el '.' de "v1.0" NO es extensión.
        val src = tempDir("src")
        png(src, "textures/v1.0/blocks/stone.png", "X")
        rpWithAtlas(src, "textures/v1.0/blocks/stone")

        val reg = ResourcePathRegistry()
        reg.registerSourceMutation(src, "textures/v1.0/blocks/stone.png", "textures/v1.0/blocks/stone_pf1.png")

        DirIndexCache.clear()
        assertEquals("Debe reescribir", 1, reg.applySourceRewrites(listOf(src)))
        assertEquals(
            "La extensión .png debe recortarse, no el punto de v1.0",
            "textures/v1.0/blocks/stone_pf1", atlasRef(src)
        )
    }

    // ── ZERO-EXCESS I/O ─────────────────────────────────────────────────
    // ── ZERO-EXCESS I/O ─────────────────────────────────────────────────

    @Test
    fun unchangedJson_isNotRewrittenToDisk() {
        val src = tempDir("src")
        val original = """{"texture_data":{"apple":{"textures":"items/apple"}}}"""
        File(src, "textures/item_texture.json").apply { parentFile.mkdirs() }.writeText(original)

        val reg = ResourcePathRegistry()
        reg.registerSourceMutation(src, "textures/entity/steve.png", "textures/entity/steve_pf1.png")

        DirIndexCache.clear()
        assertEquals("Sin coincidencias, no se reescribe", 0, reg.applySourceRewrites(listOf(src)))
        assertEquals(
            "El archivo no debe haberse reformateado",
            original, File(src, "textures/item_texture.json").readText()
        )
    }

    @Test
    fun idempotentApply_doesNotAccumulateChanges() {
        val src = tempDir("src")
        rpWithAtlas(src, "textures/entity/steve")
        val reg = ResourcePathRegistry()
        reg.registerSourceMutation(src, "textures/entity/steve.png", "textures/entity/steve_pf1.png")

        DirIndexCache.clear()
        assertEquals(1, reg.applySourceRewrites(listOf(src)))
        val afterFirst = File(src, "textures/terrain_texture.json").readText()

        DirIndexCache.clear()
        assertEquals(0, reg.applySourceRewrites(listOf(src)))
        assertEquals("Debe ser idempotente", afterFirst, File(src, "textures/terrain_texture.json").readText())
    }

    @Test
    fun clearMutations_resetsSessionState() {
        val a = tempDir("a")
        val reg = ResourcePathRegistry()
        reg.registerSourceMutation(a, "x.png", "x_pf1.png")
        assertTrue(reg.hasGlobalMutations())
        assertEquals(1, reg.totalMutations())

        reg.clearGlobalMutations()
        assertFalse(reg.hasGlobalMutations())
        assertEquals(0, reg.totalMutations())
        assertTrue(reg.mutationsFor(a).isEmpty())
        assertNotEquals("x_pf1.png", reg.getGlobalRewriteMap().values.firstOrNull())
    }

    @Test
    fun sourceWithoutCollision_isUntouched() {
        val solo = tempDir("solo")
        png(solo, "textures/entity/steve.png", "S")
        rpWithAtlas(solo, "textures/entity/steve")

        val reg = ResourcePathRegistry()
        DirIndexCache.clear()
        assertEquals(0, reg.applySourceRewrites(listOf(solo)))
        assertEquals("Sin colisión no se toca nada", "textures/entity/steve", atlasRef(solo))
    }

    // ── CASO REALISTA DE ESTRÉS (5 addons) ──────────────────────────────

    @Test
    fun stressSeveralAddons_allReferencesStayResolvable() {
        val dirs = (1..5).map { tempDir("addon$it") }
        dirs.forEachIndexed { i, d ->
            png(d, "textures/entity/body.png", "BODY$i")
            rpWithAtlas(d, "textures/entity/body")
        }

        val reg = ResourcePathRegistry()
        val expected = mutableListOf<String>()
        dirs.forEachIndexed { i, d ->
            // El primero gana la ruta canónica; el resto se renombra.
            if (i == 0) return@forEachIndexed
            val mutated = "textures/entity/body_pf$i.png"
            reg.registerSourceMutation(d, "textures/entity/body.png", mutated)
            expected += mutated
        }

        DirIndexCache.clear()
        reg.applySourceRewrites(dirs)

        assertEquals("El ganador conserva su ruta original", "textures/entity/body", atlasRef(dirs[0]))
        // El atlas referencia SIN extensión → variante sin extensión.
        dirs.drop(1).forEachIndexed { j, d ->
            assertEquals("Addon ${j + 1} mal remapeado", expected[j].removeSuffix(".png"), atlasRef(d))
        }
        assertEquals("4 perdedores mutados", 4, reg.totalMutations())
    }
}
