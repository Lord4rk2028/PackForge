package com.packforge.app.domain.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * ════════════════════════════════════════════════════════════════════════════
 * ARMONIZADOR DE ESQUEMAS BEDROCK
 * ════════════════════════════════════════════════════════════════════════════
 *
 * Normaliza lo que se puede normalizar SIN romper contenido, y **informa** del
 * resto. Solo muta en un caso, verificado contra contenido oficial:
 *
 *   · Componentes de BLOQUE del esquema previo a 1.19.80 → esquema moderno,
 *     cuando el propio archivo YA declara el esquema moderno (ver abajo).
 *
 * ── Lo que este objeto NO hace, y por qué ─────────────────────────────────
 *
 * Una versión anterior de este archivo aplicaba tres renombrados de componente
 * y dos transformaciones más. Todos eran destructivos. Se comprobaron contra
 * `bedrock-samples` (el paquete oficial de Mojang: 130 entidades de
 * comportamiento, 125 bloques y 82 ítems):
 *
 *  1. `minecraft:collision_box` → `minecraft:collider`.
 *     `collision_box` se usa en **126 de 130** entidades oficiales y
 *     `collider` en **0**. Renombrarlo dejaba a cada entidad sin caja de
 *     colisión.
 *
 *  2. `minecraft:movement` → `minecraft:movement.general`.
 *     `movement` aparece en **93 de 130** entidades oficiales y
 *     `movement.general` en **0**. Es un nombre inventado (en Bedrock
 *     `movement.*` es espacio de consultas Molang, no un componente).
 *
 *  3. `minecraft:damage` → `minecraft:weapon` (ítems).
 *     `damage` aparece en **7 de 82** ítems oficiales y `weapon` en **0**.
 *     Además no son equivalentes: `minecraft:damage` declara la DURABILIDAD
 *     del ítem, mientras que `weapon` describe eventos al golpear. El
 *     renombrado añadía encima campos inventados (`speed`, `durability`,
 *     `enchantable`) y borraba el componente real.
 *
 *  Los tres convertían un componente válido y muy usado en uno que no existe
 *  en el contenido oficial, así que se han ELIMINADO por completo. Cualquier
 *  renombrado nuevo debe pasar la misma prueba: el nombre destino tiene que
 *  aparecer en contenido oficial.
 *
 *  4. Subir `format_version` al "objetivo del modpack".
 *     `format_version` **selecciona el esquema** con el que se interpreta el
 *     archivo; subirla no convierte el contenido. Un ítem escrito para 1.12
 *     con `format_version` subida a 1.21 no se "actualiza": se reinterpreta
 *     bajo un esquema para el que no se escribió. Se ha dejado de reescribir.
 *
 *  5. Prefijar variables Molang (`v.custom` → `v.pf_addon_custom`).
 *     Una variable se DEFINE en el `player.entity.json` (o el entity JSON) y
 *     se LEE en los `animations/` y `render_controllers/` del pack. Son
 *     archivos distintos y los fusiona otro componente. Reescribirla en uno
 *     solo deja la lectura apuntando a una variable que ya no existe: la
 *     animación se queda en su posición por defecto, en silencio. Se DETECTA
 *     y se informa, sin reescribir.
 *
 * ── Regla de diseño ───────────────────────────────────────────────────────
 *
 * Ante la duda, NO se muta. Este objeto existe para que la fusión no pierda
 * contenido; una transformación "útil" que rompe un pack es un fallo neto.
 * Todo lo que se detecte y no se pueda arreglar con certeza sale por
 * [inspect] para que el reporte lo muestre.
 */
object SchemaNormalizer {

    /**
     * Versión en la que el esquema de bloque moderno sustituye al previo.
     *
     * Por debajo de ella el archivo usa el esquema antiguo, que **sigue siendo
     * válido** (es lo que declara su `format_version`), así que no se toca
     * nada: reescribirlo sería arreglar algo que no está roto.
     */
    private val BLOCK_MODERN_SCHEMA_SINCE = listOf(1, 19, 80)

    /**
     * Nombres de componente de bloque del esquema antiguo, para REPORTAR.
     *
     * Incluye los que no tienen traducción verificada (`block_light_emission`):
     * no aparece ni el antiguo ni el moderno en los samples oficiales, así que
     * no hay evidencia de cuál es su reemplazo y no se reescribe.
     */
    private val LEGACY_BLOCK_COMPONENTS = listOf(
        "minecraft:destroy_time",
        "minecraft:explosion_resistance",
        "minecraft:block_light_absorption",
        "minecraft:block_light_emission"
    )

