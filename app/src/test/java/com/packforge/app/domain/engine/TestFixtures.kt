package com.packforge.app.domain.engine

import java.io.File

/**
 * Localizador de los addons reales usados como fixtures por los tests de
 * diagnostico.
 *
 * Los tests apuntaban a una ruta FIJA (`Downloads/<archivo>`). Cuando esa
 * carpeta se reordena o se borra, `assumeTrue` los SALTA EN SILENCIO y las
 * regresiones que protegen dejan de ejecutarse sin que nadie lo note. Paso
 * justo eso: al mover los addons a subcarpetas dejaron de correr los tests del
 * HUD, de los mobs invisibles y del router de scripts.
 *
 * Ahora se busca por NOMBRE en una lista de raices candidatas: primero dentro
 * del repo (`.research/` y sus subcarpetas) y luego `Downloads` con sus
 * subcarpetas, para que reordenar archivos no desactive la proteccion.
 */
object TestFixtures {

    /** Raiz del proyecto, deducida subiendo desde el working dir del test. */
    private val projectRoot: File by lazy {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        generateSequence(cwd) { it.parentFile }
            .firstOrNull { File(it, ".research").isDirectory }
            ?: cwd
    }

    /** Raices donde se busca, en orden de prioridad. */
    val roots: List<File> by lazy {
        val encontradas = mutableListOf<File>()

        fun addTree(root: File?) {
            if (root == null || !root.isDirectory) return
            encontradas += root
            root.listFiles()
                ?.filter { it.isDirectory }
                ?.sortedBy { it.name }
                ?.forEach { encontradas += it }
        }

        addTree(File(projectRoot, ".research"))

        val home = System.getenv("USERPROFILE") ?: System.getenv("HOME").orEmpty()
        if (home.isNotBlank()) addTree(File(home, "Downloads"))

        encontradas.distinct()
    }

    /** Primer archivo cuyo nombre coincide, sin distinguir mayusculas. */
    fun addon(nombre: String): File? {
        val buscado = nombre.lowercase()
        roots.forEach { root ->
            root.listFiles()
                ?.firstOrNull { it.isFile && it.name.lowercase() == buscado }
                ?.let { return it }
        }
        return null
    }

    /**
     * Mensaje para `assumeTrue`: si el fixture aparecio, su ruta; si no, donde
     * se busco. Asi un test que se salta dice POR QUE en vez de desaparecer.
     */
    fun describe(nombre: String): String =
        addon(nombre)?.let { "fixture '$nombre' -> ${it.absolutePath}" }
            ?: ("fixture '$nombre' NO encontrado. Raices probadas: " +
                roots.joinToString(", ") { it.path })
}