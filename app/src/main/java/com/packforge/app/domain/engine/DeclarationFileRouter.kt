package com.packforge.app.domain.engine

import com.packforge.app.domain.model.ConflictSeverity
import com.packforge.app.util.PackForgeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * ENRUTADOR DE ARCHIVOS DE DECLARACIÓN ÚNICA
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Hay archivos de Bedrock cuyo objeto raíz es **fijo** y contiene UNA sola
 * declaración con `description.identifier`:
 *
 *  · `entity/<archivo>.json`       → `minecraft:client_entity`
 *  · `attachables/<archivo>.json`  → `minecraft:attachable`
 *
 * En esos archivos la **identidad es el identifier**, no la ruta. Fusionarlos
 * por nombre de archivo (lo que hacía el pipeline) tiene dos fallos opuestos:
 *
 *  1. Dos addons con `entity/mob.json` pero identifiers distintos se fusionan
 *     sobre el MISMO objeto raíz: el `identifier` es una colisión de escalar y
 *     gana uno, así que el cliente de la otra entidad desaparece. Su
 *     `minecraft:entity` del BP siguió existiendo con otro id → **mob invisible**
 *     (el fallo que la app existe para evitar).
 *  2. El mismo identifier repartido en dos archivos distintos (nombres
 *     ofuscados, `mob.entity.json` vs `mob.json`) quedaba duplicado en el pack:
 *     Bedrock descarta uno en silencio.
 *
 * Este enrutador agrupa por identifier declarado y escribe **un archivo por
 * identifier**. Distintos identifiers que pedían el mismo nombre se separan con
 * sufijo `_pf<hash>` (estable, derivado del identifier).
 *
 * Semántica de prioridad (la del contrato del producto: el último addon gana):
 *  · mismo identifier custom en varios addons → fusión profunda secuencial
 *    (el último sobrescribe) + conflicto MEDIUM para que el usuario lo vea;
 *  · mismo identifier `minecraft:*` → **reemplazo**, no fusión: mezclar dos
 *    overrides vanilla produce texturas/geometrías híbridas incoherentes.
 *
 * No se ocupa de `animations/`, `render_controllers/`: allí la raíz es un
 * contenedor (`{"animations": {...}}`) y dos archivos con nombres distintos
 * conservan ambos ids; el riesgo de pérdida no existe.
 */
object DeclarationFileRouter {

    private const val TAG = "PackForge_Router"

    /** Lecturas concurrentes acotadas: móvil primero (memoria y antivirus). */
    private val PARALELISMO_LECTURA: Int =
        Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    data class RouteResult(
        val written: Int = 0,
        val identificados: Int = 0,
        val fusionados: Int = 0,
        val divididos: Int = 0,
        val sinIdentificador: Int = 0,
        val reemplazosVanilla: Int = 0,
        /** Copias obsoletas borradas tras consolidar un identifier en un archivo. */
        val consolidados: Int = 0
    )

    private data class Candidato(
        val archivo: File,
        val relPath: String,
        val addon: String,
        val orden: Int
    )

    private data class Aporte(
        val archivo: File,
        val relPath: String,
        val addon: String,
        val orden: Int,
        val json: JSONObject?,
        /** Texto original cuando el archivo NO es una declaración (se copia tal cual). */
        val crudo: String? = null
    )

