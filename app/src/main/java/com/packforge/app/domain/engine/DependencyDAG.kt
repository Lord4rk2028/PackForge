package com.packforge.app.domain.engine

import com.packforge.app.util.PackForgeLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * ════════════════════════════════════════════════════════════════════════════
 * GRAFO DIRIGIDO DE DEPENDENCIAS (TICKET-CORE-012)
 * ════════════════════════════════════════════════════════════════════════════
 *
 * Modela la cadena completa de activos de una entidad de Bedrock:
 *
 *   `minecraft:entity` (BP) ─→ `minecraft:client_entity` (RP)
 *        └─→ render_controller ─→ geometry
 *        └─→ texture
 *        └─→ animation / animation controller
 *        └─→ particle
 *
 * y responde a la pregunta que ningún validador respondía: **¿de quién es el
 * eslabón roto?** Cada nodo y cada arista llevan el addon de origen, así que un
 * hueco se puede atribuir al addon que lo causó. Es la diferencia entre:
 *
 *   · «referencia rota tras fusión»  ← culpa al motor, sin base
 *   · «ShulkerHeadBeh cita X, que no aporta ningún addon»  ← accionable
 *
 * ── Consolidación, no una cuarta opinión ─────────────────────────────────
 *
 * Ya existían tres recorridos de esta misma cadena (`EntityDependencyResolver`,
 * `DependencyGraphResolver`, `MergeReferenceValidator`) y AGENTS.md §6 avisa de
 * que duplicar lógica produce «validadores con criterios divergentes» —que ya
 * pasó, con miles de falsos positivos—. Por eso este grafo **no parsea nada por
 * su cuenta**: reutiliza `GeometryAnimRCIndex` (el índice compartido) para las
 * definiciones y `VanillaAssets` para decidir si un hueco lo cubre el juego.
 *
 * ── Solo lectura ─────────────────────────────────────────────────────────
 *
 * `build` no escribe ni neutraliza nada. La reparación sigue viviendo en
 * `EntityDependencyResolver` y `PackForgeHealer`. Este objeto diagnostica.
 */
object DependencyDAG {

    private const val TAG = "PackForge_DAG"

    /** Tipo de nodo del grafo. */
    enum class Kind(val etiqueta: String) {
        ENTITY("entidad (BP)"),
        CLIENT_ENTITY("client_entity (RP)"),
        RENDER_CONTROLLER("render controller"),
        GEOMETRY("geometría"),
        ANIMATION("animación"),
        TEXTURE("textura"),
        PARTICLE("partícula")
    }

    /** Un addon de entrada, ya extraído. */
    data class Source(val addonName: String, val root: File, val esBehavior: Boolean)

    /**
     * Eslabón que falta: [id] lo referencia [referencedBy] (fichero del addon
     * [owners]) y **ningún** addon lo define.
     */
    data class Gap(
        val kind: Kind,
        val id: String,
        val referencedBy: String,
        val owners: Set<String>
    ) {
        fun describe(): String =
            "${kind.etiqueta} '$id' citada por $referencedBy " +
                "(addon: ${owners.joinToString()}) — ningún addon la aporta"
    }

    /**
     * Resultado del grafo.
     *
     * @param gaps eslabones que faltan de verdad (ni addon ni vanilla)
     * @param entidadesSinCliente `minecraft:entity` custom sin `client_entity`
     *        (el mob existe pero no se dibuja)
     * @param clientesSinEntidad `client_entity` custom sin `minecraft:entity`
     *        (contenido muerto: nunca aparecerá, ni con el addon original)
     */
    data class Graph(
        val declarados: Map<Kind, Set<String>>,
        val gaps: List<Gap>,
        val entidadesSinCliente: Set<String>,
        val clientesSinEntidad: Set<String>
    ) {
        /** Huecos atribuibles a un addon concreto. */
        fun gapsDe(addon: String): List<Gap> = gaps.filter { addon in it.owners }

        /** Addons que introducen algún hueco, con su recuento (de mayor a menor). */
        fun huecosPorAddon(): List<Pair<String, Int>> =
            gaps.flatMap { g -> g.owners.map { it to g } }
                .groupingBy { it.first }
                .eachCount()
                .toList()
                .sortedByDescending { it.second }

        /** Líneas para `fusion_report.txt`. */
        fun resumen(maxLineas: Int = 12): List<String> {
            val out = mutableListOf<String>()
            out.add(
                "Grafo de dependencias: ${declarados[Kind.ENTITY]?.size ?: 0} entidad(es), " +
                    "${declarados[Kind.CLIENT_ENTITY]?.size ?: 0} client_entity, " +
                    "${gaps.size} eslabón(es) roto(s)"
            )
            if (clientesSinEntidad.isNotEmpty()) {
                // Defecto DEL ADDON: no aparece ni instalándolo solo.
                out.add(
                    "⚪ ${clientesSinEntidad.size} client_entity sin definición en BP " +
                        "(contenido muerto del origen, no lo rompe la fusión): " +
                        clientesSinEntidad.take(6).joinToString()
                )
            }
            if (entidadesSinCliente.isNotEmpty()) {
                out.add(
                    "⚪ ${entidadesSinCliente.size} entidad(es) custom sin client_entity en el pack: " +
                        "no se dibujan por sí mismas (puede ser INTENCIONADO en entidades marcador o " +
                        "de datos; compruébalo con el registro de contenido): " +
                        entidadesSinCliente.take(6).joinToString()
                )
            }
            huecosPorAddon().take(maxLineas).forEach { (addon, n) ->
                out.add("   • $addon: $n eslabón(es) roto(s)")
            }
            return out
        }
    }

