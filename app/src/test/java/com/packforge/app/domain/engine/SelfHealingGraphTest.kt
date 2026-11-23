package com.packforge.app.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Tests del grafo de dependencias (TICKET-CORE-012).
 *
 * Lo que se fija aquí no es «hay un grafo», sino las tres cosas que ningún
 * validador anterior sabía responder:
 *
 *  1. **Atribución**: un eslabón roto se asigna al addon que lo cita.
 *  2. **Defecto del addon ≠ defecto de la fusión**: una `client_entity` sin
 *     `minecraft:entity` no aparece ni con el addon instalado solo, así que no
 *     es culpa de PackForge (caso real: `jkslayer10:blue_shulker_head`).
 *  3. **La dirección que nadie miraba**: `minecraft:entity` custom sin
 *     `client_entity` existe en el mundo pero no se dibuja.
 */
class SelfHealingGraphTest {

    private fun raiz(): File = Files.createTempDirectory("pf_dag_").toFile()

    private fun escribir(root: File, ruta: String, contenido: String) {
        val f = File(root, ruta)
        f.parentFile?.mkdirs()
        f.writeText(contenido, Charsets.UTF_8)
    }

    private fun bpEntity(id: String) =
        """{"format_version":"1.20.50","minecraft:entity":{"description":{"identifier":"$id"},"components":{}}}"""

    private fun rpClientEntity(
        id: String,
        geometry: String = "geometry.test",
        texture: String = "textures/entity/test",
        rc: String = "controller.render.test",
        anim: String = "animation.test"
    ) =
        """{"format_version":"1.20.50","minecraft:client_entity":{"description":{
            "identifier":"$id",
            "geometry":{"default":"$geometry"},
            "textures":{"default":"$texture"},
            "animations":{"idle":"$anim"},
            "render_controllers":["$rc"]}}}"""

    private fun geoSrc(id: String) =
        """{"format_version":"1.12.0","minecraft:geometry":[{"description":{"identifier":"$id"},"bones":[]}]}"""

    private fun rcSrc(id: String) =
        """{"format_version":"1.8.0","render_controllers":{"$id":{"geometry":"Geometry.default","materials":[{"*":"Material.default"}],"textures":["Texture.default"]}}}"""

    private fun animSrc(id: String) =
        """{"format_version":"1.8.0","animations":{"$id":{"loop":true,"bones":{}}}}"""

    // ── 1. Cadena completa: sin huecos ───────────────────────────────────────

    @Test
    fun cadenaCompletaNoProduceHuecos() {
        val bp = raiz(); val rp = raiz()
        escribir(bp, "entities/mob.json", bpEntity("test:mob"))
        escribir(rp, "entity/mob.json", rpClientEntity("test:mob"))
        escribir(rp, "models/entity/test.geo.json", geoSrc("geometry.test"))
        escribir(rp, "render_controllers/test.json", rcSrc("controller.render.test"))
        escribir(rp, "animations/test.animation.json", animSrc("animation.test"))
        escribir(rp, "textures/entity/test.png", "PNG")

        val grafo = DependencyDAG.build(
            listOf(
                DependencyDAG.Source("MiBP", bp, true),
                DependencyDAG.Source("MiRP", rp, false)
            )
        )

        assertTrue("no debe haber huecos: ${grafo.gaps.map { it.describe() }}", grafo.gaps.isEmpty())
        assertTrue(grafo.entidadesSinCliente.isEmpty())
        assertTrue(grafo.clientesSinEntidad.isEmpty())
        assertTrue(grafo.declarados[DependencyDAG.Kind.CLIENT_ENTITY]!!.contains("test:mob"))
    }

    // ── 2. Atribución: el hueco se asigna al addon que lo cita ───────────────

    @Test
    fun unEslabonFaltanteSeAtribuyeAlAddonQueLoCita() {
        val bpA = raiz(); val rpA = raiz()
        escribir(bpA, "entities/mob.json", bpEntity("test:mob"))
        // Cita una animación que NADIE aporta.
        escribir(rpA, "entity/mob.json", rpClientEntity("test:mob", anim = "animation.inexistente"))
        escribir(rpA, "models/entity/test.geo.json", geoSrc("geometry.test"))
        escribir(rpA, "render_controllers/test.json", rcSrc("controller.render.test"))
        escribir(rpA, "textures/entity/test.png", "PNG")

        val grafo = DependencyDAG.build(
            listOf(
                DependencyDAG.Source("AddonA", bpA, true),
                DependencyDAG.Source("AddonA_RP", rpA, false)
            )
        )

        val huecos = grafo.gaps.filter { it.kind == DependencyDAG.Kind.ANIMATION }
        assertEquals(1, huecos.size)
        val hueco = huecos.first()
        assertEquals("animation.inexistente", hueco.id)
        assertTrue("debe atribuirse al addon que lo cita", hueco.owners.contains("AddonA_RP"))
        assertEquals(1, grafo.gapsDe("AddonA_RP").size)
        assertTrue(grafo.huecosPorAddon().any { it.first == "AddonA_RP" && it.second == 1 })
    }

    // ── 3. Defecto DEL ADDON, no de la fusión ────────────────────────────────

