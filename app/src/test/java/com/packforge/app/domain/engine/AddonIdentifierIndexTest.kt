package com.packforge.app.domain.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La recolección de identificadores alimenta a los detectores de ConflictEngine.
 * Si se deja incompleta, el conflicto existe en el modpack pero nunca se
 * reporta: el usuario no lo ve y la fusión sobrescribe en silencio.
 *
 * Se prueba `AddonIdentifierIndex.Builder.accept`, que es EXACTAMENTE el código
 * que ejecuta `AddonParser` archivo a archivo (el parser necesita un `Context`
 * de Android y no se puede ejecutar en un test JVM).
 */
class AddonIdentifierIndexTest {

    private fun json(text: String) = JSONObject(text)

    @Test
    fun collectsEntityItemAndRecipeIdentifiers() {
        val builder = AddonIdentifierIndex.Builder()

        builder.accept(
            "bp/entities/zombie.json",
            json("""{"minecraft:entity":{"description":{"identifier":"mypack:zombie"}}}""")
        )
        builder.accept(
            "bp/items/sword.json",
            json("""{"minecraft:item":{"description":{"identifier":"mypack:sword"}}}""")
        )
        builder.accept(
            "bp/recipes/sword.json",
            json("""{"minecraft:recipe_shaped":{"description":{"identifier":"mypack:sword_recipe"}}}""")
        )

        val ids = builder.build()
        assertEquals(listOf("mypack:zombie"), ids.entity)
        assertEquals(listOf("mypack:sword"), ids.item)
        assertEquals(listOf("mypack:sword_recipe"), ids.recipe)
    }

    @Test
    fun collectsLootTableIdentifierFromItsPath() {
        // En Bedrock el identificador de una loot table ES su ruta, y se
        // conserva la extensión para que `affectedFile` apunte a una ruta real.
        val builder = AddonIdentifierIndex.Builder()

        builder.accept("bp/loot_tables/entities/zombie.json", json("""{"pools":[]}"""))

        assertEquals(
            listOf("loot_tables/entities/zombie.json"),
            builder.build().lootTable
        )
    }

    @Test
    fun collectsParticleIdentifierFromDescription() {
        val builder = AddonIdentifierIndex.Builder()

        builder.accept(
            "rp/particles/dust.json",
            json("""{"particle_effect":{"description":{"identifier":"custom:dust"},"components":{}}}""")
        )

        assertEquals(listOf("custom:dust"), builder.build().particle)
    }

    @Test
    fun collectsSoundDefinitionsKeysSkippingFormatVersion() {
        val builder = AddonIdentifierIndex.Builder()

        builder.accept(
            "rp/sound_definitions.json",
            json("""{"format_version":1,"sound.hit":{"category":"block"},"sound.step":{"category":"player"}}""")
        )

        assertEquals(
            listOf("sound.hit", "sound.step"),
            builder.build().soundDefinition
        )
    }

    @Test
    fun collectsAnimationControllerKeys() {
        val builder = AddonIdentifierIndex.Builder()

        builder.accept(
            "rp/animation_controllers/mob.json",
            json("""{"format_version":"1.10.0","animation_controllers":{"controller.animation.mob.walk":{}}}""")
        )

        assertEquals(
            listOf("controller.animation.mob.walk"),
            builder.build().animationController
        )
    }

    @Test
    fun collectsMaterialIdentifiersFromMaterialFiles() {
        // Los `.material` son JSON con otra extensión.
        val builder = AddonIdentifierIndex.Builder()

        builder.accept(
            "rp/materials/entity.material",
            json("""{"materials":{"version":"1.0.0","custom:glow":{"states":[]}}}""")
        )

        assertEquals(listOf("custom:glow"), builder.build().materialInstance)
    }

    @Test
    fun ignoresIrrelevantFilesAndMalformedJson() {
        val builder = AddonIdentifierIndex.Builder()

        builder.accept("rp/textures/blocks/stone.png", null)
        builder.accept("rp/manifest.json", json("""{"format_version":2}"""))
        builder.accept("rp/particles/broken.json", null)

        val ids = builder.build()
        assertTrue(ids.entity.isEmpty())
        assertTrue(ids.item.isEmpty())
        assertTrue(ids.recipe.isEmpty())
        assertTrue(ids.lootTable.isEmpty())
        assertTrue(ids.particle.isEmpty())
        assertTrue(ids.materialInstance.isEmpty())
        assertTrue(ids.soundDefinition.isEmpty())
        assertTrue(ids.animationController.isEmpty())
    }

    @Test
    fun duplicatesAreRemoved() {
        val builder = AddonIdentifierIndex.Builder()
        val particle = json("""{"particle_effect":{"description":{"identifier":"custom:dust"}}}""")

        builder.accept("rp/particles/a.json", particle)
        builder.accept("rp/particles/b.json", particle)

        assertEquals("Un id repetido no debe contarse dos veces", 1, builder.build().particle.size)
    }

    @Test
    fun everyDetectorOfConflictEngineGetsData() {
        // Candado contra el fallo original: 5 de los 8 tipos llegaban VACÍOS a
        // ConflictEngine, así que sus detectores recorrían listas vacías.
        val builder = AddonIdentifierIndex.Builder()

        builder.accept("bp/entities/z.json", json("""{"minecraft:entity":{"description":{"identifier":"p:z"}}}"""))
        builder.accept("bp/items/i.json", json("""{"minecraft:item":{"description":{"identifier":"p:i"}}}"""))
        builder.accept("bp/recipes/r.json", json("""{"minecraft:recipe_shaped":{"description":{"identifier":"p:r"}}}"""))
        builder.accept("bp/loot_tables/l.json", json("""{"pools":[]}"""))
        builder.accept("rp/particles/p.json", json("""{"particle_effect":{"description":{"identifier":"p:dust"}}}"""))
        builder.accept("rp/materials/m.material", json("""{"materials":{"p:glow":{}}}"""))
        builder.accept("rp/sound_definitions.json", json("""{"p:hit":{}}"""))
        builder.accept("rp/animation_controllers/a.json", json("""{"animation_controllers":{"p:ctrl":{}}}"""))

        val ids = builder.build()
        assertTrue("entidades", ids.entity.isNotEmpty())
        assertTrue("ítems", ids.item.isNotEmpty())
        assertTrue("recetas", ids.recipe.isNotEmpty())
        assertTrue("loot tables", ids.lootTable.isNotEmpty())
        assertTrue("partículas", ids.particle.isNotEmpty())
        assertTrue("materiales", ids.materialInstance.isNotEmpty())
        assertTrue("sonidos", ids.soundDefinition.isNotEmpty())
        assertTrue("animation controllers", ids.animationController.isNotEmpty())
    }
}
