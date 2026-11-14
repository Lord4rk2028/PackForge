package com.packforge.app.domain.engine

import com.packforge.app.util.PackForgeLog
import org.json.JSONObject
import java.io.File

/**
 * ==============================================================================
 * BEDROCK DECIFRATOR - TICKET-CORE-004
 * ==============================================================================
 *
 * RESTAURA addons "closed-source" cuyo contenido real esta OCULTO y sus claves
 * MEZCLADAS. Mecanismo descubierto empiricamente sobre "More Body Actions +
 * Pick up & Carry" (Test 1.mcaddon), verificado caracter a caracter:
 *
 *  1) OCULTAMIENTO POR RUTA - el JSON real se guarda en una ruta ABSURDA que
 *     Bedrock NUNCA lee, y en la ruta canonica queda un senuelo:
 *       real:  render_controllers/textures/textures/models/entity/.u6.json
 *       senal: render_controllers/carryblock.json
 *     Las carpetas se repiten ad infinitum ("entity/entity/", "textures/textures").
 *
 *  2) MEZCLA DE CLAVES (anagrama por token) - en la ruta canonica cada
 *     IDENTIFICADOR es una PERMUTACION de los caracteres del original:
 *       "format_version"      -> "_rovmofeatrsin"      (14 -> 14, mismo anagrama)
 *       "render_controllers"  -> "enen_tsdlrcorerrol"  (18 -> 18)
 *     Medido: 26/27 tokens son anagramas EXACTOS; el unico que no lo es
 *     ("array.broken[q.health * 1]" -> "...q.health*1]") es solo whitespace.
 *
 * ------------------------------------------------------------------------------
 * POR QUE NO "DESCIFRAMOS" CARACTER A CARACTER
 * ------------------------------------------------------------------------------
 *
 * Un anagrama es IRREVERSIBLE sin diccionario: "_rovmofeatrsin" admite cientos
 * de permutaciones validas y Bedrock no tiene forma de saber cual era el
 * original. Cualquier "descifrado" seria ADIVINAR y produciria claves falsas:
 * JSON que el juego rechaza igual, o peor, identificadores corruptos.
 *
 * POR ESO este motor NO inventa claves: RECUPERA el archivo en claro que el
 * propio addon ya incluye en su ruta oculta y lo RESTAURA a la ruta canonica.
 * Es recuperacion con perdida de informacion CERO, no una adivinanza.
 *
 * ------------------------------------------------------------------------------
 * CONTRATO DE SEGURIDAD (NUNCA corrompe)
 * ------------------------------------------------------------------------------
 *
 * - NUNCA escribe un archivo que no sea JSON valido con clave raiz reconocida.
 * - NUNCA sobrescribe un archivo canonico que ya sea valido.
 * - Sin contraparte en claro -> NO inventa contenido: neutraliza el archivo
 *   plano a `.bak` (Bedrock ignora esa extension) y lo reporta. Se conserva
 *   la evidencia en disco, pero Bedrock deja de intentar parsearlo y de
 *   llenar el Content Log con errores de JSON en cada arranque.
 * - Idempotente: ejecutarlo dos veces produce el mismo resultado.
 */
object BedrockDecifrator {

    private const val TAG = "PackForge_Decifrador"

    /** Carpetas que NUNCA son leidas por Bedrock como raiz de contenido. */
    private val OPAQUE_ROOTS = setOf(
        "texts", "fonts", "font", "misc", "parts", "functions", "user"
    )

    /** Profundidad minima a partir de la cual una ruta se considera RELLENO. */
    private const val NOISE_DEPTH = 4

    private fun isHiddenName(name: String) = name.startsWith(".")

    /**
     * Claves raíz REALES de Bedrock. Se usan para reconocer un señuelo por
     * ANAGRAMA: el addon cerrado reordena las letras de cada clave
     * (`format_version` -> `_rovmofeatrsin`), así que la clave girada no es
     * ninguna de estas, pero sus letras sí coinciden exactamente con una.
     */
    private val KNOWN_ROOT_KEYS = setOf(
        "format_version", "render_controllers", "animation_controllers", "animations",
        "minecraft:geometry", "minecraft:client_entity", "minecraft:entity",
        "minecraft:block", "minecraft:item", "minecraft:attachable",
        "minecraft:particle_effect", "minecraft:spawn_rules", "minecraft:fog_settings",
        "minecraft:material", "minecraft:variant", "minecraft:biome",
        "minecraft:feature_rules", "minecraft:ore_feature", "minecraft:sequence_feature",
        "minecraft:single_block_feature", "minecraft:aggregate_feature",
        "minecraft:item_catalog", "minecraft:trading", "sound_definitions",
        "particles", "models", "geometry", "pools"
    )

    /** Longitud mínima para que una coincidencia de anagrama sea significativa. */
    private const val MIN_ANAGRAM_LENGTH = 6

    /** Firma ordenada (letras+dígitos) de cada clave conocida. */
    private val KNOWN_KEY_SIGNATURES: Set<String> = KNOWN_ROOT_KEYS
        .map { key -> keySignature(key) }
        .filter { it.length >= MIN_ANAGRAM_LENGTH }
        .toSet()

    private fun keySignature(value: String): String =
        value.lowercase()
            .filter { it.isLetterOrDigit() }
            .toCharArray()
            .sorted()
            .joinToString("")

