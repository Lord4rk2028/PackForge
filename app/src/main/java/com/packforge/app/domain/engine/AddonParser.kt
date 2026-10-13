package com.packforge.app.domain.engine

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.packforge.app.domain.model.Addon
import com.packforge.app.domain.model.AddonType
import com.packforge.app.util.AddonStorageManager
import com.packforge.app.util.FileUtils
import com.packforge.app.util.PackForgeLog
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object AddonParser {

    /**
     * Nombres base aceptados como icono de un addon.
     *
     * `pack_icon` es el estándar de Minecraft, pero no todos los addons del
     * catálogo lo respetan: algunos usan `icon.png`. Al no contemplarlos, esos
     * addons se quedaban sin portada en el editor y en el visor.
     */
    private val ICON_BASE_NAMES = setOf("pack_icon", "icon")

    /** Extensiones de imagen aceptadas para el icono. */
    private val ICON_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")

    fun parseFromUri(context: Context, uri: Uri): Addon? {
        return try {
            // 1. Obtener nombre y tamaño real del archivo
            var fileName = "addon_${System.currentTimeMillis()}.mcaddon"
            var fileSize = 0L
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) fileName = cursor.getString(nameIndex) ?: fileName
                    if (sizeIndex >= 0) fileSize = cursor.getLong(sizeIndex)
                }
            }

            // Verificar extensiones permitidas
            if (!fileName.endsWith(".mcaddon", ignoreCase = true) &&
                !fileName.endsWith(".mcpack", ignoreCase = true) &&
                !fileName.endsWith(".zip", ignoreCase = true)) {
                return null
            }

            val addonId = UUID.randomUUID().toString()

            // 2. GUARDAR COPIA PERMANENTE EN ALMACENAMIENTO INTERNO (Solución al error 22B)
            //
            // Se copia a un archivo temporal calculando su SHA-256 y, si ya hay un addon
            // idéntico guardado, se REUTILIZA ese archivo en lugar de dejar otra copia.
            // Antes cada importación creaba una copia con un UUID nuevo y nada las borraba:
            // eso acumuló 270 archivos y 2.2 GB para 8 modpacks (hasta 23 copias del mismo
            // addon de 32 MB).
            val addonsDir = File(context.filesDir, "addons").apply { mkdirs() }
            val incoming = File(addonsDir, "${AddonStorageManager.INCOMING_PREFIX}${addonId}_$fileName")

            val input = context.contentResolver.openInputStream(uri)
            if (input == null) {
                incoming.delete()
                return null
            }

            var copiedBytes = 0L
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                input.use { source ->
                    FileOutputStream(incoming).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = source.read(buffer)
                            if (read <= 0) break
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                            copiedBytes += read
                        }
                    }
                }
            } catch (_: Throwable) {
                incoming.delete()
                return null
            }

            if (copiedBytes <= 0L) {
                incoming.delete()
                return null
            }

            val contentHash = digest.digest().joinToString("") { "%02x".format(it) }
            val alreadyStored = AddonStorageManager.findIdenticalAddon(
                addonsDir, fileName, copiedBytes, contentHash
            )
            val internalFile: File
            if (alreadyStored != null) {
                incoming.delete()
                internalFile = alreadyStored
                PackForgeLog.d("AddonParser", "Addon idéntico ya guardado, se reutiliza: ${alreadyStored.name}")
            } else {
                val target = File(addonsDir, "${addonId}_$fileName")
                internalFile = if (incoming.renameTo(target)) {
                    target
                } else {
                    // renameTo puede fallar según el sistema de archivos; copiar es el respaldo.
                    incoming.copyTo(target, overwrite = true)
                    incoming.delete()
                    target
                }
            }

            // 3. Analizar el archivo ya guardado internamente mediante extracción temporal robusta
            val parseTempDir = File(context.cacheDir, "parse_temp_${addonId}")
            parseTempDir.mkdirs()
            
            val extractedPath = AddonExtractor.extractAddon(internalFile.absolutePath, parseTempDir.absolutePath)
            if (extractedPath == null) {
                parseTempDir.deleteRecursively()
                return null
            }

            val info = AddonExtractor.resolveAddonInfo(parseTempDir, internalFile)
            val displayName = info.displayName
            val addonClassification = info.classification

            // CRÍTICO: invalidate DirIndexCache después de resolveAddonInfo.
            // resolveAddonInfo → classify() puede haber extraído .mcpacks anidados
            // (nested_BP, nested_RP) a parseTempDir. El cache aún no los conoce
            // y sin esto, pack_icon.png dentro de .mcpacks anidados nunca se encuentra.
            DirIndexCache.invalidate(parseTempDir)

            val addonType = when (addonClassification) {
                is AddonExtractor.AddonClassification.BEHAVIOR_PACK -> AddonType.BEHAVIOR_ONLY
                is AddonExtractor.AddonClassification.RESOURCE_PACK -> AddonType.RESOURCE_ONLY
                is AddonExtractor.AddonClassification.BOTH,
                is AddonExtractor.AddonClassification.MULTI -> AddonType.BEHAVIOR_AND_RESOURCE
                else -> AddonType.UNKNOWN
            }

            val allFiles = mutableListOf<String>()
            val behaviorFiles = mutableListOf<String>()
            val resourceFiles = mutableListOf<String>()
            val entityIdentifiers = mutableListOf<String>()
            val itemIdentifiers = mutableListOf<String>()
            val recipeIdentifiers = mutableListOf<String>()
            var hasScripts = false
            var version = "1.0.0"
            var manifestUuid = UUID.randomUUID().toString()
            var minEngineVersion = listOf(1, 20, 0)
            var rawManifest = ""
            var iconPath: String? = null
            var author: String? = null

            // ⭐ OPTIMIZACIÓN: Usar DirIndexCache en lugar de walkTopDown()
            // Se invalida antes de indexar: `resolveAddonInfo` → `classify()` pudo
            // haber extraído .mcpacks anidados (nested_BP, nested_RP) a
            // parseTempDir DESPUÉS de la primera indexación, y el índice cacheado
            // no los conocería. Sin esto, el pack_icon.png de un addon cuyos
            // packs vienen anidados (caso de "Nature's Touch" y "More Tools")
            // nunca se encontraba y la tarjeta quedaba con el icono genérico.
            DirIndexCache.invalidate(parseTempDir)
            val cachedFiles = DirIndexCache.index(parseTempDir).allFiles

            // CRÍTICO: Detectar si es un modpack de PackForge ANTES del loop
            // para priorizar el pack_icon.png de la raíz del modpack (no el de addons anidados).
            val isPackForgeModpack = cachedFiles.any { it.name == "PackForge.ID" }

            for (file in cachedFiles) {
                val entryName = file.relativeTo(parseTempDir).path.replace("\\", "/")
                allFiles.add(entryName)

                // Extraer icono → filesDir en lugar de cacheDir para que no se
                // pierda si el sistema limpia la caché.
                //
                // Se acepta tanto `pack_icon.png` como las variantes que usan
                // varios addons del catálogo: `pack_icon.jpg/.jpeg` y nombres
                // alternativos como `icon.png`. Antes solo se buscaba el nombre
                // exacto `pack_icon.png`, así que cualquier addon con el icono
                // en otro formato se quedaba sin portada y mostraba el icono
                // genérico de la pieza sobre el color del tema.
                val lowerEntry = entryName.lowercase()
                val iconFileName = lowerEntry.substringAfterLast('/')
                val isIconCandidate = iconPath == null &&
                    iconFileName.substringBeforeLast('.') in ICON_BASE_NAMES &&
                    iconFileName.substringAfterLast('.', "") in ICON_EXTENSIONS

                if (isIconCandidate) {
                    // En modpacks de PackForge solo vale el icono de la RAÍZ del
                    // modpack, no el de los addons individuales anidados.
                    val accept = if (isPackForgeModpack) {
                        entryName == "BP_PackForge/pack_icon.png" ||
                            entryName == "RP_PackForge/pack_icon.png"
                    } else {
                        true
                    }
                    if (accept) {
                        try {
                            val iconDir = File(context.filesDir, "addon_icons").apply { mkdirs() }
                            // La extensión se conserva: copiar un .jpg con nombre
                            // .png confundiría a Coil en la decodificación.
                            val ext = iconFileName.substringAfterLast('.', "png")
                            val iconFile = File(iconDir, "${addonId}_pack_icon.$ext")
                            FileUtils.fastCopy(file, iconFile)
                            iconPath = iconFile.absolutePath
                        } catch (e: Exception) {}
                    }
                }

                // Clasificación para el modelo visual
                val lower = entryName.lowercase()
                when {
                    lower.contains("behavior") || lower.contains("bp/") || lower.startsWith("entities/") || lower.startsWith("items/") -> behaviorFiles.add(entryName)
                    lower.contains("resource") || lower.contains("rp/") || lower.startsWith("textures/") || lower.startsWith("models/") -> resourceFiles.add(entryName)
                }

                if (!hasScripts && SCRIPT_EXTENSIONS.any { lower.endsWith(".$it") } && (lower.startsWith("scripts/") || lower.contains("/scripts/"))) {
                    hasScripts = true
                }
                // Heurística UI (requiere server-ui en manifest); solo lee .js si aún no se marcó
                if (!hasScripts && lower.endsWith(".js") && (lower.startsWith("scripts/") || lower.contains("/scripts/"))) {
                    try {
                        val text = file.readText(Charsets.UTF_8)
                        if (text.contains("@minecraft/server-ui") || text.contains("server-ui") ||
                            text.contains("ActionFormData") || text.contains("ModalFormData") ||
                            text.contains("MessageFormData") || text.contains("world.afterEvents") ||
                            text.contains("system.run") || text.contains("minecraft-ui")
                        ) {
                            hasScripts = true
                        }
                    } catch (_: Exception) {}
                }

                // Leer manifest y identifiers
                if (lower.endsWith("manifest.json")) {
                    try {
                        val jsonText = file.readText(Charsets.UTF_8)
                        val json = JSONObject(jsonText)
                        rawManifest = jsonText
                        json.optJSONObject("header")?.let { h ->
                            manifestUuid = h.optString("uuid", manifestUuid)
                            h.optJSONArray("version")?.let { v -> version = "${v.optInt(0)}.${v.optInt(1)}.${v.optInt(2)}" }
                            h.optJSONArray("min_engine_version")?.let { v -> minEngineVersion = listOf(v.optInt(0, 1), v.optInt(1, 20), v.optInt(2, 0)) }
                        }
                        // Extraer autor del manifest: puede estar como array "authors" o string "author"
                        author = json.optJSONArray("authors")?.let { arr ->
                            (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { s -> s.isNotBlank() } }
                        }?.joinToString(", ") ?: json.optString("author").takeIf { it.isNotBlank() }
                    } catch (e: Exception) {}
                }

                if (lower.endsWith(".json")) {
                    try {
                        val json = JSONObject(file.readText(Charsets.UTF_8))
                        if (lower.contains("entities/")) extractEntityIdentifier(json)?.let { entityIdentifiers.add(it) }
                        if (lower.contains("items/")) extractItemIdentifier(json)?.let { itemIdentifiers.add(it) }
                        if (lower.contains("recipes/")) extractRecipeIdentifier(json)?.let { recipeIdentifiers.add(it) }
                    } catch (e: Exception) {}
                }
            }

            // Limpiar carpeta temporal de análisis
            parseTempDir.deleteRecursively()

            Addon(
                id = addonId,
                name = displayName,
                fileName = fileName,
                type = addonType,
                version = version,
                sizeBytes = fileSize,
                files = allFiles,
                behaviorFiles = behaviorFiles,
                resourceFiles = resourceFiles,
                entityIdentifiers = entityIdentifiers,
                itemIdentifiers = itemIdentifiers,
                recipeIdentifiers = recipeIdentifiers,
                hasScripts = hasScripts,
                minEngineVersion = minEngineVersion,
                manifestUuid = manifestUuid,
                rawManifest = rawManifest,
                iconPath = iconPath,
                sourceFilePath = internalFile.absolutePath,
                author = author
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun extractEntityIdentifier(json: JSONObject): String? = 
        json.optJSONObject("minecraft:entity")?.optJSONObject("description")?.optString("identifier")?.takeIf { it.isNotBlank() }

    private fun extractItemIdentifier(json: JSONObject): String? = 
        json.optJSONObject("minecraft:item")?.optJSONObject("description")?.optString("identifier")?.takeIf { it.isNotBlank() }

    private fun extractRecipeIdentifier(json: JSONObject): String? {
        val types = listOf("minecraft:recipe_shaped", "minecraft:recipe_shapeless", "minecraft:recipe_furnace")
        for (t in types) {
            val id = json.optJSONObject(t)?.optJSONObject("description")?.optString("identifier")
            if (id != null) return id
        }
        return null
    }

    /**
     * Recupera el icono (pack_icon.png) de un addon ya guardado en almacenamiento
     * interno a partir de su archivo fuente. Se usa al cargar un modpack desde
     * "My Modpacks": el icono cacheado puede haber desaparecido, pero el archivo
     * fuente persiste, así que lo extraemos de nuevo para que la portada/original
     * siempre se muestre (sin recurrir a un color sólido de respaldo).
     *
     * @return ruta absoluta del icono extraído, o null si no se pudo recuperar.
     */
    fun recoverIconFromSource(sourceFilePath: String, addonId: String, context: Context): String? {
        val src = File(sourceFilePath)
        if (!src.exists()) return null

        // ⚡ VÍA RÁPIDA: leer SOLO la entrada pack_icon.png del ZIP.
        // Antes se llamaba a AddonExtractor.extractAddon(), que descomprime el
        // addon COMPLETO en un directorio temporal para luego buscar un único
        // PNG. Con addons de decenas de MB eso son cientos de ms de disco por
        // addon, y era la causa de que "Editar" tardara varios segundos.
        // Recorrer el índice del ZIP y descomprimir esa sola entrada cuesta
        // milisegundos y no escribe nada en disco salvo el icono final.
        readIconStraightFromZip(src, addonId, context)?.let { return it }

        // VÍA LENTA (respaldo): addons cuyo icono no está en la raíz ni con el
        // nombre exacto, o ZIPs con estructura anidada que exigen extraer.
        val tempDir = File(context.cacheDir, "icon_recover_$addonId")
        tempDir.mkdirs()
        val extracted = AddonExtractor.extractAddon(src.absolutePath, tempDir.absolutePath) ?: run {
            tempDir.deleteRecursively()
            return null
        }
        var iconPath: String? = null
        // ⭐ OPTIMIZACIÓN: Usar DirIndexCache en lugar de walkTopDown()
        val cachedFiles = DirIndexCache.index(File(extracted)).allFiles
        for (f in cachedFiles) {
            if (f.name.equals("pack_icon.png", ignoreCase = true) && iconPath == null) {
                val iconDir = File(context.filesDir, "addon_icons").apply { mkdirs() }
                val dest = File(iconDir, "${addonId}_pack_icon.png")
                try {
                    FileUtils.fastCopy(f, dest)
                    iconPath = dest.absolutePath
                } catch (_: Exception) { /* ignorar */ }
            }
        }
        tempDir.deleteRecursively()
        return iconPath
    }

    /**
     * Extrae `pack_icon.png` recorriendo el índice del ZIP, sin descomprimir
     * ninguna otra entrada.
     *
     * Se acepta la primera coincidencia por nombre, que es la que MC usa como
     * icono del paquete. Devuelve `null` para que el llamador use la vía lenta
     * si el ZIP no tiene el icono con ese nombre exacto.
     */
    private fun readIconStraightFromZip(src: File, addonId: String, context: Context): String? {
        return try {
            java.util.zip.ZipFile(src).use { zip ->
                // Se recorren las entradas buscando el primer nombre aceptado.
                // `pack_icon.*` tiene prioridad sobre `icon.*` porque es el
                // estándar: si un addon trae ambos, el correcto es el primero.
                val entries = zip.entries().asSequence()
                    .filter { !it.isDirectory }
                    .mapNotNull { entry ->
                        val fileName = entry.name.substringAfterLast('/').lowercase()
                        val base = fileName.substringBeforeLast('.', "")
                        val ext = fileName.substringAfterLast('.', "")
                        if (base in ICON_BASE_NAMES && ext in ICON_EXTENSIONS) {
                            Triple(entry, base, ext)
                        } else {
                            null
                        }
                    }
                    .sortedBy { if (it.second == "pack_icon") 0 else 1 }
                    .toList()

                val (entry, _, ext) = entries.firstOrNull() ?: return null

                val iconDir = File(context.filesDir, "addon_icons").apply { mkdirs() }
                val dest = File(iconDir, "${addonId}_pack_icon.$ext")
                // Copia por streaming de una sola entrada: no pasa por memoria
                // ni crea directorios temporales.
                zip.getInputStream(entry).use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                dest.absolutePath
            }
        } catch (_: Throwable) {
            // ZIP corrupto, protegido con contraseña o entrada ilegible:
            // se deja que el respaldo lento lo intente.
            null
        }
    }

    fun detectAndFixAddonFormat(actualFile: File): String {
        val zipEntries = mutableListOf<String>()
        actualFile.inputStream().use { input ->
            ZipInputStream(input).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    zipEntries.add(entry.name)
                    entry = zis.nextEntry
                }
            }
        }
        val hasRootManifest = zipEntries.any { it == "manifest.json" }
        val hasSubfolderManifests = zipEntries.any { it.startsWith("BP_") || it.startsWith("RP_") }
        val jsonCount = zipEntries.count { it.endsWith(".json") && it.contains("manifest") }
        val detectedFormat = when {
            hasRootManifest && !hasSubfolderManifests -> "mcpack"
            hasSubfolderManifests -> "mcaddon"
            jsonCount >= 2 -> "mcaddon"
            else -> "mcpack"
        }
        if (detectedFormat == "mcpack" && actualFile.name.endsWith(".mcaddon", ignoreCase = true) ||
            detectedFormat == "mcaddon" && actualFile.name.endsWith(".mcpack", ignoreCase = true)) {
            PackForgeLog.w("PackForge_Parser", "Formato detectado: $detectedFormat pero filename: ${actualFile.name}")
        }
        return detectedFormat
    }
}
