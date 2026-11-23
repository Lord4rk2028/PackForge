package com.packforge.app.domain.engine

import com.packforge.app.util.MinecraftTextSanitizer
import com.packforge.app.util.PackForgeLog
import com.packforge.app.util.logFile
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

object AddonExtractor {
    private const val TAG = "PackForge_Extractor"

    // ── LÍMITES DE SEGURIDAD AL DESCOMPRIMIR ─────────────────────────
    /** Máximo de entradas (archivos/carpetas) permitidas en un ZIP. */
    private const val MAX_ZIP_ENTRIES = 1_000_000
    /** Tamaño máximo por archivo extraído (500 MB). Soporta addons de hasta 180 MB. */
    private const val MAX_ENTRY_SIZE = 500L * 1024 * 1024
    /** Tamaño total máximo acumulado de la extracción (2 GB / 2000 MB). */
    private const val MAX_TOTAL_SIZE = 2000L * 1024 * 1024

    /**
     * Tope de entradas que se pueden descartar por no ser escribibles antes de
     * abortar (TICKET-CORE-014).
     *
     * Una entrada rara es un addon mal empaquetado y merece perdón; decenas son
     * un destino roto (sin espacio, cuota, volumen desmontado) y devolver un
     * pack incompleto como si fuera bueno sería peor que fallar.
     */
    private const val MAX_ENTRADAS_OMITIDAS = 50

    val CRITICAL_PATTERNS = listOf(
        "manifest.json", "terrain_texture.json", "item_texture.json",
        "blocks.json", "sounds.json"
    )

    fun shouldExtractToDisk(name: String): Boolean {
        // Una fusión real necesita el contenido completo. El filtrado anterior por
        // "archivos críticos" hacía que el export fuese sospechosamente rápido pero
        // eliminaba texturas, animaciones, definiciones y scripts no convencionales.
        // La seguridad no depende de filtrar extensiones: Zip Slip y los límites de
        // entradas/tamaño se validan antes de llegar a este punto.
        return name.isNotBlank()
    }

    /**
     * Resultado del análisis de un addon extraído
     */
    data class AddonAnalysis(
        val addonClassification: AddonClassification,
        val addonType: AddonType, // Mantener para compatibilidad
        val totalJsonFiles: Int,
        val manifestFiles: List<String>,
        val itemFiles: List<String>,
        val entityFiles: List<String>,
        val lootFiles: List<String>,
        val recipeFiles: List<String>,
        val otherJsonFiles: List<String>
    )

    /**
     * Clasificación detallada de un addon con rutas de subcarpetas si es BOTH
     */
    sealed class AddonClassification {
        object BEHAVIOR_PACK : AddonClassification()
        object RESOURCE_PACK : AddonClassification()
        /**
         * Addon que trae Behavior Pack y Resource Pack.
         *
         * ⭐ [bpFolders]/[rpFolders] existen porque un addon puede traer **más de
         * un** BP o RP. Caso real medido (`Origins-BE.mcaddon`): tres manifests →
         * `OriginsBE v1.5.6 BP` (1.855 archivos: 1.534 functions, 171 entities,
         * 89 scripts, 43 structures), `OriginsBE v1.5.6 RP` y
         * `Origins Craftable Orb of Origin Bev` (6 archivos).
         *
         * La versión anterior guardaba SOLO el primer BP encontrado, así que en
         * ese addon el BP grande se perdía **entero**: el modpack fusionado salía
         * sin sus entidades, funciones ni scripts, sin ningún aviso.
         *
         * [bpSubfolder]/[rpSubfolder] se mantienen como "el primero" para no
         * romper a quien solo consulte uno de los dos.
         */
        data class BOTH(
            val bpSubfolder: File,
            val rpSubfolder: File,
            val bpFolders: List<File> = listOf(bpSubfolder),
            val rpFolders: List<File> = listOf(rpSubfolder)
        ) : AddonClassification()
        /** Varios packs anidados (.mcpack/.zip dentro del addon). Cada uno se clasifica por separado. */
        data class MULTI(
            val packs: List<File>
        ) : AddonClassification()
        object UNKNOWN : AddonClassification()
    }
    
    /**
     * Tipo de addon detectado (legacy, mantener para compatibilidad)
     */
    enum class AddonType {
        BEHAVIOR_PACK,
        RESOURCE_PACK,
        BOTH,
        UNKNOWN
    }

