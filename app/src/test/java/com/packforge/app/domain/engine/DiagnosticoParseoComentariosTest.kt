package com.packforge.app.domain.engine

import org.json.JSONObject
import org.junit.Test

/**
 * DIAGNÓSTICO (temporal) — ¿rechaza `JSONObject` los comentarios `//` en el
 * entorno de tests? Es la premisa de la causa raíz de los 41 materiales. Si
 * `JSONObject` los tolerara, el arreglo de `readJson` sería un no-op y el
 * diagnóstico estaría equivocado. Imprime la evidencia en vez de asumirla.
 */
class DiagnosticoParseoComentariosTest {

    private val conComentario = """
        {
          "materials": {
            "version": "1.0.0",
            "glow:entity_alphablend": {
                // "depthFunc": "Always",
                // ^ Uncomment if you want the outline to be
                "+states": [ "Blending" ]
            }
          }
        }
    """.trimIndent()

    private val conBloque = """
        {
          /* comentario de bloque */
          "materials": { "version": "1.0.0", "a:entity_static": {} }
        }
    """.trimIndent()

    private val conComaFinal = """
        {
          "materials": { "version": "1.0.0", "a:entity_static": { }, },
        }
    """.trimIndent()

    private val limpio = """{ "materials": { "version": "1.0.0", "a:b": {} } }"""

    @Test
    fun `que tolera cada parser`() {
        fun probar(nombre: String, texto: String) {
            val estricto = try {
                val o = JSONObject(texto)
                val n = o.optJSONObject("materials")?.length() ?: -1
                "OK (materials=$n)"
            } catch (e: Exception) {
                "FALLA: ${e.javaClass.simpleName}"
            }
            val laxo = try {
                val o = LenientJson.parseObject(texto)
                val n = o?.optJSONObject("materials")?.length() ?: -1
                "OK (materials=$n)"
            } catch (e: Exception) {
                "FALLA: ${e.javaClass.simpleName}"
            }
            println("PF-DIAG | $nombre")
            println("PF-DIAG |    JSONObject   -> $estricto")
            println("PF-DIAG |    LenientJson  -> $laxo")
        }

        probar("JSON limpio (control)", limpio)
        probar("con comentario // de linea", conComentario)
        probar("con comentario /* */ de bloque", conBloque)
        probar("con coma final", conComaFinal)
        println("PF-DIAG | FIN")
    }
}
