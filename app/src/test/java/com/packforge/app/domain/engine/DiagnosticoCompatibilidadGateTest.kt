package com.packforge.app.domain.engine

import com.packforge.app.domain.model.Addon
import com.packforge.app.domain.model.AddonType
import com.packforge.app.domain.model.ConflictSeverity
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * DIAGNÓSTICO (temporal) — reproducción del bloqueo de exportación.
 *
 * La app bloqueó el modpack con **25/100**. La fórmula es
 * `100 − Σ penalizaciones` (CRITICAL 30, HIGH 15, MEDIUM 8, LOW 3, WARNING 1),
 * así que 75 puntos de penalización ≈ 5 conflictos HIGH, que además disparan la
 * regla `highConflicts.size >= 5` del gate.
 *
 * Este test construye los `Addon` desde los .mcaddon REALES y ejecuta
 * `ConflictEngine.analyze`, para ver exactamente qué conflictos generan ese
 * bloqueo en vez de suponerlo.
 */
class DiagnosticoCompatibilidadGateTest {

    private val addonsDir = File(
        System.getProperty("user.dir")
    ).let { cwd ->
        listOf(
            File(cwd, ".research/addons_otg"),
            File(cwd, "../.research/addons_otg"),
            File(cwd, "../../.research/addons_otg")
        ).firstOrNull { it.isDirectory }
    }

    private fun entradas(zip: File): List<String> =
        ZipFile(zip).use { z -> z.entries().toList().map { it.name.replace("\\", "/") } }

    /**
     * Expande los `.mcpack`/`.zip` anidados: la app los extrae y clasifica, así
     * que sin esto el addon OTG aparecía con 2 archivos y el análisis no veía
     * nada de su contenido (el diagnóstico daba 100/100 en vez del 25 real).
     */
    private fun expandir(zip: File, prefijo: String = "", tmp: File): List<String> {
        val salida = mutableListOf<String>()
        ZipFile(zip).use { z ->
            for (e in z.entries()) {
                if (e.isDirectory) continue
                val nombre = e.name.replace("\\", "/")
                val esAnidado = nombre.endsWith(".mcpack", true) || nombre.endsWith(".zip", true)
                if (esAnidado) {
                    val out = File(tmp, "n_${System.nanoTime()}_${e.name.substringAfterLast('/')}")
                    out.parentFile?.mkdirs()
                    z.getInputStream(e).use { ins -> out.outputStream().use { o -> ins.copyTo(o) } }
                    salida += expandir(out, "$prefijo$nombre/", tmp)
                    out.delete()
                } else {
                    salida += "$prefijo$nombre"
                }
            }
        }
        return salida
    }

    /** Identificadores declarados (`description.identifier`) dentro del addon. */
    private fun identificadores(zip: File, tmp: File): List<String> {
        val out = sortedSetOf<String>()
        fun scanear(z: ZipFile, prefijo: String) {
            for (e in z.entries()) {
                if (e.isDirectory) continue
                val nombre = e.name.replace("\\", "/")
                if (nombre.endsWith(".mcpack", true) || nombre.endsWith(".zip", true)) {
                    val f = File(tmp, "i_${System.nanoTime()}_${nombre.substringAfterLast('/')}")
                    f.parentFile?.mkdirs()
                    z.getInputStream(e).use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                    ZipFile(f).use { scanear(it, "$prefijo$nombre/") }
                    f.delete()
                    continue
                }
                if (!nombre.endsWith(".json", true) || e.size > 3_000_000) continue
                val t = runCatching { z.getInputStream(e).bufferedReader().readText() }.getOrDefault("")
                Regex("\"identifier\"\\s*:\\s*\"([^\"]+)\"").findAll(t).forEach { out.add(it.groupValues[1]) }
            }
        }
        ZipFile(zip).use { scanear(it, "") }
        return out.toList()
    }

    private fun buildAddon(id: String, zip: File, tmp: File): Addon {
        val todos = expandir(zip, "", tmp)
        // Heurística de clasificación por ruta, suficiente para los detectores
        // que trabajan sobre listas de archivos.
        val bp = todos.filter { it.contains("BP") || it.contains("entities/") || it.contains("items/") || it.contains("scripts/") }
        val rp = todos.filter { it.contains("RP") || it.contains("textures/") || it.contains("entity/") || it.contains("models/") }
        return Addon(
            id = id,
            name = zip.nameWithoutExtension,
            fileName = zip.name,
            type = AddonType.BEHAVIOR_AND_RESOURCE,
            version = "1.0.0",
            sizeBytes = zip.length(),
            enabled = true,
            priority = 0,
            files = todos,
            behaviorFiles = bp,
            resourceFiles = rp,
            entityIdentifiers = identificadores(zip, tmp),
            // Estrés: se reutiliza la misma colección para ítems/recetas/loot, de
            // modo que se ejercitan también esos detectores (en la app real
            // Origins y OTG comparten muchos identificadores de ítem).
            itemIdentifiers = identificadores(zip, tmp),
            recipeIdentifiers = identificadores(zip, tmp),
            lootTableIdentifiers = identificadores(zip, tmp),
            rawManifest = "",
            sourceFilePath = zip.absolutePath
        )
    }

    @Test
    fun `que conflictos producen el bloqueo 25 de 100`() {
        val dir = addonsDir
        if (dir == null) {
            println("PF-GATE | sin addons: se omite")
            return
        }
        val zips = dir.listFiles { f -> f.extension == "mcaddon" || f.extension == "mcpack" }
            ?.sortedBy { it.name }?.toList().orEmpty()
        if (zips.size < 2) {
            println("PF-GATE | hacen falta 2 addons (hay ${zips.size}): se omite")
            return
        }

        val tmp = java.nio.file.Files.createTempDirectory("pf_gate").toFile()
        val addons = zips.mapIndexed { i, z -> buildAddon("id$i", z, tmp) }
        addons.forEach { println("PF-GATE | addon: ${it.name} (${it.files.size} archivos)") }

        val conflictos = ConflictEngine.analyze(addons)
        val score = ConflictEngine.scoreOfConflicts(conflictos)
        val gate = ExportCompatibilityGate.evaluate(addons, conflictos)

        println("PF-GATE | ────────────────────────────────────────")
        println("PF-GATE | conflictos=${conflictos.size} score=$score nivel=${gate.level.name} canExport=${gate.canExport}")
        conflictos.groupBy { it.severity }.toList().sortedByDescending { it.first.ordinal }.forEach { (sev, lista) ->
            println("PF-GATE | ${sev.name}: ${lista.size}")
        }
        conflictos.sortedByDescending { it.severity.ordinal }.take(30).forEach { c ->
            println("PF-GATE |   [${c.severity.name}] ${c.type} @ ${c.affectedFile} :: ${c.title}")
        }
        gate.blockers.forEach { println("PF-GATE | BLOQUEO: $it") }
        gate.warnings.forEach { println("PF-GATE | AVISO: $it") }
    }
}