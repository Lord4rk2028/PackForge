package com.packforge.app.domain.engine

import com.packforge.app.domain.model.Addon
import com.packforge.app.domain.model.Conflict
import com.packforge.app.domain.model.ConflictResolution
import com.packforge.app.domain.model.ConflictSeverity
import com.packforge.app.domain.model.ConflictType
import java.util.UUID

object ConflictEngine {

    fun analyze(addons: List<Addon>, resolutions: Map<String, String> = emptyMap()): List<Conflict> {
        val active = addons.filter { it.enabled }
        if (active.size < 2) return emptyList()

        // Mapa id → addon para resolver nombres rápido
        val addonMap = active.associateBy { it.id }

        val conflicts = mutableListOf<Conflict>()

        conflicts += detectScriptConflicts(active)
        conflicts += detectEntityConflicts(active)
        conflicts += detectItemConflicts(active)
        conflicts += detectRecipeConflicts(active)
        conflicts += detectFileOverlaps(active)
        conflicts += detectLootTableConflicts(active)
        conflicts += detectParticleConflicts(active)
        conflicts += detectMaterialInstanceConflicts(active)
        conflicts += detectSoundDefinitionConflicts(active)
        conflicts += detectAnimationControllerConflicts(active)
        conflicts += detectTextureConflicts(active)
        conflicts += detectFormatVersionConflicts(active)
        conflicts += detectManifestUuidConflicts(active)

        // Eliminar duplicados por combinación de addons y archivo
        val deduped = conflicts.distinctBy { c -> stableKey(c) }

        // ⭐ Filtrar conflictos con un solo addon afectado (sin sentido para resolver)
        // y conflictos donde TODOS los addons afectados son del MISMO pack (RP+BP).
        // Cuando un addon se importa como RP y BP por separado, generan miles de
        // falsos conflictos entre ellos. Si comparten la misma clave de pareja
        // (mismo nombre normalizado → "My Addon BP" y "My Addon RP" → "my addon")
        // son partes del mismo addon y no tienen sentido como conflicto.
        val filtered = deduped.filter { c ->
            if (c.affectedAddonIds.size < 2) return@filter false

            // Clave de pareja de CADA addon afectado. Solo se descarta cuando
            // TODOS tienen clave conocida y coinciden; si alguna queda vacía no
            // se puede afirmar que sean el mismo pack y el conflicto se conserva.
            // (Antes se filtraban las claves vacías y luego se comparaba, así que
            // un único nombre reconocible bastaba para borrar conflictos reales:
            // "Addon A" → "a" y "Addon B" → "" dejaba pairKeys = ["a"] y se
            // descartaba TODO.)
            val pairKeys = c.affectedAddonIds.map { id ->
                addonMap[id]?.name
                    ?.let { com.packforge.app.ui.components.normalizedPairKey(it) }
                    .orEmpty()
            }
            val isSamePackPair = pairKeys.isNotEmpty() &&
                pairKeys.none { it.isBlank() } &&
                pairKeys.distinct().size == 1
            !isSamePackPair
        }

        // ⭐ IDs ESTABLES: el id se deriva de tipo + archivo + addons afectados.
        // Antes se usaban UUIDs aleatorios, y cada recálculo regeneraba ids nuevos,
        // dejando huérfano el mapa de resoluciones (la UI parpadeaba "resuelto"
        // y luego el conflicto reaparecía sin resolver).
        return filtered.map { c ->
            val stable = c.copy(id = stableKey(c))
            val res = resolutions[stable.id]
            when {
                res == null -> autoResolve(stable, addonMap)
                res == "dismiss" -> stable.copy(
                    resolution = ConflictResolution.DISMISSED,
                    isDismissed = true
                )
                res == "merge" -> stable.copy(
                    resolution = ConflictResolution.MERGE,
                    winnerAddonId = null
                )
                else -> stable.copy(
                    resolution = ConflictResolution.FIRST_WINS,
                    winnerAddonId = res
                )
            }
        }
    }

    /**
     * Brecha mínima de versiones MENORES para avisar de incompatibilidad de
     * formato. Por debajo, los addons conviven sin problema (un pack de 1.16
     * corre igual en 1.21). A partir de 8 (p. ej. 1.9 → 1.17) sí hay cambios
     * de estructura que merecen aviso.
     */
    private const val MIN_MINOR_GAP_FOR_FORMAT_WARNING = 8

