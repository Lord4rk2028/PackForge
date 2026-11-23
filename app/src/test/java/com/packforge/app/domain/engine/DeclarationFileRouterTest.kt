package com.packforge.app.domain.engine

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * TICKET-CORE-015 — los archivos de declaración única (`entity/`,
 * `attachables/`) se enrutan por **identifier**, no por nombre de archivo.
 *
 * El fallo que fija: dos addons con `entity/mob.json` pero identifiers
 * distintos se fusionaban sobre el mismo objeto raíz `minecraft:client_entity`
 * (clave fija) y la entidad del perdedor se quedaba sin definición de cliente
 * → mob invisible, con su `minecraft:entity` del BP aún presente.
 */
class DeclarationFileRouterTest {

    private val roots = mutableListOf<File>()

    @Before
    fun setUp() {
        ConflictRegistry.clear()
        JsonDeepMerger.clearConflicts()
        DirIndexCache.clear()
    }

    @After
    fun cleanUp() {
        roots.forEach { it.deleteRecursively() }
        ConflictRegistry.clear()
        JsonDeepMerger.clearConflicts()
        DirIndexCache.clear()
    }

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { roots += it }

    private fun pack(name: String, files: Map<String, String>): File {
        val root = File(tempDir("pf_router_"), name).apply { mkdirs() }
        files.forEach { (rel, content) ->
            val file = File(root, rel)
            file.parentFile?.mkdirs()
            file.writeText(content.trimIndent())
        }
        return root
    }

