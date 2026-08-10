package com.packforge.app.domain.engine

import com.packforge.app.domain.model.Addon
import com.packforge.app.domain.model.Conflict
import com.packforge.app.domain.model.ConflictResolution
import com.packforge.app.domain.model.ConflictSeverity
import com.packforge.app.domain.model.ConflictType
import java.util.UUID

object ConflictEngine {

    fun analyze(addons: List<Addon>): List<Conflict> {
        val active = addons.filter { it.enabled }
        if (active.size < 2) return emptyList()

        val conflicts = mutableListOf<Conflict>()

        conflicts += detectScriptConflicts(active)
        conflicts += detectEntityConflicts(active)
        conflicts += detectItemConflicts(active)
        conflicts += detectRecipeConflicts(active)
        conflicts += detectFileOverlaps(active)
        conflicts += detectVersionMismatches(active)
        conflicts += detectManifestUuidConflicts(active)

        // Eliminar duplicados por combinación de addons y archivo
        return conflicts.distinctBy { c ->
            c.type.name + "_" + c.affectedFile + "_" +
            c.affectedAddonIds.sorted().joinToString(",")
        }
    }

    // ─── 1. CONFLICTOS DE SCRIPTS ────────────────────────────
    private fun detectScriptConflicts(active: List<Addon>): List<Conflict> {
        val withScripts = active.filter { it.hasScripts }
        if (withScripts.size < 2) return emptyList()

        return listOf(
            Conflict(
                id = UUID.randomUUID().toString(),
                type = ConflictType.SCRIPT_CONFLICT,
                severity = ConflictSeverity.CRITICAL,
                title = "Conflicto de Scripts",
                description = "Múltiples addons tienen scripts de comportamiento. " +
                    "Los scripts pueden interferir entre sí causando crashes o " +
                    "comportamiento impredecible en el juego.",
                technicalDetail = "Addons con scripts: " +
                    withScripts.joinToString(", ") { it.name } +
                    ". Los scripts de Bedrock se ejecutan en el mismo contexto " +
                    "y pueden sobrescribirse mutuamente.",
                affectedAddonIds = withScripts.map { it.id },
                affectedFile = "scripts/",
                resolution = ConflictResolution.UNRESOLVED,
                canBeDismissed = false
            )
        )
    }

    // ─── 2. CONFLICTOS DE ENTIDADES ──────────────────────────
    private fun detectEntityConflicts(active: List<Addon>): List<Conflict> {
        val conflicts = mutableListOf<Conflict>()
        val identifierMap = mutableMapOf<String, MutableList<Addon>>()

        active.forEach { addon ->
            addon.entityIdentifiers.forEach { identifier ->
                identifierMap.getOrPut(identifier) { mutableListOf() }.add(addon)
            }
        }

        identifierMap.filter { it.value.size > 1 }.forEach { (identifier, owners) ->
            val isPlayer = identifier.contains("player", ignoreCase = true)
            conflicts.add(
                Conflict(
                    id = UUID.randomUUID().toString(),
                    type = ConflictType.ENTITY_IDENTIFIER,
                    severity = if (isPlayer) ConflictSeverity.CRITICAL
                               else ConflictSeverity.HIGH,
                    title = if (isPlayer) "Conflicto en Entidad: Jugador"
                            else "Entidad Duplicada: $identifier",
                    description = if (isPlayer)
                        "Ambos addons modifican al jugador ('$identifier'). " +
                        "Solo una definición puede existir. Esto puede causar " +
                        "que el juego crashee o que el jugador se comporte de " +
                        "forma incorrecta."
                    else
                        "La entidad '$identifier' está definida en múltiples " +
                        "addons. Solo la definición del addon con mayor " +
                        "prioridad será usada.",
                    technicalDetail = "Identifier: $identifier\n" +
                        "Definido en: ${owners.joinToString(", ") { it.name }}\n" +
                        "En Minecraft Bedrock solo puede existir una definición " +
                        "por identifier. La segunda sobrescribe a la primera.",
                    affectedAddonIds = owners.map { it.id },
                    affectedFile = "entities/$identifier.json",
                    resolution = ConflictResolution.UNRESOLVED,
                    canBeDismissed = !isPlayer
                )
            )
        }
        return conflicts
    }

    // ─── 3. CONFLICTOS DE ÍTEMS ──────────────────────────────
    private fun detectItemConflicts(active: List<Addon>): List<Conflict> {
        val conflicts = mutableListOf<Conflict>()
        val identifierMap = mutableMapOf<String, MutableList<Addon>>()

        active.forEach { addon ->
            addon.itemIdentifiers.forEach { identifier ->
                identifierMap.getOrPut(identifier) { mutableListOf() }.add(addon)
            }
        }

        identifierMap.filter { it.value.size > 1 }.forEach { (identifier, owners) ->
            conflicts.add(
                Conflict(
                    id = UUID.randomUUID().toString(),
                    type = ConflictType.ITEM_IDENTIFIER,
                    severity = ConflictSeverity.HIGH,
                    title = "Ítem Duplicado: $identifier",
                    description = "El ítem '$identifier' está definido en " +
                        "${owners.size} addons distintos. Solo una versión " +
                        "del ítem funcionará en el juego.",
                    technicalDetail = "Identifier: $identifier\n" +
                        "Conflicto entre: ${owners.joinToString(", ") { it.name }}\n" +
                        "El ítem del addon con mayor prioridad será el que " +
                        "aparezca en el juego.",
                    affectedAddonIds = owners.map { it.id },
                    affectedFile = "items/$identifier.json",
                    resolution = ConflictResolution.UNRESOLVED,
                    canBeDismissed = true
                )
            )
        }
        return conflicts
    }

