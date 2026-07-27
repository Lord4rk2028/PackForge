package com.packforge.app.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class ModpackMetadata(
    val name: String = "",
    val author: String = "",
    val version: String = "1.0.0",
    val mcVersion: String = "1.21.50",
    val description: String = "",
    val iconEmoji: String = "🎮",
    val tags: List<String> = emptyList(),
    val coverUriString: String? = null,
    val coverTemplateIndex: Int? = null
)
