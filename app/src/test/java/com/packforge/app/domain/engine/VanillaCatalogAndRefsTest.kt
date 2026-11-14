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
 * Regresiones del P0 (Fases 0-2) — nacidas del reporte de fusión de **Better MC**.
 *
 * El `.mcaddon` real demostró tres fallos que este test fija:
 *
 *  1. VANILLA: 243 líneas `❌ [ZIP] … ausente` → solo 11 identificadores distintos,
 *     9 de ellos assets de Mojang (`animation.common.look_at_target` con 211
 *     apariciones, `controller.render.item_sprite`, `textures/entity/boat/…`).
 *  2. PARSING: objetos condicionales de `render_controllers` y mapas de
 *     `bone_visibility` se serializaban y se buscaban como ids/modelos; el Molang
 *     se trataba como nombre de archivo.
 *  3. HEALER: 163 placeholders 8×8×8, **148 duplicando un identificador que ya
 *     existía** y **13 pisando identificadores VANILLA** (`geometry.arrow`,
 *     `geometry.humanoid.armor.*`), lo que rompe el render de todo el juego.
 */
class VanillaCatalogAndRefsTest {

    private val roots = mutableListOf<File>()

    @Before
    fun setUp() {
        roots.clear()
        // Idempotente: carga el catálogo empaquetado (classpath/assets).
        VanillaAssets.loadBundledCatalog(null)
    }

    @After
    fun cleanUp() {
        roots.forEach { it.deleteRecursively() }
    }

    private fun tmp(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { roots += it }

    private fun write(root: File, rel: String, content: String): File {
        val f = File(root, rel)
        f.parentFile?.mkdirs()
        f.writeText(content)
        return f
    }

    private fun clientEntity(
        id: String,
        geometry: String,
        texture: String,
        animations: Map<String, String> = emptyMap(),
        renderControllers: List<String> = emptyList()
    ): String {
        val anims = animations.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }
        val rcs = renderControllers.joinToString(",") { "\"$it\"" }
        return """{"format_version":"1.10.0","minecraft:client_entity":{"description":{
            "identifier":"$id",
            "materials":{"default":"entity_alphatest"},
            "textures":{"default":"$texture"},
            "geometry":{"default":"$geometry"},
            "animations":{$anims},
            "render_controllers":[$rcs]}}}"""
    }

    private fun geometryFile(id: String): String =
        """{"format_version":"1.12.0","minecraft:geometry":[{"description":{"identifier":"$id","texture_width":16,"texture_height":16},"bones":[{"name":"root","pivot":[0,0,0]}]}]}"""

    // ═══════════════════════════════════════════════════════════════════
    // FASE 0 — catálogo vanilla
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun bundledCatalogIsPackagedAndLoadable() {
        assertTrue(
            "El catálogo de Mojang debe empaquetarse (assets/ + classpath) y cargarse",
            VanillaAssets.catalogEntryCount > 1000
        )
        // Id que NO cubre ninguna heurística de prefijos: solo el catálogo real.
        assertTrue(
            "animation.agent.move solo puede reconocerse con el catálogo",
            VanillaAssets.isVanillaAnimation("animation.agent.move")
        )
    }

    @Test
    fun vanillaIdsThatProduced243ZipErrorsAreRecognized() {
        // Las 6 familias que generaban las 243 líneas "❌ [ZIP] … ausente".
        assertTrue(VanillaAssets.isVanillaAnimation("animation.common.look_at_target"))
        assertTrue(VanillaAssets.isVanillaAnimation("animation.actor.billboard"))
        assertTrue(VanillaAssets.isVanillaAnimation("animation.arrow.move"))
        assertTrue(VanillaAssets.isVanillaAnimation("controller.animation.iron_golem.move"))
        assertTrue(VanillaAssets.isVanillaAnimation("controller.animation.iron_golem.arm_movement"))
        assertTrue(VanillaAssets.isVanillaRenderController("controller.render.item_sprite"))
        assertTrue(VanillaAssets.isVanillaGeometry("geometry.item_sprite"))
        assertTrue(VanillaAssets.isVanillaTexture("textures/entity/boat/chest_boat_oak"))
        assertTrue(VanillaAssets.isVanillaTexture("textures/entity/arrows"))
        assertTrue(VanillaAssets.isVanillaTexture("textures/particle/soul"))
        // Y un id custom NO se vuelve vanilla por accidente.
        assertFalse(VanillaAssets.isVanillaAnimation("animation.cc.biped.walk"))
        assertFalse(VanillaAssets.isVanillaRenderController("controller.render.cc.villager.level"))
    }

