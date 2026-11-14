package com.packforge.app.domain.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * FASE 3 — el decifrador RECUPERA en vez de borrar
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Nace del reporte de Better MC: "🔓 Archivos canónicos restaurados: 0" y
 * "780 archivos pasados a .bak" que desaparecían del `.mcaddon`.
 *
 * Causas que este test fija:
 *  1. `acceptedRootsFor` comparaba el path COMPLETO: `blocks/<sub>/`,
 *     `recipes/<sub>/`, `items/<sub>/` y `entity/<sub>/` nunca emparejaban.
 *  2. El decifrador solo miraba el pack YA fusionado. Si el fusionador
 *     sobrescribió el contenido real de un addon con el señuelo de otro (misma
 *     ruta), el contenido seguía en el ZIP original y se daba por perdido.
 *  3. Todo lo que no se intentaba se reportaba como "irrecuperable".
 *  4. Los JSON con cabecera `//bridge-file-version` no parseaban y se
 *     neutralizaban (destruyendo contenido válido).
 */
class DecifratorRecoveryTest {

    private val roots = mutableListOf<File>()

    @Before
    fun setUp() {
        DirIndexCache.clear()
        ConflictRegistry.clear()
    }

    @After
    fun tearDown() {
        roots.forEach { it.deleteRecursively() }
        roots.clear()
        DirIndexCache.clear()
        ConflictRegistry.clear()
    }

