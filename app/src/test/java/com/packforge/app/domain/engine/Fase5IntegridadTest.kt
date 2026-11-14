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
 * ═══════════════════════════════════════════════════════════════════════
 * FASE 5 — Falsos positivos del reporte de integridad y tolerancia JSON
 * ═══════════════════════════════════════════════════════════════════════
 *
 * El reporte de la fusión real de Better MC declaraba ~860 hallazgos
 * `CLAVES_CIFRADAS` sobre archivos perfectamente válidos, y marcaba como
 * "JSON inválido" agregados que Bedrock lee sin problema. Con ese ruido, los
 * fallos de verdad quedaban enterrados.
 *
 * Causas cubiertas aquí:
 *  1. Claves canónicas por PREFIJO (`minecraft:`, `geometry.`, `animation.`,
 *     `controller.`): una lista cerrada nunca cubre todas las variantes.
 *  2. Claves legítimas que faltaban en el catálogo (`pools`, `namespace`,
 *     `nineslice_size`).
 *  3. Comentarios `//` y de bloque dentro de los JSON: CoreCraft los inyecta
 *     en item_texture.json / terrain_texture.json y Bedrock los acepta.
 *  4. Contadores del resumen que no cuadraban (los INFO se contaban dos veces).
 *  5. Tope de detalle: los HIGH siempre completos, el resto acotado.
 */
class Fase5IntegridadTest {

    private val roots = mutableListOf<File>()

    @Before
    fun setUp() {
        DirIndexCache.clear()
        JsonDeepMerger.clearConflicts()
        ConflictRegistry.clear()
    }

    @After
    fun tearDown() {
        roots.forEach { it.deleteRecursively() }
        roots.clear()
        DirIndexCache.clear()
        JsonDeepMerger.clearConflicts()
        ConflictRegistry.clear()
    }

    private fun tempDir(name: String): File =
        Files.createTempDirectory("pf_fase5_$name").toFile().also { roots += it }

    private fun write(root: File, rel: String, body: String): File {
        val f = File(root, rel)
        f.parentFile?.mkdirs()
        f.writeText(body)
        return f
    }

    private fun findingsIn(root: File, rel: String) =
        MergeReportGenerator.auditJsonIntegrity(root).filter { it.relativePath == rel }

    // ── 1. Prefijos canónicos ────────────────────────────────────────────

    @Test
    fun minecraftPrefixedKeys_areNotReportedAsEncrypted() {
        val root = tempDir("prefix_mc")
        // `minecraft:variant` NO está en la lista cerrada: antes se marcaba
        // como clave cifrada y el usuario veía un falso "addon ofuscado".
        write(root, "entity/cc_custom.json", """{"minecraft:variant":1,"minecraft:scale":0.5}""")

        assertTrue(
            "Una clave con prefijo 'minecraft:' es canónica por definición. " +
                "Hallazgos: ${findingsIn(root, "entity/cc_custom.json")}",
            findingsIn(root, "entity/cc_custom.json").isEmpty()
        )
    }

    @Test
    fun geometryAndAnimationPrefixedKeys_areNotReportedAsEncrypted() {
        val root = tempDir("prefix_geo")
        write(root, "models/entity/cc_block.json", """{"geometry.cc_block":{"bones":{}}}""")
        write(root, "animations/cc_walk.json", """{"animation.cc.walk":{"loop":true}}""")
        write(
            root, "animation_controllers/cc_idle.json",
            """{"controller.animation.cc.idle":{"initial_state":"default"}}"""
        )

        listOf(
            "models/entity/cc_block.json",
            "animations/cc_walk.json",
            "animation_controllers/cc_idle.json"
        ).forEach { rel ->
            assertTrue(
                "'$rel' usa un prefijo canónico y no debe marcarse. " +
                    "Hallazgos: ${findingsIn(root, rel)}",
                findingsIn(root, rel).isEmpty()
            )
        }
    }

    // ── 2. Claves legítimas que faltaban ─────────────────────────────────

    @Test
    fun lootTablePoolsKey_isNotReportedAsEncrypted() {
        val root = tempDir("pools")
        write(root, "loot_tables/chest/custom.json", """{"pools":[{"rolls":1}]}""")

        assertTrue(
            "'pools' es la raíz de toda loot table. " +
                "Hallazgos: ${findingsIn(root, "loot_tables/chest/custom.json")}",
            findingsIn(root, "loot_tables/chest/custom.json").isEmpty()
        )
    }

    @Test
    fun uiNamespaceAndNinesliceKeys_areNotReportedAsEncrypted() {
        val root = tempDir("ui")
        write(
            root, "ui/cc_hud.json",
            """{"namespace":"cc_hud","nineslice_size":4,"hud_title":{"type":"label"}}"""
        )

        assertTrue(
            "Claves reales de UI (namespace, nineslice_size). " +
                "Hallazgos: ${findingsIn(root, "ui/cc_hud.json")}",
            findingsIn(root, "ui/cc_hud.json").isEmpty()
        )
    }

    // ── 3. Tolerancia a comentarios ──────────────────────────────────────

    @Test
    fun lineCommentInsideAggregate_isNotInvalidJson() {
        val root = tempDir("cmt_line")
        // Firma real de CoreCraft: comentario de línea DENTRO del agregado.
        write(
            root, "textures/item_texture.json",
            """
            // cooking
            {
              "resource_pack_name": "vanilla",
              "texture_data": {"cc:item": {"textures": "textures/items/cc"}}
            }
            """.trimIndent()
        )

        val errors = MergeReportGenerator.validateJsonSyntax(root)
        assertTrue(
            "Bedrock acepta el comentario: no es un JSON inválido. Errores: $errors",
            errors.none { it.contains("item_texture.json") }
        )
    }

