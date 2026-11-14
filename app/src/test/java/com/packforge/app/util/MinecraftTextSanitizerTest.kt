package com.packforge.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MinecraftTextSanitizerTest {

    @Test
    fun testCleanSectionSymbols() {
        assertEquals("Espada Mágica", MinecraftTextSanitizer.clean("§cEspada §bMágica"))
        assertEquals("¡Alerta!", MinecraftTextSanitizer.clean("§6§l¡Alerta!"))
        assertEquals("Código", MinecraftTextSanitizer.clean("§kCódigo"))
        assertEquals("Normal", MinecraftTextSanitizer.clean("§rNormal"))
        assertEquals("Addon Genial", MinecraftTextSanitizer.clean("§sAddon §pGenial"))
        assertEquals("Plain Name", MinecraftTextSanitizer.clean("Plain Name §"))
        assertEquals("", MinecraftTextSanitizer.clean("pack.name"))
        assertEquals("", MinecraftTextSanitizer.clean("§cpack.name"))
        assertEquals("", MinecraftTextSanitizer.clean(null))
    }

    @Test
    fun testIsTranslationKey() {
        assertTrue(MinecraftTextSanitizer.isTranslationKey("pack.name"))
        assertTrue(MinecraftTextSanitizer.isTranslationKey("pack.description"))
        assertTrue(MinecraftTextSanitizer.isTranslationKey("%pack.name"))
        assertTrue(MinecraftTextSanitizer.isTranslationKey("tile.pack.name"))
        assertTrue(MinecraftTextSanitizer.isTranslationKey("pack.name.rp"))

        assertFalse(MinecraftTextSanitizer.isTranslationKey("Super Cool Addon"))
        assertFalse(MinecraftTextSanitizer.isTranslationKey("My Addon v2"))
    }
}
