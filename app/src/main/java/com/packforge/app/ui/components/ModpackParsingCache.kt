package com.packforge.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil.request.ImageRequest
import java.util.concurrent.ConcurrentHashMap

/**
 * Cachés compartidas para el parseo del `addonsJson` de un modpack.
 *
 * POR QUÉ EXISTE ESTE ARCHIVO
 * ---------------------------
 * `extractModpackSource()` y `decodeModpackAddons()` se ejecutan **dentro del
 * scroll** de la biblioteca, una vez por tarjeta visible. Antes construían
 * `Gson()` y hasta cinco `Regex(...)` en cada llamada:
 *
 *  · `Gson()` es barato de crear pero NO gratuito, y se creaba por tarjeta y
 *    por pasada de composición.
 *  · `Regex(...)` compila un patrón y su autómata interno cada vez: es la
 *    parte más cara de las tres y la que producía el tirón al deslizar.
 *
 * Compilar esos objetos UNA vez y reutilizarlos convierte el trabajo por
 * tarjeta en una búsqueda de texto. Además se memoiza el RESULTADO por
 * `sourceJsonHead`, así que al volver a una tarjeta ya vista no se vuelve a
 * parsear nada: el scroll repetido es instantáneo.
 */
internal object ModpackParsingCache {

    /** Una sola instancia de Gson para toda la app (es thread-safe). */
    val gson: com.google.gson.Gson = com.google.gson.Gson()

    /** Tipo de `List<Addon>`, resuelto una sola vez. */
    val addonListType: java.lang.reflect.Type =
        object : com.google.gson.reflect.TypeToken<List<com.packforge.app.domain.model.Addon>>() {}.type

    // Patrones precompilados: antes se creaban en cada llamada desde el scroll.
    val siteRegex = Regex("\"sourceSite\"\\s*:\\s*\"([^\"]+)\"")
    val urlRegex = Regex("\"sourceUrl\"\\s*:\\s*\"([^\"]+)\"")
    val nameRegex = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"")

    /**
     * Resultado memoizado del origen web por fragmento de JSON.
     *
     * `ConcurrentHashMap` porque el acceso puede llegar desde el hilo de
     * composición y desde coroutines de IO.
     */
    private val sourceCache = ConcurrentHashMap<String, ModpackSourceHolder>()

    /** Envoltorio que admite `null` como valor cacheado. */
    class ModpackSourceHolder(val value: Any?)

    fun cachedSource(key: String): ModpackSourceHolder? = sourceCache[key]

    fun putSource(key: String, value: Any?) {
        // Tope defensivo: la clave es hasta 64 KB por modpack, así que un
        // usuario con cientos de modpacks no debe poder inflar la memoria.
        if (sourceCache.size > MAX_ENTRIES) sourceCache.clear()
        sourceCache[key] = ModpackSourceHolder(value)
    }

    private const val MAX_ENTRIES = 64
}

/**
 * Construye el `ImageRequest` de Coil **una sola vez** por modelo.
 *
 * Antes se instanciaba en cada recomposición de la tarjeta, lo que además de
 * asignar un objeto nuevo invalidaba el pipeline de Coil y podía relanzar la
 * carga de la imagen durante el scroll.
 *
 * @param targetSizePx lado destino en PÍXELES. Es la clave del scroll fluido:
 *   sin él Coil decodifica la portada a su resolución original (una de
 *   1024×1024 ocupa 4 MB en memoria) y después la reduce a 118dp. Con varias
 *   tarjetas así el heap se satura, el GC entra en acción durante el
 *   desplazamiento y aparecen los tirones. Con `size()` la imagen se
 *   decodifica YA al tamaño en que se muestra (~350 px), unas 30 veces menos
 *   memoria por portada.
 */
@Composable
internal fun rememberImageRequest(
    model: Any?,
    contentDescription: String?,
    crossfade: Boolean = false,
    targetSizePx: Int? = null
): ImageRequest {
    val context = LocalContext.current
    return remember(model, contentDescription, crossfade, targetSizePx, context) {
        ImageRequest.Builder(context)
            .data(model)
            // Sin crossfade por defecto: la transición de opacidad obliga a
            // recomponer la tarjeta mientras la imagen entra en pantalla, que
            // es exactamente el momento en que el usuario está deslizando.
            .crossfade(crossfade)
            .apply { if (targetSizePx != null) size(targetSizePx) }
            .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
            .diskCachePolicy(coil.request.CachePolicy.ENABLED)
            .build()
    }
}