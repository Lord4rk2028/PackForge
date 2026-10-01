package com.packforge.app.data.update

/**
 * Comparador de versiones para Bedrock addons.
 *
 * Los addons usan versiones semánticas simples ("1.2.3", "2.0", "1.0-beta.2"),
 * así que basta con separar por puntos y comparar numéricamente cada segmento,
 * tratando las etiquetas alpha/beta/rc como MENOR que la versión final.
 */
class VersionComparator {

    /**
     * Compara dos versiones.
     * @return negativo si a < b, 0 si iguales, positivo si a > b
     */
    fun compare(a: String, b: String): Int {
        val partsA = parse(a)
        val partsB = parse(b)
        val maxLen = maxOf(partsA.size, partsB.size)

        for (i in 0 until maxLen) {
            val va = partsA.getOrElse(i) { 0 }
            val vb = partsB.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }

    /** true si [remote] es estrictamente más nueva que [local]. */
    fun isNewer(remote: String, local: String): Boolean = compare(remote, local) > 0

    /**
     * Convierte una versión en una lista de enteros comparable.
     * Ej: "1.2.3-beta" → [1, 2, 3, -1] (beta es menor que la final).
     * "1.0.0"         → [1, 0, 0]
     */
    fun parse(version: String): List<Int> {
        // Separar la parte numérica de las etiquetas (alpha, beta, rc, etc.).
        val mainPart = version.substringBefore('-').substringBefore('+').trim()
        val hasPreRelease = version.contains('-')

        val numbers = mainPart
            .split('.')
            .map { segment ->
                segment.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
            }
            .toMutableList()

        // Si hay etiquetas de pre-lanzamiento, restamos 1 al último segmento
        // para que 1.0.0-beta < 1.0.0.
        if (hasPreRelease && numbers.isNotEmpty()) {
            numbers[numbers.lastIndex] = numbers.last() - 1
        }
        return numbers
    }
}
