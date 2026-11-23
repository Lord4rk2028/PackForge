package com.packforge.app.domain.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import org.json.JSONTokener
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import com.packforge.app.domain.model.ConflictSeverity
import com.packforge.app.domain.model.MergeConflict

/**
 * ═══════════════════════════════════════════════════════════════════════
 * GENERADOR DE REPORTE DE FUSIÓN (Validación Post-Fusión)
 * ═══════════════════════════════════════════════════════════════════════
 *
 * 1. validateJsonSyntax: recorre TODO el pack fusionado parseando cada .json;
 *    cualquier archivo inválido (coma faltante, tipo incorrecto tras un
 *    reemplazo) se reporta con ruta y mensaje legible.
 * 2. build/writeToFile: consolida renombres de IDs, aliases de recursos,
 *    renombres de sonido, hallazgos de scripts y validación en
 *    `fusion_report.txt` junto al .mcaddon final.
 */
object MergeReportGenerator {

    private const val TAG = "PackForge_Report"

/**
     * ══════════════════════════════════════════════════════════════════════
     * TICKET-CORE-003 PARTE 4 — INTEGRIDAD DE ARCHIVOS JSON
     * ══════════════════════════════════════════════════════════════════════
     *
     * Detecta dos fallos que ningún validador reportaba:
     *
     *  (a) ARCHIVO_NO_JSON: texto plano (p.ej. el aviso "closed-source" de un
     *      addon) ocupando una ruta que el juego SÍ leerá como JSON. Bedrock
     *      descarta el archivo en silencio y el usuario solo ve un mob sin
     *      modelo, sin saber por qué.
     *
     *  (b) CLAVES_CIFRADAS: algunos creadores ofuscan las claves carácter a
     *      carácter conservando la longitud ("format_version" (14) →
     *      "_rovmofeatrsin" (14)). El archivo sigue siendo JSON válido, así que
     *      ningún parser falla, pero Bedrock no reconoce ninguna clave y lo ignora.
     *
     * Severidad adaptativa (regla transversal: NUNCA bloquea la exportación):
     *  - Si existe una contraparte VÁLIDA con el mismo identificador, el archivo
     *    corrupto es una SOMBRA que no se usa: se informa como INFO, sin ruido.
     *  - Si está en ruta canónica y no hay contraparte, es un problema real: HIGH.
     */
    data class IntegrityFinding(
        val relativePath: String,
        val kind: String,
        val detail: String,
        val severity: Severity
    ) {
        enum class Severity { INFO, LOW, HIGH }
    }

    /** Claves raíz canónicas de Bedrock. Si no hay NINGUNA, el JSON está cifrado. */
    private val CANONICAL_KEYS = setOf(
        "format_version", "minecraft:client_entity", "minecraft:geometry",
        "minecraft:animation", "minecraft:controller_animation",
        "minecraft:render_controller", "minecraft:entity", "minecraft:block",
        "minecraft:item", "minecraft:particle_effect", "minecraft:attachable",
        "description", "identifier", "animations", "animation_controllers",
        "render_controllers", "particles", "sound_effects", "geometry",
        "textures", "materials", "scripts", "entity_sounds", "components",
        "bones", "controller_animation", "sound_definitions",
        "sound_events", "content_log", "terrain_texture", "flipbook_textures",
        "texture_data", "ambient_sounds", "particle_effect", "block",
        "items", "recipe", "ui", "light_emission",
        // Legítimas que faltaban y se marcaban como cifradas por error:
        "languages", "language", "sounds", "sound_list", "loot_tables",
        "recipes", "ticking_blocks", "disabled_features",
        // ⭐ FASE 5: familias que producían ~860 avisos FALSOS de
        // CLAVES_CIFRADAS. El reporte culpaba al addon de "ofuscar" archivos
        // perfectamente válidos, y el usuario acababa persiguiendo fantasmas.
        //   · `pools`        → tablas de loot (loot_tables/*.json)
        //   · `namespace` / `nineslice_size` → UI (ui/*.json)
        //   · el resto       → worldgen, atmósfera, partículas y catálogos.
        "pools", "namespace", "nineslice_size", "block_culling", "fogs",
        "atmospherics", "dimensions", "dimension", "world_generation_rules",
        "particle_effects", "particle", "texture_list", "textures_list",
        "color_grading", "feature_rules", "spawn_rules", "item_catalog",
        "trading", "biomes", "features", "blocks", "materials", "client_entity",
        // ⭐ FASE 5: claves raíz legítimas que faltaban y producían falsos
        // "cifrado por sustitución" en archivos perfectamente válidos:
        //  · `values`                    → functions/tick.json
        //  · `ui_defs`                   → ui/_ui_defs.json
        //  · individual/block/interactive_sounds → sounds.json
        "values", "ui_defs", "individual_event_sounds", "block_sounds",
        "interactive_sounds"
    )

    /**
     * ⭐ FASE 5: prefijos canónicos.
     *
     * Hay familias enteras de claves que son legítimas por CONSTRUCCIÓN y que
     * una lista cerrada nunca cubrirá: `minecraft:variant`, `geometry.cc.x`,
     * `animation.cc.walk`, `controller.animation.cc.idle`… Antes cada nombre
     * nuevo parecía "clave cifrada". Con el prefijo, la regla es estable.
     */
    private val CANONICAL_KEY_PREFIXES = listOf(
        "minecraft:", "geometry.", "animation.", "controller."
    )

    /** Carpetas donde Bedrock espera JSON con claves reconocibles. */
    private val CANONICAL_DIRS = listOf(
        "entity/", "entities/", "animations/", "animation_controllers/",
        "render_controllers/", "models/entity/", "models/blocks/",
        "attachables/", "particles/", "blocks/", "items/"
    )

