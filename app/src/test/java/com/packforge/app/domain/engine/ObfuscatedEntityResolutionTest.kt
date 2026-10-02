package com.packforge.app.domain.engine

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * ═══════════════════════════════════════════════════════════════════════
 * TICKET-CORE-001 — Duck Typing (Requisito A) en el resolver de entidades
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Los addons de MCPEDL usan nombres ofuscados (.fT.json, .7I.json). Estos
 * tests garantizan que se clasifican por CONTENIDO raíz y no por extensión,
 * causa de los mobs invisibles / texturas ausentes.
 */
class ObfuscatedEntityResolutionTest {

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
        val dir = Files.createTempDirectory("pf_obf_$name").toFile()
        tempRoots.add(dir)
        return dir
    }

    private fun write(dir: File, rel: String, body: String): File {
        val f = File(dir, rel)
        f.parentFile?.mkdirs()
        f.writeText(body)
        return f
    }

    private fun geo(identifier: String) = """
        {"format_version":"1.12.0",
         "minecraft:geometry":[
           {"description":{"identifier":"$identifier","texture_width":64,"texture_height":64},
            "bones":[{"name":"body","pivot":[0,12,0]}]}]}
    """.trimIndent()

    private fun clientEntity(identifier: String, geometry: String, textures: Map<String, String>) =
        buildString {
            appendLine("""{"format_version":"1.10.0",""")
            appendLine(""" "minecraft:client_entity":{"description":{""")
            appendLine("""   "identifier":"$identifier",""")
            appendLine("""   "geometry":{"default":"$geometry"},""")
            append("""   "textures":{""")
            append(textures.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" })
            appendLine("},")
            appendLine("""   "materials":{"default":"entity"},""")
            appendLine("""   "render_controllers":["rc_${identifier.substringAfter(':')}"]}}}""")
        }

// ── CASO 1: geometría ofuscada SÍ se encuentra ─────────────────────

    @Test
    fun obfuscatedGeometry_isFoundByContentAndNotReportedMissing() {
        val sources = tempDir("src")
        val merged = tempDir("merged")

        // Geometría con nombre de hash, NO ".geo.json".
        write(sources, "models/entity/fT.json", geo("geometry.packforge.golem"))
        // Entidad también ofuscada.
        write(
            merged, "entity/7I.json",
            clientEntity("packforge:golem", "geometry.packforge.golem", mapOf("default" to "textures/entity/golem"))
        )
        // Acompañantes para que no se reporten como faltantes.
        File(merged, "textures/entity").mkdirs()
        File(merged, "textures/entity/golem.png").writeBytes(byteArrayOf(1, 2, 3))
        write(
            merged, "render_controllers/rc_golem.json",
            """{"render_controllers":{"rc_golem":{"geometry":"Geometry.default"}}}"""
        )

        DirIndexCache.clear()
        val notes = EntityDependencyResolver.resolve(listOf(sources), merged)

        assertTrue(
            "La geometría ofuscada NO debe reportarse como faltante. Notas: $notes",
            notes.none { it.contains("geometría") }
        )
    }

    // ── CASO 2: el duck typing no oculta ausencias reales ──────────────

    @Test
    fun trulyMissingGeometry_isStillReported() {
        val sources = tempDir("src2")
        val merged = tempDir("merged2")

        write(sources, "models/entity/otro.json", geo("geometry.otra.cosas"))
        write(
            merged, "entity/aB.json",
            clientEntity("packforge:fantasma", "geometry.packforge.fantasma", emptyMap())
        )

        DirIndexCache.clear()
        val notes = EntityDependencyResolver.resolve(listOf(sources), merged)

        // El detalle de lo que falta viaja en ConflictRegistry (que alimenta la
        // pantalla de Conflictos); las notes solo dan el recuento.
        val conflicts = ConflictRegistry.conflicts.value
        assertTrue(
            "Una geometría realmente ausente DEBE registrarse como conflicto. Notas: $notes",
            conflicts.any { it.conflictType == "MISSING_DEPENDENCY" && it.description.contains("geometr") }
        )
    }

// ── CASO 3: mix de ofuscado y normal conviven ──────────────────────

    @Test
    fun obfuscatedAndNormalEntities_areBothProcessed() {
        val sources = tempDir("src3")
        val merged = tempDir("merged3")

        write(sources, "models/entity/geo_ok.geo.json", geo("geometry.packforge.normal"))
        write(sources, "models/entity/xY.json", geo("geometry.packforge.ofuscado"))

        write(
            merged, "entity/normal.entity.json",
            clientEntity("packforge:normal", "geometry.packforge.normal", mapOf("default" to "textures/entity/normal"))
        )
        write(
            merged, "entity/z9.json",
            clientEntity("packforge:ofuscado", "geometry.packforge.ofuscado", mapOf("default" to "textures/entity/ofuscado"))
        )

        DirIndexCache.clear()
        val notes = EntityDependencyResolver.resolve(listOf(sources), merged)

        assertTrue(
            "Ninguna geometría debe faltar (ni la normal ni la ofuscada). Notas: $notes",
            notes.none { it.contains("geometría") }
        )
    }

    // ── CASO 4: animaciones en archivos ofuscados ──────────────────────

    @Test
    fun obfuscatedAnimationFile_isIndexedByContent() {
        val sources = tempDir("src4")
        val merged = tempDir("merged4")

        write(
            sources, "animations/qW.json",
            """{"format_version":"1.8.0","animations":{"animation.packforge.walk":{"loop":true}}}"""
        )
        write(
            merged, "entity/anim.json",
            """
            {"format_version":"1.10.0",
             "minecraft:client_entity":{"description":{
               "identifier":"packforge:caminante",
               "animations":{"walk":"animation.packforge.walk"},
               "materials":{"default":"entity"}}}}
            """.trimIndent()
        )

        DirIndexCache.clear()
        val notes = EntityDependencyResolver.resolve(listOf(sources), merged)

        assertTrue(
            "La animación ofuscada no debe faltar. Notas: $notes",
            notes.none { it.contains("animación") }
        )
    }

    // ── CASO 5: sin carpeta entity no debe romper ───────────────────────

    @Test
    fun mergedPackWithoutEntityFolder_returnsNotesWithoutThrowing() {
        val sources = tempDir("src5")
        val merged = tempDir("merged5")
        write(sources, "textures/x.png", "{}")

        DirIndexCache.clear()
        val notes = EntityDependencyResolver.resolve(listOf(sources), merged)

        assertTrue("Debe devolver una nota explicativa, no fallar", notes.isNotEmpty())
        assertTrue("Debe indicar que falta entity/", notes.any { it.contains("entity") })
    }
}