    private fun pack(prefix: String = "pf_f3_"): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "$prefix${System.nanoTime()}")
        dir.mkdirs()
        roots += dir
        return dir
    }

    private fun write(base: File, rel: String, content: String): File {
        val f = File(base, rel)
        f.parentFile?.mkdirs()
        f.writeText(content)
        return f
    }

    private fun block(id: String) =
        """{"format_version":"1.20.0","minecraft:block":{"description":{"identifier":"$id"},"components":{}}}"""

    private fun recipe(id: String) =
        """{"format_version":"1.12","minecraft:recipe_shaped":{"description":{"identifier":"$id"},
           "tags":["crafting_table"],"pattern":["X"],"key":{"X":{"item":"minecraft:stone"}},"result":{"item":"minecraft:stone"}}}"""

    private val decoy = "Hi. This addon is closed-source."

    // ── 1. Subcarpetas ────────────────────────────────────────────────────

    @Test
    fun `restaura un canonico en SUBCARPETA que antes nunca se intentaba`() {
        val bp = pack()
        // blocks/celestite/... antes devolvía acceptedRootsFor = emptySet().
        write(bp, "blocks/celestite/small_celestite_bud.json", decoy)
        write(bp, "blocks/celestite/texts/texts/.a1.json", block("cc:small_celestite_bud"))

        val report = BedrockDecifrator.decrypt(pack(), bp)

        assertEquals("Debe restaurarse desde la copia enterrada", 1, report.restored.size)
        val target = File(bp, "blocks/celestite/small_celestite_bud.json")
        assertTrue("La ruta canónica de la subcarpeta debe tener el bloque real", target.isFile)
        assertTrue(target.readText().contains("cc:small_celestite_bud"))
    }

    // ── 2. Recuperación desde los addons originales ───────────────────────

    @Test
    fun `recupera del addon original el archivo que el fusionador sobrescribio`() {
        val bp = pack()
        val addon = pack("pf_f3_orig_")
        // El señuelo ganó la fusión en esa ruta…
        write(bp, "recipes/emerald/emerald_sword.json", decoy)
        // …pero la receta real sigue en el addon de origen.
        write(addon, "BP_ADDON/recipes/emerald/emerald_sword.json", recipe("cc:emerald_sword"))

        val report = BedrockDecifrator.decrypt(pack(), bp, listOf(addon.absolutePath))

        assertEquals(1, report.restored.size)
        assertEquals(
            "recipes/emerald/emerald_sword.json",
            report.restored.first().targetPath
        )
        val restored = File(bp, "recipes/emerald/emerald_sword.json").readText()
        assertTrue("Debe contener la receta real", restored.contains("cc:emerald_sword"))
        assertFalse("Sin restos del señuelo", restored.contains("closed-source"))
    }

    @Test
    fun `recupera por IDENTIFICADOR cuando el archivo fuente tiene nombre ofuscado`() {
        val bp = pack()
        val addon = pack("pf_f3_orig_")
        write(bp, "blocks/apple.json", decoy)
        // Nombre ofuscado, pero declara el identificador del bloque que falta.
        write(addon, "BP_ADDON/blocks/pqbf68ao.json", block("cc:apple"))

        val report = BedrockDecifrator.decrypt(pack(), bp, listOf(addon.absolutePath))

        assertEquals(1, report.restored.size)
        assertTrue(File(bp, "blocks/apple.json").readText().contains("cc:apple"))
        assertEquals("cc:apple", report.restored.first().identifier)
    }

    @Test
    fun `si hay varios candidatos con el mismo nombre NO adivina`() {
        val bp = pack()
        val addon = pack("pf_f3_orig_")
        write(bp, "blocks/foo.json", decoy)
        write(addon, "BP_ADDON/a/foo.json", block("cc:foo"))
        write(addon, "BP_ADDON/b/foo.json", block("cc:foo"))

        val report = BedrockDecifrator.decrypt(pack(), bp, listOf(addon.absolutePath))

        assertEquals("Con ambigüedad no se restaura nada", 0, report.restored.size)
        assertTrue(
            "El motivo debe decir que fue ambiguo: ${report.skipped.map { it.reason }}",
            report.skipped.any { it.reason.contains("ambiguo") }
        )
    }

    // ── 3. No destruir lo que no se intenta ───────────────────────────────

    @Test
    fun `lo que no se intenta no se neutraliza y se reporta con motivo`() {
        val rp = pack()
        // Ruta canónica que Bedrock lee, pero sin tipo de contenido catalogado.
        write(rp, "textures/ui/foo.json", decoy)

        val report = BedrockDecifrator.decrypt(rp)

        assertTrue(
            "Debe quedar constancia del motivo",
            report.skipped.any { it.relPath == "textures/ui/foo.json" }
        )
        assertFalse(
            "NO puede contarse como irrecuperable (no se intentó)",
            report.unrecoverable.contains("textures/ui/foo.json")
        )
        assertTrue(
            "Y NO se destruye: el archivo sigue donde estaba",
            File(rp, "textures/ui/foo.json").isFile
        )
        assertEquals(0, report.neutralizedUnrecoverable.size)
    }

    // ── 4. Cabecera `//` (Bridge.) ────────────────────────────────────────

    @Test
    fun `un JSON con cabecera de linea no se confunde con un senuelo`() {
        val bp = pack()
        val featureRule = "//bridge-file-version: #0\n" +
            """{"format_version":"1.13.0","minecraft:feature_rules":{"description":{
               "identifier":"sp:enderite_ore_feature","places_feature":"sp:ore"},"conditions":{}}}"""
        write(bp, "feature_rules/enderite.json", featureRule)

        val report = BedrockDecifrator.decrypt(pack(), bp)

        assertEquals("No hay nada que restaurar", 0, report.restored.size)
        assertEquals("Ni nada que neutralizar", 0, report.neutralizedUnrecoverable.size)
        assertFalse(
            "El archivo válido NO puede quedar como .bak",
            File(bp, "feature_rules/enderite.json.bak").exists()
        )
        assertTrue(File(bp, "feature_rules/enderite.json").isFile)
    }

    // ── 5. El comportamiento clásico sigue intacto ────────────────────────

    @Test
    fun `sin addons originales se conserva el comportamiento de senuelo enterrado`() {
        val rp = pack()
        val clear = """{"format_version":"1.8.0","render_controllers":{"controller.render.x":{}}}"""
        write(rp, "render_controllers/x.json", """{"_rovmofeatrsin":"8.1.0"}""")
        write(rp, "render_controllers/texts/texts/.u6.json", clear)

        val report = BedrockDecifrator.decrypt(rp)

        assertEquals(1, report.restored.size)
        assertTrue(File(rp, "render_controllers/x.json").readText().contains("controller.render.x"))
    }

    @Test
    fun `es idempotente tambien cuando recupera de un addon original`() {
        val bp = pack()
        val addon = pack("pf_f3_orig_")
        write(bp, "items/thing.json", decoy)
        write(addon, "BP_ADDON/items/thing.json", """{"format_version":"1.20.0","minecraft:item":{
            "description":{"identifier":"cc:thing"},"components":{}}}""")

        val first = BedrockDecifrator.decrypt(pack(), bp, listOf(addon.absolutePath))
        DirIndexCache.invalidate(bp)
        val second = BedrockDecifrator.decrypt(pack(), bp, listOf(addon.absolutePath))

        assertEquals(1, first.restored.size)
        assertEquals("La segunda pasada no debe reescribir nada", 0, second.restored.size)
        assertEquals(0, second.neutralizedUnrecoverable.size)
    }
}