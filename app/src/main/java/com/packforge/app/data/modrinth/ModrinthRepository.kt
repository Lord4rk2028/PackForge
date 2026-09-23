package com.packforge.app.data.modrinth

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class ModrinthRepository {

    private val httpClient: OkHttpClient by lazy { defaultHttpClient() }

    suspend fun downloadToCache(
        context: Context,
        downloadUrl: String,
        suggestedFileName: String,
        onProgress: ((Float) -> Unit)? = null
    ): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val cacheDir = File(context.cacheDir, "modrinth").apply { mkdirs() }
            val safeName = suggestedFileName
                .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                .let { name ->
                    when {
                        name.endsWith(".mcaddon", ignoreCase = true) -> name
                        name.endsWith(".mcpack", ignoreCase = true) -> name
                        else -> "$name.mcaddon"
                    }
                }
            val destination = File(cacheDir, safeName)
            if (destination.exists()) destination.delete()

            val requestBuilder = Request.Builder().url(downloadUrl)
            if (downloadUrl.contains("mcpedl.com", ignoreCase = true)) {
                requestBuilder.header("Referer", "https://mcpedl.com/")
            }
            val request = requestBuilder.build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException(
                        "Error al descargar (${response.code})"
                    )
                }
                val body = response.body
                    ?: throw IllegalStateException("Respuesta vacía al descargar")
                
                val totalBytes = body.contentLength()
                body.byteStream().use { input ->
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var downloadedBytes = 0L
                        
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            if (totalBytes > 0) {
                                onProgress?.invoke(downloadedBytes.toFloat() / totalBytes)
                            }
                        }
                    }
                }
            }

            FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                destination
            )
        }
    }

    companion object {
        private fun defaultHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header(
                            "User-Agent",
                            "PackForge/1.0 (com.packforge.app; Android)"
                        )
                        .build()
                    chain.proceed(request)
                }
                .build()
    }
}
