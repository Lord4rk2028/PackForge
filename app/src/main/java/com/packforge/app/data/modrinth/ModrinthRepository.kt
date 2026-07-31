package com.packforge.app.data.modrinth

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.packforge.app.domain.model.ModrinthMod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

class ModrinthRepository {

    private val api: ModrinthApi by lazy { defaultApi() }
    private val httpClient: OkHttpClient by lazy { defaultHttpClient() }

    suspend fun searchMods(
        query: String,
        isRecommendation: Boolean = false
    ): Result<List<ModrinthMod>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val trimmed = query.trim()
                
                // Formato de búsqueda ultra-compatible
                // Si la query es vacía, buscamos "bedrock" para las tendencias
                val finalQuery = if (trimmed.isEmpty()) "bedrock" else trimmed
                
                // 1. Intentamos con el filtro de categorías Bedrock
                val response = api.searchProjects(
                    query = finalQuery,
                    facets = "[[\"categories:bedrock\"]]",
                    limit = 20
                )
                
                var hits = response.hits.orEmpty()
                
                // 2. Si no hay nada, buscamos libre pero añadiendo "bedrock" al texto
                if (hits.isEmpty()) {
                    val fallback = api.searchProjects(
                        query = if (trimmed.isEmpty()) "minecraft bedrock" else "$trimmed bedrock",
                        limit = 20
                    )
                    hits = fallback.hits.orEmpty()
                }
                
                // 3. Si sigue vacío (caso extremo), buscamos lo más popular de Modrinth en general
                if (hits.isEmpty() && trimmed.isEmpty()) {
                    val lastResort = api.searchProjects(query = "", limit = 20)
                    hits = lastResort.hits.orEmpty()
                }

                hits.map { hit ->
                    val id = hit.projectId ?: hit.id ?: java.util.UUID.randomUUID().toString()
                    ModrinthMod(
                        projectId = id,
                        title = hit.title?.ifBlank { "Sin título" } ?: "Sin título",
                        author = hit.author?.ifBlank { "Desconocido" } ?: "Desconocido",
                        description = hit.description.orEmpty(),
                        iconUrl = hit.iconUrl,
                        slug = hit.slug ?: id,
                        downloads = hit.downloads
                    )
                }
            }
        }

    suspend fun getDownloadUrl(projectId: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val versions = api.getProjectVersions(projectId)
                if (versions.isEmpty()) {
                    throw IllegalStateException("Este proyecto no tiene versiones publicadas")
                }
                for (version in versions) {
                    val files = version.files.orEmpty()
                    val file = files.firstOrNull { file ->
                        file.filename.orEmpty().endsWith(".mcaddon", ignoreCase = true)
                    } ?: files.firstOrNull { it.primary }
                        ?: files.firstOrNull()
                    val url = file?.url?.takeIf { it.isNotBlank() }
                    if (url != null) return@runCatching url
                }
                throw IllegalStateException(
                    "No se encontró un archivo .mcaddon descargable"
                )
            }
        }

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
        private const val BASE_URL = "https://api.modrinth.com/v2/"

        private fun defaultApi(): ModrinthApi =
            Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(defaultHttpClient())
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(ModrinthApi::class.java)

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
