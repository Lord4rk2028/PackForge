package com.packforge.app.util

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

object FileUtils {
    /**
     * Copia rápida por canales.
     *
     * ⭐ FASE 5 — GUARDA DE AUTOCOPIA (pérdida de datos real, medida con
     * core-craft: el validador dejaba el pack con 83 modelos de entidad cuando
     * tenía 155).
     *
     * Si `src` y `dest` son el MISMO archivo, copiar es destructivo: al abrir
     * `FileOutputStream(dest)` se trunca el archivo a 0 bytes y la lectura
     * posterior ya no encuentra nada, así que el resultado es un JSON VACÍO.
     * Ocurre siempre que el recurso ya está en su sitio y el "reparador" decide
     * copiarlo otra vez desde el propio pack.
     *
     * No es una copia que se pueda "saltar con gracia": no hay nada que hacer,
     * el contenido ya está donde debe. Se sale sin tocar el archivo.
     */
    fun fastCopy(src: File, dest: File) {
        // canonicalFile resuelve rutas relativas, symlinks y mayúsculas en Windows.
        if (runCatching { src.canonicalFile == dest.canonicalFile }.getOrDefault(false)) {
            return
        }
        dest.parentFile?.mkdirs()
        FileInputStream(src).channel.use { input ->
            FileOutputStream(dest).channel.use { output ->
                output.transferFrom(input, 0, Long.MAX_VALUE)
            }
        }
    }

    /**
     * Hilos para copias masivas de archivos.
     *
     * ⭐ RENDIMIENTO (medido con el reporte de fases): una fusión real de 4 addons
     * tardaba 1368 s, y el tramo de clasificación + separación BP/RP —que son
     * copias archivo por archivo— resultó ser el **40% del total** (545 s). El
     * trabajo es E/S independiente por archivo y está dominado por la latencia de
     * APERTURA (el antivirus de Windows cobra por apertura, no por byte), así que
     * repartirlo entre hilos es justo lo que reduce el tiempo.
     *
     * Se acota a 8 porque la máquina de referencia solo tiene 2 núcleos:
     * demasiadas aperturas concurrentes empeoran el escaneo del antivirus.
     */
    private val HILOS_COPIA: Int =
        Runtime.getRuntime().availableProcessors().coerceIn(2, 8)

    /**
     * Copia [files] de [srcRoot] a [destRoot] conservando su ruta relativa,
     * repartiendo el trabajo entre hilos.
     *
     * El orden de operaciones es deliberado:
     *  1. Crear TODAS las carpetas destino **en secuencia**. `mkdirs()` desde
     *     varios hilos sobre un padre compartido compite entre sí y devuelve
     *     `false` aunque la carpeta exista; hacerlo antes elimina esa carrera.
     *     Además se recuerda lo ya creado para no repetir la llamada por archivo.
     *  2. Copiar el CONTENIDO en paralelo, que es lo verdaderamente caro.
     *
     * Si alguna copia falla se propaga el primer error **al terminar**, de modo
     * que el llamante ve el fallo igual que con el bucle secuencial anterior,
     * pero sin dejar tareas a medias ni ocultar el motivo.
     *
     * @return número de archivos copiados.
     */
    fun copyFilesParallel(
        srcRoot: File,
        destRoot: File,
        files: List<File>,
        threads: Int = HILOS_COPIA
    ): Int {
        if (files.isEmpty()) return 0

        val pares = ArrayList<Pair<File, File>>(files.size)
        val carpetasCreadas = HashSet<String>()
        for (f in files) {
            val rel = f.relativeTo(srcRoot).path
            val destino = File(destRoot, rel)
            val padre = destino.parentFile
            if (padre != null && carpetasCreadas.add(padre.absolutePath)) {
                padre.mkdirs()
            }
            pares.add(f to destino)
        }

        val total = pares.size
        val siguiente = AtomicInteger(0)
        val errores = Collections.synchronizedList(ArrayList<Throwable>())
        val hilos = threads.coerceIn(1, total)
        val pool = Executors.newFixedThreadPool(hilos)
        try {
            val tareas = (1..hilos).map {
                Callable {
                    while (true) {
                        val i = siguiente.getAndIncrement()
                        if (i >= total) break
                        val (origen, destino) = pares[i]
                        try {
                            fastCopy(origen, destino)
                        } catch (e: Throwable) {
                            errores.add(e)
                        }
                    }
                }
            }
            pool.invokeAll(tareas)
        } finally {
            pool.shutdown()
        }

        if (errores.isNotEmpty()) {
            val primero = errores.first()
            throw if (primero is Exception) primero
            else RuntimeException("Error copiando archivos: ${primero.message}", primero)
        }
        return total
    }
}