    /**
     * Enruta y escribe [subcarpeta] desde [sourceDirs] a [destRoot].
     *
     * @param claveDeclaracion clave raíz que declara el contenido
     *        (`minecraft:client_entity`, `minecraft:attachable`).
     */
    suspend fun route(
        sourceDirs: List<File>,
        destRoot: File,
        subcarpeta: String,
        claveDeclaracion: String
    ): RouteResult = coroutineScope {
        val destDir = File(destRoot, subcarpeta)
        destDir.mkdirs()

        val prefijo = "$subcarpeta/"
        val candidatos = buildList {
            var orden = 0
            for (src in sourceDirs) {
                if (!src.isDirectory) continue
                val addon = etiquetaDe(src)
                for (file in DirIndexCache.index(src).jsonFiles) {
                    val rel = file.relativeTo(src).path.replace('\\', '/')
                    if (!rel.startsWith(prefijo)) continue
                    // Un manifiesto suelto dentro de entity/ confundiría a Bedrock
                    // (escanea `manifest.json` por subcarpeta) y la basura de
                    // desarrollo no forma parte del pack.
                    if (file.name.equals("manifest.json", ignoreCase = true) ||
                        file.name.startsWith(".") ||
                        file.name.startsWith("manifest")
                    ) continue
                    add(Candidato(file, rel, addon, orden++))
                }
            }
        }
        if (candidatos.isEmpty()) return@coroutineScope RouteResult()

        // 1) Lectura + parseo acotado. Filtro barato de texto antes de parsear:
        //    un archivo no puede declarar lo que no menciona. Los que no la
        //    mencionan NO son declaraciones: se conservan por ruta (antes se
        //    perdían, porque `entity/` está excluido de la copia genérica).
        val permisos = Semaphore(PARALELISMO_LECTURA)
        val lecturas = candidatos.map { c ->
            async(Dispatchers.IO) {
                permisos.withPermit {
                    val texto = runCatching { c.archivo.readText(StandardCharsets.UTF_8) }
                        .getOrNull() ?: return@withPermit null
                    if (!texto.contains(claveDeclaracion)) {
                        return@withPermit Aporte(c.archivo, c.relPath, c.addon, c.orden, null, texto)
                    }
                    // Vía rápida org.json; LenientJson como respaldo para los
                    // addons con comentarios internos (JSON no estricto).
                    val json = runCatching { JSONObject(texto) }.getOrNull()
                        ?: LenientJson.parseObject(texto)
                        // Declara pero no parsea: no se puede enrutar y copiarlo
                        // roto solo trasladaría el error al pack final.
                        ?: return@withPermit null
                    Aporte(c.archivo, c.relPath, c.addon, c.orden, JsonDeepMerger.cleanJsonObject(json))
                }
            }
        }.awaitAll().filterNotNull().sortedBy { it.orden }
        val aportes = lecturas.filter { it.json != null }
        val sinIdentificador = lecturas.filter { it.json == null }.toMutableList()

        // 2) Agrupar por identifier declarado.
        val grupos = LinkedHashMap<String, MutableList<Aporte>>()
        for (aporte in aportes) {
            val id = aporte.json?.let { identifierOf(it, claveDeclaracion) }
            if (id == null) {
                // Declara la clave pero sin identifier: no se puede enrutar por
                // identidad, así que se conserva por ruta.
                sinIdentificador += aporte
            } else {
                grupos.getOrPut(id) { mutableListOf() } += aporte
            }
        }

        // 3) Un archivo por identifier.
        val rutasUsadas = HashSet<String>()
        // Rutas de entrada que declaraban un identifier y salida elegida para
        // cada una. La copia eager de Fase 1 ya dejó estos archivos en el
        // destino: si un identifier estaba repartido en dos nombres distintos,
        // la copia del nombre no elegido quedaría ahí y el pack tendría la
        // definición DUPLICADA (Bedrock descarta una en silencio).
        val relsEnrutados = HashSet<String>()
        val relsEscritos = HashSet<String>()
        var escritos = 0
        var fusionados = 0
        var divididos = 0
        var reemplazosVanilla = 0

        for ((id, lista) in grupos) {
            val primero = lista.first()
            lista.forEach { relsEnrutados.add(it.relPath.removePrefix(prefijo)) }
            val relOriginal = primero.relPath.removePrefix(prefijo)
            val rel = if (rutasUsadas.add(relOriginal)) {
                relOriginal
            } else {
                divididos++
                val unico = rutaUnica(relOriginal, id, rutasUsadas)
                rutasUsadas.add(unico)
                unico
            }

            val jsonFinal = when {
                lista.size == 1 -> primero.json!!

                // Override vanilla: reemplazo íntegro del último addon.
                id.startsWith("minecraft:") -> {
                    reemplazosVanilla++
                    PackForgeLog.d(
                        TAG,
                        "🟦 '$id' redefinido por ${lista.size} addons: se aplica el último " +
                            "(${lista.last().addon}), sin mezclar definiciones vanilla"
                    )
                    lista.last().json!!
                }

                else -> {
                    fusionados++
                    val duenios = lista.joinToString(" + ") { it.addon }
                    ConflictRegistry.logConflict(
                        severity = ConflictSeverity.MEDIUM,
                        type = "ENTITY_IDENTIFIER_MERGED",
                        file = "$subcarpeta/$rel",
                        addon1 = lista.first().addon,
                        addon2 = lista.last().addon,
                        description = "El identifier '$id' está declarado por $duenios; " +
                            "se fusionó por componente y gana el último. " +
                            "Puedes elegir otro ganador en la pantalla de Conflictos."
                    )
                    var base = primero.json!!
                    for (i in 1 until lista.size) {
                        val aporte = lista[i]
                        JsonDeepMerger.setMergeContext(aporte.addon, "merged", "$subcarpeta/$rel")
                        base = JsonDeepMerger.deepMerge(base, aporte.json!!)
                    }
                    JsonDeepMerger.clearPriorityContext()

                    // 3.6 — El resultado declara UN `format_version` (el del
                    // último que lo traiga). Si los aportantes declaran
                    // esquemas distintos, el contenido del otro se reinterpreta
                    // bajo ese esquema sin aviso: se negocia y se reporta, sin
                    // tocar la versión (regla 16).
                    FormatVersionNegotiator.negotiate(
                        filePath = "$subcarpeta/$rel",
                        identifier = id,
                        aportantes = lista.map {
                            FormatVersionNegotiator.Aportante(
                                it.addon,
                                SchemaNormalizer.formatVersionOf(it.json!!)
                            )
                        },
                        mantenida = SchemaNormalizer.formatVersionOf(base)
                    )
                    base
                }
            }

            escribir(destDir, rel, jsonFinal)
            relsEscritos.add(rel)
            escritos++
        }

        // 4) Archivos sin identifier: se conservan por ruta (antes se perdían,
        //    porque `entity/` está excluido de la copia genérica).
        for (aporte in sinIdentificador) {
            val rel = aporte.relPath.removePrefix(prefijo)
            if (!rutasUsadas.add(rel)) {
                PackForgeLog.w(
                    TAG,
                    "Archivo sin identifier descartado por colisión de ruta: $subcarpeta/$rel"
                )
                continue
            }
            if (aporte.json != null) escribir(destDir, rel, aporte.json)
            else escribirCrudo(destDir, rel, aporte.crudo.orEmpty())
            relsEscritos.add(rel)
            escritos++
        }

        // 3b) Borrar las copias obsoletas de la Fase 1. Solo se tocan rutas que
        // ESTA pasada leyó como declaraciones: nunca archivos ajenos.
        var consolidados = 0
        for (rel in relsEnrutados) {
            if (rel in relsEscritos) continue
            val obsoleto = File(destDir, rel)
            if (obsoleto.isFile && obsoleto.delete()) {
                consolidados++
                PackForgeLog.d(
                    TAG,
                    "🧹 Copia obsoleta borrada en $subcarpeta/$rel (su identifier se consolidó " +
                        "en otro archivo)"
                )
            }
        }

        // El contenido del destino cambió: el índice cacheado ya no es válido.
        DirIndexCache.invalidate(destRoot)

        val resultado = RouteResult(
            written = escritos,
            identificados = grupos.size,
            fusionados = fusionados,
            divididos = divididos,
            sinIdentificador = sinIdentificador.size,
            reemplazosVanilla = reemplazosVanilla,
            consolidados = consolidados
        )
        PackForgeLog.d(
            TAG,
            "✅ $subcarpeta: ${resultado.written} archivo(s) para " +
                "${resultado.identificados} identifier(s) · ${resultado.fusionados} fusionado(s) · " +
                "${resultado.divididos} separado(s) por nombre repetido · " +
                "${resultado.reemplazosVanilla} override(s) vanilla" +
                if (resultado.consolidados > 0) {
                    " · ${resultado.consolidados} copia(s) obsoleta(s) borrada(s)"
                } else ""
        )
        resultado
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Identifier declarado por el objeto raiz. La mayoria usa
     * `description.identifier`, pero `minecraft:camera_preset` lo pone en la
     * RAIZ del objeto: sin este respaldo sus ids no se enrutaban y dos addons
     * con la misma camara se pisaban.
     */
    private fun identifierOf(json: JSONObject, clave: String): String? {
        val nodo = json.optJSONObject(clave) ?: return null
        val id = nodo.optJSONObject("description")?.optString("identifier")
            ?.takeIf { it.isNotBlank() }
            ?: nodo.optString("identifier")?.takeIf { it.isNotBlank() }
        return id?.trim()
    }

    /** `mob.json` ocupado por otro identifier → `mob_pf<hash>.json` (estable). */
    private fun rutaUnica(relPath: String, identifier: String, usadas: Set<String>): String {
        val carpeta = relPath.substringBeforeLast('/', "")
        val nombre = relPath.substringAfterLast('/')
        val punto = nombre.lastIndexOf('.')
        val stem = if (punto > 0) nombre.substring(0, punto) else nombre
        val ext = if (punto > 0) nombre.substring(punto) else ""
        var candidato = "${stem}_pf${tokenDe(identifier)}$ext"
        var intento = 1
        while (true) {
            val ruta = if (carpeta.isEmpty()) candidato else "$carpeta/$candidato"
            if (ruta !in usadas) return ruta
            intento++
            candidato = "${stem}_pf${tokenDe(identifier)}$intento$ext"
        }
    }

    /** Token estable entre exportaciones del mismo identifier (no de la ruta). */
    private fun tokenDe(identifier: String): String = try {
        val digest = MessageDigest.getInstance("SHA-256").digest(identifier.toByteArray(StandardCharsets.UTF_8))
        val sb = StringBuilder(12)
        for (i in 0 until 6) sb.append("%02x".format(digest[i]))
        sb.toString()
    } catch (_: Exception) {
        identifier.hashCode().toUInt().toString(16).padStart(6, '0').take(6)
    }

    private fun escribir(destDir: File, rel: String, json: JSONObject) {
        val destino = File(destDir, rel)
        destino.parentFile?.mkdirs()
        OutputStreamWriter(FileOutputStream(destino), StandardCharsets.UTF_8).use {
            it.write(json.toString())
        }
    }

    /** Copia byte a byte del texto original: no se reinterpreta lo que no es declaración. */
    private fun escribirCrudo(destDir: File, rel: String, texto: String) {
        val destino = File(destDir, rel)
        destino.parentFile?.mkdirs()
        destino.writeText(texto, StandardCharsets.UTF_8)
    }

    private fun etiquetaDe(dir: File): String =
        AddonOwnerRegistry.ownerOf(dir)
            ?.let { AddonOwnerRegistry.labelOf(it) }
            ?: dir.name
}