    data class NormalizeResult(
        val normalizedJson: JSONObject,
        val changes: List<String> = emptyList()
    )

    /** Hallazgo de solo lectura. No implica ninguna modificación del JSON. */
    data class Inspection(
        val addonId: String,
        /** `format_version` del propio archivo, tal cual venía. */
        val formatVersion: List<Int>?,
        /** Componentes de bloque del esquema antiguo encontrados. */
        val legacyBlockComponents: List<String>,
        /**
         * Variables Molang propias (`variable.x` / `v.x`) referenciadas por el
         * archivo. Se informan porque dos addons que toquen la misma entidad
         * pueden pisarse la misma variable.
         */
        val customMolangVariables: Set<String>
    )

    /**
     * Normaliza el JSON al esquema objetivo.
     *
     * @param targetFormatVersion versión de contenido objetivo (`1.x.y`). Si no
     *        tiene forma de versión de contenido se IGNORA: el pipeline calcula
     *        este valor a partir de los `manifest.json`, cuyo `format_version`
     *        es la versión del FORMATO DE MANIFIESTO (2 o 3), no la del esquema
     *        del contenido. Gatear con ese número daría decisiones al azar.
     */
    fun normalize(
        json: JSONObject,
        addonId: String,
        targetFormatVersion: List<Int>
    ): NormalizeResult {
        val result = JSONObject(json.toString())

        // Solo se traduce si el esquema moderno está en juego, tanto por el
        // objetivo (cuando es una versión de contenido de verdad) como por el
        // propio archivo.
        val targetIsContentVersion = targetFormatVersion.firstOrNull() == 1
        if (targetIsContentVersion && compareVersion(targetFormatVersion, BLOCK_MODERN_SCHEMA_SINCE) < 0) {
            return NormalizeResult(result, emptyList())
        }

        return NormalizeResult(result, translateLegacyBlockComponents(result, addonId))
    }

    /**
     * Inspección de solo lectura: qué esquema declara el archivo, qué
     * componentes antiguos usa y qué variables Molang propias referencia.
     *
     * Es lo que permite que el reporte avise sin tocar el pack.
     */
    fun inspect(json: JSONObject, addonId: String): Inspection {
        val components = json.optJSONObject("minecraft:block")?.optJSONObject("components")
        return Inspection(
            addonId = addonId,
            formatVersion = readFormatVersion(json),
            legacyBlockComponents = LEGACY_BLOCK_COMPONENTS.filter { components?.has(it) == true },
            customMolangVariables = collectCustomMolangVariables(json)
        )
    }

    // ── Traducción de bloques del esquema antiguo (la única verificada) ──────