    /**
     * Conflictos del tipo "gana el último": dos addons definen el MISMO
     * identificador/archivo y la fusión copia en orden sobrescribiendo, así que
     * el addon con [Addon.priority] más alta se queda con el contenido.
     *
     * Quedan fuera: UUID de manifest (requiere regenerar UUIDs), scripts,
     * desajustes de versión y brechas de formato (no son "gana uno").
     */
    private val AUTO_RESOLVABLE_TYPES = setOf(
        ConflictType.FILE_OVERLAP,
        ConflictType.ENTITY_IDENTIFIER,
        ConflictType.ITEM_IDENTIFIER,
        ConflictType.RECIPE_IDENTIFIER,
        ConflictType.TEXTURE_ATLAS,
        ConflictType.SOUND_OVERLAP,
        ConflictType.LOOT_TABLE,
        ConflictType.PARTICLE_EFFECT,
        ConflictType.MATERIAL_INSTANCE,
        ConflictType.SOUND_DEFINITION,
        ConflictType.ANIMATION_CONTROLLER,
        ConflictType.PLAYER_JSON_OVERRIDE
    )

    /**
     * Resuelve por prioridad cuando existe un ganador ÚNICO.
     *
     * Solo se marca como resuelto si la prioridad más alta es estrictamente
     * mayor que la siguiente: con empate no hay criterio objetivo y la decisión
     * vuelve al usuario.
     */
    private fun autoResolve(
        conflict: Conflict,
        addonMap: Map<String, Addon>
    ): Conflict {
        if (conflict.type !in AUTO_RESOLVABLE_TYPES) return conflict

        val ranked = conflict.affectedAddonIds
            .mapNotNull { id -> addonMap[id]?.let { addon -> addon.priority to addon.id } }
            .sortedByDescending { it.first }
        if (ranked.size < 2) return conflict

        val winner = ranked[0]
        val runnerUp = ranked[1]
        if (winner.first == runnerUp.first) return conflict

        return conflict.copy(
            resolution = ConflictResolution.FIRST_WINS,
            winnerAddonId = winner.second,
            isAutoResolved = true
        )
    }

