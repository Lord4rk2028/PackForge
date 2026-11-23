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
 * PILAR 5 — Trabajo redundante del diagnóstico (TICKET-CORE-013)
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Medido en el móvil con dos addons RP (295 JSON, 26,9 s de exportación):
 *
 *   · «resto tras Generación de reporte» ....... 3,47 s (12,9 %)
 *   · Fase 3 (22 fusiones críticas) ............ 16,08 s (59,7 %) ← invisible
 *
 * El tramo del reporte se llevaba ese tiempo porque el MISMO pack se leía y se
 * parseaba varias veces: `validateJsonSyntax` y `auditJsonIntegrity` recorrían
 * cada directorio por separado, y la auditoría parseaba cada archivo hasta
 * CINCO veces (dos para la validez estructural, dos para la clave canónica y
 * una estricta para el identificador).
 *
 * Estos tests fijan dos cosas:
 *  1. Que unificar la pasada NO cambia el veredicto (ni un hallazgo distinto).
 *  2. Que compartir el índice de geometrías/animaciones/RC entre el validador
 *     de referencias y el grafo de dependencias da exactamente el mismo
 *     resultado que dejar que cada uno construya el suyo.
 *
 * Y un tercer grupo, que es corrección pura y salió de un informe real:
 *  3. Los ficheros de `ui/` cuya raíz son NOMBRES DE ELEMENTO dejaban de ser
 *     «cifrado por sustitución». El informe afirmaba 77 veces que «el juego
 *     ignorará este archivo» sobre ficheros válidos de Desse-UI.
 */
class DiagnosticoUnaPasadaTest {

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
        Files.createTempDirectory("pf_unapasada_$name").toFile().also { roots += it }

    private fun write(root: File, rel: String, body: String): File {
        val f = File(root, rel)
        f.parentFile?.mkdirs()
        f.writeText(body)
        return f
    }

    /** Pack variado: válido, con comentarios, con BOM, texto plano y vacío. */
    private fun packVariado(root: File) {
        write(root, "entity/mob.json", """{"format_version":"1.20.50","minecraft:client_entity":{"description":{"identifier":"test:mob"}}}""")
        // Comentario inicial: Bedrock lo acepta (CoreCraft lo inyecta).
        write(root, "textures/item_texture.json", "// cooking\n{\"resource_pack_name\":\"x\",\"texture_data\":{}}")
        // BOM inicial: se acepta en el reintento.
        write(root, "texts/languages.json", "\uFEFF[\"en_US\"]")
        // Texto plano en ruta canónica: hallazgo HIGH.
        write(root, "ui/roto.json", "esto no es json, es prosa")
        // Objeto vacío: no está cifrado, no hay nada que cifrar.
        write(root, "sounds/empty.json", "{}")
        // Array en la raíz: válido.
        write(root, "textures/texture_list.json", "[\"a\",\"b\"]")
    }

    // ── 1. La pasada única no cambia el veredicto ────────────────────────────

    @Test
    fun auditPackDaElMismoVeredictoQueLasDosPasadasPorSeparado() {
        val root = tempDir("equivalencia")
        packVariado(root)

        val audit = MergeReportGenerator.auditPack(root)
        val sintaxisSuelta = MergeReportGenerator.validateJsonSyntax(root)
        val integridadSuelta = MergeReportGenerator.auditJsonIntegrity(root)

        // La ruta del error de sintaxis se normaliza a '/' (el resto del informe
        // ya usaba '/'); el número y el motivo deben ser idénticos.
        fun normalizar(xs: List<String>) = xs.map { it.replace('\\', '/') }
        assertEquals(
            "los errores de sintaxis no pueden cambiar al unificar la pasada",
            normalizar(sintaxisSuelta),
            normalizar(audit.syntaxErrors)
        )
        assertEquals(
            "los hallazgos de integridad no pueden cambiar al unificar la pasada",
            integridadSuelta,
            audit.integrityFindings
        )
    }

