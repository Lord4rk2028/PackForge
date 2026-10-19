package com.packforge.app.domain.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * ═══════════════════════════════════════════════════════════════════════
 * TICKET-CORE-003 (fix) — Severidad de la auditoría de integridad
 * ═══════════════════════════════════════════════════════════════════════
 *
 * El primer despliegue marcó 58 hallazgos HIGH, pero ~56 eran falsos positivos:
 *  1. `startsWith` estricto para detectar decoys: los archivos empiezan con
 *     un comentario de bloque seguido de un salto de linea, asi que el marcador
 *     NO estaba al inicio y las trampas inofensivas se marcaban HIGH.
 *
 *  2. `hasCanonicalKey` solo parseaba JSONObject: un archivo de raíz ARRAY
 *     (textures/texture_list.json) se marcaba CLAVES_CIFRADAS.
 *  3. Claves canónicas incompletas: texts/languages.json ("languages") se
 *     marcaba CLAVES_CIFRADAS.
 *  4. Rutas con carpetas repetidas (entities/entities/…): relleno
 *     anti-análisis que Bedrock nunca lee, reportado como HIGH.
 */
class JsonIntegritySeverityTest {

    private val tempRoots = mutableListOf<File>()

    @Before
    fun setUp() {
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

    private fun tempDir(name: String): File {
        val dir = Files.createTempDirectory("pf_integrity_$name").toFile()
        tempRoots.add(dir)
        return dir
    }

    private fun write(root: File, rel: String, body: String): File {
        val f = File(root, rel)
        f.parentFile?.mkdirs()
        f.writeText(body)
        return f
    }

    /**
     * Texto real de un decoy de addon closed-source.
  *
     * El archivo empieza con un comentario de bloque (dos barras y asterisco)
     * seguido de un salto de línea, por eso el marcador NO está al inicio del
    * texto: un `startsWith` estricto no lo detectaba.
     *
     * El comentario se inyecta por separado para no escribir literalmente
   * los dos caracteres de apertura de comentario en el código fuente.
     */
    private fun decoyText(): String {
    val bc = "/" + "*"
        return listOf(
            bc,
            "",
  "Hi. This addon is closed-source. Please close this file. You are not allowed to read its contents.",
            "",
            "Okay?",
      "",
   "Do you understand?",
            "",
  "Why did you open this file?"
        ).joinToString("\n")
    }

    private fun finding(root: File, rel: String) =
        MergeReportGenerator.auditJsonIntegrity(root).firstOrNull { it.relativePath == rel }

    private fun severityOf(root: File, rel: String) = finding(root, rel)?.severity
// ── FIX 1: decoy con salto de línea inicial ──────────────────────────

    @Test
    fun closedSourceDecoyInCanonicalPath_isHIGH() {
        val root = tempDir("decoy")
        write(root, "entity/player.json", decoyText())

        // CORREGIDO: `entity/player.json` es una ruta que Bedrock LEE. Aunque el
        // texto sea el aviso de cierre closed-source, el juego intenta parsearlo
        // y falla. Antes se clasificaba LOW y el reporte mintia al usuario.
        assertEquals(
            "Un decoy en ruta canonica es un problema REAL: HIGH",
            MergeReportGenerator.IntegrityFinding.Severity.HIGH,
            severityOf(root, "entity/player.json")
        )
    }

    @Test
    fun decoyDetail_DiceQueBedrockSiLeeLaRuta() {
        val root = tempDir("decoy_detail")
        write(root, "entity/carryblock.json", decoyText())

        val f = finding(root, "entity/carryblock.json")!!
        assertTrue(
            "Debe advertir que el juego fallara al parsear la ruta canonica",
            f.detail.contains("RUTA CANONICA")
        )
        assertTrue(
            "No debe afirmar que Bedrock lo ignora cuando si lo lee",
            !f.detail.contains("Bedrock lo ignora")
        )
    }

    @Test
    fun decoyEnRutaRelleno_sigueSiendoLOW() {
        val root = tempDir("decoy_filler")
        // Segmento repetido: Bedrock NUNCA lee esta ruta, el texto da igual.
        write(root, "entities/entities/scripts/entities/scripts/.Ei.json", decoyText())

        assertEquals(
            "Ruta relleno anti-analisis: LOW, no ensucia el reporte",
            MergeReportGenerator.IntegrityFinding.Severity.LOW,
            severityOf(root, "entities/entities/scripts/entities/scripts/.Ei.json")
        )
    }

    @Test
    fun textoPlanoEnRutaNoCanonica_esLOW() {
        val root = tempDir("plain_noop")
        write(root, "textures/notas.txt", "cualquier cosa")
        // No es .json: ni se audita.
        assertEquals(null, severityOf(root, "textures/notas.txt"))
    }

    // ── FIX 2: arrays en la raíz ───────────────────────────────────────

    @Test
    fun rootArrayFile_isNotReportedAsEncrypted() {
        val root = tempDir("array")
        // Contenido real de textures/texture_list.json
        write(root, "textures/texture_list.json", """["textures/empty","textures/carryblock/destroy_stage_0"]""")

        val findings = MergeReportGenerator.auditJsonIntegrity(root)
        assertTrue(
            "Un ARRAY en la raíz es válido y no debe marcarse. Hallazgos: $findings",
            findings.none { it.relativePath == "textures/texture_list.json" }
        )
    }

    // ── FIX 3: claves canónicas que faltaban ───────────────────────────

    @Test
    fun languagesJsonWithLanguagesKey_isNotReportedAsEncrypted() {
        val root = tempDir("langs")
        write(root, "texts/languages.json", """{"languages":["en_US"]}""")

        val findings = MergeReportGenerator.auditJsonIntegrity(root)
        assertTrue(
            "languages.json es legítimo y no debe marcarse. Hallazgos: $findings",
            findings.none { it.relativePath == "texts/languages.json" }
        )
    }

    // ── FIX 4: rutas con carpetas repetidas ────────────────────────────

    @Test
    fun repeatedFolderSegments_isLowNotHigh() {
        val root = tempDir("filler")
        // Rutas reales del relleno anti-análisis del addon
        write(root, "entities/entities/entities/scripts/scripts/.b7.json", decoyText())
        write(root, "animation_controllers/scripts/scripts/scripts/user/.OU.json", decoyText())

        listOf(
            "entities/entities/entities/scripts/scripts/.b7.json",
            "animation_controllers/scripts/scripts/scripts/user/.OU.json"
        ).forEach { rel ->
            assertEquals(
                "Ruta con carpetas repetidas: Bedrock no la lee -> LOW",
                MergeReportGenerator.IntegrityFinding.Severity.LOW,
                severityOf(root, rel)
            )
        }
    }

    // ── NO se pierde la detección real ─────────────────────────────────

    @Test
    fun encryptedKeysInRealReadPath_isStillHigh() {
        val root = tempDir("encrypted")
        // Claves cifradas carácter a carácter conservando longitud (real)
        write(
            root, "render_controllers/carryblock.json",
            """{"_mraenioftrsvo":"191..0","tanioamsni":{"yimaeardkr.noambt.oocilncl":{"olop":"tuer"}}}"""
        )

        assertEquals(
            "Claves cifradas en una ruta que Bedrock SÍ lee: HIGH",
            MergeReportGenerator.IntegrityFinding.Severity.HIGH,
            severityOf(root, "render_controllers/carryblock.json")
        )
        assertEquals("CLAVES_CIFRADAS", finding(root, "render_controllers/carryblock.json")!!.kind)
    }

    @Test
    fun plainTextWithoutDecoyMarker_inRealReadPath_isHigh() {
        val root = tempDir("plain_high")
        // Texto plano SIN marcador de decoy, en ruta que Bedrock lee
        write(root, "animations/mi_animacion.json", "esto es texto plano sin json")

        assertEquals(
            "Texto plano desconocido en ruta real: HIGH",
            MergeReportGenerator.IntegrityFinding.Severity.HIGH,
            severityOf(root, "animations/mi_animacion.json")
        )
    }

    @Test
    fun validPackWithRealEntities_hasNoFindings() {
        val root = tempDir("clean")
        write(
            root, "entity/golem.json",
            """
            {"format_version":"1.10.0",
             "minecraft:client_entity":{"description":{"identifier":"pack:golem",
              "geometry":{"default":"geometry.golem"},"textures":{"default":"textures/entity/golem"}}}}
            """.trimIndent()
        )
        write(
            root, "render_controllers/golem.render_controllers.json",
            """{"format_version":"1.8.0","render_controllers":{"pack:golem":{"geometry":"Geometry.default"}}}"""
        )

        val findings = MergeReportGenerator.auditJsonIntegrity(root)
        assertTrue("Un pack válido no debe dar hallazgos: $findings", findings.isEmpty())
    }

    // ── REGLA c) SOMBRA y formato ──────────────────────────────────────

    @Test
    fun formatIntegrityFindings_rendersSeverityIcon() {
        val root = tempDir("fmt")
        // Ruta de RELLENO (segmentos repetidos) y no canónica: Bedrock NO la lee -> LOW -> ⚠️.
        // (entity/player.json daría HIGH; se usa una ruta filler para aislar el render del icono LOW.)
        write(root, "scratch/scratch/.fmt.json", decoyText())

        val lines = MergeReportGenerator.formatIntegrityFindings(
            MergeReportGenerator.auditJsonIntegrity(root)
        )
        assertEquals("1 hallazgo -> 1 línea", 1, lines.size)
        assertTrue("LOW debe llevar icono de aviso", lines[0].startsWith("⚠️"))
        assertTrue("Debe incluir la clase", lines[0].contains("ARCHIVO_NO_JSON"))
    }
}








