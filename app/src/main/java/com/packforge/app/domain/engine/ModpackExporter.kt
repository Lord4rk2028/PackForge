package com.packforge.app.domain.engine

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

object ModpackExporter {

    private const val MINECRAFT_PACKAGE = "com.mojang.minecraftpe"

    fun isMinecraftInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(MINECRAFT_PACKAGE, 0)
            true
        } catch (e: Exception) { false }
    }

    fun getMinecraftVersion(context: Context): String? {
        return try {
            context.packageManager.getPackageInfo(MINECRAFT_PACKAGE, 0).versionName
        } catch (e: Exception) { null }
    }

    /**
     * Abre el .mcaddon en Minecraft Bedrock (o selector si no está disponible).
     * Único método vivo del exporter legacy — el flujo real usa PackForgeOrchestrator + MergeForegroundService.
     */
    fun openInMinecraft(context: Context, fileName: String) {
        try {
            val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
            if (!file.exists()) return
            val fileUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(fileUri, "application/octet-stream")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage(MINECRAFT_PACKAGE)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
                val fileUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(fileUri, "application/octet-stream")
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(android.content.Intent.createChooser(intent, "Abrir con Minecraft"))
            } catch (e2: Exception) {}
        }
    }
}