    // ─── 4. CONFLICTOS DE RECETAS ────────────────────────────
    private fun detectRecipeConflicts(active: List<Addon>): List<Conflict> {
        val conflicts = mutableListOf<Conflict>()
        val identifierMap = mutableMapOf<String, MutableList<Addon>>()

        active.forEach { addon ->
            addon.recipeIdentifiers.forEach { identifier ->
                identifierMap.getOrPut(identifier) { mutableListOf() }.add(addon)
            }
        }

        identifierMap.filter { it.value.size > 1 }.forEach { (identifier, owners) ->
            conflicts.add(
                Conflict(
                    id = UUID.randomUUID().toString(),
                    type = ConflictType.RECIPE_IDENTIFIER,
                    severity = ConflictSeverity.MEDIUM,
                    title = "Receta Duplicada: $identifier",
                    description = "La receta '$identifier' existe en múltiples " +
                        "addons. Algunos objetos pueden no poder craftearse " +
                        "correctamente.",
                    technicalDetail = "Identifier: $identifier\n" +
                        "Definida en: ${owners.joinToString(", ") { it.name }}",
                    affectedAddonIds = owners.map { it.id },
                    affectedFile = "recipes/$identifier.json",
                    resolution = ConflictResolution.UNRESOLVED,
                    canBeDismissed = true
                )
            )
        }
        return conflicts
    }

    // ─── 5. SOLAPAMIENTO DE ARCHIVOS ─────────────────────────
    private fun detectFileOverlaps(active: List<Addon>): List<Conflict> {
        val conflicts = mutableListOf<Conflict>()
        val fileMap = mutableMapOf<String, MutableList<Addon>>()

        active.forEach { addon ->
            addon.files.forEach { file ->
                val fileName = file.substringAfterLast("/")

                // PackForge REGENERA estos archivos al exportar (manifiestos
                // fusionados por ManifestGenerator y pack_icon.png desde la
                // portada personalizada), así que no son conflictos reales
                // entre addons y no deben mostrarse como advertencias.
                if (fileName == "manifest.json" || fileName == "pack_icon.png") {
                    return@forEach
                }

                // Solo archivos importantes, ignorar subdirectorios vacíos
                if (file.endsWith(".json") || file.endsWith(".js") ||
                    file.endsWith(".png") || file.endsWith(".ogg") ||
                    file.endsWith(".fsb")) {
                    fileMap.getOrPut(file) { mutableListOf() }.add(addon)
                }
            }
        }

        fileMap.filter { it.value.size > 1 }.forEach { (file, owners) ->
            val severity = when {
                file.contains("scripts/") -> ConflictSeverity.CRITICAL
                file.contains("entities/player") -> ConflictSeverity.CRITICAL
                file.contains("ui/hud_screen") -> ConflictSeverity.HIGH
                file.contains("entities/") -> ConflictSeverity.HIGH
                file.contains("terrain_texture") -> ConflictSeverity.MEDIUM
                file.contains("textures/") -> ConflictSeverity.MEDIUM
                file.contains("sounds/") -> ConflictSeverity.LOW
                file.contains("models/") -> ConflictSeverity.LOW
                else -> ConflictSeverity.WARNING
            }

            val title = when {
                file.contains("entities/player") ->
                    "Conflicto Crítico: Archivo del Jugador"
                file.contains("terrain_texture") ->
                    "Conflicto de Texturas de Terreno"
                file.contains("ui/") ->
                    "Conflicto en Interfaz de Usuario"
                file.contains("sounds/") ->
                    "Conflicto de Sonidos"
                else -> "Archivo Compartido: ${file.substringAfterLast("/")}"
            }

            val description = when {
                file.contains("entities/player") ->
                    "Dos addons modifican el mismo archivo del jugador. " +
                    "Esto puede causar que el jugador no funcione correctamente."
                file.contains("terrain_texture") ->
                    "Dos addons reemplazan las texturas del terreno. " +
                    "Solo un pack de texturas puede aplicarse a la vez."
                file.contains("ui/") ->
                    "Dos addons modifican la misma pantalla de la interfaz. " +
                    "La UI puede quedar visualmente rota."
                file.contains("sounds/") ->
                    "Dos addons definen los mismos sonidos. " +
                    "Algunos sonidos pueden no reproducirse."
                else ->
                    "El archivo '${file.substringAfterLast("/")}' existe " +
                    "en múltiples addons y solo una versión puede usarse."
            }

            conflicts.add(
                Conflict(
                    id = UUID.randomUUID().toString(),
                    type = ConflictType.FILE_OVERLAP,
                    severity = severity,
                    title = title,
                    description = description,
                    technicalDetail = "Archivo: $file\n" +
                        "Presente en: ${owners.joinToString(", ") { it.name }}\n" +
                        "El addon con mayor prioridad en la lista define " +
                        "qué versión del archivo se usa.",
                    affectedAddonIds = owners.map { it.id },
                    affectedFile = file,
                    resolution = ConflictResolution.UNRESOLVED,
                    canBeDismissed = severity == ConflictSeverity.LOW ||
                                     severity == ConflictSeverity.WARNING
                )
            )
        }
        return conflicts
    }

