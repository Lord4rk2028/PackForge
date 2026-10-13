package com.packforge.app.domain.engine

import com.packforge.app.util.PackForgeLog
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * REGISTRO DE ALIAS DE CARPETAS (Folder Alias Registry)
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Maneja colisiones de carpetas BINARIAS con mismo path pero contenido distinto:
 *   - structures/   (.mcstructure, .nbt)
 *   - fogs/        (.json de fog definitions)
 *   - ui/          (.json, .png de UI)
 *
 * Estrategia: al igual que ResourcePathRegistry, hash MD5 decide dedup vs alias.
 * Si contenido idéntico → se mantiene una copia (dedupe automático).
 * Si contenido diferente → se aliasa con prefijo pf_<hash6> y se actualiza
 * cualquier referencia en JSON (ej. structures/casa.mcstructure → structures/pf_a3f2_casa.mcstructure).
 *
 * NO se aliasan: manifest.json, pack_icon.png, scripts/, texts/
 */
object FolderAliasRegistry {
    private const val TAG = "PackForge_FolderAlias"

    /**
     * H2 (auditoría CORE): `ui/` NO se aliasa.
     *
     * La UI de Bedrock se bindea por NOMBRE EXACTO de archivo (un
     * `ui/hud_screen.json` debe llamarse así para sobreescribir la UI
     * vanilla; un alias `hud_screen_pf1a2b3.json` es IGNORADO por el motor
     * y el addon perdía su custom UI en silencio). Ante una colisión de ui/:
     *  · el PRIMER addon en llegar gana la ruta canónica (determinista),
     *  · el perdedor se archiva en `_packforge/ui_discard/` (fuera del
     *    alcance de Bedrock, recuperable manualmente),
     *  · el choque se expone en [uiConflicts] para que el orquestador lo
     *    reporte como hallazgo HIGH — hoy esa pérdida era invisible.
     */
    private val ALIASED_FOLDERS = setOf("structures/", "fogs/")

    /** Colisión de ui/ detectada (ruta, addon ganador, addon perdedor). */
    data class UiConflict(
        val path: String,
        val winnerAddon: String,
        val loserAddon: String
    )

    /** Conflictos de ui/ de la sesión actual (limpiar en clear()). */
    val uiConflicts = mutableListOf<UiConflict>()

    /** Archivo perdedor de ui/ → ruta de archivo fuera del alcance de Bedrock. */
    private const val UI_DISCARD_ROOT = "_packforge/ui_discard"

    /** Un alias de carpeta aplicado a un archivo. */
    data class FolderAlias(
        val originalPath: String,
        val aliasedPath: String,
        val hash: String,
        val ownerAddon: String
    )

    /** Commit único por ruta → hash (primer addon gana el path canónico). */
    private val committed = LinkedHashMap<String, String>()

    /** Ruta canónica → addon que la comprometió primero (el ganador en ui/). */
    private val committedOwner = mutableMapOf<String, String>()

    /** Log legible para reporte final. */
    val aliasLog = mutableListOf<String>()

    /**
     * Escanea una fuente completa y PLANIFICA alias para colisiones.
     * @return mapa de rutaOriginal → rutaAliased (vacío si no hay colisiones)
     */
    fun planAndCommit(sourceRoot: File, ownerAddon: String): Map<String, String> {
        val renames = LinkedHashMap<String, String>()
        if (!sourceRoot.isDirectory) return renames

        // ⭐ OPTIMIZACIÓN: Usar DirIndexCache en lugar de walkTopDown()
        val cachedFiles = DirIndexCache.index(sourceRoot).allFiles
        for (file in cachedFiles) {
            val rel = file.relativeTo(sourceRoot).path.replace("\\", "/")
            if (!needsAliasing(rel)) continue

            val hash = try { md5(file) } catch (e: Exception) { continue }
            val existing = committed[rel]

            when {
                existing == null -> {
                    committed[rel] = hash
                    committedOwner[rel] = ownerAddon
                }
                existing == hash -> Unit // idéntico: dedupe, sin rename
                rel.startsWith("ui/") -> {
                    // H2: NO se aliasa ui/ (Bedrock bindea por nombre exacto;
                    // un alias nunca cargaba). El primer addon en llegar gana
                    // la ruta canónica; el perdedor se archiva FUERA del
                    // alcance de Bedrock y el choque queda en uiConflicts.
                    val winner = committedOwner[rel] ?: "?"
                    val discardRel = "$UI_DISCARD_ROOT/$ownerAddon/$rel"
                    committed[discardRel] = hash
                    renames[rel] = discardRel
                    uiConflicts += UiConflict(rel, winner, ownerAddon)
                    aliasLog += "UI COLLISION: $rel — gana '$winner'; " +
                        "'$ownerAddon' archivado en $discardRel"
                    PackForgeLog.w(TAG, "🧩 Colisión de UI: $rel — gana $winner; $ownerAddon archivado")
                }
                else -> {
                    val alias = buildAlias(rel, hash)
                    committed[alias] = hash
                    renames[rel] = alias
                    aliasLog += "$rel → $alias (contenido distinto)"
                    PackForgeLog.d(TAG, "📁 Alias de carpeta: $rel → $alias")
                }
            }
        }
        return renames
    }

    /** Aplica los renombres de ruta sobre un JSONObject. */
    fun applyRenames(node: Any, renames: Map<String, String>): Boolean =
        JsonValueRewriter.replaceValues(node, renames)

    /**
     * B8 (auditoría CORE): traduce los renombres de `structures/` a la forma
     * en que las estructuras se referencian en COMANDOS (`/structure load
     * <nombre> ...` en .mcfunction): por nombre de archivo SIN extensión.
     *
     * Devuelve `stemOriginal → stemRenombrado` (p.ej. "casa" → "casa_pf2bb225")
     * para que el orquestador pueda reescribir los .mcfunction al copiarlos;
     * sin esto, toda estructura renombrada dejaba "Structure not found" en
     * gameplay (el alias resolvía el archivo, pero no el comando).
     */
    fun structureStemRenames(renames: Map<String, String>): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for ((original, aliased) in renames) {
            if (!original.startsWith("structures/")) continue
            val oldStem = original.removePrefix("structures/").substringBeforeLast('.')
            val newStem = aliased.removePrefix("structures/").substringBeforeLast('.')
            if (oldStem.isNotBlank() && oldStem != newStem) out[oldStem] = newStem
        }
        return out
    }

    private fun needsAliasing(relPath: String): Boolean {
        val lower = relPath.lowercase(Locale.ROOT)
        // ui/ se DETECTA pero ya no se aliasa (ver ALIASED_FOLDERS/H2).
        return (ALIASED_FOLDERS + "ui/").any { lower.startsWith(it) }
    }

    private fun buildAlias(rel: String, hash: String): String {
        val dot = rel.lastIndexOf('.')
        val stem = if (dot > 0) rel.substring(0, dot) else rel
        val ext = if (dot > 0) rel.substring(dot) else ""
        return "${stem}_pf${hash.substring(0, 6)}$ext"
    }

    private fun md5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Limpia el registro (nueva fusión). */
    fun clear() {
        committed.clear()
        committedOwner.clear()
        aliasLog.clear()
        uiConflicts.clear()
    }
}