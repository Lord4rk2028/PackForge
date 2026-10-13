package com.packforge.app.domain.engine

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * TICKET-CORE — Auditoría B5: sonidos en ResourcePathRegistry
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Bedrock resuelve `sound_definitions.json` SIN extensión y con
 * referencias tanto completas ("sounds/mob/x") como relativas a la raíz
 * ("mob/x"). Antes solo se generaban variantes relativas para `textures/`:
 * un .ogg renombrado por colisión dejaba la referencia huérfana → sonido
 * silencioso, sin ningún error visible en el Content Log.
 */
class ResourcePathSoundsTest {

    private val tempRoots = mutableListOf<File>()

    @Before
    fun setUp() {
        DirIndexCache.clear()
    }

    @After
    fun tearDown() {
        tempRoots.forEach { it.deleteRecursively() }
        tempRoots.clear()
        DirIndexCache.clear()
    }

    private fun tempDir(name: String): File {
        val dir = Files.createTempDirectory("pf_snd_$name").toFile()
        tempRoots.add(dir)
        DirIndexCache.invalidate(dir)
        return dir
    }

    private fun ogg(dir: File, marker: String): File {
        val f = File(dir, "sounds/mob/reef/ambient.ogg")
        f.parentFile.mkdirs()
        f.writeBytes(marker.toByteArray())
        return f
    }

    @Test
    fun sonidoColisionado_reescribe_referencias_sin_extension() {
        val a = tempDir("a")
        val b = tempDir("b")
        ogg(a, "AAA")
        ogg(b, "BBB")
        // Referencia tal como Bedrock la escribe: con prefijo sounds/ SIN extensión.
        File(b, "sounds/sound_definitions.json").writeText(
            """{"format_version":"1.14.0","sound_definitions":{"reef.ambient":{""" +
                """"category":"ambient","sounds":["sounds/mob/reef/ambient"]}}}"""
        )

        val reg = ResourcePathRegistry()
        reg.planAndCommit(a) // el primer addon compromete la ruta canónica
        val renames = reg.planAndCommit(b)

        assertTrue(
            "La colisión de .ogg debe producir alias",
            renames.containsKey("sounds/mob/reef/ambient.ogg")
        )

        DirIndexCache.invalidate(b)
        reg.applySourceRewrites(listOf(b))

        val ref = JSONObject(File(b, "sounds/sound_definitions.json").readText())
            .getJSONObject("sound_definitions").getJSONObject("reef.ambient")
            .getJSONArray("sounds").getString(0)
        assertFalse(
            "La referencia sin extensión debe reescribirse al alias",
            ref == "sounds/mob/reef/ambient"
        )
        assertTrue("Y apuntar al archivo aliasado", ref.contains("_pf"))
    }

    @Test
    fun sonido_colisionado_tambien_reescribe_forma_relativa_a_sounds() {
        val a = tempDir("a2")
        val b = tempDir("b2")
        ogg(a, "AAA")
        ogg(b, "BBB")
        // Algunos addons referencian RELATIVO a sounds/ ("mob/reef/ambient").
        File(b, "sounds/sound_definitions.json").writeText(
            """{"format_version":"1.14.0","sound_definitions":{"reef.ambient":{""" +
                """"category":"ambient","sounds":["mob/reef/ambient"]}}}"""
        )

        val reg = ResourcePathRegistry()
        reg.planAndCommit(a)
        val renames = reg.planAndCommit(b)
        assertTrue(renames.containsKey("sounds/mob/reef/ambient.ogg"))

        DirIndexCache.invalidate(b)
        reg.applySourceRewrites(listOf(b))

        val ref = JSONObject(File(b, "sounds/sound_definitions.json").readText())
            .getJSONObject("sound_definitions").getJSONObject("reef.ambient")
            .getJSONArray("sounds").getString(0)
        assertFalse(
            "La forma relativa también debe reescribirse",
            ref == "mob/reef/ambient"
        )
        assertTrue(ref.contains("_pf"))
    }
}