    /**
     * Extrae el contenido de un archivo .mcaddon o .mcpack a una carpeta de destino
     * 
     * @param sourcePath Ruta del archivo .mcaddon/.mcpack
     * @param destinationPath Ruta de la carpeta donde se extraerán los archivos
     * @param maxEntries Límite de entradas del ZIP (inyectable para tests rápidos).
     * @param maxEntrySize Límite de bytes por archivo extraído.
     * @param maxTotalSize Límite de bytes totales acumulados en la extracción.
     * @param onEntrySkipped Se invoca si una entrada concreta no se pudo escribir
     *   (nombre saneado, motivo). Permite que el orquestador lo lleve al reporte
     *   sin que el extractor dependa de la capa de informe.
     * @return Ruta de la carpeta extraída, o null si hubo error
     */
    fun extractAddon(
        sourcePath: String,
        destinationPath: String,
        maxEntries: Int = MAX_ZIP_ENTRIES,
        maxEntrySize: Long = MAX_ENTRY_SIZE,
        maxTotalSize: Long = MAX_TOTAL_SIZE,
        onEntrySkipped: ((entrada: String, motivo: String) -> Unit)? = null
    ): String? {
        try {
            val sourceFile = File(sourcePath)
            if (!sourceFile.exists()) {
                PackForgeLog.e(TAG, "Archivo fuente no existe: $sourcePath")
                return null
            }

            val destDir = File(destinationPath)
            if (!destDir.exists()) {
                destDir.mkdirs()
            }

            // Determinación segura de la "zona" de extracción (una sola vez).
            val buffer = ByteArray(65536)
            var entryCount = 0
            var totalWritten = 0L
            var descartadas = 0

            FileInputStream(sourceFile).use { fis ->
                ZipInputStream(fis.buffered()).use { zis ->
                    var entry: ZipEntry? = zis.nextEntry
                    while (entry != null) {
                        // 1) Límite de nº de entradas: evita "zip bombs" con millones de entradas.
                        entryCount++
                        if (entryCount > maxEntries) {
                            throw IllegalStateException("ZIP excede el límite técnico del formato ($maxEntries entradas)")
                        }

                        // 2) VALIDACIÓN ZIP SLIP en TODAS las entradas (archivos y carpetas).
                        if (!isSecurePath(entry.name)) {
                            throw SecurityException("Zip Slip detected: ${entry.name}")
                        }

                        if (!entry.isDirectory) {
                            if (shouldExtractToDisk(entry.name)) {
                                // 3) Límite por archivo según el tamaño declarado en la cabecera ZIP.
                                // Ignora -1L y 0xFFFFFFFFL (comunes en Zip64 / Data Descriptor en Android para archivos grandes > 25MB)
                                if (entry.size > 0L && entry.size != 0xFFFFFFFFL && entry.size > maxEntrySize) {
                                    throw IllegalStateException("Archivo demasiado grande en ZIP: ${entry.name} (${entry.size} bytes)")
                                }

                                // ⭐ TICKET-CORE-014 — El nombre se sanea ANTES de
                                // tocar el disco, y la comprobación de seguridad se
                                // repite sobre el resultado: `sanitizeEntryName`
                                // normaliza `\` a `/`, así que un nombre que antes no
                                // era una ruta ahora podría serlo (o contener `..`).
                                val nombreSeguro = sanitizeEntryName(entry.name)
                                if (!isSecurePath(nombreSeguro)) {
                                    throw SecurityException("Zip Slip detected: ${entry.name}")
                                }

                                val newFile = File(destDir, nombreSeguro)
                                newFile.parentFile?.mkdirs()

                                // Descompresión normal (con byte-accounting para los límites).
                                var fileWritten = 0L
                                try {
                                    FileOutputStream(newFile).use { fos ->
                                        var read = zis.read(buffer)
                                        while (read != -1) {
                                            fos.write(buffer, 0, read)
                                            fileWritten += read
                                            totalWritten += read

                                            // 4) Límite real por archivo (cubre entradas con size = -1).
                                            if (fileWritten > maxEntrySize) {
                                                throw IllegalStateException("Archivo demasiado grande al descomprimir: ${entry.name}")
                                            }
                                            // 5) Límite total acumulado (200 MB por defecto).
                                            if (totalWritten > maxTotalSize) {
                                                throw IllegalStateException("Tamaño total de extracción supera el límite (${maxTotalSize / (1024 * 1024)} MB)")
                                            }
                                            read = zis.read(buffer)
                                        }
                                    }
                                } catch (e: IOException) {
                                    // ⭐ TICKET-CORE-014 — Una entrada que el sistema de
                                    // archivos no puede escribir NO puede abortar el addon:
                                    // se descarta esa entrada (borrando la parte que haya
                                    // quedado) y se sigue con las demás.
                                    //
                                    // Los límites de tamaño son `IllegalStateException` a
                                    // propósito y NO se capturan aquí: si se disparan, la
                                    // extracción debe abortar (posible ZIP bomb).
                                    runCatching { newFile.delete() }
                                    // Sanear libera una entrada por nombre inválido; si aun
                                    // así falla, es E/S del dispositivo (disco lleno, cuota,
                                    // fichero bloqueado). Se cuenta aparte para no confundir
                                    // «nombre raro» con «no queda espacio».
                                    descartadas++
                                    val motivo = e.message ?: e.javaClass.simpleName
                                    PackForgeLog.w(TAG, "⚠️ Entrada omitida '${entry.name}': $motivo")
                                    onEntrySkipped?.invoke(nombreSeguro, motivo)
                                    // Muchos fallos seguidos ya no es un archivo raro: es un
                                    // destino roto. Seguir solo produce un pack incompleto
                                    // que finge estar bien.
                                    if (descartadas > MAX_ENTRADAS_OMITIDAS) {
                                        throw IOException(
                                            "Demasiadas entradas no escribibles ($descartadas); " +
                                                "último fallo: $motivo", e
                                        )
                                    }
                                }
                                if (nombreSeguro != entry.name) {
                                    PackForgeLog.w(
                                        TAG,
                                        "Nombre de entrada saneado: '${entry.name}' → '$nombreSeguro' " +
                                            "(caracteres no admitidos por el sistema de archivos)"
                                    )
                                }
                                logFile { "Extraído: ${entry.name} ($fileWritten bytes)" }
                            }
                        } else {
                            // Entrada de directorio: también debe estar dentro de la zona segura.
                            runCatching { File(destDir, sanitizeEntryName(entry.name)).mkdirs() }
                            logFile { "Directorio: ${entry.name}" }
                        }
                        entry = zis.nextEntry
                    }
                }
            }

            PackForgeLog.d(TAG, "Extracción completada en: $destinationPath")

            // ⭐ FASE 5 (fix real): un archivo que NO es un ZIP no lanza excepción:
            // ZipInputStream simplemente no devuelve ninguna entrada. Antes se
            // devolvía la ruta igualmente, así que el orquestador daba la
            // extracción por buena, seguía con una carpeta VACÍA y el reporte
            // acababa diciendo "fusión completada" sobre un addon que jamás se
            // pudo leer. Ahora, cero entradas = fallo explícito.
            if (entryCount == 0) {
                cleanupExtractedFolder(destinationPath)
                PackForgeLog.e(TAG, "❌ El archivo no es un ZIP válido o está vacío: $sourcePath")
                return null
            }

            return destinationPath

        } catch (e: SecurityException) {
            // Zip Slip detectado: se limpia lo parcialmente extraído y se NOTIFICA.
            cleanupExtractedFolder(destinationPath)
            PackForgeLog.e(TAG, "❌ ${e.message}", e)
            throw e
        } catch (e: IllegalStateException) {
            // Límite de seguridad excedido (entradas / tamaño): limpiar y notificar.
            cleanupExtractedFolder(destinationPath)
            PackForgeLog.e(TAG, "❌ Límite de seguridad excedido: ${e.message}", e)
            throw e
        } catch (e: Exception) {
            // Otro fallo (I/O, ZIP corrupto): limpiar parciales y fallar suave.
            cleanupExtractedFolder(destinationPath)
            PackForgeLog.e(TAG, "Error al extraer addon: ${e.message}", e)
            return null
        }
    }

