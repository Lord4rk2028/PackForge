import com.packforge.app.domain.engine.DirIndexCache
import com.packforge.app.domain.engine.MergeReportGenerator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Reproduce EXACTAMENTE los 51 hallazgos del reporte de Test 1.
 *
 * El reporte original clasificaba los 51 como LOW con el texto
 * "decoy closed-source: Bedrock lo ignora" / "no requieren accion", y
 * concluia "FUSION COMPLETADA SIN ERRORES CRITICOS".
 *
 * Pero 24 de esos archivos estan en rutas canonicas que Bedrock SI lee
 * (animation_controllers/player.json, entities/player.entity.json, ...).
 * Esos son fallos reales y deben ser HIGH.
 */
class Test1ReportTest {

    private val roots = mutableListOf<File>()

    @Before
    fun setUp() = DirIndexCache.clear()

    @After
    fun tearDown() {
        roots.forEach { it.deleteRecursively() }
        roots.clear()
        DirIndexCache.clear()
    }

    private fun tempDir(name: String): File {
        val d = Files.createTempDirectory("pf_t1_$name").toFile()
        roots.add(d)
        return d
    }

    private fun write(root: File, rel: String, body: String) {
        val f = File(root, rel)
        f.parentFile?.mkdirs()
        f.writeText(body)
    }

    private fun decoy(): String = listOf(
        "/" + "*",
        "",
        "Hi. This addon is closed-source. Please close this file. " +
            "You are not allowed to read its contents.",
        "",
        "Do you understand?"
    ).joinToString("\n")

    /** Rutas canonicas REALES que aparecian como LOW en el reporte. */
    private val canonicalDelReporte = listOf(
        "animation_controllers/carryblock.json",
        "animation_controllers/gasket.json",
        "animation_controllers/interact_blocker.json",
        "animation_controllers/player.json",
        "animation_controllers/sirobblock.json",
        "animation_controllers/sit_lay_crawl4.json",
        "animation_controllers/first_person_controller.json",
        "animation_controllers/third_person_controller.json",
        "entities/crawl.entity.json",
        "entities/lay.entity.json",
        "entities/player.entity.json",
        "entities/sit.entity.json",
        "entities/carryblock.entity.json",
        "entities/carryblock_collision.entity.json",
        "entities/crawl4.entity.json",
        "entities/gasket.entity.json",
        "entities/interact_blocker.entity.json",
        "entities/playername.entity.json",
        "entities/wallclimb.entity.json",
        "animations/carryblock.json",
        "animations/first_person_body.json",
        "animations/third_person_body.json",
        "models/entity/carryblock.json",
        "models/entity/carryblock_broken.json"
    )

    /** Rutas de relleno anti-analisis: Bedrock nunca las lee. */
    private val rellenoDelReporte = listOf(
        "animation_controllers/entities/entities/scripts/user/.Dc.json",
        "animation_controllers/scripts/scripts/entities/scripts/.X8.json",
        "animation_controllers/scripts/scripts/scripts/entities/.q6.json",
        "animation_controllers/scripts/scripts/scripts/scripts/.OU.json",
        "animation_controllers/scripts/scripts/scripts/user/.Fv.json",
        "animation_controllers/scripts/scripts/scripts/user/.QR.json",
        "entities/entities/entities/scripts/scripts/.b7.json",
        "entities/entities/functions/scripts/user/.HN.json",
        "entities/entities/scripts/entities/scripts/.Ei.json",
        "entities/functions/entities/functions/scripts/user/.Ao.json",
        "entities/functions/entities/scripts/functions/.FF.json",
        "entities/functions/functions/functions/scripts/.xw.json",
        "entities/functions/scripts/functions/functions/.IO.json",
        "entities/scripts/functions/scripts/scripts/user/.fx.json",
        "entities/scripts/scripts/entities/scripts/.kb.json",
        "entities/scripts/scripts/scripts/functions/.43.json",
        "entities/scripts/scripts/scripts/user/.TC.json",
        "animations/animation_controllers/textures/carryblock/.tN.json",
        "animations/models/textures/carryblock/.Ue.json",
        "animations/textures/models/entity/.66.json",
        "animation_controllers/textures/font/models/entity/.o3.json",
        "animation_controllers/textures/font/models/entity/.tC.json",
        "animation_controllers/textures/texts/models/entity/.MD.json",
        "animation_controllers/textures/texts/models/entity/.u1.json",
        "models/entity/entity/models/entity/.pp.json"
    )

    @Test
    fun rutasCanonicasDelReporteDebenSerHIGH() {
        val root = tempDir("canon")
        canonicalDelReporte.forEach { write(root, it, decoy()) }

        val findings = MergeReportGenerator.auditJsonIntegrity(root)
        val high = findings.filter {
            it.severity == MergeReportGenerator.IntegrityFinding.Severity.HIGH
        }

        assertEquals(
            "Las 24 rutas canonicas del reporte deben reportarse HIGH",
            canonicalDelReporte.size,
            high.size
        )
    }

    @Test
    fun rellenoAntiAnalisisSigueSiendoLOW() {
        val root = tempDir("fill")
        rellenoDelReporte.forEach { write(root, it, decoy()) }

        val findings = MergeReportGenerator.auditJsonIntegrity(root)

        assertEquals(
            "Todo el relleno debe detectarse",
            rellenoDelReporte.size,
            findings.size
        )
        assertTrue(
            "Ninguna ruta de relleno debe ser HIGH",
            findings.none { it.severity == MergeReportGenerator.IntegrityFinding.Severity.HIGH }
        )
    }
}
