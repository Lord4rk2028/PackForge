package com.packforge.app.domain.model

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

@Immutable
@Entity(tableName = "saved_modpacks")
data class SavedModpack(
    @PrimaryKey
    val id: String,
    val name: String,
    val author: String,
    val version: String,
    val mcVersion: String,
    val description: String,
    val addonNames: String,    // JSON array de nombres
    val addonCount: Int,
    val filePath: String,      // ruta en Descargas
    val fileName: String,
    val createdAt: Long,       // timestamp
    val coverUriString: String? = null,
    val tags: String = "",      // separados por coma
    val addonsJson: String = "" // JSON completo de los addons para recargar
)