    /**
     * Traduce los componentes de bloque que Mojang renombró al introducir el
     * esquema moderno (1.19.80+).
     *
     * Comprobado en `bedrock-samples`: los nombres modernos aparecen en 124 de
     * 125 bloques (`destructible_by_mining`, `destructible_by_explosion`,
     * `light_dampening`) y los antiguos en **0**. La estructura destino también
     * está copiada del contenido oficial, no inventada:
     *
     *   `"minecraft:destructible_by_mining": {"seconds_to_destroy": 1.8}`
     *   `"minecraft:destructible_by_explosion": {"explosion_resistance": 1.8}`
     *
     * Los antiguos eran ESCALARES, así que un simple renombrado produciría un
     * componente inválido: hay que reestructurar el valor.
     */
    private fun translateLegacyBlockComponents(json: JSONObject, addonId: String): List<String> {
        val components = json.optJSONObject("minecraft:block")?.optJSONObject("components")
            ?: return emptyList()

        // El archivo tiene que declarar el esquema moderno: por debajo, su
        // `format_version` dice que el esquema antiguo es el válido.
        val own = readFormatVersion(json) ?: return emptyList()
        if (compareVersion(own, BLOCK_MODERN_SCHEMA_SINCE) < 0) return emptyList()

        val changes = mutableListOf<String>()

        // destroy_time (segundos, escalar) → destructible_by_mining {seconds_to_destroy}
        if (components.has("minecraft:destroy_time") &&
            !components.has("minecraft:destructible_by_mining")
        ) {
            numberOrNull(components.opt("minecraft:destroy_time"))?.let { seconds ->
                components.put(
                    "minecraft:destructible_by_mining",
                    JSONObject().put("seconds_to_destroy", seconds)
                )
                components.remove("minecraft:destroy_time")
                changes.add(
                    "bloque ($addonId): minecraft:destroy_time=$seconds → " +
                        "minecraft:destructible_by_mining.seconds_to_destroy"
                )
            }
        }

        // explosion_resistance (escalar) → destructible_by_explosion {explosion_resistance}
        if (components.has("minecraft:explosion_resistance") &&
            !components.has("minecraft:destructible_by_explosion")
        ) {
            numberOrNull(components.opt("minecraft:explosion_resistance"))?.let { resistance ->
                components.put(
                    "minecraft:destructible_by_explosion",
                    JSONObject().put("explosion_resistance", resistance)
                )
                components.remove("minecraft:explosion_resistance")
                changes.add(
                    "bloque ($addonId): minecraft:explosion_resistance=$resistance → " +
                        "minecraft:destructible_by_explosion.explosion_resistance"
                )
            }
        }

        // block_light_absorption (entero 0-15) → light_dampening (mismo entero)
        if (components.has("minecraft:block_light_absorption") &&
            !components.has("minecraft:light_dampening")
        ) {
            numberOrNull(components.opt("minecraft:block_light_absorption"))?.let { dampening ->
                components.put("minecraft:light_dampening", dampening.toInt())
                components.remove("minecraft:block_light_absorption")
                changes.add(
                    "bloque ($addonId): minecraft:block_light_absorption=$dampening → " +
                        "minecraft:light_dampening"
                )
            }
        }

        // `minecraft:block_light_emission` NO se traduce: ni el nombre antiguo ni
        // `minecraft:light_emission` aparecen en los samples oficiales, así que no
        // hay evidencia del reemplazo. Se reporta por `inspect`.

        return changes
    }

    // ── Detección de variables Molang (sin reescribir) ───────────────────────

    private fun collectCustomMolangVariables(json: JSONObject): Set<String> {
        val found = linkedSetOf<String>()
        collectVariables(json, found)
        return found
    }

    private fun collectVariables(node: Any?, out: MutableSet<String>) {
        when (node) {
            is JSONObject -> node.keys().forEach { collectVariables(node.opt(it), out) }
            is JSONArray -> for (i in 0 until node.length()) collectVariables(node.opt(i), out)
            is String -> MOLANG_VAR_REGEX.findAll(node).forEach { out.add(it.value) }
            else -> Unit
        }
    }

    /**
     * `variable.x` y su alias `v.x`, con puntos incluidos (`variable.hpi.x`).
     *
     * Se aceptan ambos nombres porque Molang admite los dos y el contenido real
     * los mezcla (Just-Health-Indicator escribe `variable.hpi_y` en el entity y
     * `v.hpi_y` en las animaciones).
     */
    private val MOLANG_VAR_REGEX = Regex("""\b(?:variable|v)\.[A-Za-z_][A-Za-z0-9_.]*""")

    // ── Versiones ────────────────────────────────────────────────────────────

    /**
     * Detecta la `format_version` MÁXIMA de una lista de JSONs.
     *
     * ⚠️ Devuelve lo que declare cada JSON. Si se le pasan `manifest.json`, el
     * resultado es la versión del formato de MANIFIESTO (2 o 3), que no es una
     * versión de esquema de contenido y no sirve para gatear traducciones.
     * Para eso, usar `format_version` del propio archivo.
     */
    /**
     * Reparto de esquemas de un addon, para el reporte. Solo lectura.
     *
     * `versions` va de menor a mayor; si hay más de una, el addon mezcla
     * esquemas (lo normal: contenido viejo conviviendo con nuevo).
     */
    data class SchemaSpread(
        val addonName: String,
        val totalFiles: Int,
        val versions: List<String>,
        val legacyBlockComponents: List<String>,
        /**
         * Bloque(s) con el esquema MODERNO en vigor pero componentes con nombre
         * ANTIGUO. Es la única combinación que el motor reporta como rota: el
         * `format_version` pide el esquema nuevo y los nombres viejos no existen
         * en él.
         */
        val inconsistentBlocks: List<String> = emptyList()
    ) {
        val isMixed: Boolean get() = versions.size > 1
        val hasInconsistentBlocks: Boolean get() = inconsistentBlocks.isNotEmpty()

        /** Una línea legible para `fusion_report.txt`. */
        fun describe(): String {
            val base = if (versions.isEmpty()) {
                "$totalFiles fichero(s) de contenido sin format_version legible"
            } else if (versions.size == 1) {
                "$totalFiles fichero(s), esquema ${versions.first()}"
            } else {
                "$totalFiles fichero(s), esquemas ${versions.first()} a ${versions.last()} " +
                    "(${versions.size} distintos: MEZCLA)"
            }
            val legacy = if (legacyBlockComponents.isEmpty()) "" else {
                " · componentes de bloque antiguos: " + legacyBlockComponents.joinToString()
            }
            val roto = if (!hasInconsistentBlocks) "" else {
                " · ⚠ ${inconsistentBlocks.size} bloque(s) con esquema moderno y componentes " +
                    "antiguos (combinación inválida): " + inconsistentBlocks.joinToString()
            }
            return "$addonName → $base$legacy$roto"
        }
    }

