package com.packforge.app.domain.engine

import com.packforge.app.domain.model.ConflictSeverity
import com.packforge.app.util.PackForgeLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

/**
 * ═══════════════════════════════════════════════════════════════════════
 * SCRIPT ROUTER ENGINE - Aislamiento y Orquestación de Scripts Bedrock
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Resuelve colisiones de código JavaScript/TypeScript (@minecraft/server y módulos @minecraft)
 * cuando múltiples addons traen scripts de entrada (ej: `scripts/main.js`, `scripts/index.js`).
 *
 * 1. Aísla el árbol de código completo de cada addon en `scripts/modules/<addon_id>/...`,
 *    preservando intactas todas las importaciones relativas internas (`./jobs.js`, `./utils.js`).
 * 2. Recolecta, concilia y armoniza todas las dependencias @minecraft (@minecraft/server,
 *    @minecraft/server-ui, etc.) eligiendo la versión semver más alta y compatible.
 * 3. Escanea código fuente para detectar dependencias @minecraft importadas pero no declaradas.
 * 4. Construye un bootstrapper maestro `scripts/main.js` tolerante a fallos que inicializa cada
 *    módulo de forma aislada, capturando tanto errores síncronos como promesas rechazadas.
 * 5. Actualiza `manifest.json` del BP fusionado con el entry point maestro y las dependencias armonizadas.
 */
object ScriptRouterEngine {
    private const val TAG = "PackForge_ScriptRouter"

    private val SCRIPT_IMPORT_REGEX = Regex(
        """(?:import|from)\s+['"](@minecraft\/[a-zA-Z0-9_\-]+)['"]"""
    )

    private val DEFAULT_MODULE_VERSIONS = mapOf(
        "@minecraft/server" to "1.16.0",
        "@minecraft/server-ui" to "1.3.0",
        "@minecraft/server-gametest" to "1.0.0-beta",
        "@minecraft/server-admin" to "1.0.0-beta",
        "@minecraft/server-net" to "1.0.0-beta",
        "@minecraft/debug-utilities" to "1.0.0-beta",
        "@minecraft/server-editor" to "0.1.0-beta"
    )

    data class ScriptRouteResult(
        val routedCount: Int,
        val entryPoints: List<String>,
        val bootstrapperFile: File?,
        val libraryDeps: Map<String, String> = emptyMap(),
        val issues: List<String> = emptyList(),
        val skippedAddons: List<String> = emptyList()
    )

    data class AddonScriptInfo(
        val addonId: String,
        val sourceDir: File,
        val relativeEntryPath: String,
        val fullModuleEntryRelPath: String
    )

    /**
     * Parsea una versión semver soportando formatos Bedrock: "1.16.0", "2.6.0", "1.3.0-beta", "1.0.0-beta.1"
     */
    internal fun parseSemver(v: String): Triple<List<Int>, Boolean, String> {
        val clean = v.trim().removePrefix("v").removePrefix("V")
        val dashIdx = clean.indexOf('-')
        val numPart = if (dashIdx != -1) clean.substring(0, dashIdx) else clean
        val tag = if (dashIdx != -1) clean.substring(dashIdx + 1) else ""
        val isPre = tag.isNotEmpty()
        val parts = numPart.split('.').map { it.toIntOrNull() ?: 0 }
        return Triple(parts, isPre, tag)
    }

    /**
     * Compara dos cadenas semver.
     * Retorna > 0 si v1 > v2, < 0 si v1 < v2, y 0 si son equivalentes.
     */
    fun compareSemver(v1: String, v2: String): Int {
        val (p1, isPre1, tag1) = parseSemver(v1)
        val (p2, isPre2, tag2) = parseSemver(v2)
        val maxLen = maxOf(p1.size, p2.size, 3)
        for (i in 0 until maxLen) {
            val n1 = p1.getOrElse(i) { 0 }
            val n2 = p2.getOrElse(i) { 0 }
            if (n1 != n2) return n1.compareTo(n2)
        }
        if (isPre1 != isPre2) {
            return if (isPre1) -1 else 1
        }
        return tag1.compareTo(tag2)
    }