    @Test
    fun unDirectorioLlamadoJsonNoSeConfundeConUnArchivo() {
        // El análisis no puede inventar un «JSON ilegible» para algo que el
        // índice de directorios ni siquiera considera un archivo: la carpeta se
        // llama `roto.json` pero es un DIRECTORIO.
        val root = tempDir("lectura")
        write(root, "ok.json", "{\"format_version\":\"1.20.50\"}")
        File(root, "roto.json").mkdirs()

        val audit = MergeReportGenerator.auditPack(root)
        assertTrue(
            "no debe haber errores: ${audit.syntaxErrors}",
            audit.syntaxErrors.isEmpty()
        )
        assertTrue("no debe haber hallazgos: ${audit.integrityFindings}", audit.integrityFindings.isEmpty())
    }

    // ── 2. UI: la raíz son nombres de elemento, no claves cifradas ───────────

    private fun hallazgosDeUi(root: File, rel: String) =
        MergeReportGenerator.auditJsonIntegrity(root).filter { it.relativePath == rel }

    @Test
    fun uiConRaizDeElementoSinNamespaceNoSeReportaComoCifrado() {
        // Caso REAL de Desse-UI: 31 de 190 ficheros de ui/ son así.
        val root = tempDir("ui_override")
        write(root, "ui/blast_furnace_screen.json", """{"blast_furnace_screen":{"${'$'}is_blast_furnace_screen":true}}""")
        write(root, "ui/cartography_screen_pocket.json", """{"right_navigation_tabs":{"${'$'}show_help_button":false}}""")

        assertTrue(
            "un override de elemento de UI no está cifrado",
            hallazgosDeUi(root, "ui/blast_furnace_screen.json").isEmpty()
        )
        assertTrue(
            "un override de elemento de UI no está cifrado",
            hallazgosDeUi(root, "ui/cartography_screen_pocket.json").isEmpty()
        )
    }

    @Test
    fun uiConRutasDeModificacionNoSeReportaComoCifrado() {
        // Caso REAL: 49 de 190 ficheros de ui/ usan rutas de elemento + modifications.
        val root = tempDir("ui_mods")
        write(
            root,
            "ui/beacon_screen.json",
            """{"beacon_panel/root_panel":{"size":[220,212],"modifications":[{"array_name":"controls","operation":"insert_front","value":[]}]}}"""
        )
        assertTrue(hallazgosDeUi(root, "ui/beacon_screen.json").isEmpty())
    }

    @Test
    fun uiEnSubpackTambienSeReconoce() {
        val root = tempDir("ui_subpack")
        write(root, "subpacks/full_feature/ui/hud_screen.json", """{"hud_root_panel":{"${'$'}ignored":true}}""")
        assertTrue(
            hallazgosDeUi(root, "subpacks/full_feature/ui/hud_screen.json").isEmpty()
        )
    }

    @Test
    fun uiConBasuraMezcladaSigueReportandose() {
        // La regla exige que TODAS las claves parezcan elementos: si hay basura,
        // el archivo se sigue auditando como antes.
        val root = tempDir("ui_basura")
        write(
            root,
            "ui/raro.json",
            """{"blast_furnace_screen":{},"esto no es un nombre de elemento =":{}}"""
        )
        val hallazgos = hallazgosDeUi(root, "ui/raro.json")
        assertTrue("con basura mezclada debe reportarse: $hallazgos", hallazgos.isNotEmpty())
        assertTrue(hallazgos.any { it.kind == "CLAVES_CIFRADAS" })
    }

    @Test
    fun fueraDeUiLasClavesDeElementoSiguenReportandose() {
        // Guarda contra absolver de más: la excepción es SOLO para ui/.
        val root = tempDir("fuera_ui")
        write(root, "entity/raro.json", """{"blast_furnace_screen":{"a":1}}""")
        val hallazgos = hallazgosDeUi(root, "entity/raro.json")
        assertTrue("fuera de ui/ sigue siendo sospechoso: $hallazgos", hallazgos.isNotEmpty())
        assertTrue(hallazgos.any { it.kind == "CLAVES_CIFRADAS" })
    }

    // ── 3. El reporte dice QUÉ fusión crítica se lleva el tiempo ─────────────

