package com.packforge.app.domain.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * ─────────────────────────────────────────────────────────────────────────────
 * LenientJson — parser que tolera los DOS trucos del addon closed-source
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Descubierto analizando "More Body Actions + Pick up & Carry" (v3.4.12). Cada
 * archivo ofuscado apila DOS barreras que `org.json` rechaza en seco:
 *
 *  1. COMENTARIO INICIAL. Un bloque C-style con el texto de aviso, delante del
 *     JSON real. org.json NO admite comentarios C-style y lanza excepcion.
 *
 *  2. CLAVES DUPLICADAS. Cada clave aparece DOS veces: primero un cebo cuyo
 *     valor usa HOMOGRAFOS CIrilicos (U+0435 `е`, U+043E `о`, U+0441 `с`,
 *     U+0440 `р`, U+0430 `а`), y despues la definicion REAL en ASCII puro:
 *
 *         {"format_version": {cebo}, "format_version": "1.16.0",
 *          "minecraft:geometry": {cebo}, "minecraft:geometry": [{real}]}
 *
 *     org.json lanza `Duplicate key` y nunca llega a la definicion buena.
 *
 * El JSON subyacente ES valido y su contenido REAL: solo esta duplicado y
 * comentado. Por eso este parser recorta el comentario inicial y acepta claves
 * duplicadas quedandose con la ULTIMA, que es la autentica.
 *
 * Sin esto el decifrador era ciego a todos estos archivos: clasificaba como
 * ROTAS las fuentes CLARAS, y la geometria real de `carryblock` se perdia.
 *
 * NUNCA inventa contenido: si el texto no es parseable devuelve `null`.
 * ─────────────────────────────────────────────────────────────────────────────
 */
object LenientJson {

    /**
     * Recorta el COMENTARIO INICIAL que el addon anteponga como cebo.
     *
     * Solo se toca lo previo al primer `{`: no se modifica nada dentro del
     * JSON, de modo que no puede romperse un cierre de comentario literal
     * dentro de un string.
     */
    fun stripLeadingComment(text: String): String {
        var t = text.trimStart()
        while (t.startsWith("/*")) {
            val end = t.indexOf("*/")
            if (end < 0) return text
            t = t.substring(end + 2).trimStart()
        }
        return t
    }

    /** Parsea aceptando comentarios iniciales y claves duplicadas. `null` si falla. */
    fun parse(text: String): Any? = try {
        Parser(stripLeadingComment(text)).parseValue()
    } catch (e: Exception) {
        null
    }

    /** Como [parse], pero exigiendo un objeto raiz. `null` si no lo es. */
    fun parseObject(text: String): JSONObject? = parse(text) as? JSONObject

    private class Parser(private val s: String) {

        private var i = 0

        private fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun fail(msg: String): Nothing =
            throw RuntimeException("$msg en posición $i")

        private fun expect(word: String) {
            if (!s.startsWith(word, i)) fail("se esperaba $word")
            i += word.length
        }

        fun parseValue(): Any? {
            ws()
            if (i >= s.length) fail("fin inesperado")
            return when (s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); JSONObject.NULL }
                else -> parseNumber()
            }
        }

        private fun parseObject(): JSONObject {
            val o = JSONObject()
            i++ // '{'
            ws()
            if (i < s.length && s[i] == '}') { i++; return o }
            while (true) {
                ws()
                if (i < s.length && s[i] == '"') {
                    val key = parseString()
                    ws()
                    if (i >= s.length || s[i] != ':') fail("se esperaba ':'")
                    i++
                    val value = parseValue() ?: JSONObject.NULL
                    // Clave duplicada -> SOBRESCRIBE: el addon escribe siempre
                    // el cebo primero y la DEFINICION REAL la ultima.
                    o.put(key, value)
                }
                ws()
                when {
                    i < s.length && s[i] == ',' -> i++
                    i < s.length && s[i] == '}' -> { i++; return o }
                    else -> fail("se esperaba ',' o '}'")
                }
            }
        }

        private fun parseArray(): JSONArray {
            val a = JSONArray()
            i++ // '['
            ws()
            if (i < s.length && s[i] == ']') { i++; return a }
            while (true) {
                a.put(parseValue() ?: JSONObject.NULL)
                ws()
                when {
                    i < s.length && s[i] == ',' -> i++
                    i < s.length && s[i] == ']' -> { i++; return a }
                    else -> fail("se esperaba ',' o ']'")
                }
            }
        }

        private fun parseString(): String {
            if (i >= s.length || s[i] != '"') fail("se esperaba string")
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("string sin cerrar")
                when (val c = s[i++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) fail("escape truncado")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("\\u truncado")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> fail("escape desconocido \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): Double {
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE-+")) i++
            if (i == start) fail("se esperaba número")
            return s.substring(start, i).toDouble()
        }
    }
}
