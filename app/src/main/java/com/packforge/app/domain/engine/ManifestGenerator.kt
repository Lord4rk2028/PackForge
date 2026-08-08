package com.packforge.app.domain.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
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
     * CRÍTICO: Genera 4 UUIDs únicos y diferentes:
     * - header_uuid_bp: para header del BP
     * - module_uuid_bp: para módulo del BP
     * - header_uuid_rp: para header del RP
     * - module_uuid_rp: para módulo del RP
     * 
     * El BP tiene dependencies apuntando al header_uuid_rp
     * El RP NO tiene dependencies
     * 
     * @param modpackName Nombre del modpack
     * @return Pair con (BP manifest, RP manifest)
     */
    fun generateLinkedManifests(modpackName: String = "PackForge Modpack"): Pair<String, String> {
        // Validar que el nombre no sea vacío
        val safeName = if (modpackName.isBlank()) "PackForge Modpack" else modpackName
        
        // Generar 4 UUIDs únicos y diferentes
        val headerUuidBp = UUID.randomUUID().toString()
        val moduleUuidBp = UUID.randomUUID().toString()
        val headerUuidRp = UUID.randomUUID().toString()
        val moduleUuidRp = UUID.randomUUID().toString()
        
        // Verificar que todos los UUIDs sean diferentes
        if (!(headerUuidBp != moduleUuidBp && headerUuidBp != headerUuidRp && 
                headerUuidBp != moduleUuidRp && moduleUuidBp != headerUuidRp && 
                moduleUuidBp != moduleUuidRp && headerUuidRp != moduleUuidRp)) {
            // CASO EXTREMO: UUIDs duplicados (probabilidad ~0, pero por robustez)
            ConflictRegistry.logConflict(
                severity = com.packforge.app.domain.model.ConflictSeverity.CRITICAL,
                type = "MANIFEST_UUID_COLLISION",
                file = "manifest.json",
                addon1 = "BP",
                addon2 = "RP",
                description = "Se generaron UUIDs duplicados en los manifiestos. " +
                    "Se regenerará el modpack para garantizar identificadores únicos."
            )
        }
        
        val bpManifest = JSONObject().apply {
            put("format_version", 2)
            put("header", JSONObject().apply {
                put("name", safeName)
                put("description", "Generated by PackForge")
                put("uuid", headerUuidBp)
                put("version", JSONArray(listOf(1, 0, 0)))
                put("min_engine_version", JSONArray(listOf(1, 20, 0)))
            })
            put("modules", JSONArray(listOf(JSONObject().apply {
                put("type", "data")
                put("uuid", moduleUuidBp)
                put("version", JSONArray(listOf(1, 0, 0)))
            })))
            // CRÍTICO: BP debe tener dependencies apuntando al header del RP
            put("dependencies", JSONArray(listOf(JSONObject().apply {
                put("uuid", headerUuidRp)
                put("version", JSONArray(listOf(1, 0, 0)))
            })))
        }
        
        val rpManifest = JSONObject().apply {
            put("format_version", 2)
            put("header", JSONObject().apply {
                put("name", safeName)
                put("description", "Generated by PackForge")
                put("uuid", headerUuidRp)
                put("version", JSONArray(listOf(1, 0, 0)))
                put("min_engine_version", JSONArray(listOf(1, 20, 0)))
            })
            put("modules", JSONArray(listOf(JSONObject().apply {
                put("type", "resources")
                put("uuid", moduleUuidRp)
                put("version", JSONArray(listOf(1, 0, 0)))
            })))
            // CRÍTICO: RP NO debe tener dependencies
        }
        
        return Pair(bpManifest.toString(2), rpManifest.toString(2))
    }
    
    /**
     * Escribe un manifest.json a un archivo usando UTF-8 sin BOM
     * Minecraft Bedrock NO soporta BOM en los archivos JSON
     * CRÍTICO: Usar FileOutputStream + OutputStreamWriter para evitar BOM
     */
    fun writeManifestToFile(manifestContent: String, targetFile: File) {
        FileOutputStream(targetFile).use { fos ->
            OutputStreamWriter(fos, StandardCharsets.UTF_8).use { writer ->
                writer.write(manifestContent)
            }
        }
    }
}
