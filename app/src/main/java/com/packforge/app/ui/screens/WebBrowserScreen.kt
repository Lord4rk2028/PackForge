package com.packforge.app.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.packforge.app.domain.model.OperationProgress
import com.packforge.app.ui.components.AddonSite
import com.packforge.app.ui.components.PackForgeTopBar
import com.packforge.app.ui.components.SiteSelector
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebBrowserScreen(
    title: String,
    currentUrl: String,
    initialUrl: String,
    currentSite: AddonSite,
    importError: String?,
    isImporting: Boolean,
    importProgress: OperationProgress,
    webImportSuccess: Long?,
    onBack: () -> Unit,
    onSiteSelect: (AddonSite) -> Unit,
    onUrlChanged: (String) -> Unit,
    onImportFromUrl: (String) -> Unit,
    onClearError: () -> Unit,
    webView: WebView
) {
    val context = LocalContext.current
    var isLoadingPage by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var showSuccess by remember { mutableStateOf(false) }

    // Muestra el check verde "Addon importado" y lo oculta solo tras unos segundos.
    LaunchedEffect(webImportSuccess) {
        if (webImportSuccess != null) {
            showSuccess = true
            delay(2600)
            showSuccess = false
        }
    }

    // NAVEGACIÓN INTELIGENTE: el gesto/tecla atrás del sistema primero retrocede
    // en el historial del WebView y SOLO si está en la raíz cierra el navegador.
    BackHandler(enabled = true) {
        val wv = webViewRef
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            onBack()
        }
    }

    Scaffold(
        topBar = {
            PackForgeTopBar(
                title = title,
                onBackClick = {
                    onBack()
                },
                actions = {
                    IconButton(onClick = {
                        // Recargar Inicio: cargar la URL inicial en la WebView persistente
                        webViewRef?.let { wv ->
                            if (initialUrl.isNotEmpty() && wv.url != initialUrl) {
                                wv.loadUrl(initialUrl)
                            } else {
                                wv.reload()
                            }
                        }
                    }) {
                        Icon(Icons.Default.Refresh, "Recargar Inicio")
                    }
                    IconButton(onClick = {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, webViewRef?.url?.toUri())
                        context.startActivity(intent)
                    }) {
                        Icon(Icons.Default.OpenInBrowser, "Navegador Externo")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            // ── SELECTOR DE SITIOS (cambiar de fuente sin salir) ──
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxWidth()
            ) {
                SiteSelector(
                    currentSite = currentSite,
                    onSelect = { site ->
                        if (site != currentSite) onSiteSelect(site)
                    },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                )
            }

            if (isLoadingPage) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            // ─── Estado de descarga/importación con transición suave ───
            // "loading" → barra de descarga ; "success" → check verde "Addon importado"
            AnimatedContent(
                targetState = when {
                    webImportSuccess != null && showSuccess -> "success"
                    isImporting && importProgress is OperationProgress.Loading -> "loading"
                    else -> "idle"
                },
                transitionSpec = {
                    (fadeIn(animationSpec = tween(300)) +
                     scaleIn(initialScale = 0.9f, animationSpec = tween(300)))
                        .togetherWith(fadeOut(animationSpec = tween(200)) +
                                      scaleOut(targetScale = 0.9f, animationSpec = tween(200)))
                },
                label = "webImportStatus"
            ) { state ->
                when (state) {
                    "loading" -> WebImportProgressBar(importProgress as? OperationProgress.Loading)
                    "success" -> WebImportSuccessChip()
                }
            }

            // Errores
            if (importError != null) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(text = importError, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        IconButton(onClick = onClearError) { Icon(Icons.Default.Close, null) }
                    }
                }
            }

            key(currentSite.sourceKey) {
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { ctx ->
                        // Devolvemos la WebView persistente sin configurarla aquí:
                        // factory solo se ejecuta UNA vez por slot de composición. Al
                        // cambiar de SITIO cambiamos la clave (key) y AndroidView se
                        // recrea, ejecutando de nuevo factory con la WebView del nuevo
                        // sitio; la inicialización en `update` se aplica a esa instancia.
                        webViewRef = webView
                        webView
                    },
                update = { wv ->
                    // Configurar listeners y cargar la URL solo la primera vez que
                    // aparece ESTA instancia de WebView (tag == null).
                    if (wv.tag == null) {
                        wv.tag = "configured"
                        wv.settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            setSupportMultipleWindows(false)
                            cacheMode = WebSettings.LOAD_DEFAULT
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            useWideViewPort = true
                            loadWithOverviewMode = true
                        }
                        wv.webChromeClient = object : WebChromeClient() {
                            // FIX NAVEGACIÓN: las ventanas emergentes (window.open /
                            // target="_blank" usadas por descargas y enlaces de MCPEDL,
                            // CurseForge o Modbay) se redirigen a la MISMA WebView para
                            // que no queden en una pantalla en blanco huérfana.
                            override fun onCreateWindow(
                                view: WebView?,
                                isDialog: Boolean,
                                isUserGesture: Boolean,
                                resultMsg: android.os.Message?
                            ): Boolean {
                                val mainView = wv
                                val childWebView = WebView(view?.context ?: mainView.context)
                                childWebView.settings.javaScriptEnabled = true
                                childWebView.webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        childView: WebView?,
                                        request: WebResourceRequest?
                                    ): Boolean {
                                        val url = request?.url?.toString() ?: return false
                                        if (isAddonDownloadUrl(url)) {
                                            onImportFromUrl(url)
                                        } else {
                                            mainView.loadUrl(url)
                                        }
                                        // La ventana emergente nunca carga su propio contenido:
                                        // todo se reenvía a la WebView principal visible.
                                        return true
                                    }
                                }
                                resultMsg?.obj = childWebView
                                return true
                            }
                        }
                        wv.webViewClient = object : WebViewClient() {
                            override fun onPageStarted(v: WebView?, u: String?, f: Bitmap?) {
                                isLoadingPage = true
                            }
                            override fun onPageFinished(v: WebView?, u: String?) {
                                isLoadingPage = false
                                if (u != null && u != "about:blank") {
                                    onUrlChanged(u)
                                }
                            }
                            override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?): Boolean {
                                // Solo interceptar navegaciones del frame principal: evaluar
                                // cada subrecurso (imágenes, CSS, JS) ralentiza la carga.
                                if (r?.isForMainFrame != true) return false
                                val url = r.url?.toString() ?: return false
                                val scheme = r.url?.scheme?.lowercase()

                                // FIX NAVEGACIÓN: esquemas que el WebView no puede mostrar
                                // (intent://, market://, mailto:, tel:, whatsapp://...) se
                                // abren en la app externa que los maneja.
                                if (scheme != null && scheme != "http" && scheme != "https" &&
                                    scheme != "about" && scheme != "javascript") {
                                    try {
                                        context.startActivity(
                                            android.content.Intent(android.content.Intent.ACTION_VIEW, r.url)
                                        )
                                    } catch (e: Exception) {
                                        // No hay app registrada para ese esquema: ignorar.
                                    }
                                    return true
                                }

                                if (isAddonDownloadUrl(url)) {
                                    onImportFromUrl(url)
                                    return true
                                }
                                return false
                            }
                        }
                        wv.setDownloadListener { url, _, _, _, _ ->
                            if (url != null) onImportFromUrl(url)
                        }
                        // Cargar la URL de este sitio si la WebView está vacía (primera
                        // visita, o al cambiar de fuente con una WebView nueva).
                        if (wv.url.isNullOrEmpty()) {
                            wv.loadUrl(currentUrl.ifEmpty { initialUrl })
                        }
                    }

                    // Al re-adjuntar tras volver de otra pestaña, forzar redibujado
                    // para que la superficie nativa se restaure (evita pantalla en blanco).
                    webViewRef = wv
                    try {
                        wv.onResume()
                    } catch (e: Exception) {}
                    wv.invalidate()
                }
            )
            }
        }
    }
}