    // ═══════════════════════════════════════════════════════════════════
    // FASE 1 — normalizador de referencias
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun renderControllersAreReadFromObjectKeysNeverSerialized() {
        val json = org.json.JSONObject(
            """{"render_controllers":[
                 {"controller.render.cc.villager.level":"q.variant > 0"},
                 "controller.render.cc.common"]}"""
        )
        val ids = BedrockRefs.renderControllerIds(json.opt("render_controllers"))
        assertEquals(listOf("controller.render.cc.villager.level", "controller.render.cc.common"), ids)
        assertTrue("Nunca debe aparecer el objeto serializado", ids.none { it.startsWith("{") })
    }

    @Test
    fun geometryMapIgnoresBoneVisibilityAndAliases() {
        val json = org.json.JSONObject(
            """{"geometry":{"default":"geometry.cc.apple","bone_visibility":{"bot_ne":true,"top_sw":false}}}"""
        )
        assertEquals(listOf("geometry.cc.apple"), BedrockRefs.geometryIds(json.opt("geometry")))
        assertEquals(emptyList<String>(), BedrockRefs.geometryIds("Geometry.default"))
        assertEquals(emptyList<String>(), BedrockRefs.geometryIds("q.is_baby ? Geometry.baby : Geometry.default"))
        assertEquals(emptyList<String>(), BedrockRefs.geometryIds("{\"bot_ne\":true,\"top_sw\":false}"))
    }

    @Test
    fun animationAliasesDoNotBecomeIdsOrPaths() {
        val json = org.json.JSONObject(
            """{"animations":{"walk":"animation.cc.biped.walk","look":"animation.common.look_at_target","alias":"Texture.default"}}"""
        )
        val ids = BedrockRefs.animationIds(json)
        // org.json no garantiza orden de claves: se compara como conjunto.
        assertEquals(
            setOf("animation.cc.biped.walk", "animation.common.look_at_target"),
            ids.toSet()
        )
        assertFalse("Un alias no es un id", ids.any { it.startsWith("Texture.") })
        assertEquals(emptyList<String>(), BedrockRefs.texturePaths("Texture.default"))
    }

    // ═══════════════════════════════════════════════════════════════════
    // FASE 2 — healer no destructivo
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun healerDoesNotDuplicateAnAlreadyDefinedGeometryIdentifier() {
        val rp = tmp("rp_")
        val bp = tmp("bp_")
        // Nombre OFUSCADO: el inventario por nombre de archivo no lo encontraba y
        // el healer generaba un placeholder con el MISMO identificador.
        write(rp, "models/entity/2jm1s73o.json", geometryFile("geometry.cc.baby"))
        write(rp, "entity/mob.json", clientEntity("cc:mob", "geometry.cc.baby", "textures/entity/mob"))
        write(rp, "textures/entity/mob.png", "x")

        val result = PackForgeHealer.repair(bp, rp, emptyList())

        val placeholders = rp.walkTopDown().filter { it.name.contains("placeholder") }.toList()
        assertTrue("No debe duplicar el identificador existente: $placeholders", placeholders.isEmpty())
        assertEquals(0, result.placeholdersGenerated)
    }

