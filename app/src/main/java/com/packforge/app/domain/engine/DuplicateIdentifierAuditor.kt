package com.packforge.app.domain.engine

import com.packforge.app.domain.model.ConflictSeverity
import com.packforge.app.domain.model.MergeConflict
import com.packforge.app.util.PackForgeLog
import java.io.File

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * FASE 4 — auditor de IDENTIFICADORES DUPLICADOS
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Bedrock no avisa: si dos archivos declaran el mismo identificador, uno gana y
 * el otro queda como código muerto (o se comporta de forma indefinida según el
 * orden de carga). El pack "parece" correcto y el fallo aparece en partida como
 * una entidad invisible o un bloque con otro modelo.
 *
 * `IdentifierRemapper` ya renombra el id del addon perdedor, pero es una
 * operación heurística y no cubre todo (ids construidos, archivos que no
 * parsean con `org.json`, etc.). Este auditor es la RED DE SEGURIDAD: recorre el
 * pack YA fusionado y reporta como HIGH todo identificador declarado en más de
 * un archivo, con las dos rutas. Es barato: prefiltro de texto antes de parsear.
 *
 * No modifica nada (solo audita), así que nunca puede empeorar el pack.
 */
object DuplicateIdentifierAuditor {

    private const val TAG = "PackForge_DuplicateIds"

    /** Tope de conflictos distintos reportados (el resto se cuenta). */
    private const val MAX_FINDINGS = 200

    /** Directorios del BP que declaran identificadores. */
    private val BP_ROOTS = listOf(
        "blocks", "items", "entities", "entity", "recipes", "trading",
        // 3.8
        "item_catalog", "cameras", "dimensions", "worldgen"
    )

    /** Directorios del RP que declaran identificadores. */
    private val RP_ROOTS = listOf("entity", "models", "animations", "animation_controllers", "render_controllers", "attachables")

    data class Finding(val identifier: String, val kind: String, val files: List<String>)

    /**
     * Audita ambos packs y devuelve los identificadores duplicados.
     * Los hallazgos se registran además en [ConflictRegistry]/[JsonDeepMerger].
     */
    fun audit(rpDirs: List<File>, bpDirs: List<File>): List<Finding> {
        val findings = mutableListOf<Finding>()
        val seen = HashMap<String, MutableList<String>>()

        bpDirs.forEach { collectFrom(it, BP_ROOTS, isBp = true, seen) }
        rpDirs.forEach { collectFrom(it, RP_ROOTS, isBp = false, seen) }

        for ((id, files) in seen) {
            if (files.size < 2) continue
            val kind = kindOf(id)
            findings += Finding(id, kind, files.sorted())
        }
        if (findings.isEmpty()) {
            PackForgeLog.d(TAG, "Sin identificadores duplicados")
            return emptyList()
        }

        PackForgeLog.w(TAG, "⚠️ ${findings.size} identificador(es) declarado(s) más de una vez")
        findings.take(MAX_FINDINGS).forEach { f ->
            val desc = "Identificador '${f.identifier}' declarado en ${f.files.size} archivos " +
                "(${f.files.joinToString()}). Bedrock solo usará uno y el resto queda muerto."
            JsonDeepMerger.mergeConflicts.add(
                MergeConflict(
                    id = java.util.UUID.randomUUID().toString(),
                    filePath = f.files.first(),
                    conflictType = "DUPLICATE_IDENTIFIER",
                    sourceAddon = f.files.getOrNull(1) ?: "",
                    targetAddon = f.files.first(),
                    severity = ConflictSeverity.HIGH,
                    description = desc,
                    jsonPath = "/${f.kind}/identifier"
                )
            )
            ConflictRegistry.logConflict(
                severity = ConflictSeverity.HIGH,
                type = "DUPLICATE_IDENTIFIER",
                file = f.files.first(),
                addon1 = f.files.getOrNull(1) ?: "-",
                addon2 = f.files.first(),
                description = desc
            )
        }
        return findings
    }

    private fun collectFrom(
        root: File,
        dirs: List<String>,
        isBp: Boolean,
        seen: MutableMap<String, MutableList<String>>
    ) {
        dirs.forEach { dir ->
            val base = File(root, dir)
            if (!base.isDirectory) return@forEach
            DirIndexCache.index(base).jsonFiles.forEach { file ->
                // Prefiltro barato: sin "identifier" y sin claves de definición
                // (animations/render_controllers se identifican por CLAVE, no por
                // campo) no hace falta parsear.
                val text = try {
                    file.readText(Charsets.UTF_8)
                } catch (_: Exception) {
                    return@forEach
                }
                if (!text.contains("identifier") &&
                    !text.contains("\"animations\"") &&
                    !text.contains("\"render_controllers\"") &&
                    !text.contains("\"animation_controllers\"")
                ) return@forEach

                val json = LenientJson.parseObject(text) ?: return@forEach
                val rel = file.relativeTo(root).path.replace('\\', '/')

                // (a) Identificadores declarados en `description.identifier`.
                listOf(
                    "minecraft:block", "minecraft:item", "minecraft:entity",
                    "minecraft:client_entity", "minecraft:attachable", "minecraft:recipe_shaped",
                    "minecraft:recipe_shapeless", "minecraft:recipe_furnace",
                    "minecraft:recipe_brewing_mix", "minecraft:recipe_brewing_container"
                ).forEach { key ->
                    val id = json.optJSONObject(key)
                        ?.optJSONObject("description")
                        ?.optString("identifier")
                        .orEmpty()
                    if (id.isNotBlank()) register(id, rel, seen)
                }

                // (b) Geometrías: la lista declara `description.identifier`.
                json.optJSONArray("minecraft:geometry")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val id = arr.optJSONObject(i)
                            ?.optJSONObject("description")
                            ?.optString("identifier")
                            .orEmpty()
                        if (id.isNotBlank()) register(id, rel, seen)
                    }
                }

                // (c) Definiciones identificadas por CLAVE.
                listOf("animations", "animation_controllers", "render_controllers").forEach { key ->
                    json.optJSONObject(key)?.keys()?.forEach { id ->
                        // Fuera de las animaciones, las claves de estado internas
                        // (p. ej. "default") se repiten por diseño: solo cuentan
                        // las que llevan punto (`animation.<ns>.<nombre>`,
                        // `controller.render.<x>`, `controller.animation.<x>`).
                        if (id.contains('.') || id.contains(':')) register(id, rel, seen)
                    }
                }
            }
        }
    }

    private fun register(id: String, rel: String, seen: MutableMap<String, MutableList<String>>) {
        val list = seen.getOrPut(id) { mutableListOf() }
        if (rel !in list) list += rel
    }

    private fun kindOf(id: String): String = when {
        id.startsWith("geometry.") -> "geometry"
        id.startsWith("controller.render.") -> "render_controller"
        id.startsWith("controller.animation.") -> "animation_controller"
        id.startsWith("animation.") -> "animation"
        else -> "identifier"
    }
}
