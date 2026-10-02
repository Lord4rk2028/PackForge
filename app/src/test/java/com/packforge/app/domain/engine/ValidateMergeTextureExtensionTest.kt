package com.packforge.app.domain.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * ═══════════════════════════════════════════════════════════════════════
 * TICKET-CORE-002 — Falsos positivos de textura en validateMerge()
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Bedrock referencia las texturas SIN extensión ("textures/entity/steve")
 * mientras el ZIP guarda el archivo real CON extensión
 * ("textures/entity/steve.png"). El validador comparaba de forma estricta y
 * reportaba cada mob como "textura no empaquetada" aunque el binario existiera.
 *
 * Estos tests construyen ZIPs REALES y validan contra validateMerge().
 */
class ValidateMergeTextureExtensionTest {

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

    // ── HELPERS ─────────────────────────────────────────────────────────

    private fun tempDir(name: String): File {
        val dir = Files.createTempDirectory("pf_vmerge_$name").toFile()
        tempRoots.add(dir)
        return dir
    }

    /** Crea un .mcaddon real con las entradas dadas (nombre → contenido). */
    private fun buildAddon(name: String, entries: Map<String, ByteArray>): File {
        val file = File(tempDir(name), "$name.mcaddon")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            for ((path, content) in entries) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return file
    }

    private fun jsonEntry(text: String): ByteArray = text.trimIndent().toByteArray()

