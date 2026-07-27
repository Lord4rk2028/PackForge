package com.packforge.app.domain.model

sealed class OperationProgress {
    object Idle : OperationProgress()
    
    data class Loading(
        val message: String,
        val progress: Float? = null // 0.0 to 1.0, null if indeterminate
    ) : OperationProgress()
    
    data class Success(val message: String) : OperationProgress()
    data class Error(val message: String) : OperationProgress()
}
