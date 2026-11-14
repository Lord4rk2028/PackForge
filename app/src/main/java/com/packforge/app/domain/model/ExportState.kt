package com.packforge.app.domain.model

sealed class ExportState {
    object Idle : ExportState()
    object Loading : ExportState()
    data class Progress(val message: String, val percent: Int = 0) : ExportState()
    data class Success(
        val fileName: String,
        val filePath: String,
        val importedToMinecraft: Boolean = false,
        /** Veredicto de compatibilidad, para pintar el semáforo en la pantalla de éxito. */
        val compatibility: com.packforge.app.domain.engine.CompatibilitySummary.Report? = null
    ) : ExportState()
    data class Error(val message: String) : ExportState()
}