    /**
     * ¿El documento es un SEÑUELO con claves mezcladas?
     *
     * Un archivo legítimo tiene claves reales (`format_version`,
     * `minecraft:geometry`…), que se descartan por ser conocidas. El señuelo
     * tiene claves giradas: ninguna es conocida, pero la mitad o más de ellas
     * son anagramas exactos de una clave real. Exigir esa proporción evita
     * falsos positivos con una clave suelta de nombre raro.
     */
    private fun looksLikeScrambledDecoy(json: JSONObject): Boolean {
        val keys = json.keys().asSequence().toList()
        if (keys.isEmpty()) return false

        val scrambled = keys.count { key ->
            if (key.lowercase() in KNOWN_ROOT_KEYS) return@count false
            val signature = keySignature(key)
            signature.length >= MIN_ANAGRAM_LENGTH && signature in KNOWN_KEY_SIGNATURES
        }

        return scrambled > 0 && scrambled * 2 >= keys.size
    }

    /** La ruta tiene un segmento repetido? (firma del relleno anti-analisis) */
    private fun hasRepeatedSegment(relPath: String): Boolean {
        val segs = relPath.split('/')
        return segs.indices.any { i -> segs.drop(i + 1).contains(segs[i]) }
    }

    /** Es esta ruta una UBICACION OCULTA (no canonica) dentro del pack? */
    private fun isBuriedPath(relPath: String): Boolean {
        val segs = relPath.split('/')
        if (segs.size < NOISE_DEPTH) return false
        return hasRepeatedSegment(relPath) ||
            segs.any { it in OPAQUE_ROOTS } ||
            isHiddenName(segs.last())
    }

    /**
     * La ruta esta en una raiz que Bedrock lee de verdad?
     *
     * RP y BP tienen raíces distintas: `entity/` (RP) vs `entities/` (BP),
     * y solo el BP tiene `blocks/`, `items/`, `recipes/`, `loot_tables/`.
     */
    private fun isCanonicalPath(rel: String, isBp: Boolean): Boolean {
        val first = rel.substringBefore('/')
        val roots = if (isBp) {
            setOf(
                "entities", "blocks", "items", "recipes",
                "loot_tables", "trading", "functions",
                // Faltaban: contenido del BP que el juego SÍ lee.
                "spawn_rules", "item_catalog", "feature_rules", "features", "biomes"
            )
        } else {
            setOf(
                "entity", "entities", "animations", "animation_controllers",
                "render_controllers", "models", "attachables", "particles",
                "blocks", "items",
                // Faltaban: contenido del RP que el juego SÍ lee.
                "fogs", "textures", "sounds"
            )
        }
        return first in roots
    }

    /**
     * Lee un JSON del pack tolerando el cebo de comentario y las claves
     * duplicadas del addon closed-source; `null` si sigue sin ser valido.
     *
     * ⚠️ Se RECORTA EL BOM UTF-8 (`\uFEFF`) antes de parsear. `readText(UTF_8)`
     * lo conserva y org.json lo trata como carácter inválido: cualquier JSON
     * guardado con BOM (muy común: Bloc, VS Code, editores de Windows) fallaba
     * al parsear, se clasificaba como "closed-source roto" y acababa
     * neutralizado a `.bak` — es decir, BORRADO del pack fusionado. Medido con
     * Alex's Mobs + New Bows: 129 bloques, 61 entidades y 249 loot_tables
     * desaparecían del .mcaddon por este motivo.
     */
    private fun readJson(file: File): JSONObject? = try {
        val text = file.readText(Charsets.UTF_8).removePrefix("\uFEFF").trimStart()
        LenientJson.parseObject(text)
    } catch (e: Exception) {
        null
    }