    private fun clientEntity(
        identifier: String,
        textures: String? = null,
        geometry: String? = null,
        formatVersion: String = "1.10.0"
    ): String = buildString {
        append("""{"format_version":"$formatVersion","minecraft:client_entity":{"description":{"identifier":"$identifier"""")
        textures?.let { append(""","textures":{"default":"$it"}""") }
        geometry?.let { append(""","geometry":{"default":"$it"}""") }
        append("}}}")
    }

    private fun archivosDe(dest: File, sub: String = "entity"): List<File> =
        File(dest, sub).walkTopDown().filter { it.isFile && it.extension == "json" }.toList()

    private fun route(
        dirs: List<File>,
        dest: File,
        sub: String = "entity",
        clave: String = "minecraft:client_entity"
    ) = runBlocking { DeclarationFileRouter.route(dirs, dest, sub, clave) }

    // ── 1. Distinto identifier + mismo nombre de archivo ──────────────────

    @Test
    fun twoEntitiesThatSharedAFilenameBothSurvive() {
        val a = pack(
            "rp_a",
            mapOf("entity/mob.json" to clientEntity("alpha:mob", geometry = "geometry.alpha"))
        )
        val b = pack(
            "rp_b",
            mapOf("entity/mob.json" to clientEntity("beta:mob", geometry = "geometry.beta"))
        )
        val dest = tempDir("dest")

        val result = route(listOf(a, b), dest)

        val archivos = archivosDe(dest)
        assertEquals("Cada identifier debe tener su archivo", 2, archivos.size)
        assertEquals("Uno de los dos tuvo que separarse", 1, result.divididos)

        val contenidos = archivos.map { it.readText() }
        assertTrue(
            "Debe existir el client_entity de alpha",
            contenidos.any { it.contains("alpha:mob") && it.contains("geometry.alpha") }
        )
        assertTrue(
            "Debe existir el client_entity de beta",
            contenidos.any { it.contains("beta:mob") && it.contains("geometry.beta") }
        )
        assertTrue(
            "Ningún archivo puede mezclar las dos entidades",
            contenidos.none { it.contains("alpha:mob") && it.contains("beta:mob") }
        )
    }

    @Test
    fun separatedFileKeepsTheContentOfItsOwnAddon() {
        // Regresión concreta del síntoma: el perdedor no puede quedar sin
        // textura ni heredar la geometría del ganador.
        val a = pack(
            "rp_a",
            mapOf(
                "entity/mob.json" to
                    clientEntity("alpha:mob", textures = "textures/entity/alpha", geometry = "geometry.alpha")
            )
        )
        val b = pack(
            "rp_b",
            mapOf(
                "entity/mob.json" to
                    clientEntity("beta:mob", textures = "textures/entity/beta", geometry = "geometry.beta")
            )
        )
        val dest = tempDir("dest")

        route(listOf(a, b), dest)

        val beta = archivosDe(dest).map { it.readText() }.first { it.contains("beta:mob") }
        assertTrue("beta conserva su textura", beta.contains("textures/entity/beta"))
        assertTrue("beta conserva su geometría", beta.contains("geometry.beta"))
        assertFalse("beta no hereda la geometría de alpha", beta.contains("geometry.alpha"))
    }

    // ── 2. Mismo identifier en archivos distintos ────────────────────────

    @Test
    fun sameIdentifierFromDifferentFilesIsMergedIntoOne() {
        val a = pack(
            "rp_a",
            mapOf("entity/mob_a.json" to clientEntity("shared:mob", textures = "textures/entity/shared"))
        )
        val b = pack(
            "rp_b",
            mapOf("entity/mob_b.json" to clientEntity("shared:mob", geometry = "geometry.shared"))
        )
        val dest = tempDir("dest")

        val result = route(listOf(a, b), dest)

        val archivos = archivosDe(dest)
        assertEquals("Un identifier = un archivo", 1, archivos.size)
        assertEquals("Debe reportarse como fusión", 1, result.fusionados)

        val json = JSONObject(archivos.first().readText())
        val desc = json.getJSONObject("minecraft:client_entity").getJSONObject("description")
        assertEquals("shared:mob", desc.getString("identifier"))
        assertTrue("Conserva la textura del primero", desc.getJSONObject("textures").has("default"))
        assertTrue("Conserva la geometría del segundo", desc.getJSONObject("geometry").has("default"))

        val conflictos = ConflictRegistry.conflicts.value
            .filter { it.conflictType == "ENTITY_IDENTIFIER_MERGED" }
        assertEquals("La fusión por identifier debe reportarse", 1, conflictos.size)
    }

    // ── 3. Overrides vanilla: reemplazo, no híbrido ──────────────────────

    @Test
    fun vanillaIdentifierIsReplacedInsteadOfMerged() {
        val a = pack(
            "rp_a",
            mapOf(
                "entity/zombie.json" to
                    clientEntity("minecraft:zombie", textures = "textures/entity/zombie_a", geometry = "geometry.a")
            )
        )
        val b = pack(
            "rp_b",
            mapOf(
                "entity/zombie.json" to
                    clientEntity("minecraft:zombie", textures = "textures/entity/zombie_b", geometry = "geometry.b")
            )
        )
        val dest = tempDir("dest")

        val result = route(listOf(a, b), dest)

        val archivos = archivosDe(dest)
        assertEquals("Un solo archivo para el override vanilla", 1, archivos.size)
        assertEquals("Se cuenta como reemplazo", 1, result.reemplazosVanilla)
        assertEquals("No se fusiona un override vanilla", 0, result.fusionados)

        val texto = archivos.first().readText()
        assertTrue("Gana el último addon", texto.contains("textures/entity/zombie_b"))
        assertFalse("No se mezcla la geometría del primero", texto.contains("geometry.a"))
        assertTrue(
            "Un override vanilla no debe generar conflicto de fusión",
            ConflictRegistry.conflicts.value.none { it.conflictType == "ENTITY_IDENTIFIER_MERGED" }
        )
    }

    // ── 4. Contenido sin identifier ──────────────────────────────────────

    @Test
    fun filesWithoutIdentifierArePreserved() {
        val a = pack(
            "rp_a",
            mapOf(
                "entity/extra.json" to """{"custom":"dato"}""",
                "entity/mob.json" to clientEntity("alpha:mob")
            )
        )
        val dest = tempDir("dest")

        val result = route(listOf(a), dest)

        assertTrue("El JSON sin identifier no puede perderse", File(dest, "entity/extra.json").exists())
        assertEquals(1, result.sinIdentificador)
    }

    // ── 5. attachables ───────────────────────────────────────────────────

    @Test
    fun attachablesAreRoutedByIdentifierToo() {
        fun attachable(id: String, geo: String) =
            """{"format_version":"1.10.0","minecraft:attachable":{"description":{"identifier":"$id","geometry":{"default":"$geo"}}}}"""

        val a = pack("rp_a", mapOf("attachables/item.json" to attachable("alpha:hat", "geometry.hat_a")))
        val b = pack("rp_b", mapOf("attachables/item.json" to attachable("beta:hat", "geometry.hat_b")))
        val dest = tempDir("dest")

        route(listOf(a, b), dest, "attachables", "minecraft:attachable")

        val archivos = archivosDe(dest, "attachables")
        assertEquals(2, archivos.size)
        assertTrue(archivos.any { it.readText().contains("alpha:hat") })
        assertTrue(archivos.any { it.readText().contains("beta:hat") })
    }

    // ── 6. Negociación de format_version (3.6) ───────────────────────────

    @Test
    fun sameEntityWithDifferentFormatVersionIsReportedWithoutRaisingIt() {
        // Dos addons declaran la MISMA entidad con esquemas distintos. La fusión
        // deja el `format_version` del último (prioridad), así que el contenido
        // del otro se reinterpreta bajo ese esquema: hay que verlo en el reporte.
        val a = pack(
            "rp_a",
            mapOf(
                "entity/mob.json" to clientEntity(
                    "alpha:mob",
                    textures = "textures/entity/alpha",
                    formatVersion = "1.10.0"
                )
            )
        )
        val b = pack(
            "rp_b",
            mapOf(
                "entity/mob.json" to clientEntity(
                    "alpha:mob",
                    geometry = "geometry.beta",
                    formatVersion = "1.21.0"
                )
            )
        )
        val dest = tempDir("dest")

        route(listOf(a, b), dest)

        val conflicto = ConflictRegistry.conflicts.value
            .firstOrNull { it.conflictType == "FORMAT_VERSION_MISMATCH" }
        assertTrue("La mezcla de esquemas debe llegar al reporte", conflicto != null)
        assertEquals(com.packforge.app.domain.model.ConflictSeverity.MEDIUM, conflicto!!.severity)
        assertEquals("rp_a", conflicto.sourceAddon)
        assertEquals("rp_b", conflicto.targetAddon)
        assertTrue("Debe nombrar las dos versiones", conflicto.description.contains("1.10.0"))
        assertTrue(conflicto.description.contains("1.21.0"))

        // El esquema que gobierna es el del ÚLTIMO addon: la versión NO se sube
        // ni se reescribe (regla 16).
        val salida = archivosDe(dest).single().readText()
        assertTrue(
            "El fichero fusionado debe declarar el esquema del último addon: $salida",
            salida.contains("\"format_version\":\"1.21.0\"")
        )
        assertTrue(
            "El contenido del otro addon se conserva (solo cambia el esquema que lo lee)",
            salida.contains("textures/entity/alpha") && salida.contains("geometry.beta")
        )
    }

    @Test
    fun sameFormatVersionIsNotAConflict() {
        val a = pack(
            "rp_a",
            mapOf("entity/mob.json" to clientEntity("alpha:mob", textures = "textures/alpha"))
        )
        val b = pack(
            "rp_b",
            mapOf("entity/mob.json" to clientEntity("alpha:mob", geometry = "geometry.beta"))
        )
        val dest = tempDir("dest")

        route(listOf(a, b), dest)

        assertFalse(
            "El mismo esquema repetido no es conflicto (regla 8)",
            ConflictRegistry.conflicts.value.any { it.conflictType == "FORMAT_VERSION_MISMATCH" }
        )
    }
}
