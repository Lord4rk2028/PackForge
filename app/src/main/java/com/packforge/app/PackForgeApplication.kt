package com.packforge.app

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.packforge.app.domain.engine.VanillaAssets

class PackForgeApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        // Catálogo vanilla BASE: se carga una sola vez, antes de cualquier fusión.
        // Es la fuente de verdad de "esto lo resuelve el juego, no lo reportes".
        VanillaAssets.loadBundledCatalog(appContext)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.02)
                    .build()
            }
            .crossfade(true)
            .build()
    }

    companion object {
        /**
         * Contexto de aplicación para los motores que no lo reciben por
         * parámetro (el orquestador de fusión no es una clase Android).
         * En tests JVM queda en `null` y `VanillaAssets` cae al classpath.
         */
        @Volatile
        var appContext: Context? = null
            private set
    }
}