    // ── Construcción ─────────────────────────────────────────────────────────

    fun build(sources: List<Source>, indiceCompartido: GeometryAnimRCIndex.IndexResult? = null): Graph {
        val bp = sources.filter { it.esBehavior && it.root.isDirectory }
        val rp = sources.filter { !it.esBehavior && it.root.isDirectory }

        // Definiciones: se reutiliza el índice que ya construyó el orquestador
        // en vez de parsear otra vez. ⭐ TICKET-CORE-013: sin este parámetro, el
        // índice se construía por TERCERA vez en la misma exportación (Fase 3D,
        // validador de referencias y este grafo).
        //
        // Es seguro compartirlo: los ficheros del pack fusionado se registran en
        // mapas `merged*` aparte y nunca entran en `geometryById` /
        // `animationById` / `renderControllerById`, que es lo único que se lee
        // aquí. Por eso un índice construido con fuentes + fusionado devuelve
        // exactamente las mismas claves de origen que uno construido solo con
        // las fuentes.
        val indice = indiceCompartido ?: GeometryAnimRCIndex.build(rp.map { it.root }, null)
        val geometrias = indice.geometryById.keys
        val animaciones = indice.animationById.keys
        val controllers = indice.renderControllerById.keys

        val texturas = inventarioDeTexturas(rp)
        val particulas = inventarioDeParticulas(rp)

        val declarados = mutableMapOf<Kind, Set<String>>(
            Kind.GEOMETRY to geometrias.toSet(),
            Kind.ANIMATION to animaciones.toSet(),
            Kind.RENDER_CONTROLLER to controllers.toSet(),
            Kind.TEXTURE to texturas,
            Kind.PARTICLE to particulas
        )

        // Entidades del Behavior Pack (solo lo que puede "aparecer" en el mundo).
        val entidadesBp = linkedMapOf<String, MutableSet<String>>()
        bp.forEach { src ->
            entidadesDeBehavior(src.root).forEach { id ->
                entidadesBp.getOrPut(id) { linkedSetOf() }.add(src.addonName)
            }
        }
        declarados[Kind.ENTITY] = entidadesBp.keys.toSet()

        // client_entity del Resource Pack + sus referencias.
        val clientes = linkedMapOf<String, MutableSet<String>>()
        val gaps = mutableListOf<Gap>()

        rp.forEach { src ->
            clientEntitiesDeRecurso(src.root).forEach { (id, desc, fichero) ->
                clientes.getOrPut(id) { linkedSetOf() }.add(src.addonName)

                fun revisar(kind: Kind, valores: Collection<String>, esVanilla: (String) -> Boolean) {
                    valores.forEach { ref ->
                        if (ref.isBlank()) return@forEach
                        if (esVanilla(ref)) return@forEach
                        if (ref in declarados[kind].orEmpty()) return@forEach
                        gaps.add(Gap(kind, ref, fichero, setOf(src.addonName)))
                    }
                }

                revisar(Kind.GEOMETRY, geometriasReferenciadas(desc)) { VanillaAssets.isVanillaGeometry(it) }
                revisar(Kind.RENDER_CONTROLLER, controllersReferenciados(desc)) {
                    VanillaAssets.isVanillaRenderController(it)
                }
                revisar(Kind.ANIMATION, animacionesReferenciadas(desc)) {
                    VanillaAssets.isVanillaAnimation(it)
                }
                revisar(Kind.TEXTURE, texturasReferenciadas(desc)) {
                    VanillaAssets.isVanillaTexture(it)
                }
                revisar(Kind.PARTICLE, particulasReferenciadas(desc)) {
                    it.startsWith("minecraft:")
                }
            }
        }
        declarados[Kind.CLIENT_ENTITY] = clientes.keys.toSet()

        // Cruces BP ↔ RP, ignorando lo vanilla (esa definición la pone el juego).
        val entidadesSinCliente = entidadesBp.keys.filter { id ->
            !id.startsWith("minecraft:") && id !in clientes
        }.toSet()

        val clientesSinEntidad = clientes.keys.filter { id ->
            !id.startsWith("minecraft:") && id !in entidadesBp
        }.toSet()

        PackForgeLog.d(
            TAG,
            "🧭 DAG: ${entidadesBp.size} entidades, ${clientes.size} client_entity, " +
                "${gaps.size} huecos, ${clientesSinEntidad.size} client_entity huérfanos"
        )

        return Graph(
            declarados = declarados,
            gaps = gaps.distinctBy { "${it.kind}:${it.id}:${it.referencedBy}" },
            entidadesSinCliente = entidadesSinCliente,
            clientesSinEntidad = clientesSinEntidad
        )
    }

