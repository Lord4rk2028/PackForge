package com.packforge.app.domain.engine

import com.packforge.app.util.FileUtils
import com.packforge.app.util.PackForgeLog
import com.packforge.app.util.logFile
import org.json.JSONObject
import org.json.JSONArray
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import com.packforge.app.domain.engine.JsonDeepMerger
import com.packforge.app.domain.engine.AddonExtractor

/**
 * Motor de exportación optimizado: ZIP→ZIP sin pasos intermedios masivos a disco.
 */
object FastModpackExporter {
    private const val TAG = "FastExporter"

    // Plan de empaquetado: ruta relativa -> lista de fuentes (para detectar colisiones)
    val bpPlan = LinkedHashMap<String, MutableList<Pair<ZipFile, ZipEntry>>>()
    val rpPlan = LinkedHashMap<String, MutableList<Pair<ZipFile, ZipEntry>>>()

    fun buildPlan(
        plan: LinkedHashMap<String, MutableList<Pair<ZipFile, ZipEntry>>>,
        sources: List<Pair<ZipFile, String>>
    ) {
        sources.forEach { (zf, prefix) ->
            zf.entries().toList().forEach { e ->
                if (e.isDirectory) return@forEach
                val rel = e.name.removePrefix(prefix)
                
                // Exclusiones: se manejan por separado (paso 5 adaptado)
                if (rel == "manifest.json" || rel == "pack_icon.png") return@forEach
                if (rel.startsWith("scripts/")) return@forEach
                if (rel.startsWith("texts/")) return@forEach
                if (AddonExtractor.CRITICAL_PATTERNS.any { rel.endsWith(it) }) return@forEach
                
                plan.getOrPut(rel) { mutableListOf() }.add(zf to e)
            }
        }
    }

    fun exportToZip(
        outputFile: File,
        bpPlan: LinkedHashMap<String, MutableList<Pair<ZipFile, ZipEntry>>>,
        rpPlan: LinkedHashMap<String, MutableList<Pair<ZipFile, ZipEntry>>>,
        bpManifestBytes: ByteArray,
        rpManifestBytes: ByteArray,
        criticalFiles: List<Pair<ByteArray, String>>, // nombre -> bytes
        langFiles: List<Pair<ByteArray, String>>,
        mergedScriptsDir: File?,
        customIconUri: String?, // Implementar según contexto actual
        context: Any // Asumiendo que es context, ajustar si es necesario
    ) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(outputFile), 262144)).use { zos ->
            // --- BEHAVIOR PACK ---
            bpPlan.forEach { (rel, sources) ->
                writeEntry(zos, sources, "BP_PackForge/$rel")
            }
            // scripts fusionados
            mergedScriptsDir?.walkTopDown()?.filter { it.isFile }?.forEach { f ->
                writeBytesEntry(zos, f.readBytes(), "BP_PackForge/scripts/${f.relativeTo(mergedScriptsDir).path}")
            }
            writeBytesEntry(zos, bpManifestBytes, "BP_PackForge/manifest.json")
            // writeIconEntry ... (pendiente de integrar con tu lógica de iconos actual)

            // --- RESOURCE PACK ---
            rpPlan.forEach { (rel, sources) ->
                writeEntry(zos, sources, "RP_PackForge/$rel")
            }
            // críticos
            criticalFiles.forEach { (bytes, name) ->
                writeBytesEntry(zos, bytes, "RP_PackForge/$name")
            }
            // textos
            langFiles.forEach { (bytes, name) ->
                writeBytesEntry(zos, bytes, "RP_PackForge/texts/$name")
            }
            writeBytesEntry(zos, rpManifestBytes, "RP_PackForge/manifest.json")
            
            zos.finish()
        }
    }

    fun writeEntry(zos: ZipOutputStream, sources: List<Pair<ZipFile, ZipEntry>>, outName: String) {
        val isJson = outName.endsWith(".json")
        if (sources.size == 1 || !isJson) {
            val (zf, e) = sources.last()
            zf.getInputStream(e).use { input ->
                zos.putNextEntry(ZipEntry(outName))
                input.copyTo(zos, 65536)
                zos.closeEntry()
            }
        } else {
            // Fusión JSON en memoria
            var merged: JSONObject? = null
            sources.forEach { (zf, e) ->
                val json = JsonDeepMerger.cleanJsonObject(
                    JSONObject(zf.getInputStream(e).bufferedReader(Charsets.UTF_8).readText())
                )
                merged = merged?.let { JsonDeepMerger.deepMerge(it, json) } ?: json
            }
            zos.putNextEntry(ZipEntry(outName))
            zos.write(merged!!.toString().toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
    }

    fun writeBytesEntry(zos: ZipOutputStream, bytes: ByteArray, outName: String) {
        zos.putNextEntry(ZipEntry(outName))
        zos.write(bytes)
        zos.closeEntry()
    }
}
