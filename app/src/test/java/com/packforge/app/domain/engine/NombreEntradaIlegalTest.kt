package com.packforge.app.domain.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * ═══════════════════════════════════════════════════════════════════════
 * TICKET-CORE-014 — Nombres de entrada que el sistema de archivos rechaza
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Caso real: `Better MC_Textures.mcaddon` (Desse-UI + Chroma Shader, 11 MB)
 * contenía una entrada llamada **`RP_PackForge/"Note".txt`**. En Windows las
 * comillas dobles no son un carácter válido de nombre, así que al crear el
 * archivo saltaba `FileNotFoundException: El nombre de archivo, el nombre de
 * directorio o la sintaxis de la etiqueta del volumen no son correctos`.
 *
 * Como ese `catch` estaba FUERA del bucle de entradas, una nota de texto
 * abortaba la extracción del addon COMPLETO: 773 entradas y 11 MB de shaders
 * sin importar por un `.txt` irrelevante. La app decía «no se pudo extraer».
 *
 * Estos tests fijan las dos mitades del contrato:
 *  1. El nombre se sanea por SEGMENTO, conservando la jerarquía.
 *  2. Una entrada impresentable se descarta, pero las demás se extraen.
 */
class NombreEntradaIlegalTest {

    private val roots = mutableListOf<File>()

    @Before
    fun setUp() {
        DirIndexCache.clear()
    }

    @After
    fun tearDown() {
        roots.forEach { it.deleteRecursively() }
        roots.clear()
        DirIndexCache.clear()
    }

    private fun tempDir(name: String): File =
        Files.createTempDirectory("pf_entrada_$name").toFile().also { roots += it }

