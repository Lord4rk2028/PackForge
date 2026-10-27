package com.packforge.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.packforge.app.MainActivity
import com.packforge.app.R
import com.packforge.app.data.PackForgeDatabase
import com.packforge.app.domain.model.Addon
import com.packforge.app.domain.model.SavedModpack
import com.packforge.app.domain.model.withSafeLists
import com.packforge.app.domain.engine.PackForgeOrchestrator
import com.packforge.app.ui.viewmodel.ThemeAccent
import com.packforge.app.util.PackForgeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * ═══════════════════════════════════════════════════════════════════════
 * SESIÓN DE FUSIÓN COMPARTIDA (proceso vivo)
 * ═══════════════════════════════════════════════════════════════════════
 * Estado global observable por ViewModel/UI mientras [MergeForegroundService]
 * ejecuta la fusión aunque la app esté en segundo plano.
 */
object MergeSession {

    data class State(
        val phase: String = "idle",
        val percent: Int = 0,
        val done: Boolean = false,
        val success: Boolean = false,
        val message: String = "",
        val fileName: String? = null,
        val outputPath: String? = null,
        val reportPath: String? = null,
        val regenerateId: String? = null,
        /** "editor" (exportación normal, UI propia) | "library" (re-fusión con overlay). */
        val origin: String = "idle",
        /** Portada auto-seleccionada cuando el usuario no puso una */
        val autoCoverPath: String? = null
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    fun reset(origin: String = "idle") { _state.value = State(origin = origin) }

    fun cancel() {
        _state.value = State(done = true, success = false, message = "Fusión cancelada por el usuario", origin = "idle")
    }

    fun isBusy(): Boolean {
        val s = _state.value
        return !s.done && s.phase != "idle"
    }

    internal fun update(phase: String, percent: Int) {
        _state.value = _state.value.copy(phase = phase, percent = percent)
    }

    internal fun finish(
        success: Boolean, message: String, fileName: String?, outputPath: String?,
        reportPath: String?, regenerateId: String?, autoCoverPath: String? = null
    ) {
        _state.value = _state.value.copy(
            done = true, success = success, message = message,
            fileName = fileName, outputPath = outputPath,
            reportPath = reportPath, regenerateId = regenerateId,
            autoCoverPath = autoCoverPath
        )
    }
}

/**
 * ═══════════════════════════════════════════════════════════════════════
 * SERVICIO EN PRIMER PLANO DE FUSIÓN
 * ═══════════════════════════════════════════════════════════════════════
 * Mantiene viva la fusión con una notificación de progreso permanente
 * (tema de color personalizado del usuario), sobrevive a que la app pase a
 * segundo plano y finaliza copias + historial + reporte sin depender del
 * proceso de la Activity/ViewModel.
 *
 * Acciones soportadas:
 *  - ACTION_EXPORT      : fusión desde el editor (addons activos).
 *  - ACTION_REGENERATE  : re-fusión de un SavedModpack existente con el
 *                         motor actual (anti-obsolescencia de "My Modpacks").
 */
class MergeForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Trabajo en curso; se cancela de forma explícita desde ACTION_CANCEL. */
    private var activeMergeJob: Job? = null
    private var lastUpdateTime = mutableMapOf<Any?, Long>().withDefault { 0L }
    private var lastNotifiedPercent = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val accentColor = ThemeAccent.colorBlocking(this)
        // Crear canal ANTES de cualquier notify/startForeground (evita crash API 26-27)
        ensureChannel()
        // Siempre recrear la notificación cuando se re-inicia el servicio
        startForegroundCompat(buildNotification("Preparando fusión…", 0, true, accentColor))

