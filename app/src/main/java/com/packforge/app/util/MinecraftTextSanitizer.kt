package com.packforge.app.util

object MinecraftTextSanitizer {
    /**
     * Regex que reconoce cualquier código de formato o color de Minecraft (§ seguido de cualquier carácter)
     */
    private val MINECRAFT_FORMAT_REGEX = Regex("§.")

    /**
     * Limpia los símbolos § y sus códigos de formato/color para dejar texto plano legible.
     * Si el resultado final sigue siendo una clave de traducción interna (ej. "pack.name"),
     * devuelve cadena vacía "" para forzar el uso de fallbacks con nombres reales.
     * Ejemplo: "§6§l¡Alerta!" -> "¡Alerta!"
     *          "§cEspada §bMágica" -> "Espada Mágica"
     *          "pack.name" -> ""
     */
    fun clean(text: String?): String {
        if (text.isNullOrBlank()) return ""
        val cleaned = text.replace(MINECRAFT_FORMAT_REGEX, "")
            .replace("§", "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (isTranslationKey(cleaned)) return ""
        return cleaned
    }

    /**
     * Determina si un texto representa una clave de traducción interna de Minecraft
     * (p. ej. "pack.name") en lugar de un nombre legible.
     */
    fun isTranslationKey(text: String?): Boolean {
        if (text.isNullOrBlank()) return true
        val trimmed = text.trim()
        val lower = trimmed.lowercase()
        return lower == "pack.name" ||
                lower == "pack.description" ||
                lower == "pack.name.rp" ||
                lower == "pack.name.bp" ||
                lower == "tile.pack.name" ||
                trimmed.startsWith("%") ||
                trimmed.startsWith("pack.", ignoreCase = true) ||
                trimmed.startsWith("tile.", ignoreCase = true) ||
                (!trimmed.contains(" ") && (lower.endsWith(".name") || lower.endsWith(".description")))
    }
}
