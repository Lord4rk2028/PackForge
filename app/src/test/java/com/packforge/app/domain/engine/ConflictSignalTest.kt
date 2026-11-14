package com.packforge.app.domain.engine

import com.packforge.app.domain.model.ConflictSeverity
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * FASE 4 — conflictos REALES, no ruido
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * El reporte de Better MC tenía 18.379 avisos de los que 17.313 (94,2%) eran
 * claves repetidas con el MISMO valor y sin ruta (`• MEDIUM @ `). Estos tests
 * fijan las tres causas:
 *
 *  1. Se registraba conflicto aunque el valor fuese idéntico.
 *  2. La ruta del archivo venía de un ThreadLocal que solo escribía código
 *     muerto ⇒ siempre vacía; y no había breadcrumb de la ruta JSON.
 *  3. La severidad era un `MEDIUM` literal: un `identifier` sobrescrito (lo más
 *     grave) se veía igual que un `format_version`.
 */
class ConflictSignalTest {

    @Before
    fun setUp() {
        JsonDeepMerger.clearConflicts()
        DirIndexCache.clear()
    }

    @After
    fun tearDown() {
        JsonDeepMerger.clearConflicts()
        DirIndexCache.clear()
    }

    private fun merge(base: String, incoming: String) =
        JsonDeepMerger.deepMerge(
            JSONObject(base), JSONObject(incoming),
            filePath = "entity/ghost.entity.json"
        )

    // ── 1. Igualdad de valores ────────────────────────────────────────────

    @Test
    fun `un valor identico no genera conflicto`() {
        merge(
            """{"format_version":"1.20.0","minecraft:entity":{"description":{"identifier":"cc:x"}}}""",
            """{"format_version":"1.20.0","minecraft:entity":{"description":{"identifier":"cc:x"}}}"""
        )
        assertEquals(
            "Mismo valor ⇒ mismo resultado ⇒ no hay nada que resolver",
            0, JsonDeepMerger.mergeConflicts.size
        )
    }

    @Test
    fun `un valor distinto si genera conflicto`() {
        val out = merge(
            """{"format_version":"1.20.0"}""",
            """{"format_version":"1.21.0"}"""
        )
        assertEquals(1, JsonDeepMerger.mergeConflicts.size)
        assertEquals(
            "Gana el entrante (el receptor prevalece)",
            "1.21.0", out.getString("format_version")
        )
    }

    // ── 2. Ruta de archivo y breadcrumb ──────────────────────────────────

    @Test
    fun `el conflicto lleva la ruta del archivo y la ruta dentro del JSON`() {
        merge(
            """{"minecraft:entity":{"description":{"identifier":"cc:uno"}}}""",
            """{"minecraft:entity":{"description":{"identifier":"cc:dos"}}}"""
        )
        val c = JsonDeepMerger.mergeConflicts.first()
        assertEquals("entity/ghost.entity.json", c.filePath)
        assertEquals(
            "/minecraft:entity/description/identifier",
            c.jsonPath
        )

        // Y no queda vacía: ésa era la causa del ruido `• MEDIUM @ ` sin localizar.
        assertTrue(c.filePath.isNotEmpty())
    }

    // ── 3. Severidad calculada ───────────────────────────────────────────

    @Test
    fun `sobrescribir un identifier es HIGH y una version LOW`() {
        merge(
            """{"minecraft:entity":{"description":{"identifier":"cc:uno"}},"format_version":"1.20.0"}""",
            """{"minecraft:entity":{"description":{"identifier":"cc:dos"}},"format_version":"1.21.0"}"""
        )
        val byType = JsonDeepMerger.mergeConflicts.associate { it.conflictType to it.severity }
        assertEquals(ConflictSeverity.HIGH, byType["IDENTIFIER_OVERWRITE"])
        assertEquals(ConflictSeverity.LOW, byType["VERSION_OVERWRITE"])
    }

    // ── 4. Auditor de identificadores duplicados ─────────────────────────

    private fun pack(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "pf_f4_${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    private fun write(base: File, rel: String, content: String) {
        val f = File(base, rel)
        f.parentFile?.mkdirs()
        f.writeText(content)
    }

    private fun block(id: String) =
        """{"minecraft:block":{"description":{"identifier":"$id"},"components":{}}}"""

    @Test
    fun `audita como HIGH un identificador declarado en dos archivos`() {
        val bp = pack()
        write(bp, "blocks/uno.json", block("cc:piedra"))
        write(bp, "blocks/dos.json", block("cc:piedra"))
        write(bp, "blocks/tres.json", block("cc:unico"))

        val findings = DuplicateIdentifierAuditor.audit(emptyList(), listOf(bp))

        assertEquals("Solo 'cc:piedra' está duplicado", 1, findings.size)
        assertEquals("cc:piedra", findings.first().identifier)
        assertEquals(2, findings.first().files.size)
        val c = JsonDeepMerger.mergeConflicts.first()
        assertEquals(ConflictSeverity.HIGH, c.severity)
        assertEquals("DUPLICATE_IDENTIFIER", c.conflictType)
        bp.deleteRecursively()
    }

    @Test
    fun `no marca como duplicadas las claves de estado internas`() {
        val rp = pack()
        write(
            rp, "animation_controllers/a.json",
            """{"animation_controllers":{"controller.animation.uno":{"states":{"default":{}}}}}"""
        )
        write(
            rp, "animation_controllers/b.json",
            """{"animation_controllers":{"controller.animation.dos":{"states":{"default":{}}}}}"""
        )

        val findings = DuplicateIdentifierAuditor.audit(listOf(rp), emptyList())
        // "default" se repite en los dos archivos por diseño: no debe salir.
        assertTrue(findings.none { it.identifier == "default" })
        assertTrue(findings.isEmpty())
        rp.deleteRecursively()
    }

    @Test
    fun `detecta geometrias duplicadas por su identificador declarado`() {
        val rp = pack()
        val geo = """{"minecraft:geometry":[{"description":{"identifier":"geometry.cc.x"},"bones":[]}]}"""
        write(rp, "models/entity/a.json", geo)
        write(rp, "models/entity/b.json", geo)

        val findings = DuplicateIdentifierAuditor.audit(listOf(rp), emptyList())

        assertEquals(1, findings.size)
        assertEquals("geometry", findings.first().kind)
        rp.deleteRecursively()
    }
}