    /**
     * ⭐ TICKET-CORE-011 — Normaliza un JSON de BLOQUE con la única
     * transformación verificada.
     *
     * La puerta es el `format_version` del PROPIO archivo (lo comprueba
     * [translateLegacyBlockComponents]), no el del modpack: así no depende del
     * «objetivo» que el pipeline calcula a partir de los manifiestos.
     */
    fun normalizeBlock(json: JSONObject, addonId: String): NormalizeResult {
        val result = JSONObject(json.toString())
        return NormalizeResult(result, translateLegacyBlockComponents(result, addonId))
    }

    /**
     * Aplica [normalizeBlock] a los bloques del MODPACK ya fusionado.
     *
     * Por qué se hace sobre el pack fusionado y no sobre el origen: aquí es
     * donde el `.mcaddon` que se entrega tiene el contenido definitivo, y no se
     * alteran las carpetas extraídas de los addons del usuario.
     *
     * Probado en el juego (addon `PF Legacy Schema Test.mcaddon`): un bloque con
     * `format_version: 1.20.50` y `minecraft:destroy_time` **no se carga**,
     * mientras que su gemelo con `destructible_by_mining` sí. Es el único caso
     * en el que reescribir está justificado.
     *
     * @return descripciones de los cambios aplicados (van al reporte).
     */
    fun normalizeBlockFiles(blocksDir: File, addonLabel: String = "modpack"): List<String> {
        if (!blocksDir.isDirectory) return emptyList()

        val cambios = mutableListOf<String>()
        for (file in DirIndexCache.index(blocksDir).jsonFiles) {
            val texto = runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull() ?: continue
            val json = runCatching { JSONObject(texto) }.getOrNull()
                ?: LenientJson.parseObject(texto) ?: continue

            val resultado = normalizeBlock(json, addonLabel)
            if (resultado.changes.isEmpty()) continue

            runCatching {
                OutputStreamWriter(
                    FileOutputStream(file),
                    StandardCharsets.UTF_8
                ).use { it.write(resultado.normalizedJson.toString(2)) }
            }.onSuccess {
                // El nombre del fichero importa para poder rastrearlo.
                cambios.addAll(resultado.changes.map { "${file.name}: $it" })
            }.onFailure {
                // No se disfraza: si no se pudo escribir, el reporte baja a HIGH
                // porque el bloque sigue roto.
                cambios.add("⚠ no se pudo reparar ${file.name}: ${it.message}")
            }
        }
        return cambios
    }

    /** Carpetas cuyo `format_version` describe el esquema de CONTENIDO. */
    private val CONTENT_SUBDIRS = listOf("entities/", "entity/", "items/", "item/", "blocks/")

    private val FORMAT_VERSION_STRING = Regex("\"format_version\"\\s*:\\s*\"([0-9][0-9.]*)\"")
    private val FORMAT_VERSION_ARRAY =
        Regex("\"format_version\"\\s*:\\s*\\[\\s*(\\d+)\\s*,\\s*(\\d+)")

