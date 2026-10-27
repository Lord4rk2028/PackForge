package com.packforge.app.domain.engine

import com.packforge.app.util.PackForgeLog
import org.json.JSONObject
import java.io.File

/**
 * ═══════════════════════════════════════════════════════════════════════
 * PARTICLE DEDUPLICATOR — renombra partículas duplicadas en el ORIGEN
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Cuando dos addons definen `custom:dust`, solo uno puede quedarse con ese
 * identificador. La versión anterior lo resolvía DESPUÉS de fusionar, sobre el
 * árbol ya combinado, y ahí es imposible saber a qué addon pertenecía cada
 * referencia:
 *
 *  - el pack ganador también veía reescritas sus referencias al alias, así que
 *    sus entidades acababan usando la partícula del otro addon;
 *  - y la reescritura llegaba a tocar la DEFINICIÓN ya copiada del ganador,
 *    colapsando las dos partículas en una sola.
 *
 * La solución es renombrar **dentro del addon perdedor y antes de copiar**:
 *  - su definición cambia de identificador,
 *  - sus propias referencias (en cualquier JSON de ESE addon) se actualizan,
 *  - el addon ganador no se toca en absoluto.
 *
 * Se ejecuta en la fase previa a la copia, como [CustomComponentRewriter].
 */
object ParticleDeduplicator {

    private const val TAG = "PackForge_Particles"

    /**
     * @param renames cuántas partículas se renombraron
     * @param affectedRoots addons mutados (sus índices de disco quedan obsoletos)
     */
    data class Result(
        val renames: Int,
        val affectedRoots: List<File>
    )

    /**
     * Desambigua identificadores de partículas repetidos entre addons.
     *
     * @param addonRoots raíz extraída de cada addon (puede contener BP y RP)
     */
    fun deduplicate(addonRoots: List<File>): Result {
        val roots = addonRoots.filter { it.isDirectory }
        if (roots.size < 2) return Result(0, emptyList())

        val firstOwner = mutableMapOf<String, String>()
        val touched = linkedSetOf<File>()
        var renames = 0

        roots.forEach { root ->
            val token = Integer.toHexString(root.absolutePath.hashCode())
                .takeLast(4)
                .padStart(4, '0')

            particleDefinitionFiles(root).forEach { file ->
                val json = try {
                    JsonDeepMerger.cleanJsonObject(JSONObject(file.readText(Charsets.UTF_8)))
                } catch (e: Exception) {
                    PackForgeLog.e(TAG, "No se pudo leer ${file.name}: ${e.message}")
                    return@forEach
                }

                val description = json.optJSONObject("particle_effect")
                    ?.optJSONObject("description") ?: return@forEach
                val identifier = description.optString("identifier")
                if (identifier.isBlank() || identifier.startsWith("minecraft:")) return@forEach

                val owner = firstOwner[identifier]
                if (owner == null) {
                    // Este addon se queda con el identificador original.
                    firstOwner[identifier] = root.absolutePath
                    return@forEach
                }

                val newIdentifier = "${identifier}_pf$token"
                description.put("identifier", newIdentifier)
                writeJson(file, json)
                rewriteReferencesWithin(root, identifier, newIdentifier)

                renames++
                touched += root
                PackForgeLog.d(
                    TAG,
                    "✨ '$identifier' → '$newIdentifier' en ${root.name} (lo conserva ${File(owner).name})"
                )
            }
        }

        if (renames > 0) {
            PackForgeLog.d(TAG, "✅ $renames partícula(s) duplicada(s) desambiguada(s) en el origen")
        }
        return Result(renames, touched.toList())
    }

    /** Archivos de definición de partículas dentro de la raíz de un addon. */
    private fun particleDefinitionFiles(root: File): List<File> =
        root.walkTopDown()
            .filter { it.isFile && it.extension.equals("json", true) }
            .filter { it.parentFile?.name.equals("particles", ignoreCase = true) }
            .toList()

    /**
     * Reescribe las referencias a [oldIdentifier] SOLO dentro de [root].
     *
     * Las definiciones de partícula se saltan: la que acabamos de renombrar ya
     * no contiene el identificador viejo, y cualquier otra definición es un
     * identificador propio que no debe tocarse.
     */
    private fun rewriteReferencesWithin(root: File, oldIdentifier: String, newIdentifier: String) {
        val needle = "\"$oldIdentifier\""
        root.walkTopDown()
            .filter { it.isFile && it.extension.equals("json", true) }
            .forEach { file ->
                try {
                    val text = file.readText(Charsets.UTF_8)
                    if (!text.contains(needle)) return@forEach

                    val json = JSONObject(text)
                    if (json.has("particle_effect")) return@forEach

                    val probe = JSONObject(json.toString())
                    ResourcePathRegistry.applyRenames(probe, mapOf(oldIdentifier to newIdentifier))
                    writeJson(file, probe)
                    PackForgeLog.d(TAG, "  ↪ referencia actualizada en ${file.name}")
                } catch (_: Exception) {
                    // Un JSON ilegible no debe abortar la desambiguación.
                }
            }
    }

    private fun writeJson(file: File, json: JSONObject) {
        file.writeText(json.toString(), Charsets.UTF_8)
    }
}