    /**
     * ⭐ TICKET-CORE-014 — Nombres de entrada que el sistema de archivos rechaza.
     *
     * Caso real que motivó esto: `Desse-UI` trae una entrada llamada
     * **`"Note".txt`** (con las comillas dobles dentro del nombre). Windows y
     * Android sobre FAT/exFAT no admiten comillas en un nombre de archivo, así
     * que `FileOutputStream` lanzaba `FileNotFoundException: ... \"Note\".txt
     * (El nombre de archivo, el nombre de directorio o la sintaxis de la
     * etiqueta del volumen no son correctos)`.
     *
     * El `catch` de esa excepción estaba FUERA del bucle de entradas, así que
     * —al no ser `SecurityException` ni `IllegalStateException`— caía en el
     * `catch (Exception)` genérico, devolvía `null` y **abortaba la extracción
     * del addon completo**: 11 MB de texturas y shaders quedaban sin importar
     * por una nota de texto.
     */
    private val CARACTERES_ILEGALES = charArrayOf('"', '*', ':', '<', '>', '?', '|')

    /**
     * Nombres reservados del SO (Windows y algunos drivers Android). Un archivo
     * llamado exactamente `con` o `nul` no se puede crear con ese nombre, ni
     * siquiera con extensión en algunos casos.
     */
    private val NOMBRES_RESERVADOS = setOf(
        "con", "prn", "aux", "nul",
        "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
        "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9"
    )

    /**
     * Convierte un nombre de entrada ZIP en uno que el sistema de archivos del
     * dispositivo pueda crear de verdad.
     *
     * Se aplica **por segmento**, así que la jerarquía de carpetas se conserva:
     * `RP_PackForge/"Note".txt` → `RP_PackForge/_Note_.txt`.
     *
     * Qué NO hace (deliberadamente):
     * - No decide seguridad: eso es [isSecurePath], y se vuelve a comprobar
     *   sobre el nombre ya saneado porque normalizar `\\` a `/` puede convertir
     *   un nombre inocente en una ruta con `..`.
     * - No recorta el nombre a un tamaño arbitrario: truncar por longitud
     *   colisionaría dos archivos distintos y perdería contenido en silencio.
     */
    fun sanitizeEntryName(entryName: String): String {
        // Los ZIP antiguos usan `\` como separador. Se normaliza ANTES de
        // separar, o `carpeta\archivo.png` quedaría como un único nombre legal
        // con una barra invertida dentro.
        val normalizado = entryName.replace('\\', '/')
        return normalizado.split('/').joinToString("/") { sanearSegmento(it) }
    }

    private fun sanearSegmento(segmento: String): String {
        if (segmento.isEmpty() || segmento == "." || segmento == "..") return segmento

        val limpio = buildString(segmento.length) {
            for (c in segmento) {
                append(
                    when {
                        c in CARACTERES_ILEGALES -> '_'
                        // Controles (0x00-0x1F) y DEL: ninguna API de archivos los
                        // acepta, y en Android rompen `open()` con un mensaje opaco.
                        c.code < 0x20 || c.code == 0x7F -> '_'
                        else -> c
                    }
                )
            }
        }

        // Windows no admite nombres terminados en punto ni en espacio: el
        // sistema los recorta en silencio y luego el archivo no aparece.
        val recortado = limpio.trimEnd('.', ' ')
        if (recortado.isEmpty()) return "_"

        // `CON`, `nul.txt`, `COM1.json`… siguen siendo dispositivos reservados
        // aunque lleven extensión: se comprueba solo la parte anterior al punto.
        val base = recortado.substringBefore('.').lowercase()
        return if (base in NOMBRES_RESERVADOS) "_$recortado" else recortado
    }

