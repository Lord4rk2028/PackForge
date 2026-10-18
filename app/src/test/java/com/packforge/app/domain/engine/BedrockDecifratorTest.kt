package com.packforge.app.domain.engine

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * TICKET-CORE-004 — BedrockDecifrator
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Reproduce la estructura REAL de un addon "closed-source" (descubierta
 * empíricamente en "More Body Actions + Pick up & Carry" / Test 1.mcaddon):
 *
 *   · ruta canónica  = SEÑUELO (claves mezcladas o texto plano)
 *   · ruta profunda  = CONTENIDO EN CLARO que Bedrock nunca lee
 *
 * El motor debe devolver el archivo en claro a su ruta canónica SIN inventar
 * nada, sin sobrescribir lo que ya es válido y sin dejar el pack peor.
 */
class BedrockDecifratorTest {

    private val tempRoots = mutableListOf<File>()

    @Before
    fun setUp() {
        DirIndexCache.clear()
        ConflictRegistry.clear()
    }

    @After
    fun tearDown() {
        tempRoots.forEach { it.deleteRecursively() }
        tempRoots.clear()
        DirIndexCache.clear()
        ConflictRegistry.clear()
    }

    private fun newPack(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "pf_dec_${System.nanoTime()}")
        dir.mkdirs()
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
    /** JSON en claro real de un render controller (el que va escondido). */
    private val clearRenderController =
        "{\"format_version\":\"1.8.0\",\"render_controllers\":" +
            "{\"controller.render.carryblock.broken\":{" +
            "\"arrays\":{\"textures\":{\"array.broken\":[\"texture.default\"]}}," +
            "\"geometry\":\"Geometry.broken\",\"materials\":[{\"*\":\"Material.default\"}]," +
            "\"textures\":[\"Texture.default\"],\"is_hurt_color\":{},\"on_fire_color\":{}}}}"

    /** El mismo archivo con las CLAVES MEZCLADAS (anagramas reales del addon). */
    private val scrambledRenderController =
        "{\"_rovmofeatrsin\":\"8.1.0\",\"enen_tsdlrcorerrol\":{" +
            "\"ror..olerrnnednbotrcrokaeeck.blrycl\":{" +
            "\"rarays\":{\"tsexruet\":{\"arnyroaek.br\":[\"texture.default\"]}}," +
            "\"rtemyoeg\":\"oGerometrekny.b\",\"isertlaam\":[{\"*\":\"auafMltirtd.aele\"}]," +
            "\"xeruestt\":[\"Texture.default\"],\"cl_sruoh_itro\":{},\"fnrec_r_oiloo\":{}}}}"

    @Test
    fun `restaura render controller desde ruta oculta a ruta canonica`() {
        val pack = newPack()
        write(pack, "render_controllers/carryblock.json", scrambledRenderController)
        write(
            pack,
            "render_controllers/textures/textures/models/entity/.u6.json",
            clearRenderController
        )

        val report = BedrockDecifrator.decrypt(pack)

        assertEquals("Debe restaurar 1 archivo", 1, report.restored.size)

        val target = File(pack, "render_controllers/carryblock.json")
        assertTrue("La ruta canónica debe existir", target.exists())
        assertTrue(
            "El archivo restaurado debe contener la clave real",
            target.readText().contains("render_controllers")
        )
        assertTrue(
            "Y el identificador original",
            target.readText().contains("controller.render.carryblock.broken")
        )
        assertFalse(
            "Sin restos de claves mezcladas",
            target.readText().contains("_rovmofeatrsin")
        )
    }

