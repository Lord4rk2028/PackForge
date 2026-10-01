package com.packforge.app.domain.model

import androidx.compose.runtime.Immutable

/**
 * Versión LIGERA de [SavedModpack] para la biblioteca.
 *
 * No incluye las columnas pesadas (addonsJson, resolutionsJson) que pueden
 * pesar cientos de KB por fila. Si la lista de la biblioteca leyera esas
 * columnas de todas las filas a la vez, el CursorWindow de SQLite (2 MB por
 * defecto) se desborda con "Row too big to fit into CursorWindow" y la
 * biblioteca aparece vacía aunque los datos estén en la base de datos.
 *
 * Al ABRIR o REGENERAR un modpack se recupera la fila completa con
 * SavedModpackDao.getById(), que lee una sola fila y cabe sin problema.
 */
@Immutable
data class SavedModpackSummary(
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
    val conflictStrategy: String = "KEEP_FIRST",  // Estrategia de conflicto seleccionada
    /**
     * Primeros 64 KB del addonsJson: suficiente para detectar el ORIGEN WEB del
     * primer addon (badge "fuente" en la tarjeta) sin arrastrar la columna
     * completa de cientos de KB al CursorWindow.
     */
    val sourceJsonHead: String = ""
)