package com.packforge.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.packforge.app.ui.theme.ExpressiveAnimations
import com.packforge.app.ui.theme.ExpressiveElevation
import com.packforge.app.ui.theme.ExpressiveShapes
import com.packforge.app.ui.theme.LocalMotionLevel
import com.packforge.app.ui.theme.MotionLevel

/**
 * Entrada Expressive (fade + slide + scale) para el contenido de un `Dialog`.
 *
 * `AlertDialog` de Material3 no expone transiciones, y los `Dialog` crudos
 * aparecen de golpe. Este envoltorio unifica la entrada de TODOS los diálogos
 * con [ExpressiveAnimations.fadeSlideScaleIn], respetando el [MotionLevel]:
 *
 * - FULL/MODERATE/MINIMAL: rebote suave desde la mitad de la pantalla.
 * - NONE ("Reducir Movimiento"): sin envoltorio, el contenido aparece ya
 *   puesto en el primer fotograma.
 *
 * La salida la aporta el propio `Dialog` (su ventana se desvanece al
 * cerrarse); el `exit` de aquí queda por si el contenido se oculta antes de
 * destruir la ventana.
 */
@Composable
fun ExpressiveDialogEntrance(content: @Composable () -> Unit) {
    val motionLevel = LocalMotionLevel.current
    if (motionLevel == MotionLevel.NONE) {
        content()
        return
    }

    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    AnimatedVisibility(
        visible = visible,
        enter = ExpressiveAnimations.fadeSlideScaleIn(motionLevel),
        exit = ExpressiveAnimations.fadeSlideScaleOut(motionLevel)
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