    @Test
    fun `restaura client_entity y respeta el sufijo de tipo de Bedrock`() {
        val pack = newPack()
        write(pack, "entity/player.json", "Hi. This addon is closed-source.")
        write(
            pack,
            "entity/texts/textures/textures/carryblock/.OE.json",
            "{\"format_version\":\"1.8.0\",\"minecraft:client_entity\":{\"description\":" +
                "{\"identifier\":\"minecraft:player\",\"materials\":{\"default\":\"entity_alphablob\"}," +
                "\"textures\":{\"default\":\"textures/entity/player\"}," +
                "\"geometry\":{\"default\":\"geometry.humanoid.custom\"}}}}"
        )

        val report = BedrockDecifrator.decrypt(pack)

        assertEquals(1, report.restored.size)
        val restored = report.restored.first()
        // Bedrock exige el sufijo .entity.json dentro de la carpeta entity/
        assertEquals("entity/player.entity.json", restored.targetPath)
        assertTrue(File(pack, "entity/player.entity.json").readText().contains("minecraft:player"))
    }
    @Test
    fun `NUNCA sobrescribe un archivo canonico ya valido`() {
        val pack = newPack()
        write(pack, "render_controllers/carryblock.json", clearRenderController)
        write(
            pack,
            "render_controllers/textures/textures/models/entity/.u6.json",
            clearRenderController
        )

        val before = File(pack, "render_controllers/carryblock.json").readText()
        val report = BedrockDecifrator.decrypt(pack)

        assertEquals("No debe restaurar nada sobre un archivo válido", 0, report.restored.size)
        assertEquals(
            "El archivo no debe cambiar",
            before,
            File(pack, "render_controllers/carryblock.json").readText()
        )
    }

    @Test
    fun `NUNCA escribe basura cuando no hay contraparte en claro`() {
        val pack = newPack()
        write(pack, "animations/third_person_body.json", scrambledRenderController)

        val report = BedrockDecifrator.decrypt(pack)

        assertEquals("Sin contraparte no debe restaurar nada", 0, report.restored.size)
        assertEquals(1, report.unrecoverable.size)
        // Contrato CORE-004: NUNCA escribe basura sobre la ruta canónica.
        // Cumplido en ambos modelos, y aquí se exige además que el texto
        // plano deje de estorbar: ya no aporta nada (no es JSON válido),
        // solo llenaría el Content Log de errores en cada arranque.
        assertFalse(
            "El texto plano NO debe seguir en la ruta canónica que Bedrock lee",
            File(pack, "animations/third_person_body.json").exists()
        )
        assertTrue(
            "La evidencia original debe conservarse sin alterar bytes",
            File(pack, "animations/third_person_body.json.bak").readText()
                .contains("_rovmofeatrsin")
        )
    }

    @Test
    fun `no mezcla tipos de contenido entre carpetas`() {
        val pack = newPack()
        // entity/ espera un client_entity, pero el claro es geometría.
        write(pack, "entity/cube.json", "Hi. This addon is closed-source.")
        write(
            pack,
            "models/entity/entity/textures/carryblock/.Pb.json",
            "{\"format_version\":\"1.8.0\",\"minecraft:geometry\":[" +
                "{\"description\":{\"identifier\":\"geometry.carryblock\"}}]}"
        )

        val report = BedrockDecifrator.decrypt(pack)

        assertEquals(
            "Una geometría NO debe aterrizar en entity/",
            0, report.restored.size
        )
        assertFalse(
            "El texto plano NO debe seguir en la ruta canonica",
            File(pack, "entity/cube.json").exists()
        )
        assertTrue(
            "El texto plano original debe conservarse sin alterar bytes",
            File(pack, "entity/cube.json.bak").readText().contains("closed-source")
        )
    }

