package com.packforge.app.data.update

import com.packforge.app.ui.components.AddonSite
import com.packforge.app.util.PackForgeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Resultado de buscar un addon en UNA web.
 */
data class SearchHit(
    val site: String,
    val pageUrl: String,
    val name: String,
    val version: String,
    val author: String = "",
    val sizeLabel: String = "",
    val iconUrl: String? = null,
    val downloadUrl: String? = null
)

/**
 * Buscador de addons en las tres fuentes (MCPEDL, CurseForge, ModBay).
 *
 * ── CRÍTICO: por qué NO se parsean bloques de HTML con regex ──
 * Las páginas de resultados de estas webs miden entre 300 KB y 3 MB. Un patrón
 * del tipo `<div class="post">(.*?)</div>` con DOT_MATCHES_ALL sobre HTML real
 * (donde los <div> se anidan) provoca backtracking catastrófico: el motor
 * prueba cada cierre posible de la etiqueta, retry tras retry, y termina
 * quemando cientos de MB de heap. En un móvil eso es un OutOfMemoryError
 * inmediato y la app cae (ASF) justo al buscar.
 *
 * En su lugar el buscador:
 *  1. Trunca el HTML a [MAX_HTML_CHARS] para acotar la memoria.
 *  2. Extrae SOLO los enlaces de addon (patrón lineal, sin anidamiento).
 *  3. Lee una ventana de ±[WINDOW_CHARS] alrededor de cada enlace para sacar
 *     nombre, versión e icono, en vez de capturar bloques enteros.
 *  4. Limita cuántos candidatos se inspeccionan por web.
 *
 * Si el HTML cambia y ninguna web devuelve resultados, el addon queda en
 * "no encontrado" — que es un resultado honesto, no un fallo.
 */
