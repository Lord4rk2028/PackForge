package com.packforge.app.domain.engine

import java.io.File
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

    /** Limpia el registro al inicio de cada fusión. */
    fun clear() {
        porRuta.clear()
        etiquetas.clear()
    }

    /**
     * Declara que [dir] pertenece al addon [owner]. Varias carpetas pueden
     * compartir propietario (típicamente el BP y el RP del mismo `.mcaddon`).
     *
     * @param etiqueta nombre legible del addon, solo para los reportes.
     */
    fun register(dir: File, owner: String, etiqueta: String) {
        porRuta[dir.absolutePath] = owner
        etiquetas[owner] = etiqueta
    }

    /** Propietario registrado para [dir], o `null` si no se registró. */
    fun ownerOf(dir: File): String? = porRuta[dir.absolutePath]

    /** Nombre legible del addon [owner], o `null` si no se conoce. */
    fun labelOf(owner: String): String? = etiquetas[owner]
}