    @Test
    fun `es idempotente`() {
        val pack = newPack()
        write(pack, "render_controllers/carryblock.json", scrambledRenderController)
        write(
            pack,
            "render_controllers/textures/textures/models/entity/.u6.json",
            clearRenderController
        )

        val first = BedrockDecifrator.decrypt(pack)
        DirIndexCache.invalidate(pack)
        val second = BedrockDecifrator.decrypt(pack)

        assertEquals(1, first.restored.size)
        assertEquals(
            "La segunda pasada no debe volver a escribir nada",
            0, second.restored.size
        )
    }
    @Test
    fun `el archivo restaurado es siempre JSON parseable`() {
        val pack = newPack()
        write(pack, "render_controllers/carryblock.json", scrambledRenderController)
        write(
            pack,
            "render_controllers/textures/textures/models/entity/.u6.json",
            clearRenderController
        )

        val report = BedrockDecifrator.decrypt(pack)

        assertEquals(1, report.restored.size)
        report.restored.forEach { r ->
            val text = File(pack, r.targetPath).readText()
            val json = org.json.JSONObject(text)
            assertNotNull("Todo restaurado debe tener raíz reconocida", r.rootKey)
            assertTrue("Y parsear correctamente", json.has("format_version"))
        }
    }

    @Test
    fun `neutraliza el senuelo residual tras restaurar`() {
        val pack = newPack()
        write(pack, "entity/player.json", "Hi. This addon is closed-source.")
        write(
            pack,
            "entity/texts/textures/textures/carryblock/.OE.json",
            "{\"format_version\":\"1.8.0\",\"minecraft:client_entity\":{\"description\":" +
                "{\"identifier\":\"minecraft:player\",\"materials\":{\"default\":\"entity_alphablob\"}," +
                "\"textures\":{\"default\":\"textures/entity/player\"}," +
                "\"geometry\":{\"default\":\"geometry.humanoid.custom\"}}}}"
        )

        BedrockDecifrator.decrypt(pack)

        // El señuelo no debe seguir vivo: Bedrock lo leería y fallaría.
        assertFalse(
            "El señuelo roto debe desaparecer de la ruta canónica",
            File(pack, "entity/player.json").exists()
        )
        assertTrue(
            "Pero la evidencia se conserva en .bak",
            File(pack, "entity/player.json.bak").exists()
        )
        assertTrue(
            "Y el restaurado sigue en su sitio",
            File(pack, "entity/player.entity.json").exists()
        )
    }

    @Test
    fun `restaura contenido oculto en el BEHAVIOR pack`() {
        val rp = newPack()
        val bp = newPack()
        write(bp, "entities/creature.json", "Hi. This addon is closed-source.")
        write(
            bp,
            "entities/parts/parts/textures/.q7.json",
            "{\"format_version\":\"1.20.0\",\"minecraft:entity\":{\"description\":" +
                "{\"identifier\":\"minecraft:armor_stand\"},\"component_groups\":{},\"components\":{}}}"
        )

        val report = BedrockDecifrator.decrypt(rp, bp)

        assertEquals(1, report.restored.size)
        assertEquals(
            "BP exige sufijo .entity.json",
            "entities/creature.entity.json",
            report.restored.first().targetPath
        )
        assertTrue(
            File(bp, "entities/creature.entity.json").readText()
                .contains("minecraft:armor_stand")
        )
        assertTrue(
            "El señuelo del BP también se neutraliza",
            File(bp, "entities/creature.json.bak").exists()
        )
    }

    @Test
    fun `neutraliza a bak un roto sin contraparte en claro`() {
        val pack = newPack()
        write(pack, "manifest.json", "{\"format_version\":2}")
        // Texto plano en ruta canónica que Bedrock SÍ lee, y SIN ningún
        // archivo en claro enterrado que permita restaurarlo.
        write(pack, "models/entity/carryblock.json", "Hi. This addon is closed-source.")

        val report = BedrockDecifrator.decrypt(pack)

        assertTrue(
            "Debe reportarse como irrecuperable",
            report.unrecoverable.contains("models/entity/carryblock.json")
        )
        assertTrue(
            "Debe contarse como neutralizado",
            report.neutralizedUnrecoverable.contains("models/entity/carryblock.json")
        )
        assertFalse(
            "El texto plano NO debe seguir en la ruta canónica que Bedrock lee",
            File(pack, "models/entity/carryblock.json").exists()
        )
        assertTrue(
            "La evidencia debe conservarse como .bak",
            File(pack, "models/entity/carryblock.json.bak").exists()
        )
    }

