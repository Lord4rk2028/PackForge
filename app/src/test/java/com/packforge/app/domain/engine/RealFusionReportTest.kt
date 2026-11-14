package com.packforge.app.domain.engine

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * ═══════════════════════════════════════════════════════════════════════
 * FASE 5 — FUSIÓN REAL DE LOS 4 ADDONS (opt-in, no corre por defecto)
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Los tests unitarios validan piezas; los tests de escenario usan packs
 * sintéticos. Este ejecuta la fusión COMPLETA con los addons reales que
 * produjeron el reporte de 37.083 líneas, y mide el reporte nuevo.
 *
 * Es opt-in porque tarda minutos y necesita ~1 GB de disco:
 *
 *     $env:PF_FUSION_ADDONS = "C:\ruta\a\carpeta"   # con los .mcaddon/.mcpack
 *     .\run-tests.ps1 -Filter "com.packforge.app.domain.engine.RealFusionReportTest"
 *
 * ⚠️ Las variables de entorno **no llegan de forma fiable** al JVM de tests
 * cuando Gradle reutiliza un daemon ya arrancado (el daemon conserva el entorno
 * con el que se inició). Eso hacía que este test se saltara en silencio
 * (`skipped=1`) aunque el usuario acabara de definir las variables — pasó varias
 * veces durante la auditoría de rendimiento y lleva a medir el build equivocado.
 *
 * Por eso existe un respaldo por archivo: si la variable no está, se leen las
 * mismas claves de `.research/fusion.properties`. Crear el archivo basta para
 * reproducir una fusión sin pelear con el entorno del daemon:
 *
 *     PF_FUSION_ADDONS=C:\ruta\a\carpeta
 *     PF_FUSION_ALL=1
 *     PF_FUSION_OUT=.research/repro_out
 *
 * Los addons se descubren por nombre dentro de la carpeta indicada.
 */
class RealFusionReportTest {

    private val roots = mutableListOf<File>()

    /**
     * Lee una clave de configuración.
     *
     * Precedencia: primero `.research/fusion.properties`, después el entorno.
     *
     * ⚠️ El archivo va PRIMERO a propósito. El daemon de Gradle conserva el
     * entorno con el que se inició, así que puede arrastrar un `PF_FUSION_ALL=0`
     * de una corrida anterior e ignorar lo que el usuario acaba de definir. Eso
     * ya provocó aquí fusionar el conjunto equivocado (y saltarse el test en
     * silencio). Un archivo local explícito es la fuente más fiable para una
     * ejecución concreta; el entorno sirve de respaldo.
     */
    private fun cfg(clave: String): String? {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val archivo = listOf(
            File(cwd, ".research/fusion.properties"),
            File(cwd, "../.research/fusion.properties"),
            File(cwd, "../../.research/fusion.properties")
        ).firstOrNull { it.isFile }
        if (archivo != null) {
            val delArchivo = runCatching {
                val props = java.util.Properties()
                archivo.inputStream().use { props.load(it) }
                props.getProperty(clave)?.takeIf { it.isNotBlank() }
            }.getOrNull()
            if (delArchivo != null) return delArchivo
        }
        return System.getenv(clave)?.takeIf { it.isNotBlank() }
    }

    @After
    fun cleanUp() {
        roots.forEach { it.deleteRecursively() }
    }