    /** ZIP con las entradas EXACTAS que se le pidan (el nombre no se valida). */
    private fun zipConEntradas(dest: File, entradas: Map<String, String>) {
        ZipOutputStream(dest.outputStream().buffered()).use { zos ->
            entradas.forEach { (nombre, contenido) ->
                zos.putNextEntry(ZipEntry(nombre))
                zos.write(contenido.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
    }

    // ── 1. Saneamiento del nombre ────────────────────────────────────────────

    @Test
    fun elCasoRealSeSaneaConservandoLaJerarquia() {
        assertEquals(
            "RP_PackForge/_Note_.txt",
            AddonExtractor.sanitizeEntryName("RP_PackForge/\"Note\".txt")
        )
    }

    @Test
    fun unNombreNormalNoSeToca() {
        listOf(
            "manifest.json",
            "RP_PackForge/ui/beacon_screen.json",
            "textures/entity/zombie/skin.png",
            "scripts/modules/mi_addon/jobs.js",
            "subpacks/full_feature/ui/hud_screen.json",
            "textures/cc/ritual/entities/astral.soul.png"
        ).forEach {
            assertEquals("no debe cambiar: $it", it, AddonExtractor.sanitizeEntryName(it))
        }
    }

    @Test
    fun seSaneanTodosLosCaracteresQueWindowsProhibe() {
        val saneado = AddonExtractor.sanitizeEntryName("""a"b*c:d<e>f?g|h.txt""")
        assertEquals("a_b_c_d_e_f_g_h.txt", saneado)
        // El resultado debe poder crearse de verdad en ESTA máquina.
        val dir = tempDir("creable")
        val f = File(dir, saneado)
        f.writeText("ok")
        assertTrue("debe ser un nombre real: ${f.name}", f.isFile)
    }

    @Test
    fun laBarraInvertidaSeConvierteEnSeparador() {
        // ZIPs antiguos hechos en Windows usan `\`. Si se dejara como carácter
        // literal, el archivo se escribiría con `carpeta\archivo` DENTRO del
        // nombre... o, en Windows, fuera del sitio por ser separador de ruta.
        assertEquals(
            "RP_PackForge/ui/beacon_screen.json",
            AddonExtractor.sanitizeEntryName("""RP_PackForge\ui\beacon_screen.json""")
        )
    }

    @Test
    fun losPuntosSuspensivosDelZipSlipSobrevivenAlSaneado() {
        // El saneado NO es la defensa contra Zip Slip: `isSecurePath` debe seguir
        // viendo el `..` para poder rechazarlo. Si esto se convirtiera en `_`, la
        // comprobación de seguridad se quedaría ciega.
        assertEquals("../../evil.json", AddonExtractor.sanitizeEntryName("../../evil.json"))
    }

    @Test
    fun seRecortanPuntosYEspaciosFinalesDelSegmento() {
        // Windows los borra en silencio: el archivo aparecería con otro nombre
        // del que declara el ZIP, o directamente no aparecería.
        assertEquals("RP_PackForge/nota.txt", AddonExtractor.sanitizeEntryName("RP_PackForge/nota.txt."))
        // Un ESPACIO interior se conserva (la extensión sigue). Lo que no es
        // legal es el espacio como ÚLTIMO carácter de un segmento sin punto.
        assertEquals("RP_PackForge/nota txt", AddonExtractor.sanitizeEntryName("RP_PackForge/nota txt"))
    }

    @Test
    fun losNombresReservadosDelSistemaSePrefijan() {
        assertEquals("_con.json", AddonExtractor.sanitizeEntryName("con.json"))
        assertEquals("RP/_nul.txt", AddonExtractor.sanitizeEntryName("RP/nul.txt"))
        // Pero no cualquier cosa que empiece por esas letras.
        assertEquals("config.json", AddonExtractor.sanitizeEntryName("config.json"))
        assertEquals("nulo.json", AddonExtractor.sanitizeEntryName("nulo.json"))
    }

    @Test
    fun losCaracteresDeControlNoLleganAlNombre() {
        val saneado = AddonExtractor.sanitizeEntryName("foo\u0000bar\u0001.json")
        assertEquals("foo_bar_.json", saneado)
    }

    // ── 2. El addon completo se extrae (la regresión de verdad) ───────────────

    @Test
    fun unaEntradaConComillasNoAbortaLaExtraccionDelAddon() {
        val dir = tempDir("addon_comillas")
        val zip = File(dir, "addon.mcaddon")
        // Reproducción fiel: la nota molesta + el contenido que SÍ importa.
        zipConEntradas(
            zip,
            mapOf(
                "RP_PackForge/\"Note\".txt" to "nota del autor",
                "RP_PackForge/manifest.json" to """{"format_version":2}""",
                "RP_PackForge/textures/ui/title.png" to "PNG",
                "RP_PackForge/ui/beacon_screen.json" to """{"beacon_panel":{}}"""
            )
        )

        val destino = File(dir, "extraido")
        val resultado = AddonExtractor.extractAddon(zip.absolutePath, destino.absolutePath)

        assertNotNull("el addon debe extraerse aunque traiga una entrada ilegal", resultado)
        val extraido = File(resultado!!)
        assertTrue("falta el manifest", File(extraido, "RP_PackForge/manifest.json").isFile)
        assertTrue("falta la textura", File(extraido, "RP_PackForge/textures/ui/title.png").isFile)
        assertTrue("falta el ui", File(extraido, "RP_PackForge/ui/beacon_screen.json").isFile)

        // Y la entrada problemática se conserva SANEADA, no se pierde en silencio.
        val nota = File(extraido, "RP_PackForge/_Note_.txt")
        assertTrue("la nota debe quedar saneada", nota.isFile)
        assertEquals("nota del autor", nota.readText())
    }

    @Test
    fun unaFilaDeEntradasIlegalesNoAbortaLaExtraccion() {
        val dir = tempDir("varias_ilegales")
        val zip = File(dir, "addon.mcaddon")
        zipConEntradas(
            zip,
            mapOf(
                "BP/\"a\".json" to "{}",
                "BP/b*c.json" to "{}",
                "BP/d?e.json" to "{}",
                "BP/f<g>h.json" to "{}",
                "BP/valid.json" to "{\"ok\":true}"
            )
        )

        val destino = File(dir, "extraido")
        val resultado = AddonExtractor.extractAddon(zip.absolutePath, destino.absolutePath)

        assertNotNull("ninguna entrada ilegal puede tumbar el addon", resultado)
        val extraido = File(resultado!!)
        // Las entradas con caracteres ilegales se conservan SANEADAS.
        assertTrue(File(extraido, "BP/_a_.json").isFile)
        assertTrue(File(extraido, "BP/b_c.json").isFile)
        assertTrue(File(extraido, "BP/d_e.json").isFile)
        assertTrue(File(extraido, "BP/f_g_h.json").isFile)
        // Y la sana sigue igual.
        assertTrue(File(extraido, "BP/valid.json").isFile)
    }
}