    private fun pngEntry(): ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A)

    /** Entity JSON con la textura referenciada como se indica en [textureRef]. */
    private fun entityJson(identifier: String, geometry: String, textureRef: String) = """
        {
          "format_version": "1.10.0",
          "minecraft:client_entity": {
            "description": {
              "identifier": "$identifier",
              "geometry": { "default": "$geometry" },
              "textures": { "default": "$textureRef" },
              "materials": { "default": "entity" }
            }
          }
        }
    """.trimIndent()

    private fun geoJson(identifier: String) = """
        {
          "format_version": "1.12.0",
          "minecraft:geometry": [
            {
              "description": {
                "identifier": "$identifier",
                "texture_width": 64,
                "texture_height": 64
              },
              "bones": [ { "name": "body", "pivot": [0, 12, 0] } ]
            }
          ]
        }
    """.trimIndent()

    /** Paquete base con una entidad válida, para aislar el caso bajo prueba. */
    private fun basePack(
        entries: MutableMap<String, ByteArray>,
     entityPath: String,
        textureRef: String
    ) {
        entries["RP_pack/entity/$entityPath"] =
            jsonEntry(entityJson("pack:golem", "geometry.pack.golem", textureRef))
        entries["RP_pack/models/entity/golem.geo.json"] = jsonEntry(geoJson("geometry.pack.golem"))
  }

    private fun textureErrors(addon: File): List<String> =
        EntityDependencyResolver.validateMerge(addon.absolutePath)
     .filter { it.contains("textura") && it.contains("no est") }

    // ── CASO PRINCIPAL DEL TICKET ──────────────────────────────────────
    @Test
    fun textureReferencedWithoutExtension_presentInZipAsPng_isNotReported() {
        // El caso exacto del reporte: la referencia va SIN extensión,
        // el ZIP guarda "textures/entity/steve.png".
        val entries = mutableMapOf<String, ByteArray>()
        basePack(entries, "golem.entity.json", "textures/entity/steve")
        entries["RP_pack/textures/entity/steve.png"] = pngEntry()

        val errs = textureErrors(buildAddon("png_case", entries))
        assertTrue(
            "No debe reportar la textura: SÍ está empaquetada como .png. Errores: $errs",
            errs.isEmpty()
        )
    }

    @Test
    fun allSupportedImageExtensions_areAccepted() {
        // png, jpg, jpeg, tga, webp y bmp deben aceptarse sin falsos positivos.
        listOf("png", "jpg", "jpeg", "tga", "webp", "bmp").forEach { ext ->
            val entries = mutableMapOf<String, ByteArray>()
            basePack(entries, "golem.entity.json", "textures/entity/steve")
            entries["RP_pack/textures/entity/steve.$ext"] = pngEntry()

            val errs = textureErrors(buildAddon("ext_$ext", entries))
            assertTrue(
                "La extensión .$ext debe aceptarse como textura presente. Errores: $errs",
                errs.isEmpty()
            )
        }
    }

    @Test
    fun textureReferencedWithoutExtension_presentUnderDifferentPackPrefix_isNotReported() {
        // En un .mcaddon el pack cuelga de una carpeta cuyo nombre lo genera
        // la app ("RP_<nombre>"). La referencia del JSON no la incluye, así que
        // el fallback debe tolerar ese prefijo variable.
        val entries = mutableMapOf<String, ByteArray>()
        basePack(entries, "golem.entity.json", "textures/entity/steve")
        // El prefijo NO coincide con el de la entidad ("RP_pack").
        entries["Otro_Pack/textures/entity/steve.png"] = pngEntry()

        val errs = textureErrors(buildAddon("prefix_case", entries))
        assertTrue("Debe resolverse ignorando el prefijo del pack. Errores: $errs", errs.isEmpty())
    }

    @Test
    fun sameBasenameInDifferentFolder_isNotSilentlyAccepted() {
        // "textures/mobs/custom/steve.png" NO satisface "textures/entity/steve":
        // un fallback por solo-basenamewould dar por buena una textura ajena.
        val entries = mutableMapOf<String, ByteArray>()
        basePack(entries, "golem.entity.json", "textures/entity/steve")
        entries["RP_pack/textures/mobs/custom/steve.png"] = pngEntry()

        val errs = textureErrors(buildAddon("wrong_folder", entries))
        assertTrue(
            "Una textura de otra carpeta NO debe darse por válida. Errores: $errs",
            errs.isNotEmpty()
        )
    }

    @Test
    fun textureReferencedWithExtension_presentInZip_isNotReported() {
        val entries = mutableMapOf<String, ByteArray>()
        basePack(entries, "golem.entity.json", "textures/entity/steve.png")
        entries["RP_pack/textures/entity/steve.png"] = pngEntry()

        assertTrue("Referencia con extensión válida", textureErrors(buildAddon("ext_ref", entries)).isEmpty())
    }

    @Test
    fun textureReferencedWithMutationSuffix_presentInZip_isNotReported() {
        // Caso TICKET-001: archivo renombrado por colisión de nombres.
     val entries = mutableMapOf<String, ByteArray>()
        basePack(entries, "golem.entity.json", "textures/entity/steve_pf2")
        entries["RP_pack/textures/entity/steve_pf2.png"] = pngEntry()

        assertTrue(
        "La textura mutada por colisión debe considerarse presente",
            textureErrors(buildAddon("mutated", entries)).isEmpty()
        )
    }

    // ── NO DEBE PERDER DETECCIÓN DE AUSENCIAS REALES ────────────────────
    @Test
    fun genuinelyMissingTexture_isStillReported() {
        // La corrección NO debe convertir el validador en un "todo bien".
        val entries = mutableMapOf<String, ByteArray>()
        basePack(entries, "golem.entity.json", "textures/entity/inexistente")
        entries["RP_pack/textures/entity/steve.png"] = pngEntry()

        val errs = textureErrors(buildAddon("missing", entries))
        assertTrue("Una textura realmente ausente DEBE reportarse. Errores: $errs", errs.isNotEmpty())
    }

    @Test
    fun missingTextureWithExtension_isStillReported() {
        val entries = mutableMapOf<String, ByteArray>()
        basePack(entries, "golem.entity.json", "textures/entity/fantasma.png")
        entries["RP_pack/textures/entity/steve.png"] = pngEntry()

        assertTrue(
            "Referencia con extensión y ausente también debe reportarse",
            textureErrors(buildAddon("missing_ext", entries)).isNotEmpty()
        )
    }

    @Test
    fun nonImageBinary_isNotAcceptedAsTexture() {
        // Un .ogg NO es una textura: la corrección no debe aceptarlo.
        val entries = mutableMapOf<String, ByteArray>()
        basePack(entries, "golem.entity.json", "textures/entity/sonido")
        entries["RP_pack/textures/entity/sonido.ogg"] = byteArrayOf(1, 2, 3)

        assertTrue(
            "Un archivo de audio no debe contar como textura",
            textureErrors(buildAddon("ogg", entries)).isNotEmpty()
        )
    }

    // ── PAQUETES COMPLETOS ─────────────────────────────────────────────

    @Test
    fun validPackWithMultipleEntities_hasNoErrors() {
        val entries = mutableMapOf<String, ByteArray>()
        basePack(entries, "golem.entity.json", "textures/entity/steve")
        entries["RP_pack/textures/entity/steve.png"] = pngEntry()
        entries["RP_pack/entity/slime.json"] =
            jsonEntry(entityJson("pack:slime", "geometry.pack.slime", "textures/entity/slime"))
        entries["RP_pack/models/entity/slime.geo.json"] = jsonEntry(geoJson("geometry.pack.slime"))
        entries["RP_pack/textures/entity/slime.jpg"] = pngEntry()

        val all = EntityDependencyResolver.validateMerge(buildAddon("multi", entries).absolutePath)
        assertTrue("Un paquete válido no debe reportar errores: $all", all.isEmpty())
    }

    @Test
    fun obfuscatedEntityFile_isValidatedByContentNotExtension() {
        // Requisito A + TICKET-002 combinados: entidad con nombre ofuscado.
        val entries = mutableMapOf<String, ByteArray>()
        basePack(entries, "7I.json", "textures/entity/steve")
        entries["RP_pack/textures/entity/steve.png"] = pngEntry()

        val all = EntityDependencyResolver.validateMerge(buildAddon("obf", entries).absolutePath)
        assertTrue("La entidad ofuscada debe validarse sin falsos positivos: $all", all.isEmpty())
    }

    @Test
    fun nonExistentZip_returnsErrorWithoutThrowing() {
        val missing = File(tempDir("nope"), "no_existe.mcaddon")
        val errors = EntityDependencyResolver.validateMerge(missing.absolutePath)

        assertEquals(
            "Debe devolver un error legible en vez de propagar la excepción",
            1, errors.size
        )
        assertTrue(errors.first().contains("No se pudo validar"))
    }

    @Test
    fun emptyEntityFolder_producesNoTextureErrors() {
        val entries = mapOf<String, ByteArray>("RP_pack/textures/entity/steve.png" to pngEntry())
        val all = EntityDependencyResolver.validateMerge(buildAddon("empty", entries).absolutePath)

        assertFalse("Sin entidades no debe haber errores de textura", all.any { it.contains("textura") })
    }
}

