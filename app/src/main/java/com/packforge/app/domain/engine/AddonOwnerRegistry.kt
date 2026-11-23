package com.packforge.app.domain.engine

import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * REGISTRO DE PROPIETARIO DE ADDON (qué carpetas son el MISMO addon)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Un `.mcaddon` puede traer su Behavior Pack y su Resource Pack juntos. Antes,
 * el orquestador los separaba **copiando** cada subcarpeta a
 * `separated_bp_<estampa>` / `separated_rp_<estampa>`, y el renamespacer deducía
 * que ambas carpetas eran el MISMO addon **parseando ese número de estampa del
 * nombre**.
 *
 * Esa copia era redundante: `bpSubfolder` y `rpSubfolder` ya son raíces válidas
 * de pack (su `manifest.json` está en su raíz), y la Fase 1 los vuelve a copiar
 * a los directorios fusionados. Es decir, todo el addon se escribía dos veces.
 * Medido en el pipeline real, la separación estaba dentro del tramo más caro.
 *
 * Este registro sustituye a la estampa-en-el-nombre: el orquestador apunta qué
 * carpetas forman un mismo addon y el renamespacer lo consulta. Así se conserva
 * el agrupamiento BP↔RP —que es lo que impide que un addon "colisione consigo
 * mismo" y se rompa el vínculo entre su entidad y su textura— sin copiar nada.
 *
 * Si una carpeta no está registrada, el renamespacer cae en su lógica anterior
 * por nombre, de modo que cualquier ruta antigua sigue funcionando.
 */
object AddonOwnerRegistry {

    /** ruta absoluta de la carpeta → clave de propietario (addon). */
    private val porRuta = ConcurrentHashMap<String, String>()

    /** clave de propietario → nombre legible para los reportes. */
    private val etiquetas = ConcurrentHashMap<String, String>()

    /**
     * clave de propietario → SEMILLA estable del addon (UUID de sus manifiestos).
     *
     * Se separa de la clave porque la clave puede ser posicional (`both_0`) —
     * lo que mantiene determinista al ganador de una colisión— mientras que el
     * token `pf:<hex>` debe sobrevivir a las reexportaciones.
     */
    private val semillas = ConcurrentHashMap<String, String>()

    /** Limpia el registro al inicio de cada fusión. */
    fun clear() {
        porRuta.clear()
        etiquetas.clear()
        semillas.clear()
    }

    /**
     * Declara que [dir] pertenece al addon [owner]. Varias carpetas pueden
     * compartir propietario (típicamente el BP y el RP del mismo `.mcaddon`).
     *
     * @param etiqueta nombre legible del addon, solo para los reportes.
     */
    fun register(dir: File, owner: String, etiqueta: String, semilla: String = owner) {
        porRuta[dir.absolutePath] = owner
        etiquetas[owner] = etiqueta
        semillas.putIfAbsent(owner, semilla)
    }

    /** Propietario registrado para [dir], o `null` si no se registró. */
    fun ownerOf(dir: File): String? = porRuta[dir.absolutePath]

    /** Nombre legible del addon [owner], o `null` si no se conoce. */
    fun labelOf(owner: String): String? = etiquetas[owner]

    /**
     * Semilla estable del addon [owner] (ver [stableSeed]), o `null` si la
     * carpeta no se registró. La usan los tokens `_pf<hex>` cuya estabilidad
     * entre exportaciones importa (identificadores que ya viven en los mundos).
     */
    fun seedOf(owner: String): String? = semillas[owner]

    /**
     * 3.11 — Semilla ESTABLE de un addon a partir de sus raíces de pack.
     *
     * Antes el token `_pf` salía de `ownerKey.hashCode()` y la clave solía ser la
     * ruta extraída (`extracted_<epochMs>_<addon>`): el identificador renombrado
     * (`pf:1a2b3c_stone`) cambiaba en cada exportación y lo que ya estaba
     * guardado en un mundo dejaba de resolverse al reinstalar el modpack.
     *
     * La semilla se deriva de los **UUID de header de los manifiestos**, que son
     * estables entre reimportaciones del mismo addon y distintos entre addons.
     * Solo si no hay manifiesto legible se cae al nombre sin la estampa de
     * extracción (nunca a la ruta completa, que incluye el epoch).
     */
    fun stableSeed(roots: List<File>, etiqueta: String): String {
        val uuids = roots.asSequence()
            .filter { it.isDirectory }
            .mapNotNull { uuidDeManifiesto(it) }
            .distinct()
            .sorted()
            .toList()

        val base = if (uuids.isNotEmpty()) {
            "uuid:" + uuids.joinToString("|")
        } else {
            "nombre:" + etiqueta
                .replace(Regex("(?i)^(extracted|separated_(bp|rp))_\\d+_"), "")
                .trim()
                .lowercase(Locale.ROOT)
        }
        return "seed_" + sha256Hex(base).take(12)
    }

    private fun uuidDeManifiesto(dir: File): String? {
        val manifiesto = File(dir, "manifest.json")
        if (!manifiesto.isFile) return null
        val texto = runCatching { manifiesto.readText(StandardCharsets.UTF_8) }.getOrNull()
            ?: return null
        val json = runCatching { JSONObject(texto) }.getOrNull()
            ?: LenientJson.parseObject(texto)
            ?: return null
        return json.optJSONObject("header")
            ?.optString("uuid", "")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.lowercase(Locale.ROOT)
    }

    private fun sha256Hex(texto: String): String = try {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(texto.toByteArray(StandardCharsets.UTF_8))
        digest.joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        texto.hashCode().toUInt().toString(16)
    }
}
