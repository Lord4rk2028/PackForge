package com.packforge.app.domain.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonDeepMergerTest {

    @Test
    fun testDeepMerge_ObjectsRecursive() {
        // Arrange
        val base = JSONObject().apply {
            put("format_version", 2)
            put("minecraft:item", JSONObject().apply {
                put("description", JSONObject().apply {
                    put("identifier", "minecraft:apple")
                })
                put("components", JSONObject().apply {
                    put("minecraft:max_stack_size", 64)
                })
            })
        }

        val toMerge = JSONObject().apply {
            put("minecraft:item", JSONObject().apply {
                put("components", JSONObject().apply {
                    put("minecraft:food", JSONObject().apply {
                        put("nutrition", 4)
                    })
                })
            })
        }

        // Act
        val result = JsonDeepMerger.deepMerge(base, toMerge)

        // Assert
        assertEquals(2, result.getInt("format_version"))
        
        val item = result.getJSONObject("minecraft:item")
        assertEquals("minecraft:apple", item.getJSONObject("description").getString("identifier"))
        
        val components = item.getJSONObject("components")
        assertEquals(64, components.getInt("minecraft:max_stack_size"))
        assertTrue(components.has("minecraft:food"))
        assertEquals(4, components.getJSONObject("minecraft:food").getInt("nutrition"))
    }

    @Test
    fun testDeepMerge_ArraysConcatenate() {
        // Arrange
        val base = JSONObject().apply {
            put("textures", JSONArray(listOf("diamond_sword", "iron_sword")))
        }

        val toMerge = JSONObject().apply {
            put("textures", JSONArray(listOf("gold_sword")))
        }

        // Act
        val result = JsonDeepMerger.deepMerge(base, toMerge)

        // Assert
        val textures = result.getJSONArray("textures")
        assertEquals(3, textures.length())
        assertEquals("diamond_sword", textures.getString(0))
        assertEquals("iron_sword", textures.getString(1))
        assertEquals("gold_sword", textures.getString(2))
    }

    @Test
    fun testDeepMerge_PrimitiveCollision_Overwrites() {
        // Arrange
        val base = JSONObject().apply {
            put("format_version", 1)
            put("value", "old_value")
        }

        val toMerge = JSONObject().apply {
            put("format_version", 2)
            put("value", "new_value")
        }

        // Act
        val result = JsonDeepMerger.deepMerge(base, toMerge)

        // Assert
        assertEquals(2, result.getInt("format_version"))
        assertEquals("new_value", result.getString("value"))
    }

    @Test
    fun testDeepMerge_NamespaceCollision() {
        // Arrange
        val base = JSONObject().apply {
            put("my_mod:custom_sword", JSONObject().apply {
                put("damage", 5)
            })
        }

        val toMerge = JSONObject().apply {
            put("my_mod:custom_sword", JSONObject().apply {
                put("damage", 10)
            })
        }

        // Act
        JsonDeepMerger.checkNamespaceCollision("my_mod:custom_sword")
        val result = JsonDeepMerger.deepMerge(base, toMerge)

        // Assert - el segundo sobrescribe al primero
        assertEquals(10, result.getJSONObject("my_mod:custom_sword").getInt("damage"))
    }

    @Test
    fun testDeepMergeStrings() {
        // Arrange
        val baseJson = """{"format_version": 2, "data": {"a": 1}}"""
        val mergeJson = """{"data": {"b": 2}}"""

        // Act
        val result = JsonDeepMerger.deepMergeStrings(baseJson, mergeJson)

        // Assert
        val resultObj = JSONObject(result)
        assertEquals(2, resultObj.getInt("format_version"))
        assertEquals(1, resultObj.getJSONObject("data").getInt("a"))
        assertEquals(2, resultObj.getJSONObject("data").getInt("b"))
    }

    @Test
    fun testDeepMerge_ComplexScenario() {
        // Arrange - caso real de Minecraft Bedrock
        val base = JSONObject().apply {
            put("format_version", "1.16.0")
            put("minecraft:item", JSONObject().apply {
                put("description", JSONObject().apply {
                    put("identifier", "mod:custom_item")
                })
                put("components", JSONObject().apply {
                    put("minecraft:max_stack_size", 64)
                    put("minecraft:creative_category", JSONObject().apply {
                        put("parent", "itemGroup.name.sword")
                    })
                })
            })
        }

        val toMerge = JSONObject().apply {
            put("minecraft:item", JSONObject().apply {
                put("components", JSONObject().apply {
                    put("minecraft:durability", JSONObject().apply {
                        put("max_durability", 500)
                    })
                    put("minecraft:creative_category", JSONObject().apply {
                        put("parent", "itemGroup.name.tools")
                    })
                })
            })
        }

        // Act
        val result = JsonDeepMerger.deepMerge(base, toMerge)

        // Assert
        assertEquals("1.16.0", result.getString("format_version"))
        
        val item = result.getJSONObject("minecraft:item")
        assertEquals("mod:custom_item", item.getJSONObject("description").getString("identifier"))
        
        val components = item.getJSONObject("components")
        assertEquals(64, components.getInt("minecraft:max_stack_size"))
        assertEquals(500, components.getJSONObject("minecraft:durability").getInt("max_durability"))
        assertEquals("itemGroup.name.tools", components.getJSONObject("minecraft:creative_category").getString("parent"))
    }
}