    /** Clave raiz que hace que un JSON sea REALMENTE util para Bedrock. */
    private fun rootSignature(json: JSONObject): String? {
        return when {
            json.has("minecraft:client_entity") -> "minecraft:client_entity"
            json.has("minecraft:geometry") -> "minecraft:geometry"
            json.has("minecraft:animation") -> "minecraft:animation"
            json.has("minecraft:controller_animation") -> "minecraft:controller_animation"
            json.has("render_controllers") -> "render_controllers"
            json.has("animation_controllers") -> "animation_controllers"
            json.has("animations") -> "animations"
            // ---- firmas propias del BEHAVIOR PACK ----
            json.has("minecraft:entity") -> "minecraft:entity"
            json.has("minecraft:block") -> "minecraft:block"
            json.has("minecraft:item") -> "minecraft:item"
            json.keys().asSequence().any { it.startsWith("minecraft:recipe_") } ->
                "minecraft:recipe"
            // ---- B10: firmas FALTANTES (contenido legítimo destruido) ----
            // Con esta lista incompleta, cualquier JSON válido de estas familias
            // caía en "sin clave raíz reconocible" → neutralizado a `.bak` →
            // fuera del .mcaddon. Se perdían loot_tables (raíz "pools"),
            // attachables, spawn_rules, partículas, fogs, biomas, features,
            // catálogos de items, trading, materiales y el sound_definitions.
            json.has("pools") -> "minecraft:loot_table"
            json.has("minecraft:loot_table") -> "minecraft:loot_table"
            json.has("minecraft:attachable") -> "minecraft:attachable"
            json.has("minecraft:spawn_rules") -> "minecraft:spawn_rules"
            json.has("minecraft:particle_effect") -> "minecraft:particle_effect"
            json.has("minecraft:fog_settings") -> "minecraft:fog"
            json.has("minecraft:biome") -> "minecraft:biome"
            json.has("minecraft:feature_rules") -> "minecraft:feature_rules"
            json.has("minecraft:aggregate_feature") -> "minecraft:feature"
            json.has("minecraft:sequence_feature") -> "minecraft:feature"
            json.has("minecraft:single_block_feature") -> "minecraft:feature"
            json.has("minecraft:ore_feature") -> "minecraft:feature"
            json.has("minecraft:item_catalog") -> "minecraft:item_catalog"
            json.has("minecraft:trading") -> "minecraft:trading"
            json.has("minecraft:material") -> "minecraft:material"
            json.has("minecraft:variant") -> "minecraft:variant"
            json.has("sound_definitions") -> "sound_definitions"
            // ---- Familias de worldgen: el BP de CoreCraft tiene cientos ----
            // Sin estas firmas, un `features/*.json` perfectamente válido caía en
            // "sin raíz reconocible" y el decifrador lo trataba como señuelo.
            json.has("minecraft:weighted_random_feature") -> "minecraft:feature"
            json.has("minecraft:scatter_feature") -> "minecraft:feature"
            json.has("minecraft:structure_template_feature") -> "minecraft:feature"
            json.has("minecraft:snap_to_surface_feature") -> "minecraft:feature"
            json.has("minecraft:growing_plant_feature") -> "minecraft:feature"
            json.has("minecraft:search_feature") -> "minecraft:feature"
            json.has("minecraft:conditional_list") -> "minecraft:feature"
            json.has("minecraft:template_pool") -> "minecraft:template_pool"
            json.has("minecraft:jigsaw") -> "minecraft:jigsaw"
            json.has("minecraft:processor_list") -> "minecraft:processor_list"
            json.has("minecraft:structure_set") -> "minecraft:structure_set"
            json.has("minecraft:client_biome") -> "minecraft:client_biome"
            json.has("minecraft:multi_noise_biome") -> "minecraft:biome"
            json.has("minecraft:color_grading_settings") -> "minecraft:color_grading"
            else -> null
        }
    }
    /**
     * Tipo de contenido que ACEPTA una ruta canonica concreta.
     *
     * Cruce estricto `directorio canonico x tipo de contenido`: impide que un
     * client_entity se restaure donde el juego espera un render controller. Sin
     * este filtro, un emparejamiento por similitud de nombre podria escribir
     * geometria donde se espera una entidad.
     */
    private fun acceptedRootsFor(dirPath: String, isBp: Boolean): Set<String> {
        // ⭐ FASE 3: se clasifica por el PRIMER SEGMENTO.
        //
        // Antes se comparaba el path COMPLETO (`d == "blocks"`), así que
        // `blocks/celestite/…`, `recipes/emerald/…`, `items/<sub>/…` y
        // `entity/<sub>/…` devolvían `emptySet()` y el bucle hacía `continue`:
        // NINGÚN archivo en subcarpeta podía emparejarse JAMÁS. De ahí
        // "restaurados: 0" en el reporte de Better MC.
        val d = dirPath.trim('/').substringBefore('/').lowercase()
        if (isBp) {
            return when (d) {
                "entities" -> setOf("minecraft:entity")
                "blocks" -> setOf("minecraft:block")
                "items" -> setOf("minecraft:item")
                "recipes" -> setOf("minecraft:recipe")
                "loot_tables" -> setOf("minecraft:loot_table")
                "trading" -> setOf("minecraft:trading")
                "spawn_rules" -> setOf("minecraft:spawn_rules")
                "item_catalog" -> setOf("minecraft:item_catalog")
                "feature_rules" -> setOf("minecraft:feature_rules")
                "features" -> setOf("minecraft:feature")
                "biomes" -> setOf("minecraft:biome")
                else -> emptySet()
            }
        }
        return when (d) {
            "entity", "entities" -> setOf("minecraft:client_entity")
            "models" -> setOf("minecraft:geometry")
            "render_controllers" -> setOf("render_controllers")
            "animation_controllers" ->
                setOf("animation_controllers", "minecraft:controller_animation")
            "animations" -> setOf("animations", "minecraft:animation")
            "attachables" -> setOf("minecraft:attachable")
            "particles" -> setOf("minecraft:particle_effect")
            "fogs" -> setOf("minecraft:fog")
            else -> emptySet()
        }
    }

    /** Trocea un identificador en palabras significativas. */
    private fun tokensOf(name: String): Set<String> {
        return name.split('.', ':', '/', '_')
            .map { it.trim().lowercase() }
            .filter { it.length > 2 && it != "minecraft" && it != "geometry" }
            .toSet()
    }

    /**
     * Identificador principal del documento, para emparejar cifrado <-> claro.
     *
     * En los CONTENEDORES (render_controllers / animation_controllers /
     * animations) la firma util no es la clave de nivel superior sino los
     * identificadores de CADA controlador de dentro: son los que comparten
     * palabras con el nombre del archivo canónico roto (p.ej.
     * `carryblock.json` <-> `controller.render.carryblock.broken`).
     */
    private fun signatureId(json: JSONObject, root: String): List<String>? {
        return when (root) {
            "minecraft:client_entity" -> listOfNotNull(
                json.optJSONObject("minecraft:client_entity")
                    ?.optJSONObject("description")?.optString("identifier")?.ifBlank { null }
            )
            "minecraft:geometry" -> geometryIds(json)
            // ---- firmas del BEHAVIOR PACK: todas cuelgan de description.identifier ----
            "minecraft:entity", "minecraft:block", "minecraft:item" ->
                listOfNotNull(
                    json.optJSONObject(root)
                        ?.optJSONObject("description")?.optString("identifier")?.ifBlank { null }
                )
            "minecraft:recipe" ->
                json.keys().asSequence()
                    .filter { it.startsWith("minecraft:recipe_") }
                    .mapNotNull { key ->
                        json.optJSONObject(key)
                            ?.optJSONObject("description")?.optString("identifier")
                            ?.ifBlank { null }
                    }
                    .toList().ifEmpty { null }
            "render_controllers", "animation_controllers", "animations" ->
                json.optJSONObject(root)
                    ?.keys()?.asSequence()?.toList()
                    ?.ifEmpty { null }
            else -> null
        }
    }

