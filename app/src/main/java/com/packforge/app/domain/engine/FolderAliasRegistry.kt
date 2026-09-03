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

    private val ALIASED_FOLDERS = setOf("structures/", "fogs/", "ui/")

    /** Un alias de carpeta aplicado a un archivo. */
    data class FolderAlias(
        val originalPath: String,
        val aliasedPath: String,
        val hash: String,
        val ownerAddon: String
    )

    /** Commit único por ruta → hash (primer addon gana el path canónico). */
    private val committed = LinkedHashMap<String, String>()

    /** Log legible para reporte final. */
    val aliasLog = mutableListOf<String>()

    /**
     * Escanea una fuente completa y PLANIFICA alias para colisiones.
     * @return mapa de rutaOriginal → rutaAliased (vacío si no hay colisiones)
     */
    fun planAndCommit(sourceRoot: File, ownerAddon: String): Map<String, String> {
        val renames = LinkedHashMap<String, String>()
        if (!sourceRoot.isDirectory) return renames

        sourceRoot.walkTopDown().filter { it.isFile }.forEach { file ->
            val rel = file.relativeTo(sourceRoot).path.replace("\\", "/")
            if (!needsAliasing(rel)) return@forEach

            val hash = try { md5(file) } catch (e: Exception) { return@forEach }
            val existing = committed[rel]

            when {
                existing == null -> committed[rel] = hash
                existing == hash -> Unit // idéntico: dedupe, sin rename
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

    private fun needsAliasing(relPath: String): Boolean {
        val lower = relPath.lowercase(Locale.ROOT)
        return ALIASED_FOLDERS.any { lower.startsWith(it) }
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
        aliasLog.clear()
    }
}