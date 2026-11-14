package com.packforge.app.domain.engine

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * ⭐ RENDIMIENTO — benchmark del escaneo de identificadores.
 *
 * El reporte de fases de una fusión real de Better MC situó la detección del
 * renamespacer en el tramo más caro del pipeline (545 s de 1368 s junto a la
 * clasificación y la separación BP/RP). El motivo no es CPU: hay que abrir miles
 * de JSON para comprobar si contienen "identifier", y el antivirus de Windows
 * cobra por APERTURA, no por byte. Con E/S de latencia alta, repartir las
 * lecturas es lo que reduce el tiempo.
 *
 * Este test no valida comportamiento (eso lo hace IdentifierRemapperTest): mide
 * el mismo trabajo hecho de forma secuencial y a través de `run()`, e imprime la
 * comparación, para comprobar que la paralelización aporta de verdad en la
 * máquina donde se ejecuta en vez de asumirlo.
 *
 * Las dimensiones son pequeñas a propósito: crear archivos también cuesta
 * (~11 ms por archivo medidos en esta máquina), así que un banco de pruebas
 * grande tardaría más en montarse que en medir.
 */
class PerfRemapperEscaneoParaleloTest {

    private lateinit var root: File

    /**
     * JSON que NO declara ningún identificador.
     *
     * ⚠️ Importante: no puede contener la palabra "identifier" ni un bloque de
     * los que declaran id (una receta con `description.identifier` SÍ lo hace).
     * En una primera versión este relleno declaraba ids idénticos en los dos
     * addons y el test medía, sin querer, el camino de reescritura por colisión.
     */
    private fun relleno(i: Int): String = """
        {
          "format_version": "1.20.10",
          "minecraft:recipe_shaped": {
            "tags": ["crafting_table"],
            "pattern": ["X"],
            "key": { "X": { "item": "minecraft:stick" } },
            "result": { "item": "minecraft:stone" },
            "nota": "relleno $i sin declaracion"
          }
        }
    """.trimIndent()

    /** JSON que SÍ declara un identificador propio. */
    private fun declarante(prefix: String, i: Int): String = """
        {
          "format_version": "1.20.10",
          "minecraft:entity": {
            "description": { "identifier": "$prefix:entidad_$i" },
            "components": { }
          }
        }
    """.trimIndent()

    @Before
    fun setUp() {
        root = Files.createTempDirectory("pf_perf_remap").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `el escaneo paralelo lee lo mismo y aprovecha los hilos`() {
        val porAddon = 120
        val dirs = listOf(File(root, "addonA_bp"), File(root, "addonB_bp"))
        dirs.forEach { it.mkdirs() }

        dirs.forEachIndexed { d, dir ->
            val prefix = if (d == 0) "aaa" else "bbb"
            repeat(porAddon) { i ->
                val texto = if (i % 20 == 0) declarante(prefix, i) else relleno(i)
                File(dir, "archivo_$i.json").writeText(texto, Charsets.UTF_8)
            }
        }

        DirIndexCache.clear()
        val todos = dirs.flatMap { DirIndexCache.index(it).jsonFiles }
        val n = todos.size
        assertTrue("debería haber archivos para medir", n == porAddon * dirs.size)

        // ⚠️ CALENTAMIENTO OBLIGATORIO. La primera lectura de un archivo paga
        // traerlo a la caché del sistema de archivos. Si se mide "secuencial"
        // primero y "paralelo" después, la segunda pasada sale ganadora por el
        // orden, no por los hilos: en una versión anterior de este test eso dio
        // un "16x" que era puro efecto de caché.
        fun pasadaSecuencial(): Pair<Long, Int> {
            var declarados = 0
            val t0 = System.currentTimeMillis()
            for (f in todos) {
                val texto = try { f.readText(Charsets.UTF_8) } catch (e: Exception) { continue }
                if (texto.contains("identifier")) declarados++
            }
            return (System.currentTimeMillis() - t0) to declarados
        }

        pasadaSecuencial() // calienta la caché; el resultado se descarta

        val (seqMs, declaradosSeq) = pasadaSecuencial()

        // ── Escaneo PARALELO a través del remapper (caché ya caliente) ──
        DirIndexCache.clear()
        val tPar0 = System.currentTimeMillis()
        val remaps = runBlocking { IdentifierRemapper.run(dirs, emptyList()) }
        val parMs = System.currentTimeMillis() - tPar0

        // Segunda pasada secuencial, ya en caliente y DESPUÉS del paralelo, para
        // descartar que el orden de medición siga favoreciendo a alguien.
        val (seqMs2, _) = pasadaSecuencial()

        val nucleos = Runtime.getRuntime().availableProcessors()
        println("PF-PERF | archivos=$n nucleos=$nucleos")
        println("PF-PERF | secuencial (antes)  : ${seqMs} ms")
        println("PF-PERF | paralelo            : ${parMs} ms")
        println("PF-PERF | secuencial (despues): ${seqMs2} ms")
        val referencia = minOf(seqMs, seqMs2)
        if (parMs > 0 && referencia > 0) {
            println(String.format("PF-PERF | ganancia vs mejor secuencial: %.2fx", referencia.toDouble() / parMs))
        }
        println("PF-PERF | renombres (esperado 0): ${remaps.size}")

        assertTrue("el relleno debe declarar algún id para que la medición valga", declaradosSeq > 0)
        // Cada addon usa un prefijo distinto, así que no hay ids repetidos.
        assertTrue("sin ids repetidos no debe haber renombres: ${remaps.size}", remaps.isEmpty())
    }

    @Test
    fun `la paralelizacion no pierde colisiones reales`() {
        val dirs = listOf(File(root, "addonA_bp"), File(root, "addonB_bp"))
        dirs.forEach { it.mkdirs() }

        // Mismo identificador en los dos addons → debe renombrarse.
        dirs.forEach { dir ->
            File(dir, "comun.json").writeText(
                """
                {
                  "format_version": "1.20.10",
                  "minecraft:entity": {
                    "description": { "identifier": "comun:espada" },
                    "components": { }
                  }
                }
                """.trimIndent(),
                Charsets.UTF_8
            )
        }
        // Relleno para forzar el reparto entre hilos (sin declarar ids).
        dirs.forEach { dir ->
            repeat(40) { i -> File(dir, "r_$i.json").writeText(relleno(i), Charsets.UTF_8) }
        }

        DirIndexCache.clear()
        val remaps = runBlocking { IdentifierRemapper.run(dirs, emptyList()) }

        assertTrue(
            "con el mismo id en dos addons, el escaneo paralelo DEBE detectar la colisión",
            remaps.any { it.oldId == "comun:espada" }
        )
        println("PF-PERF | colisión detectada: ${remaps.map { "${it.oldId} -> ${it.newId}" }}")
    }
}