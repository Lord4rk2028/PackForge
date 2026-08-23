package com.packforge.app.domain.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ═══════════════════════════════════════════════════════════════════════
 * SERVICIO DE FUSIÓN INTELIGENTE DE ADDONS (fachada pública)
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Punto de entrada único para fusionar N archivos .mcpack/.mcaddon/.zip en un
 * solo paquete funcional. Encapsula el pipeline completo de PackForge:
 *
 *   extracción → clasificación BP/RP → resolución de IDs (IdentifierRemapper)
 *   → fusión por componentes (JsonDeepMerger/BedrockComponentMerger)
 *   → registro de recursos (ResourcePathRegistry) → player.json merge
 *   → sound_definitions / particles / recetas / loot tables
 *   → manifiestos vinculados → validación post-fusión → .mcaddon final
 *
 * Uso:
 * ```
 * val result = AddonMergerService.mergeAddons(files, outputDir, name = "MiPack")
 * result.fold(
 *     onSuccess { file -> /* instalar/compartir */ },
 *     onFailure { e  -> /* mostrar error */ }
 * )
 * ```
 */
object AddonMergerService {

    data class Options(
        /** Nombre visible del modpack y del archivo final. */
        val name: String = "PackForge_Modpack",
        val author: String = "",
        val version: String = "1.0.0",
        val description: String = "",
        /** Ruta absoluta opcional a pack_icon.png personalizado. */
        val iconPath: String? = null,
        /** Callback de progreso (hilo IO). */
        val onProgress: ((message: String) -> Unit)? = null
    )

    /**
     * Fusiona los addons dados y devuelve el archivo .mcaddon resultante.
     *
     * @param addonFiles lista de .mcpack/.mcaddon/.zip (los inexistentes se ignoran;
     *                   si no queda ninguno válido devuelve failure).
     * @param outputDir carpeta destino del paquete fusionado.
     */
    suspend fun mergeAddons(
        addonFiles: List<File>,
        outputDir: File,
        options: Options = Options()
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val valid = addonFiles.filter { it.isFile && it.exists() }
            require(valid.isNotEmpty()) { "No hay addons válidos para fusionar." }
            require(outputDir.isDirectory || outputDir.mkdirs()) {
                "No se pudo crear el directorio de salida: ${outputDir.absolutePath}"
            }

            val callback = options.onProgress?.let { cb ->
                object : PackForgeOrchestrator.ProgressCallback {
                    override suspend fun onProgress(message: String) { cb(message) }
                }
            }

            val result = PackForgeOrchestrator.mergeAddons(
                addonPaths = valid.map { it.absolutePath },
                outputDir = outputDir.absolutePath,
                progressCallback = callback,
                customName = options.name.ifBlank { "PackForge_Modpack" },
                customAuthor = options.author,
                customVersion = options.version.ifBlank { "1.0.0" },
                customDescription = options.description,
                customIconPath = options.iconPath
            )

            check(result.success) { result.errorMessage ?: "La fusión falló sin detalle." }
            val outputFile = File(requireNotNull(result.outputPath) { "Ruta de salida nula." })
            check(outputFile.exists() && outputFile.length() > 0L) {
                "El paquete generado está vacío."
            }
            // El reporte legible queda en <outputDir>/fusion_report.txt (MergeReportGenerator).
            outputFile
        }
    }
}
