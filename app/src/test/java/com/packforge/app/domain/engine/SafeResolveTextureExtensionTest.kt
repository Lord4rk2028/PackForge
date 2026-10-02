package com.packforge.app.domain.engine

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * ═══════════════════════════════════════════════════════════════════════
 * TICKET-CORE-002 (extensión) — safeResolve() en la fase de RESOLUCIÓN
 * ═══════════════════════════════════════════════════════════════════════
 *
 * El mismo bug de la ausencia de extensión corregido en validateMerge existía
 * también aquí: safeResolve() construía la ruta literal ("textures/entity/
 * steve") y comprobaba exists() sobre ella, sin probar la extensión real del
 * archivo en disco (".png"). Toda textura referenciada sin extensión se
 * reportaba como ausente y se intentaba "recuperar" un archivo ya presente.
 */
class SafeResolveTextureExtensionTest {

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
        val dir = Files.createTempDirectory("pf_safe_$name").toFile()
        tempRoots.add(dir)
        return dir
    }

    private fun write(dir: File, rel: String, body: String): File {
        val f = File(dir, rel)
        f.parentFile?.mkdirs()
        f.writeText(body)
        return f
    }

    private fun png(dir: File, rel: String): File {
        val f = File(dir, rel)
        f.parentFile?.mkdirs()
        f.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
        return f
    }

    private fun entityJson(geometry: String, textureRef: String) = """
        {
          "format_version": "1.10.0",
          "minecraft:client_entity": {
            "description": {
              "identifier": "pack:golem",
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
              "description": { "identifier": "$identifier", "texture_width": 64, "texture_height": 64 },
              "bones": [ { "name": "body", "pivot": [0, 12, 0] } ]
            }
          ]
        }
    """.trimIndent()

    /**
     * El detalle de cada referencia irrecuperable NO viaja en las notes (que
     * solo dan el recuento): se registra en ConflictRegistry, que es lo que
     * alimenta la pantalla de Conflictos. Por eso los tests consultan ahí.
     */
    private fun missingTextureDetails(): List<String> =
        ConflictRegistry.conflicts.value
            .filter { it.conflictType == "MISSING_DEPENDENCY" }
            .map { it.description }

    private fun hasMissingTexture(): Boolean =
        missingTextureDetails().any { it.contains("textura") }

    /** Monta el escenario: geometría en fuentes, entidad + textura en el destino. */
    private fun stage(
        name: String,
        textureRef: String,
        textureFile: String?,
        ext: String = "png"
    ): Pair<File, File> {
    val sources = tempDir("src_$name")
   val merged = tempDir("merged_$name")
        write(sources, "models/entity/golem.geo.json", geoJson("geometry.pack.golem"))
        write(merged, "entity/golem.json", entityJson("geometry.pack.golem", textureRef))
        if (textureFile != null) png(merged, "$textureFile.$ext")
        return sources to merged
    }

    // ── CASO PRINCIPAL ──────────────────────────────────────────────────

    @Test
    fun textureReferencedWithoutExtension_presentOnDiskAsPng_isNotReportedMissing() {
        // El bug: la referencia es "textures/entity/steve" y el archivo real
        // es "textures/entity/steve.png". Antes se reportaba como ausente.
        val (sources, merged) = stage("png", "textures/entity/steve", "textures/entity/steve")

        DirIndexCache.clear()
        val notes = EntityDependencyResolver.resolve(listOf(sources), merged)

        assertTrue(
            "La textura SÍ está en disco como .png: no debe reportarse ausente. Notas: $notes",
            !hasMissingTexture()
        )
    }

    @Test
    fun allSupportedExtensions_presentOnDisk_areNotReportedMissing() {
        listOf("png", "jpg", "jpeg", "tga", "webp", "bmp").forEach { ext ->
            val (sources, merged) =
                stage("ext_$ext", "textures/entity/steve", "textures/entity/steve", ext)

            DirIndexCache.clear()
            val notes = EntityDependencyResolver.resolve(listOf(sources), merged)

            assertTrue(
                "La extensión .$ext está en disco y no debe reportarse ausente. Notas: $notes",
                !hasMissingTexture()
            )
        }
    }

    // ── NO DEBE ENMASCARAR AUSENCIAS REALES ────────────────────────────

    @Test
    fun genuinelyMissingTexture_isStillReported() {
        val (sources, merged) = stage("missing", "textures/entity/inexistente", null)

        DirIndexCache.clear()
        val notes = EntityDependencyResolver.resolve(listOf(sources), merged)

        assertTrue(
            "Una textura realmente ausente DEBE reportarse. Notas: $notes",
            hasMissingTexture()
        )
    }

    @Test
    fun nonImageBinary_isNotAcceptedAsTexture() {
        // Un .ogg no cuenta como textura, aunque comparte el nombre base.
        val sources = tempDir("src_ogg")
        val merged = tempDir("merged_ogg")
        write(sources, "models/entity/golem.geo.json", geoJson("geometry.pack.golem"))
        write(merged, "entity/golem.json", entityJson("geometry.pack.golem", "textures/entity/sonido"))
        File(merged, "textures/entity").mkdirs()
        File(merged, "textures/entity/sonido.ogg").writeBytes(byteArrayOf(1, 2, 3))

        DirIndexCache.clear()
        val notes = EntityDependencyResolver.resolve(listOf(sources), merged)

        assertTrue(
            "Un archivo de audio no debe satisfaer una referencia de textura. Notas: $notes",
            hasMissingTexture()
        )
    }

    // ── SANDBOX (no debe relajarse) ─────────────────────────────────────

    @Test
    fun pathTraversalTexture_isStillRejectedAsInsecure() {
        val sources = tempDir("src_sec")
        val merged = tempDir("merged_sec")
        write(sources, "models/entity/golem.geo.json", geoJson("geometry.pack.golem"))
        write(merged, "entity/golem.json", entityJson("geometry.pack.golem", "../../etc/passwd"))

     DirIndexCache.clear()
  EntityDependencyResolver.resolve(listOf(sources), merged)

        val details = missingTextureDetails()
        assertTrue(
"Una ruta fuera del sandbox debe seguir marcándose como insegura. Detalle: $details",
         details.any { it.contains("insegura") }
        )
    }

    @Test
    fun textureOutsideSandbox_isNotCreated() {
        val sources = tempDir("src_esc")
        val merged = tempDir("merged_esc")
        val outside = File(merged.parentFile, "fuera_de_contenido")
        outside.mkdirs()
        write(sources, "models/entity/golem.geo.json", geoJson("geometry.pack.golem"))
        write(
            merged, "entity/golem.json",
            entityJson("geometry.pack.golem", "../${outside.name}/robo.png")
        )

        DirIndexCache.clear()
        EntityDependencyResolver.resolve(listOf(sources), merged)

        assertFalse(
            "No debe crearse ningún archivo fuera del directorio del pack",
            File(outside, "robo.png").exists()
        )
    }
}