    /**
     * ⭐ TICKET-CORE-010 — Resume el esquema de un addon SIN modificar nada.
     *
     * Es la alternativa segura a «transpilar»: informa del reparto real de
     * `format_version` y de si hay componentes de bloque del esquema antiguo,
     * que es lo único que el motor sabe traducir con garantías.
     *
     * Lee el texto y extrae la versión con una expresión regular: **no parsea**
     * el archivo completo, porque hacerlo con todos los JSON de un addon grande
     * es justo el coste que se eliminó en TICKET-CORE-009.
     *
     * @return `null` si el addon no tiene ficheros de contenido reconocibles.
     */
    fun summarizeSchemaSpread(root: File, addonName: String = root.name): SchemaSpread? {
        if (!root.isDirectory) return null

        val versiones = linkedSetOf<String>()
        val legacy = linkedSetOf<String>()
        val inconsistent = mutableListOf<String>()
        var total = 0

        for (file in DirIndexCache.index(root).jsonFiles) {
            val rel = file.relativeTo(root).path.replace('\\', '/').lowercase(Locale.ROOT)
            if (rel.endsWith("manifest.json")) continue
            if (CONTENT_SUBDIRS.none { rel.contains(it) }) continue

            val texto = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: continue
            total++

            val version = FORMAT_VERSION_STRING.find(texto)?.groupValues?.get(1)
                ?: FORMAT_VERSION_ARRAY.find(texto)?.let { "${it.groupValues[1]}.${it.groupValues[2]}" }
            if (!version.isNullOrBlank()) versiones.add(version.trim())

            var tieneLegacy = false
            LEGACY_BLOCK_COMPONENTS.forEach { nombre ->
                if (texto.contains("\"$nombre\"")) {
                    legacy.add(nombre)
                    tieneLegacy = true
                }
            }

            // ⭐ La combinación que SÍ está rota: el archivo pide el esquema
            // moderno (>= 1.19.80) y sin embargo usa nombres de componente que
            // ese esquema ya no define. Se reporta; no se reescribe.
            if (tieneLegacy && version != null &&
                compareVersion(parseVersionString(version.trim()), BLOCK_MODERN_SCHEMA_SINCE) >= 0
            ) {
                if (inconsistent.size < 10) inconsistent.add(rel)
            }
        }

        if (total == 0) return null

        val ordenadas = versiones.sortedWith { a, b ->
            compareVersion(parseVersionString(a), parseVersionString(b))
        }
        return SchemaSpread(addonName, total, ordenadas, legacy.toList(), inconsistent)
    }

    fun detectMaxFormatVersion(jsons: List<JSONObject>): List<Int> {
        var maxVersion = listOf(1, 0, 0)
        jsons.forEach { json ->
            val version = readFormatVersion(json) ?: return@forEach
            if (compareVersion(version, maxVersion) > 0) maxVersion = version
        }
        return maxVersion
    }

    fun compareSemver(v1: String, v2: String): Int =
        compareVersion(parseVersionString(v1), parseVersionString(v2))

    /**
     * `format_version` de un JSON ya parseado (lista de enteros), o `null` si
     * el archivo no la declara o no es interpretable.
     *
     * Expuesta para [FormatVersionNegotiator] y el enrutador de declaraciones:
     * la versión es del ESQUEMA, no del pack, y hay que compararla tal cual.
     */
    fun formatVersionOf(json: JSONObject): List<Int>? = readFormatVersion(json)

    /** Devuelve la versión del JSON como lista, o `null` si no es interpretable. */
    private fun readFormatVersion(json: JSONObject): List<Int>? {
        val current = json.opt("format_version") ?: return null
        val parsed = when (current) {
            is JSONArray -> (0 until current.length())
                .mapNotNull { current.optInt(it, -1).takeIf { n -> n >= 0 } }
            is String -> parseVersionString(current)
            is Number -> listOf(current.toInt())
            else -> return null
        }
        return parsed.takeIf { it.isNotEmpty() }
    }

    private fun parseVersionString(v: String): List<Int> =
        v.trim().split('.').mapNotNull { it.trim().toIntOrNull() }

    /**
     * Compara dos versiones componente a componente, rellenando con ceros.
     * Devuelve negativo si `a < b`, positivo si `a > b` y 0 si son iguales.
     */
    private fun compareVersion(a: List<Int>, b: List<Int>): Int {
        val max = maxOf(a.size, b.size)
        for (i in 0 until max) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    /** Convierte a número un valor que puede venir como número o como texto. */
    private fun numberOrNull(raw: Any?): Double? = when (raw) {
        is Number -> raw.toDouble()
        is String -> raw.trim().toDoubleOrNull()
        else -> null
    }
}
