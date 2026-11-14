package com.packforge.app.domain.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * DIAGNÓSTICO de la pérdida de 59 archivos de geometría de entidad.
 *
 * Aísla el PRIMER eslabón de la cadena: ¿la EXTRACCIÓN escribe esos archivos en
 * disco? Si aquí faltan, la pérdida es temprana; si están todos, se pierden más
 * adelante (copia Eager, fusión o empaquetado).
 *
 * Opt-in: sólo corre con PF_DIAG_ADDON apuntando al .mcaddon.
 */
class DiagnosticoPerdidaModelsEntityTest {

    @Test
    fun `los modelos de entidad llegan a disco tras la extraccion`() {
        val addon = System.getenv("PF_DIAG_ADDON")
        if (addon.isNullOrBlank()) {
            println("DIAG-MODELS | omitido (define PF_DIAG_ADDON)")
            return
        }
        val src = File(addon)
        assertTrue("no existe: $addon", src.isFile)

        val dest = File(System.getProperty("java.io.tmpdir"), "pf_diag_models_${System.nanoTime()}")
            .also { it.mkdirs() }

        try {
            AddonExtractor.extractAddon(src.absolutePath, dest.absolutePath)

            // Contar por carpeta en TODO el árbol extraído.
            val todos = dest.walkTopDown().filter { it.isFile }.toList()
            val modelsEntity = todos.filter {
                it.relativeTo(dest).path.replace('\\', '/').contains("models/entity/") &&
                    it.extension.equals("json", true)
            }
            println("DIAG-MODELS | extraidos totales=${todos.size}")
            println("DIAG-MODELS | models/entity en disco=${modelsEntity.size}")

            // Los que sabemos que se pierden.
            val sospechosos = listOf(
                "08xqz3d1", "0ktpsxzu", "0wv7prc4", "13uwkwwg", "1fg0584n",
                "2kbvdz5e", "h631xvkf", "o0stnnkg"
            )
            sospechosos.forEach { stem ->
                val hit = modelsEntity.filter { it.name == "$stem.json" }
                println("DIAG-MODELS |   $stem.json -> ${if (hit.isEmpty()) "AUSENTE" else hit.joinToString { it.relativeTo(dest).path }}")
            }

            val raices = dest.listFiles()?.map { it.name } ?: emptyList()
            println("DIAG-MODELS | raices extraidas: $raices")

            println("DIAG-MODELS | dir temporal: ${dest.absolutePath}")
        } finally {
            // Se conserva a propósito para inspección manual.
            println("DIAG-MODELS | (no se borra el temporal para poder inspeccionarlo)")
        }
    }
}