    /**
     * Valida que una entrada del ZIP no escape del directorio de destino
     * (vulnerabilidad "Zip Slip").
     *
     * ⭐ TICKET-CORE-014 — La comprobación es **léxica a propósito**, no
     * `canonicalPath`.
     *
     * Antes se resolvían rutas canónicas y se comparaba el prefijo. Eso tiene
     * dos problemas, y el segundo es grave:
     *
     * 1. En Windows, `getCanonicalPath()` valida el nombre contra la API de
     *    archivos, así que **lanza IOException para nombres legales dentro del
     *    destino** como `b*c.json` o `d?e.json` (caracteres que Windows reserva
     *    para comodines). Esa rama devolvía `false` → "inseguro".
     * 2. Un simple `*` en el nombre de una entrada se reportaba entonces como
     *    **«Zip Slip detected»** y abortaba la extracción del addon entero: un
     *    ataque de seguridad anunciado donde solo había un nombre de archivo
     *    feo. Un falso positivo así no se nota como bug de robustez, se nota
     *    como un addon que "no se puede importar".
     *
     * El escape de directorio es una propiedad **del texto**, no del sistema de
     * archivos, así que se decide sin tocar el disco: si algún segmento es `..`,
     * si la ruta es absoluta, si trae unidad de disco (`C:`) o un carácter de
     * control, se rechaza; cualquier otra cosa es un nombre raro y se sanea en
     * [sanitizeEntryName]. Es determinista y da igual en qué SO corra el test.
     */
    private fun isSecurePath(entryName: String): Boolean {
        val normalizado = entryName.replace('\\', '/')
        if (normalizado.isEmpty()) return false

        // Absoluta: "/etc/x", "//servidor/x".
        if (normalizado.startsWith("/")) return false
        // Unidad de disco o flujo alternativo ("C:/x", "C:x").
        if (normalizado.length >= 2 && normalizado[1] == ':') return false
        // Caracteres de control: en una API nativa podrían truncar el nombre y
        // colarse como una ruta distinta de la validada aquí.
        if (normalizado.any { it.code < 0x20 || it.code == 0x7F }) return false
        // `..` como segmento propio escapa del destino. Comparar el SEGMENTO
        // completo importa: "..evil.png" es un nombre de archivo legítimo.
        if (normalizado.split('/').any { it == ".." }) return false

        return true
    }

    /**
     * Analiza una carpeta extraída y clasifica todos los archivos JSON
     * 
     * @param extractedPath Ruta de la carpeta extraída
     * @return AddonAnalysis con la clasificación de archivos
     */
    fun analyzeExtractedAddon(extractedPath: String): AddonAnalysis {
        val extractedDir = File(extractedPath)
        PackForgeLog.d(TAG, "🔍 DIAG: Analizando addon: ${extractedDir.name}") // DIAGNÓSTICO
        if (!extractedDir.exists()) {
            PackForgeLog.e(TAG, "Carpeta extraída no existe: $extractedPath")
            return AddonAnalysis(
                AddonClassification.UNKNOWN, AddonType.UNKNOWN, 0, emptyList(), emptyList(),
                emptyList(), emptyList(), emptyList(), emptyList()
            )
        }

        val manifestFiles = mutableListOf<String>()
        val itemFiles = mutableListOf<String>()
        val entityFiles = mutableListOf<String>()
        val lootFiles = mutableListOf<String>()
        val recipeFiles = mutableListOf<String>()
        val otherJsonFiles = mutableListOf<String>()

        // Buscar recursivamente todos los archivos JSON
        findJsonFiles(extractedDir, "", manifestFiles, itemFiles, entityFiles, lootFiles, recipeFiles, otherJsonFiles)

        // Detectar tipo de addon clasificando por CONTENIDO del manifest (no por nombre).
        val addonClassification = classify(extractedDir)
        val addonType = when (addonClassification) {
            is AddonClassification.BEHAVIOR_PACK -> AddonType.BEHAVIOR_PACK
            is AddonClassification.RESOURCE_PACK -> AddonType.RESOURCE_PACK
            is AddonClassification.BOTH,
            is AddonClassification.MULTI -> AddonType.BOTH
            is AddonClassification.UNKNOWN -> AddonType.UNKNOWN
        }

        val totalJsonFiles = manifestFiles.size + itemFiles.size + entityFiles.size + 
                           lootFiles.size + recipeFiles.size + otherJsonFiles.size

        PackForgeLog.d(TAG, "Análisis completado - Tipo: $addonType, Total JSONs: $totalJsonFiles")

        // REGISTRAR: estructuras raras detectadas (para que aparezcan en Conflictos)
        if (addonClassification is AddonClassification.UNKNOWN) {
            ConflictRegistry.logConflict(
                severity = com.packforge.app.domain.model.ConflictSeverity.WARNING,
                type = "UNKNOWN_STRUCTURE",
                file = extractedDir.name,
                addon1 = extractedDir.name,
                addon2 = "",
                description = "Estructura de addon no reconocida. Puede que algunos " +
                    "archivos no se fusionen correctamente."
            )
        }

        return AddonAnalysis(
            addonClassification = addonClassification,
            addonType = addonType,
            totalJsonFiles = totalJsonFiles,
            manifestFiles = manifestFiles,
            itemFiles = itemFiles,
            entityFiles = entityFiles,
            lootFiles = lootFiles,
            recipeFiles = recipeFiles,
            otherJsonFiles = otherJsonFiles
        )
    }

    /**
     * Busca recursivamente archivos JSON y los clasifica.
     * OPTIMIZACIÓN: usa DirIndexCache (cacheado) en lugar de walkTopDown nuevo.
     */
    private fun findJsonFiles(
        dir: File,
        basePath: String,
        manifestFiles: MutableList<String>,
        itemFiles: MutableList<String>,
        entityFiles: MutableList<String>,
        lootFiles: MutableList<String>,
        recipeFiles: MutableList<String>,
        otherJsonFiles: MutableList<String>
    ) {
        val jsonFiles = DirIndexCache.index(dir).jsonFiles
        jsonFiles.forEach { file ->
            val rel = file.relativeTo(dir).path.replace("\\", "/")
            val fullPath = if (rel.isEmpty()) file.name else rel
            val lowerPath = fullPath.lowercase()

            when {
                file.name == "manifest.json" -> {
                    manifestFiles.add(fullPath)
                }
                file.name == "blocks.json" ||
                lowerPath.contains("items/") ||
                lowerPath.contains("/item/") || lowerPath.startsWith("items/") -> {
                    itemFiles.add(fullPath)
                }
                lowerPath.contains("entities/") || lowerPath.contains("/entity/") -> {
                    entityFiles.add(fullPath)
                }
                lowerPath.contains("loot_tables/") || lowerPath.contains("loot_tables") -> {
                    lootFiles.add(fullPath)
                }
                lowerPath.contains("recipes/") || lowerPath.contains("recipes") -> {
                    recipeFiles.add(fullPath)
                }
                else -> {
                    otherJsonFiles.add(fullPath)
                }
            }
        }
    }