    /**
 * Marcadores de "decoy de cierre" de addons closed-source.
  *
   * Se buscan en las primeras líneas NO LANZANDO el marcador con
     * `startsWith`: los archivos reales empiezan con un comentario de bloque seguido
     * de "Hi. This addon is closed-source...", asi que el prefijo esta tras un
     * salto de linea. Un `startsWith` estricto marcaba 56 trampas como HIGH.
    */
    private val PLAIN_TEXT_MARKERS = listOf(
        "this addon is closed-source",
  "closed-source",
        "you are not allowed to read",
        "do you understand?",
        "this file is auto-generated",
   "do not edit this file"
    )

    /** Cuántos caracteres iniciales se inspeccionan para detectar el decoy. */
    private const val DECOY_SCAN_CHARS = 400

    /**
     * ⭐ FASE 5: tope de hallazgos informativos detallados en el reporte.
     * Los HIGH nunca se recortan; este límite solo aplica a LOW/INFO.
     */
    private const val MAX_INTEGRITY_DETAIL = 60

    /**
   * ¿El archivo es un decoy conocido de un addon closed-source?
  * Bedrock lo ignora por completo, así que es LOW y nunca HIGH.
     */
    private fun isKnownDecoy(head: String): Boolean {
  val lower = head.lowercase()
  return PLAIN_TEXT_MARKERS.any { lower.contains(it) }
    }

    /**
     * ¿La ruta tiene un segmento repetido (p.ej. "entities/entities/…")?
  *
     * Es la firma del relleno anti-análisis de los addons closed-source: el
     * creador duplica carpetas para desorientar a quien inspeccione el pack.
     * Bedrock nunca lee un archivo en una ruta así, así que NO es un problema
     * real del pack fusionado: se informa como LOW aunque esté en carpeta canónica.
  */
    private fun hasRepeatedSegment(relPath: String): Boolean {
        val segments = relPath.split('/').filter { it.isNotEmpty() }
        if (segments.size < 3) return false
        return segments.indices.any { i ->
            val seg = segments[i]
            seg.length >= 3 && segments.drop(i + 1).any { it == seg }
        }
    }

    /**
     * ¿El archivo está en una ruta que Bedrock lee REALMENTE como contenido?
  * Una ruta canónica anidada (entities/entities/x.json) no lo es.
     */
    private fun isBedrockReadPath(rel: String): Boolean {
  val inCanonical = CANONICAL_DIRS.any { rel.startsWith(it) }
        if (!inCanonical) return false
        // Ruta con segmentos repetidos: relleno anti-analisis, no la lee nadie.
        if (hasRepeatedSegment(rel)) return false
        // Nombre oculto (.66.json, .Ei.json): es la firma de las copias
        // ENTERRADAS del esquema closed-source. Bedrock no expone un asset con
        // nombre de punto, asi que no lo lee. Mismo criterio que usa
        // BedrockDecifrator.isBuriedPath (isHiddenName): una sola definicion de
        // "enterrado" para todo el motor, no dos que se contradigan.
        val name = rel.substringAfterLast('/')
        return !name.startsWith(".")
    }

