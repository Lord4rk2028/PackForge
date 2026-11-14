package com.packforge.app.domain.engine

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * ⭐ RENDIMIENTO (punto 1) — SEPARAR UN ADDON `BOTH` SIN COPIARLO.
 *
 * Antes, un `.mcaddon` que traía su Behavior Pack y su Resource Pack juntos se
 * **copiaba** a `separated_bp_<estampa>` / `separated_rp_<estampa>`, y después la
 * Fase 1 **volvía a copiar** esos archivos a los directorios fusionados: el addon
 * entero se escribía dos veces. Esa copia era parte del tramo más caro del
 * pipeline y no aportaba nada, porque las subcarpetas del clasificador ya son
 * raíces válidas de pack (su `manifest.json` está en su raíz).
 *
 * Lo ÚNICO que aportaba la estampa era agrupar el BP y el RP como el MISMO addon.
 * Sin esa agrupación el renamespacer ve al BP y al RP como dos addons distintos y
 * aparece el fallo histórico documentado en el propio orquestador: el addon
 * "colisiona consigo mismo", se renombra el id en el BP pero el RP conserva el
 * original, y el vínculo entidad↔textura se rompe (entidades invisibles o sin
 * modelo en el juego).
 *
 * Estos tests fijan que [AddonOwnerRegistry] mantiene esa garantía sin copiar:
 * el primero comprueba el registro, y el segundo que el RP del addon PERDEDOR se
 * reescribe junto con su BP (que es exactamente lo que la copia protegía).
 */
class RendimientoSeparacionSinCopiaTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("pf_sep_sin_copia").toFile()
        AddonOwnerRegistry.clear()
        DirIndexCache.clear()
    }

    @After
    fun tearDown() {
        AddonOwnerRegistry.clear()
        DirIndexCache.clear()
        root.deleteRecursively()
    }

    /** Declara un identificador en el archivo, con la clave raíz indicada. */
    private fun escribirDeclarante(dir: File, nombre: String, clave: String, id: String) {
        dir.mkdirs()
        File(dir, nombre).writeText(
            """
            {
              "format_version": "1.20.10",
              "$clave": {
                "description": { "identifier": "$id" },
                "components": { }
              }
            }
            """.trimIndent(),
            Charsets.UTF_8
        )
    }

    @Test
    fun `el registro agrupa BP y RP bajo un mismo propietario`() {
        val bp = File(root, "BP_Addon")
        val rp = File(root, "RP_Addon")
        bp.mkdirs(); rp.mkdirs()

        AddonOwnerRegistry.register(bp, "both_0", "MiAddon")
        AddonOwnerRegistry.register(rp, "both_0", "MiAddon")

        assertEquals("both_0", AddonOwnerRegistry.ownerOf(bp))
        assertEquals("both_0", AddonOwnerRegistry.ownerOf(rp))
        assertEquals(
            "BP y RP del mismo addon deben compartir propietario",
            AddonOwnerRegistry.ownerOf(bp),
            AddonOwnerRegistry.ownerOf(rp)
        )
        assertEquals("MiAddon", AddonOwnerRegistry.labelOf("both_0"))

        // Una carpeta sin registrar no tiene propietario: el remapper cae en su
        // lógica anterior por nombre, así que nada se rompe.
        assertEquals(null, AddonOwnerRegistry.ownerOf(File(root, "desconocida")))
    }

    @Test
    fun `el registro se limpia entre fusiones`() {
        val dir = File(root, "BP_X")
        dir.mkdirs()
        AddonOwnerRegistry.register(dir, "both_0", "X")
        assertNotNull(AddonOwnerRegistry.ownerOf(dir))

        AddonOwnerRegistry.clear()
        assertEquals(null, AddonOwnerRegistry.ownerOf(dir))
        assertEquals(null, AddonOwnerRegistry.labelOf("both_0"))
    }

    /**
     * ⭐ EL TEST QUE PROTEGE EL VÍNCULO BP↔RP.
     *
     * Dos addons declaran el MISMO id. El perdedor debe renombrarse **en su BP y
     * en su RP a la vez**: si solo se renombrara el BP, su `client_entity` (RP)
     * seguiría apuntando al id viejo y el mob saldría invisible o sin modelo.
     */
    @Test
    fun `al renombrar un addon BOTH se reescriben su BP y su RP juntos`() {
        val aBp = File(root, "addonA_bp")
        val aRp = File(root, "addonA_rp")
        val bBp = File(root, "addonB_bp")
        val bRp = File(root, "addonB_rp")

        // El MISMO identificador en los dos addons → hay colisión real.
        escribirDeclarante(aBp, "entidad.json", "minecraft:entity", "comun:espada")
        escribirDeclarante(aRp, "client.json", "minecraft:client_entity", "comun:espada")
        escribirDeclarante(bBp, "entidad.json", "minecraft:entity", "comun:espada")
        escribirDeclarante(bRp, "client.json", "minecraft:client_entity", "comun:espada")

        // El orquestador declara quién es quién (esto sustituye a la copia).
        AddonOwnerRegistry.register(aBp, "both_0", "addonA")
        AddonOwnerRegistry.register(aRp, "both_0", "addonA")
        AddonOwnerRegistry.register(bBp, "both_1", "addonB")
        AddonOwnerRegistry.register(bRp, "both_1", "addonB")

        DirIndexCache.clear()
        val remaps = runBlocking {
            IdentifierRemapper.run(
                behaviorDirs = listOf(aBp, bBp),
                resourceDirs = listOf(aRp, bRp)
            )
        }

        // El ganador es el propietario menor en orden lexicográfico: both_0 (A).
        val entrada = remaps.firstOrNull { it.oldId == "comun:espada" }
        assertNotNull("los dos addons declaran el mismo id: debe haber un renombre", entrada)
        val nuevo = entrada!!.newId

        // El GANADOR conserva su id en BP y en RP.
        assertTrue("el BP ganador no debe renombrarse", File(aBp, "entidad.json").readText().contains("comun:espada"))
        assertTrue("el RP ganador no debe renombrarse", File(aRp, "client.json").readText().contains("comun:espada"))

        // El PERDEDOR se reescribe en AMBAS mitades (esto es lo que protegía la
        // copia a `separated_*`: sin la agrupación, el RP quedaría con el id viejo).
        val bBpTexto = File(bBp, "entidad.json").readText()
        val bRpTexto = File(bRp, "client.json").readText()
        assertTrue("el BP perdedor debe llevar el id nuevo: $bBpTexto", bBpTexto.contains(nuevo))
        assertTrue(
            "el RP perdedor DEBE reescribirse junto al BP; si no, la entidad apunta a un id inexistente",
            bRpTexto.contains(nuevo)
        )
        assertFalse("el RP perdedor no debe conservar el id viejo", bRpTexto.contains("comun:espada"))
    }

    /**
     * El registro NO debe enmascarar colisiones reales entre addons distintos:
     * dos propietarios que declaran el mismo id siguen renombrándose.
     */
    @Test
    fun `dos addons distintos con el mismo id siguen colisionando`() {
        val aBp = File(root, "uno_bp")
        val bBp = File(root, "dos_bp")
        escribirDeclarante(aBp, "e.json", "minecraft:entity", "comun:cosa")
        escribirDeclarante(bBp, "e.json", "minecraft:entity", "comun:cosa")

        AddonOwnerRegistry.register(aBp, "both_0", "uno")
        AddonOwnerRegistry.register(bBp, "both_1", "dos")

        DirIndexCache.clear()
        val remaps = runBlocking { IdentifierRemapper.run(listOf(aBp, bBp), emptyList()) }

        assertTrue(
            "dos addons distintos con el mismo id deben seguir renombrándose",
            remaps.any { it.oldId == "comun:cosa" }
        )
        assertEquals("solo debe renombrarse uno (el perdedor)", 1, remaps.count { it.oldId == "comun:cosa" })
    }
}
