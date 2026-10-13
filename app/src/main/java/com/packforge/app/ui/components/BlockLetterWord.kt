package com.packforge.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Wordmark "blocky" inspirado en la tipografía de los títulos de Minecraft
 * Bedrock y en las texturas de bloque de los addons (estilo *Better on
 * Minecraft*).
 *
 * Cada letra es un bloque independiente con volumen simulado en tres caras,
 * igual que un cubo isométrico:
 *
 * ```
 *   ▁▁▁▁▁▁   ← cara superior (brillo variable por letra)
 *   │ A  │   ← cara frontal (color del tema)
 *   ▔▔▔▔▔▔   ← canto inferior (sombra)
 * ```
 *
 * La variación es **determinista** —semilla por índice y código de carácter—
 * así que la misma palabra se ve siempre igual y no "tiembla" entre
 * recomposiciones, pero ninguna letra sale idéntica a su vecina. De ahí el
 * efecto de textura de bloques sin usar imágenes ni fuentes externas.
 *
 * Se usa una fuente monoespaciada para que los cubos queden perfectamente
 * cuadrados y alineados con cualquier fuente del sistema.
 *
 * @param text palabra a dibujar (se recomienda en mayúsculas)
 * @param cellSize lado del cubo de cada letra
 * @param fontSize tamaño del carácter dentro del cubo
 */
@Composable
fun BlockLetterWord(
    text: String,
    modifier: Modifier = Modifier,
    cellSize: Dp = 30.dp,
    fontSize: Int = 15
) {
    val base = MaterialTheme.colorScheme.primary
    val accent = MaterialTheme.colorScheme.tertiary

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        text.forEachIndexed { index, letter ->
            val seed = (index * 37 + letter.code) % 100

            // Cada parámetro sale de la semilla, así que dos letras seguidas
            // nunca comparten inclinación, brillo, altura ni color.
            val tilt = ((seed % 7) - 3).toFloat()        // −3° … +3°
            val lift = (seed % 4).dp                     // altura irregular
            val topAlpha = 0.38f + (seed % 4) * 0.09f    // brillo cara superior
            val edgeAlpha = 0.55f + (seed % 3) * 0.08f   // canto inferior
            val shade = if (seed % 2 == 0) base else accent
            val corner = (5 + seed % 3).dp               // radio distinto por letra
            val bevelInset = (5 + seed % 4).dp           // ancho del bisel

            Box(
                modifier = Modifier
                    .graphicsLayer {
                        rotationZ = tilt
                        translationY = -lift.toPx()
                    }
                    .size(cellSize),
                contentAlignment = Alignment.Center
            ) {
                // ── Cuerpo del bloque: degradado vertical que simula la cara
                // frontal iluminada desde arriba (como una textura de MC).
                Box(
                    modifier = Modifier
                        .size(cellSize)
                        .clip(RoundedCornerShape(corner))
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    shade.copy(alpha = 0.80f),
                                    shade,
                                    shade.copy(alpha = 0.68f)
                                )
                            )
                        )
                )

                // ── Cara superior: franja clara en el borde de arriba. Es el
                // detalle que hace que el cuadrado se lea como cubo 3D.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 2.dp)
                        .width(cellSize - bevelInset)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = topAlpha))
                )

                // ── Canto inferior: sombra que ancla el cubo y da profundidad.
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 2.dp)
                        .width(cellSize - bevelInset - 2.dp)
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(Color.Black.copy(alpha = 0.28f))
                )

                // ── Letra blanca con sombra dura: en MC el texto lleva contorno
                // para despegarse de la textura del bloque.
                Text(
                    text = letter.toString(),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = fontSize.sp
                    ),
                    color = Color.White.copy(alpha = edgeAlpha + 0.42f)
                )
            }
        }
    }
}