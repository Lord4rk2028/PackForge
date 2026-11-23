package com.packforge.app.domain.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * TICKET-CORE-014 - Tokens estables entre exportaciones.
 *
 * El sufijo _pfXXXX de un identificador duplicado debe ser unico dentro de la
 * fusion pero estable entre exportaciones del mismo addon. Antes derivaba de
 * root.absolutePath.hashCode(), que cambia con cada exportacion porque el
 * directorio extraido lleva un timestamp. Ahora deriva del contenido SHA-256.
 *
 * La unicidad dentro de una fusion se garantiza desambiguando por el ORDEN de
 * la lista (ver ParticleDeduplicator): dos addons con contenido identico en la
 * misma fusion reciben tokens distintos. Lo que estos tests fijan es la otra
 * mitad: el MISMO addon, solo o en la misma posicion, produce el mismo token
 * entre exportaciones.
 */
class TokenEstableTest {

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
        Files.createTempDirectory("pf_token_$name").toFile().also { roots += it }

    private fun write(root: File, rel: String, body: String) {
        val f = File(root, rel)
        f.parentFile?.mkdirs()
        f.writeText(body)
    }

    private fun dirConParticula(id: String, extra: String = ""): File {
        val d = tempDir("part")
        val cuerpo = buildString {
            append("{\"format_version\":\"1.10.0\",\"particle_effect\":{\"description\":{\"identifier\":\"")
            append(id)
            append("\"")
            if (extra.isNotEmpty()) {
                append(",")
                append(extra)
            }
            append("}}}")
        }
        write(d, "particles/dust.json", cuerpo)
        return d
    }

    private fun tokenDe(dir: File): String {
        val p = dir.listFiles()!!.first { it.name == "particles" }
        val def = p.listFiles()!!.first { it.name == "dust.json" }
        val texto = def.readText()
        return texto.substringAfter("_pf").substringBefore("\"")
    }

    /** El MISMO addon, re-exportado (misma carpeta, misma posicion), da el mismo token. */
    @Test
    fun elMismoAddonEnDosExportacionesDaElMismoToken() {
        val contenido = """{"format_version":"1.10.0","particle_effect":{"description":{"identifier":"custom:estable"}}}"""
        val exp1 = tempDir("exp1")
        write(exp1, "particles/dust.json", contenido)
        // Segunda exportacion: misma carpeta, mismo contenido.
        val exp2 = tempDir("exp2")
        write(exp2, "particles/dust.json", contenido)
        val rival = tempDir("rival")
        write(rival, "particles/dust.json", contenido)

        // exp1 y exp2 en la MISMA posicion relativa a rival -> mismo token.
        val r1 = ParticleDeduplicator.deduplicate(listOf(rival, exp1))
        val r2 = ParticleDeduplicator.deduplicate(listOf(rival, exp2))
        assertEquals(1, r1.renames)
        assertEquals(1, r2.renames)
        assertEquals("mismo addon, mismo orden -> mismo token", tokenDe(exp1), tokenDe(exp2))
    }

    /** Un addon solo, re-exportado, mantiene su token aun con una partida distinta despues. */
    @Test
    fun elTokenEsEstableCuandoElAddonNoCambiaDePosicion() {
        val contenidoA = """{"format_version":"1.10.0","particle_effect":{"description":{"identifier":"custom:alpha"}}}"""
        val contenidoB = """{"format_version":"1.10.0","particle_effect":{"description":{"identifier":"custom:beta"}}}"""

        val a1 = tempDir("a1"); write(a1, "particles/dust.json", contenidoA)
        val b1 = tempDir("b1"); write(b1, "particles/dust.json", contenidoB)

        // a pierde contra b en ambas "exportaciones": su token depende de su
        // contenido y de su posicion (primera en la lista de perdedores).
        ParticleDeduplicator.deduplicate(listOf(b1, a1))
        val t1 = tokenDe(a1)

        // Segunda exportacion: a tiene EL MISMO contenido y va en la misma
        // posicion -> mismo token.
        val a2 = tempDir("a2"); write(a2, "particles/dust.json", contenidoA)
        val b2 = tempDir("b2"); write(b2, "particles/dust.json", contenidoB)
        ParticleDeduplicator.deduplicate(listOf(b2, a2))
        val t2 = tokenDe(a2)

        assertEquals(t1, t2)
    }

    /** Contenido distinto -> token distinto (hash de contenido). */
    @Test
    fun contenidoDistintoDaTokenDistinto() {
        val a = dirConParticula("custom:comun", "\"var\":\"A\"")
        val b = dirConParticula("custom:comun", "\"var\":\"B\"")
        val rival = dirConParticula("custom:comun")

        val r = ParticleDeduplicator.deduplicate(listOf(rival, a, b))
        assertEquals(2, r.renames)
        assertNotEquals("contenido distinto -> token distinto", tokenDe(a), tokenDe(b))
    }

    @Test
    fun elTokenEsHexCorto() {
        val a = dirConParticula("custom:hex")
        val b = dirConParticula("custom:hex")
        val rival = dirConParticula("custom:hex")
        val r = ParticleDeduplicator.deduplicate(listOf(rival, a, b))
        assertEquals(2, r.renames)
        val t = tokenDe(a)
        assertTrue(t.length >= 4)
        assertTrue(t.all { it in "0123456789abcdef" })
    }
}