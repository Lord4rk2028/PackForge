package com.packforge.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Extrae el COLOR DOMINANTE de la portada de un modpack para teñir el borde de
 * su tarjeta en la biblioteca.
 *
 * CÓMO FUNCIONA
 * -------------
 * 1. Decodifica la imagen con `inSampleSize`, es decir a ~32 px de lado. Un
 *    bitmap de 32×32 son 1024 píxeles: recorrerlos es instantáneo, mientras
 *    que leer la portada a tamaño completo (p. ej. 1024×1024) costaría un
 *    millón de píxeles y arruinaría el scroll.
 * 2. Cuantiza cada píxel a cubos de 4 bits y se queda con el **cubo más
 *    frecuente**, no con la media. La media de una portada colorida da un
 *    marrón embarrado; el color más repetido sí representa la imagen.
 * 3. Ajusta el color para que sirva como BORDE: garantiza saturación y
 *    luminosidad mínimas, porque un borde gris o casi negro no se distingue
 *    del fondo y el efecto se perdería.
 *
 * Todo corre en [Dispatchers.IO] y el resultado se guarda en memoria por ruta,
 * así que cada portada se analiza UNA vez por sesión. Si algo falla (archivo
 * ausente, formato raro, sin permisos) devuelve `null` y la tarjeta conserva
 * el color de acento del tema, que es el respaldo acordado.
 */
object CoverColorExtractor {

    /** Tamaño de decodificación: suficiente para el color, trivial de recorrer. */
    private const val SAMPLE_TARGET_PX = 32

    private val cache = ConcurrentHashMap<String, Color>()

    /** Rutas ya analizadas que NO tenían color utilizable, para no reintentar. */
    private val negatives = ConcurrentHashMap.newKeySet<String>()

    /** Devuelve el color cacheado si ya se calculó, sin tocar el disco. */
    fun peek(path: String): Color? = cache[path]

    /**
     * Calcula (o recupera de caché) el color dominante de [path].
     *
     * @param path ruta absoluta, `file://` o `content://`
     * @return color listo para usar como borde, o `null` si no se pudo obtener
     */
    suspend fun extract(context: Context, path: String?): Color? {
        if (path.isNullOrBlank()) return null
        cache[path]?.let { return it }
        if (negatives.contains(path)) return null

        val color = withContext(Dispatchers.IO) { computeColor(context, path) }
        if (color == null) {
            if (negatives.size > MAX_NEGATIVES) negatives.clear()
            negatives.add(path)
        } else {
            if (cache.size > MAX_CACHE) cache.clear()
            cache[path] = color
        }
        return color
    }

    private fun computeColor(context: Context, path: String): Color? {
        val bitmap = decodeSmall(context, path) ?: return null
        return try {
            dominantColorOf(bitmap)
        } finally {
            // Liberar ya: son 1024 píxeles, pero mantener bitmaps vivos en una
            // lista larga es lo que hace que el GC provoque tirones después.
            bitmap.recycle()
        }
    }

    private fun decodeSmall(context: Context, path: String): Bitmap? = try {
        // 1ª pasada: solo medir, sin asignar memoria de píxeles.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(context, path)?.use { BitmapFactory.decodeStream(it, null, bounds) }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            // 2ª pasada: decodificar ya reducido al lado mayor deseado.
            val largest = maxOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (largest / (sample * 2) >= SAMPLE_TARGET_PX) sample *= 2

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565 // sin canal alfa: menos memoria
            }
            openStream(context, path)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }
    } catch (_: Throwable) {
        null
    }

    private fun openStream(context: Context, path: String): java.io.InputStream? {
        return try {
            when {
                path.startsWith("content://") ->
                    context.contentResolver.openInputStream(Uri.parse(path))
                path.startsWith("file://") -> {
                    val filePath = Uri.parse(path).path
                    if (filePath == null) null else File(filePath).inputStream()
                }
                else -> File(path).takeIf { it.exists() }?.inputStream()
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Cubo de color más frecuente, ajustado para ser visible como borde.
     */
    private fun dominantColorOf(bitmap: Bitmap): Color? {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // Cada cubo agrupa colores parecidos; la clave usa 4 bits por canal.
        val buckets = HashMap<Int, Int>(64)
        for (pixel in pixels) {
            val alpha = (pixel ushr 24) and 0xFF
            if (alpha < 128) continue // transparente: no aporta color

            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF

            // Se descartan casi-negros y casi-blancos: son sombras, marcos y
            // fondos, y como borde no aportarían identidad al modpack.
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            if (max < 28 || min > 232) continue

            val key = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
            buckets[key] = (buckets[key] ?: 0) + 1
        }

        val best = buckets.maxByOrNull { it.value }?.key ?: return null

        // Reconstruir el color en el centro de su cubo (no en la esquina).
        val r = (((best shr 8) and 0xF) shl 4) + 8
        val g = (((best shr 4) and 0xF) shl 4) + 8
        val b = ((best and 0xF) shl 4) + 8

        return normalizeForBorder(r, g, b)
    }

    /**
     * Garantiza que el color se lea como borde sobre `surfaceContainerLow`.
     *
     * Si el color es demasiado apagado se satura; si es demasiado oscuro se
     * aclara y si es demasiado claro se oscurece. Así una portada gris o muy
     * clara sigue produciendo un borde reconocible.
     */
    private fun normalizeForBorder(r: Int, g: Int, b: Int): Color {
        val max = maxOf(r, g, b).toFloat() / 255f
        val min = minOf(r, g, b).toFloat() / 255f
        val delta = max - min

        var hue = 0f
        var saturation = if (max == 0f) 0f else delta / max
        var value = max

        if (delta > 0.0001f) {
            hue = when (max) {
                r / 255f -> 60f * (((g - b) / 255f) / delta).mod(6f)
                g / 255f -> 60f * (((b - r) / 255f) / delta + 2f)
                else -> 60f * (((r - g) / 255f) / delta + 4f)
            }
        }

        saturation = saturation.coerceAtLeast(0.45f)
        value = value.coerceIn(0.42f, 0.86f)

        return Color.hsv(hue.mod(360f), saturation, value)
    }

    private const val MAX_CACHE = 96
    private const val MAX_NEGATIVES = 96
}