    @Test
    fun healerNeverShadowsVanillaGeometry() {
        val rp = tmp("rp_")
        val bp = tmp("bp_")
        // El .mcaddon real definía geometry.humanoid.armor.helmet y geometry.arrow:
        // eso sustituye el modelo vanilla y rompe TODO el render del juego.
        write(
            rp, "entity/armor.json",
            clientEntity("cc:armor", "geometry.humanoid.armor.helmet", "textures/entity/armor")
        )
        write(rp, "textures/entity/armor.png", "x")

        val result = PackForgeHealer.repair(bp, rp, emptyList())

        val placeholders = rp.walkTopDown().filter { it.name.contains("placeholder") }.toList()
        assertTrue("Nunca un placeholder sobre un id vanilla: $placeholders", placeholders.isEmpty())
        assertEquals(0, result.placeholdersGenerated)
    }

    @Test
    fun healerStillProtectsAgainstAGenuinelyMissingCustomGeometry() {
        val rp = tmp("rp_")
        val bp = tmp("bp_")
        write(rp, "entity/ghost.json", clientEntity("cc:ghost", "geometry.cc.ghost", "textures/entity/g"))
        write(rp, "textures/entity/g.png", "x")

        val result = PackForgeHealer.repair(bp, rp, emptyList())

        // La red de seguridad se mantiene: la entidad al menos spawnea visible.
        assertEquals(1, result.placeholdersGenerated)
        assertTrue(File(rp, "models/entity/geometry.cc.ghost.placeholder.geo.json").isFile)
    }

    @Test
    fun healerCopiesTexturesToTheReferencedPathNotToTexturesEntity() {
        val rp = tmp("rp_")
        val bp = tmp("bp_")
        val addon = tmp("addon_")
        // Ruta CUSTOM (las de textures/blocks/ o textures/ui/ son vanilla y no se tocan).
        write(addon, "textures/cc/block/dirt.png", "x")
        write(rp, "models/entity/mob.geo.json", geometryFile("geometry.mob"))
        write(rp, "entity/mob.json", clientEntity("cc:mob", "geometry.mob", "textures/cc/block/dirt"))

        PackForgeHealer.repair(bp, rp, listOf(addon.absolutePath))

        assertTrue(
            "La textura debe copiarse a su ruta real",
            File(rp, "textures/cc/block/dirt.png").isFile
        )
        assertFalse(
            "No debe copiarse a textures/entity (la referencia seguiría rota)",
            File(rp, "textures/entity/dirt.png").isFile
        )
    }

    @Test
    fun vanillaTexturesAreNotReportedAsMissing() {
        val rp = tmp("rp_")
        val bp = tmp("bp_")
        write(rp, "models/entity/mob.geo.json", geometryFile("geometry.mob"))
        // wool_colored_white / hunger_effect_full / bucket_water / particle/soul
        // aparecían en "sin textura en ningún addon" siendo vanilla.
        write(rp, "entity/mob.json", clientEntity("cc:mob", "geometry.mob", "textures/ui/hunger_effect_full"))

        val result = PackForgeHealer.repair(bp, rp, emptyList())

        assertFalse(
            "Una textura vanilla la resuelve el juego: ${result.stillMissingTextures}",
            result.stillMissingTextures.any { it.contains("hunger_effect_full") }
        )
        assertEquals(0, result.texturesCopied)
    }