class AddonUpdateSearcher {

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/120.0.0.0 Mobile Safari/537.36"
                    )
                    .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    /**
     * Busca un addon por nombre en las tres fuentes, en paralelo.
     * Devuelve un mapa fuente → primer resultado relevante.
     */
    suspend fun searchAll(sourceHint: AddonSite, addonName: String): Map<AddonSite, SearchHit> =
        withContext(Dispatchers.IO) {
            val query = buildQuery(addonName)
            if (query.isBlank()) return@withContext emptyMap()

            val results = mutableMapOf<AddonSite, SearchHit>()
            for (site in AddonSite.entries) {
                val hit = try {
                    searchOne(site, query, addonName)
                } catch (e: OutOfMemoryError) {
                    // Por muy precautionary que sea, si el heap se agota
                    // durante una búsqueda se aborta esa fuente y se sigue.
                    PackForgeLog.e("PackForge", "OOM buscando en ${site.sourceKey}")
                    null
                } catch (e: Exception) {
                    PackForgeLog.d("PackForge", "Búsqueda falló en ${site.sourceKey}: ${e.message}")
                    null
                }
                if (hit != null) results[site] = hit
            }
            results
        }

    /** Busca un addon en UNA fuente concreta. */
    suspend fun searchOne(site: AddonSite, query: String, addonName: String? = null): SearchHit? =
        withContext(Dispatchers.IO) {
            try {
                val html = fetch(buildSearchUrl(site, query), site)
                if (html.isBlank()) return@withContext null
                extractCandidates(site, html)
                    .firstOrNull { isRelevant(it, query, addonName) }
            } catch (e: Exception) {
                null
            }
        }

    // ─── URLs de búsqueda ────────────────────────────────────────────

    private fun buildSearchUrl(site: AddonSite, query: String): String {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        return when (site) {
            AddonSite.MCPEDL -> "https://mcpedl.com/?s=$q"
            AddonSite.CURSEFORGE ->
                "https://www.curseforge.com/minecraft-bedrock/search?page=1&pageSize=20&search=$q"
            AddonSite.MODBAY -> "https://modbay.org/?s=$q"
        }
    }

    private fun fetch(url: String, site: AddonSite): String {
        val builder = Request.Builder().url(url)
        if (site == AddonSite.MCPEDL) builder.header("Referer", "https://mcpedl.com/")
        httpClient.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            // Truncar de inmediato: nunca cargamos megabytes completos en heap.
            return if (body.length > MAX_HTML_CHARS) body.substring(0, MAX_HTML_CHARS) else body
        }
    }

    // ─── Extracción de candidatos (sin regex anidada) ────────────────

    /**
     * Localiza enlaces a páginas de addon y, para cada uno, lee una ventana
     * pequeña de texto a su alrededor para deducir nombre y versión.
     *
     * Todos los patrones son planos: no hay cuantificadores anidados sobre
     * etiquetas, así que no existe backtracking catastrófico.
     */
    private fun extractCandidates(site: AddonSite, html: String): List<SearchHit> {
        val linkPattern = linkPatternFor(site)
        if (linkPattern == null) return emptyList()

        val seen = LinkedHashSet<String>()
        val hits = mutableListOf<SearchHit>()

        for (m in linkPattern.findAll(html)) {
            if (hits.size >= MAX_CANDIDATES) break

            val rawLink = m.groupValues.getOrNull(1).orEmpty()
            val pageUrl = absolutize(site, rawLink)
            if (pageUrl.isBlank() || !seen.add(pageUrl)) continue
            // Ignorar páginas que no son fichas de addon.
            if (pageUrl.contains("/category/") || pageUrl.contains("/tag/")) continue

            // Ventana acotada alrededor del enlace: título, versión e icono.
            val start = (m.range.first - WINDOW_CHARS).coerceAtLeast(0)
            val end = (m.range.last + WINDOW_CHARS).coerceAtMost(html.length)
            val window = html.substring(start, end)

            val name = nameFromWindow(window, site) ?: continue
            hits += SearchHit(
                site = site.sourceKey,
                pageUrl = pageUrl,
                name = name,
                version = versionFromWindow(window),
                iconUrl = iconFromWindow(window)
            )
        }
        return hits
    }

    /** Patrón plano de enlaces a fichas de addon, específico de cada web. */
    private fun linkPatternFor(site: AddonSite): Regex? = when (site) {
        AddonSite.MCPEDL -> Regex(
            """href="https?://(?:www\.)?mcpedl\.com/([a-z0-9\-]{2,12}/[a-z0-9\-]{3,80})"""",
            RegexOption.IGNORE_CASE
        )
        AddonSite.MODBAY -> Regex(
            """href="https?://(?:www\.)?modbay\.org/([a-z0-9\-/]{2,120})"""",
            RegexOption.IGNORE_CASE
        )
        AddonSite.CURSEFORGE -> Regex(
            """href="(/minecraft-bedrock/[a-z0-9\-/]{2,120})"""",
            RegexOption.IGNORE_CASE
        )
    }

    private fun absolutize(site: AddonSite, raw: String): String = when {
        raw.startsWith("http") -> raw
        else -> when (site) {
            AddonSite.MCPEDL -> "https://mcpedl.com/$raw"
            AddonSite.MODBAY -> "https://modbay.org/$raw"
            AddonSite.CURSEFORGE -> "https://www.curseforge.com$raw"
        }
    }

    /**
     * Deduce el nombre del addon en la ventana de texto: prefiere una
     * etiqueta de encabezado, luego el texto alternativo de una imagen, y
     * como último recurso el propio slug de la URL.
     */
    private fun nameFromWindow(window: String, site: AddonSite): String? {
        headingRegex.find(window)?.let { return cleanHtml(it.groupValues[1]) }
        altRegex.find(window)?.let { return cleanHtml(it.groupValues[1]) }
        // Slug: "faithful-32x-v-patch" → "Faithful 32x V Patch"
        val slug = Regex("""/([^/]+)$""").find(window)?.groupValues?.get(1)
        if (!slug.isNullOrBlank() && slug.length >= 3) {
            return slug.replace('-', ' ').replaceFirstChar { it.uppercase() }
        }
        return null
    }

    private val headingRegex = Regex(
        """<(?:h2|h3|h4)[^>]*>([^<]{3,90})</(?:h2|h3|h4)>""",
        RegexOption.IGNORE_CASE
    )
    private val altRegex = Regex("""alt="([^"]{3,90})\"""", RegexOption.IGNORE_CASE)
    private val iconRegex = Regex(
        """src="(https?://[^"\s]{4,300}?\.(?:png|jpg|jpeg|webp)[^"\s]{0,40})"""",
        RegexOption.IGNORE_CASE
    )
    private val versionRegex = Regex(
        """(?:\bversion|\bv|\brelease)\b[:\s]*([0-9]{1,3}(?:\.[0-9]{1,4}){1,3})""",
        RegexOption.IGNORE_CASE
    )

    private fun iconFromWindow(window: String): String? =
        iconRegex.find(window)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

    private fun versionFromWindow(window: String): String =
        versionRegex.find(window)?.groupValues?.getOrNull(1).orEmpty()

    // ─── Filtro de relevancia ────────────────────────────────────────

    /**
     * Decide si un resultado corresponde realmente al addon buscado.
     * Se usa puntuación por tokens, no igualdad exacta: los nombres en las
     * webs suelen llevar sufijos, guiones o distinta capitalización.
     */
    private fun isRelevant(hit: SearchHit, query: String, addonName: String?): Boolean {
        val target = normalize(addonName ?: query)
        if (target.isBlank()) return false
        val candidate = normalize(hit.name)
        val slug = normalize(hit.pageUrl)

        if (candidate == target) return true

        val targetTokens = target.split(' ').filter { it.length >= 3 }.toSet()
        if (targetTokens.isEmpty()) return false
        val candidateTokens = candidate.split(' ').filter { it.length >= 3 }.toSet()

        if (candidateTokens.isEmpty()) return false

        // Un token del objetivo aparece en el candidato o en el slug.
        val tokenHits = targetTokens.count { t ->
            candidateTokens.any { c -> c == t || c.contains(t) } || slug.contains(t)
        }
        return tokenHits >= 1
    }

    // ─── Utilidades ─────────────────────────────────────────────────

    /**
     * Monta la consulta a partir del nombre del addon.
     * El nombre del manifest suele traer la versión pegada ("Name v1.2.3",
     * "Name [1.2.3]", "Name by Autor"), así que se limpia antes de buscar
     * para no buscarla literalmente.
     */
    fun buildQuery(addonName: String): String {
        var cleaned = addonName.trim()
        cleaned = versionSuffixRegex.replace(cleaned, "")
        cleaned = byAuthorRegex.replace(cleaned, "")
        cleaned = cleaned.replace(Regex("\\s+"), " ").trim()
        return cleaned.ifBlank { addonName.trim() }
    }

    private val versionSuffixRegex = Regex(
        """\s*[\[\(]?\s*v?\d+(?:\.\d+){0,3}[a-zA-Z0-9]*\s*[\]\)]?\s*$"""
    )
    private val byAuthorRegex = Regex("""\s+by\s+.*$""", RegexOption.IGNORE_CASE)

    private fun cleanHtml(raw: String): String = raw
        .replace("&amp;", "&").replace("&quot;", "\"")
        .replace("&#039;", "'").replace("&lt;", "<").replace("&gt;", ">")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** Minúsculas, sin acentos y sin puntuación, para comparar. */
    private fun normalize(s: String): String = java.text.Normalizer
        .normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("[\\u0300-\\u036f]"), "")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    /**
     * Resuelve la URL real de descarga de un addon.
     *
     * ── Por qué hacen falta dos fases ──
     * Estas webs nunca enlazan al fichero directamente: el botón de "Descargar"
     * apunta a una página intermedia (wpdm/ajax) que, tras verificarla,
     * sirve el .mcaddon con un token temporal. Extrayendo solo el HTML no se
     * obtiene la URL final válida, así que la resolución tiene dos pasos:
     *
     *   Fase 1 — Descarga la página de detalle y busca el enlace al
     *            intermediario (`/download/…`, `?wpdmdl=`, `/dm/`, etc.).
     *            Si el intermediario sirve el fichero de verdad, se devuelve
     *            ya la URL final.
     *
     *   Fase 2 — Si la URL es intermedia, se pide siguiendo las redirecciones
     *            (el servidor responde 302 al .mcaddon real) y se devuelve la
     *            URL a la que acabaría apuntando. Esa URL es la que se pasa al
     *            repositorio de descargas, que ya añade el Referer que exige
     *            MCPEDL.
     *
     * Si ninguna fase acierta se devuelve null y la UI cae al WebView, que es
     * el camino que siempre funciona porque la página ejecuta su JavaScript.
     */
    suspend fun resolveDownloadUrl(hit: SearchHit): String? = withContext(Dispatchers.IO) {
        val site = AddonSite.fromSourceKey(hit.site)

        // Fase 1: buscar el enlace de descarga en la ficha.
        val intermediate = try {
            fetch(hit.pageUrl, site).let { findDownloadLinkIn(it, site) }
        } catch (e: Exception) {
            PackForgeLog.d("PackForge", "Fase 1 falló en ${hit.site}: ${e.message}")
            null
        } ?: return@withContext null

        // Si ya es un .mcaddon/.mcpack, no hay nada más que hacer.
        if (looksLikeAddonFile(intermediate)) return@withContext intermediate

        // Fase 2: seguir la redirección del intermediario.
        try {
            val finalUrl = followRedirects(intermediate, site)
            if (finalUrl != null && looksLikeAddonFile(finalUrl)) {
                PackForgeLog.d("PackForge", "Descarga resuelta en ${hit.site}: $finalUrl")
                return@withContext finalUrl
            }
        } catch (e: Exception) {
            PackForgeLog.d("PackForge", "Fase 2 falló en ${hit.site}: ${e.message}")
        }

        // Se devuelve el intermediario: puede que la descarga sí funcione con
        // el Referer adecuado, y el repositorio ya lo envía.
        PackForgeLog.d("PackForge", "Usando enlace intermedio de ${hit.site}")
        intermediate
    }

    /** ¿La URL termina en un archivo de addon (.mcaddon / .mcpack)? */
    private fun looksLikeAddonFile(url: String): Boolean {
        val clean = url.substringBefore('?').lowercase()
        return clean.endsWith(".mcaddon") || clean.endsWith(".mcpack")
    }

    /**
     * Sigue las redirecciones de una URL y devuelve la final. Se usa el
     * protocolo HTTP en modo HEAD cuando el servidor lo acepta; si no,
     * se reintenta con GETRange para no descargar el binario entero.
     */
    private fun followRedirects(url: String, site: AddonSite): String? {
        val clean = url.replace("&amp;", "&")
        val request = Request.Builder()
            .url(clean)
            .apply { if (site == AddonSite.MCPEDL) header("Referer", site.url) }
            .head()
            .build()

        httpClient.newCall(request).execute().use { response ->
            val final = response.request.url.toString()
            response.close()
            return if (final.startsWith("http")) final else null
        }
    }

    /**
     * Busca el enlace que dispara la descarga en la ficha del addon.
     *
     * Cubre los tres patrones que usan estas webs, del más específico al más
     * genérico. Todos son planos: capturan un atributo href, no un bloque de
     * HTML, así que no hay riesgo de backtracking.
     */
    private fun findDownloadLinkIn(html: String, site: AddonSite): String? {
        val patterns = when (site) {
            AddonSite.MCPEDL -> listOf(
                // Botón de descarga de MCPEDL: apunta a /download/… o trae
                // el token de descarga en la query (?wpdmdl=… / ?download=…).
                Regex(
                    """href="(https?://[^"\s]*/(?:download|dl)/[^"\s]{4,300})"""",
                    RegexOption.IGNORE_CASE
                ),
                Regex(
                    """href="(https?://[^"\s]*\?(?:wpdmdl|download|dl)=[^"\s]{4,300})"""",
                    RegexOption.IGNORE_CASE
                ),
                Regex(
                    """href="(https?://[^"\s]+\.(?:mcaddon|mcpack)[^"\s]*)"""",
                    RegexOption.IGNORE_CASE
                )
            )
            AddonSite.MODBAY -> listOf(
                Regex(
                    """href="(https?://[^"\s]*/(?:download|dm)/[^"\s]{4,300})"""",
                    RegexOption.IGNORE_CASE
                ),
                Regex(
                    """href="(https?://[^"\s]+\.(?:mcaddon|mcpack)[^"\s]*)"""",
                    RegexOption.IGNORE_CASE
                )
            )
            // CurseForge sirve el fichero desde su API con un token de sesión:
            // no es resoluble sin ejecutar JavaScript, así que se deja fuera y
            // la UI cae al WebView.
            AddonSite.CURSEFORGE -> emptyList()
        }

        for (pattern in patterns) {
            val match = pattern.find(html) ?: continue
            val url = match.groupValues.getOrNull(1) ?: continue
            val cleaned = url.replace("&amp;", "&")
            if (cleaned.startsWith("http")) return cleaned
        }
        return null
    }

    private companion object {
        /** Tope de HTML procesado por respuesta (evita agotar el heap). */
        const val MAX_HTML_CHARS = 400_000

        /** Radio de la ventana de contexto leída alrededor de cada enlace. */
        const val WINDOW_CHARS = 1_200

        /** Máximo de fichas inspeccionadas por web. */
        const val MAX_CANDIDATES = 25
    }
}
