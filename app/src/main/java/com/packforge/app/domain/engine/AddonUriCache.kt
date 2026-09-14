package com.packforge.app.domain.engine

import android.net.Uri
import java.util.concurrent.ConcurrentHashMap

object AddonUriCache {
    // ConcurrentHashMap: el caché se consulta desde el hilo de UI al pintar la
    // lista de addons y se escribe desde coroutines de importación en background.
    // Con un mutableMapOf plano, leer mientras otro hilo escribe puede provocar
    // ConcurrentModificationException (crash al importar varios addons a la vez).
    private val cache = ConcurrentHashMap<String, Uri>()

    fun saveUri(addonId: String, uri: Uri) {
        cache[addonId] = uri
    }

    fun getUri(addonId: String): Uri? = cache[addonId]

    fun removeUri(addonId: String) {
        cache.remove(addonId)
    }

    fun clear() {
        cache.clear()
    }
}
