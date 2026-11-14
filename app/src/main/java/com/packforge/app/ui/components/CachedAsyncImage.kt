package com.packforge.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/**
 * Imagen con caché de memoria y disco.
 *
 * @param crossfade desactivado por defecto: la transición de opacidad fuerza
 *   recomposiciones mientras la imagen entra en el viewport, justo cuando el
 *   usuario desliza la lista. Coil ya cachea en memoria, así que al volver a
 *   una tarjeta la imagen aparece al primer fotograma y no se percibe corte.
 * @param cornerRadius se expone porque cada superficie usa un radio distinto;
 *   antes estaba fijo en 16dp y forzaba a envolver la imagen en otro `clip`.
 * @param decodeSize lado en dp al que se va a DIBUJAR la imagen. Se traduce a
 *   píxeles y se pasa a Coil para que decodifique ya reducido: es lo que evita
 *   que una portada de 1024×1024 se cargue entera (4 MB) solo para pintarla en
 *   118dp, origen de los tirones de scroll por presión de memoria. Pasa el
 *   tamaño real del contenedor; `null` deja el comportamiento anterior.
 * @param clip desactiva el recorte interno. En la biblioteca la portada ya va
 *   dentro de un `Box` con `clip(...)`: recortar TAMBIÉN la imagen duplicaba el
 *   trabajo de outline+canvas por portada en cada fotograma del scroll.
 *   Por defecto `true` para no cambiar el comportamiento del resto de pantallas.
 */
@Composable
fun CachedAsyncImage(
    model: Any?,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    cornerRadius: Dp = 16.dp,
    decodeSize: Dp? = null,
    crossfade: Boolean = false,
    colorFilter: ColorFilter? = null,
    onError: () -> Unit = {},
    clip: Boolean = true
) {
    val density = LocalDensity.current
    val targetPx = decodeSize?.let { with(density) { it.roundToPx() } }

    Box(modifier = modifier.fillMaxSize()) {
        AsyncImage(
            model = rememberImageRequest(
                model = model,
                contentDescription = contentDescription,
                crossfade = crossfade,
                targetSizePx = targetPx
            ),
            contentDescription = contentDescription,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (clip) Modifier.clip(RoundedCornerShape(cornerRadius)) else Modifier
                ),
            contentScale = contentScale,
            colorFilter = colorFilter,
            onError = { onError() }
        )
    }
}