    @Test
    fun `la neutralizacion de irrecuperables es idempotente`() {
        val pack = newPack()
        write(pack, "manifest.json", "{\"format_version\":2}")
        write(pack, "models/entity/carryblock.json", "Hi. This addon is closed-source.")

        val primera = BedrockDecifrator.decrypt(pack)
        val segunda = BedrockDecifrator.decrypt(pack)

        assertEquals(
            "La 1ra pasada sí debe detectar el irrecuperable",
            1, primera.unrecoverable.size
        )
        assertEquals(
            "Tras neutralizar, la 2da pasada ya no ve texto plano en ruta canónica",
            0, segunda.unrecoverable.size
        )
        assertEquals(
            "La 2da pasada no debe volver a neutralizar",
            0, segunda.neutralizedUnrecoverable.size
        )
        assertTrue(
            "No debe crearse un .bak doble (.bak.bak)",
            !File(pack, "models/entity/carryblock.json.bak.bak").exists()
        )
        assertTrue(
            "El .bak debe seguir intacto tras dos pasadas",
            File(pack, "models/entity/carryblock.json.bak").exists()
        )
        assertEquals(
            "El contenido de la evidencia no debe alterarse entre pasadas",
            "Hi. This addon is closed-source.",
            File(pack, "models/entity/carryblock.json.bak").readText()
        )
    }

    @Test
    fun `recupera geometria envuelta en el cebo de comentario`() {
        // Reproduce el cebo REAL del addon: un comentario /* */ delante del JSON.
        val conComentario = "/*\n\nHi. This addon is closed-source.\n\n*/\n" +
            "{\"format_version\":\"1.8.0\",\"minecraft:geometry\":[{\"description\":{" +
            "\"identifier\":\"geometry.carryblock\"}}]}"
        val pack = newPack()
        write(pack, "manifest.json", "{\"format_version\":2}")

        // Documenta el limite que obliga a recortar: org.json lanza con comentarios.
        var orgjsonAceptoElComentario = false
        try {
            JSONObject(conComentario)
            orgjsonAceptoElComentario = true
        } catch (expected: Exception) {
        }
        assertFalse(
            "org.json NO admite comentarios C-style: por eso hay que recortar el cebo",
            orgjsonAceptoElComentario
        )

        // Fuente clara enterrada, igual que en el addon real.
        write(pack, "models/entity/entity/models/entity/.pp.json", conComentario)
        // Roto canonico: cebo + JSON incompleto, irrecuperable.
        write(pack, "models/entity/carryblock.json", "/* bait */{\"incomplete\":")

        val report = BedrockDecifrator.decrypt(pack)

        assertEquals(
            "La geometria envuelta en cebo SI debe reconocerse como fuente clara",
            1, report.restored.size
        )
        val restaurado = File(pack, "models/entity/carryblock.geometry.json")
        assertTrue("Debe escribirse en la ruta canonica de geometria", restaurado.exists())
        val texto = restaurado.readText()
        assertFalse(
            "El restaurado NO debe conservar el cebo: Bedrock lo rechazaria",
            texto.contains("closed-source")
        )
        assertTrue(
            "Debe contener la geometria real",
            texto.contains("geometry.carryblock")
        )
    }

    @Test
    fun `no toca un pack normal sin contenido closed-source`() {
        val pack = newPack()
        write(pack, "manifest.json", "{\"format_version\":2}")
        write(pack, "render_controllers/player.json", clearRenderController)

        val report = BedrockDecifrator.decrypt(pack)

        assertEquals(0, report.restored.size)
        assertEquals(0, report.unrecoverable.size)
        assertTrue(
            "El render controller válido debe seguir igual",
            File(pack, "render_controllers/player.json").readText()
                .contains("controller.render.carryblock.broken")
        )
    }
}