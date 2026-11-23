package com.packforge.app.domain.engine

import com.packforge.app.domain.model.ConflictSeverity
import com.packforge.app.util.PackForgeLog

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * NEGOCIACIÓN DE `format_version` AL FUSIONAR UNA DEFINICIÓN
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * `format_version` **no** es la versión del pack: selecciona el ESQUEMA con el
 * que Bedrock interpreta el archivo. Cuando dos addons declaran la misma
 * definición (misma entidad cliente, mismo attachable, mismo override del
 * jugador) con esquemas distintos, la fusión profunda deja el `format_version`
 * del último addon —la prioridad del producto— y el contenido del otro pasa a
 * interpretarse bajo ese esquema **sin que nadie lo diga**.
 *
 * Lo que este objeto NO hace, y por qué (regla 16 de AGENTS.md):
 *
 *  · **No sube la versión al máximo.** Subirla no convierte el contenido: un
 *    archivo escrito para 1.10 no se «actualiza» porque diga 1.21, se
 *    reinterpreta bajo un esquema para el que no se escribió.
 *  · **No reescribe la versión del ganador.** La prioridad de fusión manda
 *    (el último addon decide el esquema), igual que en el resto del motor.
 *
 * Lo que SÍ hace: cuando los esquemas declarados no coinciden, registra
 * `FORMAT_VERSION_MISMATCH` (MEDIUM) con la versión de cada addon y cuál
 * gobierna, para que el usuario lo vea en el reporte y pueda cambiar el
 * ganador en Conflictos. Dos archivos que declaran la MISMA versión no son
 * conflicto (regla 8).
 */
object FormatVersionNegotiator {

    private const val TAG = "PackForge_FormatVersion"

    /** Un archivo que aporta contenido a la definición fusionada. */
    data class Aportante(val addon: String, val version: List<Int>?)

    data class Negotiation(
        /** Esquema que gobierna el fichero fusionado (nunca se modifica aquí). */
        val version: List<Int>?,
        val conflicto: Boolean = false,
        val detalle: String = ""
    )

    /**
     * Compara los esquemas de los archivos que se fusionan.
     *
     * @param filePath ruta relativa del fichero fusionado (para el reporte).
     * @param identifier identifier/definición afectada, si se conoce.
     * @param aportantes ficheros fuente en orden de fusión (el último gana).
     * @param mantenida `format_version` que quedará en el fichero fusionado.
     */
    fun negotiate(
        filePath: String,
        identifier: String?,
        aportantes: List<Aportante>,
        mantenida: List<Int>?
    ): Negotiation {
        val declaradas = aportantes.filter { it.version != null }
        val distintas = declaradas.map { it.version!! }.distinct()
        if (distintas.size < 2) return Negotiation(mantenida)

        // El esquema que gobierna es el último que declara la versión mantenida;
        // si nadie la declara (raro), el último que declaró algo.
        val gobernante = declaradas.lastOrNull { it.version == mantenida } ?: declaradas.last()

        val versiones = declaradas.joinToString("; ") {
            "${it.addon}=${it.version!!.joinToString(".")}"
        }
        val objetivo = identifier?.let { " de '$it'" } ?: ""
        val descripcion = "El fichero '$filePath'$objetivo se fusiona desde esquemas " +
            "distintos: $versiones. El resultado declara " +
            "format_version ${mantenida?.joinToString(".") ?: "?"} (la del último addon que " +
            "declara la definición), así que el contenido del otro addon se reinterpreta bajo " +
            "ese esquema. El motor NO sube ni reescribe la versión: subirla no convierte el " +
            "contenido (regla 16). Si la entidad se ve o se comporta distinto a lo esperado, " +
            "cambia el ganador en Conflictos."

        PackForgeLog.w(TAG, descripcion)
        ConflictRegistry.logConflict(
            severity = ConflictSeverity.MEDIUM,
            type = "FORMAT_VERSION_MISMATCH",
            file = filePath,
            // Mismo orden que el resto de conflictos: addon1 = primero en el
            // orden de fusión, addon2 = el que decide el esquema.
            addon1 = declaradas.first().addon,
            addon2 = gobernante.addon,
            description = descripcion
        )

        return Negotiation(
            version = mantenida,
            conflicto = true,
            detalle = versiones
        )
    }
}
