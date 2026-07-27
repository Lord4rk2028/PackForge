package com.packforge.app.domain.model

sealed class ModrinthSearchState {
    data object Idle : ModrinthSearchState()
    data object Loading : ModrinthSearchState()
    data class Success(val mods: List<ModrinthMod>) : ModrinthSearchState()
    data class Error(val message: String) : ModrinthSearchState()
}
