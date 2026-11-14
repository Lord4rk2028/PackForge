package com.packforge.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.packforge.app.ui.theme.ExpressiveElevation
import com.packforge.app.ui.theme.ExpressiveShapes
import com.packforge.app.ui.theme.LocalMotionLevel
import com.packforge.app.ui.theme.MotionLevel
import com.packforge.app.ui.theme.MotionTimings

/**
 * Entrada Expressive (fade + slide + scale) para el contenido de un `Dialog`.
 *
 * `AlertDialog` de Material3 no expone transiciones, y los `Dialog` crudos
 * aparecen de golpe.
 *
 * ⚠️ POR QUÉ YA NO SE USA `AnimatedVisibility` CON ESTADO PROPIO
 * ---------------------------------------------------------------
 * La versión anterior hacía:
 *
 *     var visible by remember { mutableStateOf(false) }
 *     LaunchedEffect(Unit) { visible = true }
 *     AnimatedVisibility(visible = visible, ...) { content() }
 *
 * Eso introducía un fotograma en el que el `Dialog` YA había abierto su ventana
 * de Android —con su scrim capturando todos los toques— pero el contenido aún
 * no se había compuesto, porque `visible` seguía en `false` hasta que corría la
 * `LaunchedEffect`. Durante ese hueco la pantalla mostraba un velo oscuro sin
 * nada dentro y sin responder: la sensación exacta de "menú trabado". Con varios
 * diálogos encadenados (o al abrir uno justo al reciclarse un ítem de lista) ese
 * hueco se percibía como un cuelgue.
 *
 * Además, el `exit` de aquel `AnimatedVisibility` nunca llegaba a ejecutarse de
 * forma fiable: `AlertDialog`/`Dialog` desmontan su ventana en cuanto el llamador
 * deja de componerlos, sin dar un fotograma extra para animar la salida. La
 * animación de salida era, en la práctica, código muerto.
 *
 * Ahora el contenido se compone SIEMPRE en el primer fotograma (nunca hay
 * ventana vacía) y la animación de entrada se aplica con `graphicsLayer` +
 * `animateFloatAsState`, que no condiciona la existencia del contenido: si la
 * animación se interrumpe o el MotionLevel es NONE, el contenido simplemente se
 * pinta en su estado final.
 */
@Composable
fun ExpressiveDialogEntrance(content: @Composable () -> Unit) {
    val motionLevel = LocalMotionLevel.current

    // Sin movimiento: contenido directo, sin capas ni animaciones.
    if (motionLevel == MotionLevel.NONE) {
        content()
        return
    }

    // 0f = recién abierto, 1f = asentado. Se anima a 1f en el primer fotograma
    // para que el contenido ya exista y la ventana nunca quede vacía.
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = MotionTimings.mediumDuration(motionLevel))
        )
    }

    val p = progress.value
    Box(
        modifier = Modifier.graphicsLayer {
            // Escala 0.94 → 1.0 y desplazamiento vertical suave.
            val scale = 0.94f + (0.06f * p)
            scaleX = scale
            scaleY = scale
            alpha = p.coerceIn(0f, 1f)
            translationY = (1f - p) * 48f
        }
    ) {
        content()
    }
}

/**
 * Diálogo de confirmación con forma, elevación y entrada Expressive.
 * Sustituye a `androidx.compose.material3.AlertDialog` en toda la app con la
 * misma API de slots (title/text/icon/confirmButton/dismissButton), añadiendo:
 *
 * - [ExpressiveShapes.dialog] (28dp) y [ExpressiveElevation.dialog] (12dp),
 *   los valores oficiales del sistema Expressive.
 * - Entrada animada con [ExpressiveDialogEntrance], adaptada al MotionLevel.
 * - Ancho M3: márgenes de 40dp y tope de 560dp en pantallas grandes.
 */
@Composable
fun ExpressiveAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties()
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier,
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = ExpressiveShapes.dialog,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        iconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = ExpressiveElevation.dialog,
        properties = properties
    )
}

/**
 * Sustituto directo de `androidx.compose.ui.window.Dialog` con entrada
 * Expressive. Misma API: solo cambia el nombre en el punto de llamado.
 *
 * Los diálogos personalizados (centro de actualizaciones, addons del
 * modpack, compartir, indicador de carga) conservan su contenido y
 * composición interna; lo único que ganan es la aparición con
 * fade + slide + scale adaptada al MotionLevel.
 */
@Composable
fun ExpressiveDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = properties
    ) {
        ExpressiveDialogEntrance(content = content)
    }
}