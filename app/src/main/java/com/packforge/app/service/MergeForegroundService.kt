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
import com.packforge.app.domain.engine.PackForgeOrchestrator
import com.packforge.app.ui.viewmodel.ThemeAccent
import com.packforge.app.util.PackForgeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
        val origin: String = "idle"
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

    internal fun finish(success: Boolean, message: String, fileName: String?, outputPath: String?, reportPath: String?, regenerateId: String?) {
        _state.value = _state.value.copy(
            done = true, success = success, message = message,
            fileName = fileName, outputPath = outputPath,
            reportPath = reportPath, regenerateId = regenerateId
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
    private var lastUpdateTime = mutableMapOf<Any?, Long>().withDefault { 0L }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val accentColor = ThemeAccent.colorBlocking(this)
        // Crear canal ANTES de cualquier notify/startForeground (evita crash API 26-27)
        ensureChannel()
        // Siempre recrear la notificación cuando se re-inicia el servicio
        startForegroundCompat(buildNotification("Preparando fusión…", 0, true, accentColor))

        when (intent?.action) {
            ACTION_CANCEL -> {
                PackForgeLog.d(TAG, "🛑 Solicitud de cancelación recibida")
                MergeSession.cancel()
                stopSelf()
            }
            ACTION_REGENERATE -> {
                val id = intent.getStringExtra(EXTRA_REGENERATE_ID)
                scope.launch { runRegenerate(id, accentColor) }
            }
            else -> scope.launch { runExport(intent, accentColor) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
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

            if (paths.isEmpty()) { fail("No hay addons para fusionar", null, null); return }

            PackForgeLog.d(TAG, "🚀 Servicio: iniciando exportación de ${paths.size} addons")

            val result = PackForgeOrchestrator.mergeAddons(
                addonPaths = paths,
                outputDir = cacheDir.absolutePath,
                progressCallback = progressCallback(),
                addonNames = names,
                customName = name,
                customAuthor = author,
                customVersion = version.ifBlank { "1.0.0" },
                customDescription = desc,
                customIconPath = iconPath
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
            val reportPath = publishReport(result.reportPath, name, outUri == null)
            val internalPath = dest.internalPath
            val visiblePath = dest.visiblePath ?: outputFile.absolutePath

            // Limpieza del archivo temporal de paths
            pathsFile?.let { f -> runCatching { File(f).delete() } }

            PackForgeLog.d(TAG, "✅ Servicio: exportación completa → $visiblePath")
            MergeSession.finish(
                success = true, message = "¡Modpack fusionado con éxito!",
                fileName = "$name.mcaddon", outputPath = visiblePath,
                reportPath = reportPath, regenerateId = null
            )
            notifyFinal(true, "Guardado en ${dest.whereLabel}. Reporte: ${reportHint(reportPath)}", accentColor)
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
            PackForgeLog.d(TAG, "♻️ Servicio: regenerando '${row.name}' (${usable.size} addons)")

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
            val reportPath = publishReport(result.reportPath, name, true)

            // Anti-obsolescencia: misma fila, artifacts reemplazados y fecha actualizada.
            db.savedModpackDao().insert(
                row.copy(filePath = dest.visiblePath ?: outputFile.absolutePath, createdAt = System.currentTimeMillis())
            )

            PackForgeLog.d(TAG, "✅ Servicio: '${row.name}' regenerado con el motor actual")
            MergeSession.finish(
                success = true, message = "'${row.name}' regenerado con el motor actual",
                fileName = row.fileName, outputPath = dest.visiblePath,
                reportPath = reportPath, regenerateId = modpackId
            )
            notifyFinal(true, "Regenerado con el motor actual. ${reportHint(reportPath)}", accentColor)
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
        override suspend fun onProgress(message: String) {
            val now = System.currentTimeMillis()
            val last = lastUpdateTime[message] ?: 0L
            if (now - last < 200L) return
            lastUpdateTime[message] = now

            val percent = percentFor(message)
            MergeSession.update(message, percent)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(message, percent, true, ThemeAccent.colorBlocking(this@MergeForegroundService)))
        }
    }

    private class ArtifactDest(val visiblePath: String?, val internalPath: String?, val whereLabel: String)

    /** Copia el .mcaddon a Descargas (o SAF) y garantiza copia interna permanente. */
    private fun finalizeArtifacts(outputFile: File, name: String, outUri: String?): ArtifactDest {
        var visible: String? = null
        var label = "almacenamiento interno"

        if (outUri == null) {
            try {
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                val target = File(downloadsDir, "$name.mcaddon")
                outputFile.copyTo(target, overwrite = true)
                visible = target.absolutePath
                label = "Descargas"
            } catch (e: Exception) {
                PackForgeLog.e(TAG, "Error al copiar a Downloads: ${e.message}")
            }
        } else {
            try {
                contentResolver.openOutputStream(android.net.Uri.parse(outUri))?.use { out ->
                    outputFile.inputStream().copyTo(out)
                }
                visible = outUri
                label = "ubicación seleccionada"
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
        return ArtifactDest(visible, internal, label)
    }

    /**
     * Publica el fusion_report.txt junto al .mcaddon cuando el destino es
     * Descargas (visible para el usuario) y SIEMPRE en filesDir/reports/.
     */
    private fun publishReport(sourceReportPath: String?, baseName: String, alsoDownloads: Boolean): String? {
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

        if (alsoDownloads) {
            try {
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                src.copyTo(File(downloadsDir, "${baseName}_fusion_report.txt"), overwrite = true)
                PackForgeLog.d(TAG, "📄 Reporte publicado en Descargas")
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
                val addons = Gson().fromJson(addonsJson, Array<Addon>::class.java)
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
        Gson().fromJson(json, Array<Addon>::class.java).toList()
    } catch (_: Exception) { emptyList() }

    private fun sanitizeName(raw: String?): String =
        raw?.trim()?.replace(Regex("[/\\\\:*?\"<>|]"), "_")?.ifBlank { "PackForge_Modpack" } ?: "PackForge_Modpack"

    private fun percentFor(message: String): Int = when {
        message.contains("Extrayendo") -> 10
        message.contains("Clasificando") -> 30
        message.contains("Behavior") -> 45
        message.contains("Resource") -> 65
        message.contains("críticos") -> 72
        message.contains("dependencias de entidades") -> 76
        message.contains("manifiestos") -> 80
        message.contains("Validando") -> 85
        message.contains("Empaquetando") -> 90
        message.contains("reporte") -> 96
        message.contains("Limpiando") -> 98
        else -> 5
    }

    private suspend fun fail(message: String, fileName: String?, regenerateId: String?) {
        PackForgeLog.e(TAG, "❌ Servicio: $message")
        MergeSession.finish(false, message, fileName, null, null, regenerateId)
        notifyFinal(false, message, ThemeAccent.colorBlocking(this))
    }

    private fun reportHint(reportPath: String?): String = when {
        reportPath != null -> "Reporte en files/reports."
        else -> ""
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_merge)
            .setContentTitle("PackForge · Fusionando modpack")
            .setContentText("$text · $percent%")
            .setProgress(100, percent.coerceIn(0, 100), false)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setColor(accentColor)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
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
            .setContentTitle(if (success) "PackForge · Fusión completada ✅" else "PackForge · Fallo en la fusión ❌")
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