    @Test
    fun healerRemovesStalePlaceholdersThatShadowRealOrVanillaModels() {
        val rp = tmp("rp_")
        val bp = tmp("bp_")
        // Pack "ya fusionado" antes del arreglo: modelo real + su placeholder
        // duplicado, y un placeholder que pisa una geometría vanilla.
        write(rp, "models/entity/2jm1s73o.json", geometryFile("geometry.cc.baby"))
        write(rp, "models/entity/geometry.cc.baby.placeholder.geo.json", geometryFile("geometry.cc.baby"))
        write(rp, "models/entity/geometry.arrow.placeholder.geo.json", geometryFile("geometry.arrow"))
        // Este NO es dañino: su id no lo define nadie más y no es vanilla.
        write(rp, "models/entity/geometry.cc.ghost.placeholder.geo.json", geometryFile("geometry.cc.ghost"))
        write(rp, "entity/mob.json", clientEntity("cc:mob", "geometry.cc.baby", "textures/entity/mob"))
        write(rp, "textures/entity/mob.png", "x")

        val result = PackForgeHealer.repair(bp, rp, emptyList())

        assertEquals(2, result.stalePlaceholdersRemoved)
        assertFalse(
            "El placeholder duplicado debe salir del pack",
            File(rp, "models/entity/geometry.cc.baby.placeholder.geo.json").exists()
        )
        assertFalse(
            "Un placeholder no puede pisar geometry.arrow (vanilla)",
            File(rp, "models/entity/geometry.arrow.placeholder.geo.json").exists()
        )
        assertTrue(
            "El placeholder legítimo (id ausente y no vanilla) se conserva",
            File(rp, "models/entity/geometry.cc.ghost.placeholder.geo.json").exists()
        )
        // Y se conserva la evidencia dentro de _packforge/ (excluida del .mcaddon y
        // de los índices JSON por el sufijo .bak).
        assertTrue(File(rp, "_packforge/stale_placeholders/geometry.arrow.placeholder.geo.json.bak").isFile)
    }

    @Test
    fun itemTextureArrayVariantsAreNotStringified() {
        val rp = tmp("rp_")
        val bp = tmp("bp_")
        // En item_texture.json `textures` es un ARRAY cuando hay variantes.
        // `optString` devolvía "[\"textures/items/amethyst_helmet\"]" y el reporte
        // de Better MC lo publicaba como 100 conflictos HIGH "sin origen".
        write(rp, "textures/items/amethyst_helmet.png", "x")
        write(
            rp, "textures/item_texture.json",
            """{"resource_pack_name":"x","texture_name":"atlas.items","texture_data":{
                "amethyst_helmet":{"textures":["textures/items/amethyst_helmet"]}}}"""
        )

        val report = PackForgeHealer.heal(bp, rp)

        assertTrue(
            "Ninguna referencia debe contener corchetes serializados: ${report.unresolved.map { it.missingRef }}",
            report.unresolved.none { it.missingRef.contains("[") || it.missingRef.contains("\"") }
        )
        assertTrue(
            "La textura existe: no es una referencia rota: ${report.unresolved.map { it.missingRef }}",
            report.unresolved.none { it.missingRef.contains("amethyst_helmet") }
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    // FASE 1 — validación [ZIP] de extremo a extremo
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun zipValidationDoesNotReportVanillaDependencies() {
        val zip = File(tmp("zip_"), "test.mcaddon")
        val entity = """{"format_version":"1.10.0","minecraft:client_entity":{"description":{
            "identifier":"cc:mob",
            "materials":{"default":"entity_alphatest"},
            "textures":{"default":"textures/entity/mob"},
            "geometry":{"default":"geometry.cc.mob"},
            "animations":{
                "look":"animation.common.look_at_target",
                "move":"controller.animation.iron_golem.move",
                "billboard":"animation.actor.billboard"},
            "render_controllers":["controller.render.item_sprite"]}}}"""

        ZipOutputStream(zip.outputStream()).use { out ->
            fun put(name: String, content: String) {
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
            put("RP/entity/mob.json", entity)
            put("RP/models/entity/mob.geo.json", geometryFile("geometry.cc.mob"))
            put("RP/textures/entity/mob.png", "x")
        }

        val errors = EntityDependencyResolver.validateMerge(zip.absolutePath)

        assertTrue(
            "Ninguna de estas 5 referencias es un error (todas son vanilla): $errors",
            errors.none {
                it.contains("animation.common.look_at_target") ||
                    it.contains("animation.actor.billboard") ||
                    it.contains("controller.animation.iron_golem.move") ||
                    it.contains("controller.render.item_sprite")
            }
        )
    }
}