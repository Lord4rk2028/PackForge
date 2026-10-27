package com.packforge.app.domain.model

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifica que los addons deserializados desde un `addonsJson` antiguo (sin los
 * campos List nuevos) quedan con listas no nulas tras `withSafeLists()`.
 *
 * Gson no respeta ni la nulabilidad ni los valores por defecto de Kotlin: un
 * modpack guardado antes de introducir `lootTableIdentifiers` deja ese campo en
 * `null`. Iterarlo producía el `NullPointerException` que cerraba la app al
 * abrir/editar un modpack en "My Modpacks".
 */
class AddonSafeListsTest {

    private val gson = Gson()
    private val listType = object : TypeToken<List<Addon>>() {}.type

    /** JSON de un addon guardado por una versión anterior que no conocía los campos nuevos. */
    private val legacyJson = """
        [
          {
            "id": "abc",
            "name": "AntiLag",
            "fileName": "antilag.mcpack",
            "type": "BEHAVIOR_ONLY",
            "version": "1.0.0",
            "sizeBytes": 2048,
            "enabled": true,
            "priority": 0,
            "hasPlayerJson": false,
            "hasScripts": false,
            "manifestUuid": "uuid-1",
            "rawManifest": "{}",
            "sourceFilePath": "/data/antilag"
          }
        ]
    """.trimIndent()

    @Test
    fun legacyJsonWithMissingListFieldsResolvesToNullLists() {
        val raw: List<Addon> = gson.fromJson(legacyJson, listType)

        // Precondición del bug: Gson no ejecuta el constructor de Kotlin, así que
        // los campos ausentes en el JSON quedan en null (no en emptyList()).
        assertNull("lootTableIdentifiers debería ser null según Gson", raw[0].lootTableIdentifiers)
        assertNull("particleIdentifiers debería ser null según Gson", raw[0].particleIdentifiers)
        assertNull("files debería ser null según Gson", raw[0].files)
        assertNull("minEngineVersion debería ser null según Gson", raw[0].minEngineVersion)
    }

    @Test
    fun withSafeListsFillsMissingListsAndKeepsPresentValues() {
        val raw: List<Addon> = gson.fromJson(legacyJson, listType)
        val safe = raw.withSafeLists()

        val addon = safe[0]
        assertTrue(addon.files.isEmpty())
        assertTrue(addon.behaviorFiles.isEmpty())
        assertTrue(addon.resourceFiles.isEmpty())
        assertTrue(addon.entityIdentifiers.isEmpty())
        assertTrue(addon.itemIdentifiers.isEmpty())
        assertTrue(addon.recipeIdentifiers.isEmpty())
        assertTrue(addon.lootTableIdentifiers.isEmpty())
        assertTrue(addon.particleIdentifiers.isEmpty())
        assertTrue(addon.materialInstanceIdentifiers.isEmpty())
        assertTrue(addon.soundDefinitionIdentifiers.isEmpty())
        assertTrue(addon.animationControllerIdentifiers.isEmpty())
        assertEquals(listOf(1, 20, 0), addon.minEngineVersion)

        // Los campos no-lista no deben alterarse.
        assertEquals("AntiLag", addon.name)
        assertEquals("abc", addon.id)
    }

    @Test
    fun withSafeListsKeepsNonNullPayload() {
        val json = """
            [
              {
                "id": "x", "name": "N", "fileName": "f.mcpack", "type": "BEHAVIOR_ONLY",
                "version": "0.1", "sizeBytes": 1,
                "lootTableIdentifiers": ["loot:t1", "loot:t2"],
                "files": ["a", "b"],
                "minEngineVersion": [1, 21, 0]
              }
            ]
        """.trimIndent()
        val safe = gson.fromJson<List<Addon>>(json, listType).withSafeLists()

        assertEquals(listOf("loot:t1", "loot:t2"), safe[0].lootTableIdentifiers)
        assertEquals(listOf("a", "b"), safe[0].files)
        assertEquals(listOf(1, 21, 0), safe[0].minEngineVersion)
    }

    @Test
    fun conflictEngineIterationDoesNotCrashOnLegacyAddons() {
        // Simula exactamente lo que hace ConflictEngine.detectLootTableConflicts:
        // iterar lootTableIdentifiers y concatenar files/behavior/resource.
        val safe: List<Addon> = gson.fromJson<List<Addon>>(legacyJson, listType).withSafeLists()
        val addon = safe[0]

        val lootIterated = addon.lootTableIdentifiers.size
        val allFiles = addon.files + addon.behaviorFiles + addon.resourceFiles

        assertEquals(0, lootIterated)
        assertTrue(allFiles.isEmpty())
    }
}