    // ── Lectura de las fuentes ───────────────────────────────────────────────

    private fun leerJson(file: File): JSONObject? {
        val texto = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        return runCatching { JSONObject(texto) }.getOrNull() ?: LenientJson.parseObject(texto)
    }

    private fun entidadesDeBehavior(root: File): Set<String> {
        val out = linkedSetOf<String>()
        for (file in DirIndexCache.index(root).jsonFiles) {
            val json = leerJson(file) ?: continue
            val id = json.optJSONObject("minecraft:entity")
                ?.optJSONObject("description")?.optString("identifier").orEmpty()
            if (id.isNotBlank()) out.add(id)
        }
        return out
    }

    /** Índice → descripción + fichero. */
    private fun clientEntitiesDeRecurso(root: File): List<Triple<String, JSONObject, String>> {
        val out = mutableListOf<Triple<String, JSONObject, String>>()
        for (file in DirIndexCache.index(root).jsonFiles) {
            val json = leerJson(file) ?: continue
            val desc = json.optJSONObject("minecraft:client_entity")?.optJSONObject("description")
                ?: continue
            val id = desc.optString("identifier")
            if (id.isNotBlank()) out.add(Triple(id, desc, file.name))
        }
        return out
    }

    private fun inventarioDeTexturas(rp: List<Source>): Set<String> {
        val out = linkedSetOf<String>()
        rp.forEach { src ->
            DirIndexCache.index(src.root).allFiles.forEach archivo@{ f ->
                val rel = f.relativeTo(src.root).path.replace('\\', '/')
                if (!rel.startsWith("textures/", ignoreCase = true)) return@archivo
                val ext = f.extension.lowercase(Locale.ROOT)
                if (ext !in setOf("png", "tga", "jpg", "jpeg")) return@archivo
                val sinExt = rel.substringBeforeLast('.')
                out.add(sinExt)
                out.add(sinExt.lowercase(Locale.ROOT))
            }
        }
        return out
    }

    private fun inventarioDeParticulas(rp: List<Source>): Set<String> {
        val out = linkedSetOf<String>()
        rp.forEach { src ->
            DirIndexCache.index(src.root).jsonFiles.forEach archivo@{ f ->
                val rel = f.relativeTo(src.root).path.replace('\\', '/')
                if (!rel.contains("particles/", ignoreCase = true)) return@archivo
                val json = leerJson(f) ?: return@archivo
                json.optJSONObject("particle_effect")?.optJSONObject("description")
                    ?.optString("identifier")?.takeIf { it.isNotBlank() }?.let { out.add(it) }
            }
        }
        return out
    }

    // ── Extracción de referencias (usa BedrockRefs: alias y Molang fuera) ────

    private fun geometriasReferenciadas(desc: JSONObject): List<String> =
        valoresDe(desc.opt("geometry")).filter { it.startsWith("geometry.") && !BedrockRefs.isAlias(it) }

    private fun controllersReferenciados(desc: JSONObject): List<String> =
        valoresDe(desc.opt("render_controllers"))
            .filter { it.startsWith("controller.render.") && !BedrockRefs.isAlias(it) }

    private fun animacionesReferenciadas(desc: JSONObject): List<String> =
        valoresDe(desc.opt("animations")).filter {
            (it.startsWith("animation.") || it.startsWith("controller.animation.")) &&
                !BedrockRefs.isAlias(it)
        }

    private fun texturasReferenciadas(desc: JSONObject): List<String> =
        valoresDe(desc.opt("textures")).filter {
            it.startsWith("textures/") && !BedrockRefs.isExpression(it)
        }

    private fun particulasReferenciadas(desc: JSONObject): List<String> =
        valoresDe(desc.opt("particle_effects"))
            .flatMap { it.split(',') }
            .map { it.trim().substringAfterLast(':').let { n -> if (n.isBlank()) "" else n } }
            .filter { it.isNotBlank() }

    /** Aplana un valor JSON (string, array o mapa) a la lista de strings útiles. */
    private fun valoresDe(valor: Any?): List<String> = when (valor) {
        null -> emptyList()
        is String -> listOf(valor.trim())
        is JSONArray -> (0 until valor.length()).flatMap { valoresDe(valor.opt(it)) }
        is JSONObject -> {
            // Mapas de hueso→id, o condicionales {"controller.render.x": "q..."}:
            // la clave es el identificador cuando el valor es una expresión.
            valor.keys().asSequence().flatMap { k ->
                val v = valor.opt(k)
                if (v is String && (v.contains('?') || v.contains("query."))) listOf(k)
                else valoresDe(v)
            }.toList()
        }
        else -> emptyList()
    }
}
