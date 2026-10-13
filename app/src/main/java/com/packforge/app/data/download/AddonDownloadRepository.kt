package com.packforge.app.data.download

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import androidx.core.content.FileProvider
import com.packforge.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
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
 * ── POR QUÉ FALLABAN LAS DESCARGAS (arreglo crítico) ────────────────────────
 * 1. SIN COOKIES: el cliente HTTP no compartía nada con el WebView. El usuario
 *    navega en el navegador integrado, el sitio le pone cookies de sesión, y
 *    al pedir la descarga por separado el servidor no reconocía esa sesión y
 *    devolvía una PÁGINA HTML (aviso de verificación o "inicia sesión") en vez
 *    del archivo. Esa página se guardaba como .mcaddon y, al no ser un ZIP,
 *    el parser la descartaba en silencio: parecía que "ya no se importa nada".
 *    Ahora un [CookieJar] lee y escribe las cookies del WebView.
 * 2. FALLO SILENCIOSO: si el archivo descargado no era un ZIP válido,
 *    `parseFromUri` devolvía null y no se avisaba de nada. Ahora se comprueba
 *    la firma ZIP y se explica el motivo real.
 * 3. PROGRESO EXCESIVO: se emitía un valor por cada bloque de 8 KB. Un addon
 *    de 32 MB provocaba ~4000 emisiones de estado, cada una recomponiendo la
 *    interfaz: la app se quedaba congelada durante la descarga. Ahora solo se
 *    notifica cuando cambia el porcentaje entero.
 */
class AddonDownloadRepository {

    /**
     * Puente de cookies entre el WebView y OkHttp.
     *
     * Es la pieza que faltaba: al compartir el almacén de cookies del sistema,
     * las descargas heredan la sesión que el usuario ya estableció navegando.
     */
    private val httpClient: OkHttpClient by lazy { defaultHttpClient(webViewCookieJar) }

    private val webViewCookieJar = object : CookieJar {
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val header = runCatching {
                CookieManager.getInstance().getCookie(url.toString())
            }.getOrNull() ?: return emptyList()
            return header.split(';').mapNotNull { part ->
                val trimmed = part.trim()
                if (trimmed.isEmpty()) null else Cookie.parse(url, trimmed)
            }
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            runCatching {
                val manager = CookieManager.getInstance()
                cookies.forEach { manager.setCookie(url.toString(), it.toString()) }
                manager.flush()
            }
        }
    }

    suspend fun downloadToCache(
        context: Context,
        downloadUrl: String,
        suggestedFileName: String,
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
                // Progreso troceado: solo se emite al cambiar el porcentaje
                // entero. Antes se emitía por bloque de 8 KB y miles de
                // actualizaciones seguidas bloqueaban la interfaz.
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
            // o de inicio de sesión. Antes esto se descartaba en silencio y el
            // usuario no sabía por qué "no importaba nada".
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

    /** ¿El archivo empieza por la firma ZIP ("PK\x03\x04")? */
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

        private fun defaultHttpClient(cookieJar: CookieJar): OkHttpClient =
            OkHttpClient.Builder()
                .cookieJar(cookieJar)
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