    /**
     * CLASIFICACIÓN UNIVERSAL por CONTENIDO del manifest.json (no por nombre de carpeta).
     *
     * 1. manifest.json en la raíz → tipo según modules[].type (JEI)
     * 2. ZIPs anidados (.mcpack/.zip) → se extraen a subcarpetas (More Tools) y se devuelven como MULTI
     * 3. Subcarpetas con manifest.json → cada una se clasifica por contenido (Corecraft, The Lost Mobs)
     */
    fun classify(dir: File): AddonClassification {
        // CASO 1: manifest.json en la raíz → clasificar por contenido
        val rootManifest = File(dir, "manifest.json")
        if (rootManifest.exists()) {
            return classifyByManifestContent(rootManifest)
        }

        // CASO 2: ZIPs anidados (.mcpack o .zip dentro) → extraer y clasificar cada uno
        // ⭐ OPTIMIZACIÓN: Usar DirIndexCache en lugar de walkTopDown()
        val nestedZips = DirIndexCache.index(dir).allFiles
            .filter { it.extension.equals("mcpack", true) || it.extension.equals("zip", true) }

        if (nestedZips.isNotEmpty()) {
            PackForgeLog.d("PackForge_Classify", "📦 ZIPs anidados detectados: ${nestedZips.size}")
            val packs = mutableListOf<File>()
            nestedZips.forEach { zip ->
                val packDir = File(dir, "nested_${zip.nameWithoutExtension}")
                packDir.mkdirs()
                val extracted = extractAddon(zip.absolutePath, packDir.absolutePath)
                if (extracted != null) {
                    // zip.delete() // ⚠️ ¡NO ELIMINAR EL ZIP! Necesitamos mantenerlo para Nivel 3.
                    packs.add(packDir)
                    PackForgeLog.d("PackForge_Classify", "  ✅ Extraído: ${zip.name}")
                }
            }

            // ⚠️ Además de los packs anidados hay que conservar las carpetas
            // PROPIAS del addon (bp/, rp/). Antes se retornaba solo `packs`, así
            // que un .mcaddon con sus carpetas Y un .mcpack dentro perdía TODO
            // su contenido externo: sin Behavior Pack, sin texturas y sin
            // modelos. Es un patrón muy común (el .mcaddon actúa de contenedor).
            val ownFolders = dir.listFiles()
                ?.filter { folder ->
                    folder.isDirectory &&
                        !folder.name.startsWith("nested_") &&
                        File(folder, "manifest.json").exists()
                }
                .orEmpty()

            return AddonClassification.MULTI(
                if (ownFolders.isEmpty()) packs else ownFolders + packs
            )
        }

        // CASO 3: Búsqueda profunda de manifiesta (subcarpetas inmediatas o envueltas)
        val allManifests = DirIndexCache.index(dir).allFiles
            .filter { it.name.equals("manifest.json", ignoreCase = true) }

        if (allManifests.size >= 2) {
            // ⭐ Se recogen TODOS los BP y RP, no solo el primero.
            //
            // Antes: `if (bpFolder == null) bpFolder = parent`. Un addon con dos
            // Behavior Packs (caso real `Origins-BE`: el de 1.855 archivos y el
            // "Craftable Orb" de 6) conservaba el que apareciera primero en el
            // recorrido y DESCARTABA el otro sin ningún aviso, así que el modpack
            // salía sin entidades ni funciones.
            val bpFolders = mutableListOf<File>()
            val rpFolders = mutableListOf<File>()

            fun agregar(lista: MutableList<File>, carpeta: File) {
                if (lista.none { it.absolutePath == carpeta.absolutePath }) lista.add(carpeta)
            }

            allManifests.forEach { manifestFile ->
                val parent = manifestFile.parentFile ?: dir
                when (classifyByManifestContent(manifestFile)) {
                    is AddonClassification.BEHAVIOR_PACK -> agregar(bpFolders, parent)
                    is AddonClassification.RESOURCE_PACK -> agregar(rpFolders, parent)
                    is AddonClassification.BOTH -> {
                        agregar(bpFolders, parent)
                        agregar(rpFolders, parent)
                    }
                    else -> {}
                }
            }

            if (bpFolders.isNotEmpty() && rpFolders.isNotEmpty()) {
                PackForgeLog.d("PackForge_Classify", "🔀 BOTH detectado por contenido:")
                bpFolders.forEach { PackForgeLog.d("PackForge_Classify", "   BP: ${it.name}") }
                rpFolders.forEach { PackForgeLog.d("PackForge_Classify", "   RP: ${it.name}") }
                return AddonClassification.BOTH(
                    bpFolders.first(), rpFolders.first(), bpFolders, rpFolders
                )
            } else if (bpFolders.isNotEmpty()) {
                return AddonClassification.BEHAVIOR_PACK
            } else if (rpFolders.isNotEmpty()) {
                return AddonClassification.RESOURCE_PACK
            }
        }

        if (allManifests.size == 1) {
            val res = classifyByManifestContent(allManifests[0])
            if (res !is AddonClassification.UNKNOWN) return res
        }

        // FALLBACK POR ESTRUCTURA DE ARCHIVOS DE LA CARPETA
        val allFiles = DirIndexCache.index(dir).allFiles
        val lowerPaths = allFiles.map { it.relativeTo(dir).path.replace("\\", "/").lowercase() }
        val hasBp = lowerPaths.any {
            it.contains("items/") || it.contains("entities/") || it.contains("recipes/") ||
            it.contains("loot_tables/") || it.contains("functions/") || it.contains("scripts/") ||
            it.contains("blocks/")
        }
        val hasRp = lowerPaths.any {
            it.contains("textures/") || it.contains("models/") || it.contains("sounds/") ||
            it.contains("entity/") || it.contains("render_controllers/") || it.contains("particles/") ||
            it.contains("animation_controllers/") || it.contains("font/") || it.contains("ui/")
        }

        if (hasBp && hasRp) {
            PackForgeLog.d("PackForge_Classify", "🔀 BOTH detectado por archivos de estructura en: ${dir.name}")
            return AddonClassification.BOTH(dir, dir)
        } else if (hasBp) {
            PackForgeLog.d("PackForge_Classify", "🔵 BP detectado por archivos de estructura en: ${dir.name}")
            return AddonClassification.BEHAVIOR_PACK
        } else if (hasRp) {
            PackForgeLog.d("PackForge_Classify", "🟢 RP detectado por archivos de estructura en: ${dir.name}")
            return AddonClassification.RESOURCE_PACK
        }

        PackForgeLog.w("PackForge_Classify", "⚠️ Sin clasificación: ${dir.name}")
        return AddonClassification.UNKNOWN
    }