    @Test
    fun elReporteIncluyeElDesglosePorFusionCritica() {
        val lineas = MergeReportGenerator.build(
            MergeReportGenerator.Inputs(
                totalJsonsMerged = 295,
                elapsedSeconds = 26.9,
                phaseTimings = mapOf(
                    "Fusión de archivos críticos (Fase 3)" to 16.08,
                    "Empaquetado .mcaddon (93%)" to 16.79
                ),
                criticalMergeTimings = listOf(
                    "mergeUi" to 9.5,
                    "mergeLangFiles" to 4.0,
                    "mergeSoundDefinitions" to 1.2,
                    "mergeMaterialInstances" to 0.3
                )
            )
        )

        assertTrue(
            "debe haber una sección de desglose: ${lineas.take(30)}",
            lineas.any { it.contains("FUSIONES CRÍTICAS") }
        )
        // Las cuatro fusiones deben aparecer, no solo un top.
        listOf("mergeUi", "mergeLangFiles", "mergeSoundDefinitions", "mergeMaterialInstances")
            .forEach { nombre ->
                assertTrue("falta '$nombre' en el desglose", lineas.any { it.contains(nombre) })
            }
        assertTrue(
            "debe declarar la suma",
            lineas.any { it.contains("SUMA de las fusiones") }
        )
        // Y el tramo final del informe ya no se atribuye al empaquetado.
        assertTrue(
            "la etiqueta del tramo final no debe hablar de empaquetado",
            lineas.none { it.contains("(empaquetado/cierre)") }
        )
    }

    @Test
    fun sinFusionesMedidasNoApareceLaSeccion() {
        val lineas = MergeReportGenerator.build(
            MergeReportGenerator.Inputs(totalJsonsMerged = 1, elapsedSeconds = 1.0)
        )
        assertFalse(lineas.any { it.contains("FUSIONES CRÍTICAS") })
    }

    // ── 4. El índice compartido no cambia los resultados ─────────────────────

    private fun rpConEntidad(rp: File) {
        write(
            rp,
            "entity/mob.json",
            """{"format_version":"1.20.50","minecraft:client_entity":{"description":{
                "identifier":"test:mob","geometry":{"default":"geometry.test"},
                "textures":{"default":"textures/entity/test"},
                "animations":{"idle":"animation.test"},
                "render_controllers":["controller.render.test"]}}}"""
        )
        write(rp, "models/entity/test.geo.json", """{"format_version":"1.12.0","minecraft:geometry":[{"description":{"identifier":"geometry.test"},"bones":[]}]}""")
        write(rp, "render_controllers/test.json", """{"format_version":"1.8.0","render_controllers":{"controller.render.test":{"geometry":"Geometry.default"}}}""")
        // `animation.test` NO se define: genera un hueco real.
        write(rp, "textures/entity/test.png", "PNG")
    }

    @Test
    fun elGrafoConIndiceCompartidoDaElMismoResultado() {
        val rp = tempDir("grafo_idx")
        rpConEntidad(rp)
        val fuentes = listOf(DependencyDAG.Source("MiRP", rp, false))

        val sinIndice = DependencyDAG.build(fuentes)
        // El orquestador lo construye con fuentes + pack fusionado y lo reutiliza.
        val indice = GeometryAnimRCIndex.build(listOf(rp), rp)
        val conIndice = DependencyDAG.build(fuentes, indice)

        assertEquals(
            "las claves de origen no pueden cambiar porque el índice incluya el pack fusionado",
            sinIndice.gaps.map { it.describe() },
            conIndice.gaps.map { it.describe() }
        )
        assertEquals(sinIndice.resumen(), conIndice.resumen())
        assertEquals(sinIndice.entidadesSinCliente, conIndice.entidadesSinCliente)
        assertEquals(sinIndice.clientesSinEntidad, conIndice.clientesSinEntidad)
        // Y hay un hueco real que ambos deben ver (la animación no existe).
        assertTrue(sinIndice.gaps.any { it.kind == DependencyDAG.Kind.ANIMATION })
    }

    @Test
    fun elValidadorConIndiceCompartidoDaElMismoInforme() {
        val rp = tempDir("val_idx")
        rpConEntidad(rp)

        val suelto = MergeReferenceValidator.validate(null, rp)
        val indice = GeometryAnimRCIndex.build(listOf(rp), rp)
        val compartido = MergeReferenceValidator.validate(null, rp, unifiedIndex = indice)

        assertEquals(suelto.checkedReferences, compartido.checkedReferences)
        assertEquals(suelto.brokenCount, compartido.brokenCount)
        assertEquals(
            suelto.broken.map { "${it.kind}|${it.identifier}|${it.referencingFile}" },
            compartido.broken.map { "${it.kind}|${it.identifier}|${it.referencingFile}" }
        )
    }
}
