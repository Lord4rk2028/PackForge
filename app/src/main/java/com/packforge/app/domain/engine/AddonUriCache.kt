package com.packforge.app.domain.engine

import android.net.Uri

object AddonUriCache {
    private val cache = mutableMapOf<String, Uri>()

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