    /** Clave estable y única por conflicto (sobrevive a recalculos). */
    private fun stableKey(c: Conflict): String =
        c.type.name + "_" + c.affectedFile + "_" +
        c.affectedAddonIds.sorted().joinToString(",")

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
                // portada personalizada), así que no son conflictos reales.
                // También ignoramos languages.json y archivos .lang ya que
                // BedrockCriticalFilesMerger los fusiona automáticamente.
                if (fileName == "manifest.json" || fileName == "pack_icon.png" ||
                    fileName == "languages.json" || file.endsWith(".lang")) {
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

    // ─── 6. CONFLICTOS DE LOOT TABLES ─────────────────────────
    private fun detectLootTableConflicts(active: List<Addon>): List<Conflict> {
        val conflicts = mutableListOf<Conflict>()
        val identifierMap = mutableMapOf<String, MutableList<Addon>>()

        active.forEach { addon ->
            addon.lootTableIdentifiers.forEach { identifier ->
                identifierMap.getOrPut(identifier) { mutableListOf() }.add(addon)
            }
        }

        identifierMap.filter { it.value.size > 1 && !isVanillaIdentifier("loot_table", it.key) }.forEach { (identifier, owners) ->
            conflicts.add(
                Conflict(
                    id = UUID.randomUUID().toString(),
                    type = ConflictType.LOOT_TABLE,
                    severity = ConflictSeverity.HIGH,
                    title = "Loot Table Duplicado: $identifier",
                    description = "La loot table '$identifier' está definida en " +
                        "${owners.size} addons. Solo una versión funcionará.",
                    technicalDetail = "Identifier: $identifier\n" +
                        "Definida en: ${owners.joinToString(", ") { it.name }}",
                    affectedAddonIds = owners.map { it.id },
                    affectedFile = "loot_tables/$identifier.json",
                    resolution = ConflictResolution.UNRESOLVED,
                    canBeDismissed = true
                )
            )
        }
        return conflicts
    }

    // ─── 7. CONFLICTOS DE SOUND DEFINITIONS ───────────────────
    private fun detectSoundDefinitionConflicts(active: List<Addon>): List<Conflict> {
        val conflicts = mutableListOf<Conflict>()
        val identifierMap = mutableMapOf<String, MutableList<Addon>>()

        active.forEach { addon ->
            addon.soundDefinitionIdentifiers.forEach { identifier ->
                identifierMap.getOrPut(identifier) { mutableListOf() }.add(addon)
            }
        }

        identifierMap.filter { it.value.size > 1 && !isVanillaIdentifier("sound_definition", it.key) }.forEach { (identifier, owners) ->
            conflicts.add(
                Conflict(
                    id = UUID.randomUUID().toString(),
                    type = ConflictType.SOUND_DEFINITION,
                    severity = ConflictSeverity.HIGH,
                    title = "Sound Definition Duplicado: $identifier",
                    description = "La sound definition '$identifier' está definida en " +
                        "${owners.size} addons. Esto puede causar que los sonidos " +
                        "no se reproduzcan correctamente.",
                    technicalDetail = "Identifier: $identifier\n" +
                        "Definida en: ${owners.joinToString(", ") { it.name }}",
                    affectedAddonIds = owners.map { it.id },
                    affectedFile = "sounds/$identifier.json",
                    resolution = ConflictResolution.UNRESOLVED,
                    canBeDismissed = true
                )
            )
        }
        return conflicts
    }

    // ─── 8. CONFLICTOS DE ANIMATION CONTROLLERS ───────────────
    private fun detectAnimationControllerConflicts(active: List<Addon>): List<Conflict> {
        val conflicts = mutableListOf<Conflict>()
        val identifierMap = mutableMapOf<String, MutableList<Addon>>()

        active.forEach { addon ->
            addon.animationControllerIdentifiers.forEach { identifier ->
                identifierMap.getOrPut(identifier) { mutableListOf() }.add(addon)
            }
        }

        identifierMap.filter { it.value.size > 1 && !isVanillaIdentifier("animation_controller", it.key) }.forEach { (identifier, owners) ->
            conflicts.add(
                Conflict(
                    id = UUID.randomUUID().toString(),
                    type = ConflictType.ANIMATION_CONTROLLER,
                    severity = ConflictSeverity.HIGH,
                    title = "Animation Controller Duplicado: $identifier",
                    description = "El animation controller '$identifier' está definido en " +
                        "${owners.size} addons. Esto puede causar que las animaciones " +
                        "se comporten de forma incorrecta.",
                    technicalDetail = "Identifier: $identifier\n" +
                        "Definido en: ${owners.joinToString(", ") { it.name }}",
                    affectedAddonIds = owners.map { it.id },
                    affectedFile = "animation_controllers/$identifier.json",
                    resolution = ConflictResolution.UNRESOLVED,
                    canBeDismissed = true
                )
            )
        }
        return conflicts
    }

    // ─── 6. INCOMPATIBILIDAD DE VERSIÓN DE FORMATO ───────────
    /**
     * Brecha de versión de motor entre los addons del modpack.
     *
     * OJO: `min_engine_version` no significa "para qué versión se diseñó el
     * addon", sino **la versión mínima del motor que necesita**. Un addon con
     * 1.16 funciona igual en 1.21, así que la mayoría de combinaciones NO son
     * un conflicto. Solo importa cuando conviven extremos muy separados, donde
     * Bedrock cambió el formato de los JSON (bloques, ítems, entidades).
     *
     * Por eso se emite **un solo conflicto para todo el modpack** (no uno por
     * addon, que es lo que hacía que "todos los addons" mostraran el aviso) y
     * únicamente si la brecha alcanza [MIN_MINOR_GAP_FOR_FORMAT_WARNING]
     * versiones menores o si cambia la versión mayor.
     *
     * Criterio de brecha (según reportes reales):
     *  · 1.16 → 1.21 (5 versiones menores): normal, NO se reporta.
     *  · 1.9  → 1.17 (8 versiones menores): brecha grande, SÍ se reporta.
     */
    private fun detectFormatVersionConflicts(active: List<Addon>): List<Conflict> {
        if (active.size < 2) return emptyList()

        val oldest = active.minByOrNull { formatScore(it.minEngineVersion) } ?: return emptyList()
        val newest = active.maxByOrNull { formatScore(it.minEngineVersion) } ?: return emptyList()

        val oldVersion = oldest.minEngineVersion
        val newVersion = newest.minEngineVersion
        if (oldVersion == newVersion) return emptyList()

        val oldMajor = oldVersion.getOrElse(0) { 1 }
        val oldMinor = oldVersion.getOrElse(1) { 20 }
        val newMajor = newVersion.getOrElse(0) { 1 }
        val newMinor = newVersion.getOrElse(1) { 20 }

        val majorGap = newMajor - oldMajor
        val minorGap = majorGap * 100 + (newMinor - oldMinor)

        // Cambio de versión mayor = formato distinto con seguridad.
        val isMajorChange = majorGap >= 1
        if (!isMajorChange && minorGap < MIN_MINOR_GAP_FOR_FORMAT_WARNING) return emptyList()

        // Solo los addons de los DOS extremos son el conflicto: marcar todos los
        // intermedios es exactamente el ruido que se quería eliminar.
        val affected = buildList {
            active.filter { it.minEngineVersion == oldVersion }.forEach { add(it.id) }
            active.filter { it.minEngineVersion == newVersion }
                .map { it.id }
                .filterNot { it in this }
                .forEach { add(it) }
        }
        if (affected.size < 2) return emptyList()

        val oldNames = active.filter { it.minEngineVersion == oldVersion }
            .joinToString(", ") { it.name }
        val newNames = active.filter { it.minEngineVersion == newVersion }
            .joinToString(", ") { it.name }
        val oldLabel = oldVersion.joinToString(".")
        val newLabel = newVersion.joinToString(".")

        val gapLabel = if (isMajorChange) {
            "un cambio de versión mayor ($oldMajor.x → $newMajor.x)"
        } else {
            "$minorGap versiones menores"
        }

        return listOf(
            Conflict(
                id = UUID.randomUUID().toString(),
                type = ConflictType.FORMAT_VERSION_INCOMPATIBLE,
                severity = ConflictSeverity.MEDIUM,
                title = "Brecha de formato: $oldLabel → $newLabel",
                description = "Conviven addons con versiones mínimas muy separadas " +
                    "($oldLabel frente a $newLabel). Los más antiguos pueden usar " +
                    "estructuras de JSON que las versiones recientes de Bedrock ya " +
                    "no reconocen, aunque sigan cargando.",
                technicalDetail = "Addons en $oldLabel: $oldNames\n" +
                    "Addons en $newLabel: $newNames\n" +
                    "Brecha: $gapLabel",
                affectedAddonIds = affected,
                affectedFile = "manifest.json",
                resolution = ConflictResolution.UNRESOLVED,
                canBeDismissed = true
            )
        )
    }

    /** Puntuación comparable de una versión `[major, minor, patch]`. */
    private fun formatScore(version: List<Int>): Int =
        version.getOrElse(0) { 1 } * 10000 +
            version.getOrElse(1) { 20 } * 100 +
            version.getOrElse(2) { 0 }

    // ─── 7. CONFLICTOS DE PARTÍCULAS ─────────────────────────
    /**
     * Dos addons definen el mismo identificador de efecto de partícula.
     * Bedrock resuelve por orden de carga, así que solo uno sobrevive: el otro
     * addon mostrará el efecto equivocado (o ninguno).
     */
    private fun detectParticleConflicts(active: List<Addon>): List<Conflict> {
        val conflicts = mutableListOf<Conflict>()
        val identifierMap = mutableMapOf<String, MutableList<Addon>>()

        active.forEach { addon ->
            addon.particleIdentifiers.forEach { identifier ->
                identifierMap.getOrPut(identifier) { mutableListOf() }.add(addon)
            }
        }

        val fileMap = mutableMapOf<String, MutableList<Addon>>()
        active.forEach { addon ->
            val allFiles = addon.files + addon.behaviorFiles + addon.resourceFiles
            allFiles.forEach { filepath ->
                val lower = filepath.lowercase()
                if (lower.contains("particles/") && lower.endsWith(".json")) {
                    fileMap.getOrPut(filepath) { mutableListOf() }.add(addon)
                }
            }
        }

        identifierMap.filter { it.value.size > 1 }.forEach { (identifier, owners) ->
            conflicts.add(
                Conflict(
                    id = UUID.randomUUID().toString(),
                    type = ConflictType.PARTICLE_EFFECT,
                    severity = ConflictSeverity.MEDIUM,
                    title = "Partícula Duplicada: $identifier",
                    description = "La partícula '$identifier' está definida en " +
                        "${owners.size} addons distintos. Solo un efecto puede " +
                        "existir por identificador en Bedrock.",
                    technicalDetail = "Identifier: $identifier\n" +
                        "Conflicto entre: ${owners.joinToString(", ") { it.name }}",
                    affectedAddonIds = owners.map { it.id },
                    affectedFile = "particles/$identifier.json",
                    resolution = ConflictResolution.UNRESOLVED,
                    canBeDismissed = true
                )
            )
        }

        fileMap.filter { it.value.size > 1 }.forEach { (filepath, owners) ->
            val alreadyDetected = conflicts.any { c ->
                c.type == ConflictType.PARTICLE_EFFECT && c.affectedFile == filepath
            }
            if (!alreadyDetected) {
                conflicts.add(
                    Conflict(
                        id = UUID.randomUUID().toString(),
                        type = ConflictType.PARTICLE_EFFECT,
                        severity = ConflictSeverity.MEDIUM,
                        title = "Archivo de partícula duplicado: ${filepath.substringAfterLast("/")}",
                        description = "El archivo '$filepath' está presente en " +
                            "${owners.size} addons. El último cargado sobrescribirá a los anteriores.",
                        technicalDetail = "Ruta duplicada: $filepath\n" +
                            "Presente en: ${owners.joinToString(", ") { it.name }}",
                        affectedAddonIds = owners.map { it.id },
                        affectedFile = filepath,
                        resolution = ConflictResolution.UNRESOLVED,
                        canBeDismissed = true
                    )
                )
            }
        }

        return conflicts
    }

    // ─── 8. CONFLICTOS DE MATERIAL INSTANCES ─────────────────
    /**
     * Mismo identificador de material instance en dos addons. El material
     * define cómo se renderiza una cara (alpha_test, blend, iluminación), así
     * que un ganador inesperado cambia el aspecto del bloque.
     */
    private fun detectMaterialInstanceConflicts(active: List<Addon>): List<Conflict> {
        val conflicts = mutableListOf<Conflict>()
        val identifierMap = mutableMapOf<String, MutableList<Addon>>()

        active.forEach { addon ->
            addon.materialInstanceIdentifiers.forEach { identifier ->
                identifierMap.getOrPut(identifier) { mutableListOf() }.add(addon)
            }
        }

        identifierMap.filter { it.value.size > 1 }.forEach { (identifier, owners) ->
            conflicts.add(
                Conflict(
                    id = UUID.randomUUID().toString(),
                    type = ConflictType.MATERIAL_INSTANCE,
                    severity = ConflictSeverity.HIGH,
                    title = "Material Instance Duplicado: $identifier",
                    description = "El material '$identifier' está definido en " +
                        "${owners.size} addons distintos. Cambia cómo se renderiza " +
                        "la cara (transparencia, iluminación), así que solo uno puede ganar.",
                    technicalDetail = "Identifier: $identifier\n" +
                        "Conflicto entre: ${owners.joinToString(", ") { it.name }}\n" +
                        "Los materiales viven en materials/*.material, un archivo " +
                        "compartido: revisa el orden de prioridad.",
                    affectedAddonIds = owners.map { it.id },
                    affectedFile = "materials/$identifier.material",
                    resolution = ConflictResolution.UNRESOLVED,
                    canBeDismissed = true
                )
            )
        }

        return conflicts
    }

    // ─── 9. UUID DUPLICADO EN MANIFESTS ──────────────────────
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

    // ─── 8. CONFLICTOS DE TEXTURAS ───────────────────────────
    fun detectTextureConflicts(addons: List<Addon>): List<Conflict> {
        val conflicts = mutableListOf<Conflict>()
        val textureMap = mutableMapOf<String, MutableList<Addon>>()

        addons.forEach { addon ->
            val allFiles = addon.files + addon.behaviorFiles + addon.resourceFiles
            allFiles.forEach { filepath ->
                val lower = filepath.lowercase()
                if (lower.contains("texture") || lower.contains("terrain") || lower.contains("item_texture")) {
                    textureMap.getOrPut(filepath) { mutableListOf() }.add(addon)
                }
            }
        }

        textureMap.filter { it.value.size > 1 }.forEach { (texturePath, owners) ->
            val addonNames = owners.map { it.name }
            conflicts.add(
                Conflict(
                    id = "texture_${texturePath.hashCode()}",
                    type = ConflictType.TEXTURE_ATLAS,
                    severity = ConflictSeverity.HIGH,
                    title = "Conflicto de Textura: ${texturePath.substringAfterLast("/")}",
                    description = "Conflicto de textura: $texturePath - usado por ${addonNames.joinToString(", ")}",
                    technicalDetail = "Ruta duplicada: $texturePath\nPresente en: ${addonNames.joinToString(", ")}\nEn Minecraft Bedrock las texturas comparten el atlas; la última cargada sobrescribe a las anteriores. La prioridad en la lista determina cuál prevalece.",
                    affectedAddonIds = owners.map { it.id },
                    affectedFile = texturePath,
                    resolution = ConflictResolution.UNRESOLVED,
                    canBeDismissed = true
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


    private val VANILLA_ANIMATION_IDENTIFIERS = setOf(
        "animation.look_at_target", "common_look_at", "animation.base_pose",
        "animation.humanoid", "animation.villager", "animation.villager_v2",
        "animation.armor", "animation.biped", "animation.creeper", "animation.zombie",
        "animation.skeleton", "animation.parrot", "animation.ghast", "animation.blaze",
        "animation.enderman", "animation.spider", "animation.quadruped", "animation.irongolem",
        "animation.guardian", "animation.shulker", "animation.slime", "animation.magmacube",
        "animation.wither", "animation.dragon", "animation.agent", "animation.armor_stand",
        "animation.player", "animation.frog", "animation.goat", "animation.panda",
        "animation.polar_bear", "animation.tadpole", "animation.trident"
    )
    private val VANILLA_RENDER_CONTROLLER_IDENTIFIERS = setOf(
        "controller.render.default", "controller.render.item_sprite",
        "controller.render.block", "controller.render.armor_stand",
        "controller.render.player.first_person", "controller.render.player.third_person",
        "controller.render.locator", "controller.render.ui_hurt"
    )
    private val VALID_SOUNDS_JSON_ROOT_KEYS = setOf(
        "entity_sounds", "block_sounds", "individual_event_sounds", "interactive_sounds"
    )

    private val VANILLA_EXPLICIT_KEYS: Set<String> = setOf("spawn_egg_agent", "spawn_egg_allay", "spawn_egg_armadillo", "spawn_egg_axolotl", "spawn_egg_bat", "spawn_egg_bee", "spawn_egg_blaze", "spawn_egg_bogged", "spawn_egg_breeze", "spawn_egg_camel", "spawn_egg_camel_husk", "spawn_egg_cat", "spawn_egg_cave_spider", "spawn_egg_chicken", "spawn_egg_cod", "spawn_egg_copper_golem", "spawn_egg_cow", "spawn_egg_creaking", "spawn_egg_creeper", "spawn_egg_dolphin", "spawn_egg_donkey", "spawn_egg_drowned", "spawn_egg_elder_guardian", "spawn_egg_ender_dragon", "spawn_egg_enderman", "spawn_egg_endermite", "spawn_egg_evoker", "spawn_egg_fox", "spawn_egg_frog", "spawn_egg_ghast", "spawn_egg_glow_squid", "spawn_egg_goat", "spawn_egg_guardian", "spawn_egg_happy_ghast", "spawn_egg_hoglin", "spawn_egg_horse", "spawn_egg_husk", "spawn_egg_iron_golem", "spawn_egg_llama", "spawn_egg_magma_cube", "spawn_egg_mooshroom", "spawn_egg_mule", "spawn_egg_nautilus", "spawn_egg_ocelot", "spawn_egg_panda", "spawn_egg_parched", "spawn_egg_parrot", "spawn_egg_phantom", "spawn_egg_pig", "spawn_egg_piglin", "spawn_egg_piglin_brute", "spawn_egg_pillager", "spawn_egg_polar_bear", "spawn_egg_pufferfish", "spawn_egg_rabbit", "spawn_egg_ravager", "spawn_egg_salmon", "spawn_egg_sheep", "spawn_egg_shulker", "spawn_egg_silverfish", "spawn_egg_skeleton", "spawn_egg_skeleton_horse", "spawn_egg_slime", "spawn_egg_sniffer", "spawn_egg_snow_golem", "spawn_egg_spider", "spawn_egg_squid", "spawn_egg_stray", "spawn_egg_strider", "spawn_egg_sulfur_cube", "spawn_egg_tadpole", "spawn_egg_trader_llama", "spawn_egg_tropicalfish", "spawn_egg_turtle", "spawn_egg_vex", "spawn_egg_villager", "spawn_egg_vindicator", "spawn_egg_wandering_trader", "spawn_egg_warden", "spawn_egg_witch", "spawn_egg_wither", "spawn_egg_wither_skeleton", "spawn_egg_wolf", "spawn_egg_zoglin", "spawn_egg_zombie", "spawn_egg_zombie_horse", "spawn_egg_zombie_nautilus", "spawn_egg_zombie_villager", "spawn_egg_zombified_piglin", "particle.soul_escape", "particle.soul_escape.loud", "particle.soul_escape.quiet")

    private val VANILLA_ANIMATION_PREFIXES = listOf(
        "animation.actor.", "animation.agent.", "animation.allay.", "animation.armadillo.",
        "animation.armor.", "animation.armor_stand.", "animation.arrow.", "animation.axolotl.",
        "animation.bat.", "animation.bee.", "animation.blaze.", "animation.bow.",
        "animation.breeze.", "animation.camel.", "animation.camel_husk.", "animation.cat.",
        "animation.chicken.", "animation.cod.", "animation.common.", "animation.copper_golem.",
        "animation.cow.", "animation.creaking.", "animation.creeper.", "animation.crossbow.",
        "animation.dolphin.", "animation.drowned.", "animation.elytra.", "animation.ender_crystal.",
        "animation.ender_dragon.", "animation.enderman.", "animation.endermite.", "animation.evocation_fang.",
        "animation.evoker.", "animation.fireworks_rocket.", "animation.fish.", "animation.fox.",
        "animation.frog.", "animation.ghast.", "animation.goat.", "animation.guardian.",
        "animation.happy_ghast.", "animation.hoglin.", "animation.horse.", "animation.humanoid.",
        "animation.idle_", "animation.iron_golem.", "animation.llama.", "animation.magma_cube.",
        "animation.minecart.", "animation.mooshroom.", "animation.nautilus.", "animation.npc.",
        "animation.ocelot.", "animation.panda.", "animation.parrot.", "animation.persona.",
        "animation.phantom.", "animation.pig.", "animation.piglin.", "animation.pillager.",
        "animation.player", "animation.polar_bear.", "animation.polarbear.", "animation.pufferfish.",
        "animation.quadruped.", "animation.rabbit.", "animation.ravager.", "animation.react_",
        "animation.salmon.", "animation.sheep.", "animation.shield.", "animation.shulker",
        "animation.shulker_bullet", "animation.silverfish", "animation.skeleton", "animation.sniffer",
        "animation.snowgolem", "animation.spear", "animation.spider", "animation.spyglass",
        "animation.squid", "animation.strider", "animation.sulfur_cube", "animation.tadpole",
        "animation.trident", "animation.tripod_camera", "animation.tropicalfish", "animation.turtle",
        "animation.vex", "animation.villager", "animation.vindicator", "animation.warden",
        "animation.wind_charge", "animation.witch", "animation.wither_boss", "animation.wither_skeleton",
        "animation.wither_skull", "animation.wolf", "animation.zombie", "controller.animation.",
        "controller.holding_heavy_core", "controller.pose", "common.look_at_target"
    )

    private val VANILLA_RENDER_CONTROLLER_PREFIXES = listOf(
        "controller.render.", "render."
    )

    private fun isVanillaIdentifier(type: String, id: String): Boolean {
        if (id.startsWith("minecraft:")) return true
        if (id in VANILLA_EXPLICIT_KEYS) return true
        return when (type) {
            "animation", "animation_controller" -> {
                VANILLA_ANIMATION_PREFIXES.any { id.startsWith(it) } ||
                VANILLA_RENDER_CONTROLLER_PREFIXES.any { id.startsWith(it) } ||
                id in VANILLA_ANIMATION_IDENTIFIERS ||
                id in VANILLA_RENDER_CONTROLLER_IDENTIFIERS
            }
            "sound_definition" -> id in VALID_SOUNDS_JSON_ROOT_KEYS
            else -> false
        }
    }
}