/**
 * Barra de descarga compacta que aparece mientras se importa un addon de la web.
 * Muestra el progreso real cuando está disponible; si la descarga es muy rápida,
 * apenas se alcanza a ver antes de pasar al check verde.
 */
@Composable
private fun WebImportProgressBar(loading: OperationProgress.Loading?) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.5.dp,
                    progress = { loading?.progress ?: 0f }
                )
                Text(
                    text = loading?.message ?: "Descargando...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.height(6.dp))
            // Barra de descarga con progreso (determinada si hay %, indeterminada si no)
            if ((loading?.progress ?: 0f) > 0f) {
                LinearProgressIndicator(
                    progress = { (loading?.progress ?: 0f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    strokeCap = StrokeCap.Round
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    strokeCap = StrokeCap.Round
                )
            }
        }
    }
}

/**
 * Chip verde con check + "Addon importado". Se muestra tras una transición suave
 * desde la barra de descarga y desaparece por sí solo a los ~2.6 segundos.
 */
@Composable
private fun WebImportSuccessChip() {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Círculo con check
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
            Text(
                text = "Addon importado",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun isAddonDownloadUrl(url: String): Boolean {
    val lower = url.lowercase()
    return lower.endsWith(".mcaddon") || lower.endsWith(".mcpack") ||
           lower.contains(".mcaddon?") || lower.contains(".mcpack?")
}