    /**
     * Identificadores de geometria.
     *
     * En Bedrock moderno `minecraft:geometry` es un ARRAY de geometrias
     * (`[{"description":{"identifier":"geometry.x"}, "bones":[...]}]`), no un
     * objeto. Tratarlo con `optJSONObject` devolvia `null`, la firma salia
     * vacia, el overlap con el nombre del roto era 0 y NUNCA se emparejaba:
     * la geometria real del addon quedaba sin restaurar. Tambien se soporta el
     * formato de objeto unico, por si aparece.
     */
    private fun geometryIds(json: JSONObject): List<String>? {
        val arr = json.optJSONArray("minecraft:geometry")
        if (arr != null) {
            return (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)
                    ?.optJSONObject("description")?.optString("identifier")
                    ?.ifBlank { null }
            }.ifEmpty { null }
        }
        return listOfNotNull(
            json.optJSONObject("minecraft:geometry")
                ?.optJSONObject("description")?.optString("identifier")?.ifBlank { null }
        )
    }

    /** Un archivo JSON en claro hallado en una ruta oculta (fuente de restauración). */
    data class ClearFile(val file: File, val relPath: String, val root: String)

    data class RestoredFile(
        val targetPath: String,
        val rootKey: String,
        val identifier: String?,
        val fromPath: String,
        val bytes: Int
    )

    data class DecryptReport(
        val restored: List<RestoredFile>,
        val unrecoverable: List<String>,
        val unusedClearFiles: Int,
        val notes: List<String>,
        /** Señuelos rotos renombrados a `.bak` tras una restauración exitosa. */
        val renamedDecoys: List<String> = emptyList(),
        /**
         * Rotos SIN contraparte en claro, neutralizados a `.bak` para que
         * Bedrock no intente parsearlos. No son señuelos: son contenido real
         * perdido, cuyo archivo plano ya no estorba en la ruta canónica.
         */
        val neutralizedUnrecoverable: List<String> = emptyList(),
        /**
         * Rotos que NI SIQUIERA se intentaron, con el motivo exacto.
         *
         * Antes eran invisibles: `acceptedRootsFor` devolvía vacío para
         * `blocks/<sub>/`, `recipes/<sub>/`, `items/<sub>/`, `entity/<sub>/` y el
         * bucle hacía `continue` en silencio. El reporte los contaba como
         * "irrecuperables", culpando al addon de un fallo del motor.
         */
        val skipped: List<Skipped> = emptyList()
    ) {
        data class Skipped(val relPath: String, val reason: String)

        val summary: String
            get() = "Decifrador: ${restored.size} restaurado(s), " +
                "${unrecoverable.size} sin contraparte en claro" +
                " (${neutralizedUnrecoverable.size} neutralizado(s) a .bak), " +
                "${renamedDecoys.size} señuelo(s) neutralizado(s), " +
                "${skipped.size} no intentado(s)"
    }
    /**
     * Restaura los archivos "closed-source" de un pack ya fusionado.
     *
     * @param rpDir Resource pack fusionado (siempre).
     * @param bpDir Behavior pack fusionado (opcional: los addons cerrados también
     *              pueden enterrar entidades/bloques/objetos/recetas en el BP).
     * @param originalAddonPaths ZIPs o carpetas de los addons ORIGINALES.
     *
     * ⭐ FASE 3 (P0 del reporte de Better MC) ⭐
     * El fusionador copia por RUTA: si el addon A trae `recipes/emerald/x.json`
     * válido y el addon B trae un señuelo en la MISMA ruta, gana el último y el
     * contenido real de A desaparece del pack fusionado. Con `originalAddonPaths`
     * ese contenido se recupera del addon original por ruta, por nombre o por
     * IDENTIFICADOR, en vez de declararlo "irrecuperable" y neutralizarlo.
     *
     * Cada raíz se procesa con su propio mapa de rutas canónicas: un `entities/`
     * de BP espera `minecraft:entity`, mientras que un `entity/` de RP espera
     * `minecraft:client_entity`. El emparejamiento NUNCA cruza entre paquetes.
     */
    fun decrypt(
        rpDir: File,
        bpDir: File? = null,
        originalAddonPaths: List<String> = emptyList()
    ): DecryptReport {
        val sources = SourceIndex.build(originalAddonPaths)
        // El índice de identificadores se comparte entre RP y BP: se construye una
        // sola vez, y solo si de verdad falta algo.
        val idHolder = IdIndexHolder()
        val rp = decryptPack(rpDir, isBp = false, sources = sources, idHolder = idHolder)
        if (bpDir == null) return rp
        val bp = decryptPack(bpDir, isBp = true, sources = sources, idHolder = idHolder)
        return DecryptReport(
            rp.restored + bp.restored,
            rp.unrecoverable + bp.unrecoverable,
            rp.unusedClearFiles + bp.unusedClearFiles,
            rp.notes + bp.notes,
            rp.renamedDecoys + bp.renamedDecoys,
            rp.neutralizedUnrecoverable + bp.neutralizedUnrecoverable,
            rp.skipped + bp.skipped
        )
    }

    /**
     * Procesa UN paquete (RP o BP): inventario -> emparejamiento -> guardas.
     *
     * Un canonico valido NUNCA se sobrescribe y un roto sin contraparte NUNCA se
     * inventa: ambos casos se devuelven en el reporte.
     */
    private fun decryptPack(
        root: File,
        isBp: Boolean,
        sources: SourceIndex = SourceIndex.build(emptyList()),
        idHolder: IdIndexHolder = IdIndexHolder()
    ): DecryptReport {
        val restored = mutableListOf<RestoredFile>()
        val unrecoverable = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val renamedDecoys = mutableListOf<String>()
        val neutralizedUnrecoverable = mutableListOf<String>()
        val skipped = mutableListOf<DecryptReport.Skipped>()
        val reasons = mutableMapOf<String, String>()
        val packLabel = if (isBp) "BP" else "RP"

        try {
            if (!root.isDirectory) {
                return DecryptReport(
                    emptyList(), emptyList(), 0,
                    listOf("$packLabel inexistente: ${root.path}")
                )
            }

            // ---- 1. INVENTARIO ----
            val broken = mutableListOf<String>()
            val clearFiles = mutableListOf<ClearFile>()

            for (file in DirIndexCache.index(root).allFiles) {
                if (!file.isFile || !file.name.endsWith(".json", true)) continue
                val rel = file.relativeTo(root).path.replace('\\', '/')

                val json = readJson(file)
                val rootKey = json?.let { rootSignature(it) }

                if (rootKey != null) {
                    // JSON valido: si esta enterrado es fuente de recuperacion.
                    if (isBuriedPath(rel)) clearFiles += ClearFile(file, rel, rootKey)
                } else if (isCanonicalPath(rel, isBp)) {
                    // ⭐ SÓLO es un ROTO si NO parsea, o si parsea pero tiene las
                    // claves mezcladas. Un JSON válido con una raíz que este motor
                    // no cataloga (texture sets con "nineslice_size", fogs…, o los
                    // feature_rules con cabecera `//bridge-file-version`) NO es un
                    // señuelo: antes entraba aquí y acababa neutralizado a `.bak`.
                    if (json == null || looksLikeScrambledDecoy(json)) broken += rel
                }
            }

            if (broken.isEmpty() && clearFiles.isEmpty()) {
                return DecryptReport(
                    emptyList(), emptyList(), 0,
                    listOf("Sin contenido closed-source")
                )
            }

            // ---- 2. EMPAREJAMIENTO ----
            // Un claro puede servir a UN solo roto: se marca como consumido para
            // que dos rutas canonicas no copien el mismo contenido.
            val consumed = mutableSetOf<String>()
            val pendingRestores = mutableListOf<Pair<RestoreSource, String>>()
            var restoredFromPack = 0

            for (brokenRel in broken) {
                val dir = File(brokenRel).parentFile?.path?.replace('\\', '/') ?: ""
                val accepted = acceptedRootsFor(dir, isBp)
                if (accepted.isEmpty()) {
                    // No se intenta (y por tanto NUNCA se neutraliza): el tipo de
                    // contenido de esta carpeta no está catalogado.
                    skipped += DecryptReport.Skipped(
                        brokenRel,
                        "carpeta canónica sin tipo de contenido catalogado; no se intentó"
                    )
                    continue
                }

                // 2a) Contraparte ENTERRADA en el propio pack fusionado.
                val local = findBuriedMatch(brokenRel, accepted, clearFiles, consumed)
                if (local != null) {
                    consumed += local.second
                    pendingRestores += local.first to brokenRel
                    restoredFromPack++
                    continue
                }

                // 2b) Contenido que el fusionador SOBRESCRIBIÓ: el addon original
                // todavía lo tiene. Se busca por ruta, por nombre y por identificador.
                val hit = recoverFromSources(brokenRel, accepted, sources, idHolder)
                if (hit.source != null) {
                    pendingRestores += hit.source to brokenRel
                } else {
                    reasons[brokenRel] = hit.reason
                }
            }

            // ---- 3. RESTAURACION CON GUARDAS ----
            for ((src, brokenRel) in pendingRestores) {
                val dir = File(brokenRel).parentFile?.path?.replace('\\', '/') ?: ""
                // GUARDA 1: el origen ya es JSON valido con raiz reconocida.
                val rootKey = src.rootKey
                if (rootSignature(src.json) != rootKey) continue

                val targetRel = buildTargetPath(dir, rootKey, File(brokenRel).name)
                val target = File(root, targetRel)

                // GUARDA 2: idempotencia, no reescribir un canonico ya valido.
                if (target.exists()) {
                    val existing = readJson(target)
                    if (existing != null && rootSignature(existing) != null) {
                        skipped += DecryptReport.Skipped(
                            brokenRel,
                            "el destino '$targetRel' ya es un JSON válido; no se toca"
                        )
                        continue
                    }
                }

                target.parentFile?.mkdirs()
                // Se vuelca el JSON NORMALIZADO: sin el cebo de comentario y sin
                // las claves duplicadas, que Bedrock rechazaria igual que org.json.
                val outText = src.json.toString()
                target.writeText(outText, Charsets.UTF_8)

                val id = signatureId(src.json, rootKey)?.joinToString(",")
                restored += RestoredFile(targetRel, rootKey, id, src.fromLabel, outText.length)
                PackForgeLog.d(TAG, "[$packLabel] Restaurado $targetRel <- ${src.fromLabel}")

                // SEÑUELO RESIDUAL: si el roto vivo en otra ruta canónica ya fue
                // superado por la restauración, Bedrock seguiría leyéndolo y
                // registraría errores en el Content Log. Se neutraliza a `.bak`
                // (extensión que Bedrock ignora), sin destruir evidencia.
                if (targetRel != brokenRel) {
                    val leftover = File(root, brokenRel)
                    if (leftover.exists()) {
                        val bak = File(root, "$brokenRel.bak")
                        if (!bak.exists() && leftover.renameTo(bak)) {
                            renamedDecoys += brokenRel
                            PackForgeLog.d(TAG, "[$packLabel] Señuelo neutralizado: $brokenRel -> .bak")
                        }
                    }
                }
            }
            // ---- 4. ROTOS SIN CONTRAPARTE ----
            // Un archivo plano en ruta canonica sin contraparte en claro no es
            // reparable, pero dejarlo peor el pack: Bedrock lo intentaria parsear
            // y registraria un error de JSON en el Content Log en cada arranque.
            // Es exactamente el mismo criterio que ya aplicamos a los señuelos
            // residuales arriba. Se neutraliza a `.bak` (extension que Bedrock
            // ignora) conservando la evidencia para diagnostico.
            val resolved = restored.map { it.targetPath }.toSet() + renamedDecoys
            // Los "no intentados" NO se tocan: no se destruye lo que ni siquiera
            // se ha intentado emparejar.
            val skippedPaths = skipped.map { it.relPath }.toSet()
            broken.filter { it !in resolved && it !in skippedPaths }.forEach { brokenRel ->
                val source = File(root, brokenRel)

                // 🛡️ RED DE SEGURIDAD: si el archivo PARSEA como JSON, es
                // contenido legítimo (una loot table con raíz "pools", un
                // attachable, un spawn_rule…) y NUNCA se neutraliza. Antes
                // bastaba con que su raíz no estuviera en una lista corta para
                // renombrarlo a `.bak`, y eso borraba del pack cientos de
                // archivos válidos del addon (ver B10 en rootSignature). Este
                // guardado convierte el decifrador en fail-open: como mucho
                // deja pasar un decoy, jamás destruye contenido real.
                val sourceJson = readJson(source)
                if (sourceJson != null && !looksLikeScrambledDecoy(sourceJson)) {
                    PackForgeLog.d(
                        TAG,
                        "[$packLabel] JSON válido sin raíz clasificada, se conserva: $brokenRel"
                    )
                    return@forEach
                }

                val reason = reasons[brokenRel] ?: NO_COPY_REASON
                val bak = File(root, "$brokenRel.bak")
                if (source.exists() && !bak.exists() && source.renameTo(bak)) {
                    neutralizedUnrecoverable += brokenRel
                    PackForgeLog.w(
                        TAG,
                        "[$packLabel] Sin contraparte en claro ($reason), neutralizado: $brokenRel -> .bak"
                    )
                }
                unrecoverable += brokenRel
                skipped += DecryptReport.Skipped(brokenRel, reason)
            }

            notes += "[$packLabel] ${clearFiles.size} archivo(s) en claro en rutas ocultas"
            notes += "[$packLabel] ${restored.size} restaurado(s) en rutas canonicas"
            if (restored.isNotEmpty()) {
                val fromAddons = restored.size - restoredFromPack
                if (fromAddons > 0) {
                    notes += "[$packLabel] $fromAddons recuperado(s) de los addons ORIGINALES " +
                        "(el fusionador había sobrescrito su ruta con un señuelo)"
                }
            }
            if (skipped.isNotEmpty()) {
                notes += "[$packLabel] ${skipped.size} no resuelto(s): " +
                    skipped.groupingBy { it.reason }.eachCount().entries
                        .joinToString(", ") { "${it.value} × ${it.key}" }
            }

            val report = DecryptReport(
                restored,
                unrecoverable,
                // Archivos en claro que quedaron sin usar (solo del pack fusionado).
                maxOf(0, clearFiles.size - restoredFromPack),
                notes,
                renamedDecoys,
                neutralizedUnrecoverable.toList(),
                skipped.toList()
            )
            PackForgeLog.d(TAG, "===== DECIFRADOR ($packLabel) =====")
            PackForgeLog.d(TAG, report.summary)
            restored.forEach { PackForgeLog.d(TAG, "  OK ${it.targetPath} <- ${it.fromPath}") }
            renamedDecoys.forEach { PackForgeLog.d(TAG, "  Señuelo -> .bak: $it") }
            unrecoverable.forEach { rel ->
                val estado = if (rel in neutralizedUnrecoverable) "neutralizado a .bak" else "intacto"
                PackForgeLog.w(TAG, "  Sin contraparte en claro ($estado): $rel")
            }
            PackForgeLog.d(TAG, "===== FIN DECIFRADOR ($packLabel) =====")

            return report
        } catch (e: Exception) {
            PackForgeLog.e(TAG, "Error en BedrockDecifrator ($packLabel): ${e.message}")
            return DecryptReport(
                emptyList(), emptyList(), 0, listOf("Error ($packLabel): ${e.message}")
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // FASE 3 — RECUPERACIÓN DESDE LOS ADDONS ORIGINALES
    // ══════════════════════════════════════════════════════════════════════════
    //
    // El fusionador copia por RUTA. Si el addon A trae `recipes/emerald/x.json`
    // válido y el addon B un señuelo en la MISMA ruta, gana el último y el
    // contenido real de A desaparece del pack fusionado. Ese contenido NO es
    // "irrecuperable": sigue en el ZIP original. Antes el decifrador solo miraba
    // el árbol ya fusionado y lo declaraba perdido.

    /** Motivo estándar cuando no hay ninguna copia en claro localizable. */
    private const val NO_COPY_REASON =
        "sin copia en claro en el pack ni en los addons originales"

    /** Documento en claro listo para restaurar, con su procedencia. */
    private class RestoreSource(
        val json: JSONObject,
        val rootKey: String,
        val fromLabel: String
    )

    /** Resultado de buscar en los addons originales: fuente o motivo. */
    private class SourceHit(val source: RestoreSource?, val reason: String)

    /** Un JSON de un addon ORIGINAL (ZIP o carpeta extraída). */
    private class SourceFile(val label: String, private val reader: () -> String?) {
        fun text(): String? = try {
            reader()
        } catch (_: Exception) {
            null
        }
    }

    /** Candidato del índice de identificadores (identificador → archivo fuente). */
    private class IdCandidate(val root: String, val file: SourceFile)

    /** Índice perezoso de identificadores: se construye solo si falta algo. */
    private class IdIndexHolder {
        var index: Map<String, List<IdCandidate>>? = null
    }

    /**
     * Índice de los addons ORIGINALES por ruta relativa, nombre de archivo y
     * (bajo demanda) identificador. Solo guarda NOMBRES; el contenido se lee al
     * usarlo, así que construir el índice es barato aunque haya 15.000 JSON.
     */
    private class SourceIndex private constructor(
        private val byRel: Map<String, List<SourceFile>>,
        private val byName: Map<String, List<SourceFile>>,
        val all: List<Pair<String, SourceFile>>
    ) {
        val isEmpty: Boolean get() = all.isEmpty()

        fun atPath(rel: String): List<SourceFile> = byRel[rel.lowercase()].orEmpty()
        fun named(name: String): List<SourceFile> = byName[name.lowercase()].orEmpty()

        companion object {
            /** Tope defensivo: ningún pack legítimo tiene más JSON que esto. */
            private const val MAX_INDEXED = 60_000

            fun build(paths: List<String>): SourceIndex {
                if (paths.isEmpty()) return SourceIndex(emptyMap(), emptyMap(), emptyList())
                val byRel = HashMap<String, MutableList<SourceFile>>()
                val byName = HashMap<String, MutableList<SourceFile>>()
                val all = mutableListOf<Pair<String, SourceFile>>()
                var count = 0

                for (path in paths) {
                    if (count >= MAX_INDEXED) break
                    val addon = File(path)
                    if (!addon.exists()) continue
                    when {
                        addon.isDirectory -> {
                            for (file in DirIndexCache.index(addon).jsonFiles) {
                                if (count++ >= MAX_INDEXED) break
                                val rel = file.relativeTo(addon).path.replace('\\', '/')
                                val sf = SourceFile("${addon.name}/$rel") { file.readText(Charsets.UTF_8) }
                                register(sf, rel, byRel, byName, all)
                            }
                        }
                        addon.extension.lowercase() in setOf("zip", "mcpack", "mcaddon") -> {
                            runCatching {
                                java.util.zip.ZipFile(addon).use { zip ->
                                    val entries = zip.entries()
                                    while (entries.hasMoreElements()) {
                                        if (count++ >= MAX_INDEXED) break
                                        val entry = entries.nextElement()
                                        if (entry.isDirectory) continue
                                        if (!entry.name.endsWith(".json", true)) continue
                                        val name = entry.name.replace('\\', '/')
                                        val sf = SourceFile("${addon.name}:$name") {
                                            java.util.zip.ZipFile(addon).use { z ->
                                                z.getInputStream(entry)?.bufferedReader(Charsets.UTF_8)?.readText()
                                            }
                                        }
                                        register(sf, name, byRel, byName, all)
                                    }
                                }
                            }.onFailure {
                                PackForgeLog.w(TAG, "No se pudo indexar $path: ${it.message}")
                            }
                        }
                    }
                }
                return SourceIndex(byRel, byName, all)
            }

            private fun register(
                sf: SourceFile,
                rel: String,
                byRel: MutableMap<String, MutableList<SourceFile>>,
                byName: MutableMap<String, MutableList<SourceFile>>,
                all: MutableList<Pair<String, SourceFile>>
            ) {
                val lower = rel.lowercase()
                byRel.getOrPut(lower) { mutableListOf() } += sf
                // El addon suele envolver el pack en una carpeta (BP_X/…): se
                // registra también sin el primer segmento.
                val stripped = lower.substringAfter('/', missingDelimiterValue = lower)
                if (stripped != lower) byRel.getOrPut(stripped) { mutableListOf() } += sf
                byName.getOrPut(lower.substringAfterLast('/')) { mutableListOf() } += sf
                all += lower to sf
            }
        }
    }

    /** Parsea texto tolerando BOM y el cebo de comentario. */
    private fun parseJsonText(text: String): JSONObject? = try {
        LenientJson.parseObject(text.removePrefix("\uFEFF").trimStart())
    } catch (_: Exception) {
        null
    }

    /**
     * Empareja un roto con una copia en claro ENTERRADA en el pack fusionado
     * (el modelo "señuelo en ruta canónica + original oculto" del addon cerrado).
     *
     * @return fuente + ruta del claro consumido, o `null` si no hay candidato.
     */
    private fun findBuriedMatch(
        brokenRel: String,
        accepted: Set<String>,
        clearFiles: List<ClearFile>,
        consumed: Set<String>
    ): Pair<RestoreSource, String>? {
        val stemTokens = tokensOf(File(brokenRel).name.removeSuffix(".json"))
        var best: ClearFile? = null
        var bestScore = 0
        val typedCandidates = mutableListOf<ClearFile>()

        for (cf in clearFiles) {
            if (cf.relPath in consumed) continue
            if (cf.root !in accepted) continue
            typedCandidates += cf

            val ids = readJson(cf.file)?.let { signatureId(it, cf.root) }.orEmpty()
            val idTokens = ids.flatMap { tokensOf(it) }.toSet()
            val overlap = (stemTokens intersect idTokens).size
            if (overlap == 0) continue
            if (overlap > bestScore) {
                bestScore = overlap
                best = cf
            }
        }
        // Respaldo: si solo existe UN candidato de este tipo, es el único posible.
        if (best == null && typedCandidates.size == 1) best = typedCandidates.first()
        val chosen = best ?: return null
        val json = readJson(chosen.file) ?: return null
        return RestoreSource(json, chosen.root, chosen.relPath) to chosen.relPath
    }

    /**
     * Busca la contraparte de un roto en los addons ORIGINALES, en este orden:
     *
     *  1. La MISMA ruta relativa (el fusionador la sobrescribió con un señuelo).
     *  2. El MISMO nombre de archivo en cualquier otra ruta del addon.
     *  3. El MISMO identificador, para modelos con nombre ofuscado
     *     (`2jm1s73o.json` define `geometry.cc.villager_baby`).
     *
     * Nunca elige si hay ambigüedad: dos candidatos posibles se reportan como
     * tales en lugar de adivinar (que es como se corrompen los packs).
     */
    private fun recoverFromSources(
        brokenRel: String,
        accepted: Set<String>,
        sources: SourceIndex,
        idHolder: IdIndexHolder
    ): SourceHit {
        if (sources.isEmpty) return SourceHit(null, NO_COPY_REASON)

        // (1) Misma ruta.
        for (sf in sources.atPath(brokenRel)) {
            val json = sf.text()?.let { parseJsonText(it) } ?: continue
            val root = rootSignature(json) ?: continue
            if (root !in accepted) continue
            return SourceHit(RestoreSource(json, root, sf.label), "")
        }

        // (2) Mismo nombre de archivo.
        val fileName = File(brokenRel).name
        val byName = sources.named(fileName).mapNotNull { sf ->
            val json = sf.text()?.let { parseJsonText(it) } ?: return@mapNotNull null
            val root = rootSignature(json) ?: return@mapNotNull null
            if (root !in accepted) return@mapNotNull null
            RestoreSource(json, root, sf.label)
        }.distinctBy { it.fromLabel }

        if (byName.size == 1) return SourceHit(byName.first(), "")
        if (byName.size > 1) {
            val stemTokens = tokensOf(fileName.removeSuffix(".json"))
            val scored = byName.map { cand ->
                val idTokens = signatureId(cand.json, cand.rootKey)
                    .orEmpty().flatMap { tokensOf(it) }.toSet()
                cand to (stemTokens intersect idTokens).size
            }
            val bestScore = scored.maxOfOrNull { it.second } ?: 0
            val winners = scored.filter { it.second == bestScore && it.second > 0 }
            if (winners.size == 1) return SourceHit(winners.first().first, "")
            return SourceHit(
                null,
                "${byName.size} candidatos con el nombre '$fileName' en los addons originales (ambiguo)"
            )
        }

        // (3) Mismo identificador (nombres ofuscados).
        val index = idHolder.index ?: buildIdIndex(sources).also { idHolder.index = it }
        val suffix = File(brokenRel).name.removeSuffix(".json").lowercase()
        val candidates = index[suffix].orEmpty().filter { it.root in accepted }
        if (candidates.size == 1) {
            val cand = candidates.first()
            val json = cand.file.text()?.let { parseJsonText(it) }
                ?: return SourceHit(null, "candidato ilegible en el addon original")
            return SourceHit(RestoreSource(json, cand.root, cand.file.label), "")
        }
        if (candidates.size > 1) {
            return SourceHit(
                null,
                "${candidates.size} candidatos con el identificador '$suffix' (ambiguo)"
            )
        }
        return SourceHit(null, NO_COPY_REASON)
    }

    /**
     * Índice `última-parte-del-identificador → candidato`, construido en una sola
     * pasada sobre los addons originales. Es lo que permite recuperar modelos
     * cuyo ARCHIVO tiene nombre ofuscado (`pqbf68ao.json` → `geometry.cc.apple`).
     */
    private fun buildIdIndex(sources: SourceIndex): Map<String, List<IdCandidate>> {
        val out = HashMap<String, MutableList<IdCandidate>>()
        for ((_, sf) in sources.all) {
            val json = sf.text()?.let { parseJsonText(it) } ?: continue
            val root = rootSignature(json) ?: continue
            val ids = signatureId(json, root) ?: continue
            for (id in ids) {
                val suffix = id.substringAfterLast('.').substringAfterLast(':').lowercase()
                if (suffix.length < 4) continue
                out.getOrPut(suffix) { mutableListOf() } += IdCandidate(root, sf)
            }
        }
        PackForgeLog.d(TAG, "Índice de identificadores de addons originales: ${out.size} entradas")
        return out
    }

    /**
     * Construye la ruta canonica destino respetando la convencion de Bedrock:
     * entidades y geometrias exigen un sufijo de tipo explicito.
     */
    private fun buildTargetPath(dir: String, rootKey: String, brokenName: String): String {
        val stem = brokenName.removeSuffix(".json")
            .removeSuffix(".entity").removeSuffix(".render_controller")
            .removeSuffix(".render_controllers").removeSuffix(".controller_animation")
        val suffix = when (rootKey) {
            "minecraft:client_entity", "minecraft:entity" -> ".entity.json"
            "minecraft:geometry" -> ".geometry.json"
            "minecraft:controller_animation" -> ".animation_controllers.json"
            else -> ".json"
        }
        return "$dir/$stem$suffix"
    }
}