    /**
     * Extrae de forma segura la versión de una dependencia JSON, soportando
     * tanto cadenas ("1.16.0") como arrays JSON ([1, 16, 0]).
     */
    fun extractDependencyVersion(dep: JSONObject): String {
        if (!dep.has("version")) return ""
        return when (val vObj = dep.get("version")) {
            is JSONArray -> (0 until vObj.length()).joinToString(".") { vObj.optString(it, "0") }
            is String -> vObj.trim()
            is Number -> vObj.toString()
            else -> vObj.toString().trim()
        }
    }

    /**
     * Recolecta y armoniza todas las dependencias @minecraft entre todos los packs.
     * Selecciona la versión semver más alta para cada módulo único.
     */
    fun harvestAndHarmonizeDependencies(bpDirs: List<File>): Map<String, String> {
        val harmonized = mutableMapOf<String, String>()

        bpDirs.forEach { bpDir ->
            if (!bpDir.isDirectory) return@forEach

            // 1. Extraer del manifest.json
            val manifestFile = File(bpDir, "manifest.json")
            if (manifestFile.exists()) {
                try {
                    val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
                    val dependencies = manifest.optJSONArray("dependencies")
                    if (dependencies != null) {
                        for (i in 0 until dependencies.length()) {
                            val dep = dependencies.optJSONObject(i) ?: continue
                            val modName = dep.optString("module_name", "").trim()
                            if (modName.startsWith("@minecraft/")) {
                                val ver = extractDependencyVersion(dep)
                                if (ver.isNotBlank()) {
                                    val currentMax = harmonized[modName]
                                    if (currentMax == null || compareSemver(ver, currentMax) > 0) {
                                        harmonized[modName] = ver
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    PackForgeLog.w(TAG, "Error leyendo dependencias de manifest en ${bpDir.name}: ${e.message}")
                }
            }

            // 2. Escanear archivos scripts/ para capturar dependencias importadas en código
            val scriptsDir = File(bpDir, "scripts")
            if (scriptsDir.isDirectory) {
                val detectedImports = scanScriptFilesForImports(scriptsDir)
                detectedImports.forEach { modName ->
                    if (!harmonized.containsKey(modName)) {
                        val defaultVer = DEFAULT_MODULE_VERSIONS[modName] ?: "1.0.0"
                        harmonized[modName] = defaultVer
                        PackForgeLog.d(TAG, "Detectada importación en código de '$modName' no declarada en manifest; usando versión $defaultVer")
                    }
                }
            }
        }

        // Siempre garantizar al menos @minecraft/server si hay módulos de scripts
        if (!harmonized.containsKey("@minecraft/server")) {
            harmonized["@minecraft/server"] = DEFAULT_MODULE_VERSIONS["@minecraft/server"] ?: "1.16.0"
        }

        // ⭐ TICKET-CORE-007 — ALINEAR LOS MAJORS DE LA API DE SCRIPTS.
        //
        // Tomar el máximo de CADA módulo por separado produce combinaciones que
        // NO existen. Caso real (log de contenido del usuario):
        //
        //   version conflict for module [@minecraft/server]
        //    [2.6.0] requested by [BetterMC_Real]
        //    [1.3.0] requested by [@minecraft/server-ui-bindings]
        //            requested by [@minecraft/server-ui - 1.3.0]
        //
        // `simple-gravestone` pide server 2.6.0 (usa `system.beforeEvents.startup`
        // y `blockComponentRegistry`, API 2.x) y `SimpleEconomy` pide
        // `server-ui 1.3.0` (que internamente exige server 1.x). Al quedarnos con
        // server 2.6.0 + server-ui 1.3.0 el motor NO puede crear el contexto
        // (`failed to create context`) y **ningún script se ejecuta**: ni la
        // tumba, ni la UI del banco. Los bloques del addon aparecían porque son
        // datos del BP, pero su lógica moría antes de arrancar.
        //
        // Regla: la familia @minecraft/* es una sola API. Si server queda en un
        // major mayor, sus satélites (server-ui, server-net…) no pueden quedarse
        // en el major antiguo. Se elevan al primer release del mismo major.
        val serverVersion = harmonized["@minecraft/server"].orEmpty()
        val serverMajor = parseSemver(serverVersion).first.getOrElse(0) { 0 }
        if (serverMajor >= 2) {
            harmonized.keys.toList().forEach { nombre ->
                if (nombre == "@minecraft/server") return@forEach
                val actual = harmonized[nombre].orEmpty()
                val majorActual = parseSemver(actual).first.getOrElse(0) { 0 }
                if (majorActual in 1 until serverMajor) {
                    val alineada = "$serverMajor.0.0"
                    harmonized[nombre] = alineada
                    PackForgeLog.w(
                        TAG,
                        "🔗 '$nombre' $actual → $alineada para no chocar con " +
                            "@minecraft/server $serverVersion (un mismo plugin no puede " +
                            "pedir dos majors de la API: el motor no crea contexto y los " +
                            "scripts no arrancan)"
                    )
                }
            }
        }

        return harmonized
    }

    /** Petición de versión de un módulo `@minecraft/...` hecha por un addon. */
    data class ApiRequest(val addon: String, val module: String, val version: String)

    /**
     * 3.7 — Recolecta qué versión de cada módulo `@minecraft/...` declara cada
     * addon en su manifiesto, CON su autor. `harvestAndHarmonizeDependencies`
     * solo devuelve el máximo final y pierde la atribución, así que no se puede
     * avisar de quién se queda fuera cuando el máximo cambia de major.
     */
    fun collectApiRequests(bpDirs: List<File>): List<ApiRequest> {
        val peticiones = mutableListOf<ApiRequest>()
        bpDirs.forEach { bpDir ->
            if (!bpDir.isDirectory) return@forEach
            val manifiesto = File(bpDir, "manifest.json")
            if (!manifiesto.isFile) return@forEach
            val addon = AddonOwnerRegistry.ownerOf(bpDir)
                ?.let { AddonOwnerRegistry.labelOf(it) }
                ?: bpDir.name
            val deps = runCatching {
                JSONObject(manifiesto.readText(Charsets.UTF_8)).optJSONArray("dependencies")
            }.getOrNull() ?: return@forEach

            for (i in 0 until deps.length()) {
                val dep = deps.optJSONObject(i) ?: continue
                val modulo = dep.optString("module_name", "").trim()
                if (!modulo.startsWith("@minecraft/")) continue
                val version = extractDependencyVersion(dep)
                if (version.isBlank()) continue
                peticiones += ApiRequest(addon, modulo, version)
            }
        }
        return peticiones
    }

    /**
     * 3.7 — Detecta el caso que NO se puede resolver fusionando: dos addons que
     * necesitan majors distintos de `@minecraft/server`.
     *
     * Un `.mcaddon` con **un solo** Behavior Pack carga **una** versión de la
     * API. La armonización eleva los satélites al major mayor, pero si un addon
     * usa APIs eliminadas en ese major, su código no arranca. La salida real
     * sería empaquetar dos BPs (uno por grupo de compatibilidad), que el motor
     * todavía no genera: por eso se reporta con nombre y apellidos en vez de
     * fingir que la fusión es compatible.
     */
    fun reportApiMajorConflicts(bpDirs: List<File>, harmonized: Map<String, String>) {
        val serverFinal = harmonized["@minecraft/server"] ?: return
        val serverMajor = parseSemver(serverFinal).first.firstOrNull() ?: return

        collectApiRequests(bpDirs)
            .filter { it.module == "@minecraft/server" }
            .groupBy { it.addon }
            .forEach { (addon, peticiones) ->
                val majorsPedidos = peticiones
                    .map { parseSemver(it.version).first.firstOrNull() ?: 0 }
                    .distinct()
                if (majorsPedidos.all { it == serverMajor }) return@forEach

                val versiones = peticiones.map { it.version }.distinct().joinToString()
                ConflictRegistry.logConflict(
                    severity = com.packforge.app.domain.model.ConflictSeverity.MEDIUM,
                    type = "SCRIPT_API_MAJOR_CONFLICT",
                    file = "manifest.json",
                    addon1 = addon,
                    addon2 = "modpack",
                    description = "El addon '$addon' declara @minecraft/server $versiones y el " +
                        "modpack usa $serverFinal. Un solo Behavior Pack carga UNA versión de la " +
                        "API: si '$addon' usa funciones eliminadas en el major $serverMajor, sus " +
                        "scripts no arrancarán (el resto del pack sí). Exportar ese addon en un " +
                        "Behavior Pack aparte —multi-BP— todavía no está implementado."
                )
            }
    }

    /**
     * Escanea archivos de script buscando imports que coincidan con @minecraft.
     */
    fun scanScriptFilesForImports(scriptsDir: File): Set<String> {
        val found = mutableSetOf<String>()
        if (!scriptsDir.isDirectory) return found

        val files = DirIndexCache.index(scriptsDir).allFiles
            .filter { it.extension.lowercase(Locale.ROOT) in SCRIPT_EXTENSIONS }

        files.forEach { file ->
            try {
                val text = file.readText(Charsets.UTF_8)
                SCRIPT_IMPORT_REGEX.findAll(text).forEach { m ->
                    val mod = m.groupValues[1].trim()
                    if (mod.startsWith("@minecraft/")) {
                        found.add(mod)
                    }
                }
            } catch (_: Exception) {
                // Ignorar fallos de lectura de archivos individuales
            }
        }
        return found
    }

    /**
     * Aísla los scripts de cada addon en `scripts/modules/<addon_id>/...`, genera el
     * bootstrapper maestro `scripts/main.js` y actualiza el `manifest.json` fusionado.
     */
    fun routeAndBuildBootstrapper(bpDirs: List<File>, mergedBpDir: File): ScriptRouteResult {
        if (!mergedBpDir.exists()) mergedBpDir.mkdirs()

        val scriptAddons = mutableListOf<AddonScriptInfo>()
        val issues = mutableListOf<String>()
        val skippedAddons = mutableListOf<String>()

        bpDirs.forEachIndexed { index, bpDir ->
            if (!bpDir.isDirectory) return@forEachIndexed

            val manifestFile = File(bpDir, "manifest.json")
            var rawEntryPath = "scripts/main.js"
            var hasScriptModule = false

            if (manifestFile.exists()) {
                try {
                    val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
                    val modules = manifest.optJSONArray("modules")
                    if (modules != null) {
                        for (i in 0 until modules.length()) {
                            val mod = modules.optJSONObject(i) ?: continue
                            val type = mod.optString("type", "").lowercase(Locale.ROOT)
                            if (type == "script" || type == "javascript") {
                                hasScriptModule = true
                                val entry = mod.optString("entry", "").trim()
                                if (entry.isNotEmpty()) {
                                    rawEntryPath = entry.replace('\\', '/')
                                }
                                break
                            }
                        }
                    }
                } catch (e: Exception) {
                    PackForgeLog.w(TAG, "Error leyendo manifest en ${bpDir.name}: ${e.message}")
                }
            }

            val scriptsDir = File(bpDir, "scripts")
            if (!hasScriptModule && !scriptsDir.isDirectory) {
                return@forEachIndexed
            }

            val rawAddonId = bpDir.name
                .replace(Regex("(?i)_?(bp|behavior|packs?)$"), "")
                .replace(Regex("[^A-Za-z0-9_]"), "_")
                .lowercase(Locale.ROOT)
                .ifBlank { "addon_${index + 1}" }

            val addonId = "${rawAddonId}_${index + 1}"
            val moduleDir = File(mergedBpDir, "scripts/modules/$addonId")
            moduleDir.mkdirs()

            // Copiar el árbol completo de scripts preservando subcarpetas
            if (scriptsDir.isDirectory) {
                val cachedFiles = DirIndexCache.index(scriptsDir).allFiles
                for (file in cachedFiles) {
                    val relPath = file.relativeTo(scriptsDir).path.replace('\\', '/')
                    val destFile = File(moduleDir, relPath)
                    destFile.parentFile?.mkdirs()
                    file.copyTo(destFile, overwrite = true)
                }
            } else if (hasScriptModule) {
                // Caso atípico: script declarado fuera de scripts/ (ej: en raíz o dist/)
                val candidateFile = File(bpDir, rawEntryPath.removePrefix("./").removePrefix("/"))
                if (candidateFile.isFile) {
                    val destFile = File(moduleDir, candidateFile.name)
                    destFile.parentFile?.mkdirs()
                    candidateFile.copyTo(destFile, overwrite = true)
                }
            }

            // Normalizar la ruta del entry
            val cleanEntry = rawEntryPath.replace('\\', '/').trim().trimStart('.', '/')
            val entryRelToScripts = if (cleanEntry.startsWith("scripts/")) {
                cleanEntry.removePrefix("scripts/")
            } else cleanEntry

            val entryInModule = File(moduleDir, entryRelToScripts)
            if (!entryInModule.exists()) {
                val candidateMain = sequenceOf("main.js", "index.js", "main.ts", "index.ts")
                    .map { File(moduleDir, it) }
                    .firstOrNull { it.exists() }

                if (candidateMain != null) {
                    val newRel = candidateMain.relativeTo(moduleDir).path.replace('\\', '/')
                    scriptAddons.add(AddonScriptInfo(addonId, bpDir, newRel, "modules/$addonId/$newRel"))
                } else {
                    // Si hay exactamente un archivo .js/.ts en el módulo, usarlo
                    val allModuleFiles = moduleDir.walkTopDown().filter { it.isFile && it.extension.lowercase(Locale.ROOT) in SCRIPT_EXTENSIONS }.toList()
                    if (allModuleFiles.size == 1) {
                        val singleFile = allModuleFiles.first()
                        val singleRel = singleFile.relativeTo(moduleDir).path.replace('\\', '/')
                        scriptAddons.add(AddonScriptInfo(addonId, bpDir, singleRel, "modules/$addonId/$singleRel"))
                    } else {
                        val emptyMain = File(moduleDir, "main.js")
                        emptyMain.writeText("// Stub vacio generado por PackForge para $addonId\n", Charsets.UTF_8)
                        scriptAddons.add(AddonScriptInfo(addonId, bpDir, "main.js", "modules/$addonId/main.js"))
                        issues.add("Script de $addonId no tenia entry point valido; se creo main.js stub")
                    }
                }
            } else {
                val cleanRel = entryInModule.relativeTo(moduleDir).path.replace('\\', '/')
                scriptAddons.add(AddonScriptInfo(addonId, bpDir, cleanRel, "modules/$addonId/$cleanRel"))
            }
        }

        if (scriptAddons.isEmpty()) {
            return ScriptRouteResult(0, emptyList(), null, emptyMap(), issues, skippedAddons)
        }

        val scriptsRoot = File(mergedBpDir, "scripts")
        scriptsRoot.mkdirs()
        val masterBootstrapper = File(scriptsRoot, "main.js")

        // Construir bootstrapper maestro.
        //
        // ⭐ IMPORTS ESTÁTICOS, no import() dinámico.
        //
        // Bedrock documenta y usa el import estático relativo entre archivos del
        // propio pack (tutoriales oficiales de Mojang: `import x from "./file.js"`);
        // el import() DINÁMICO no figura en ninguna documentación de Bedrock y el
        // pack de prueba real (SimpleEconomy + simple-gravestone) salió así: datos
        // del BP cargados (los bloques existían en el juego) pero NI UN SCRIPT se
        // ejecutó. El engine no resolvió el import() dinámico y, al romperse la
        // cadena de promesas del primer addon, el resto tampoco llegó a
        // evaluarse.
        //
        // Con imports estáticos el motor evalúa cada módulo UNA vez, en orden de
        // declaración, en el momento de cargar el pack: sin carreras, sin
        // promesas, y el addon base se registra ANTES que sus DLC.
        val ordered = scriptAddons.sortedWith(Comparator { a, b ->
            val baseA = a.addonId.replace(Regex("_\\d+$"), "")
            val baseB = b.addonId.replace(Regex("_\\d+$"), "")
            when {
                baseA == baseB -> 0
                baseB.startsWith(baseA) -> -1 // a es el base de b: a va primero
                baseA.startsWith(baseB) -> 1
                else -> 0                    // orden original (sort estable)
            }
        })

        val bootstrapperCode = buildString {
            appendLine("// =====================================================================")
            appendLine("// PACKFORGE MASTER SCRIPT BOOTSTRAPPER")
            appendLine("// Auto-generated script router for Bedrock Script API (@minecraft/server)")
            appendLine("// =====================================================================")
            appendLine("// Cada addon vive en scripts/modules/<addon_id>/ con su ruta interna")
            appendLine("// original, para que sus imports relativos sigan resolviéndose.")
            appendLine("// El orden de import garantiza que un addon base se evalúe antes que")
            appendLine("// los addons que dependen de él.")
            ordered.forEach { info ->
                appendLine("// Addon: ${info.addonId}")
                appendLine("import \"./${info.fullModuleEntryRelPath}\";")
            }
        }

        OutputStreamWriter(FileOutputStream(masterBootstrapper), StandardCharsets.UTF_8).use {
            it.write(bootstrapperCode)
        }

        // Armonizar dependencias de todos los BPs
        val harmonizedDeps = harvestAndHarmonizeDependencies(bpDirs)
        // 3.7: avisar de los addons que necesitaban OTRO major de la API.
        reportApiMajorConflicts(bpDirs, harmonizedDeps)

        DirIndexCache.invalidate(mergedBpDir)
        updateMergedBpManifestScriptModule(mergedBpDir, "scripts/main.js", harmonizedDeps)

        // ⭐ TICKET-CORE-004 — AVISO, no conflicto. Nada que decidir aquí: el
        // router es el resultado esperado de fusionar addons con scripts y se
        // reporta aparte en la sección "SCRIPTS". Como MEDIUM, cada fusión con
        // >1 addon con scripts abría un "conflicto pendiente" que arrastraba el
        // semáforo a PROBLEMS y engordaba el contador de conflictos.
        PackForgeLog.d(
            TAG,
            "🔀 Router de scripts (${scriptAddons.size} addon(s): " +
                scriptAddons.joinToString { it.addonId } + "). Dependencias: $harmonizedDeps"
        )

        PackForgeLog.d(TAG, "✅ Bootstrapper maestro de scripts generado en scripts/main.js con ${scriptAddons.size} módulos. Dependencias armonizadas: $harmonizedDeps")

        return ScriptRouteResult(
            routedCount = scriptAddons.size,
            entryPoints = scriptAddons.map { info -> info.fullModuleEntryRelPath },
            bootstrapperFile = masterBootstrapper,
            libraryDeps = harmonizedDeps,
            issues = issues,
            skippedAddons = skippedAddons
        )
    }

    /**
     * Actualiza o inserta el módulo de script en `manifest.json` y armoniza las
     * dependencias @minecraft con las versiones más altas reconciliadas.
     */
    fun updateMergedBpManifestScriptModule(
        mergedBpDir: File,
        bootstrapperRelPath: String,
        harmonizedDeps: Map<String, String> = emptyMap()
    ) {
        val manifestFile = File(mergedBpDir, "manifest.json")
        val manifest = if (manifestFile.exists()) {
            try {
                JSONObject(manifestFile.readText(Charsets.UTF_8))
            } catch (_: Exception) {
                JSONObject()
            }
        } else {
            JSONObject().apply {
                put("format_version", 2)
                put("header", JSONObject().apply {
                    put("name", "PackForge Modpack BP")
                    put("uuid", UUID.randomUUID().toString())
                    put("version", JSONArray(listOf(1, 0, 0)))
                })
            }
        }

        try {
            val formatVersion = manifest.optInt("format_version", 2)

            // 1. Módulo de Script
            val modules = manifest.optJSONArray("modules") ?: JSONArray().also { manifest.put("modules", it) }
            var scriptModFound = false
            for (i in 0 until modules.length()) {
                val mod = modules.optJSONObject(i) ?: continue
                val type = mod.optString("type", "").lowercase(Locale.ROOT)
                if (type == "script" || type == "javascript") {
                    mod.put("entry", bootstrapperRelPath)
                    scriptModFound = true
                    break
                }
            }

            if (!scriptModFound) {
                val newMod = JSONObject().apply {
                    put("type", "script")
                    put("language", "javascript")
                    put("uuid", UUID.randomUUID().toString())
                    if (formatVersion >= 3) {
                        put("version", "1.0.0")
                    } else {
                        put("version", JSONArray(listOf(1, 0, 0)))
                    }
                    put("entry", bootstrapperRelPath)
                }
                modules.put(newMod)
            }

            // 2. Dependencias @minecraft/*
            val dependencies = manifest.optJSONArray("dependencies") ?: JSONArray().also { manifest.put("dependencies", it) }
            val existingMcDeps = mutableMapOf<String, JSONObject>()

            for (i in 0 until dependencies.length()) {
                val dep = dependencies.optJSONObject(i) ?: continue
                val modName = dep.optString("module_name", "").trim()
                if (modName.startsWith("@minecraft/")) {
                    existingMcDeps[modName] = dep
                }
            }

            // Aplicar armonización
            val targetDeps = harmonizedDeps.ifEmpty {
                mapOf("@minecraft/server" to (DEFAULT_MODULE_VERSIONS["@minecraft/server"] ?: "1.16.0"))
            }

            targetDeps.forEach { (modName, targetVer) ->
                val existingObj = existingMcDeps[modName]
                if (existingObj != null) {
                    val currentVer = extractDependencyVersion(existingObj)
                    if (currentVer.isBlank() || compareSemver(targetVer, currentVer) > 0) {
                        existingObj.put("version", targetVer)
                        PackForgeLog.d(TAG, "Dependencia '$modName' actualizada de '$currentVer' a '$targetVer' en manifest")
                    }
                } else {
                    val newDep = JSONObject().apply {
                        put("module_name", modName)
                        put("version", targetVer)
                    }
                    dependencies.put(newDep)
                    PackForgeLog.d(TAG, "Dependencia '$modName' ($targetVer) insertada en manifest")
                }
            }

            OutputStreamWriter(FileOutputStream(manifestFile), StandardCharsets.UTF_8).use {
                it.write(manifest.toString(4))
            }
            PackForgeLog.d(TAG, "✅ manifest.json del BP actualizado con módulo script maestro y dependencias: $targetDeps")
        } catch (e: Exception) {
            PackForgeLog.e(TAG, "Error actualizando manifest.json del BP: ${e.message}")
        }
    }
}
