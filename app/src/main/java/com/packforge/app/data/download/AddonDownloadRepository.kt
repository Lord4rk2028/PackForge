package com.packforge.app.data.download

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.packforge.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Descarga addons (.mcaddon/.mcpack) desde cualquier enlace web a la caché
 * interna de la app y devuelve un content:// URI listo para importar.
 *
 * Lo usa el navegador integrado (MCPEDL, CurseForge, ModBay): cuando el
 * usuario toca el enlace de descarga de un addon, el fichero se guarda en
 * cacheDir/downloads/ y se entrega al motor de fusión.
 *
 * ── HISTORIAL DE CORRECCIONES ───────────────────────────────────────────────
 *
 * 1. SE RETIRÓ EL `CookieJar` BASADO EN `CookieManager`.
 *    Se añadió para que las descargas heredaran la sesión del WebView, pero
 *    `CookieManager` SOLO puede usarse desde el hilo principal, y OkHttp lo
 *    invoca desde su hilo de red. Además de no funcionar, interfería con la
 *    sincronización interna de cookies del WebView y dejaba la página
 *    COMPLETAMENTE EN BLANCO al iniciar una descarga, inutilizando el
 *    navegador. Las cookies ahora se leen una sola vez, en el hilo principal,
 *    con [snapshotWebViewCookies] y se pasan al cliente como cabecera.
 *
 * 2. SIN VERIFICACIÓN SILENCIOSA: si lo descargado no era un ZIP, el parser lo
 *    descartaba sin mensaje y parecía que "la importación no hace nada". Ahora
 *    se comprueba la firma ZIP y se explica el motivo real.
 *
 * 3. PROGRESO TROCEADO: se emitía un valor por cada bloque de 8 KB, lo que en
 *    un addon de 32 MB son ~4000 emisiones de estado recomponiendo la interfaz
 *    y congelaba la app. Ahora solo se notifica al cambiar el porcentaje entero.
 */
class AddonDownloadRepository {

    private val httpClient: OkHttpClient by lazy { defaultHttpClient() }

    suspend fun downloadToCache(
        context: Context,
        downloadUrl: String,
        suggestedFileName: String,
        cookieHeader: String? = null,
        onProgress: ((Float) -> Unit)? = null
    ): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val cacheDir = File(context.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }
            val safeName = suggestedFileName
                .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                .let { name ->
                    when {
                        name.endsWith(".mcaddon", ignoreCase = true) -> name
                        name.endsWith(".mcpack", ignoreCase = true) -> name
                        name.endsWith(".zip", ignoreCase = true) -> name
                        else -> "$name.mcaddon"
                    }
                }
            val destination = File(cacheDir, safeName)
            if (destination.exists()) destination.delete()

            val requestBuilder = Request.Builder().url(downloadUrl)
            // Cada sitio exige su propio Referer; sin él responde con un aviso
            // en HTML en lugar del archivo.
            refererFor(downloadUrl)?.let { requestBuilder.header("Referer", it) }
            requestBuilder.header(
                "Accept",
                "application/octet-stream,application/zip,*/*;q=0.8"
            )
            // Cookies capturadas en el hilo principal: es lo que permite que la
            // descarga herede la sesión que el usuario ya abrió en el WebView,
            // sin tocar `CookieManager` desde este hilo de red.
            if (!cookieHeader.isNullOrBlank()) {
                requestBuilder.header("Cookie", cookieHeader)
            }
            val request = requestBuilder.build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException(
                        "El servidor respondió ${response.code}. " +
                            "Puede que el enlace haya caducado."
                    )
                }
                val body = response.body
                    ?: throw IllegalStateException("Respuesta vacía al descargar")

                val totalBytes = body.contentLength()
                var lastPercent = -1

                body.byteStream().use { input ->
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloadedBytes = 0L
                        while (true) {
                            val bytesRead = input.read(buffer)
                            if (bytesRead <= 0) break
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            if (totalBytes > 0) {
                                val percent = ((downloadedBytes * 100) / totalBytes).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress?.invoke(downloadedBytes.toFloat() / totalBytes)
                                }
                            }
                        }
                    }
                }
            }

            // Verificación decisiva: un .mcaddon/.mcpack es un ZIP, así que debe
            // empezar por "PK". Si no, lo que llegó fue una página HTML de aviso
            // o de inicio de sesión.
            if (!hasZipSignature(destination)) {
                val sizeKb = destination.length() / 1024
                destination.delete()
                throw IllegalStateException(
                    "El enlace no devolvió un addon (llegaron $sizeKb KB de una " +
                        "página web). Abre la página del addon y usa su botón de " +
                        "descarga directa."
                )
            }

            FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                destination
            )
        }
    }

    /** ¿El archivo empieza por la firma ZIP ("PK")? */
    private fun hasZipSignature(file: File): Boolean = try {
        file.inputStream().use { input ->
            val header = ByteArray(4)
            val read = input.read(header)
            read >= 2 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
        }
    } catch (_: Throwable) {
        false
    }

    companion object {
        /** Subcarpeta de cacheDir donde se guardan los addons descargados. */
        const val CACHE_DIR_NAME = "downloads"

        /**
         * Copia las cookies de una URL desde el WebView.
         *
         * DEBE invocarse en el hilo principal: `CookieManager` no es
         * thread-safe y usarlo desde otro hilo corrompe su estado interno y
         * deja el WebView en blanco.
         */
        fun snapshotWebViewCookies(url: String): String? = try {
            android.webkit.CookieManager.getInstance().getCookie(url)
        } catch (_: Throwable) {
            null
        }

        /**
         * Referer adecuado según el origen del enlace.
         *
         * Los CDN de CurseForge y MCPEDL rechazan peticiones sin Referer, y cada
         * uno espera el suyo: enviar el de MCPEDL a CurseForge no sirve.
         */
        private fun refererFor(url: String): String? = when {
            url.contains("mcpedl.com", ignoreCase = true) -> "https://mcpedl.com/"
            url.contains("curseforge.com", ignoreCase = true) -> "https://www.curseforge.com/"
            url.contains("forgecdn.net", ignoreCase = true) -> "https://www.curseforge.com/"
            url.contains("modbay.org", ignoreCase = true) -> "https://modbay.org/"
            else -> null
        }

        private fun defaultHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header(
                            "User-Agent",
                            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 " +
                                "PackForge/${BuildConfig.VERSION_NAME}"
                        )
                        .build()
                    chain.proceed(request)
                }
                .build()
    }
}