    /** ¿El texto puede ser JSON? Detecta texto plano (comentario, prosa). */
    private fun looksLikeJsonAtAll(trimmed: String): Boolean {
        if (trimmed.isEmpty()) return false
        // ⭐ FASE 5: un comentario inicial NO significa "texto plano".
        //
        // CoreCraft abre item_texture.json con `// cooking` y el archivo es un
        // JSON perfectamente válido. Antes, cualquier cosa que empezara por un
        // comentario se declaraba ARCHIVO_NO_JSON (texto plano) y, si la ruta era
        // canónica, HIGH: medio pack marcado como roto por un comentario.
        // Se recorta el comentario y se decide sobre lo que hay debajo.
        val effective = lenientForTokener(trimmed)
        if (effective.isEmpty()) return false
        val body = effective.trimStart()
        val first = body.firstOrNull() ?: return false
        if (first == '{' || first == '[' || first == '"' ||
            first == '-' || first.isDigit()
        ) return true
        // Literales JSON válidos como raíz. Se comprueba la palabra COMPLETA:
        // un `first == 't'` a secas daba por JSON cualquier prosa que empezara
        // por "t" ("texto plano sin json"), y esos archivos quedaban invisibles
        // para el auditor en vez de reportarse como texto plano.
        return body.startsWith("true") || body.startsWith("false") || body.startsWith("null")
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ANÁLISIS DE UN FICHERO, UNA SOLA VEZ (TICKET-CORE-013)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Resultado de leer y parsear UN archivo, reutilizado por todas las
     * comprobaciones de diagnóstico.
     *
     * Antes cada comprobación volvía a parsear el mismo texto: la validación de
     * sintaxis lo hacía por su cuenta y la auditoría de integridad hasta cinco
     * veces (dos para la validez estructural, dos para la clave canónica y una
     * estricta para el identificador). Con miles de JSON eso domina el tramo
     * final de la exportación, que es puro diagnóstico.
     */
    private class AnalisisArchivo(
        /** Ruta relativa normalizada, con `/`. */
        val rel: String,
        /** Texto del archivo, o null si no se pudo leer. */
        val text: String?,
        /** Mensaje de error de lectura, si lo hubo. */
        val lecturaError: String?,
        /** ¿Se pudo parsear con el parser tolerante a comentarios? */
        val estructuralmenteValido: Boolean,
        /** ¿Tiene alguna clave raíz que Bedrock reconozca? */
        val tieneClaveCanonica: Boolean,
        /** `description.identifier`; solo si válido y canónico, como antes. */
        val identificador: String?
    )

    /**
     * Lee y parsea todos los `.json` del pack UNA sola vez, en el orden estable
     * de `DirIndexCache`, para que los hallazgos conserven su orden.
     */
    private fun analizarPack(root: File): List<AnalisisArchivo> {
        if (!root.isDirectory) return emptyList()
        val salida = ArrayList<AnalisisArchivo>()
        for (file in DirIndexCache.index(root).jsonFiles) {
            val rel = file.relativeTo(root).path.replace('\\', '/')
            val text = try {
                file.readText(StandardCharsets.UTF_8)
            } catch (e: Exception) {
                // Un fallo de E/S NO puede desaparecer: antes producía un
                // «JSON ilegible» distinto de «JSON inválido».
                salida += AnalisisArchivo(
                    rel = rel,
                    text = null,
                    lecturaError = e.message ?: "E/S",
                    estructuralmenteValido = false,
                    tieneClaveCanonica = false,
                    identificador = null
                )
                continue
            }
            // Un único parseo tolerante a comentarios: es el MISMO parser que
            // usaban la comprobación estructural y la de clave canónica.
            val raiz: Any? = try {
                org.json.JSONTokener(lenientForTokener(text)).nextValue()
            } catch (_: Exception) {
                null
            }
            val objeto = raiz as? JSONObject
            val canonica = when {
                raiz is JSONArray -> true
                objeto != null -> tieneClaveCanonicaDeBedrock(rel, objeto)
                else -> false
            }
            salida += AnalisisArchivo(
                rel = rel,
                text = text,
                lecturaError = null,
                estructuralmenteValido = raiz != null,
                tieneClaveCanonica = canonica,
                // Condición EXACTA de antes: el identificador se busca solo en
                // archivos válidos y canónicos, y con el parser ESTRICTO, para
                // que los hallazgos salgan idénticos.
                identificador = if (raiz != null && canonica) extractIdentifierSafely(text) else null
            )
        }
        return salida
    }

    /**
     * ¿El objeto raíz declara algo que Bedrock reconozca?
     *
     * ⭐ PASO 5 — Además del catálogo de claves, se aceptan los ficheros de `ui/`
     * cuyas claves raíz son NOMBRES DE ELEMENTO.
     *
     * Motivo (medido sobre `Desse-UI` + `Chroma Shader`): de 190 ficheros bajo
     * `ui/`, 113 usan `namespace`, 49 usan `modifications` y **31 son overrides
     * de elemento sin `namespace`**, del estilo
     * `{"blast_furnace_screen": {"$is_blast_furnace_screen": true}}` o
     * `{"right_navigation_tabs": {"$show_help_button": false}}`.
     *
     * El auditor los marcaba todos como «cifrado por sustitución» y el informe
     * afirmaba **77 veces** que «el juego ignorará este archivo»: una afirmación
     * sobre el comportamiento del juego que no habíamos verificado y que además
     * contradecía al Decifrador, que en el MISMO informe decía «no se detectó
     * contenido closed-source que recuperar».
     *
     * En `ui/` la raíz ES el mapa de elementos: Bedrock no espera ahí
     * `format_version` ni `minecraft:*`. Por eso, para esa carpeta, se acepta un
     * objeto no vacío cuyas claves tengan forma de elemento. Se exige que TODAS
     * lo parezcan, así que un archivo con basura mezclada se sigue reportando.
     */
    private fun tieneClaveCanonicaDeBedrock(rel: String, root: JSONObject): Boolean {
        val claves = root.keys().asSequence().toList()
        val canonica = claves.any { clave ->
            CANONICAL_KEYS.contains(clave) ||
                CANONICAL_KEY_PREFIXES.any { prefijo -> clave.startsWith(prefijo) }
        }
        if (canonica) return true
        if (!esRutaDeUi(rel) || claves.isEmpty()) return false
        return claves.all { PATRON_ELEMENTO_UI.matches(it) }
    }

    /** ¿La ruta está bajo una carpeta `ui/` y no es relleno con segmentos repetidos? */
    private fun esRutaDeUi(rel: String): Boolean {
        if (hasRepeatedSegment(rel)) return false
        return rel.split('/').any { it.equals("ui", ignoreCase = true) }
    }

    /**
     * Forma de un nombre de elemento de UI: `blast_furnace_screen`,
     * `right_navigation_tabs` o una ruta de modificación como
     * `beacon_panel/root_panel/beacon_inventory`.
     *
     * Deliberadamente estricto (sin espacios ni `=`, `+`, `%`) para no absolver
     * a un archivo cifrado de verdad.
     */
    private val PATRON_ELEMENTO_UI = Regex("""[A-Za-z0-9_$][A-Za-z0-9_$./:@\-]*""")

    /**
     * ⭐ FASE 5: el auditor también debe tolerar comentarios de línea y de bloque.
     *
     * Bedrock acepta comentarios en sus JSON, así que marcarlos como "JSON
     * inválido" o "claves cifradas" es un falso positivo que ensucia el reporte
     * y esconde los fallos reales.
     *
     * OJO: no escribir la secuencia de apertura de bloque literal en este KDoc.
     * Kotlin anida los comentarios de bloque, así que mencionarla aquí abre un
     * comentario interno y deja el archivo entero sin cerrar (Syntax error:
     * Unclosed comment al final del fichero).
     */
    private fun lenientForTokener(text: String): String = try {
        LenientJson.stripLeadingComment(text)
    } catch (_: Exception) {
        text
    }

    /**
     * ⭐ TICKET-CORE-013 — Diagnóstico completo de un pack en UNA sola pasada.
     *
     * El orquestador pedía sintaxis e integridad por separado sobre el MISMO
     * pack, así que cada archivo se leía y se parseaba dos veces completas. Aquí
     * las dos comprobaciones comparten el mismo análisis.
     */
    data class PackAudit(
        val syntaxErrors: List<String>,
        val integrityFindings: List<IntegrityFinding>
    )

    fun auditPack(root: File): PackAudit {
        val analisis = analizarPack(root)
        return PackAudit(
            syntaxErrors = erroresDeSintaxis(analisis),
            integrityFindings = auditarIntegridad(analisis)
        )
    }

    /** Extrae description.identifier sin lanzar excepciones. */
    private fun extractIdentifierSafely(text: String): String? {
        return try {
            val json = JSONObject(text)
            val id = json.optJSONObject("minecraft:client_entity")
                ?.optJSONObject("description")?.optString("identifier")
                ?: json.optJSONObject("description")?.optString("identifier")
            id?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Analiza la integridad de todos los .json del pack fusionado.
     *
     * Zero-Excess I/O: un solo `readText()` por archivo, reutilizado en todas las
     * comprobaciones. Nada de walkTopDown(): se usa DirIndexCache.
     *
     * @return lista de hallazgos (vacía = todo correcto).
     */
    fun auditJsonIntegrity(root: File): List<IntegrityFinding> =
        auditarIntegridad(analizarPack(root))

    /** Cuerpo de la auditoría, sobre un análisis ya hecho (evita reparsear). */
    private fun auditarIntegridad(analisis: List<AnalisisArchivo>): List<IntegrityFinding> {
        val findings = mutableListOf<IntegrityFinding>()

        // 1a pasada: identificadores VALIDOS, para saber que archivos corruptos
        // son sombras de una contraparte sana (INFO).
        val validIds = HashSet<String>()
        for (a in analisis) {
            if (!a.estructuralmenteValido || !a.tieneClaveCanonica) continue
            a.identificador?.let { validIds += it }
        }

        // 2a pasada: auditoria real, sobre el análisis ya hecho.
        for (a in analisis) {
            val rel = a.rel
            val text = a.text ?: continue
      val trimmed = text.trimStart('\uFEFF').trim()
      val head = text.take(DECOY_SCAN_CHARS)

     // (a) Texto plano en una ruta JSON.
    if (!looksLikeJsonAtAll(trimmed)) {
      val decoy = isKnownDecoy(head)
     val filler = hasRepeatedSegment(rel)
       val readByBedrock = isBedrockReadPath(rel)
    findings += IntegrityFinding(
      rel, "ARCHIVO_NO_JSON",
            buildString {
                append("Contiene texto plano en una ruta .json")
                if (filler) append(" - carpetas repetidas (relleno anti-analisis): Bedrock no lee esta ruta")
                if (readByBedrock) {
                    append(" - RUTA CANONICA que Bedrock SI lee: el juego fallara al parsearla")
                } else if (!filler) {
                    append(" - ruta no canonica: Bedrock no la lee")
                }
                if (decoy) append(" (texto de cierre closed-source)")
            },
            when {
                // ORDEN CORREGIDO (bug real, visible en el reporte de Test 1):
                // ANTES `decoy || filler` se evaluaba PRIMERO, asi que un decoy
                // en una ruta canonica (p.ej. animation_controllers/player.json)
                // se clasificaba LOW y el reporte decia "Bedrock lo ignora" /
                // "no requieren accion". Es FALSO: el juego SI lee esa ruta,
                // intenta parsear el texto plano y registra el error.
                //
                // En el reporte de Test 1 habia 51 hallazgos LOW de este tipo,
                // y ~26 estaban en rutas canonicas REALES. Por eso el reporte
                // declaraba "FUSION COMPLETADA SIN ERRORES CRITICOS" mientras
                // medio pack tenia archivos ilegibles.
                //
                // `filler` si es inofensivo: una ruta con segmentos repetidos
                // (entities/entities/...) jamas la lee el motor, asi que sigue
                // siendo LOW aunque traiga el texto de cierre.
                filler -> IntegrityFinding.Severity.LOW
                readByBedrock -> IntegrityFinding.Severity.HIGH
                else -> IntegrityFinding.Severity.LOW
            }
    )
      continue
     }

            // (b) JSON valido en estructura pero sin ninguna clave canonica.
            //
            // Un objeto/array VACÍO (`{}`, `[]`) no está cifrado: no hay nada que
            // cifrar. Antes se marcaba como "claves cifradas" y el reporte acusaba
            // al addon de ofuscar un `sounds.json` de 2 bytes perfectamente válido.
            val vacio = text.trim().let { it == "{}" || it == "[]" || it.isEmpty() }
            if (!vacio && a.estructuralmenteValido && !a.tieneClaveCanonica) {
           val id = extractIdentifierSafely(text)
          val hasValidTwin = id != null && validIds.contains(id)
       val readByBedrock = isBedrockReadPath(rel)
        findings += IntegrityFinding(
      rel,
          if (hasValidTwin) "SOMBRA_NO_UTILIZADA" else "CLAVES_CIFRADAS",
if (hasValidTwin) {
      "Claves cifradas pero existe una copia valida con el mismo id: Bedrock usara la valida."
          } else {
  "Ninguna clave raiz reconocida por Bedrock (cifrado por sustitucion). " +
      "El juego ignorara este archivo."
    },
          when {
       hasValidTwin -> IntegrityFinding.Severity.INFO
      readByBedrock -> IntegrityFinding.Severity.HIGH
    else -> IntegrityFinding.Severity.LOW
         }
           )
     }
        }

        if (findings.isNotEmpty()) {
            com.packforge.app.util.PackForgeLog.w(TAG, "Integridad JSON: ${findings.size} hallazgo(s)")
        }
        return findings
    }

    /**
     * Resumen para el reporte: una línea legible por hallazgo.
     *
     * ⭐ FASE 5: los HIGH se listan SIEMPRE completos (requieren acción), pero
     * el resto se recorta a [MAX_INTEGRITY_DETAIL] líneas. Antes volcaba cientos
     * de avisos idénticos y el reporte dejaba de ser legible.
     */
    fun formatIntegrityFindings(findings: List<IntegrityFinding>): List<String> {
        if (findings.isEmpty()) return emptyList()
        val icon = mapOf(
            IntegrityFinding.Severity.INFO to "ℹ️",
            IntegrityFinding.Severity.LOW to "⚠️",
            IntegrityFinding.Severity.HIGH to "❌"
        )
        val high = findings.filter { it.severity == IntegrityFinding.Severity.HIGH }
        val rest = findings.filter { it.severity != IntegrityFinding.Severity.HIGH }
        val shown = high + rest.take(MAX_INTEGRITY_DETAIL)
        val lines = shown.map { f ->
            "${icon[f.severity]} [${f.kind}] ${f.relativePath}: ${f.detail}"
        }.toMutableList()
        val omitted = findings.size - shown.size
        if (omitted > 0) {
            lines += "   … y $omitted hallazgo(s) informativo(s) más (no accionables)."
        }
        return lines
    }
  /**
   * Valida la sintaxis JSON de todo el directorio.
   *
 * TICKET-CORE-003: acepta CUALQUIER tipo de raíz válido. Bedrock usa
   * archivos como textures/texture_list.json cuyo contenido es un ARRAY en la raíz
   * (["textures/empty","textures/carryblock/destroy_stage_0",...]). Parsearlos
   * con JSONObject(text) lanzaba "cannot be converted to JSONObject" y se
  * reportaba como JSON inválido: un falso positivo.
   *
   * Se usa JSONTokener.nextValue(), que devuelve JSONObject, JSONArray,
    * JSONObject.NULL u otro tipo primitivo, todos válidos como raíz.
   * @return líneas de error (vacío = OK).
    */
    fun validateJsonSyntax(root: File): List<String> = erroresDeSintaxis(analizarPack(root))

    /**
     * ⭐ TICKET-CORE-013 — Errores de sintaxis sobre el análisis ya hecho.
     *
     * Si el parseo tolerante del análisis funcionó, el archivo es válido y no
     * hay nada más que comprobar: el parser estricto acepta un subconjunto de lo
     * que acepta el tolerante, así que un archivo que pasa el tolerante no puede
     * fallar el estricto. Solo cuando el análisis falla se recorre la cadena de
     * reintentos original —BOM y `LenientJson`—, de modo que el veredicto es
     * exactamente el mismo que antes pero sin reparsear los archivos sanos.
     */
    private fun erroresDeSintaxis(analisis: List<AnalisisArchivo>): List<String> {
        val errors = mutableListOf<String>()
        for (a in analisis) {
            val errorLectura = a.lecturaError
            if (errorLectura != null) {
                errors += "❌ JSON ilegible: ${a.rel} ($errorLectura)"
                continue
            }
            if (a.estructuralmenteValido) continue
            val text = a.text ?: continue
            if (isValidJsonRoot(text)) continue
            // Reintento tolerante: algunos archivos legacy llevan BOM inicial.
            if (isValidJsonRoot(text.trimStart('\uFEFF'))) continue
            // ⭐ FASE 5: reintento tolerante a comentarios y comas finales, con el
            // MISMO parser que usa el resto del pipeline. CoreCraft los inyecta
            // dentro de agregados como item_texture.json, y
            // `entities/vanilla/sulfur_cube.json` de Origins (66 KB) también los
            // trae; el preprocesado anterior no cubría todas las posiciones y el
            // archivo se reportaba como inválido siendo válido para el juego.
            if (LenientJson.parse(text) != null) continue
            if (LenientJson.parse(text.trimStart('\uFEFF')) != null) continue

            errors += "❌ JSON inválido: ${a.rel} (raíz no reconocida como JSONObject/JSONArray)"
        }
        if (errors.isNotEmpty()) {
            com.packforge.app.util.PackForgeLog.w(TAG, "Validación JSON: ${errors.size} archivo(s) inválido(s)")
        }
        return errors
    }

    /**
  * ¿El texto es un JSON válido con cualquier tipo de raíz?
   * JSONObject, JSONArray y JSONObject.NULL son todos aceptados.
     */
    private fun isValidJsonRoot(text: String): Boolean {
        if (text.isBlank()) return false
        return try {
      when (JSONTokener(text).nextValue()) {       is JSONObject, is JSONArray -> true
     JSONObject.NULL -> true
       else -> true // números, strings, booleanos: JSON válido
            }
        } catch (_: Exception) {
            false
  }
    }

    data class Inputs(
        val idRenames: List<IdentifierRemapper.RemapEntry> = emptyList(),
        val resourceAliases: List<String> = emptyList(),
        val soundRenames: Map<String, String> = emptyMap(),
        val scriptFindings: List<String> = emptyList(),
        val syntaxErrors: List<String> = emptyList(),
        val integrityFindings: List<String> = emptyList(),
        val validationResult: PackForgeValidator.ValidationResult? = null,
        val dependencyNotes: List<String> = emptyList(),
        val zipValidation: List<String> = emptyList(),
        val totalJsonsMerged: Int = 0,
        val elapsedSeconds: Double = 0.0,
        /**
         * Resumen del decifrador (CORE-004).
         *
         * ANTES no se incluia: el trabajo de recuperar addons closed-source era
         * INVISIBLE en el reporte. El usuario veia 51 avisos de "texto plano" y
         * ninguna explicacion de si el motor habia podido recuperar algo.
         */
        val decryptRestored: Int = 0,
        val decryptUnrecoverable: List<String> = emptyList(),
        val decryptUnrecoverableNeutralized: Int = 0,
        val decryptDecoysNeutralized: Int = 0,
        /**
         * Resultados de PackForgeHealer.repair(): texturas/modelos copiados
         * desde los addons originales y placeholders de geometría generados.
         */
        val healerNotes: List<String> = emptyList(),
        /**
         * Auditoría del .mcaddon YA ESCRITO (manifiestos y portadas presentes,
         * separadores de ruta válidos). Es distinta de `zipValidation`, que
         * valida las referencias de entidades dentro del archivo.
         */
        val zipAuditNotes: List<String> = emptyList(),
        /**
         * Tiempos por fase del pipeline (en segundos).
         * Clave = nombre de la fase, Valor = segundos.
         */
        val phaseTimings: Map<String, Double> = emptyMap(),
        /**
         * ⭐ TICKET-CORE-013 — Tiempos de CADA fusión crítica de la Fase 3.
         *
         * La Fase 3 lanza 22 fusiones en paralelo y el reporte solo mostraba el
         * total del bloque. Con dos addons RP cualesquiera (295 JSON) ese total
         * era de 16,08 s —el 59,7 % de la exportación— sin decir cuál de las 22
         * se los llevaba: el desglose existía pero iba a `println`, que en
         * Android termina en logcat y no en el informe. Sin esta lista,
         * optimizar la fase era adivinar.
         *
         * Ordenado por coste descendente (lo decide el orquestador).
         */
        val criticalMergeTimings: List<Pair<String, Double>> = emptyList(),
        /**
         * Lista de addons fusionados en este modpack.
         * Cada entrada: "nombre (tipo: BP/RP/BOTH, tamaño: X MB)"
         */
        val mergedAddons: List<String> = emptyList(),
        /**
         * Conflictos registrados durante la fusión ([ConflictRegistry]).
         *
         * Antes NO llegaban a `fusion_report.txt`: el usuario los veía en la
         * pantalla de Conflictos y en el semáforo, pero el documento que se
         * entrega junto al `.mcaddon` no decía nada de ellos. Eso dejaba fuera
         * del informe hallazgos como una geometría duplicada o un subpack
         * renombrado, que solo existían en logcat.
         */
        val conflicts: List<MergeConflict> = emptyList()
    )

    /** Icono del semáforo para un conflicto en el informe de texto. */
    private fun iconoSeveridad(s: ConflictSeverity): String = when (s) {
        ConflictSeverity.CRITICAL -> "🔴"
        ConflictSeverity.HIGH -> "🟠"
        ConflictSeverity.MEDIUM -> "🟡"
        else -> "⚪"
    }

    fun build(inputs: Inputs): List<String> {
        val lines = mutableListOf<String>()
        lines += "══════════════ PACKFORGE · REPORTE DE FUSIÓN INTELIGENTE ══════════════"
        lines += "JSONs procesados: ${inputs.totalJsonsMerged}   |   Tiempo total: ${"%.1f".format(inputs.elapsedSeconds)}s"
        lines += ""

        // ── 0. ADDONS FUSIONADOS ──
        if (inputs.mergedAddons.isNotEmpty()) {
            lines += "── 0. ADDONS FUSIONADOS EN ESTE MODPACK ──"
            inputs.mergedAddons.forEach { lines += "  📦 $it" }
            lines += ""
        }

        // ── 0b. TIEMPOS POR FASE ──
        if (inputs.phaseTimings.isNotEmpty()) {
            lines += "── 0b. TIEMPOS POR FASE DEL PIPELINE ──"
            // ⚠️ DOS correcciones respecto a la versión anterior, que mostraba
            // cifras que no significaban lo que decían:
            //
            //  1. `markPhase` guarda tiempo ACUMULADO desde el inicio. Imprimirlo
            //     tal cual hacía que los porcentajes sumaran >100% y que toda
            //     cifra pareciera "lo que llevaba el pipeline". Aquí se resta el
            //     acumulado anterior y lo que se ve es la DURACIÓN del tramo.
            //  2. Cada marca se registra al ENTRAR en su etapa (justo después de
            //     su mensaje de progreso), así que el tiempo transcurrido hasta
            //     ella pertenece a la etapa ANTERIOR. Etiquetar el tramo con la
            //     marca que lo cierra hacía que "Extracción" apareciera con 0,08 s
            //     y la extracción real (130 s) saliera bajo "Clasificación".
            //     Por eso la etiqueta es la de la marca que ABRE el tramo.
            val marcas = inputs.phaseTimings.entries.toList()
            var anterior = 0.0
            marcas.forEachIndexed { idx, marca ->
                val duracion = (marca.value - anterior).coerceAtLeast(0.0)
                val etiqueta = if (idx == 0) "(arranque)" else marcas[idx - 1].key
                val pct = if (inputs.elapsedSeconds > 0) duracion / inputs.elapsedSeconds * 100 else 0.0
                lines += String.format("  ⏱️ %-46s %8.2fs %5.1f%%", etiqueta, duracion, pct)
                anterior = marca.value
            }
            val noMedido = inputs.elapsedSeconds - anterior
            if (noMedido > 0.05) {
                val pct = if (inputs.elapsedSeconds > 0) noMedido / inputs.elapsedSeconds * 100 else 0.0
                val ultima = marcas.lastOrNull()?.key ?: ""
                // ⚠️ La etiqueta anterior decía "(empaquetado/cierre)" y era
                // engañosa: este tramo empieza DESPUÉS de la marca «Generación
                // de reporte», así que es el reporte (auditorías de sintaxis e
                // integridad + validación del ZIP) y el cierre, no el empaquetado
                // —que ya se contó en su propia marca—. En el pack de prueba eran
                // 3,47 s (12,9 %), el segundo coste de la exportación, y con la
                // etiqueta vieja parecía parte del ZIP.
                lines += String.format(
                    "  ⏱️ %-46s %8.2fs %5.1f%%",
                    "resto tras '$ultima' (reporte y cierre)", noMedido, pct
                )
            }
            lines += String.format("  %-48s %8.2fs", "TOTAL", inputs.elapsedSeconds)
            lines += ""
        }

        // ── 0c. FUSIONES CRÍTICAS, POR COSTE ──
        //
        // La Fase 3 es un bloque de 22 fusiones en paralelo cuyo total puede
        // llevarse más de la mitad de la exportación. Sin este desglose el
        // informe decía «Fase 3: 16,08 s» y no había forma de saber cuál de las
        // 22 fusiones atacar; el detalle solo existía en logcat.
        //
        // Se listan TODAS, no un top: un tope escondería justo la fusión que se
        // busca cuando cambia el addon, y son 23 líneas.
        if (inputs.criticalMergeTimings.isNotEmpty()) {
            lines += "── 0c. FUSIONES CRÍTICAS (Fase 3, por coste) ──"
            val suma = inputs.criticalMergeTimings.sumOf { it.second }
            inputs.criticalMergeTimings.forEach { (nombre, segundos) ->
                val pct = if (suma > 0) segundos / suma * 100 else 0.0
                lines += String.format("  🔧 %-44s %8.2fs %5.1f%%", nombre, segundos, pct)
            }
            lines += String.format("  %-48s %8.2fs", "SUMA de las fusiones", suma)
            lines += ""
        }

        // ── 0d. CONFLICTOS REGISTRADOS ──
        //
        // Todo lo que el motor descarta, renombra o repara debe verse aquí
        // (regla 15). Hasta ahora estos conflictos solo existían en la UI y en
        // el semáforo: el documento que se entrega con el `.mcaddon` no los
        // mencionaba, así que hallazgos como una geometría duplicada o un
        // subpack renombrado quedaban solo en logcat.
        if (inputs.conflicts.isNotEmpty()) {
            val conflictos = inputs.conflicts
            lines += "── 0d. CONFLICTOS REGISTRADOS (${conflictos.size}) ──"
            conflictos.groupingBy { it.conflictType }
                .eachCount()
                .entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .forEach { (tipo, cantidad) ->
                    lines += "  • $tipo: $cantidad"
                }
            lines += ""

            // El detalle omite los avisos menores y las sobrescrituras de
            // versión (rutina), y deduplica, para que el informe siga siendo
            // legible en packs con cientos de colisiones de escalares.
            val detallables = conflictos
                .filter { it.severity != ConflictSeverity.LOW &&
                    it.severity != ConflictSeverity.WARNING &&
                    it.conflictType != "VERSION_OVERWRITE" }
                .distinctBy { "${it.conflictType}|${it.filePath}|${it.description}" }
            if (detallables.isNotEmpty()) {
                lines += "  DETALLE (${detallables.size} conflicto(s) relevante(s)):"
                detallables.take(60).forEach { c ->
                    lines += "  ${iconoSeveridad(c.severity)} [${c.conflictType}] ${c.filePath}"
                    if (c.sourceAddon.isNotBlank() || c.targetAddon.isNotBlank()) {
                        lines += "      ${c.sourceAddon} ↔ ${c.targetAddon}"
                    }
                    if (c.description.isNotBlank()) lines += "      ${c.description}"
                }
                if (detallables.size > 60) {
                    lines += "  … y ${detallables.size - 60} conflicto(s) más (ver pantalla de Conflictos)"
                }
            }
            lines += ""
        }

        lines += "── 1. RESOLUCIÓN DE IDENTIFICADORES ──"
        if (inputs.idRenames.isEmpty()) {
            lines += "✅ Sin colisiones de IDs entre addons."
        } else {
            inputs.idRenames.forEach { r ->
                lines += "🔁 Renombrado ID: ${r.oldId} → ${r.newId} (${r.file})"
            }
        }
        lines += ""

        lines += "── 2. RECURSOS (texturas / sonidos / partículas) ──"
        if (inputs.resourceAliases.isEmpty() && inputs.soundRenames.isEmpty()) {
            lines += "✅ Sin colisiones de rutas de recursos."
        } else {
            inputs.resourceAliases.forEach { lines += "🖼️ Recurso con alias: $it" }
            inputs.soundRenames.forEach { (old, new) -> lines += "🔊 Sonido renombrado: $old → $new" }
        }
        lines += ""

        lines += "── 3. SCRIPTS ──"
        if (inputs.scriptFindings.isEmpty()) lines += "✅ Sin colisiones detectadas en scripts/."
        else inputs.scriptFindings.forEach { lines += it }
        lines += ""

        // ── 3b. DECIFRADOR (addons closed-source) ──────────────────────
        // ANTES esta seccion NO existia: el trabajo de recuperacion era
        // invisible, y el usuario no podia saber si los 51 avisos de
        // "texto plano" eran fallos reales o contenido ya recuperado.
        lines += "── 3b. DECIFRADOR (addons closed-source) ──"
        if (inputs.decryptRestored == 0 && inputs.decryptUnrecoverable.isEmpty()) {
            lines += "✅ No se detectó contenido closed-source que recuperar."
        } else {
            lines += "🔓 Archivos canónicos restaurados: ${inputs.decryptRestored}"
            if (inputs.decryptDecoysNeutralized > 0) {
                lines += "🧹 Señuelos neutralizados (.bak): ${inputs.decryptDecoysNeutralized}"
            }
            if (inputs.decryptUnrecoverable.isNotEmpty()) {
                lines += "⚠️ SIN CONTRAPARTE EN CLARO (${inputs.decryptUnrecoverable.size}): " +
                    "el contenido real NO viene en el .mcaddon, es irrecuperable."
                if (inputs.decryptUnrecoverableNeutralized > 0) {
                    lines += "   ↳ ${inputs.decryptUnrecoverableNeutralized} archivo(s) de texto plano " +
                        "pasados a .bak en su ruta canónica, para que Bedrock deje de " +
                        "intentar parsearlos y de llenar el Content Log de errores."
                }
                lines += "   IMPORTANTE: si alguno es geometría, material o render_controller " +
                    "referenciado por una entidad, esa entidad NO se renderizará."
                inputs.decryptUnrecoverable.take(20).forEach {
                    lines += "      • $it"
                }
                if (inputs.decryptUnrecoverable.size > 20) {
                    lines += "      … y ${inputs.decryptUnrecoverable.size - 20} más"
                }
            }
        }
        lines += ""

        lines += "── 4. DEPENDENCIAS DE ENTIDADES (geometría/texturas/animaciones) ──"
        if (inputs.dependencyNotes.isEmpty()) lines += "ℹ️ Sin entidades que procesar."
        else inputs.dependencyNotes.forEach { lines += it }
        lines += ""

        lines += "── 5. EMPAQUETADO (.mcaddon) ──"
        if (inputs.zipAuditNotes.isEmpty()) {
            lines += "✅ El .mcaddon contiene los manifiestos y portadas de todos los packs."
        } else {
            inputs.zipAuditNotes.forEach { lines += "   • $it" }
        }
        lines += ""

        lines += "── 6. VALIDACIÓN POST-FUSIÓN ──"
        if (inputs.syntaxErrors.isEmpty()) lines += "✅ Todos los JSON del paquete son sintácticamente válidos."
   else inputs.syntaxErrors.forEach { lines += it }
        // TICKET-CORE-004: resumen ejecutivo en vez de un listado plano de 56 líneas.
        lines += buildIntegritySummary(inputs.integrityFindings)
        if (inputs.zipValidation.isEmpty()) lines += "✅ Paquete final validado: referencias de entidades íntegras."
        else inputs.zipValidation.forEach { lines += it }
        inputs.validationResult?.let { vr ->
            lines += "Texturas faltantes: ${vr.missingTextures.size} | Modelos faltantes: ${vr.missingModels.size} " +
                "| Referencias reparadas: ${vr.fixedReferences} | Claves .lang añadidas: ${vr.langKeysAdded.values.sum()}"
            vr.warnings.take(10).forEach { lines += "⚠️ $it" }
            if (vr.incompleteEntities.isNotEmpty()) {
                lines += "🧩 Entidades incompletas (${vr.incompleteEntities.size}): " +
                    "tienen JSON válido pero les falta geometría, texturas o definición en BP."
                vr.incompleteEntities.take(10).forEach { lines += "   • $it" }
                if (vr.incompleteEntities.size > 10) {
                    lines += "   … y ${vr.incompleteEntities.size - 10} más"
                }
            }
        }
        if (inputs.healerNotes.isNotEmpty()) {
            lines += "💊 HEALER (reparación automática):"
            inputs.healerNotes.forEach { lines += "   • $it" }
        }
        lines += "═══════════════════════════════════════════════════════════════════════"
        return lines
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TICKET-CORE-004 — RESUMEN EJECUTIVO DE INTEGRIDAD
    // ══════════════════════════════════════════════════════════════════════════
    //
    // Un listado plano de 56 líneas, donde 47 dicen literalmente lo mismo,
    // esconde los 9 hallazgos que el usuario necesita ver. Aquí se separa:
    //
    //   · HIGH  → se listan SIEMPRE, uno por línea. Requieren acción.
    //   · LOW   → se colapsan en una sola línea con el motivo y el recuento,
    //             porque son ruido informativo (decoys del propio addon).
    //   · INFO  → una sola línea de confirmación.
    //
    // El detalle completo de los LOW sigue disponible en el reporte de la app,
    // pero el archivo de texto deja de ser un muro ilegible.
    private fun buildIntegritySummary(findings: List<String>): List<String> {
        if (findings.isEmpty()) {
            return listOf("✅ Integridad de archivos: sin texto plano ni claves cifradas.")
        }

        val out = mutableListOf<String>()
        val high = findings.filter { it.contains("❌") }
        val low = findings.filter { it.contains("⚠️") }
        val info = findings.filter { it.contains("ℹ️") }

        // ⭐ FASE 5: contadores HONESTOS.
        //
        // ANTES los INFO se contaban DOS veces: entraban en `relevantes`
        // (high + info) y otra vez en la cifra de "informativos" (low + info).
        // El encabezado sumaba más de lo que luego listaba, así que el usuario
        // veía "12 relevantes · 40 informativos" sobre un total de 45 hallazgos.
        // Ahora cada hallazgo cae en UNA sola categoría y la suma cuadra.
        val total = findings.size
        out += "ℹ️ Integridad: $total hallazgo(s) · 🔴 ${high.size} alto · " +
            "🟡 ${low.size} aviso(s) · ℹ️ ${info.size} sombra(s)"

        if (high.isEmpty()) {
            out += "   ✅ Ningún archivo en ruta canónica queda ilegible para el juego."
        }

        // ── HIGH: nunca se oculta nada ──
        if (high.isNotEmpty()) {
            out += "   🔴 Archivos que el juego NO podrá leer (${high.size}):"
            high.forEach { out += "      ${it.trim().removePrefix("❌").trim()}" }
        }

        // ── LOW/INFO: colapsados por motivo, con tope explícito ──
        if (low.isNotEmpty()) {
            out += "   🟡 Avisos sin impacto (${low.size}): el propio addon los ignora."
            out += "      └─ No requieren acción. Detalle en el reporte de la app."
        }

        if (info.isNotEmpty()) {
            out += "   ℹ️ Archivos sombreados con contraparte válida (${info.size}): sin efecto."
        }

        return out
    }

    fun writeToFile(lines: List<String>, outFile: File): Boolean {
        return try {
            outFile.parentFile?.mkdirs()
            OutputStreamWriter(FileOutputStream(outFile), StandardCharsets.UTF_8).use { writer ->
                lines.forEach { writer.write(it); writer.write("\n") }
            }
            com.packforge.app.util.PackForgeLog.d(TAG, "📄 Reporte escrito: ${outFile.absolutePath}")
            true
        } catch (e: Exception) {
            com.packforge.app.util.PackForgeLog.e(TAG, "No se pudo escribir el reporte: ${e.message}")
            false
        }
    }
}











