package com.packforge.app.domain.model

data class AddonContent(
    val addonId: String,
    val manifestJson: Map<String, Any>?,
    val entityFiles: Map<String, Map<String, Any>>,   // ruta -> contenido parseado
    val itemFiles: Map<String, Map<String, Any>>,
    val recipeFiles: Map<String, Map<String, Any>>,
    val lootTableFiles: Map<String, Map<String, Any>>,
    val textureFiles: Map<String, Map<String, Any>>,
    val soundFiles: Map<String, Map<String, Any>>,
    val scriptFiles: Map<String, String>,             // ruta -> código como String
    val otherFiles: Map<String, Map<String, Any>>
)
