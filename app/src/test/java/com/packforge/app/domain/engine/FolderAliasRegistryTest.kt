package com.packforge.app.domain.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * TICKET-CORE — Auditoría H2: FolderAliasRegistry
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * `ui/` NO debe aliasarse: Bedrock carga la UI por NOMBRE EXACTO de archivo,
 * así que un alias `hud_screen_pf1a2b3.json` era ignorado por el motor y la
 * UI del addon perdedor se perdía en silencio. La nueva política:
 *  · primer addon en llegar = ganador de la ruta canónica (determinista)
 *  · perdedor → archivado en `_packforge/ui_discard/` (recuperable)
 *  · el choque queda expuesto en [FolderAliasRegistry.uiConflicts]
 * structures/ y fogs/ siguen aliasando por hash como siempre.
 */
class FolderAliasRegistryTest {

    private val tempRoots = mutableListOf<File>()

    @Before
    fun setUp() {
        FolderAliasRegistry.clear()
        DirIndexCache.clear()
    }

    @After
    fun tearDown() {
        tempRoots.forEach { it.deleteRecursively() }
        tempRoots.clear()
        FolderAliasRegistry.clear()
        DirIndexCache.clear()
    }

    private fun tempDir(name: String): File {
        val dir = Files.createTempDirectory("pf_far_$name").toFile()
        tempRoots.add(dir)
        DirIndexCache.invalidate(dir)
        return dir
    }

    private fun write(base: File, rel: String, content: String): File {
        val f = File(base, rel)
        f.parentFile?.mkdirs()
        f.writeText(content)
        return f
    }

    @Test
    fun `ui con contenido distinto NO se aliasa - perdedor se archiva y conflicto es HIGH`() {
        val addonA = tempDir("a")
        val addonB = tempDir("b")
        write(addonA, "ui/hud_screen.json", """{"namespace":"hud","screen_a":{}}""")
        write(addonB, "ui/hud_screen.json", """{"namespace":"hud","screen_b":{}}""")

        val renamesA = FolderAliasRegistry.planAndCommit(addonA, "addon_a")
        val renamesB = FolderAliasRegistry.planAndCommit(addonB, "addon_b")

        // El ganador (primer addon) NO se renombra: conserva el nombre canónico.
        assertTrue("El ganador no debe renombrarse", renamesA.isEmpty())
        // El perdedor va FUERA del alcance de Bedrock, no a un alias cargable-falso.
        assertEquals(
            "_packforge/ui_discard/addon_b/ui/hud_screen.json",
            renamesB["ui/hud_screen.json"]
        )
        // El choque queda expuesto para el reporte.
        assertEquals(1, FolderAliasRegistry.uiConflicts.size)
        val conflict = FolderAliasRegistry.uiConflicts.first()
        assertEquals("ui/hud_screen.json", conflict.path)
        assertEquals("addon_a", conflict.winnerAddon)
        assertEquals("addon_b", conflict.loserAddon)
    }

    @Test
    fun `ui NUEVA sin colision se copia tal cual - cero perdida de funcionalidad`() {
        val addonA = tempDir("a")
        write(addonA, "ui/mi_inventario_custom.json", """{"namespace":"custom","root":{}}""")

        val renames = FolderAliasRegistry.planAndCommit(addonA, "addon_a")

        assertTrue("Las UIs nuevas no se tocan", renames.isEmpty())
        assertTrue("Y no hay conflictos registrados", FolderAliasRegistry.uiConflicts.isEmpty())
    }

    @Test
    fun `ui identico entre addons es dedupe - sin conflicto`() {
        val addonA = tempDir("a")
        val addonB = tempDir("b")
        val content = """{"namespace":"hud","screen":{}}"""
        write(addonA, "ui/hud_screen.json", content)
        write(addonB, "ui/hud_screen.json", content)

        FolderAliasRegistry.planAndCommit(addonA, "addon_a")
        val renamesB = FolderAliasRegistry.planAndCommit(addonB, "addon_b")

        assertTrue("Contenido idéntico = dedupe, sin renombre", renamesB.isEmpty())
        assertTrue("Sin conflicto si el contenido es igual", FolderAliasRegistry.uiConflicts.isEmpty())
    }

    @Test
    fun `structures sigue aliasando por hash - regresion del alcance H2`() {
        val addonA = tempDir("a")
        val addonB = tempDir("b")
        write(addonA, "structures/casa.mcstructure", "AAA")
        write(addonB, "structures/casa.mcstructure", "BBB")

        FolderAliasRegistry.planAndCommit(addonA, "addon_a")
        val renamesB = FolderAliasRegistry.planAndCommit(addonB, "addon_b")

        val aliased = renamesB["structures/casa.mcstructure"]
        assertTrue(
            "structures/ con contenido distinto DEBE seguir aliasándose",
            aliased != null && aliased.startsWith("structures/casa_pf") && aliased.endsWith(".mcstructure")
        )
    }

    @Test
    fun `structureStemRenames traduce alias a nombres de comando mcfunction`() {
        // B8: `/structure load casa ...` debe seguir funcionando tras el alias.
        val renames = mapOf(
            "structures/casa.mcstructure" to "structures/casa_pf2bb225.mcstructure",
            "textures/ui/x.png" to "textures/ui/x_pf111111.png" // no-estructura: se ignora
        )

        val stems = FolderAliasRegistry.structureStemRenames(renames)

        assertEquals("casa_pf2bb225", stems["casa"])
        assertEquals(1, stems.size)
    }
}
