package com.packforge.app.domain.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID

object ManifestGenerator {
    
    /**
     * Genera un manifest.json válido para un Behavior Pack (BP) de Minecraft Bedrock.
     * 
     * @param modpackName Nombre del modpack (default: "PackForge Modpack")
     * @return String JSON del manifiesto generado
     */
    fun generateBehaviorPackManifest(modpackName: String = "PackForge Modpack"): String {
        // Validar que el nombre no sea vacío
        val safeName = if (modpackName.isBlank()) "PackForge Modpack" else modpackName
        
        // Generar UUIDs aleatorios
        val bpUuid = UUID.randomUUID().toString()
        val rpUuid = UUID.randomUUID().toString()
        
        val manifest = JSONObject().apply {
            // Header
            put("format_version", 2)
            put("header", JSONObject().apply {
                put("name", safeName)
                put("description", "Behavior Pack created by PackForge")
                put("uuid", bpUuid)
                put("version", JSONArray(listOf(1, 0, 0)))
                put("min_engine_version", JSONArray(listOf(1, 20, 0)))
            })
            
            // Modules
            put("modules", JSONArray(listOf(JSONObject().apply {
                put("type", "data")
                put("uuid", UUID.randomUUID().toString())
                put("version", JSONArray(listOf(1, 0, 0)))
            })))
            
            // Dependencies - Referencia al UUID del RP
            put("dependencies", JSONArray(listOf(JSONObject().apply {
                put("uuid", rpUuid)
                put("version", JSONArray(listOf(1, 0, 0)))
            })))
        }
        
        return manifest.toString(4)
    }
    
    /**
     * Genera un manifest.json válido para un Resource Pack (RP) de Minecraft Bedrock.
     * 
     * @param modpackName Nombre del modpack (default: "PackForge Modpack")
     * @param bpUuid UUID del Behavior Pack al que este RP depende (opcional)
     * @return String JSON del manifiesto generado
     */
    fun generateResourcePackManifest(modpackName: String = "PackForge Modpack", bpUuid: String? = null): String {
        // Validar que el nombre no sea vacío
        val safeName = if (modpackName.isBlank()) "PackForge Modpack" else modpackName
        
        val rpUuid = UUID.randomUUID().toString()
        
        val manifest = JSONObject().apply {
            // Header
            put("format_version", 2)
            put("header", JSONObject().apply {
                put("name", "$safeName (RP)")
                put("description", "Resource Pack created by PackForge")
                put("uuid", rpUuid)
                put("version", JSONArray(listOf(1, 0, 0)))
                put("min_engine_version", JSONArray(listOf(1, 20, 0)))
            })
            
            // Modules
            put("modules", JSONArray(listOf(JSONObject().apply {
                put("type", "resources")
                put("uuid", UUID.randomUUID().toString())
                put("version", JSONArray(listOf(1, 0, 0)))
            })))
            
            // Dependencies - Si se proporciona el UUID del BP, agregarlo
            if (bpUuid != null) {
                put("dependencies", JSONArray(listOf(JSONObject().apply {
                    put("uuid", bpUuid)
                    put("version", JSONArray(listOf(1, 0, 0)))
                })))
            }
        }
        
        return manifest.toString(4)
    }
    
    /**
     * Genera ambos manifiestos (BP y RP) con UUIDs vinculados.
     * 
     * @param modpackName Nombre del modpack
     * @return Pair con (BP manifest, RP manifest)
     */
    fun generateLinkedManifests(modpackName: String = "PackForge Modpack"): Pair<String, String> {
        // Validar que el nombre no sea vacío
        val safeName = if (modpackName.isBlank()) "PackForge Modpack" else modpackName
        
        val bpUuid = UUID.randomUUID().toString()
        val rpUuid = UUID.randomUUID().toString()
        
        val bpManifest = JSONObject().apply {
            put("format_version", 2)
            put("header", JSONObject().apply {
                put("name", "$safeName (BP)")
                put("description", "Behavior Pack created by PackForge")
                put("uuid", bpUuid)
                put("version", JSONArray(listOf(1, 0, 0)))
                put("min_engine_version", JSONArray(listOf(1, 20, 0)))
            })
            put("modules", JSONArray(listOf(JSONObject().apply {
                put("type", "data")
                put("uuid", UUID.randomUUID().toString())
                put("version", JSONArray(listOf(1, 0, 0)))
            })))
            put("dependencies", JSONArray(listOf(JSONObject().apply {
                put("uuid", rpUuid)
                put("version", JSONArray(listOf(1, 0, 0)))
            })))
        }
        
        val rpManifest = JSONObject().apply {
            put("format_version", 2)
            put("header", JSONObject().apply {
                put("name", "$safeName (RP)")
                put("description", "Resource Pack created by PackForge")
                put("uuid", rpUuid)
                put("version", JSONArray(listOf(1, 0, 0)))
                put("min_engine_version", JSONArray(listOf(1, 20, 0)))
            })
            put("modules", JSONArray(listOf(JSONObject().apply {
                put("type", "resources")
                put("uuid", UUID.randomUUID().toString())
                put("version", JSONArray(listOf(1, 0, 0)))
            })))
        }
        
        return Pair(bpManifest.toString(4), rpManifest.toString(4))
    }
    
    /**
     * Escribe un manifest.json a un archivo usando UTF-8 sin BOM
     * Minecraft Bedrock NO soporta BOM en los archivos JSON
     */
    fun writeManifestToFile(manifestContent: String, targetFile: File) {
        targetFile.writeText(manifestContent, StandardCharsets.UTF_8)
    }
}