    /**
     * Caso real medido: OriginsBE define `jkslayer10:blue_shulker_head` solo como
     * `client_entity`, sin `minecraft:entity`. Ese mob no aparece nunca, ni con
     * el addon original: es contenido muerto, no un fallo de la fusión.
     */
    @Test
    fun clientEntitySinEntidadSeMarcaComoDefectoDelAddon() {
        val bp = raiz(); val rp = raiz()
        escribir(bp, "entities/otro.json", bpEntity("test:otro"))
        escribir(rp, "entity/huerfano.json", rpClientEntity("test:huerfano"))
        escribir(rp, "models/entity/test.geo.json", geoSrc("geometry.test"))
        escribir(rp, "render_controllers/test.json", rcSrc("controller.render.test"))
        escribir(rp, "animations/test.animation.json", animSrc("animation.test"))
        escribir(rp, "textures/entity/test.png", "PNG")

        val grafo = DependencyDAG.build(
            listOf(
                DependencyDAG.Source("MiBP", bp, true),
                DependencyDAG.Source("MiRP", rp, false)
            )
        )

        assertTrue(grafo.clientesSinEntidad.contains("test:huerfano"))
        assertTrue(grafo.resumen().any { it.contains("contenido muerto del origen") })
    }

    // ── 4. La dirección que nadie miraba: entidad que no se dibuja ──────────

    @Test
    fun entidadCustomSinClientEntitySeDetecta() {
        val bp = raiz(); val rp = raiz()
        escribir(bp, "entities/invisible.json", bpEntity("test:invisible"))

        val grafo = DependencyDAG.build(
            listOf(
                DependencyDAG.Source("MiBP", bp, true),
                DependencyDAG.Source("MiRP", rp, false)
            )
        )

        assertTrue(
            "una entidad sin client_entity existe pero no se dibuja",
            grafo.entidadesSinCliente.contains("test:invisible")
        )
        assertTrue(grafo.resumen().any { it.contains("sin client_entity") })
    }

    // ── 5. Lo vanilla NO es un hueco ─────────────────────────────────────────

    /**
     * Un mob vanilla sin definición en el pack no es un eslabón roto: la pone el
     * juego. Marcarlo fue el origen de miles de falsos positivos.
     */
    @Test
    fun loVanillaNoCuentaComoHueco() {
        val bp = raiz(); val rp = raiz()
        // Todo lo que cita esta entidad es vanilla: geometría, textura, render
        // controller y animación las pone el juego, no el pack.
        escribir(
            rp, "entity/armadillo.json",
            rpClientEntity(
                "minecraft:armadillo",
                geometry = "geometry.armadillo",
                texture = "textures/entity/armadillo",
                rc = "controller.render.armadillo.v2",
                anim = "animation.armadillo.walk"
            )
        )

        val grafo = DependencyDAG.build(
            listOf(
                DependencyDAG.Source("MiBP", bp, true),
                DependencyDAG.Source("MiRP", rp, false)
            )
        )

        assertTrue("la entidad vanilla no es huérfana", grafo.clientesSinEntidad.isEmpty())
        assertTrue(
            "geometry.armadillo la resuelve el juego",
            grafo.gaps.none { it.kind == DependencyDAG.Kind.GEOMETRY }
        )
    }

    // ── 6. Alias de entidad y Molang no son referencias ──────────────────────

    /**
     * `Geometry.default` es un alias interno y `query.is_baby ? ...` es Molang:
     * ninguno apunta a un activo, así que no pueden generar huecos.
     */
    @Test
    fun aliasYExpresionesMolangNoGeneranHuecos() {
        val rp = raiz()
        escribir(
            rp, "entity/alias.json",
            """{"format_version":"1.20.50","minecraft:client_entity":{"description":{
               "identifier":"test:alias",
               "geometry":{"default":"Geometry.default"},
               "textures":{"default":"Texture.default"},
               "render_controllers":["controller.render.test"],
               "animations":{"idle":"animation.test"}}}}"""
        )
        escribir(rp, "render_controllers/test.json", rcSrc("controller.render.test"))
        escribir(rp, "animations/test.animation.json", animSrc("animation.test"))

        val grafo = DependencyDAG.build(listOf(DependencyDAG.Source("MiRP", rp, false)))

        assertTrue(
            "alias no son huecos: ${grafo.gaps.map { "${it.kind}:${it.id}" }}",
            grafo.gaps.none { it.kind == DependencyDAG.Kind.GEOMETRY || it.kind == DependencyDAG.Kind.TEXTURE }
        )
    }

    // ── 7. El grafo no modifica nada ─────────────────────────────────────────

    @Test
    fun elGrafoNoEscribeNiBorraFicheros() {
        val bp = raiz(); val rp = raiz()
        escribir(bp, "entities/mob.json", bpEntity("test:mob"))
        escribir(rp, "entity/mob.json", rpClientEntity("test:mob"))

        fun inventario(): List<String> =
            listOf(bp, rp).flatMap { root ->
                root.walkTopDown().filter { it.isFile }
                    .map { it.relativeTo(root).path }.toList()
            }.sorted()

        val antes = inventario()
        DependencyDAG.build(
            listOf(
                DependencyDAG.Source("MiBP", bp, true),
                DependencyDAG.Source("MiRP", rp, false)
            )
        )
        assertEquals("el grafo es de solo lectura", antes, inventario())
    }
}