    /**
     * ⭐ LA CLAVE: clasificar por CONTENIDO del manifest, no por nombre ⭐
     * Lee modules[].type: "data" → BEHAVIOR_PACK, "resources"/"resource" → RESOURCE_PACK.
     * Funciona con CUALQUIER nombre de carpeta y con TODAS las versiones del manifest
     * (format_version 0/1 usa "resource" en singular; format_version 2/3 usa "resources";
     * soporta también "script", "client_data", "interface", "javascript", "world_template").
     */
    fun classifyByManifestContent(manifestFile: File): AddonClassification {
        return try {
            val json = JSONObject(manifestFile.readText(Charsets.UTF_8))
            val modules = json.optJSONArray("modules") ?: return AddonClassification.UNKNOWN
            var hasBp = false
            var hasRp = false

            for (i in 0 until modules.length()) {
                val mod = modules.optJSONObject(i) ?: continue
                val type = mod.optString("type", "").lowercase()
                when (type) {
                    "data", "script", "javascript", "world_template" -> hasBp = true
                    "resources", "resource", "client_data", "interface", "textures" -> hasRp = true
                }
            }

            val parent = manifestFile.parentFile ?: manifestFile
            when {
                hasBp && hasRp -> AddonClassification.BOTH(parent, parent)
                hasBp -> AddonClassification.BEHAVIOR_PACK
                hasRp -> AddonClassification.RESOURCE_PACK
                else -> AddonClassification.UNKNOWN
            }
        } catch (e: Exception) {
            PackForgeLog.e("PackForge_Classify", "❌ Error leyendo manifest: ${e.message}")
            AddonClassification.UNKNOWN
        }
    }

    /**
     * Raíz real de un pack anidado: la carpeta que contiene manifest.json.
     * Si el manifest está en la raíz de `dir`, se devuelve `dir` mismo.
     * ⭐ OPTIMIZACIÓN: Usar DirIndexCache en lugar de walkTopDown()
     *
     * @param tipoEsperado si se indica "data" o "resources", se elige la carpeta
     *   cuyo manifest declare ese módulo. Necesario en .mcaddon que traen
     *   `BP_x/manifest.json` y `RP_x/manifest.json`: devolver el PRIMERO (que la
     *   API entrega como el BP) hacía que el pipeline tratase el Behavior Pack
     *   como Resource Pack, buscando `models/` y `textures/` donde no están.
     */
    fun resolvePackRoot(dir: File, tipoEsperado: String? = null): File {
        val manifests = DirIndexCache.index(dir).allFiles
            .filter { it.name.equals("manifest.json", ignoreCase = true) }

        if (manifests.isEmpty()) return dir

        // Sin tipo pedido: comportamiento histórico (primer manifest hallado).
        if (tipoEsperado == null) return manifests.first().parentFile ?: dir

        // Con tipo pedido: preferir el manifest que declare ese módulo.
        val porTipo = manifests.firstOrNull { m ->
            val modulos = runCatching {
                JSONObject(m.readText(Charsets.UTF_8)).optJSONArray("modules")
            }.getOrNull() ?: return@firstOrNull false
            (0 until modulos.length()).any { i ->
                modulos.optJSONObject(i)?.optString("type") == tipoEsperado
            }
        }
        return (porTipo ?: manifests.first()).parentFile ?: dir
    }