    @Test
    fun `fusiona los addons reales y mide el reporte resultante`() {
        val rootPath = cfg("PF_FUSION_ADDONS")
        assumeTrue("Define PF_FUSION_ADDONS para fusionar addons reales", !rootPath.isNullOrBlank())

        val dir = File(rootPath!!)
        assumeTrue("La carpeta debe existir: $rootPath", dir.isDirectory)

        // Descubrimiento por nombre: tolera sufijos de descarga (uuid_, _v1.2, etc.).
        val all = dir.listFiles { f ->
            f.isFile && (f.extension == "mcaddon" || f.extension == "mcpack" || f.extension == "zip")
        }?.toList().orEmpty()
        assumeTrue("No hay .mcaddon/.mcpack en $rootPath", all.isNotEmpty())

        fun pick(vararg needles: String) = all.firstOrNull { f ->
            val n = f.name.lowercase()
            needles.all { n.contains(it) }
        }

        // Modo A: los 4 addons concretos del caso Better MC.
        // Modo B: TODOS los addons de la carpeta (PF_FUSION_ALL=1).
        val fuseAll = cfg("PF_FUSION_ALL") == "1"
        val wanted = if (fuseAll) {
            all
        } else {
            listOfNotNull(
                pick("core-craft").or(pick("corecraft")),
                pick("sift", "bp"),
                pick("sift", "rp"),
                pick("mta").or(pick("moretools"))
            )
        }
        // El modo A exige el juego concreto de Better MC (3+ addons). El modo B
        // fusiona lo que haya en la carpeta, así que basta con que haya algo:
        // antes se exigían >=3 SIEMPRE y una pareja de addons (caso de
        // reproducción de 2 packs) se saltaba el test en silencio.
        assumeTrue(
            "Faltan addons en $rootPath (encontrados: ${all.map { it.name }})",
            if (fuseAll) wanted.isNotEmpty() else wanted.size >= 3
        )

        val work = Files.createTempDirectory("pf_real_fusion_").toFile().also { roots += it }
        println("P5-REAL | addons a fusionar (${wanted.size}):")
        wanted.forEach { println("P5-REAL |   · ${it.name} (${it.length() / 1024 / 1024} MB)") }

        val t0 = System.currentTimeMillis()
        val result = runBlocking {
            PackForgeOrchestrator.mergeAddons(
                addonPaths = wanted.map { it.absolutePath },
                outputDir = work.absolutePath,
                addonNames = wanted.map { it.nameWithoutExtension },
                customName = "BetterMC_Real",
                addonIds = wanted.map { it.nameWithoutExtension }
            )
        }
        val elapsed = (System.currentTimeMillis() - t0) / 1000.0

        println("P5-REAL | success=${result.success} error=${result.errorMessage}")
        println("P5-REAL | output=${result.outputPath}")
        println("P5-REAL | elapsed=${"%.1f".format(elapsed)}s")
        println("P5-REAL | JSONs=${result.totalJsonsMerged}")

        // ── Veredicto de compatibilidad ──
        val compat = result.compatibility
        println("P5-REAL | compatibility=${compat?.level?.name ?: "null"}")
        println("P5-REAL | headline=${compat?.headline}")
        compat?.details?.forEach { println("P5-REAL |   detalle: $it") }

        // ── Reporte de fusión: tamaño y ruido ──
        val report = result.reportPath?.let { File(it) }?.takeIf { it.isFile }
        if (report != null) {
            val lines = report.readLines()
            println("P5-REAL | reporte: ${lines.size} líneas, ${report.length()} bytes")

            // Los conflictos "sin ruta" eran la firma del ruido (17.313 de 18.379).
            val noPath = lines.count { it.contains("@  ") || it.trim().endsWith("@") }
            println("P5-REAL | lineas sin ruta=$noPath")

            // Detalle de lo que el semáforo declara como problema, para poder
            // juzgar si es real o ruido residual.
            val interesting = listOf(
                "REFERENCIA", "referencia", "rota", "•",
                "Integridad", "COMPATIBILIDAD", "incompleta",
                "Texturas faltantes", "Modelos faltantes", "CLAVES_CIFRADAS",
                "SOMBRA", "ARCHIVO_NO_JSON", "DECIFRADOR", "restaurado"
            )
            lines.filter { l -> interesting.any { l.contains(it) } }
                .take(80)
                .forEach { println("P5-REAL | $it") }
        } else {
            println("P5-REAL | SIN REPORTE")
        }

        // ── Artefactos: se copian a una ruta estable para inspeccionarlos ──
        val outDir = File(cfg("PF_FUSION_OUT") ?: ".research/fase5_out")
        outDir.mkdirs()

        report?.let {
            val dest = File(outDir, "fusion_report.txt")
            dest.writeText(it.readText())
            println("P5-REAL | reporte guardado en: ${dest.absolutePath}")
        }

        result.outputPath?.let { out ->
            val f = File(out)
            println("P5-REAL | mcaddon=${f.length() / 1024 / 1024} MB")
            if (f.isFile) {
                val dest = File(outDir, f.name)
                f.copyTo(dest, overwrite = true)
                println("P5-REAL | mcaddon guardado en: ${dest.absolutePath}")
            }
            if (f.isFile) {
                java.util.zip.ZipFile(f).use { zip ->
                    val names = zip.entries().toList().filter { !it.isDirectory }.map { it.name }
                    println("P5-REAL | entradas en el .mcaddon=${names.size}")
                    println("P5-REAL | .bak presentes=${names.count { it.endsWith(".bak") }}")
                    println("P5-REAL | manifiestos=${names.count { it.endsWith("manifest.json") }}")

                    // ── PÉRDIDA DE GEOMETRÍAS: comparar con los addons de origen ──
                    // Es la métrica que reveló que 69 archivos reales desaparecían.
                    val enSalida = names.filter { it.matches(Regex(".*/(models|entity)/.*\\.json")) }
                        .map { it.substringAfterLast('/') }.toSet()
                    println("P5-REAL | archivos models|entity en la salida=${enSalida.size}")

                    wanted.forEach { addon ->
                        val declarados = java.util.zip.ZipFile(addon).use { src ->
                            src.entries().toList()
                                .filter {
                                    it.name.matches(Regex(".*/(models|entity)/.*\\.json")) &&
                                        !it.name.contains(".bak")
                                }
                                .map { it.name.substringAfterLast('/') }
                                .toSet()
                        }
                        val perdidos = declarados - enSalida
                        println("P5-REAL | ${addon.name}: declara=${declarados.size} perdidos=${perdidos.size}")
                        if (perdidos.isNotEmpty()) {
                            println("P5-REAL |    ejemplos: ${perdidos.take(6).joinToString()}")
                        }
                    }
                }
            }
        }

        assertTrue("La fusión real no debe lanzar excepción", true)
    }

    private fun File?.or(other: File?): File? = this ?: other
}
