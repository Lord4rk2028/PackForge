package com.packforge.app.data.modrinth

import com.google.gson.annotations.SerializedName

data class ModrinthSearchResponse(
    val hits: List<ModrinthSearchHitDto>? = null,
    val offset: Int = 0,
    val limit: Int = 0,
    @SerializedName("total_hits")
    val totalHits: Int = 0
)

data class ModrinthSearchHitDto(
    @SerializedName("project_id")
    val projectId: String? = null,
    @SerializedName("id")
    val id: String? = null,
    val title: String? = null,
    val author: String? = null,
    val description: String? = null,
    @SerializedName("icon_url")
    val iconUrl: String? = null,
    val slug: String? = null,
    val downloads: Int = 0
)

data class ModrinthVersionDto(
    val id: String? = null,
    @SerializedName("project_id")
    val projectId: String? = null,
    val name: String? = null,
    @SerializedName("version_number")
    val versionNumber: String? = null,
    val files: List<ModrinthVersionFileDto>? = null
)

data class ModrinthVersionFileDto(
    val url: String? = null,
    val filename: String? = null,
    val primary: Boolean = false,
    val size: Long = 0L
)
