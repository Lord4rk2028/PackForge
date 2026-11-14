package com.packforge.app.domain.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PlayerEntityHybridMergeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testMergeBedrockEvents_StacksCollidingEventsIntoSequence() {
        val eventA = JSONObject().apply {
            put("add", JSONObject().apply {
                put("component_groups", JSONArray(listOf("addon_a:magic_boost")))
            })
        }
        val eventB = JSONObject().apply {
            put("add", JSONObject().apply {
                put("component_groups", JSONArray(listOf("addon_b:stamina_drain")))
            })
        }

        val baseEvents = JSONObject().apply {
            put("minecraft:entity_spawned", eventA)
        }
        val incomingEvents = JSONObject().apply {
            put("minecraft:entity_spawned", eventB)
            put("addon_b:custom_event", JSONObject().apply {
                put("remove", JSONObject().apply {
                    put("component_groups", JSONArray(listOf("addon_b:temp")))
                })
            })
        }

        val mergedEvents = JsonDeepMerger.mergeBedrockEvents(baseEvents, incomingEvents)

        assertTrue(mergedEvents.has("minecraft:entity_spawned"))
        assertTrue(mergedEvents.has("addon_b:custom_event"))

        val spawnedObj = mergedEvents.getJSONObject("minecraft:entity_spawned")
        assertTrue(spawnedObj.has("sequence"))

        val sequenceArray = spawnedObj.getJSONArray("sequence")
        assertEquals(2, sequenceArray.length())

        val firstAdd = sequenceArray.getJSONObject(0).getJSONObject("add").getJSONArray("component_groups")
        val secondAdd = sequenceArray.getJSONObject(1).getJSONObject("add").getJSONArray("component_groups")

        assertEquals("addon_a:magic_boost", firstAdd.getString(0))
        assertEquals("addon_b:stamina_drain", secondAdd.getString(0))
    }

    @Test
    fun testMergePlayerEntityObjects_HybridStacking() {
        val playerA = JSONObject().apply {
            put("format_version", "1.20.50")
            put("minecraft:entity", JSONObject().apply {
                put("description", JSONObject().apply {
                    put("identifier", "minecraft:player")
                    put("scripts", JSONObject().apply {
                        put("animate", JSONArray(listOf("magic_anim")))
                    })
                })
                put("component_groups", JSONObject().apply {
                    put("addon_a:magic_group", JSONObject().apply {
                        put("minecraft:health", JSONObject().apply { put("value", 30) })
                    })
                })
                put("events", JSONObject().apply {
                    put("minecraft:entity_spawned", JSONObject().apply {
                        put("add", JSONObject().apply {
                            put("component_groups", JSONArray(listOf("addon_a:magic_group")))
                        })
                    })
                })
            })
        }

        val playerB = JSONObject().apply {
            put("format_version", "1.20.50")
            put("minecraft:entity", JSONObject().apply {
                put("description", JSONObject().apply {
                    put("identifier", "minecraft:player")
                    put("scripts", JSONObject().apply {
                        put("animate", JSONArray(listOf("backpack_anim")))
                    })
                })
                put("component_groups", JSONObject().apply {
                    put("addon_b:backpack_group", JSONObject().apply {
                        put("minecraft:inventory", JSONObject().apply { put("inventory_size", 36) })
                    })
                })
                put("events", JSONObject().apply {
                    put("minecraft:entity_spawned", JSONObject().apply {
                        put("add", JSONObject().apply {
                            put("component_groups", JSONArray(listOf("addon_b:backpack_group")))
                        })
                    })
                })
            })
        }

        val merged = BedrockCriticalFilesMerger.mergePlayerEntityObjects(playerA, playerB, "AddonB")

        val entity = merged.getJSONObject("minecraft:entity")
        val componentGroups = entity.getJSONObject("component_groups")
        assertTrue(componentGroups.has("addon_a:magic_group"))
        assertTrue(componentGroups.has("addon_b:backpack_group"))

        val animates = entity.getJSONObject("description").getJSONObject("scripts").getJSONArray("animate")
        assertEquals(2, animates.length())
        assertTrue(animates.getString(0) == "magic_anim" || animates.getString(1) == "magic_anim")
        assertTrue(animates.getString(0) == "backpack_anim" || animates.getString(1) == "backpack_anim")

        val spawnedEvent = entity.getJSONObject("events").getJSONObject("minecraft:entity_spawned")
        assertTrue(spawnedEvent.has("sequence"))
        assertEquals(2, spawnedEvent.getJSONArray("sequence").length())
    }

    @Test
    fun testMergePlayerEntity_FilesystemIntegration() {
        val bpDir1 = tempFolder.newFolder("addon1_bp")
        val bpDir2 = tempFolder.newFolder("addon2_bp")
        val destBpDir = tempFolder.newFolder("merged_bp")

        val playerFile1 = File(bpDir1, "player.entity.json")
        val playerFile2 = File(bpDir2, "player.entity.json")

        playerFile1.writeText("""
            {
              "format_version": "1.20.50",
              "minecraft:entity": {
                "description": { "identifier": "minecraft:player" },
                "component_groups": { "addon1:group": {} },
                "events": {
                  "minecraft:entity_spawned": {
                    "add": { "component_groups": ["addon1:group"] }
                  }
                }
              }
            }
        """.trimIndent())

        playerFile2.writeText("""
            {
              "format_version": "1.20.50",
              "minecraft:entity": {
                "description": { "identifier": "minecraft:player" },
                "component_groups": { "addon2:group": {} },
                "events": {
                  "minecraft:entity_spawned": {
                    "add": { "component_groups": ["addon2:group"] }
                  }
                }
              }
            }
        """.trimIndent())

        BedrockCriticalFilesMerger.mergePlayerEntity(listOf(bpDir1, bpDir2), destBpDir)

        val destFile = File(destBpDir, "player.entity.json")
        assertTrue(destFile.exists())

        val resultJson = JSONObject(destFile.readText())
        val entity = resultJson.getJSONObject("minecraft:entity")

        val groups = entity.getJSONObject("component_groups")
        assertTrue(groups.has("addon1:group"))
        assertTrue(groups.has("addon2:group"))

        val spawned = entity.getJSONObject("events").getJSONObject("minecraft:entity_spawned")
        assertTrue(spawned.has("sequence"))
        assertEquals(2, spawned.getJSONArray("sequence").length())
    }
}
