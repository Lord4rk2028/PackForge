package com.packforge.app.domain.engine

import com.packforge.app.domain.model.ConflictSeverity
import com.packforge.app.util.PackForgeLog
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * REGISTRO DE SUBPACKS (`manifest.header.subpacks`)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Bedrock permite que un pack declare **variantes por nivel de memoria**:
 * carpetas (`folder_name`) con contenido que sobrescribe el del pack, y una
 * entrada por variante en `header.subpacks` con su `memory_tier`. El juego
 * elige UNA variante según la memoria del dispositivo.
 *
 * El motor reconstruye los manifiestos y **no copiaba `subpacks`**, así que
 * todas las variantes desaparecían del pack fusionado: los archivos seguían
 * dentro del `.mcaddon` (en su carpeta) pero el juego no tenía forma de saber
 * que existían. Un addon cuyas texturas viven solo en subpacks se quedaba sin
 * texturas al fusionarlo con cualquier otro.
 *
 * Este registro hace dos cosas, ambas antes de copiar:
 *
 *  1. **Preserva** las entradas de todos los manifiestos (las lee
 *     `ManifestGenerator` con [renamesForManifest]).
 *  2. **Desambigua** las carpetas: si dos addons usan el mismo `folder_name`,
 *     el segundo se copia a `<carpeta>_pf<hash>` y su entrada del manifiesto
 *     apunta a la carpeta nueva. Las referencias internas de Bedrock son
 *     relativas a la raíz del pack y NO incluyen la carpeta del subpack, así
 *     que renombrarla no rompe ninguna ruta.
 *
 * Además reporta colisiones de `memory_tier`: el juego aplica **un solo**
 * subpack por nivel, así que dos addons que declaren el mismo tier no pueden
 * convivir; es una limitación de Bedrock, no del motor, y debe verse.
 */
object SubpackRegistry {

    private const val TAG = "PackForge_Subpacks"

    /** Una entrada de subpack ya planificada. */
    data class Entry(
        val manifiesto: File,
        val addon: String,
        val carpetaOriginal: String,
        val carpetaFinal: String,
        val memoryTier: Int,
        val nombre: String
    )

    /** dir absoluto → (carpeta original → carpeta final) */
    private val renombresPorDir = ConcurrentHashMap<String, Map<String, String>>()

    /** manifest absoluto → (carpeta original → carpeta final) */
    private val renombresPorManifiesto = ConcurrentHashMap<String, Map<String, String>>()

    /** Evita planificar dos veces el mismo directorio (BP y RP se planifican juntos). */
    private val planificados = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** Resumen de la última planificación (para reporte/tests). */
    @Volatile
    var resumen: String = ""
        private set

    /**
     * Planifica los subpacks de un conjunto de packs (BP o RP).
     * Llamar una vez por tipo; los nombres de carpeta de BP y RP son
     * independientes porque cada pack tiene su propio manifiesto.
     */
    fun plan(dirs: List<File>) {
        // carpeta final → addon dueño (para poder culpar a los dos en el reporte).
        val usadas = HashMap<String, String>()
        val entradas = mutableListOf<Entry>()

        for (dir in dirs) {
            if (!dir.isDirectory) continue
            if (!planificados.add(dir.absolutePath)) continue

            val manifiesto = File(dir, "manifest.json")
            if (!manifiesto.isFile) continue
            val json = runCatching { JSONObject(manifiesto.readText(StandardCharsets.UTF_8)) }
                .getOrNull() ?: continue
            val header = json.optJSONObject("header") ?: continue
            val subpacks = header.optJSONArray("subpacks") ?: continue
            if (subpacks.length() == 0) continue

            val uuid = header.optString("uuid", "").trim()
            val etiqueta = etiquetaAddon(dir)
            val mapa = LinkedHashMap<String, String>()

            for (i in 0 until subpacks.length()) {
                val sp = subpacks.optJSONObject(i) ?: continue
                val original = normalizarCarpeta(sp.optString("folder_name", ""))
                if (original.isEmpty()) {
                    ConflictRegistry.logConflict(
                        severity = ConflictSeverity.MEDIUM,
                        type = "SUBPACK_INVALID",
                        file = "manifest.json",
                        addon1 = etiqueta,
                        addon2 = etiqueta,
                        description = "Una entrada de 'subpacks' de '$etiqueta' no declara " +
                            "'folder_name'. Bedrock no puede cargarla, así que se omite del " +
                            "manifiesto fusionado."
                    )
                    continue
                }

                val duenioPrevio = usadas[original]
                var final = original
                if (duenioPrevio != null) {
                    // Token estable entre exportaciones: UUID del addon + carpeta.
                    val token = token6("$uuid|$original")
                    final = "${original}_pf$token"
                    var intento = 1
                    while (usadas.containsKey(final)) {
                        intento++
                        final = "${original}_pf${token}$intento"
                    }
                    ConflictRegistry.logConflict(
                        severity = ConflictSeverity.LOW,
                        type = "SUBPACK_RENAMED",
                        file = "manifest.json",
                        addon1 = duenioPrevio,
                        addon2 = etiqueta,
                        description = "Dos addons usan la carpeta de subpack '$original'. La de " +
                            "'$etiqueta' se copia como '$final' y su entrada del manifiesto " +
                            "apunta ahí; así las dos variantes coexisten. Las referencias de " +
                            "Bedrock son relativas a la raíz del pack, no a la carpeta."
                    )
                }

                usadas[final] = etiqueta
                mapa[original] = final
                entradas += Entry(
                    manifiesto = manifiesto,
                    addon = etiqueta,
                    carpetaOriginal = original,
                    carpetaFinal = final,
                    memoryTier = sp.optInt("memory_tier", 0),
                    nombre = sp.optString("name", "")
                )
            }

            if (mapa.isNotEmpty()) {
                renombresPorDir[dir.absolutePath] = mapa
                renombresPorManifiesto[manifiesto.absolutePath] = mapa
            }
        }

        reportarTiersDuplicados(entradas)

        if (entradas.isNotEmpty()) {
            resumen = entradas.joinToString("; ") {
                "${it.addon}: ${it.carpetaOriginal}→${it.carpetaFinal} (tier ${it.memoryTier})"
            }
            PackForgeLog.d(TAG, "Subpacks planificados: $resumen")
        }
    }