    // ─── 6. INCOMPATIBILIDAD DE VERSIONES ────────────────────
    private fun detectVersionMismatches(active: List<Addon>): List<Conflict> {
        val conflicts = mutableListOf<Conflict>()
        if (active.size < 2) return conflicts

        // Encontrar la versión mínima más alta requerida
        val maxMinVersion = active.maxByOrNull { addon ->
            addon.minEngineVersion.getOrElse(0){1} * 10000 +
            addon.minEngineVersion.getOrElse(1){20} * 100 +
            addon.minEngineVersion.getOrElse(2){0}
        }?.minEngineVersion ?: return conflicts

        // Addons que requieren versiones muy distintas
        active.forEach { addon ->
            val addonMajor = addon.minEngineVersion.getOrElse(0){1}
            val maxMajor = maxMinVersion.getOrElse(0){1}
            val addonMinor = addon.minEngineVersion.getOrElse(1){20}
            val maxMinor = maxMinVersion.getOrElse(1){20}

            if (maxMajor > addonMajor ||
                (maxMajor == addonMajor && maxMinor - addonMinor > 10)) {
                conflicts.add(
                    Conflict(
                        id = UUID.randomUUID().toString(),
                        type = ConflictType.VERSION_MISMATCH,
                        severity = ConflictSeverity.WARNING,
                        title = "Advertencia de Versión: ${addon.name}",
                        description = "'${addon.name}' fue diseñado para " +
                            "Minecraft ${addon.minEngineVersion.joinToString(".")}. " +
                            "Otros addons requieren versiones más recientes. " +
                            "Puede haber incompatibilidades.",
                        technicalDetail = "Versión mínima de '${addon.name}': " +
                            addon.minEngineVersion.joinToString(".") + "\n" +
                            "Versión más alta requerida en el modpack: " +
                            maxMinVersion.joinToString("."),
                        affectedAddonIds = listOf(addon.id),
                        affectedFile = "manifest.json",
                        resolution = ConflictResolution.UNRESOLVED,
                        canBeDismissed = true
                    )
                )
            }
        }
        return conflicts
    }

    // ─── 7. UUID DUPLICADO EN MANIFESTS ──────────────────────
    private fun detectManifestUuidConflicts(active: List<Addon>): List<Conflict> {
        val conflicts = mutableListOf<Conflict>()
        val uuidMap = mutableMapOf<String, MutableList<Addon>>()

        active.forEach { addon ->
            if (addon.manifestUuid.isNotBlank()) {
                uuidMap.getOrPut(addon.manifestUuid) { mutableListOf() }.add(addon)
            }
        }

        uuidMap.filter { it.value.size > 1 }.forEach { (uuid, owners) ->
            conflicts.add(
                Conflict(
                    id = UUID.randomUUID().toString(),
                    type = ConflictType.MANIFEST_UUID,
                    severity = ConflictSeverity.CRITICAL,
                    title = "UUID Duplicado en Manifests",
                    description = "Dos addons tienen el mismo UUID en su " +
                        "manifest.json. Minecraft los tratará como el mismo " +
                        "addon y uno sobrescribirá al otro completamente.",
                    technicalDetail = "UUID duplicado: $uuid\n" +
                        "Presente en: ${owners.joinToString(", ") { it.name }}\n" +
                        "PackForge asignará un nuevo UUID único al exportar.",
                    affectedAddonIds = owners.map { it.id },
                    affectedFile = "manifest.json",
                    resolution = ConflictResolution.UNRESOLVED,
                    canBeDismissed = false
                )
            )
        }
        return conflicts
    }

    // ─── COMPATIBILIDAD GENERAL ──────────────────────────────
    fun getCompatibilityScore(addons: List<Addon>): Int {
        val conflicts = analyze(addons)
        if (conflicts.isEmpty()) return 100
        val penalty = conflicts.sumOf { conflict ->
            when (conflict.severity) {
                ConflictSeverity.CRITICAL -> 30
                ConflictSeverity.HIGH     -> 15
                ConflictSeverity.MEDIUM   -> 8
                ConflictSeverity.LOW      -> 3
                ConflictSeverity.WARNING  -> 1
            }
        }
        return maxOf(0, 100 - penalty)
    }
}
