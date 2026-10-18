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
                "loot_tables", "trading", "functions"
            )
        } else {
            setOf(
                "entity", "entities", "animations", "animation_controllers",
                "render_controllers", "models", "attachables", "particles",
                "blocks", "items"
            )
        }
        return first in roots
    }

    /**
     * Lee un JSON del pack tolerando el cebo de comentario y las claves
     * duplicadas del addon closed-source; `null` si sigue sin ser valido.
     */
    private fun readJson(file: File): JSONObject? = try {
        LenientJson.parseObject(file.readText(Charsets.UTF_8))
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
        val d = dirPath.trim('/')
        if (isBp) {
            return when {
                d == "entities" -> setOf("minecraft:entity")
                d == "blocks" -> setOf("minecraft:block")
                d == "items" -> setOf("minecraft:item")
                d == "recipes" -> setOf("minecraft:recipe")
                else -> emptySet()
            }
        }
        return when {
            d == "entity" || d == "entities" -> setOf("minecraft:client_entity")
            d == "models" || d.startsWith("models/") -> setOf("minecraft:geometry")
            d == "render_controllers" -> setOf("render_controllers")
            d == "animation_controllers" ->
                setOf("animation_controllers", "minecraft:controller_animation")
            d == "animations" -> setOf("animations", "minecraft:animation")
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

    /** Pareja roto-en-cánónico <-> claro-enterrado, ya validada por tipo. */
    data class Match(val brokenRel: String, val clear: ClearFile)

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
        val neutralizedUnrecoverable: List<String> = emptyList()
    ) {
        val summary: String
            get() = "Decifrador: ${restored.size} restaurado(s), " +
                "${unrecoverable.size} sin contraparte en claro" +
                " (${neutralizedUnrecoverable.size} neutralizado(s) a .bak), " +
                "${renamedDecoys.size} señuelo(s) neutralizado(s)"
    }
    /**
     * Restaura los archivos "closed-source" de un pack ya fusionado.
     *
     * @param rpDir Resource pack fusionado (siempre).
     * @param bpDir Behavior pack fusionado (opcional: los addons cerrados también
     *              pueden enterrar entidades/bloques/objetos/recetas en el BP).
     *
     * Cada raíz se procesa con su propio mapa de rutas canónicas: un `entities/`
     * de BP espera `minecraft:entity`, mientras que un `entity/` de RP espera
     * `minecraft:client_entity`. El emparejamiento NUNCA cruza entre paquetes.
     */
    fun decrypt(rpDir: File, bpDir: File? = null): DecryptReport {
        val rp = decryptPack(rpDir, isBp = false)
        if (bpDir == null) return rp
        val bp = decryptPack(bpDir, isBp = true)
        return DecryptReport(
            rp.restored + bp.restored,
            rp.unrecoverable + bp.unrecoverable,
            rp.unusedClearFiles + bp.unusedClearFiles,
            rp.notes + bp.notes,
            rp.renamedDecoys + bp.renamedDecoys,
            rp.neutralizedUnrecoverable + bp.neutralizedUnrecoverable
        )
    }

    /**
     * Procesa UN paquete (RP o BP): inventario -> emparejamiento -> guardas.
     *
     * Un canonico valido NUNCA se sobrescribe y un roto sin contraparte NUNCA se
     * inventa: ambos casos se devuelven en el reporte.
     */
    private fun decryptPack(root: File, isBp: Boolean): DecryptReport {
        val restored = mutableListOf<RestoredFile>()
        val unrecoverable = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val renamedDecoys = mutableListOf<String>()
        val neutralizedUnrecoverable = mutableListOf<String>()
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
                val root = json?.let { rootSignature(it) }

                if (root != null) {
                    // JSON valido: si esta enterrado es fuente de recuperacion.
                    if (isBuriedPath(rel)) clearFiles += ClearFile(file, rel, root)
                } else if (isCanonicalPath(rel, isBp)) {
                    // Sin clave raiz reconocible en ruta que Bedrock lee = roto.
                    broken += rel
                }
            }

            if (broken.isEmpty() && clearFiles.isEmpty()) {
                return DecryptReport(
                    emptyList(), emptyList(), 0,
                    listOf("Sin contenido closed-source")
                )
            }
            // ---- 2. EMPAREJAMIENTO GLOBAL ----
            // Un claro puede servir a UN solo roto: se marca como consumido para
            // que dos rutas canonicas no copien el mismo contenido.
            val consumed = mutableSetOf<String>()
            val pairs = mutableListOf<Match>()

            for (brokenRel in broken) {
                val dir = File(brokenRel).parentFile?.path?.replace('\\', '/') ?: ""
                val accepted = acceptedRootsFor(dir, isBp)
                if (accepted.isEmpty()) continue

                val stem = File(brokenRel).name.removeSuffix(".json")
                val stemTokens = tokensOf(stem)

                var best: ClearFile? = null
                var bestScore = 0
                // Cada archivo en claro que encaje por TIPO, aunque su nombre
                // no comparta palabras: se usa como respaldo si es el único.
                val typedCandidates = mutableListOf<ClearFile>()

                for (cf in clearFiles) {
                    if (cf.relPath in consumed) continue
                    if (cf.root !in accepted) continue
                    typedCandidates += cf

                    val ids = readJson(cf.file)?.let { signatureId(it, cf.root) } ?: emptyList()

                    // Afinidad = palabras del nombre del roto presentes en el id.
                    val idTokens = ids.flatMap { tokensOf(it) }.toSet()
                    val overlap = (stemTokens intersect idTokens).size
                    if (overlap == 0) continue
                    if (overlap > bestScore) {
                        bestScore = overlap
                        best = cf
                    }
                }

                // Respaldo: si no hay coincidencia por nombre pero solo existe
                // UN candidato de este tipo en el pack, es el único posible.
                if (best == null && typedCandidates.size == 1) best = typedCandidates.first()

                if (best != null) {
                    pairs += Match(brokenRel, best)
                    consumed += best.relPath
                }
            }
            // ---- 3. RESTAURACION CON GUARDAS ----
            for (match in pairs) {
                val brokenRel = match.brokenRel
                val cf = match.clear
                val rootKey = cf.root
                val dir = File(brokenRel).parentFile?.path?.replace('\\', '/') ?: ""
                // El origen se PARSEA tolerando el cebo y las claves duplicadas,
                // y se ESCRIBE normalizado. Si se volcara el texto crudo, Bedrock
                // rechazaria el archivo por las claves duplicadas, igual que lo
                // hace org.json.
                val srcJson = readJson(cf.file) ?: continue

                // GUARDA 1: el origen debe ser JSON valido con raiz reconocida.
                if (srcJson == null || rootSignature(srcJson) == null) continue

                val targetRel = buildTargetPath(dir, rootKey, File(brokenRel).name)
                val target = File(root, targetRel)

                // GUARDA 2: idempotencia, no reescribir un canonico ya valido.
                if (target.exists()) {
                    val existing = readJson(target)
                    if (existing != null && rootSignature(existing) != null) continue
                }

                // GUARDA 3: la firma restaurada debe ser la esperada para esa ruta.
                if (rootSignature(srcJson) != rootKey) continue

                target.parentFile?.mkdirs()
                // Se vuelca el JSON NORMALIZADO: sin el cebo de comentario y sin
                // las claves duplicadas, que Bedrock rechazaria igual que org.json.
                val outText = srcJson.toString()
                target.writeText(outText, Charsets.UTF_8)

                val id = signatureId(srcJson, rootKey)?.joinToString(",")
                restored += RestoredFile(targetRel, rootKey, id, cf.relPath, outText.length)
                PackForgeLog.d(TAG, "[$packLabel] Restaurado $targetRel <- ${cf.relPath}")

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
            broken.filter { it !in resolved }.forEach { brokenRel ->
                val source = File(root, brokenRel)
                val bak = File(root, "$brokenRel.bak")
                if (source.exists() && !bak.exists() && source.renameTo(bak)) {
                    neutralizedUnrecoverable += brokenRel
                    PackForgeLog.w(
                        TAG,
                        "[$packLabel] Sin contraparte en claro, neutralizado: $brokenRel -> .bak"
                    )
                }
                unrecoverable += brokenRel
            }

            notes += "[$packLabel] ${clearFiles.size} archivo(s) en claro en rutas ocultas"
            notes += "[$packLabel] ${restored.size} restaurado(s) en rutas canonicas"

            val report = DecryptReport(
                restored, unrecoverable, clearFiles.size - restored.size, notes, renamedDecoys,
                neutralizedUnrecoverable.toList()
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