    /**
     * Ruta de destino de un archivo que vive dentro de una carpeta de subpack.
     * Si la carpeta se renombró, se aplica el prefijo nuevo.
     */
    fun renameFor(dir: File, relPath: String): String {
        val mapa = renombresPorDir[dir.absolutePath] ?: return relPath
        val limpio = relPath.replace('\\', '/')
        for ((original, final) in mapa) {
            if (limpio == original) return final
            if (limpio.startsWith("$original/")) return final + limpio.substring(original.length)
        }
        return relPath
    }

    /**
     * Carpeta final de una entrada de subpack. Si no se planificó (p. ej. un
     * manifiesto sintético de test), devuelve la carpeta declarada tal cual.
     */
    fun finalFolderFor(manifiesto: File, carpetaOriginal: String): String {
        val mapa = renombresPorManifiesto[manifiesto.absolutePath] ?: return carpetaOriginal.trim()
        return mapa[normalizarCarpeta(carpetaOriginal)] ?: carpetaOriginal.trim()
    }

    fun clear() {
        renombresPorDir.clear()
        renombresPorManifiesto.clear()
        planificados.clear()
        resumen = ""
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun reportarTiersDuplicados(entradas: List<Entry>) {
        if (entradas.isEmpty()) return
        entradas.groupBy { it.memoryTier }.forEach { (tier, lista) ->
            if (lista.size <= 1) return@forEach
            val carpetas = lista.map { it.carpetaFinal }.distinct()
            if (carpetas.size <= 1) return@forEach // misma carpeta: es un duplicado exacto
            val addons = lista.joinToString(", ") { it.addon }
            ConflictRegistry.logConflict(
                severity = ConflictSeverity.MEDIUM,
                type = "SUBPACK_TIER_COLLISION",
                file = "manifest.json",
                addon1 = lista.first().addon,
                addon2 = lista.last().addon,
                description = "Los addons $addons declaran subpacks para memory_tier $tier. " +
                    "Bedrock aplica UN solo subpack por nivel de memoria, así que solo uno " +
                    "de ellos se usará en el juego. Es una limitación del motor del juego; " +
                    "revisa si ambos aportan variantes del mismo pack."
            )
        }
    }

    /** Nombre real del addon (no `rp`/`bp`), con la carpeta como respaldo. */
    private fun etiquetaAddon(dir: File): String =
        AddonOwnerRegistry.ownerOf(dir)?.let { AddonOwnerRegistry.labelOf(it) } ?: dir.name

    private fun normalizarCarpeta(raw: String): String {
        val limpio = raw.trim().replace('\\', '/').removePrefix("./").trimEnd('/')
        return if (limpio == "." || limpio == "..") "" else limpio
    }

    private fun token6(semilla: String): String = try {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(semilla.toByteArray(StandardCharsets.UTF_8))
        val sb = StringBuilder(12)
        for (i in 0 until 6) sb.append("%02x".format(digest[i]))
        sb.toString()
    } catch (_: Exception) {
        semilla.hashCode().toUInt().toString(16).padStart(6, '0').take(6)
    }
}