    /**
     * Limpia una carpeta extraída (borra todos los archivos)
     */
    fun cleanupExtractedFolder(path: String): Boolean {
        return try {
            val dir = File(path)
            if (dir.exists()) {
                dir.deleteRecursively()
                PackForgeLog.d(TAG, "Carpeta limpiada: $path")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            PackForgeLog.e(TAG, "Error al limpiar carpeta: ${e.message}", e)
            false
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // RESOLUCIÓN UNIVERSAL DE NOMBRE + TIPO (3 NIVELES)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Resultado de resolución de un addon: nombre visible + clasificación.
     */
    data class AddonInfo(
        val displayName: String,
        val classification: AddonClassification
    )

    /**
     * ⭐ Lee manifest.json desde un ZIP usando ZipFile (acceso aleatorio, rápido) ⭐
     * Busca "manifest.json" en la raíz o en cualquier subcarpeta del ZIP.
     * NO extrae nada al disco.
     */
    fun readManifestFromZip(zipFile: File): JSONObject? {
        return try {
            PackForgeLog.d("PackForge_Info", "Intentando leer manifest de ZIP: ${zipFile.absolutePath}")
            ZipFile(zipFile).use { zip ->
                // Priorizar manifest.json en la raíz
                val entry = zip.getEntry("manifest.json")
                    ?: zip.entries().asSequence().firstOrNull {
                        val isManifest = !it.isDirectory && it.name.replace("\\", "/")
                            .endsWith("manifest.json", ignoreCase = true)
                        if (isManifest) PackForgeLog.d("PackForge_Info", "  Manifest encontrado: ${it.name}")
                        isManifest
                    }
                
                if (entry == null) {
                    PackForgeLog.w("PackForge_Info", "  No se encontró manifest.json en ${zipFile.name}")
                }
                
                entry?.let { e ->
                    zip.getInputStream(e).bufferedReader(Charsets.UTF_8).use { reader ->
                        JSONObject(reader.readText())
                    }
                }
            }
        } catch (e: Exception) {
            PackForgeLog.w("PackForge_Info", "No se pudo leer manifest de ${zipFile.name}: ${e.message}")
            null
        }
    }

    /**
     * ⭐ Lee manifest.json desde bytes de un ZIP en memoria (streaming) ⭐
     * Para usar cuando ya tenemos los bytes en memoria (ej: al leer un .mcpack
     * desde un ZipInputStream dentro de un .mcaddon).
     */
    fun readManifestFromZipBytes(zipBytes: ByteArray): JSONObject? {
        return try {
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory &&
                        entry.name.replace("\\", "/")
                            .endsWith("manifest.json", ignoreCase = true)
                    ) {
                        return JSONObject(String(zis.readBytes(), Charsets.UTF_8))
                    }
                    entry = zis.nextEntry
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Lee y parsea un archivo .lang de Minecraft, devolviendo clave -> valor.
     * Soporta UTF-8, UTF-16LE/BE con BOM y comentarios (#).
     */
    fun parseLangFile(file: File): Map<String, String> {
        val translations = mutableMapOf<String, String>()
        try {
            if (!file.exists() || !file.isFile) return translations
            val bytes = file.readBytes()
            val text = when {
                bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
                bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
                bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
                else -> String(bytes, Charsets.UTF_8)
            }
            text.lines().forEach { rawLine ->
                val line = rawLine.substringBefore('#').trim()
                if (line.isNotBlank()) {
                    val parts = line.split("=", limit = 2)
                    if (parts.size == 2) {
                        val key = parts[0].trim().removePrefix("%").lowercase()
                        val value = parts[1].trim()
                        if (key.isNotBlank() && value.isNotBlank()) {
                            translations[key] = value
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return translations
    }

    /**
     * Busca todos los archivos .lang dentro de extractedDir (en texts/, RP/texts/, BP/texts/, subcarpetas)
     * y extrae el mapa consolidado de traducciones.
     */
    fun resolveLangTranslations(extractedDir: File): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            DirIndexCache.invalidate(extractedDir)
            val langFiles = DirIndexCache.index(extractedDir).allFiles
                .filter { it.extension.equals("lang", ignoreCase = true) }

            // Priorizar en_US, es_ES, es_MX si existen
            val sortedFiles = langFiles.sortedByDescending { file ->
                val name = file.nameWithoutExtension.lowercase()
                when {
                    name == "en_us" -> 3
                    name.startsWith("es_") -> 2
                    name.startsWith("en_") -> 1
                    else -> 0
                }
            }

            sortedFiles.forEach { file ->
                val parsed = parseLangFile(file)
                parsed.forEach { (k, v) ->
                    if (!result.containsKey(k)) {
                        result[k] = v
                    }
                }
            }
        } catch (_: Exception) {}
        return result
    }

    /**
     * ⭐ RESOLUCIÓN UNIVERSAL de nombre + tipo buscando en 3 niveles ⭐
     *
     * NIVEL 1: manifest.json en la raíz
     * NIVEL 2: manifest.json en subcarpetas (BP_xxx/, RP_xxx/)
     * NIVEL 3: manifest.json DENTRO de .mcpack/.zip anidados (leer SIN extraer)
     *
     * FALLBACK del nombre: NUNCA "desconocido" / "pack.name" → resuelve desde .lang o nombre de archivo.
     */
    fun resolveAddonInfo(extractedDir: File, originalFile: File): AddonInfo {
        var rawName: String? = null
        var description: String? = null

        // ── NIVEL 1: manifest.json en la raíz ──
        val rootManifest = File(extractedDir, "manifest.json")
        if (rootManifest.exists()) {
            try {
                val json = JSONObject(rootManifest.readText(Charsets.UTF_8))
                json.optJSONObject("header")?.let { h ->
                    rawName = h.optString("name").takeIf { it.isNotBlank() }
                    description = h.optString("description").takeIf { it.isNotBlank() }
                }
                PackForgeLog.d("PackForge_Info", "  Nivel 1 (raíz): rawName=$rawName")
            } catch (e: Exception) {
                PackForgeLog.w("PackForge_Info", "  Nivel 1: manifest inválido: ${e.message}")
            }
        }

        // ── NIVEL 2: manifest.json en subcarpetas (BP_xxx, RP_xxx) ──
        if (rawName.isNullOrBlank()) {
            extractedDir.listFiles()?.filter { it.isDirectory }?.forEach { sub ->
                if (rawName.isNullOrBlank()) {
                    val m = File(sub, "manifest.json")
                    if (m.exists()) {
                        try {
                            val json = JSONObject(m.readText(Charsets.UTF_8))
                            json.optJSONObject("header")?.let { h ->
                                rawName = h.optString("name").takeIf { it.isNotBlank() }
                                description = h.optString("description").takeIf { it.isNotBlank() }
                            }
                            PackForgeLog.d("PackForge_Info", "  Nivel 2 (sub ${sub.name}): rawName=$rawName")
                        } catch (e: Exception) {
                            PackForgeLog.w("PackForge_Info", "  Nivel 2: manifest inválido en ${sub.name}: ${e.message}")
                        }
                    }
                }
            }
        }

        // ── NIVEL 3: manifest.json DENTRO de .mcpack/.zip anidados ──
        if (rawName.isNullOrBlank()) {
            DirIndexCache.invalidate(extractedDir)
            val nestedZips = DirIndexCache.index(extractedDir).allFiles
                .filter {
                    it.extension.equals("mcpack", true) ||
                    it.extension.equals("zip", true)
                }
            
            nestedZips.forEach { nested ->
                if (rawName.isNullOrBlank()) {
                    val manifest = readManifestFromZip(nested)
                    manifest?.optJSONObject("header")?.let { h ->
                        rawName = h.optString("name").takeIf { it.isNotBlank() }
                        description = h.optString("description").takeIf { it.isNotBlank() }
                    }
                    PackForgeLog.d("PackForge_Info", "  Nivel 3 (ZIP ${nested.name}): rawName=$rawName")
                }
            }
        }

        // ── TRADUCCIÓN DESDE .LANG Y SANITIZACIÓN DE CÓDIGOS § ──
        val translations = resolveLangTranslations(extractedDir)

        var resolvedName: String? = null

        // 1. Si rawName es una clave de traducción (p. ej. "pack.name", "%pack.name"), buscar en .lang
        if (!rawName.isNullOrBlank()) {
            val key = rawName.trim().removePrefix("%").lowercase()
            val translated = translations[key]
            if (!translated.isNullOrBlank()) {
                resolvedName = MinecraftTextSanitizer.clean(translated)
            } else if (!MinecraftTextSanitizer.isTranslationKey(rawName)) {
                resolvedName = MinecraftTextSanitizer.clean(rawName)
            }
        }

        // 2. Si sigue sin resolverse, buscar claves estándar en .lang: pack.name, pack.name.rp, pack.name.bp, tile.pack.name
        if (resolvedName.isNullOrBlank() || MinecraftTextSanitizer.isTranslationKey(resolvedName)) {
            val fallbackKeys = listOf("pack.name", "pack.name.rp", "pack.name.bp", "tile.pack.name")
            for (fk in fallbackKeys) {
                val valInLang = translations[fk]
                if (!valInLang.isNullOrBlank() && !MinecraftTextSanitizer.isTranslationKey(valInLang)) {
                    resolvedName = MinecraftTextSanitizer.clean(valInLang)
                    if (resolvedName.isNotBlank()) break
                }
            }
        }

        // 3. Fallback por traducciones en .lang que no sean claves
        if (resolvedName.isNullOrBlank() || MinecraftTextSanitizer.isTranslationKey(resolvedName)) {
            val nonKeyTranslation = translations.values.firstOrNull {
                it.isNotBlank() && !MinecraftTextSanitizer.isTranslationKey(it)
            }
            if (!nonKeyTranslation.isNullOrBlank()) {
                resolvedName = MinecraftTextSanitizer.clean(nonKeyTranslation)
            }
        }

        // 4. Fallback por nombre de subcarpetas en extractedDir (ej: "MyAwesomePack_BP" -> "MyAwesomePack")
        if (resolvedName.isNullOrBlank() || MinecraftTextSanitizer.isTranslationKey(resolvedName)) {
            val subFolders = extractedDir.listFiles()?.filter { it.isDirectory }.orEmpty()
            for (sub in subFolders) {
                val cleanSub = sub.name
                    .replace(Regex("(?i)_?(bp|rp|behavior|resource|packs?)$"), "")
                    .replace('_', ' ')
                    .replace('-', ' ')
                    .trim()
                if (cleanSub.isNotBlank() && !MinecraftTextSanitizer.isTranslationKey(cleanSub)) {
                    resolvedName = MinecraftTextSanitizer.clean(cleanSub)
                    break
                }
            }
        }

        // 5. Fallback por la descripción del manifiesto si no es una clave
        if ((resolvedName.isNullOrBlank() || MinecraftTextSanitizer.isTranslationKey(resolvedName)) && !description.isNullOrBlank()) {
            val descKey = description.trim().removePrefix("%").lowercase()
            val descTranslated = translations[descKey]
            val descClean = if (!descTranslated.isNullOrBlank() && !MinecraftTextSanitizer.isTranslationKey(descTranslated)) {
                MinecraftTextSanitizer.clean(descTranslated)
            } else if (!MinecraftTextSanitizer.isTranslationKey(description)) {
                MinecraftTextSanitizer.clean(description)
            } else ""

            if (descClean.isNotBlank()) {
                resolvedName = descClean
            }
        }

        // 6. Fallback final: nombre del archivo original limpio
        val candidateName = if (resolvedName.isNullOrBlank() || MinecraftTextSanitizer.isTranslationKey(resolvedName)) {
            val fileClean = originalFile.nameWithoutExtension
                .replace(Regex("(?i)\\.(mcaddon|mcpack|zip)$"), "")
                .replace(Regex("(?i)_?(bp|rp|behavior|resource|packs?)$"), "")
                .replace('_', ' ')
                .replace('-', ' ')
                .trim()
            val cleaned = MinecraftTextSanitizer.clean(fileClean)
            if (cleaned.isNotBlank() && !MinecraftTextSanitizer.isTranslationKey(cleaned)) {
                cleaned
            } else {
                originalFile.nameWithoutExtension
            }
        } else {
            resolvedName
        }

        val finalName = if (candidateName.isBlank() || MinecraftTextSanitizer.isTranslationKey(candidateName)) {
            val safeFile = originalFile.nameWithoutExtension
            if (!MinecraftTextSanitizer.isTranslationKey(safeFile)) safeFile else "Addon Sin Nombre"
        } else {
            candidateName
        }

        PackForgeLog.d("PackForge_Info", "📛 Addon: ${originalFile.name} → nombre resuelto: $finalName")

        return AddonInfo(finalName, classify(extractedDir))
    }
}
