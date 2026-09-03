package com.packforge.app.domain.engine

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class DependencyGraphResolverTest {

    private fun tmpDirs(vararg setup: (File) -> Unit): Pair<List<File>, List<File>> {
        val bp = Files.createTempDirectory("bp_").toFile()
        val rp = Files.createTempDirectory("rp_").toFile()
        bp.deleteOnExit(); rp.deleteOnExit()
        setup.forEach { it(bp) }
        return Pair(listOf(rp), listOf(bp))
    }

    private fun File.jsonFile(relPath: String, content: String) {
        val f = File(this, relPath)
        f.parentFile!!.mkdirs()
        f.writeText(content, StandardCharsets.UTF_8)
    }

    @Test
    fun graph_criticalMissingEntityDependency_aborts() {
        // Indexar: entity A referencia geometry custom no vanilla ni indexada → error crítico
        val bpDir = Files.createTempDirectory("bp2_").toFile().also { it.deleteOnExit() }
        val rpDir = Files.createTempDirectory("rp2_").toFile().also { it.deleteOnExit() }
        val outRp = Files.createTempDirectory("outRp_").toFile().also { it.deleteOnExit() }
        val outBp = Files.createTempDirectory("outBp_").toFile().also { it.deleteOnExit() }

        // Entidad RP que referencia geometría inexistente
        rpDir.jsonFile("entity/my_mob.entity.json", JSONObject().apply {
            put("minecraft:client_entity", JSONObject().apply {
                put("description", JSONObject().apply { put("identifier", "custom:my_mob") })
                put("render_controllers", "controller.render.my_mob_custom")
                put("geometry", JSONObject().apply { put("default", "geometry.my_custom_mob") })
            })
        }.toString())

        // Recoger el entity como target del grafo (por eso debe estar en output también)
        outRp.jsonFile("entity/my_mob.entity.json", JSONObject().apply {
            put("minecraft:client_entity", JSONObject().apply {
                put("description", JSONObject().apply { put("identifier", "custom:my_mob") })
                put("render_controllers", "controller.render.my_mob_custom")
                put("geometry", JSONObject().apply { put("default", "geometry.my_custom_mob") })
            })
        }.toString())

        val result = DependencyGraphResolver.run(
            rpDirs = listOf(rpDir), bpDirs = listOf(bpDir), outputRp = outRp, outputBp = outBp
        )

        // geometry.my_custom_mob no existe en índice ni en destino → error crítico
        assertTrue("Se espera al menos un error crítico", result.criticalErrors.isNotEmpty())
        assertTrue(result.criticalErrors.any { it.type == DependencyGraphResolver.ResType.GEOMETRY })
    }

    @Test
    fun graph_vanillaGeometry_notCritical() {
        val bpDir = Files.createTempDirectory("bp3_").toFile().also { it.deleteOnExit() }
        val rpDir = Files.createTempDirectory("rp3_").toFile().also { it.deleteOnExit() }
        val outRp = Files.createTempDirectory("outRp3_").toFile().also { it.deleteOnExit() }
        val outBp = Files.createTempDirectory("outBp3_").toFile().also { it.deleteOnExit() }

        outRp.jsonFile("entity/cow.entity.json", JSONObject().apply {
            put("minecraft:client_entity", JSONObject().apply {
                put("description", JSONObject().apply { put("identifier", "minecraft:cow") })
                put("geometry", JSONObject().apply { put("default", "geometry.cow") })
            })
        }.toString())

        val result = DependencyGraphResolver.run(
            rpDirs = listOf(rpDir), bpDirs = listOf(bpDir), outputRp = outRp, outputBp = outBp
        )
        assertTrue("Geometría vanilla no debe generar errores críticos", result.criticalErrors.isEmpty())
    }

    @Test
    fun graph_resolvesGeometryTransitivamente() {
        val bpDir = Files.createTempDirectory("bp4_").toFile().also { it.deleteOnExit() }
        val rpDir = Files.createTempDirectory("rp4_").toFile().also { it.deleteOnExit() }
        val outRp = Files.createTempDirectory("outRp4_").toFile().also { it.deleteOnExit() }
        val outBp = Files.createTempDirectory("outBp4_").toFile().also { it.deleteOnExit() }

        // RP fuente con geometría disponible
        val geoContent = JSONObject().apply {
            put("minecraft:geometry", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("description", JSONObject().apply { put("identifier", "geometry.my_boss") })
                    put("bones", org.json.JSONArray())
                })
            })
        }.toString()
        rpDir.jsonFile("models/entity/my_boss.geo.json", geoContent)

        // Destino referencea esa geometría
        outRp.jsonFile("entity/boss.entity.json", JSONObject().apply {
            put("minecraft:client_entity", JSONObject().apply {
                put("description", JSONObject().apply { put("identifier", "custom:boss") })
                put("geometry", JSONObject().apply { put("default", "geometry.my_boss") })
            })
        }.toString())

        val result = DependencyGraphResolver.run(
            rpDirs = listOf(rpDir), bpDirs = listOf(bpDir), outputRp = outRp, outputBp = outBp
        )
        assertTrue("No debe haber errores críticos cuando la dependencia existe", result.criticalErrors.isEmpty())
        val copied = result.copiedByType[DependencyGraphResolver.ResType.GEOMETRY] ?: 0
        assertTrue("Se espera copia de geometría", copied > 0 || File(outRp, "models/entity/my_boss.geo.json").exists())
    }

    @Test
    fun vanillaResources_isVanillaGeometryPrefixes() {
        assertTrue(DependencyGraphResolver.VanillaResources.isVanilla(
            DependencyGraphResolver.ResType.GEOMETRY, "geometry.villager_v2"))
        assertTrue(DependencyGraphResolver.VanillaResources.isVanilla(
            DependencyGraphResolver.ResType.GEOMETRY, "geometry.zombie_custom"))
        assertFalse(DependencyGraphResolver.VanillaResources.isVanilla(
            DependencyGraphResolver.ResType.GEOMETRY, "geometry.my_dragon_boss"))
    }

    @Test
    fun vanillaResources_minecraftNamespaceIsAlwaysVanilla() {
        assertTrue(DependencyGraphResolver.VanillaResources.isVanilla(
            DependencyGraphResolver.ResType.GEOMETRY, "minecraft:ender_dragon"))
    }

    @Test
    fun graph_newResTypesSpawnRulesNotCriticalWhenMissing() {
        val bpDir = Files.createTempDirectory("bp5_").toFile().also { it.deleteOnExit() }
        val rpDir = Files.createTempDirectory("rp5_").toFile().also { it.deleteOnExit() }
        val outRp = Files.createTempDirectory("outRp5_").toFile().also { it.deleteOnExit() }
        val outBp = Files.createTempDirectory("outBp5_").toFile().also { it.deleteOnExit() }

        // Sin añadir spawn_rules al grafo → no hay error porque no se referencia
        val result = DependencyGraphResolver.run(
            rpDirs = listOf(rpDir), bpDirs = listOf(bpDir), outputRp = outRp, outputBp = outBp
        )
        assertTrue("Grafo vacío no debe producir errores", result.criticalErrors.isEmpty())
    }
}