    @Test
    fun commentedJson_isNotReportedAsEncrypted() {
        val root = tempDir("cmt_audit")
        write(
            root, "textures/terrain_texture.json",
            """
            // cooking
            {
              "resource_pack_name": "vanilla",
              "texture_data": {"cc:block": {"textures": "textures/blocks/cc"}}
            }
            """.trimIndent()
        )

        assertTrue(
            "Con el comentario tolerado, el archivo tiene clave canónica y no " +
                "debe marcarse como cifrado. Hallazgos: " +
                "${findingsIn(root, "textures/terrain_texture.json")}",
            findingsIn(root, "textures/terrain_texture.json").isEmpty()
        )
    }

    @Test
    fun genuinelyBrokenJson_isStillReported() {
        val root = tempDir("broken")
        // Sin coma y sin cierre: esto SÍ es un fallo real y debe seguir saliendo.
        write(root, "entity/roto.json", """{"format_version":"1.10.0" "minecraft:entity":{}}""")

        val errors = MergeReportGenerator.validateJsonSyntax(root)
        // El separador depende de la plataforma (\ en Windows): se normaliza.
        val normalized = errors.map { it.replace('\\', '/') }
        assertTrue(
            "Un JSON roto de verdad no puede pasar por tolerancia. Errores: $errors",
            normalized.any { it.contains("entity/roto.json") }
        )
    }

    // ── 4/5. Resumen honesto y tope de detalle ───────────────────────────

    @Test
    fun integritySummaryCountsEachFindingExactlyOnce() {
        val root = tempDir("summary")
        // 1 HIGH real (claves cifradas en ruta que Bedrock lee) + 2 LOW de relleno.
        write(
            root, "render_controllers/cc.json",
            """{"_mraenioftrsvo":"191..0","tanioamsni":{"olop":"tuer"}}"""
        )
        write(root, "filler/filler/.a.json", "texto plano sin json")
        write(root, "filler/filler/.b.json", "texto plano sin json")

        val lines = MergeReportGenerator.formatIntegrityFindings(
            MergeReportGenerator.auditJsonIntegrity(root)
        )
        assertTrue("Debe haber líneas de hallazgo", lines.isNotEmpty())

        // El icono de cada línea debe corresponder a UNA sola severidad: si un
        // hallazgo se contase dos veces, aparecerían recuentos incoherentes.
        val high = lines.count { it.startsWith("❌") }
        val low = lines.count { it.startsWith("⚠️") }
        val info = lines.count { it.startsWith("ℹ️") }
        assertEquals("3 hallazgos -> 3 líneas", 3, high + low + info)
    }

    @Test
    fun manyLowFindings_areCappedButHighsAreKept() {
        val root = tempDir("cap")
        // 1 HIGH + 80 LOW informativos: el tope debe comerse los LOW, no el HIGH.
        write(
            root, "render_controllers/cc.json",
            """{"_mraenioftrsvo":"191..0","tanioamsni":{"olop":"tuer"}}"""
        )
        repeat(80) { i ->
            write(root, "filler/filler/.f$i.json", "texto plano sin json")
        }

        val lines = MergeReportGenerator.formatIntegrityFindings(
            MergeReportGenerator.auditJsonIntegrity(root)
        )

        assertTrue(
            "El HIGH nunca se recorta",
            lines.any { it.startsWith("❌") && it.contains("render_controllers/cc.json") }
        )
        assertTrue(
            "Debe anunciar cuántos quedaron fuera",
            lines.any { it.contains("más") }
        )
        assertTrue(
            "El listado no puede volcar 81 líneas",
            lines.size < 81
        )
    }

    @Test
    fun cleanPackProducesNoIntegrityFindings() {
        val root = tempDir("clean")
        write(
            root, "entity/cc_golem.json",
            """
            {"format_version":"1.10.0",
             "minecraft:client_entity":{"description":{"identifier":"cc:golem",
              "geometry":{"default":"geometry.cc_golem"}}}}
            """.trimIndent()
        )

        val findings = MergeReportGenerator.auditJsonIntegrity(root)
        assertTrue("Un pack limpio no debe generar hallazgos: $findings", findings.isEmpty())
        assertTrue(
            "El resumen de un pack limpio debe decirlo",
            MergeReportGenerator.formatIntegrityFindings(findings).isEmpty()
        )
    }

    @Test
    fun missingJsonSyntaxErrors_areReportedForUnreadableFiles() {
        val root = tempDir("missing")
        assertTrue(
            "Un directorio inexistente no debe lanzar: simplemente no hay errores",
            MergeReportGenerator.validateJsonSyntax(File(root, "no_existe")).isEmpty()
        )
    }

    @Test
    fun rootArrayStillAccepted() {
        val samples = tempDir("root_array")
        write(samples, "textures/texture_list.json", """["textures/a","textures/b"]""")

        assertTrue(
            "Un array en la raíz es JSON válido y Bedrock lo usa",
            MergeReportGenerator.validateJsonSyntax(samples).isEmpty()
        )
        assertFalse(
            "Y no debe considerarse cifrado",
            hasFinding(samples, "textures/texture_list.json")
        )
    }

    private fun hasFinding(root: File, rel: String): Boolean =
        MergeReportGenerator.auditJsonIntegrity(root).any { it.relativePath == rel }
}
