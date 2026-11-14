package com.packforge.app.domain.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * ⭐ FASE 5 — ¿Huérfana del ORIGEN o defecto de la fusión?
 *
 * El validador marcaba las 5 `cc:*_market_villager` de CoreCraft como
 * "entidades incompletas" porque no encontraba su definición en el BP. Con
 * "Modelos faltantes" ya en 0 y "no existe en el paquete" también en 0, esas 5
 * eran lo único que quedaba en pie del reporte original — y no eran culpa de la
 * fusión:
 *
 * **Cada una aparece en un solo archivo de los 9.405 del addon, el suyo.** Ni
 * `spawn_rules`, ni `scripts`, ni `structures`, ni un `spawn_egg`, ni un
 * `/summon` las invoca. Tampoco podían aparecer con el addon original instalado
 * tal cual. Son contenido muerto que el autor dejó sin conectar.
 *
 * Este test fija la distinción que evita confundirlas con un fallo real:
 *
 * - nadie la nombra            → huérfana del origen (aviso, no defecto)
 * - alguien la nombra y no está → el mob no aparecerá (defecto de verdad)
 *
 * ⚠️ Y NO hay catálogo de entidades vanilla en ninguna parte. Enumerar los mobs
 * del juego obligaría a actualizar una lista con cada versión (armadillo,
 * breeze, bogged, camel, creaking, happy_ghast…) y este repo ya tiene escrito
 * ese epitafio en `VANILLA_GEOMETRY_PREFIXES`: las listas a mano "SIEMPRE se
 * quedan cortas" y costaron ~1.000 errores falsos. La evidencia de si una
 * entidad se usa está en el propio pack.
 */
class Fase5HuerfanasOrigenTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("pf_huerfanas").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun bp(): File = File(root, "BP_PackForge").apply { mkdirs() }

    private fun escribir(bp: File, ruta: String, contenido: String): File {
        val f = File(bp, ruta)
        f.parentFile?.mkdirs()
        f.writeText(contenido, Charsets.UTF_8)
        return f
    }

    @Test
    fun `una entidad que nadie nombra es huerfana del origen`() {
        val bp = bp()
        // El pack tiene contenido: otra entidad y su regla de aparición.
        escribir(bp, "entities/otra.json", """{"minecraft:entity":{"description":{"identifier":"cc:otra"}}}""")
        escribir(bp, "spawn_rules/otra.json", """{"minecraft:spawn_rules":{"description":{"identifier":"cc:otra"}}}""")

        // Nada menciona a la market villager → huérfana, no defecto.
        assertNull(
            "sin ninguna cita debe ser huérfana del origen",
            EntityOrphanClassifier.findReference(bp, "cc:mining_market_villager")
        )
    }

    @Test
    fun `una regla de aparicion que la nombra SI es evidencia de defecto`() {
        val bp = bp()
        escribir(bp, "spawn_rules/mercado.json", """
            {
              "minecraft:spawn_rules": {
                "description": { "identifier": "cc:farming_market_villager" }
              }
            }
        """.trimIndent())

        val cita = EntityOrphanClassifier.findReference(bp, "cc:farming_market_villager")
        assertNotNull("la regla de aparición la nombra: hay defecto real", cita)
        assertEquals("spawn_rules/mercado.json", cita)
    }

    @Test
    fun `un script del BP que la invoca cuenta como referencia`() {
        val bp = bp()
        escribir(
            bp,
            "scripts/mercado.js",
            """world.getDimension("overworld").spawnEntity("cc:cooking_market_villager", {x:0,y:0,z:0});"""
        )

        assertEquals(
            "scripts/mercado.js",
            EntityOrphanClassifier.findReference(bp, "cc:cooking_market_villager")
        )
    }

    @Test
    fun `una entidad definida en el propio BP se encuentra como referencia`() {
        val bp = bp()
        // Caso normal (271 de las 271): la definición existe. El escáner debe
        // verla para que el validador NUNCA llegue a clasificarla como huérfana.
        escribir(
            bp,
            "entities/villager_mercado.json",
            """{"minecraft:entity":{"description":{"identifier":"cc:adventure_market_villager"},"components":{}}}"""
        )

        assertEquals(
            "entities/villager_mercado.json",
            EntityOrphanClassifier.findReference(bp, "cc:adventure_market_villager")
        )
    }

    @Test
    fun `una imagen no se confunde con una referencia`() {
        val bp = bp()
        // Bytes binarios que casualmente contienen el identificador.
        val png = File(bp, "textures/entity/falsa.png")
        png.parentFile?.mkdirs()
        png.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) + "cc:fishing_market_villager".toByteArray())

        assertNull(
            "un PNG con esos bytes no es alguien invocando la entidad",
            EntityOrphanClassifier.findReference(bp, "cc:fishing_market_villager")
        )
    }

    @Test
    fun `la carpeta interna de sombras no cuenta como referencia`() {
        val bp = bp()
        escribir(bp, ".shadow/entities/zombie.json", """{"identifier":"cc:mining_market_villager"}""")

        assertNull(
            "la sombra la genera el propio pipeline: no es uso del autor",
            EntityOrphanClassifier.findReference(bp, "cc:mining_market_villager")
        )
    }

    @Test
    fun `se detecta aunque caiga entre dos bloques de lectura`() {
        val bp = bp()
        val id = "cc:partido_entre_bloques"
        // El solape entre bloques es lo único que hace fiable el escaneo: si se
        // perdiera, una entidad realmente invocada se clasificaría como huérfana
        // y el usuario no recibiría el aviso de que su mob no aparece.
        val relleno = "a".repeat(65536 - 12)
        escribir(bp, "entities/limite.json", relleno + id + relleno.repeat(80))

        assertTrue(
            "el archivo debe forzar varias lecturas por bloques",
            File(bp(), "entities/limite.json").length() > 3L * 65536
        )
        assertEquals(
            "entities/limite.json",
            EntityOrphanClassifier.findReference(bp, id)
        )
    }

    @Test
    fun `el lote resuelve varias entidades en una sola pasada y omite las ausentes`() {
        val bp = bp()
        escribir(bp, "spawn_rules/una.json", """{"identifier":"cc:una"}""")
        escribir(bp, "scripts/dos.js", """spawnEntity("cc:dos")""")

        val hallados = EntityOrphanClassifier.findReferences(
            bp, listOf("cc:una", "cc:dos", "cc:no_aparece_nunca")
        )

        assertEquals(2, hallados.size)
        assertEquals("spawn_rules/una.json", hallados["cc:una"])
        assertEquals("scripts/dos.js", hallados["cc:dos"])
        assertNull("no citada = huérfana del origen", hallados["cc:no_aparece_nunca"])
    }

    @Test
    fun `un BP inexistente o vacio no rompe y responde huerfana`() {
        assertNull(EntityOrphanClassifier.findReference(null, "cc:x"))
        assertNull(EntityOrphanClassifier.findReference(bp(), "cc:x"))
        assertNull(EntityOrphanClassifier.findReference(bp(), "  "))
        assertNull(EntityOrphanClassifier.findReference(File(root, "no_existe"), "cc:x"))
    }
}
