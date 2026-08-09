package com.packforge.app.domain.engine

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.UUID

object ManifestGenerator {

    private const val MANIFEST_TAG = "PackForge_Manifest"

    // ══════════════════════════════════════════════════════════════════
    // GENERADOR DEFINITIVO DE MANIFESTS FUSIONADOS
    // ══════════════════════════════════════════════════════════════════
    //
    // El manifest final DEBE conservar:
    //   1. max min_engine_version de los addons (nunca [1,0,0])
    //   2. módulos script (type "script", language javascript)
    //   3. dependencias @minecraft/* (server, server-ui, vanilla…)
    //   4. la vinculación BP↔RP con el nuevo UUID del RP fusionado
    //   5. UUIDs nuevos (header + modules)
    //   6. fallback: si el BP fusionado tiene scripts/ pero se perdió el
    //      módulo script, agregarlo manualmente.
    // ══════════════════════════════════════════════════════════════════

    /**
     * Construye el manifest final del Behavior Pack fusionado.
     *
     * @param originalBpManifests Archivos manifest.json de los BPs ORIGINALES
     * @param originalRpHeaderUuids UUIDs de los headers de los RPs ORIGINALES
     * @param newRpHeaderUuid UUID nuevo del RP fusionado (para revincular)
     * @param packName Nombre del modpack
     * @param hasScriptsFolder true si la carpeta BP fusionada contiene scripts/
     * @return manifest.json final como JSONObject
     */
    fun buildMergedBpManifest(
        originalBpManifests: List<File>,
        originalRpHeaderUuids: Set<String>,
        newRpHeaderUuid: String?,
        packName: String,
        hasScriptsFolder: Boolean,
        packAuthor: String = "",
        packVersion: String = "1.0.0",
        packDescription: String = ""
    ): JSONObject {
        val newBpHeaderUuid = UUID.randomUUID().toString()
        val modules = JSONArray()
        val dependencies = JSONArray()
        val seenDeps = mutableSetOf<String>()
        var minEngine = listOf(1, 21, 0)

        // REGLA 2: Módulo data principal (SÓLO uno, con UUID nuevo)
        modules.put(JSONObject().apply {
            put("type", "data")
            put("uuid", UUID.randomUUID().toString())
            put("version", JSONArray(listOf(1, 0, 0)))
        })

        // REGLA 4: Dependencia al RP fusionado (solo si existe RP)
        if (newRpHeaderUuid != null && newRpHeaderUuid.isNotBlank()) {
            val newRpDep = JSONObject().apply {
                put("uuid", newRpHeaderUuid)
                put("version", JSONArray(listOf(1, 0, 0)))
            }
            dependencies.put(newRpDep)
            seenDeps.add("uuid:${newRpHeaderUuid.lowercase()}")
        }

        var hasScriptModule = false
        val rpUuidsNormalized = originalRpHeaderUuids.map { it.lowercase() }.toSet()

        originalBpManifests.forEach { file ->
            try {
                val json = JSONObject(file.readText(Charsets.UTF_8))

                // REGLA 1: min_engine_version MÁXIMO (nunca [1,0,0])
                json.optJSONObject("header")
                    ?.optJSONArray("min_engine_version")
                    ?.let { mev ->
                        val v = (0 until mev.length()).mapNotNull { idx ->
                            mev.optInt(idx, -1).takeIf { it >= 0 }
                        }
                        if (v.isNotEmpty() && compareVersion(v, minEngine) > 0) {
                            minEngine = v
                        }
                    }

                // REGLA 2: modules — conservar script y otros, reconstruir data
                json.optJSONArray("modules")?.let { mods ->
                    for (i in 0 until mods.length()) {
                        val mod = mods.optJSONObject(i) ?: continue
                        when (mod.optString("type")) {
                            "data" -> Unit // ya está el módulo data nuevo
                            "script" -> {
                                hasScriptModule = true
                                modules.put(JSONObject().apply {
                                    put("type", "script")
                                    put("language", mod.optString("language", "javascript"))
                                    put("uuid", UUID.randomUUID().toString())
                                    put("version", mod.optJSONArray("version")
                                        ?: JSONArray(listOf(1, 0, 0)))
                                })
                            }
                            else -> {
                                // Cualquier otro type (ej. "client_data") se conserva
                                val copy = JSONObject(mod.toString())
                                copy.put("uuid", UUID.randomUUID().toString())
                                modules.put(copy)
                            }
                        }
                    }
                }

                // REGLA 3 y 4: dependencias @minecraft/* + revinculación BP↔RP
                json.optJSONArray("dependencies")?.let { deps ->
                    for (i in 0 until deps.length()) {
                        val dep = deps.optJSONObject(i) ?: continue
                        val uuid = dep.optString("uuid", "").lowercase()
                        val name = dep.optString("name", "")

                        // Si es el uuid de un RP ORIGINAL → ya está el nuevo RP agregado
                        if (uuid.isNotEmpty() && uuid in rpUuidsNormalized) {
                            continue
                        }

                        // Conservar toda dependencia (uuid o @minecraft/*); duplicados → mayor versión
                        val depKey = if (uuid.isNotEmpty()) "uuid:$uuid" else "name:$name"
                        if (depKey.isEmpty()) continue

                        val existing = findExistingDependency(dependencies, dep, depKey)
                        if (existing != null) {
                            // Mantener la versión MAYOR en caso de duplicado
                            val existingVer = existing.optJSONArray("version")
                            val newVer = dep.optJSONArray("version")
                            if (newVer != null && versionGreater(newVer, existingVer)) {
                                existing.put("version", newVer)
                            }
                        } else {
                            dependencies.put(dep)
                            seenDeps.add(depKey)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(MANIFEST_TAG, "Error leyendo manifest original ${file.name}: ${e.message}")
            }
        }

        // REGLA 6: fallback — hay scripts/ pero ningún módulo script
        if (hasScriptsFolder && !hasScriptModule) {
            modules.put(JSONObject().apply {
                put("type", "script")
                put("language", "javascript")
                put("uuid", UUID.randomUUID().toString())
                put("version", JSONArray(listOf(1, 0, 0)))
            })
            hasScriptModule = true
            Log.d(MANIFEST_TAG, "Fallback: módulo script agregado (carpeta scripts/ detectada)")
        }

        val manifest = JSONObject().apply {
            put("format_version", 2)
            put("header", JSONObject().apply {
                put("name", packName)
                put("description", packDescription.ifBlank { "Behavior Pack created by PackForge" })
                put("uuid", newBpHeaderUuid)
                put("version", parseVersion(packVersion))
                put("min_engine_version", JSONArray(minEngine))
            })
            put("modules", modules)
            put("dependencies", dependencies)
            // Metadatos: autor visible en el juego
            if (packAuthor.isNotBlank()) {
                put("metadata", JSONObject().apply {
                    put("authors", JSONArray(listOf(packAuthor)))
                })
            }
        }

        // LOG OBLIGATORIO: manifiesto del BP completo
        Log.d(MANIFEST_TAG, "=== BP MANIFEST FINAL ===")
        Log.d(MANIFEST_TAG, manifest.toString(2))
        return manifest
    }

    /**
     * Construye el manifest final del Resource Pack fusionado.
     */
    fun buildMergedRpManifest(
        originalRpManifests: List<File>,
        packName: String,
        newBpHeaderUuid: String? = null,
        packAuthor: String = "",
        packVersion: String = "1.0.0",
        packDescription: String = ""
    ): JSONObject {
        val newRpHeaderUuid = UUID.randomUUID().toString()
        val modules = JSONArray()
        val dependencies = JSONArray()
        val seenDeps = mutableSetOf<String>()
        var minEngine = listOf(1, 21, 0)

        // module resources nuevo
        modules.put(JSONObject().apply {
            put("type", "resources")
            put("uuid", UUID.randomUUID().toString())
            put("version", JSONArray(listOf(1, 0, 0)))
        })

        originalRpManifests.forEach { file ->
            try {
                val json = JSONObject(file.readText(Charsets.UTF_8))

                json.optJSONObject("header")
                    ?.optJSONArray("min_engine_version")
                    ?.let { mev ->
                        val v = (0 until mev.length()).mapNotNull { idx ->
                            mev.optInt(idx, -1).takeIf { it >= 0 }
                        }
                        if (v.isNotEmpty() && compareVersion(v, minEngine) > 0) {
                            minEngine = v
                        }
                    }

                // Conservar módulos adicionales que no sean resources puros (ej. client_data)
                json.optJSONArray("modules")?.let { mods ->
                    for (i in 0 until mods.length()) {
                        val mod = mods.optJSONObject(i) ?: continue
                        if (mod.optString("type") == "resources") continue
                        val copy = JSONObject(mod.toString())
                        copy.put("uuid", UUID.randomUUID().toString())
                        modules.put(copy)
                    }
                }

                // Conservar dependencias @minecraft/*
                json.optJSONArray("dependencies")?.let { deps ->
                    for (i in 0 until deps.length()) {
                        val dep = deps.optJSONObject(i) ?: continue
                        val name = dep.optString("name", "")
                        if (name.isNotEmpty() && name.startsWith("@minecraft/")) {
                            val depKey = "name:$name"
                            if (seenDeps.add(depKey)) {
                                dependencies.put(dep)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(MANIFEST_TAG, "Error al leer manifest ${file.name}: ${e.message}")
            }
        }

        // Si el BP existe, vincular (RP depende del BP es opcional, pero se agrega para integridad)
        if (newBpHeaderUuid != null) {
            dependencies.put(JSONObject().apply {
                put("uuid", newBpHeaderUuid)
                put("version", JSONArray(listOf(1, 0, 0)))
            })
        }

        val manifest = JSONObject().apply {
            put("format_version", 2)
            put("header", JSONObject().apply {
                put("name", packName)
                put("description", packDescription.ifBlank { "Resource Pack created by PackForge" })
                put("uuid", newRpHeaderUuid)
                put("version", parseVersion(packVersion))
                put("min_engine_version", JSONArray(minEngine))
            })
            put("modules", modules)
            put("dependencies", dependencies)
            // Metadatos: autor visible en el juego
            if (packAuthor.isNotBlank()) {
                put("metadata", JSONObject().apply {
                    put("authors", JSONArray(listOf(packAuthor)))
                })
            }
        }

        // LOG OBLIGATORIO: manifiesto del RP completo
        Log.d(MANIFEST_TAG, "=== RP MANIFEST FINAL ===")
        Log.d(MANIFEST_TAG, manifest.toString(2))
        return manifest
    }

    // ─── HELPERS DE VERSIONES ──────────────────────────────────────────

    /**
     * ═══════════════════════════════════════════
     * GENERADOR EXACTO DEL MANIFEST DEL BP
     * ═══════════════════════════════════════════
     * min_engine_version OBLIGATORIO (default alto [1,21,0], nunca [1,0,0]),
     * módulo data + módulo script (entry scripts/main.js),
     * dependencies RP + @minecraft/server + @minecraft/server-ui.
     */
    fun generateBpManifest(
        packName: String,
        rpHeaderUuid: String,
        originalManifests: List<File> = emptyList()
    ): JSONObject {
        val bpHeaderUuid = UUID.randomUUID().toString()
        val bpModuleUuid = UUID.randomUUID().toString()

        // Detectar min_engine_version MÁXIMO de los manifiestos originales
        var minEngine = JSONArray(listOf(1, 21, 0))  // ⭐ DEFAULT ALTO, NO [1,0,0]
        originalManifests.forEach { file ->
            try {
                val json = JSONObject(file.readText())
                json.optJSONObject("header")?.optJSONArray("min_engine_version")?.let { mev ->
                    if (mev.length() >= 3) {
                        val v = JSONArray(listOf(
                            maxOf(minEngine.getInt(0), mev.getInt(0)),
                            maxOf(minEngine.getInt(1), mev.getInt(1)),
                            maxOf(minEngine.getInt(2), mev.getInt(2))
                        ))
                        minEngine = v
                    }
                }
            } catch (e: Exception) {}
        }

        // ⭐ CRÍTICO: Agregar módulo SCRIPT si existe carpeta scripts/
        val modules = JSONArray()
        modules.put(JSONObject().apply {
            put("type", "data")
            put("uuid", bpModuleUuid)
            put("version", JSONArray(listOf(1, 0, 0)))
        })

        // ⭐ AGREGAR MÓDULO SCRIPT
        modules.put(JSONObject().apply {
            put("type", "script")
            put("language", "javascript")
            put("uuid", UUID.randomUUID().toString())
            put("version", JSONArray(listOf(1, 0, 0)))
            put("entry", "scripts/main.js")  // Entry point estándar
        })

        // Dependencies: RP + @minecraft/server
        val dependencies = JSONArray()
        dependencies.put(JSONObject().apply {
            put("uuid", rpHeaderUuid)
            put("version", JSONArray(listOf(1, 0, 0)))
        })
        dependencies.put(JSONObject().apply {
            put("module_name", "@minecraft/server")
            put("version", "1.11.0")
        })
        dependencies.put(JSONObject().apply {
            put("module_name", "@minecraft/server-ui")
            put("version", "1.1.0")
        })

        return JSONObject().apply {
            put("format_version", 2)
            put("header", JSONObject().apply {
                put("name", packName)
                put("description", "PackForge Modpack")
                put("uuid", bpHeaderUuid)
                put("version", JSONArray(listOf(1, 0, 0)))
                put("min_engine_version", minEngine)  // ⭐ OBLIGATORIO
            })
            put("modules", modules)
            put("dependencies", dependencies)
        }
    }

    /**
     * ═══════════════════════════════════════════
     * GENERADOR EXACTO DEL MANIFEST DEL RP
     * ═══════════════════════════════════════════
     */
    fun generateRpManifest(packName: String): JSONObject {
        return JSONObject().apply {
            put("format_version", 2)
            put("header", JSONObject().apply {
                put("name", packName)
                put("description", "PackForge Modpack")
                put("uuid", UUID.randomUUID().toString())
                put("version", JSONArray(listOf(1, 0, 0)))
                put("min_engine_version", JSONArray(listOf(1, 21, 0)))  // ⭐ OBLIGATORIO
            })
            put("modules", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "resources")
                    put("uuid", UUID.randomUUID().toString())
                    put("version", JSONArray(listOf(1, 0, 0)))
                })
            })
        }
    }

    /**
     * Convierte una versión "1.2.3" en un JSONArray [1, 2, 3].
     * Si la entrada es inválida o vacía, usa [1, 0, 0].
     */
    private fun parseVersion(version: String): JSONArray {
        if (version.isBlank()) return JSONArray(listOf(1, 0, 0))
        val parts = version.trim().split("\\.".toRegex()).mapNotNull { it.toIntOrNull() }
        return if (parts.isEmpty()) {
            JSONArray(listOf(1, 0, 0))
        } else {
            JSONArray(listOf(
                parts.getOrElse(0) { 1 },
                parts.getOrElse(1) { 0 },
                parts.getOrElse(2) { 0 }
            ))
        }
    }

    /**
     * Compara dos versiones [a, b, c] — devuelve negativo si a < b,
     * positivo si a > b y 0 si son iguales.
     */
    private fun compareVersion(a: List<Int>, b: List<Int>): Int {
        val max = maxOf(a.size, b.size)
        for (i in 0 until max) {
            val av = a.getOrElse(i) { 0 }
            val bv = b.getOrElse(i) { 0 }
            if (av != bv) return av - bv
        }
        return 0
    }

    /**
     * Devuelve true si la nueva versión es estrictamente mayor que la existente.
     */
    private fun versionGreater(newVer: JSONArray, existingVer: JSONArray?): Boolean {
        if (existingVer == null) return true
        val newV = (0 until newVer.length()).mapNotNull { i -> newVer.optInt(i, -1).takeIf { it >= 0 } }
        val oldV = (0 until existingVer.length()).mapNotNull { i -> existingVer.optInt(i, -1).takeIf { it >= 0 } }
        return compareVersion(newV, oldV) > 0
    }

    /**
     * Busca una dependencia ya añadida con la misma clave (uuid o nombre).
     */
    private fun findExistingDependency(
        dependencies: JSONArray,
        candidate: JSONObject,
        candidateKey: String
    ): JSONObject? {
        for (i in 0 until dependencies.length()) {
            val dep = dependencies.optJSONObject(i) ?: continue
            val uuid = dep.optString("uuid", "").lowercase()
            val name = dep.optString("name", "")
            val key = if (uuid.isNotEmpty()) "uuid:$uuid" else "name:$name"
            if (key == candidateKey) return dep
        }
        return null
    }

    /**
     * Escribe un manifest.json en UTF-8 sin BOM (requisito de Minecraft Bedrock).
     */
    fun writeManifestToFile(manifestContent: String, targetFile: File) {
        FileOutputStream(targetFile).use { fos ->
            OutputStreamWriter(fos, StandardCharsets.UTF_8).use { writer ->
                writer.write(manifestContent)
            }
        }
    }
}