        when (intent?.action) {
            ACTION_CANCEL -> {
                PackForgeLog.d(TAG, "Solicitud de cancelación recibida")
                MergeSession.cancel()
                lastNotifiedPercent = 0
                activeMergeJob?.cancel(CancellationException("Merge cancelled by user"))
                stopSelf()
            }
            ACTION_REGENERATE -> {
                lastNotifiedPercent = 0
                val id = intent.getStringExtra(EXTRA_REGENERATE_ID)
                activeMergeJob?.cancel()
                activeMergeJob = scope.launch { runRegenerate(id, accentColor) }
            }
            else -> {
                lastNotifiedPercent = 0
                activeMergeJob?.cancel()
                activeMergeJob = scope.launch { runExport(intent, accentColor) }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        activeMergeJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // EXPORTACIÓN DESDE EL EDITOR (mismo proceso de antes pero ahora incluye retorno de progreso)
    // ═══════════════════════════════════════════════════════════════════════════════

    private suspend fun runExport(intent: Intent?, accentColor: Int) {
        var pathsFile: String? = null
        try {
            if (intent == null) { fail("Intent inválido", null, null); return }

            // Leer paths desde archivo de cache (evita TransactionTooLargeException)
            pathsFile = intent.getStringExtra(EXTRA_PATHS_FILE)
            val paths = if (pathsFile != null) {
                File(pathsFile).readLines().filter { it.isNotBlank() }
            } else {
                intent.getStringArrayExtra(EXTRA_PATHS)?.toList().orEmpty()
            }
            val names = intent.getStringArrayExtra(EXTRA_NAMES)?.toList().orEmpty()
            val name = sanitizeName(intent.getStringExtra(EXTRA_NAME))
            val author = intent.getStringExtra(EXTRA_AUTHOR).orEmpty()
            val version = intent.getStringExtra(EXTRA_VERSION).orEmpty()
            val desc = intent.getStringExtra(EXTRA_DESC).orEmpty()
            val iconPath = intent.getStringExtra(EXTRA_ICON)
            val outUri = intent.getStringExtra(EXTRA_OUT_URI)
            val editId = intent.getStringExtra(EXTRA_EDIT_ID)

            // Decisiones explícitas del usuario (ruta relativa → id del ganador)
            // para aplicarlas archivo por archivo al final de la fusión.
            val addonIds = intent.getStringArrayListExtra(EXTRA_ADDON_IDS).orEmpty()
            val fileWinners: Map<String, String> = intent.getStringExtra(EXTRA_FILE_WINNERS)
                ?.takeIf { it.isNotBlank() }
                ?.let { json ->
                    runCatching {
                        val type = object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type
                        Gson().fromJson<Map<String, String>>(json, type)
                    }.getOrNull()
                }
                .orEmpty()

            if (paths.isEmpty()) { fail("No hay addons para fusionar", null, null); return }

            PackForgeLog.d(TAG, "Servicio: iniciando exportación de ${paths.size} addons")

            val result = PackForgeOrchestrator.mergeAddons(
                addonPaths = paths,
                outputDir = cacheDir.absolutePath,
                progressCallback = progressCallback(),
                addonNames = names,
                customName = name,
                customAuthor = author,
                customVersion = version.ifBlank { "1.0.0" },
                customDescription = desc,
                customIconPath = iconPath,
                addonIds = addonIds,
                fileWinners = fileWinners
            )
            iconPath?.let { File(it).delete() }

            if (!result.success || result.outputPath == null) {
                MergeSession.finish(false, result.errorMessage ?: "Fallo en la fusión", null, null, null, null)
                notifyFinal(false, result.errorMessage ?: "Fallo en la fusión", accentColor)
                stopSelf()
                return
            }

            // ── FINALIZACIÓN: copias + reporte (sin historial) ──
            // El historial se persiste en el ViewModel con la fuente de verdad
            // (_addons.value), para evitar TransactionTooLargeException al enviar
            // addons serializados al Intent del Service.
            val outputFile = File(result.outputPath)
            val dest = finalizeArtifacts(outputFile, name, outUri)
            val reportPath = publishReport(result.reportPath, name, dest)
            val internalPath = dest.internalPath
            val visiblePath = dest.visiblePath ?: outputFile.absolutePath

            // Limpieza del archivo temporal de paths
            pathsFile?.let { f -> runCatching { File(f).delete() } }

            PackForgeLog.d(TAG, "Servicio: exportación completa -> $visiblePath")
            MergeSession.finish(
                success = true, message = "¡Modpack fusionado con éxito!",
                fileName = "$name.mcaddon", outputPath = visiblePath,
                reportPath = reportPath, regenerateId = null,
                autoCoverPath = result.autoCoverPath
            )
            notifyFinal(true, "Guardado en ${dest.whereLabel}. ${reportHint(reportPath, dest)}", accentColor)
        } catch (e: CancellationException) {
            PackForgeLog.d(TAG, "Export cancelled")
            throw e
        } catch (e: Exception) {
            PackForgeLog.e(TAG, "Error en servicio de exportación", e)
            fail(e.message ?: "Error desconocido", null, null)
        } finally {
            // Asegurar limpieza del archivo de paths incluso en error
            pathsFile?.let { f -> runCatching { File(f).delete() } }
            stopSelf()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // REGENERACIÓN DESDE EL SERVICIO (mismo proceso de antes)
    // ═══════════════════════════════════════════════════════════════════════════════

    private suspend fun runRegenerate(modpackId: String?, accentColor: Int) {
        try {
            if (modpackId == null) { fail("ID inválido", null, modpackId); return }
            val db = PackForgeDatabase.getInstance(this)
            val row = db.savedModpackDao().getById(modpackId)
            if (row == null) { fail("Modpack no encontrado", null, modpackId); return }

            val addons = parseAddons(row.addonsJson)
            val usable = addons.filter { it.enabled && it.sourceFilePath.isNotBlank() && File(it.sourceFilePath).exists() }
            if (usable.isEmpty()) {
                fail("Las fuentes originales ya no están disponibles en el dispositivo", null, modpackId)
                return
            }

            val name = sanitizeName(row.name)
            MergeSession.update("Re-fusionando '${row.name}' con el motor actual…", 5)
            PackForgeLog.d(TAG, "Regenerando '${row.name}' (${usable.size} addons)")

            val result = PackForgeOrchestrator.mergeAddons(
                addonPaths = usable.map { it.sourceFilePath },
                outputDir = cacheDir.absolutePath,
                progressCallback = progressCallback(),
                addonNames = usable.map { it.name },
                customName = name,
                customAuthor = row.author,
                customVersion = row.version.ifBlank { "1.0.0" },
                customDescription = row.description,
                customIconPath = row.coverUriString?.takeIf { it.startsWith("/") && File(it).exists() }
            )

            if (!result.success || result.outputPath == null) {
                MergeSession.finish(false, result.errorMessage ?: "Fallo al regenerar", null, null, null, modpackId)
                notifyFinal(false, result.errorMessage ?: "Fallo al regenerar", accentColor)
                stopSelf()
                return
            }

            val outputFile = File(result.outputPath)
            val dest = finalizeArtifacts(outputFile, name, outUri = null)
            val reportPath = publishReport(result.reportPath, name, dest)

            // Anti-obsolescencia: misma fila, artifacts reemplazados y fecha actualizada.
            db.savedModpackDao().insert(
                row.copy(filePath = dest.visiblePath ?: outputFile.absolutePath, createdAt = System.currentTimeMillis())
            )

            PackForgeLog.d(TAG, "Servicio: '${row.name}' regenerado con el motor actual")
            MergeSession.finish(
                success = true, message = "'${row.name}' regenerado con el motor actual",
                fileName = row.fileName, outputPath = dest.visiblePath,
                reportPath = reportPath, regenerateId = modpackId
            )
            notifyFinal(true, "Regenerado con el motor actual. ${reportHint(reportPath, dest)}", accentColor)
        } catch (e: CancellationException) {
            PackForgeLog.d(TAG, "Regeneration cancelled")
            throw e
        } catch (e: Exception) {
            PackForgeLog.e(TAG, "Error regenerando modpack", e)
            fail(e.message ?: "Error desconocido", null, modpackId)
        } finally {
            stopSelf()
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  HANDLERS DE NEGOCIO (compartidos entre export y regeneración)
    // ──────────────────────────────────────────────────────────────────────────

    private fun progressCallback() = object : PackForgeOrchestrator.ProgressCallback {
        override suspend fun onProgress(message: String, percent: Int) {
            val now = System.currentTimeMillis()
            val last = lastUpdateTime[message] ?: 0L
            if (now - last < 200L) return
            lastUpdateTime[message] = now

            // Si el orchestrator proporcionó un porcentaje real, usarlo directamente.
            // Si no, caer al mapeo por mensaje (estimado).
            val finalPercent = if (percent >= 0) percent else percentFor(message)
            val p = maxOf(finalPercent, lastNotifiedPercent)
            if (p == lastNotifiedPercent && finalPercent < lastNotifiedPercent) return
            lastNotifiedPercent = p
            MergeSession.update(message, p)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(message, p, true, ThemeAccent.colorBlocking(this@MergeForegroundService)))
        }
    }

    private class ArtifactDest(
        val visiblePath: String?,
        val internalPath: String?,
        val whereLabel: String,
        /** Carpeta SAF elegida por el usuario, si el destino fue una carpeta. */
        val treeUri: android.net.Uri? = null,
        /** Carpeta visible real (Descargas) donde dejamos también el reporte. */
        val publicDir: java.io.File? = null
    )

    /** Copia el .mcaddon a Descargas (o a la carpeta elegida) y garantiza copia interna. */
    private fun finalizeArtifacts(outputFile: File, name: String, outUri: String?): ArtifactDest {
        var visible: String? = null
        var label = "almacenamiento interno"
        var tree: android.net.Uri? = null
        var publicDir: File? = null

        if (outUri == null) {
            try {
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                val target = File(downloadsDir, "$name.mcaddon")
                outputFile.copyTo(target, overwrite = true)
                visible = target.absolutePath
                label = "Descargas"
                publicDir = downloadsDir
            } catch (e: Exception) {
                PackForgeLog.e(TAG, "Error al copiar a Downloads: ${e.message}")
            }
        } else {
            val uri = android.net.Uri.parse(outUri)
            try {
                if (android.provider.DocumentsContract.isTreeUri(uri)) {
                    // El usuario eligió una CARPETA: se crea el .mcaddon dentro.
                    // Antes se abría el árbol como si fuera un archivo, lo que
                    // fallaba y dejaba el ZIP vacío creado al elegir la ruta.
                    val targetUri = createOrReplaceDocument(
                        treeUri = uri,
                        fileName = "$name.mcaddon",
                        mimeType = "application/octet-stream"
                    )
                    if (targetUri != null) {
                        contentResolver.openOutputStream(targetUri, "wt")?.use { out ->
                            outputFile.inputStream().use { it.copyTo(out) }
                        }
                        visible = targetUri.toString()
                        tree = uri
                        label = folderLabelOf(uri)
                    } else {
                        PackForgeLog.e(TAG, "No se pudo crear el .mcaddon en la carpeta elegida")
                    }
                } else {
                    // URI de documento suelto (compatibilidad con versiones previas).
                    contentResolver.openOutputStream(uri)?.use { out ->
                        outputFile.inputStream().use { it.copyTo(out) }
                    }
                    visible = outUri
                    label = "la ubicación elegida"
                }
            } catch (e: Exception) {
                PackForgeLog.e(TAG, "Error al guardar en ubicación seleccionada: ${e.message}")
            }
        }

        var internal: String? = null
        try {
            val exportsDir = File(filesDir, "exports").apply { mkdirs() }
            val destInternal = File(exportsDir, "$name.mcaddon")
            outputFile.copyTo(destInternal, overwrite = true)
            internal = destInternal.absolutePath
        } catch (e: Exception) {
            PackForgeLog.e(TAG, "No se pudo copiar a almacenamiento interno: ${e.message}")
        }
        return ArtifactDest(visible, internal, label, tree, publicDir)
    }

    /** Nombre legible de la carpeta SAF elegida (`primary:Documents/Pack` → `Documents/Pack`). */
    private fun folderLabelOf(treeUri: android.net.Uri): String {
        val docId = runCatching {
            android.provider.DocumentsContract.getTreeDocumentId(treeUri)
        }.getOrNull() ?: return "la carpeta elegida"

        val path = docId.substringAfter(':', "").trim('/')
        if (path.isNotBlank()) return path

        return treeUri.lastPathSegment?.substringAfterLast(':')?.takeIf { it.isNotBlank() }
            ?: "la carpeta elegida"
    }

    /**
     * Crea (o reutiliza) un documento dentro de la carpeta SAF elegida.
     * Reutilizar evita que cada exportación deje `nombre (1).mcaddon`.
     */
    private fun createOrReplaceDocument(
        treeUri: android.net.Uri,
        fileName: String,
        mimeType: String
    ): android.net.Uri? {
        val parentDocId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
        val parentUri = android.provider.DocumentsContract
            .buildDocumentUriUsingTree(treeUri, parentDocId)

        val existing = findChildInFolder(treeUri, parentDocId, fileName)
        if (existing != null) return existing

        return try {
            android.provider.DocumentsContract.createDocument(
                contentResolver, parentUri, mimeType, fileName
            )
        } catch (e: Exception) {
            PackForgeLog.e(TAG, "No se pudo crear '$fileName': ${e.message}")
            null
        }
    }

    /** Busca un hijo por nombre dentro de la carpeta SAF. */
    private fun findChildInFolder(
        treeUri: android.net.Uri,
        parentDocId: String,
        fileName: String
    ): android.net.Uri? {
        return try {
            val childrenUri = android.provider.DocumentsContract
                .buildChildDocumentsUriUsingTree(treeUri, parentDocId)
            contentResolver.query(
                childrenUri,
                arrayOf(
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val display = cursor.getString(1)
                    if (display.equals(fileName, ignoreCase = true)) {
                        return android.provider.DocumentsContract
                            .buildDocumentUriUsingTree(treeUri, cursor.getString(0))
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Publica el fusion_report.txt junto al .mcaddon y SIEMPRE en filesDir/reports/.
     */
    private fun publishReport(
        sourceReportPath: String?,
        baseName: String,
        dest: ArtifactDest
    ): String? {
        if (sourceReportPath == null) return null
        val src = File(sourceReportPath)
        if (!src.exists()) return null

        var published: String? = null
        try {
            val reportsDir = File(filesDir, "reports").apply { mkdirs() }
            val internalCopy = File(reportsDir, "${baseName}_fusion_report.txt")
            src.copyTo(internalCopy, overwrite = true)
            published = internalCopy.absolutePath
        } catch (_: Exception) {}

        // Junto al modpack, para que el usuario lo tenga donde lo buscó.
        val reportName = "${baseName}_fusion_report.txt"
        if (dest.treeUri != null) {
            try {
                val targetUri = createOrReplaceDocument(
                    treeUri = dest.treeUri,
                    fileName = reportName,
                    mimeType = "text/plain"
                )
                if (targetUri != null) {
                    contentResolver.openOutputStream(targetUri, "wt")?.use { out ->
                        src.inputStream().use { it.copyTo(out) }
                    }
                    PackForgeLog.d(TAG, "Reporte publicado en la carpeta elegida")
                }
            } catch (e: Exception) {
                PackForgeLog.e(TAG, "No se pudo publicar el reporte en la carpeta: ${e.message}")
            }
        } else if (dest.publicDir != null) {
            try {
                src.copyTo(File(dest.publicDir, reportName), overwrite = true)
                PackForgeLog.d(TAG, "Reporte publicado en Descargas")
            } catch (e: Exception) {
                PackForgeLog.e(TAG, "No se pudo publicar reporte en Descargas: ${e.message}")
            }
        }

        return published
    }

    private suspend fun saveRow(
        id: String?, name: String, author: String, version: String, description: String,
        cover: String?, tagsJson: String, addonsJson: String, addonCount: Int,
        fileName: String, filePath: String
    ) {
        try {
            val db = PackForgeDatabase.getInstance(this)
            val modpackId = id ?: java.util.UUID.randomUUID().toString()
            val persistedCover = persistCover(cover, modpackId)
            // Extraer nombres reales de addons desde addonsJson
            val addonNamesJson = try {
                val addons = Gson().fromJson(addonsJson, Array<Addon>::class.java).toList().withSafeLists()
                Gson().toJson(addons.map { it.name })
            } catch (_: Exception) {
                Gson().toJson(List<String>(addonCount) { "" }) // fallback
            }
            db.savedModpackDao().insert(
                SavedModpack(
                    id = modpackId, name = name, author = author, version = version,
                    mcVersion = "", description = description,
                    addonNames = addonNamesJson,
                    addonCount = addonCount, filePath = filePath, fileName = fileName,
                    createdAt = System.currentTimeMillis(), coverUriString = persistedCover,
                    tags = tagsJson, addonsJson = addonsJson
                )
            )
        } catch (e: Exception) {
            PackForgeLog.e(TAG, "Error guardando historial: ${e.message}")
        }
    }

    private fun persistCover(cover: String?, modpackId: String): String? {
        if (cover.isNullOrBlank()) return null
        return try {
            val iconDir = File(filesDir, "modpack_icons").apply { mkdirs() }
            val iconFile = File(iconDir, "${modpackId}_cover.png")
            if (cover == iconFile.absolutePath) {
                return if (iconFile.exists() && iconFile.length() > 0) iconFile.absolutePath else null
            }
            val input = when {
                cover.startsWith("/") -> File(cover).takeIf { it.exists() }?.inputStream()
                cover.startsWith("file://") -> try { File(java.net.URI(cover)).inputStream() } catch (_: Exception) { null }
                else -> runCatching { contentResolver.openInputStream(android.net.Uri.parse(cover)) }.getOrNull()
            }
            input?.use { ins -> iconFile.outputStream().use { outs -> ins.copyTo(outs) } }
            if (iconFile.exists() && iconFile.length() > 0) iconFile.absolutePath else null
        } catch (_: Exception) { null }
    }

    private fun parseAddons(json: String): List<Addon> = try {
        Gson().fromJson(json, Array<Addon>::class.java).toList().withSafeLists()
    } catch (_: Exception) { emptyList() }

    private fun sanitizeName(raw: String?): String =
        raw?.trim()?.replace(Regex("[/\\\\:*?\"<>|]"), "_")?.ifBlank { "PackForge_Modpack" } ?: "PackForge_Modpack"

    private fun percentFor(message: String): Int = when {
        message.contains("Extrayendo") -> 4
        message.contains("Clasificando") -> 8
        message.contains("Actualizando referencias") -> 12
        message.contains("Copiando archivos...") -> 15
        message.contains("Copiando addon") -> 15
        message.contains("Copiando archivos (Fase 1") -> 15
        message.contains("Reparando dependencias") -> 74
        message.contains("críticos") || message.contains("criticos") -> 76
        message.contains("dependencias de entidades") -> 78
        message.contains("manifiestos") -> 80
        message.contains("Analizando scripts") -> 82
        message.contains("Descifrando") -> 84
        message.contains("Reparando texturas") -> 87
        message.contains("Validando") -> 89
        message.contains("integridad") -> 91
        message.contains("Empaquetando") -> 93
        message.contains("reporte") -> 96
        message.contains("Limpiando") -> 98
        else -> 5
    }

    private suspend fun fail(message: String, fileName: String?, regenerateId: String?) {
        PackForgeLog.e(TAG, "Servicio: $message")
        MergeSession.finish(false, message, fileName, null, null, regenerateId)
        notifyFinal(false, message, ThemeAccent.colorBlocking(this))
    }

    /** Dónde quedó el reporte, según el destino real de la fusión. */
    private fun reportHint(reportPath: String?, dest: ArtifactDest? = null): String = when {
        reportPath == null -> ""
        dest?.treeUri != null -> "Reporte en la misma carpeta."
        dest?.publicDir != null -> "Reporte en Descargas."
        else -> "Reporte en files/reports."
    }

    // ──────────────────────────────────────────────────────────────────────────
    // NOTIFICACIONES (tema personalizado del usuario)
    // ──────────────────────────────────────────────────────────────────────────

    private fun ensureChannel(): NotificationManager {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Fusión de Modpacks", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progreso de fusión de addons"
                    setShowBadge(false)
                }
            )
        }
        return nm
    }

    private fun buildNotification(text: String, percent: Int, ongoing: Boolean, accentColor: Int): Notification {
        ensureChannel()
        // Muestra el porcentaje numérico exacto delante del texto de fase, para que
        // el usuario vea el avance real y no solo una barra sin escala.
        val percentLabel = if (percent in 0..100) "$percent% · " else ""
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_merge)
            .setContentTitle("PackForge · Fusionando modpack")
            .setContentText("$percentLabel$text")
            .setProgress(100, percent, false)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setColor(accentColor)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun notifyFinal(success: Boolean, message: String, accentColor: Int) {
        ensureChannel()
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_merge)
            .setContentTitle(if (success) "PackForge - Fusión completada" else "PackForge - Fallo en la fusión")
            .setContentText(message.take(180))
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setOngoing(false)
            .setAutoCancel(true)
            .setColor(accentColor)
            .setContentIntent(openApp)
            .build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID + 1, n)
    }

    @SuppressWarnings("MissingPermission")
    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "PackForge_Service"
        const val CHANNEL_ID = "packforge_merge"
        const val NOTIFICATION_ID = 4201

        const val ACTION_EXPORT = "com.packforge.app.action.EXPORT_MERGE"
        const val ACTION_REGENERATE = "com.packforge.app.action.REGENERATE_MERGE"
        const val ACTION_CANCEL = "com.packforge.app.action.CANCEL_MERGE"

        const val EXTRA_PATHS = "paths"
        const val EXTRA_NAMES = "names"
        const val EXTRA_NAME = "name"
        const val EXTRA_AUTHOR = "author"
        const val EXTRA_VERSION = "version"
        const val EXTRA_DESC = "desc"
        const val EXTRA_ICON = "iconPath"
        const val EXTRA_COVER = "coverUri"
        const val EXTRA_TAGS = "tags"
        const val EXTRA_OUT_URI = "outUri"
        const val EXTRA_EDIT_ID = "editId"

        /** Ids de los addons, en el mismo orden que los paths. */
        const val EXTRA_ADDON_IDS = "addonIds"

        /** JSON `ruta relativa → id del ganador` de las decisiones del usuario. */
        const val EXTRA_FILE_WINNERS = "fileWinners"
        const val EXTRA_REGENERATE_ID = "regenerateId"
        // El ID del archivo de cache donde se serializó la lista de paths de addons
        // (un path por línea). Esto evita el TransactionTooLargeException al pasar
        // un String[] largo a través del Intent del Service.
        const val EXTRA_PATHS_FILE = "pathsFile"

        fun startExport(context: Context, intentConfig: (Intent) -> Intent) {
            val intent = intentConfig(Intent(context, MergeForegroundService::class.java).setAction(ACTION_EXPORT))
            ContextCompat.startForegroundService(context, intent)
        }

        fun startRegenerate(context: Context, modpackId: String) {
            val intent = Intent(context, MergeForegroundService::class.java)
                .setAction(ACTION_REGENERATE)
                .putExtra(EXTRA_REGENERATE_ID, modpackId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MergeForegroundService::class.java).setAction(ACTION_CANCEL)
            context.startService(intent)
        }
    }
}
