package com.packforge.app.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class ModrinthMod(
    val projectId: String,
    val title: String,
    val author: String,
    val description: String,
    val iconUrl: String?,
    val slug: String,
    val downloads: Int
)
